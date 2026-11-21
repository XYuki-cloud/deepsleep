# Build and sign the self-contained Android app using the installed SDK and JDK.
[CmdletBinding()]
param(
    [string] $AndroidSdk,
    [string] $Jdk,
    [string] $OutputApk
)

$ErrorActionPreference = 'Stop'
$localAppData = $env:LOCALAPPDATA
if (-not $localAppData) {
    $localAppData = [Environment]::GetFolderPath('LocalApplicationData')
}

function Invoke-Checked {
    param([string] $Executable, [string[]] $Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$(Split-Path -Leaf $Executable) exited with code $LASTEXITCODE."
    }
}

if (-not $AndroidSdk) {
    $AndroidSdk = @(
        $env:ANDROID_HOME
        $env:ANDROID_SDK_ROOT
        (Join-Path $localAppData 'Android\Sdk')
    ) | Where-Object {
        $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platforms\android-35\android.jar'))
    } | Select-Object -First 1
}
if (-not $AndroidSdk) {
    throw 'Set ANDROID_HOME or pass -AndroidSdk to a SDK with Android platform 35 and build-tools 35.0.0.'
}

if (-not $Jdk) {
    $Jdk = @(
        $env:JAVA_HOME
        (Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'Android\Android Studio\jbr')
    ) | Where-Object {
        $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe'))
    } | Select-Object -First 1
}
if (-not $Jdk) {
    throw 'Set JAVA_HOME or pass -Jdk to JDK 17 or newer.'
}

$sdkJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
$tools = Join-Path $AndroidSdk 'build-tools\35.0.0'
$aapt2 = Join-Path $tools 'aapt2.exe'
$zipalign = Join-Path $tools 'zipalign.exe'
$d8 = Join-Path $tools 'lib\d8.jar'
$apksigner = Join-Path $tools 'lib\apksigner.jar'
$java = Join-Path $Jdk 'bin\java.exe'
$javac = Join-Path $Jdk 'bin\javac.exe'
$jar = Join-Path $Jdk 'bin\jar.exe'
$keytool = Join-Path $Jdk 'bin\keytool.exe'
$librariesDir = Join-Path $PSScriptRoot 'app\libs'
$commonmarkJars = @('commonmark-0.32.0.jar', 'commonmark-ext-gfm-tables-0.32.0.jar')
$commonmarkLicense = Join-Path $librariesDir 'commonmark-LICENSE.txt'
$projectLicense = Join-Path $PSScriptRoot 'LICENSE'
$libraries = @($commonmarkJars | ForEach-Object { Join-Path $librariesDir $_ })
foreach ($file in @($sdkJar, $aapt2, $zipalign, $d8, $apksigner, $java, $javac, $jar, $keytool, $commonmarkLicense, $projectLicense) + $libraries) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Missing build dependency: $file"
    }
}

$source = Join-Path $PSScriptRoot 'app\src\main'
$manifestSource = Join-Path $source 'AndroidManifest.xml'
[xml] $manifestXml = Get-Content -LiteralPath $manifestSource -Encoding UTF8 -Raw
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$minSdk = $manifestXml.manifest.'uses-sdk'.GetAttribute('minSdkVersion', $androidNamespace)
$version = $manifestXml.manifest.GetAttribute('versionName', $androidNamespace)
if (-not $OutputApk) {
    $OutputApk = Join-Path $PSScriptRoot "dist\deepsleep-$version.apk"
}
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputApk)

$staging = Join-Path ([IO.Path]::GetTempPath()) ('simpledeep-' + [guid]::NewGuid().ToString('N'))
$stagedSource = Join-Path $staging 'main'
$stagedLibraries = Join-Path $staging 'libraries'
$classes = Join-Path $staging 'classes'
$generated = Join-Path $staging 'generated'
$dex = Join-Path $staging 'dex'
$resourceZip = Join-Path $staging 'res.zip'
$unpackedApk = Join-Path $staging 'linked.apk'
$compiledJar = Join-Path $staging 'classes.jar'
$alignedApk = Join-Path $staging 'aligned.apk'
$signedApk = Join-Path $staging 'signed.apk'

$signingDir = Join-Path $localAppData 'SimpleDeepChat\signing'
$keystore = Join-Path $signingDir 'release.p12'
$passwordFile = Join-Path $signingDir 'release.dpapi'
$passwordVariable = 'SIMPLEDEEP_CHAT_BUILD_SIGNING_PASSWORD'
$previousPasswordValue = [Environment]::GetEnvironmentVariable($passwordVariable)
$signingLock = $null
$outputTemp = $null

try {
    New-Item -ItemType Directory -Path $classes, $generated, $dex, $stagedLibraries -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $stagedSource -Recurse
    Copy-Item -LiteralPath $libraries -Destination $stagedLibraries
    $programJars = @($commonmarkJars | ForEach-Object { Join-Path $stagedLibraries $_ })
    $compileClasspath = (@($sdkJar) + $programJars) -join [IO.Path]::PathSeparator

    Invoke-Checked $aapt2 @('compile', '--dir', (Join-Path $stagedSource 'res'), '-o', $resourceZip)
    Invoke-Checked $aapt2 @(
        'link', '-I', $sdkJar, '--manifest', (Join-Path $stagedSource 'AndroidManifest.xml'),
        '--java', $generated, '-o', $unpackedApk, $resourceZip
    )

    $javaSources = Get-ChildItem -LiteralPath (Join-Path $stagedSource 'java'), $generated -Recurse -Filter '*.java' -File
    Invoke-Checked $javac (@('-encoding', 'UTF-8', '--release', '8', '-Xlint:-options', '-cp', $compileClasspath, '-d', $classes) + $javaSources.FullName)
    Invoke-Checked $jar @('--create', '--file', $compiledJar, '-C', $classes, '.')
    Invoke-Checked $java (@(
        '-cp', $d8, 'com.android.tools.r8.D8', '--release', '--min-api', $minSdk,
        '--lib', $sdkJar, '--output', $dex, $compiledJar
    ) + $programJars)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apkZip = [IO.Compression.ZipFile]::Open($unpackedApk, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($file in (Get-ChildItem -LiteralPath $dex -Filter '*.dex' -File)) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apkZip, $file.FullName, $file.Name) | Out-Null
        }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apkZip, $commonmarkLicense, 'META-INF/LICENSE-commonmark.txt') | Out-Null
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apkZip, $projectLicense, 'META-INF/LICENSE-deepsleep.txt') | Out-Null
    } finally {
        $apkZip.Dispose()
    }
    Invoke-Checked $zipalign @('-P', '16', '-f', '4', $unpackedApk, $alignedApk)

    New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
    $signingLock = [IO.File]::Open((Join-Path $signingDir 'build.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    if (-not (Test-Path -LiteralPath $keystore)) {
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $secretBytes = New-Object byte[] 24
            $random.GetBytes($secretBytes)
        } finally {
            $random.Dispose()
        }
        $password = [Convert]::ToBase64String($secretBytes)
        [Environment]::SetEnvironmentVariable($passwordVariable, $password)
        $secret = ConvertTo-SecureString $password -AsPlainText -Force
        $encryptedSecret = ConvertFrom-SecureString $secret
        $keystoreTemp = Join-Path $staging 'release.p12'
        Invoke-Checked $keytool @(
            '-genkeypair', '-noprompt', '-storetype', 'PKCS12', '-keystore', $keystoreTemp,
            '-storepass:env', $passwordVariable, '-alias', 'release', '-keyalg', 'RSA',
            '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Simple Deep Chat'
        )
        Set-Content -LiteralPath $passwordFile -Value $encryptedSecret -Encoding ASCII
        Move-Item -LiteralPath $keystoreTemp -Destination $keystore
    } else {
        $secret = ConvertTo-SecureString (Get-Content -LiteralPath $passwordFile -Raw).Trim()
        $credential = [System.Management.Automation.PSCredential]::new('release', $secret)
        [Environment]::SetEnvironmentVariable($passwordVariable, $credential.GetNetworkCredential().Password)
    }

    Invoke-Checked $java @(
        '-jar', $apksigner, 'sign', '--ks', $keystore, '--ks-type', 'PKCS12',
        '--ks-key-alias', 'release', '--ks-pass', "env:$passwordVariable",
        '--out', $signedApk, $alignedApk
    )
    Invoke-Checked $java @('-jar', $apksigner, 'verify', '--verbose', $signedApk)
    Invoke-Checked $zipalign @('-c', '-P', '16', '4', $signedApk)

    $outputDir = Split-Path -Parent $outputPath
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    $outputTemp = Join-Path $outputDir ('.' + [guid]::NewGuid().ToString('N') + '.apk.tmp')
    Copy-Item -LiteralPath $signedApk -Destination $outputTemp
    Move-Item -LiteralPath $outputTemp -Destination $outputPath -Force
    Get-FileHash -LiteralPath $outputPath -Algorithm SHA256 | Format-List Hash, Path
} finally {
    [Environment]::SetEnvironmentVariable($passwordVariable, $previousPasswordValue)
    if ($signingLock) {
        $signingLock.Dispose()
    }
    if ($outputTemp -and (Test-Path -LiteralPath $outputTemp)) {
        Remove-Item -LiteralPath $outputTemp -Force
    }
    if ((Split-Path -Parent $staging) -eq [IO.Path]::GetTempPath().TrimEnd('\') -and
        (Split-Path -Leaf $staging) -match '^simpledeep-[a-f0-9]{32}$' -and
        (Test-Path -LiteralPath $staging -PathType Container)) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
}

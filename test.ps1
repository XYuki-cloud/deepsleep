# Run the JVM and JavaScript regression suites without Android or a DeepSeek account.
[CmdletBinding()]
param(
    [string] $AndroidSdk,
    [string] $Jdk,
    [string] $JsonJar
)

$ErrorActionPreference = 'Stop'

function Invoke-Checked {
    param([string] $Executable, [string[]] $Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$(Split-Path -Leaf $Executable) exited with code $LASTEXITCODE."
    }
}

$localAppData = $env:LOCALAPPDATA
if (-not $localAppData) {
    $localAppData = [Environment]::GetFolderPath('LocalApplicationData')
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
    throw 'Set ANDROID_HOME or pass -AndroidSdk to an SDK with Android platform 35.'
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

$androidJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
$java = Join-Path $Jdk 'bin\java.exe'
$javac = Join-Path $Jdk 'bin\javac.exe'
foreach ($file in @($androidJar, $java, $javac)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Missing test dependency: $file"
    }
}
$node = Get-Command 'node.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $node) {
    throw 'Install Node.js and add it to PATH.'
}

# Keep Android's stub jar behind a real org.json implementation on the JVM classpath.
$jsonVersion = '20160810'
$jsonName = "json-$jsonVersion.jar"
$jsonSha256 = 'BF51C9013128CB15201225E51476F60AD9116813729040655A238D2829AEF8B8'
$jsonUri = "https://repo.maven.apache.org/maven2/org/json/json/$jsonVersion/$jsonName"
if (-not $JsonJar) {
    $profileDir = [Environment]::GetFolderPath('UserProfile')
    $gradleDir = $env:GRADLE_USER_HOME
    if (-not $gradleDir) {
        $gradleDir = Join-Path $profileDir '.gradle'
    }
    $JsonJar = @(
        (Join-Path $profileDir ".m2\repository\org\json\json\$jsonVersion\$jsonName")
        (Join-Path $gradleDir "caches\modules-2\files-2.1\org.json\json\$jsonVersion\aca5eb39e2a12fddd6c472b240afe9ebea3a6733\$jsonName")
    ) | Where-Object {
        (Test-Path -LiteralPath $_ -PathType Leaf) -and (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash -eq $jsonSha256
    } | Select-Object -First 1
}

$tests = @('ConversationStoreTest', 'DeepSeekApiTest', 'DeepSeekFilesTest', 'KeyTextTest', 'DeepSeekHistoryReaderTest', 'HistoryArchiveTest', 'HistoryExportDownloadTest', 'PlatformRouteTest', 'NativeExportStepsTest')
$testDir = Join-Path $PSScriptRoot 'tests'
$javaDir = Join-Path $PSScriptRoot 'app\src\main\java\com\simpledeep\chat'
$sources = @('ConversationStore', 'DeepSeekApi', 'DeepSeekFiles', 'KeyText', 'DeepSeekHistoryReader', 'HistoryArchive', 'HistoryExportDownload', 'PlatformRoute', 'NativeExportSteps') | ForEach-Object {
    Join-Path $javaDir "$_.java"
}
$testSources = $tests | ForEach-Object { Join-Path $testDir "$_.java" }

$tempRoot = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).Path.TrimEnd('\')
$stagingName = 'simpledeep-tests-' + [guid]::NewGuid().ToString('N')
$staging = Join-Path $tempRoot $stagingName
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    $stagedJson = Join-Path $staging $jsonName
    if ($JsonJar) {
        Copy-Item -LiteralPath $JsonJar -Destination $stagedJson
    } else {
        Write-Host "Downloading org.json:json:$jsonVersion from Maven Central."
        Invoke-WebRequest -Uri $jsonUri -OutFile $stagedJson -UseBasicParsing -TimeoutSec 30
    }
    if ((Get-FileHash -LiteralPath $stagedJson -Algorithm SHA256).Hash -ne $jsonSha256) {
        throw "Unexpected SHA-256 for $jsonName. Use the pinned Maven Central artifact: $jsonUri"
    }

    $classes = Join-Path $staging 'classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    $stagedSources = foreach ($source in ($sources + $testSources)) {
        $stagedSource = Join-Path $staging (Split-Path -Leaf $source)
        Copy-Item -LiteralPath $source -Destination $stagedSource
        $stagedSource
    }
    Invoke-Checked $javac (@('-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
        '-cp', "$stagedJson;$androidJar", '-d', $classes) + $stagedSources)
    foreach ($test in $tests) {
        Invoke-Checked $java @('-ea', "-Djava.io.tmpdir=$staging", '-cp', "$classes;$stagedJson;$androidJar",
            "com.simpledeep.chat.$test")
    }
    Invoke-Checked $node.Source @((Join-Path $testDir 'official_key_flow_test.js'))
} finally {
    $resolved = Resolve-Path -LiteralPath $staging -ErrorAction SilentlyContinue
    if ($resolved -and (Split-Path -Parent $resolved.Path) -eq $tempRoot -and
        (Split-Path -Leaf $resolved.Path) -eq $stagingName) {
        Remove-Item -LiteralPath $resolved.Path -Recurse -Force
    }
}

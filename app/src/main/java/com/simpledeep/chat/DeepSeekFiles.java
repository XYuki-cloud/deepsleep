package com.simpledeep.chat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Uploads an attachment to the official DeepSeek Files API. */
public final class DeepSeekFiles {
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_FILENAME_BYTES = 512;

    private DeepSeekFiles() { }

    public static boolean isImage(String fileName, String mimeType) throws IOException {
        return Format.find(fileName, mimeType).kind == Kind.IMAGE;
    }

    public static long sizeLimit(String fileName, String mimeType) throws IOException {
        return Format.find(fileName, mimeType).kind.maxBytes;
    }

    /** A negative length means the document provider did not report a size. */
    public static void validate(String fileName, String mimeType, long length) throws IOException {
        multipartFilename(fileName);
        Format format = Format.find(fileName, mimeType);
        validateLength(length, format.kind);
    }

    /** The caller owns and closes the input stream. This method blocks until upload and parsing finish. */
    public static String upload(String apiKey, InputStream input, String fileName, String mimeType, long length,
                                DeepSeekApi.Cancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        if (input == null) throw new DeepSeekApi.ApiException(0, "无法打开附件，请重新选择。");

        String filename = multipartFilename(fileName);
        Format format = Format.find(fileName, mimeType);
        validateLength(length, format.kind);
        String boundary = "SimpleDeep-" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                + format.kind.purpose + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + format.mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = null;
        try {
            connection = DeepSeekApi.open("/files", apiKey, "POST", "application/json", 250_000);
            cancellation.bind(connection);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (length >= 0) {
                connection.setFixedLengthStreamingMode(length + prefix.length + suffix.length);
            } else {
                connection.setChunkedStreamingMode(BUFFER_SIZE);
            }
            try (OutputStream output = connection.getOutputStream()) {
                output.write(prefix);
                copy(input, output, length, format.kind, cancellation);
                cancellation.throwIfCancelled();
                output.write(suffix);
            }
            checkResponse(connection.getResponseCode());
            cancellation.throwIfCancelled();
            try (InputStream response = connection.getInputStream()) {
                String id = readFileId(response, cancellation);
                cancellation.throwIfCancelled();
                return id;
            }
        } catch (IOException error) {
            cancellation.throwIfCancelled();
            throw DeepSeekApi.networkError(error);
        } finally {
            if (connection != null) {
                cancellation.unbind(connection);
                connection.disconnect();
            }
        }
    }

    private static String multipartFilename(String fileName) throws IOException {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new DeepSeekApi.ApiException(0, "无法读取附件名称，请重新选择。");
        }
        String filename = fileName.replace("\r", "%0D").replace("\n", "%0A").replace("\"", "%22")
                .replace('/', '_').replace('\\', '_').replaceAll("\\p{Cc}", "_");
        if (filename.getBytes(StandardCharsets.UTF_8).length > MAX_FILENAME_BYTES) {
            throw new DeepSeekApi.ApiException(0, "附件名称过长，请重命名后再发送。");
        }
        return filename;
    }

    private static void validateLength(long length, Kind kind) throws IOException {
        if (length == 0) throw new DeepSeekApi.ApiException(0, "附件是空文件。");
        if (length > kind.maxBytes) {
            throw new DeepSeekApi.ApiException(0, "此类附件最大支持 " + kind.maxMiB + " MiB。");
        }
    }

    private static void copy(InputStream input, OutputStream output, long length, Kind kind,
                             DeepSeekApi.Cancellation cancellation) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long limit = length >= 0 ? length : kind.maxBytes;
        long copied = 0;
        while (true) {
            cancellation.throwIfCancelled();
            int count;
            try {
                count = input.read(buffer, 0, (int) Math.min(buffer.length, limit - copied + 1));
            } catch (IOException | SecurityException error) {
                throw new DeepSeekApi.ApiException(0, "读取附件失败，请重新选择。");
            }
            cancellation.throwIfCancelled();
            if (count < 0) break;
            if (count == 0) throw new DeepSeekApi.ApiException(0, "读取附件失败，请重新选择。");
            copied += count;
            validateLength(copied, kind);
            if (copied > limit) throw changedLength();
            output.write(buffer, 0, count);
        }
        if (length >= 0 && copied != length) throw changedLength();
        validateLength(copied, kind);
    }

    private static DeepSeekApi.ApiException changedLength() {
        return new DeepSeekApi.ApiException(0, "附件大小与选择时不一致，请重新选择。");
    }

    private static String readFileId(InputStream input, DeepSeekApi.Cancellation cancellation) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            cancellation.throwIfCancelled();
            if (count == 0 || response.size() + count > MAX_RESPONSE_BYTES) break;
            response.write(buffer, 0, count);
        }
        if (count == -1) {
            try {
                String id = new JSONObject(response.toString("UTF-8")).getString("id");
                if (id.matches("file-api-[A-Za-z0-9_-]+")) return id;
            } catch (JSONException ignored) {
                // Do not include the server body in the error shown to the user.
            }
        }
        throw new DeepSeekApi.ApiException(0, "DeepSeek 没有返回有效的文件标识。");
    }

    private static void checkResponse(int status) throws DeepSeekApi.ApiException {
        if (status >= 200 && status < 300) return;
        switch (status) {
            case 400:
            case 415:
            case 422:
                throw new DeepSeekApi.ApiException(status, "DeepSeek 无法处理此附件。请检查文件格式，以及文件是否加密或损坏。");
            case 401:
            case 403:
                throw new DeepSeekApi.ApiException(status, "DeepSeek 验证未通过，请检查 API Key 和访问权限。");
            case 402:
                throw new DeepSeekApi.ApiException(status, "DeepSeek API 余额不足，请到开放平台充值。");
            case 408:
                throw new DeepSeekApi.ApiException(status, "附件上传超时，请检查网络或选择更小的文件。");
            case 413:
                throw new DeepSeekApi.ApiException(status, "附件超过 DeepSeek 的文件大小或可解析内容限制。");
            case 429:
                throw new DeepSeekApi.ApiException(status, "附件上传请求过于频繁，请稍后重试。");
            case 500:
            case 503:
                throw new DeepSeekApi.ApiException(status, "DeepSeek 暂时无法处理附件，请稍后重试。");
            case 504:
                throw new DeepSeekApi.ApiException(status, "DeepSeek 解析附件超时，可以选择更小的文件。");
            default:
                throw new DeepSeekApi.ApiException(status, "DeepSeek 附件上传失败（HTTP " + status + "）。");
        }
    }

    private enum Kind {
        IMAGE("user_data", 64),
        DOCUMENT("rich_layout_recognition", 100),
        TEXT_OR_TABLE("user_data", 32);

        final String purpose;
        final int maxMiB;
        final long maxBytes;

        Kind(String purpose, int maxMiB) {
            this.purpose = purpose;
            this.maxMiB = maxMiB;
            maxBytes = maxMiB * 1024L * 1024L;
        }
    }

    private enum Format {
        JPEG(Kind.IMAGE, "image/jpeg", "jpg", "jpeg"),
        PNG(Kind.IMAGE, "image/png", "png"),
        GIF(Kind.IMAGE, "image/gif", "gif"),
        WEBP(Kind.IMAGE, "image/webp", "webp"),
        TIFF(Kind.DOCUMENT, "image/tiff", "tif", "tiff"),
        BMP(Kind.DOCUMENT, "image/bmp", "bmp"),
        PDF(Kind.DOCUMENT, "application/pdf", "pdf"),
        DOC(Kind.DOCUMENT, "application/msword", "doc"),
        DOCX(Kind.DOCUMENT, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        PPT(Kind.DOCUMENT, "application/vnd.ms-powerpoint", "ppt"),
        PPTX(Kind.DOCUMENT, "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
        XLS(Kind.TEXT_OR_TABLE, "application/vnd.ms-excel", "xls"),
        XLSX(Kind.TEXT_OR_TABLE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        EPUB(Kind.TEXT_OR_TABLE, "application/epub+zip", "epub"),
        MOBI(Kind.TEXT_OR_TABLE, "application/x-mobipocket-ebook", "mobi"),
        MARKDOWN(Kind.TEXT_OR_TABLE, "text/markdown", "md", "markdown"),
        CSV(Kind.TEXT_OR_TABLE, "text/csv", "csv"),
        JSON(Kind.TEXT_OR_TABLE, "application/json", "json"),
        XML(Kind.TEXT_OR_TABLE, "application/xml", "xml"),
        TEXT(Kind.TEXT_OR_TABLE, "text/plain", "txt", "text", "log", "tsv", "jsonl", "yaml", "yml", "toml",
                "ini", "html", "htm", "css", "java", "kt", "js", "jsx", "ts", "tsx", "py", "c", "h", "cpp", "hpp",
                "cs", "go", "rs", "swift", "sh", "ps1", "sql", "rb", "php", "lua", "r", "tex");

        final Kind kind;
        final String mimeType;
        final String[] extensions;

        Format(Kind kind, String mimeType, String... extensions) {
            this.kind = kind;
            this.mimeType = mimeType;
            this.extensions = extensions;
        }

        static Format find(String fileName, String mimeType) throws IOException {
            String name = fileName == null ? "" : fileName.trim();
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String suffix = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                for (Format format : values()) {
                    for (String extension : format.extensions) {
                        if (extension.equals(suffix)) return format;
                    }
                }
            }
            if (mimeType != null) {
                String mime = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                for (Format format : values()) {
                    if (format.mimeType.equals(mime)) return format;
                }
                if (mime.matches("text/[a-z0-9!#$&^_.+-]+")) return TEXT;
            }
            throw new DeepSeekApi.ApiException(0,
                    "暂不支持此附件格式。可选择常见图片、PDF、Word、PowerPoint、Excel、电子书或纯文本文件。");
        }
    }
}

package com.simpledeep.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class DeepSeekFilesTest {
    private static final String KEY = "sk-example-file-test";
    private static final String FILE_ID = "file-api-0123456789abcdef";
    private static final String SUCCESS = "{\"id\":\"" + FILE_ID + "\",\"object\":\"file\",\"bytes\":4,"
            + "\"created_at\":1700000000,\"filename\":\"report.pdf\",\"purpose\":\"rich_layout_recognition\"}";
    private static final long MIB = 1024L * 1024L;

    private static FakeConnection pending;

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> {
            if (!"https".equals(protocol)) return null;
            return new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) {
                    FakeConnection connection = pending;
                    if (connection == null) throw new AssertionError("Unexpected request: " + url);
                    pending = null;
                    connection.requestedUrl = url;
                    return connection;
                }
            };
        });

        sendsPurposeBeforeFileAndPreservesBytes();
        appliesFormatLimitsBeforeOpeningAConnection();
        uploadsUnknownLengthWithChunkedStreaming();
        boundsBytesReadForAnUnknownLength();
        rejectsChangedLength();
        reportsUploadErrorsWithoutReadingServerBodies();
        rejectsInvalidFileId();
        disconnectsWhenCancelled();
        System.out.println("DeepSeek Files protocol tests passed (8 cases)");
    }

    private static void sendsPurposeBeforeFileAndPreservesBytes() throws Exception {
        byte[] content = {0, 1, (byte) 0xf1, 10};
        FakeConnection connection = reply(200, SUCCESS);
        same(FILE_ID, upload(new ByteArrayInputStream(content), "文\"档\r\n.pdf", "application/octet-stream",
                content.length, new DeepSeekApi.Cancellation()));

        same("https://api.deepseek.com/files", connection.requestedUrl.toString());
        same("POST", connection.getRequestMethod());
        same(false, connection.getInstanceFollowRedirects());
        same("Bearer " + KEY, connection.getRequestProperty("Authorization"));
        same("application/json", connection.getRequestProperty("Accept"));
        String header = connection.getRequestProperty("Content-Type");
        check(header.startsWith("multipart/form-data; boundary="));
        String boundary = header.substring(header.indexOf('=') + 1);
        String start = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                + "rich_layout_recognition\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"文%22档%0D%0A.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n";
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(start.getBytes(StandardCharsets.UTF_8));
        expected.write(content);
        expected.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        check(Arrays.equals(expected.toByteArray(), connection.output.toByteArray()));
        same((long) expected.size(), connection.fixedLength());
        same(true, connection.disconnected);
    }

    private static void appliesFormatLimitsBeforeOpeningAConnection() throws Exception {
        byte[] content = {1};
        for (String fileName : new String[] {"scan.JPEG", "image.png", "animation.gif", "image.webp"}) {
            same(64 * MIB, DeepSeekFiles.sizeLimit(fileName, "application/octet-stream"));
            same(true, DeepSeekFiles.isImage(fileName, "application/octet-stream"));
        }
        for (String fileName : new String[] {"report.PDF", "text.doc", "text.docx", "slides.ppt", "slides.pptx",
                "scan.tiff", "scan.bmp"}) {
            same(100 * MIB, DeepSeekFiles.sizeLimit(fileName, null));
            same(false, DeepSeekFiles.isImage(fileName, null));
        }
        for (String fileName : new String[] {"notes.txt", "README.md", "data.csv", "data.json", "Main.java",
                "budget.xlsx", "budget.xls", "readable.epub", "readable.mobi"}) {
            same(32 * MIB, DeepSeekFiles.sizeLimit(fileName, "application/octet-stream"));
            same(false, DeepSeekFiles.isImage(fileName, "application/octet-stream"));
        }
        same(32 * MIB, DeepSeekFiles.sizeLimit("unnamed", "text/plain"));
        same(64 * MIB, DeepSeekFiles.sizeLimit("unnamed", "image/jpeg"));
        DeepSeekFiles.validate("文档.pdf", "application/octet-stream", 100 * MIB);
        DeepSeekFiles.validate("文档.pdf", "application/octet-stream", -1);
        expectError("格式", () -> DeepSeekFiles.validate("photo.heic", "image/heic", 1));
        expectError("格式", () -> upload(new ByteArrayInputStream(content), "photo.heic", "image/heic", 1,
                new DeepSeekApi.Cancellation()));
        expectError("32 MiB", () -> upload(new ByteArrayInputStream(content), "notes.txt", null, 32 * MIB + 1,
                new DeepSeekApi.Cancellation()));
        expectError("空", () -> upload(new ByteArrayInputStream(new byte[0]), "notes.txt", "text/plain", 0,
                new DeepSeekApi.Cancellation()));
    }

    private static void uploadsUnknownLengthWithChunkedStreaming() throws Exception {
        for (String filename : new String[] {"screen.png", "notes.json", "budget.xlsx"}) {
            FakeConnection connection = reply(200, SUCCESS);
            same(FILE_ID, upload(new ByteArrayInputStream(new byte[] {1}), filename, null, -1,
                    new DeepSeekApi.Cancellation()));
            check(connection.isChunked());
            check(connection.output.toString("UTF-8").contains("\r\n\r\nuser_data\r\n"));
        }
    }

    private static void boundsBytesReadForAnUnknownLength() throws Exception {
        FakeConnection connection = reply(200, SUCCESS);
        connection.discardBody = true;
        final long[] bytesRead = {0};
        InputStream input = new InputStream() {
            @Override
            public int read() {
                bytesRead[0]++;
                return 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                bytesRead[0] += length;
                return length;
            }
        };
        expectError("32 MiB", () -> upload(input, "notes.txt", null, -1, new DeepSeekApi.Cancellation()));
        same(32 * MIB + 1, bytesRead[0]);
        same(false, connection.readResponse);
        same(true, connection.disconnected);
    }

    private static void rejectsChangedLength() throws Exception {
        FakeConnection connection = reply(200, SUCCESS);
        expectError("大小", () -> upload(new ByteArrayInputStream(new byte[] {1}), "notes.txt", null, 2,
                new DeepSeekApi.Cancellation()));
        same(false, connection.readResponse);
        same(true, connection.disconnected);
    }

    private static void reportsUploadErrorsWithoutReadingServerBodies() throws Exception {
        FakeConnection connection = reply(413, "server response containing " + KEY);
        expectError("附件", () -> upload(new ByteArrayInputStream(new byte[] {1}), "report.pdf", null, 1,
                new DeepSeekApi.Cancellation()));
        same(true, connection.disconnected);
    }

    private static void rejectsInvalidFileId() throws Exception {
        reply(200, "{\"id\":\"malformed\"}");
        expectError("文件标识", () -> upload(new ByteArrayInputStream(new byte[] {1}), "scan.png", null, 1,
                new DeepSeekApi.Cancellation()));
    }

    private static void disconnectsWhenCancelled() throws Exception {
        DeepSeekApi.Cancellation cancellation = new DeepSeekApi.Cancellation();
        FakeConnection connection = reply(200, SUCCESS);
        connection.beforeResponse = cancellation::cancel;
        expectError("已停止", () -> upload(new ByteArrayInputStream(new byte[] {1}), "scan.png", null, 1,
                cancellation));
        same(true, connection.disconnected);
    }

    private static String upload(InputStream input, String filename, String mime, long size,
                                 DeepSeekApi.Cancellation cancellation) throws Exception {
        return DeepSeekFiles.upload(KEY, input, filename, mime, size, cancellation);
    }

    private static FakeConnection reply(int status, String body) throws Exception {
        check(pending == null);
        pending = new FakeConnection(status, body);
        return pending;
    }

    private static void expectError(String messagePart, IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected an IOException containing " + messagePart);
        } catch (IOException error) {
            if (!error.getMessage().contains(messagePart) || error.getMessage().contains(KEY)) {
                throw new AssertionError("Unexpected upload error: " + error.getMessage(), error);
            }
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static void same(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    private static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final int status;
        final byte[] body;
        URL requestedUrl;
        boolean discardBody;
        boolean readResponse;
        boolean disconnected;
        Runnable beforeResponse;

        FakeConnection(int status, String body) throws Exception {
            super(new URL("https://api.deepseek.com/files"));
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            if (!discardBody) return output;
            return new OutputStream() {
                @Override
                public void write(int value) { }

                @Override
                public void write(byte[] buffer, int offset, int length) { }
            };
        }

        @Override
        public int getResponseCode() {
            readResponse = true;
            if (beforeResponse != null) beforeResponse.run();
            return status;
        }

        @Override
        public InputStream getInputStream() {
            if (status >= 300) throw new AssertionError("Error response body should not be read");
            return new ByteArrayInputStream(body);
        }

        @Override
        public InputStream getErrorStream() {
            throw new AssertionError("Error response body should not be read");
        }

        long fixedLength() { return fixedContentLengthLong; }
        boolean isChunked() { return chunkLength > 0; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}

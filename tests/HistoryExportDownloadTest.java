package com.simpledeep.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class HistoryExportDownloadTest {
    private static final String START = "https://chat.deepseek.com/export?signature=private-test-value";
    private static final String CDN = "https://files.example.org/bucket?signature=private-test-value";
    private final File cache = Files.createTempDirectory("export-test").toFile();
    private final ConversationStore store = new ConversationStore(new File(cache, "history"), "[]", "");
    private final ArrayDeque<Reply> queue = new ArrayDeque<>();

    private HistoryExportDownloadTest() throws IOException { }

    public static void main(String[] args) throws Exception {
        HistoryExportDownloadTest test = new HistoryExportDownloadTest();
        test.importRedirectAndConfirm();
        test.originalAppStorage();
        test.rejectBadLinksAndResponses();
        test.boundsAndCancellation();
        System.out.println("HistoryExportDownloadTest: anonymous ZIP download, redirects, trust, cancellation and limits passed");
    }

    private void originalAppStorage() throws Exception {
        String origin = "deepseek-chat-history-exports-prod.obs.cn-east-3.myhuaweicloud.com";
        String archive = "https://" + origin + "/example.zip";
        String approved = HistoryExportDownload.ORIGINAL_EXPORT_HOST;
        fails("确认", () -> download(archive, null)); // A manually copied external link still asks.
        Reply stored = reply(200);
        stored.data = sampleZip();
        check(download(archive, approved).duplicates == 1 && stored.closed);
        Reply first = reply(302);
        first.location = archive;
        Reply target = reply(200);
        target.data = sampleZip();
        check(download(START, approved).duplicates == 1 && target.closed);
        for (String other : new String[] {origin + ".example.org", "other.obs.cn-east-3.myhuaweicloud.com"}) {
            Reply redirect = reply(302);
            redirect.location = "https://" + other + "/example.zip";
            fails("确认", () -> download(archive, approved));
            check(redirect.disconnected && queue.isEmpty());
        }
    }

    private void importRedirectAndConfirm() throws Exception {
        Reply redirect = reply(302);
        redirect.location = CDN;
        try {
            download(START, null);
            throw new AssertionError("CDN fetch without confirmation");
        } catch (HistoryExportDownload.ConfirmHost next) {
            check("files.example.org".equals(next.host) && CDN.equals(next.link));
            check(!next.getMessage().contains("private-test-value"));
        }
        check(redirect.disconnected && !redirect.bodyRead && queue.isEmpty());
        byte[] archive = sampleZip();
        Reply body = reply(200);
        body.data = archive;
        HistoryArchive.Result imported = download(CDN, "files.example.org");
        check(imported.added == 1 && body.disconnected && body.closed);
        check("GET".equals(body.getRequestMethod()) && !body.getInstanceFollowRedirects());
        check(!body.getUseCaches() && body.getRequestProperty("Cookie") == null
                && body.getRequestProperty("Referer") == null && body.getRequestProperty("Authorization") == null);
        Reply sameSite = reply(307);
        sameSite.location = "/file.zip";
        Reply repeated = reply(200);
        repeated.data = archive;
        repeated.length = archive.length;
        check(download(START, null).duplicates == 1);
        check("https://chat.deepseek.com/file.zip".equals(repeated.target.toString()));
        check(!new File(cache, "deepseek-history-import.tmp").exists());
    }

    private void rejectBadLinksAndResponses() throws Exception {
        for (String link : new String[] {"http://chat.deepseek.com/x", "https://a:b@chat.deepseek.com/x",
                "https://127.0.0.1/x", "https://[::1]/x", "https://foo.local/x",
                "https://chat.deepseek.com:444/x", "file:///data/file", "https://chat.deepseek.com/x#secret"}) {
            fails("HTTPS", () -> download(link, null));
        }
        fails("确认", () -> download("https://chat.deepseek.com.example.org/x", null));
        for (String ip : new String[]{"127.9.9.9", "10.1.2.3", "192.168.2.1", "169.254.169.254",
                "100.96.1.1", "172.31.255.255", "198.18.1.1", "224.0.0.1", "::", "::1", "fd00::1",
                "fe80::1", "64:ff9b::c0a8:0101", "2002:0a00:0001::1", "2001:db8::1",
                "192.0.2.1", "198.51.100.1", "203.0.113.1"}) {
            check(!HistoryExportDownload.publicAddress(InetAddress.getByName(ip)));
        }
        for (String ip : new String[]{"1.1.1.1", "2606:4700:4700::1111", "64:ff9b::0101:0101"}) {
            check(HistoryExportDownload.publicAddress(InetAddress.getByName(ip)));
        }
        Reply downgrade = reply(302);
        downgrade.location = "http://chat.deepseek.com/";
        fails("HTTPS", () -> download(START, null));
        for (int i = 0; i < 6; i++) reply(302).location = "/repeat";
        fails("跳转", () -> download(START, null));
        check(queue.isEmpty());
        Reply expired = reply(403);
        fails("已失效", () -> download(START, null));
        check(expired.disconnected && !expired.bodyRead);
        Reply huge = reply(200);
        huge.length = 512L * 1024 * 1024 + 1;
        fails("512", () -> download(START, null));
        check(huge.disconnected && !huge.bodyRead);
        Reply html = reply(200);
        html.data = "<html>sign in</html>".getBytes(StandardCharsets.UTF_8);
        fails("ZIP", () -> download(START, null));
        check(html.closed && html.disconnected);
        Reply shortBody = reply(200);
        shortBody.data = sampleZip();
        shortBody.length = shortBody.data.length + 2;
        fails("不完整", () -> download(START, null));
        check(shortBody.disconnected && shortBody.closed);
        Reply broken = reply(200);
        broken.fault = true;
        fails("中断", () -> download(START, null));
        check(broken.disconnected && !new File(cache, "deepseek-history-import.tmp").exists());
    }

    private void boundsAndCancellation() throws Exception {
        DeepSeekApi.Cancellation cancel = new DeepSeekApi.Cancellation();
        InputStream limited = new HistoryExportDownload.SizedStream(new ByteArrayInputStream(new byte[6]), -1, 5, cancel);
        check(limited.read(new byte[5]) == 5);
        fails("512", limited::read);
        cancel.cancel();
        fails("取消", () -> HistoryExportDownload.read(START, null, cache, store, new HistoryArchive.Result(),
                cancel, link -> { throw new AssertionError("Connection after cancellation"); }));
        DeepSeekApi.Cancellation active = new DeepSeekApi.Cancellation();
        Reply stops = reply(200);
        stops.beforeRead = active::cancel;
        fails("取消", () -> HistoryExportDownload.read(START, null, cache, store, new HistoryArchive.Result(),
                active, this::connect));
        check(stops.disconnected);
    }

    private static byte[] sampleZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "manifest.json", "{\"export_format_version\":3}");
            entry(zip, "chat_session_index.json", "[{\"id\":\"sample\"}]");
            entry(zip, "chat_sessions/sample.json", "{\"id\":\"sample\",\"messages\":[{\"id\":1,\"parent_id\":null,\"role\":\"USER\","
                    + "\"fragments\":[{\"type\":\"REQUEST\",\"content\":\"测试提问\"}]}]}");
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private Reply reply(int code) throws Exception {
        Reply response = new Reply(code);
        queue.add(response);
        return response;
    }

    private HttpURLConnection connect(URI target) {
        if (queue.isEmpty()) throw new AssertionError("Unexpected outbound request");
        Reply reply = queue.remove();
        reply.target = target;
        return reply;
    }

    private HistoryArchive.Result download(String url, String host) throws IOException {
        HistoryArchive.Result result = new HistoryArchive.Result();
        HistoryExportDownload.read(url, host, cache, store, result, new DeepSeekApi.Cancellation(), this::connect);
        return result;
    }

    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    private interface Attempt { void run() throws IOException; }
    private static void fails(String part, Attempt attempt) throws IOException {
        try { attempt.run(); }
        catch (IOException error) {
            check(error.getMessage().contains(part) && !error.getMessage().contains("private-test-value"));
            return;
        }
        throw new AssertionError("Expected " + part);
    }

    private static final class Reply extends HttpURLConnection {
        final int code;
        byte[] data = new byte[0];
        String location;
        URI target;
        long length = -1;
        boolean bodyRead, closed, disconnected, fault;
        Runnable beforeRead;
        Reply(int code) throws Exception { super(new URL(START)); this.code = code; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String key) { return "Location".equals(key) ? location : null; }
        @Override public long getContentLengthLong() { return length; }
        @Override public void disconnect() { disconnected = true; }
        @Override public InputStream getInputStream() throws IOException {
            bodyRead = true;
            if (fault) throw new IOException(START);
            if (beforeRead != null) beforeRead.run();
            return new ByteArrayInputStream(data) {
                @Override public void close() { closed = true; }
            };
        }
    }
}

package com.simpledeep.chat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Locale;

/** A user-initiated, anonymous GET of an official export. No account session is needed. */
final class HistoryExportDownload {
    // The original app serves chat archives from this dedicated object-storage bucket.
    static final String ORIGINAL_EXPORT_HOST = "deepseek-chat-history-exports-prod.obs.cn-east-3.myhuaweicloud.com";
    private static final long SIZE_LIMIT = 512L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    static final class ConfirmHost extends IOException {
        final String host;
        final String link; // A signed URL. Keep in memory only, never in an exception message.

        private ConfirmHost(URI target) {
            super("导出文件存放在另一个网站，请确认下载来源。");
            host = target.getHost().toLowerCase(Locale.ROOT);
            link = target.toASCIIString();
        }
    }

    private static final class DownloadProblem extends IOException {
        DownloadProblem(String message) { super(message); }
    }

    interface ConnectionOpener {
        HttpURLConnection open(URI target) throws IOException;
    }

    static void read(String link, String approvedHost, File cacheDir, ConversationStore store,
                     HistoryArchive.Result counts, DeepSeekApi.Cancellation cancellation) throws IOException {
        read(link, approvedHost, cacheDir, store, counts, cancellation, target -> {
            // WebView has its own cookie store. Do not adopt a process-wide Java cookie jar either.
            if (CookieHandler.getDefault() != null) {
                throw new DownloadProblem("当前无法匿名下载，请在浏览器下载导出包后选择文件。");
            }
            for (InetAddress address : InetAddress.getAllByName(target.getHost())) {
                if (!publicAddress(address)) throw invalidLink();
            }
            return (HttpURLConnection) target.toURL().openConnection();
        });
    }

    static void read(String link, String approvedHost, File cacheDir, ConversationStore store,
                     HistoryArchive.Result counts, DeepSeekApi.Cancellation cancellation,
                     ConnectionOpener opener) throws IOException {
        URI target = parse(link);
        for (int hops = 0; ; hops++) {
            HttpURLConnection connection = null;
            try {
                checkCancelled(cancellation);
                String host = target.getHost().toLowerCase(Locale.ROOT);
                if (!host.equals("deepseek.com") && !host.endsWith(".deepseek.com")
                        && !host.equals(approvedHost)) throw new ConfirmHost(target);
                connection = opener.open(target);
                cancellation.bind(connection);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Cache-Control", "no-store");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (hops >= MAX_REDIRECTS || location == null || location.isEmpty()) {
                        throw new DownloadProblem("下载跳转未能完成，请从原版重新获取下载链接。");
                    }
                    target = parse(target.resolve(new URI(location)).toASCIIString());
                    continue;
                }
                if (code == 401 || code == 403 || code == 404 || code == 410) {
                    throw new DownloadProblem("这个下载链接已失效或需要登录，请在原版重新获取导出文件。");
                }
                if (code != 200) throw new DownloadProblem("下载暂时失败，请稍后重试。");
                long length = connection.getContentLengthLong();
                if (length > SIZE_LIMIT) throw tooLarge();
                try (BufferedInputStream input = new BufferedInputStream(
                        new SizedStream(connection.getInputStream(), length, SIZE_LIMIT, cancellation))) {
                    input.mark(4);
                    if (input.read() != 'P' || input.read() != 'K' || input.read() != 3 || input.read() != 4) {
                        throw new DownloadProblem("链接未返回聊天记录 ZIP，请在原版确认导出已完成。");
                    }
                    input.reset();
                    HistoryArchive.read(input, cacheDir, store, counts);
                }
                return;
            } catch (ConfirmHost | DownloadProblem known) {
                checkCancelled(cancellation);
                throw known;
            } catch (IOException | SecurityException | URISyntaxException failure) {
                checkCancelled(cancellation);
                // Platform errors may contain the complete signed request URL or response content.
                throw new DownloadProblem("导出包下载中断或无法读取，请重试，或选择已下载的 ZIP。");
            } finally {
                if (connection != null) {
                    cancellation.unbind(connection);
                    connection.disconnect();
                }
            }
        }
    }

    private static URI parse(String link) throws IOException {
        if (link == null || link.length() > 16384) throw invalidLink();
        try {
            URI uri = new URI(link);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null) throw invalidLink();
            host = host.toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9.-]+\\.[a-z][a-z0-9-]*") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".home.arpa")
                    || host.endsWith(".lan")) throw invalidLink();
            return uri;
        } catch (URISyntaxException invalid) {
            throw invalidLink();
        }
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] ip = address.getAddress();
        if (ip.length == 4) return publicV4(ip, 0);
        // Mobile IPv6-only networks can return a NAT64 address for a public IPv4 origin.
        if (ip[0] == 0 && ip[1] == 0x64 && ip[2] == (byte) 0xff && ip[3] == (byte) 0x9b) {
            for (int i = 4; i < 12; i++) if (ip[i] != 0) return false;
            return publicV4(ip, 12);
        }
        // Exclude documentation and transition prefixes whose embedded IPv4 destination may be private.
        int prefix = ByteBuffer.wrap(ip).getInt();
        return (ip[0] & 0xe0) == 0x20 && (prefix >>> 16) != 0x2002
                && prefix != 0x20010000 && prefix != 0x20010db8;
    }

    private static boolean publicV4(byte[] ip, int offset) {
        int a = ip[offset] & 255, b = ip[offset + 1] & 255, c = ip[offset + 2] & 255;
        return a > 0 && a < 224 && a != 10 && a != 127 && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && b == 168)
                && !(a == 100 && b >= 64 && b <= 127) && !(a == 198 && (b == 18 || b == 19))
                && !(a == 192 && b == 0 && (c == 0 || c == 2))
                && !(a == 198 && b == 51 && c == 100) && !(a == 203 && b == 0 && c == 113);
    }

    private static DownloadProblem invalidLink() {
        return new DownloadProblem("这个链接不是可用的公网 HTTPS 导出地址，请从原版重新获取。");
    }

    private static DownloadProblem tooLarge() {
        return new DownloadProblem("导出包超过 512 MiB。请先下载 ZIP，解压后分次选择会话 JSON。");
    }

    private static void checkCancelled(DeepSeekApi.Cancellation cancellation) throws InterruptedIOException {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("已取消本次导入。");
        }
    }

    static final class SizedStream extends FilterInputStream {
        private final long expected, limit;
        private long count;
        private final DeepSeekApi.Cancellation cancellation;

        SizedStream(InputStream source, long expected, long limit, DeepSeekApi.Cancellation cancellation) {
            super(source);
            this.expected = expected;
            this.limit = limit;
            this.cancellation = cancellation;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCancelled(cancellation);
            int n = super.read(bytes, offset, (int) Math.min(length, limit - count + 1));
            if (n < 0) {
                if (expected >= 0 && count != expected) throw new DownloadProblem("导出包下载不完整，请重试。");
            } else {
                count += n;
                if (count > limit) throw tooLarge();
                if (expected >= 0 && count > expected) throw new DownloadProblem("导出包大小发生变化，请重新下载。");
            }
            return n;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
    }
}

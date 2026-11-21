package com.simpledeep.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs without Android or a network connection. */
public final class DeepSeekApiTest {
    private static final String KEY = "fixture-key";
    private static final String FIRST_PART = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n";
    private static final String DONE = "data: [DONE]\n\n";
    private static final List<DeepSeekApi.Message> MESSAGES = Arrays.asList(
            new DeepSeekApi.Message("user", "前一个问题"),
            new DeepSeekApi.Message("assistant", "前一个回答"),
            new DeepSeekApi.Message("user", "继续说"));

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

        streamsAnswerWithoutReasoningAndSendsContext();
        acceptsConsecutiveDataLines();
        sendsExplicitNonThinkingMode();
        parsesBalanceAndPreservesCurrencies();
        exposesUnavailabilityFromBalance();
        acceptsTotalsWithoutOptionalBreakdown();
        classifiesWalletsAcrossCurrencies();
        handlesIncompleteWallets();
        reportsHttpFailuresWithoutServerResponseBody();
        preservesHttpStatusWhenStreamingCannotRetry();
        reportsNetworkTimeout();
        detectsInterruptedStreamAndKeepsPartialText();
        reportsContentFilteringAndOutputLimit();
        rejectsInvalidStream();
        cancelsConnectionAndStopsFurtherCallbacks();
        streamsWebAnswerAndReplaysSignedToolCallsWithSearchOff();
        sendsFileReferencesInCurrentAndHistoricalUserMessages();
        System.out.println("DeepSeekApi protocol tests passed (17 cases)");
    }

    private static void streamsAnswerWithoutReasoningAndSendsContext() throws Exception {
        FakeConnection connection = reply(200, ": keep-alive\r\n\r\n"
                + "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}\r\n\r\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\"}}]}\r\n\r\n"
                + FIRST_PART
                + "data: {\"choices\":\r\ndata: [{\"delta\":{\"content\":\" 👋\"}}]}\r\n\r\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":\"stop\"}]}\r\n\r\n"
                + "data: [DONE]");
        List<String> chunks = new ArrayList<>();
        equal("你好 👋", DeepSeekApi.chat(KEY, MESSAGES, true, chunks::add));
        equal(Arrays.asList("你好", "你好 👋"), chunks);
        verifyRequest(connection, "POST", "/chat/completions", "text/event-stream");

        JSONObject request = new JSONObject(connection.output.toString("UTF-8"));
        equal("deepseek-flash", request.getString("model"));
        equal(true, request.getBoolean("stream"));
        equal("enabled", request.getJSONObject("thinking").getString("type"));
        equal(3, request.getJSONArray("messages").length());
        JSONObject assistant = request.getJSONArray("messages").getJSONObject(1);
        equal("assistant", assistant.getString("role"));
        equal("前一个回答", assistant.getString("content"));
        equal(false, assistant.has("reasoning_content"));
    }

    private static void sendsExplicitNonThinkingMode() throws Exception {
        FakeConnection connection = reply(200, FIRST_PART + DONE);
        equal("你好", DeepSeekApi.chat(KEY, MESSAGES, false, null));
        JSONObject request = new JSONObject(connection.output.toString("UTF-8"));
        equal("disabled", request.getJSONObject("thinking").getString("type"));
    }

    private static void acceptsConsecutiveDataLines() throws Exception {
        reply(200, "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n"
                + DONE);
        equal("你好", DeepSeekApi.chat(KEY, MESSAGES, false, null));
    }

    private static void parsesBalanceAndPreservesCurrencies() throws Exception {
        FakeConnection connection = reply(200, "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"110.00\",\"granted_balance\":\"10.00\","
                + "\"topped_up_balance\":\"100.00\"},"
                + "{\"currency\":\"USD\",\"total_balance\":\"2.5000\",\"granted_balance\":\"2.5000\","
                + "\"topped_up_balance\":\"0.00\"}]}");
        DeepSeekApi.Balance balance = DeepSeekApi.balance(KEY);
        verifyRequest(connection, "GET", "/user/balance", "application/json");
        equal(true, balance.available);
        equal(DeepSeekApi.BalanceLevel.FUNDED, balance.level);
        equal(2, balance.amounts.size());
        DeepSeekApi.BalanceAmount cny = balance.amounts.get(0);
        equal("CNY", cny.currency);
        equal("110.00", cny.total);
        equal("10.00", cny.granted);
        equal("100.00", cny.toppedUp);
        check(balance.display.contains("110.00 元") && balance.display.contains("2.5000 美元"));
    }

    private static void exposesUnavailabilityFromBalance() throws Exception {
        reply(200, "{\"is_available\":false,\"balance_infos\":[]}");
        equal(false, DeepSeekApi.balance(KEY).available);
    }

    private static void acceptsTotalsWithoutOptionalBreakdown() throws Exception {
        reply(200, "{\"is_available\":true,\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"0.50\"}]}");
        equal("0.50 元", DeepSeekApi.balance(KEY).display);
    }

    private static JSONObject wallet(String currency, String total) throws Exception {
        return new JSONObject().put("currency", currency).put("total_balance", total);
    }

    private static DeepSeekApi.BalanceLevel balanceLevel(boolean available, Object... rows) throws Exception {
        JSONArray amounts = new JSONArray();
        for (Object row : rows) amounts.put(row);
        reply(200, new JSONObject().put("is_available", available).put("balance_infos", amounts).toString());
        return DeepSeekApi.balance(KEY).level;
    }

    private static void classifiesWalletsAcrossCurrencies() throws Exception {
        equal(DeepSeekApi.BalanceLevel.LOW, balanceLevel(true, wallet("CNY", "1.0000")));
        equal(DeepSeekApi.BalanceLevel.LOW, balanceLevel(true, wallet("USD", "0.1500")));
        equal(DeepSeekApi.BalanceLevel.LOW, balanceLevel(true, wallet("CNY", "0"), wallet("USD", "0.00001")));
        equal(DeepSeekApi.BalanceLevel.EMPTY, balanceLevel(true, wallet("CNY", "0.0000"), wallet("USD", "0")));
        equal(DeepSeekApi.BalanceLevel.EMPTY, balanceLevel(false, wallet("CNY", "1.01")));
        equal(DeepSeekApi.BalanceLevel.EMPTY, balanceLevel(false));
        equal(DeepSeekApi.BalanceLevel.FUNDED, balanceLevel(true, wallet("USD", "0.1500001"), wallet("CNY", "1")));
        equal(DeepSeekApi.BalanceLevel.FUNDED, balanceLevel(true, wallet("USD", "0.15"), wallet("CNY", "1.00001")));
    }

    private static void handlesIncompleteWallets() throws Exception {
        equal(DeepSeekApi.BalanceLevel.UNKNOWN, balanceLevel(true));
        equal(DeepSeekApi.BalanceLevel.UNKNOWN, balanceLevel(true, wallet("CNY", "NaN")));
        equal(DeepSeekApi.BalanceLevel.UNKNOWN, balanceLevel(true,
                new JSONObject().put("currency", "CNY").put("total_balance", 0)));
        equal(DeepSeekApi.BalanceLevel.UNKNOWN, balanceLevel(true, wallet("USD", "0"), JSONObject.NULL));
        equal(DeepSeekApi.BalanceLevel.UNKNOWN, balanceLevel(true, wallet("CNY", "0.01"), wallet("EUR", "2")));
        equal(DeepSeekApi.BalanceLevel.FUNDED, balanceLevel(true, new JSONObject(), wallet("USD", "2")));
        reply(200, "{\"is_available\":true}");
        equal(DeepSeekApi.BalanceLevel.UNKNOWN, DeepSeekApi.balance(KEY).level);
        reply(200, "{\"balance_infos\":[]}");
        expectError(0, "格式", () -> DeepSeekApi.balance(KEY));
        reply(200, "{\"is_available\":\"false\",\"balance_infos\":[]}");
        expectError(0, "格式", () -> DeepSeekApi.balance(KEY));
    }

    private static void reportsHttpFailuresWithoutServerResponseBody() throws Exception {
        int[] statuses = {307, 401, 402, 429, 503};
        String[] messages = {"重定向", "API Key", "余额", "频繁", "繁忙"};
        for (int i = 0; i < statuses.length; i++) {
            FakeConnection connection = reply(statuses[i], "echoed private text " + KEY);
            expectError(statuses[i], messages[i], () -> DeepSeekApi.balance(KEY));
            verifyRequest(connection, "GET", "/user/balance", "application/json");
        }
        reply(401, "");
        expectError(401, "API Key", () -> DeepSeekApi.chat(KEY, MESSAGES, false, null));
        reply(402, "");
        expectError(402, "余额", () -> DeepSeekApi.chat(KEY, MESSAGES, false, null));
    }

    private static void preservesHttpStatusWhenStreamingCannotRetry() throws Exception {
        pending = new FakeConnection(401, "") {
            @Override
            public int getResponseCode() throws IOException {
                throw new HttpRetryException("echoed private text " + KEY, 401);
            }
        };
        expectError(401, "API Key", () -> DeepSeekApi.chat(KEY, MESSAGES, false, null));
    }

    private static void reportsNetworkTimeout() throws Exception {
        pending = new FakeConnection(200, "") {
            @Override
            public int getResponseCode() throws IOException {
                throw new SocketTimeoutException("echoed private text " + KEY);
            }
        };
        expectError(0, "超时", () -> DeepSeekApi.balance(KEY));
    }

    private static void detectsInterruptedStreamAndKeepsPartialText() throws Exception {
        reply(200, FIRST_PART);
        List<String> chunks = new ArrayList<>();
        expectError(0, "中断", () -> DeepSeekApi.chat(KEY, MESSAGES, false, chunks::add));
        equal(Arrays.asList("你好"), chunks);
    }

    private static void reportsContentFilteringAndOutputLimit() throws Exception {
        reply(200, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\n" + DONE);
        expectError(0, "内容过滤", () -> DeepSeekApi.chat(KEY, MESSAGES, false, null));
        reply(200, FIRST_PART + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" + DONE);
        expectError(0, "长度上限", () -> DeepSeekApi.chat(KEY, MESSAGES, false, null));
    }

    private static void rejectsInvalidStream() throws Exception {
        reply(200, "data: {\"error\":{\"message\":\"echoed private text " + KEY + "\"}}\n\n");
        expectError(0, "服务", () -> DeepSeekApi.chat(KEY, MESSAGES, false, null));
        reply(200, "data: echoed private text " + KEY + "\n\n");
        expectError(0, "格式", () -> DeepSeekApi.chat(KEY, MESSAGES, false, null));
        reply(200, DONE);
        expectError(0, "没有返回正文", () -> DeepSeekApi.chat(KEY, MESSAGES, false, null));
    }

    private static void cancelsConnectionAndStopsFurtherCallbacks() throws Exception {
        FakeConnection connection = reply(200, FIRST_PART + FIRST_PART + DONE);
        DeepSeekApi.Cancellation cancellation = new DeepSeekApi.Cancellation();
        List<String> chunks = new ArrayList<>();
        expectError(0, "停止", () -> DeepSeekApi.chat(KEY, MESSAGES, false, content -> {
            chunks.add(content);
            cancellation.cancel();
            check(connection.disconnected);
        }, cancellation));
        equal(true, cancellation.isCancelled());
        equal(Arrays.asList("你好"), chunks);
        expectError(0, "停止", () -> DeepSeekApi.chat(KEY, MESSAGES, false, null, cancellation));
    }

    private static void streamsWebAnswerAndReplaysSignedToolCallsWithSearchOff() throws Exception {
        String calls = "[{\"index\":0,\"reasoning_content\":\"先看原文\",\"calls\":["
                + "{\"index\":0,\"id\":\"read_0\",\"type\":\"deepseek_read_url\",\"kind\":\"read_link\","
                + "\"action\":{\"type\":\"read_link\",\"url\":\"https://example.com/a\"},\"status\":\"completed\","
                + "\"page\":{\"url\":\"https://example.com/a\",\"title\":\"文章\"},\"signature\":\"fixture/+==\"}]},"
                + "{\"index\":1,\"reasoning_content\":\"再查背景\",\"calls\":["
                + "{\"index\":0,\"id\":\"search_0\",\"type\":\"deepseek_web_search\",\"kind\":\"deep_search\","
                + "\"action\":{\"type\":\"search\",\"queries\":[\"背景资料\"]},\"status\":\"completed\","
                + "\"sources\":[{\"type\":\"url\",\"url\":\"https://example.org/b\"}]}]}]";
        String annotations = "[{\"type\":\"url_citation\",\"url_citation\":{\"url\":\"https://example.com/a\","
                + "\"title\":\"文章\",\"start_index\":0,\"end_index\":2}},"
                + "{\"type\":\"url_citation\",\"url_citation\":{\"url\":\"https://example.org/b\"}},"
                + "{\"type\":\"url_citation\",\"url_citation\":{\"url\":\"javascript:alert(1)\"}}]";
        FakeConnection connection = reply(200,
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先看原文\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"server_tool_event\":{\"type\":\"call_started\",\"step\":0,"
                + "\"call\":{\"index\":0,\"id\":\"read_0\",\"type\":\"deepseek_read_url\",\"action\":{\"type\":\"read_link\"}}}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"再查背景\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"👋你好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"server_tool_calls\":" + calls + ",\"annotations\":"
                + annotations + "},\"finish_reason\":\"stop\"}]}\n\n" + DONE);
        List<String> chunks = new ArrayList<>();
        List<String> thoughts = new ArrayList<>();
        List<String> toolEvents = new ArrayList<>();
        DeepSeekApi.Message result = DeepSeekApi.chatMessage(KEY, MESSAGES, true, true,
                new DeepSeekApi.ChunkListener() {
                    @Override public void onChunk(String text) { chunks.add(text); }
                    @Override public void onReasoning(String text) { thoughts.add(text); }
                    @Override public void onToolActivity(String toolType) { toolEvents.add(toolType); }
                }, new DeepSeekApi.Cancellation());
        equal("assistant", result.role);
        equal("👋你好", result.content);
        equal(Arrays.asList("👋你好"), chunks);
        equal(Arrays.asList("先看原文", "先看原文再查背景"), thoughts);
        equal("先看原文再查背景", result.reasoningContent);
        equal(Arrays.asList("deepseek_read_url"), toolEvents);
        equal(2, result.citations.size());
        equal("文章", result.citations.get(0).title);
        equal("https://example.com/a", result.citations.get(0).url);
        equal(2, result.citations.get(0).endIndex);
        equal("https://example.org/b", result.citations.get(1).title);

        JSONObject request = new JSONObject(connection.output.toString("UTF-8"));
        equal(2, request.getJSONArray("tools").length());
        equal("deepseek_web_search", request.getJSONArray("tools").getJSONObject(0).getString("type"));
        equal("deepseek_read_url", request.getJSONArray("tools").getJSONObject(1).getString("type"));

        FakeConnection followup = reply(200, FIRST_PART + DONE);
        DeepSeekApi.chatMessage(KEY, Arrays.asList(MESSAGES.get(0), result, MESSAGES.get(2)), false, false, null,
                new DeepSeekApi.Cancellation());
        JSONObject followupRequest = new JSONObject(followup.output.toString("UTF-8"));
        equal(false, followupRequest.has("tools"));
        JSONObject prior = followupRequest.getJSONArray("messages").getJSONObject(1);
        equal("👋你好", prior.getString("content"));
        equal(false, prior.has("annotations"));
        equal(false, prior.has("reasoning_content"));
        equal(result.serverToolCallsJson, prior.getJSONArray("server_tool_calls").toString());
        equal("fixture/+==", prior.getJSONArray("server_tool_calls").getJSONObject(0).getJSONArray("calls")
                .getJSONObject(0).getString("signature"));
    }

    private static void sendsFileReferencesInCurrentAndHistoricalUserMessages() throws Exception {
        List<DeepSeekApi.Attachment> files = new ArrayList<>();
        files.add(new DeepSeekApi.Attachment("file-api-test1", "材料.pdf"));
        files.add(new DeepSeekApi.Attachment("file-api-test2", "图片.png", true));
        DeepSeekApi.Message earlier = new DeepSeekApi.Message("user", "总结", files, null, null);
        equal(false, earlier.attachments.get(0).image);
        equal(true, earlier.attachments.get(1).image);
        files.clear();
        files.add(new DeepSeekApi.Attachment("file-api-test3", "表格.xlsx"));
        DeepSeekApi.Message current = new DeepSeekApi.Message("user", "", files, null, null);
        FakeConnection connection = reply(200, FIRST_PART + DONE);
        equal("你好", DeepSeekApi.chat(KEY, Arrays.asList(earlier, MESSAGES.get(1), current), false, null));
        JSONObject request = new JSONObject(connection.output.toString("UTF-8"));
        JSONArray messages = request.getJSONArray("messages");
        JSONArray historicalParts = messages.getJSONObject(0).getJSONArray("content");
        equal(3, historicalParts.length());
        equal("file", historicalParts.getJSONObject(0).getString("type"));
        equal("file-api-test1", historicalParts.getJSONObject(0).getString("file_id"));
        equal("file-api-test2", historicalParts.getJSONObject(1).getString("file_id"));
        equal(false, historicalParts.getJSONObject(1).has("name"));
        equal(false, historicalParts.getJSONObject(1).has("image"));
        equal("text", historicalParts.getJSONObject(2).getString("type"));
        equal("总结", historicalParts.getJSONObject(2).getString("text"));
        equal("前一个回答", messages.getJSONObject(1).getString("content"));
        JSONArray currentParts = messages.getJSONObject(2).getJSONArray("content");
        equal(1, currentParts.length());
        equal("file-api-test3", currentParts.getJSONObject(0).getString("file_id"));
    }

    private static void verifyRequest(FakeConnection connection, String method, String path, String accept) {
        equal("https://api.deepseek.com" + path, connection.requestedUrl.toString());
        equal(method, connection.getRequestMethod());
        equal("Bearer " + KEY, connection.getRequestProperty("Authorization"));
        equal(accept, connection.getRequestProperty("Accept"));
        equal(false, connection.getInstanceFollowRedirects());
        equal(true, connection.disconnected);
    }

    private static void expectError(int status, String messagePart, Call call) throws Exception {
        try {
            call.run();
            throw new AssertionError("Expected API error: " + messagePart);
        } catch (DeepSeekApi.ApiException error) {
            equal(status, error.statusCode);
            if (!error.getMessage().contains(messagePart) || error.getMessage().contains(KEY)
                    || error.getMessage().contains("private text")) {
                throw new AssertionError("Unexpected error message: " + error.getMessage());
            }
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Expected condition to be true");
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + ">, got <" + actual + ">");
        }
    }

    private static FakeConnection reply(int status, String body) throws IOException {
        pending = new FakeConnection(status, body);
        return pending;
    }

    private interface Call {
        void run() throws Exception;
    }

    private static class FakeConnection extends HttpURLConnection {
        private final int status;
        private final byte[] body;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        URL requestedUrl;
        boolean disconnected;

        FakeConnection(int status, String body) throws IOException {
            super(new URL("https://api.deepseek.com"));
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int getResponseCode() throws IOException {
            return status;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() { }
    }
}

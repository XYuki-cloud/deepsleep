package com.simpledeep.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLException;

public final class DeepSeekApi {
    public static final String MODEL = "deepseek-flash";

    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String WEB_SEARCH = "deepseek_web_search";
    private static final String READ_URL = "deepseek_read_url";

    private DeepSeekApi() { }

    public static final class Message {
        public final String role;
        public final String content;
        public final List<Attachment> attachments;
        public final List<Citation> citations;
        public final String serverToolCallsJson;
        public final String reasoningContent;

        public Message(String role, String content) {
            this(role, content, null, null, null);
        }

        public Message(String role, String content, List<Attachment> attachments, List<Citation> citations,
                       String serverToolCallsJson) {
            this(role, content, attachments, citations, serverToolCallsJson, "");
        }

        public Message(String role, String content, List<Attachment> attachments, List<Citation> citations,
                       String serverToolCallsJson, String reasoningContent) {
            this.role = role;
            this.content = content;
            this.attachments = attachments == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(attachments));
            this.citations = citations == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(citations));
            this.serverToolCallsJson = serverToolCallsJson;
            this.reasoningContent = reasoningContent == null ? "" : reasoningContent;
        }
    }

    public static final class Attachment {
        public final String fileId;
        public final String name;
        public final boolean image;

        public Attachment(String fileId, String name) {
            this(fileId, name, false);
        }

        public Attachment(String fileId, String name, boolean image) {
            this.fileId = fileId;
            this.name = name;
            this.image = image;
        }
    }

    public static final class Citation {
        public final String title;
        public final String url;
        /** Offsets count Unicode code points. An absent offset is -1. */
        public final int startIndex;
        public final int endIndex;

        public Citation(String title, String url) {
            this(title, url, -1, -1);
        }

        public Citation(String title, String url, int startIndex, int endIndex) {
            this.title = title;
            this.url = url;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
    }

    public static final class BalanceAmount {
        private static final BigDecimal LOW_USD = new BigDecimal("0.15");
        public final String currency;
        public final String total;
        public final String granted;
        public final String toppedUp;

        private BalanceAmount(JSONObject amount) {
            currency = amount.optString("currency", "");
            Object rawTotal = amount.opt("total_balance");
            total = rawTotal instanceof String ? ((String) rawTotal).trim() : "";
            granted = amount.optString("granted_balance", "");
            toppedUp = amount.optString("topped_up_balance", "");
        }

        private BalanceLevel level() {
            BigDecimal threshold;
            if ("CNY".equals(currency)) threshold = BigDecimal.ONE;
            else if ("USD".equals(currency)) threshold = LOW_USD;
            else return BalanceLevel.UNKNOWN;
            try {
                BigDecimal value = new BigDecimal(total);
                if (value.signum() <= 0) return BalanceLevel.EMPTY;
                return value.compareTo(threshold) <= 0 ? BalanceLevel.LOW : BalanceLevel.FUNDED;
            } catch (NumberFormatException invalidAmount) {
                return BalanceLevel.UNKNOWN;
            }
        }
    }

    public enum BalanceLevel { FUNDED, LOW, EMPTY, UNKNOWN }

    public static final class Balance {
        public final boolean available;
        public final List<BalanceAmount> amounts;
        public final String display;
        public final BalanceLevel level;

        private Balance(JSONObject json) throws JSONException {
            Object flag = json.get("is_available");
            if (!(flag instanceof Boolean)) throw new JSONException("Invalid is_available");
            available = (Boolean) flag;
            JSONArray rows = json.optJSONArray("balance_infos");
            int count = rows == null ? 0 : rows.length();
            boolean incomplete = count == 0;
            BalanceLevel highest = BalanceLevel.EMPTY;
            List<BalanceAmount> balances = new ArrayList<>();
            List<String> summaries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    incomplete = true;
                    continue;
                }
                BalanceAmount amount = new BalanceAmount(row);
                balances.add(amount);
                BalanceLevel wallet = amount.level();
                if (wallet == BalanceLevel.UNKNOWN) {
                    incomplete = true;
                    continue;
                }
                summaries.add(amount.total + ("CNY".equals(amount.currency) ? " 元" : " 美元"));
                if (wallet == BalanceLevel.FUNDED || highest == BalanceLevel.EMPTY) highest = wallet;
            }
            amounts = Collections.unmodifiableList(balances);
            display = summaries.isEmpty() ? "暂无余额数据" : String.join(" · ", summaries);
            if (!available) level = BalanceLevel.EMPTY;
            else if (incomplete && highest != BalanceLevel.FUNDED) level = BalanceLevel.UNKNOWN;
            else level = highest;
        }
    }

    public static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;

        /** HTTP status, or zero when the problem occurred outside the HTTP status response. */
        public final int statusCode;

        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    @FunctionalInterface
    public interface ChunkListener {
        /** Called on the requesting worker thread with the answer so far. */
        void onChunk(String content);

        /** Called on the requesting worker thread with the reasoning so far. */
        default void onReasoning(String reasoning) { }

        /** Called on the requesting worker thread when a DeepSeek server tool starts. */
        default void onToolActivity(String toolType) { }
    }

    /** One cancellation object per request. */
    public static final class Cancellation {
        private volatile boolean cancelled;
        private HttpURLConnection connection;

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            HttpURLConnection active;
            synchronized (this) {
                cancelled = true;
                active = connection;
                connection = null;
            }
            if (active != null) active.disconnect();
        }

        synchronized void bind(HttpURLConnection active) throws ApiException {
            throwIfCancelled();
            connection = active;
        }

        synchronized void unbind(HttpURLConnection active) {
            if (connection == active) connection = null;
        }

        void throwIfCancelled() throws ApiException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw stopped();
            }
        }
    }

    public static Balance balance(String key) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = open("/user/balance", key, "GET", "application/json", 20_000);
            checkResponse(connection);

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = reader(connection.getInputStream())) {
                char[] buffer = new char[1024];
                int size;
                while ((size = reader.read(buffer)) != -1) {
                    response.append(buffer, 0, size);
                }
            }

            return new Balance(new JSONObject(response.toString()));
        } catch (JSONException error) {
            throw new ApiException(0, "DeepSeek 返回的余额数据格式异常。");
        } catch (IOException error) {
            throw networkError(error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static String chat(String key, List<Message> messages, boolean thinking, ChunkListener listener)
            throws IOException {
        return chat(key, messages, thinking, listener, new Cancellation());
    }

    public static String chat(String key, List<Message> messages, boolean thinking, ChunkListener listener,
                              Cancellation cancellation) throws IOException {
        return chatMessage(key, messages, thinking, false, listener, cancellation).content;
    }

    public static Message chatMessage(String key, List<Message> messages, boolean thinking, boolean search,
                                      ChunkListener listener, Cancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        byte[] request = chatRequest(messages, thinking, search);
        HttpURLConnection connection = null;
        try {
            connection = open("/chat/completions", key, "POST", "text/event-stream", 120_000);
            cancellation.bind(connection);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }
            checkResponse(connection);
            try (BufferedReader reader = reader(connection.getInputStream())) {
                return readChat(reader, listener, cancellation);
            }
        } catch (IOException error) {
            cancellation.throwIfCancelled();
            throw networkError(error);
        } finally {
            cancellation.unbind(connection);
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] chatRequest(List<Message> messages, boolean thinking, boolean search) throws ApiException {
        if (messages == null || messages.isEmpty()) {
            throw new ApiException(0, "请先输入消息。");
        }
        try {
            JSONArray context = new JSONArray();
            for (Message message : messages) {
                JSONObject next = new JSONObject().put("role", message.role)
                        .put("content", messageContent(message));
                if ("assistant".equals(message.role) && message.serverToolCallsJson != null
                        && !message.serverToolCallsJson.isEmpty()) {
                    next.put("server_tool_calls", new JSONArray(message.serverToolCallsJson));
                }
                context.put(next);
            }
            JSONObject thinkingMode = new JSONObject().put("type", thinking ? "enabled" : "disabled");
            JSONObject request = new JSONObject()
                    .put("model", MODEL)
                    .put("stream", true)
                    .put("thinking", thinkingMode)
                    .put("messages", context);
            if (search) {
                request.put("tools", new JSONArray()
                        .put(new JSONObject().put("type", WEB_SEARCH))
                        .put(new JSONObject().put("type", READ_URL)));
            }
            return request.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new ApiException(0, "无法构建聊天请求。");
        }
    }

    private static Object messageContent(Message message) throws JSONException, ApiException {
        if (message.attachments.isEmpty()) return message.content;

        JSONArray parts = new JSONArray();
        for (Attachment attachment : message.attachments) {
            if (attachment.fileId == null || attachment.fileId.isEmpty()) {
                throw new ApiException(0, "附件还未上传成功，请重新添加附件。");
            }
            parts.put(new JSONObject().put("type", "file").put("file_id", attachment.fileId));
        }
        if (message.content != null && !message.content.isEmpty()) {
            parts.put(new JSONObject().put("type", "text").put("text", message.content));
        }
        return parts;
    }

    private static Message readChat(BufferedReader reader, ChunkListener listener, Cancellation cancellation)
            throws IOException {
        StreamAnswer answer = new StreamAnswer(listener, cancellation);
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            cancellation.throwIfCancelled();
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    throw new ApiException(0, "DeepSeek 返回的回复数据格式异常。");
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (data.length() > 0) data.append('\n');
                data.append(value);

                String event = data.toString();
                if ("[DONE]".equals(event.trim())) return answer.complete();
                JSONObject chunk = parseEvent(event);
                if (chunk != null) {
                    answer.accept(chunk);
                    data.setLength(0);
                }
            }
        }
        cancellation.throwIfCancelled();
        throw new ApiException(0, "回复传输中断，请重试。");
    }

    private static JSONObject parseEvent(String event) {
        // Keep waiting for the next data line if this event is split across multiple lines.
        try {
            JSONTokener parser = new JSONTokener(event);
            Object value = parser.nextValue();
            if (value instanceof JSONObject && parser.nextClean() == '\0') {
                return (JSONObject) value;
            }
        } catch (JSONException ignored) {
            // The next blank line will delimit a malformed event.
        }
        return null;
    }

    private static final class StreamAnswer {
        private final ChunkListener listener;
        private final Cancellation cancellation;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private JSONArray annotations;
        private JSONArray serverToolCalls;

        StreamAnswer(ChunkListener listener, Cancellation cancellation) {
            this.listener = listener;
            this.cancellation = cancellation;
        }

        void accept(JSONObject chunk) throws ApiException {
            try {
                if (!chunk.isNull("error")) {
                    throw new ApiException(0, "DeepSeek 服务返回错误，请稍后重试。");
                }
                JSONArray choices = chunk.getJSONArray("choices");
                if (choices.length() == 0) return;

                JSONObject choice = choices.getJSONObject(0);
                JSONObject delta = choice.optJSONObject("delta");
                if (delta != null) acceptDelta(delta);
                JSONObject toolEvent = choice.optJSONObject("server_tool_event");
                if (toolEvent != null) acceptToolEvent(toolEvent);
                checkFinishReason(choice.optString("finish_reason", ""));
            } catch (JSONException error) {
                throw new ApiException(0, "DeepSeek 返回的回复数据格式异常。");
            }
        }

        private void acceptDelta(JSONObject delta) throws JSONException, ApiException {
            if (!delta.isNull("reasoning_content")) {
                String text = delta.getString("reasoning_content");
                if (!text.isEmpty()) {
                    reasoning.append(text);
                    cancellation.throwIfCancelled();
                    if (listener != null) listener.onReasoning(reasoning.toString());
                }
            }
            if (!delta.isNull("content")) {
                String text = delta.getString("content");
                if (!text.isEmpty()) {
                    content.append(text);
                    cancellation.throwIfCancelled();
                    if (listener != null) listener.onChunk(content.toString());
                }
            }
            // The terminal delta carries the complete arrays, including signed page data.
            if (!delta.isNull("server_tool_calls")) serverToolCalls = delta.getJSONArray("server_tool_calls");
            if (!delta.isNull("annotations")) annotations = delta.getJSONArray("annotations");
        }

        private void acceptToolEvent(JSONObject event) throws ApiException {
            if (listener == null || !"call_started".equals(event.optString("type"))) return;
            JSONObject call = event.optJSONObject("call");
            if (call == null) return;
            String type = call.optString("type");
            if (WEB_SEARCH.equals(type) || READ_URL.equals(type)) {
                cancellation.throwIfCancelled();
                listener.onToolActivity(type);
            }
        }

        Message complete() throws ApiException {
            cancellation.throwIfCancelled();
            String text = content.toString();
            if (text.trim().isEmpty()) throw new ApiException(0, "DeepSeek 没有返回正文，请重试。");
            String calls = serverToolCalls == null || serverToolCalls.length() == 0 ? null : serverToolCalls.toString();
            return new Message("assistant", text, null, citations(annotations), calls, reasoning.toString());
        }
    }

    private static List<Citation> citations(JSONArray annotations) {
        List<Citation> result = new ArrayList<>();
        if (annotations == null) return result;
        for (int i = 0; i < annotations.length(); i++) {
            JSONObject annotation = annotations.optJSONObject(i);
            if (annotation == null || !"url_citation".equals(annotation.optString("type"))) continue;
            JSONObject citation = annotation.optJSONObject("url_citation");
            if (citation == null) continue;
            String url = citation.optString("url", "");
            if (!isWebUrl(url)) continue;
            String title = citation.isNull("title") ? url : citation.optString("title");
            result.add(new Citation(title.isEmpty() ? url : title, url,
                    citation.optInt("start_index", -1), citation.optInt("end_index", -1)));
        }
        return result;
    }

    private static boolean isWebUrl(String url) {
        try {
            URI uri = new URI(url);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static void checkFinishReason(String reason) throws ApiException {
        switch (reason) {
            case "length":
                throw new ApiException(0, "回复已达到长度上限。");
            case "content_filter":
                throw new ApiException(0, "回复被 DeepSeek 的内容过滤机制中止。");
            case "insufficient_system_resource":
                throw new ApiException(0, "DeepSeek 服务繁忙，回复已中断。请稍后重试。");
            case "aborted":
                throw new ApiException(0, "DeepSeek 已中断回复，请重试。");
            case "tool_calls":
                throw new ApiException(0, "DeepSeek 返回了意外的工具调用。");
            default:
                break;
        }
    }

    static HttpURLConnection open(String path, String key, String method, String accept, int readTimeout)
            throws IOException {
        String token = key == null ? "" : key.trim();
        if (token.isEmpty()) throw new ApiException(0, "请先设置 DeepSeek API Key。");
        for (int i = 0; i < token.length(); i++) {
            char character = token.charAt(i);
            if (character <= ' ' || character >= 127) {
                throw new ApiException(0, "DeepSeek API Key 格式有误，请重新粘贴。");
            }
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(readTimeout);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", accept);
        return connection;
    }

    private static BufferedReader reader(InputStream input) {
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static void checkResponse(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status >= 200 && status < 300) return;
        throw httpError(status);
    }

    private static ApiException httpError(int status) {
        if (status >= 300 && status < 400) return new ApiException(status, "DeepSeek 接口返回了意外的重定向。");

        switch (status) {
            case 400:
            case 422:
                return new ApiException(status, "DeepSeek 无法处理这条请求。");
            case 401:
                return new ApiException(status, "API Key 验证失败，请检查密钥。");
            case 403:
                return new ApiException(status, "DeepSeek 拒绝了这次请求，请检查访问权限。");
            case 402:
                return new ApiException(status, "DeepSeek API 余额不足，请到开放平台充值。");
            case 408:
            case 504:
                return new ApiException(status, "DeepSeek 请求超时，请重试。");
            case 413:
                return new ApiException(status, "这次请求太长，可以缩短消息或新建对话。");
            case 429:
                return new ApiException(status, "请求过于频繁，请稍后重试。");
            case 503:
                return new ApiException(status, "DeepSeek 服务繁忙，请稍后重试。");
            default:
                if (status >= 500) return new ApiException(status, "DeepSeek 服务暂时不可用，请稍后重试。");
                return new ApiException(status, "DeepSeek 请求失败（HTTP " + status + "）。");
        }
    }

    private static ApiException stopped() {
        return new ApiException(0, "已停止生成。");
    }

    static ApiException networkError(IOException error) {
        if (error instanceof ApiException) return (ApiException) error;
        if (error instanceof HttpRetryException) return httpError(((HttpRetryException) error).responseCode());
        if (error instanceof SocketTimeoutException) return new ApiException(0, "连接 DeepSeek 超时，请重试。");
        if (error instanceof UnknownHostException) return new ApiException(0, "无法解析 DeepSeek 域名，请检查网络。");
        if (error instanceof SSLException) return new ApiException(0, "与 DeepSeek 的安全连接失败，请检查网络。");
        return new ApiException(0, "连接 DeepSeek 失败，请检查网络后重试。");
    }
}

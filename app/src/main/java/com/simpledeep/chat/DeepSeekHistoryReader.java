package com.simpledeep.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads legacy conversations.json arrays and current chat_sessions/{id}.json export entries. */
final class DeepSeekHistoryReader {
    private DeepSeekHistoryReader() {}

    interface Sink {
        void accept(String sourceId, String title, long updatedAt, List<DeepSeekApi.Message> messages)
                throws IOException;
    }

    /** Returns the number of empty, unsupported or oversized conversations. Caller owns the reader. */
    static int read(Reader input, Sink sink) throws IOException {
        JsonRecords json = new JsonRecords(input);
        int skipped = 0;
        if (json.take('[')) {
            json.whitespace();
            if (!json.take(']')) {
                do {
                    json.whitespace();
                    if (!readRecord(json, sink)) skipped++;
                    json.whitespace();
                } while (json.take(','));
                json.expect(']');
            }
        } else if (json.current == '{') {
            if (!readRecord(json, sink)) skipped++;
        } else {
            throw new IOException("未识别出 DeepSeek 导出 JSON。");
        }
        json.finish();
        return skipped;
    }

    /** Reads one complete manifest or session index, with the same syntax, depth and allocation limits. */
    static Object readMetadata(Reader input) throws IOException {
        JsonRecords json = new JsonRecords(input);
        boolean array = json.current == '[';
        if (!array && json.current != '{') throw new IOException("导出包元数据格式错误。");
        String text = json.record(true);
        json.finish();
        if (text == null) throw new IOException("导出包元数据过大。");
        try {
            return array ? new JSONArray(text) : new JSONObject(text);
        } catch (JSONException invalid) {
            throw new IOException("导出包元数据格式错误。", invalid);
        }
    }

    private static boolean readRecord(JsonRecords json, Sink sink) throws IOException {
        String text = json.record(false);
        if (text == null) return false;
        JSONObject chat;
        try {
            chat = new JSONObject(text);
        } catch (JSONException invalid) {
            throw new IOException("会话 JSON 无法解析。", invalid);
        }
        String id = textField(chat, "id");
        if (id == null || id.trim().isEmpty() || id.length() > 256) return false;
        JSONObject mapping = chat.optJSONObject("mapping");
        Map<String, Node> nodes = mapping != null ? legacyNodes(mapping) : currentNodes(chat.optJSONArray("messages"));
        if (nodes == null || nodes.isEmpty()) return false;
        String selected = nodeId(chat.opt("current_message_id"));
        if (selected == null && mapping != null) selected = nodeId(chat.opt("current_node"));
        List<Node> branch = selectBranch(nodes, selected);
        if (branch == null) return false;

        long lastMessage = 0;
        List<DeepSeekApi.Message> messages = new ArrayList<>();
        for (Node node : branch) {
            if (node.message == null) continue; // Synthetic root in the old export.
            lastMessage = Math.max(lastMessage, node.time);
            addMessages(node.message, messages);
        }
        if (messages.isEmpty()) return false;
        long updatedAt = time(chat, "updated_at");
        if (updatedAt == 0) updatedAt = Math.max(lastMessage, time(chat, "inserted_at"));
        checkInterrupted();
        sink.accept(id, textField(chat, "title"), updatedAt, messages);
        return true;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已中止。");
    }

    private static Map<String, Node> legacyNodes(JSONObject mapping) {
        Map<String, Node> nodes = new HashMap<>();
        for (Iterator<String> keys = mapping.keys(); keys.hasNext();) {
            String id = keys.next();
            JSONObject raw = mapping.optJSONObject(id);
            if (raw == null || !raw.has("parent")) return null;
            String statedId = nodeId(raw.opt("id"));
            if (statedId != null && !id.equals(statedId)) return null;
            JSONObject message = raw.optJSONObject("message");
            if (message == null && !raw.isNull("message")) return null;
            String parent = nodeId(raw.opt("parent"));
            if (parent == null && !raw.isNull("parent")) return null;
            nodes.put(id, new Node(id, parent, message));
        }
        return nodes;
    }

    private static Map<String, Node> currentNodes(JSONArray entries) {
        if (entries == null) return null;
        Map<String, Node> nodes = new HashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject message = entries.optJSONObject(i);
            if (message == null || !message.has("parent_id") || message.optJSONArray("fragments") == null) return null;
            String role = textField(message, "role");
            if (!"USER".equals(role) && !"ASSISTANT".equals(role)) return null;
            String id = nodeId(message.opt("id"));
            String parent = nodeId(message.opt("parent_id"));
            if (id == null || (parent == null && !message.isNull("parent_id"))) return null;
            if (nodes.put(id, new Node(id, parent, message)) != null) return null;
        }
        return nodes;
    }

    private static List<Node> selectBranch(Map<String, Node> nodes, String selected) {
        Node tip = selected == null ? null : nodes.get(selected);
        if (tip == null) {
            Set<String> parents = new HashSet<>();
            for (Node node : nodes.values()) parents.add(node.parent);
            for (Node node : nodes.values()) {
                if (!parents.contains(node.id) && (tip == null || node.newerThan(tip))) tip = node;
            }
        }
        if (tip == null) return null;
        List<Node> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (visited.add(tip.id)) {
            chain.add(tip);
            if (tip.parent == null) {
                Collections.reverse(chain);
                return chain;
            }
            tip = nodes.get(tip.parent);
            if (tip == null) break;
        }
        return null;
    }

    private static final class Node {
        final String id;
        final String parent;
        final JSONObject message;
        final long time;
        final long numericId;

        Node(String id, String parent, JSONObject message) {
            this.id = id;
            this.parent = parent;
            this.message = message;
            this.time = message == null ? 0 : time(message, "inserted_at");
            long numeric = -1;
            try {
                numeric = Long.parseLong(id);
            } catch (NumberFormatException ignored) {
                // Legacy format also allows named nodes.
            }
            numericId = numeric;
        }

        boolean newerThan(Node other) {
            // DeepSeek's official fallback is the greatest message id. Old named leaves use their date.
            if (numericId != other.numericId) return numericId > other.numericId;
            return time != other.time ? time > other.time : id.compareTo(other.id) > 0;
        }
    }

    private static String nodeId(Object value) {
        if (value instanceof Integer || value instanceof Long) return value.toString();
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    private static String textField(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    static long time(JSONObject object, String key) {
        String date = textField(object, key);
        if (date == null || date.length() > 40) return 0;
        try {
            return Math.max(0, OffsetDateTime.parse(date).toInstant().toEpochMilli());
        } catch (DateTimeParseException | ArithmeticException invalid) {
            return 0;
        }
    }

    private static void addMessages(JSONObject message, List<DeepSeekApi.Message> result) {
        JSONArray fragments = message.optJSONArray("fragments");
        if (fragments == null) return;
        String role = textField(message, "role");
        boolean user = role == null || "USER".equalsIgnoreCase(role);
        boolean assistant = role == null || "ASSISTANT".equalsIgnoreCase(role);
        StringBuilder request = new StringBuilder();
        StringBuilder response = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<String> files = new ArrayList<>();
        if (user) addFileNames(message.optJSONArray("files"), files);
        List<DeepSeekApi.Citation> sources = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (int i = 0; i < fragments.length(); i++) {
            JSONObject fragment = fragments.optJSONObject(i);
            if (fragment == null) continue;
            String type = textField(fragment, "type");
            String content = textField(fragment, "content");
            if ("REQUEST".equals(type) && user && content != null) request.append(content);
            if ("FILE".equals(type) && user) addFileNames(fragment.optJSONArray("files"), files);
            if (!assistant) continue;
            if ("RESPONSE".equals(type) && content != null) response.append(content);
            if ("THINK".equals(type) && content != null) reasoning.append(content);
            if ("TOOL_OPEN".equals(type) && "FINISHED".equals(fragment.opt("status"))) addSource(fragment, sources, urls);
            if ("TOOL_SEARCH".equals(type) || "READ_LINK".equals(type) || "SEARCH".equals(type)) {
                JSONArray items = fragment.optJSONArray("results");
                if (items == null) continue;
                for (int j = 0; j < items.length(); j++) addSource(items.optJSONObject(j), sources, urls);
            }
        }
        for (String file : files) {
            if (request.length() > 0) request.append('\n');
            request.append("[历史附件：").append(file).append(']');
        }
        if (request.length() > 0) result.add(new DeepSeekApi.Message("user", request.toString()));
        // A filtered reply's raw content is not part of the visible conversation.
        if ("CONTENT_FILTER".equals(message.opt("status"))) return;
        if (response.length() > 0 || reasoning.length() > 0 || !sources.isEmpty()) {
            result.add(new DeepSeekApi.Message("assistant", response.toString(), null, sources, null, reasoning.toString()));
        }
    }

    private static void addFileNames(JSONArray entries, List<String> files) {
        if (entries == null) return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            String name = item == null ? null : textField(item, "file_name");
            if (name != null && !name.trim().isEmpty()) files.add(name);
        }
    }

    private static void addSource(JSONObject item, List<DeepSeekApi.Citation> sources, Set<String> urls) {
        if (item == null) return;
        String url = textField(item, "url");
        if (url == null) return;
        try {
            URI address = new URI(url);
            String scheme = address.getScheme();
            if (address.getHost() == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return;
        } catch (URISyntaxException invalid) {
            return;
        }
        String title = textField(item, "title");
        if (urls.add(url)) sources.add(new DeepSeekApi.Citation(title == null ? "" : title, url));
    }

    /** Frames and validates one JSON value before passing it to Android's lenient, in-memory org.json. */
    private static final class JsonRecords {
        private static final int MAX_CHARS = 4 * 1024 * 1024;
        private static final int MAX_VALUES = 100_000;
        private static final int MAX_DEPTH = 48;
        private final Reader input;
        private final char[] chunk = new char[8192];
        private int offset;
        private int available;
        private int current;
        private StringBuilder buffer;
        private int valueCount;

        JsonRecords(Reader input) throws IOException {
            this.input = input;
            current = readChar();
            if (current == '\ufeff') next();
            whitespace();
        }

        private int readChar() throws IOException {
            while (offset >= available) {
                checkInterrupted();
                available = input.read(chunk);
                offset = 0;
                if (available < 0) return -1;
            }
            return chunk[offset++];
        }

        void next() throws IOException {
            if (buffer != null) {
                if (buffer.length() == MAX_CHARS) buffer = null;
                else buffer.append((char) current);
            }
            current = readChar();
        }

        boolean take(int token) throws IOException {
            if (current != token) return false;
            next();
            return true;
        }

        void expect(int token) throws IOException {
            if (!take(token)) throw malformed();
        }

        void whitespace() throws IOException {
            while (current == ' ' || current == '\t' || current == '\r' || current == '\n') next();
        }

        void finish() throws IOException {
            whitespace();
            if (current != -1) throw new IOException("导出 JSON 末尾有多余内容。");
            checkInterrupted();
        }

        String record(boolean keepArray) throws IOException {
            valueCount = 0;
            buffer = current == '{' || keepArray && current == '[' ? new StringBuilder() : null;
            value(0);
            String text = buffer == null ? null : buffer.toString();
            buffer = null;
            return text;
        }

        private void value(int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new IOException("会话 JSON 嵌套层数过多。");
            if (++valueCount > MAX_VALUES) buffer = null;
            switch (current) {
                case '{':
                case '[':
                    boolean object = current == '{';
                    int end = object ? '}' : ']';
                    next();
                    whitespace();
                    if (take(end)) return;
                    do {
                        whitespace();
                        if (object) {
                            string();
                            whitespace();
                            expect(':');
                            whitespace();
                        }
                        value(depth + 1);
                        whitespace();
                    } while (take(','));
                    expect(end);
                    return;
                case '"': string(); return;
                case 't': literal("true"); return;
                case 'f': literal("false"); return;
                case 'n': literal("null"); return;
                default: number();
            }
        }

        private void literal(String word) throws IOException {
            for (int i = 0; i < word.length(); i++) expect(word.charAt(i));
        }

        private void string() throws IOException {
            expect('"');
            while (!take('"')) {
                if (current < ' ') throw malformed();
                if (take('\\')) {
                    if (take('u')) {
                        for (int i = 0; i < 4; i++) {
                            if (!(current >= '0' && current <= '9' || current >= 'a' && current <= 'f'
                                    || current >= 'A' && current <= 'F')) throw malformed();
                            next();
                        }
                    } else {
                        if (current < 0 || "\"\\/bfnrt".indexOf(current) < 0) throw malformed();
                        next();
                    }
                } else {
                    next();
                }
            }
        }

        private void number() throws IOException {
            take('-');
            if (!take('0')) digits();
            if (take('.')) digits();
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits();
            }
        }

        private void digits() throws IOException {
            if (current < '0' || current > '9') throw malformed();
            do { next(); } while (current >= '0' && current <= '9');
        }

        private IOException malformed() {
            return new IOException("导出 JSON 格式错误或文件不完整。");
        }
    }
}

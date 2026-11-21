package com.simpledeep.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DeepSeekHistoryReaderTest {
    public static void main(String[] args) throws Exception {
        legacyMapping();
        currentBranch();
        missingBranchPointer();
        invalidAndEmptyConversations();
        sourceReferences();
        streamedRecordsAndLimits();
        malformedDocuments();
        sinkFailure();
        metadata();
        cancellation();
        System.out.println("DeepSeekHistoryReaderTest: all 10 test groups passed");
    }

    // Synthetic messages conforming to the published conversations.example.json mapping.
    private static void legacyMapping() throws Exception {
        JSONObject mapping = new JSONObject();
        mapping.put("root", oldNode("root", null, null));
        JSONObject user = fragments(fragment("REQUEST", "写出 "), fragment("REQUEST", "hello。"));
        mapping.put("1", oldNode("1", "root", user));
        JSONObject discarded = fragments(fragment("RESPONSE", "other branch"));
        mapping.put("4", oldNode("4", "1", discarded));
        JSONObject reply = fragments(fragment("THINK", "先看格式"), fragment("RESPONSE", "  `x`"),
                fragment("RESPONSE", "\n完成。")).put("inserted_at", "2025-01-27T14:00:00.000001+08:00");
        mapping.put("2", oldNode("2", "1", reply));
        JSONObject chat = new JSONObject().put("id", "old-example").put("title", "旧会话")
                .put("updated_at", "2025-01-27T14:01:00.123456+08:00").put("current_node", "2").put("mapping", mapping);
        List<Chat> imported = new ArrayList<>();
        int skipped = parse("\ufeff  " + new JSONArray().put(chat).put(v3()) + "\r\n", imported);
        check(skipped == 0 && imported.size() == 2);
        Chat old = imported.get(0);
        check("old-example".equals(old.id) && "旧会话".equals(old.title));
        check(old.time == Instant.parse("2025-01-27T06:01:00.123Z").toEpochMilli());
        check(old.messages.size() == 2 && "user".equals(old.messages.get(0).role));
        check("写出 hello。".equals(old.messages.get(0).content));
        check("  `x`\n完成。".equals(old.messages.get(1).content));
        check("先看格式".equals(old.messages.get(1).reasoningContent));
    }

    // Official v3 session file shape. Numeric IDs need not appear in branch order in the file.
    private static JSONObject v3() throws Exception {
        JSONObject attachment = new JSONObject().put("file_name", "参考.pdf").put("id", "website-id");
        JSONObject request = v3Node(1, null, "USER", new JSONObject().put("type", "FILE")
                .put("files", new JSONArray().put(attachment)), fragment("REQUEST", "总结"));
        JSONObject original = v3Node(2, 1, "ASSISTANT", fragment("THINK", "想一想"), fragment("RESPONSE", "原答"))
                .put("inserted_at", "2026-11-12T02:04:05.000000Z");
        JSONObject alternative = v3Node(10, 1, "ASSISTANT", fragment("RESPONSE", "重试答复"));
        JSONObject descendant = v3Node(11, 10, "USER", fragment("REQUEST", "追问"));
        return new JSONObject().put("id", "session-123").put("current_message_id", 2)
                .put("inserted_at", "2026-11-12T02:03:00Z").put("title", JSONObject.NULL)
                .put("messages", new JSONArray().put(original).put(descendant).put(request).put(alternative));
    }

    private static void currentBranch() throws Exception {
        List<Chat> imported = new ArrayList<>();
        check(parse(v3().toString(), imported) == 0);
        Chat chat = imported.get(0);
        check(chat.title == null && "session-123".equals(chat.id));
        check(chat.time == Instant.parse("2026-11-12T02:04:05Z").toEpochMilli());
        check(chat.messages.size() == 2 && chat.messages.get(0).content.equals("总结\n[历史附件：参考.pdf]"));
        check(chat.messages.get(0).attachments.isEmpty());
        check("原答".equals(chat.messages.get(1).content));
        check("想一想".equals(chat.messages.get(1).reasoningContent));
        JSONObject interior = v3().put("current_message_id", 1);
        imported.clear();
        check(parse(interior.toString(), imported) == 0 && imported.get(0).messages.size() == 1);
    }

    private static void missingBranchPointer() throws Exception {
        JSONObject chat = v3().put("current_message_id", 999);
        List<Chat> imported = new ArrayList<>();
        check(parse(chat.toString(), imported) == 0);
        List<DeepSeekApi.Message> messages = imported.get(0).messages;
        check(messages.size() == 3 && "重试答复".equals(messages.get(1).content) && "追问".equals(messages.get(2).content));
        JSONObject mapping = new JSONObject().put("root", oldNode("root", null, null));
        mapping.put("earlier", oldNode("earlier", "root", fragments(fragment("REQUEST", "早"))
                .put("inserted_at", "2025-01-01T00:00:00Z")));
        mapping.put("later", oldNode("later", "root", fragments(fragment("REQUEST", "晚"))
                .put("inserted_at", "2025-01-02T00:00:00Z")));
        imported.clear();
        check(parse(new JSONObject().put("id", "legacy").put("mapping", mapping).toString(), imported) == 0);
        check("晚".equals(imported.get(0).messages.get(0).content));
    }

    private static void invalidAndEmptyConversations() throws Exception {
        JSONObject orphan = v3();
        orphan.getJSONArray("messages").getJSONObject(0).put("parent_id", 200);
        JSONObject cycle = v3();
        cycle.getJSONArray("messages").getJSONObject(0).put("parent_id", 2);
        JSONObject duplicate = v3();
        duplicate.getJSONArray("messages").put(duplicate.getJSONArray("messages").getJSONObject(0));
        JSONObject empty = new JSONObject().put("id", "empty").put("mapping", new JSONObject());
        JSONArray array = new JSONArray().put(orphan).put(cycle).put(duplicate).put(empty)
                .put(new JSONObject().put("api_key", "unrelated")).put(JSONObject.NULL).put(v3());
        List<Chat> imported = new ArrayList<>();
        check(parse(array.toString(), imported) == 6 && imported.size() == 1);
    }

    private static void sourceReferences() throws Exception {
        JSONObject website = new JSONObject().put("url", "https://example.org/path");
        JSONArray links = new JSONArray().put(website).put(website).put(new JSONObject().put("url", "file:///local"));
        JSONObject reply = v3Node(2, 1, "ASSISTANT", fragment("RESPONSE", "有出处"),
                new JSONObject().put("type", "READ_LINK").put("results", links));
        JSONObject chat = v3();
        chat.getJSONArray("messages").put(0, reply);
        List<Chat> imported = new ArrayList<>();
        check(parse(chat.toString(), imported) == 0);
        DeepSeekApi.Message answer = imported.get(0).messages.get(1);
        check(answer.citations.size() == 1 && "".equals(answer.citations.get(0).title));
        check("https://example.org/path".equals(answer.citations.get(0).url) && answer.serverToolCallsJson == null);
    }

    private static void streamedRecordsAndLimits() throws Exception {
        char[] chars = new char[4 * 1024 * 1024 + 1];
        Arrays.fill(chars, 'x');
        String large = "[{\"large\":\"" + new String(chars) + "\"}," + v3() + "]";
        List<Chat> imported = new ArrayList<>();
        check(parse(large, imported) == 1 && imported.size() == 1);
        StringBuilder dense = new StringBuilder("[{\"large\":[");
        for (int i = 0; i < 105_000; i++) dense.append("0,");
        dense.append("0]},").append(v3()).append(']');
        imported.clear();
        check(parse(dense.toString(), imported) == 1 && imported.size() == 1);
    }

    private static void malformedDocuments() throws Exception {
        StringBuilder deep = new StringBuilder("{\"x\":");
        for (int i = 0; i < 60; i++) deep.append('[');
        deep.append('0');
        for (int i = 0; i < 60; i++) deep.append(']');
        deep.append('}');
        for (String value : Arrays.asList("[]garbage", "{}{}", "[{},]", "[{\"x\":}", "[", "[] []",
                "[{\"x\":'forbidden'}]", "[{\"x\":\"\\x\"}]", "[{\"x\":00}]", deep.toString())) {
            try {
                parse(value, new ArrayList<>());
                throw new AssertionError("invalid JSON accepted");
            } catch (IOException expected) {
                check(!expected.getMessage().isEmpty());
            }
        }
    }

    private static void sinkFailure() throws Exception {
        IOException failed = new IOException("storage");
        try {
            DeepSeekHistoryReader.read(new StringReader(v3().toString()), (id, title, time, messages) -> { throw failed; });
            throw new AssertionError("sink failure swallowed");
        } catch (IOException expected) {
            check(failed == expected);
        }
    }

    private static void metadata() throws Exception {
        Object manifest = DeepSeekHistoryReader.readMetadata(new StringReader("\ufeff{\"export_format_version\":3} "));
        check(((JSONObject) manifest).getInt("export_format_version") == 3);
        Object index = DeepSeekHistoryReader.readMetadata(new StringReader("\n[{\"id\":\"a\"}]\r"));
        check("a".equals(((JSONArray) index).getJSONObject(0).getString("id")));
        char[] chars = new char[4 * 1024 * 1024];
        Arrays.fill(chars, 'a');
        for (String input : Arrays.asList("true", "{}[]", "[{},]", "{ unquoted: 0 }", "[\"" + new String(chars) + "\"]")) {
            try {
                DeepSeekHistoryReader.readMetadata(new StringReader(input));
                throw new AssertionError("invalid archive metadata accepted");
            } catch (IOException expected) {
                check(!expected.getMessage().isEmpty());
            }
        }
    }

    private static void cancellation() throws Exception {
        Reader cancelling = new Reader() {
            int reads;
            @Override public int read(char[] buffer, int offset, int length) {
                Arrays.fill(buffer, offset, offset + length, ' ');
                if (++reads == 2) Thread.currentThread().interrupt();
                if (reads > 2) throw new AssertionError("cancelled import kept reading");
                return length;
            }
            @Override public void close() {}
        };
        try {
            DeepSeekHistoryReader.read(cancelling, (id, title, time, messages) -> { throw new AssertionError(); });
            throw new AssertionError("interruption not reported");
        } catch (InterruptedIOException expected) {
            check(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static int parse(String input, List<Chat> imported) throws IOException {
        return DeepSeekHistoryReader.read(new StringReader(input), (id, title, time, messages) ->
                imported.add(new Chat(id, title, time, messages)));
    }

    private static JSONObject oldNode(String id, String parent, JSONObject message) throws Exception {
        return new JSONObject().put("id", id).put("parent", parent == null ? JSONObject.NULL : parent)
                .put("message", message == null ? JSONObject.NULL : message);
    }

    private static JSONObject v3Node(int id, Integer parent, String role, JSONObject... items) throws Exception {
        return fragments(items).put("id", id).put("parent_id", parent == null ? JSONObject.NULL : parent)
                .put("role", role).put("status", "FINISHED").put("inserted_at", "2026-11-12T02:03:04Z");
    }

    private static JSONObject fragments(JSONObject... items) throws Exception {
        return new JSONObject().put("fragments", new JSONArray(Arrays.asList(items)));
    }

    private static JSONObject fragment(String type, String content) throws Exception {
        return new JSONObject().put("type", type).put("content", content);
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    private static class Chat {
        final String id;
        final String title;
        final long time;
        final List<DeepSeekApi.Message> messages;
        Chat(String id, String title, long time, List<DeepSeekApi.Message> messages) {
            this.id = id;
            this.title = title;
            this.time = time;
            this.messages = messages;
        }
    }
}

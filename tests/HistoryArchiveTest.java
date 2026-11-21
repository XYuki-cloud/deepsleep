package com.simpledeep.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class HistoryArchiveTest {
    private static final String SESSION = "{\"id\":\"a\",\"title\":\"旧对话\",\"current_message_id\":2,"
            + "\"messages\":[{\"id\":1,\"parent_id\":null,\"role\":\"USER\",\"fragments\":[{\"type\":\"REQUEST\",\"content\":\"问题\"}]},"
            + "{\"id\":2,\"parent_id\":1,\"role\":\"ASSISTANT\",\"fragments\":[{\"type\":\"RESPONSE\",\"content\":\"回答\"}]}]}";
    private static final String MANIFEST = "{\"export_format_version\":3}";
    private static final String INDEX = "[{\"id\":\"a\",\"updated_at\":\"2025-02-01T12:00:00Z\"}]";

    public static void main(String[] args) throws Exception {
        File cache = Files.createTempDirectory("archive-test").toFile();
        ConversationStore store = new ConversationStore(new File(cache, "store"), "[]", "原草稿");
        String active = store.current().id;
        byte[] valid = zip("manifest.json", MANIFEST, "user.json", "ignored private metadata",
                "chat_sessions/a_files/unrelated.json", "not a conversation", "chat_sessions/a.json", SESSION,
                "chat_session_index.json", INDEX);
        HistoryArchive.Result imported = read(valid, cache, store);
        check(imported.added == 1 && imported.skipped == 0 && imported.duplicates == 0);
        check(active.equals(store.current().id) && store.current().draft.equals("原草稿"));
        check(store.list().size() == 2);
        check(store.list().stream().filter(chat -> "旧对话".equals(chat.title)).findFirst().get().updatedAt ==
                java.time.Instant.parse("2025-02-01T12:00:00Z").toEpochMilli());
        check(read(valid, cache, store).duplicates == 1);
        check(read(SESSION.getBytes(StandardCharsets.UTF_8), cache, store).duplicates == 1);

        String legacy = "[{\"id\":\"old\",\"mapping\":{\"root\":{\"parent\":null,\"message\":null},"
                + "\"x\":{\"parent\":\"root\",\"message\":{\"fragments\":[{\"type\":\"REQUEST\",\"content\":\"早先的消息\"}]}}}}]";
        check(read(zip("conversations.json", legacy, "user.json", "ignored"), cache, store).added == 1);
        check(read(zip("manifest.json", MANIFEST, "chat_session_index.json", "[{\"id\":\"absent\"}]"), cache, store).skipped == 1);

        reject(zip("manifest.json", "{\"export_format_version\":3.5}", "chat_session_index.json", INDEX), cache, store);
        reject(zip("manifest.json", "{\"export_format_version\":999}", "chat_session_index.json", INDEX), cache, store);
        reject(zip("manifest.json", MANIFEST, "chat_session_index.json", "[{\"id\":\"../user\"}]"), cache, store);
        reject(zip("manifest.json", MANIFEST, "chat_session_index.json", INDEX, "chat_sessions/a.json",
                SESSION.replace("\"id\":\"a\"", "\"id\":\"b\"")), cache, store);
        reject(zip("manifest.json", MANIFEST, "chat_session_index.json", INDEX + "garbage"), cache, store);
        reject(zip("user.json", "{}"), cache, store);
        reject(new byte[]{'P','K',1}, cache, store);
        check(store.list().size() == 3 && !new File(cache, "deepseek-history-import.tmp").exists());
        System.out.println("History archive tests passed (ZIP v3, legacy, JSON, duplicates, metadata, invalid inputs)");
    }

    private static HistoryArchive.Result read(byte[] data, File cache, ConversationStore store) throws IOException {
        HistoryArchive.Result result = new HistoryArchive.Result();
        HistoryArchive.read(new ByteArrayInputStream(data), cache, store, result);
        return result;
    }

    private static void reject(byte[] data, File cache, ConversationStore store) throws Exception {
        try { read(data, cache, store); } catch (IOException expected) { return; }
        throw new AssertionError("Incorrect archive accepted");
    }

    private static byte[] zip(String... fields) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < fields.length; i += 2) {
                zip.putNextEntry(new ZipEntry(fields[i]));
                zip.write(fields[i+1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
}

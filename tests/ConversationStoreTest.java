package com.simpledeep.chat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/** Exercises local conversation storage without Android or a network connection. */
public final class ConversationStoreTest {
    private static final String LEGACY_CHAT = "[\"旧问题\",\"旧答案\",\"结尾待答\"]";
    private static final String LEGACY_DRAFT = "还在写";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("simpledeep-history-test-");
        try {
            migratesChatAndDraftOnce(root.resolve("migration").toFile());
            preservesIndependentDraftsAndActiveSelection(root.resolve("selection").toFile());
            preservesCompleteMessages(root.resolve("rich").toFile());
            renamesDeletesAndKeepsRecency(root.resolve("changes").toFile());
            ignoresAbandonedWrite(root.resolve("interrupted").toFile());
            malformedHistoryLeavesCurrentChatUsable(root.resolve("damaged").toFile());
            savesAttachmentWithoutText(root.resolve("attachment").toFile());
            importsWithoutChangingActiveChat(root.resolve("import").toFile());
            deduplicatesImportsAfterLocalEdits(root.resolve("retry").toFile());
            importsWithoutIdByContent(root.resolve("fingerprints").toFile());
            isolatesFutureImportedTimestamp(root.resolve("future").toFile());
            importsOneCopyFromConcurrentRetries(root.resolve("concurrent").toFile());
            System.out.println("ConversationStore tests passed (12 cases)");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void migratesChatAndDraftOnce(File directory) {
        ConversationStore store = open(directory);
        ConversationStore.Conversation migrated = store.current();
        equal("旧问题", migrated.title);
        equal(LEGACY_DRAFT, migrated.draft);
        equal(3, migrated.messages.size());
        equal("旧答案", migrated.messages.get(1).content);
        equal("user", migrated.messages.get(2).role);

        ConversationStore reloaded = open(directory);
        equal(migrated.id, reloaded.current().id);
        equal(1, reloaded.list().size());

        ConversationStore.Conversation blank = reloaded.delete(migrated);
        check(!blank.id.equals(migrated.id));
        equal(0, blank.messages.size());
        equal("", blank.draft);
        equal(blank.id, open(directory).current().id);
        equal(1, open(directory).list().size());
    }

    private static void preservesIndependentDraftsAndActiveSelection(File directory) {
        ConversationStore store = open(directory);
        ConversationStore.Conversation oldChat = store.current();
        ConversationStore.Conversation newChat = store.startNew();
        store.save(newChat, Collections.emptyList(), "另一份草稿\n第二行");
        store.select(oldChat.id);
        ConversationStore reloaded = open(directory);
        equal(oldChat.id, reloaded.current().id);
        equal(LEGACY_DRAFT, reloaded.current().draft);
        equal("另一份草稿\n第二行", reloaded.select(newChat.id).draft);
        equal(newChat.id, open(directory).current().id);
    }

    private static void preservesCompleteMessages(File directory) {
        ConversationStore store = open(directory);
        ConversationStore.Conversation chat = store.startNew();
        List<DeepSeekApi.Message> messages = new ArrayList<>();
        messages.add(new DeepSeekApi.Message("user", "说明 😀", Arrays.asList(
                new DeepSeekApi.Attachment("image-id", "图片.png", true),
                new DeepSeekApi.Attachment("doc-id", "文件.pdf")), Collections.emptyList(), null));
        for (int i = 0; i < 75; i++) {
            messages.add(new DeepSeekApi.Message("assistant", "回答\n第 " + i + " 次",
                    Collections.emptyList(), Collections.singletonList(
                            new DeepSeekApi.Citation("来源", "https://example.com/", 2, 3)),
                    "[{\"id\":\"fixture\"}]", "推理 🤔"));
        }
        store.save(chat, messages, "");

        ConversationStore.Conversation reloaded = open(directory).current();
        equal("说明 😀", reloaded.title);
        equal("回答 第 74 次", reloaded.preview);
        equal(76, reloaded.messages.size());
        equal("image-id", reloaded.messages.get(0).attachments.get(0).fileId);
        equal(true, reloaded.messages.get(0).attachments.get(0).image);
        equal("文件.pdf", reloaded.messages.get(0).attachments.get(1).name);
        DeepSeekApi.Message answer = reloaded.messages.get(75);
        equal("回答\n第 74 次", answer.content);
        equal("推理 🤔", answer.reasoningContent);
        equal("[{\"id\":\"fixture\"}]", answer.serverToolCallsJson);
        equal("来源", answer.citations.get(0).title);
        equal("https://example.com/", answer.citations.get(0).url);
        equal(2, answer.citations.get(0).startIndex);
        equal(3, answer.citations.get(0).endIndex);
    }

    private static void renamesDeletesAndKeepsRecency(File directory) {
        ConversationStore store = open(directory);
        ConversationStore.Conversation older = store.current();
        ConversationStore.Conversation newer = store.startNew();
        equal(newer.id, store.list().get(0).id);
        store.rename(older, "  以前的会话  ");
        store.save(older, older.messages, "更新后的草稿");
        equal(older.id, store.list().get(0).id);
        equal("以前的会话", open(directory).list().get(0).title);
        store.select(older.id);
        equal(newer.id, store.delete(older).id);
        store.save(older, older.messages, "不会恢复已删除的会话");
        equal(1, open(directory).list().size());
        equal(newer.id, store.select(older.id).id);
    }

    private static void ignoresAbandonedWrite(File directory) throws Exception {
        ConversationStore store = open(directory);
        ConversationStore.Conversation chat = store.current();
        Files.write(new File(directory, "abandoned.tmp").toPath(), "bad".getBytes(StandardCharsets.UTF_8));
        ConversationStore reloaded = open(directory);
        equal(1, reloaded.list().size());
        equal(chat.id, reloaded.current().id);
        equal(LEGACY_DRAFT, reloaded.current().draft);
    }

    private static void malformedHistoryLeavesCurrentChatUsable(File directory) throws Exception {
        ConversationStore store = open(directory);
        ConversationStore.Conversation oldChat = store.current();
        ConversationStore.Conversation active = store.startNew();
        Path damaged = new File(directory, oldChat.id + ".jsonl").toPath();
        String header = Files.readAllLines(damaged, StandardCharsets.UTF_8).get(0);
        Files.write(damaged, Arrays.asList(header, "malformed"), StandardCharsets.UTF_8);

        ConversationStore reloaded = open(directory);
        try {
            reloaded.select(oldChat.id);
            throw new AssertionError("Expected a damaged conversation to be rejected before selecting it");
        } catch (ConversationStore.StorageException expected) {
            equal(active.id, reloaded.current().id);
        }
        Files.write(damaged, Collections.singletonList("bad header"), StandardCharsets.UTF_8);
        equal(active.id, open(directory).current().id);
    }

    private static void savesAttachmentWithoutText(File directory) {
        ConversationStore store = open(directory);
        ConversationStore.Conversation chat = store.startNew();
        List<DeepSeekApi.Message> messages = Collections.singletonList(
                new DeepSeekApi.Message("user", null, Collections.singletonList(
                        new DeepSeekApi.Attachment("file-id", "文档.pdf")), null, null));
        store.save(chat, messages, "");
        ConversationStore.Conversation reloaded = open(directory).current();
        equal("文档.pdf", reloaded.title);
        check(reloaded.messages.get(0).content == null);
        equal("file-id", reloaded.messages.get(0).attachments.get(0).fileId);
    }

    private static ConversationStore open(File directory) {
        return new ConversationStore(directory, LEGACY_CHAT, LEGACY_DRAFT);
    }

    private static void importsWithoutChangingActiveChat(File directory) {
        ConversationStore store = open(directory);
        ConversationStore.Conversation active = store.current();
        List<DeepSeekApi.Message> messages = Arrays.asList(new DeepSeekApi.Message("user", "旧问题？"),
                new DeepSeekApi.Message("assistant", "两行\n回答", null, null, null, "思考过程"));
        long timestamp = 1_650_000_000_000L;
        check(store.importConversation(active.id, "导出的标题", timestamp, messages));

        ConversationStore reloaded = open(directory);
        equal(active.id, reloaded.current().id);
        equal(LEGACY_DRAFT, reloaded.current().draft);
        equal(2, reloaded.list().size());
        ConversationStore.Conversation imported = reloaded.list().get(1);
        check(!imported.id.equals(active.id));
        equal("导出的标题", imported.title);
        equal(timestamp, imported.updatedAt);
        equal("两行 回答", imported.preview);
        equal("思考过程", imported.messages.get(1).reasoningContent);
        equal("两行\n回答", imported.messages.get(1).content);
        equal("", imported.draft);
        equal("导出的标题", reloaded.save(imported, messages, "导入后编辑").title);
    }

    private static void deduplicatesImportsAfterLocalEdits(File directory) {
        ConversationStore store = open(directory);
        String externalId = "../../current";
        List<DeepSeekApi.Message> original = Collections.singletonList(new DeepSeekApi.Message("user", "原文"));
        check(store.importConversation(externalId, "原始标题", 1, original));
        ConversationStore.Conversation imported = store.list().get(1);
        List<DeepSeekApi.Message> continued = new ArrayList<>(original);
        continued.add(new DeepSeekApi.Message("assistant", "在本机续聊"));
        store.select(imported.id);
        store.save(imported, continued, "用户写到一半");
        store.rename(imported, "我的标题");

        ConversationStore reloaded = open(directory);
        check(!reloaded.importConversation(externalId, "重新导出的标题", 2, original));
        equal(2, reloaded.list().size());
        equal(imported.id, reloaded.current().id);
        equal("我的标题", reloaded.current().title);
        equal("用户写到一半", reloaded.current().draft);
        equal("在本机续聊", reloaded.current().messages.get(1).content);
    }

    private static void importsWithoutIdByContent(File directory) {
        ConversationStore store = open(directory);
        List<DeepSeekApi.Message> question = Collections.singletonList(new DeepSeekApi.Message("user", "没有标识"));
        check(store.importConversation(null, null, 0, question));
        check(!open(directory).importConversation("   ", "", 0, question));
        equal("没有标识", store.list().get(0).title);
        check(store.importConversation(null, null, 0, Collections.singletonList(
                new DeepSeekApi.Message("assistant", "没有标识", null, null, null, "推理"))));
        equal(3, store.list().size());
    }

    private static void isolatesFutureImportedTimestamp(File directory) {
        ConversationStore store = open(directory);
        long future = Long.MAX_VALUE - 64;
        check(store.importConversation("未来", null, future, Collections.singletonList(
                new DeepSeekApi.Message("user", "示例"))));
        ConversationStore reloaded = open(directory);
        equal(future, reloaded.list().get(0).updatedAt);
        check(reloaded.startNew().updatedAt < System.currentTimeMillis() + 60_000);
    }

    private static void importsOneCopyFromConcurrentRetries(File directory) throws Exception {
        ConversationStore first = open(directory);
        ConversationStore second = open(directory);
        List<DeepSeekApi.Message> messages = Collections.singletonList(new DeepSeekApi.Message("user", "同一条问题"));
        Callable<Boolean> firstImport = () -> first.importConversation("shared", null, 1, messages);
        Callable<Boolean> retry = () -> second.importConversation("shared", null, 1, messages);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            int inserted = 0;
            for (Future<Boolean> result : workers.invokeAll(Arrays.asList(firstImport, retry))) {
                if (result.get()) inserted++;
            }
            equal(1, inserted);
            equal(2, open(directory).list().size());
        } finally {
            workers.shutdown();
        }
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Expected condition to hold");
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}

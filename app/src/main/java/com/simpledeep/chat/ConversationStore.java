package com.simpledeep.chat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class ConversationStore {
    private static final Object LOCK = new Object();
    private static final String DEFAULT_TITLE = "新对话";
    private static final String FILE_SUFFIX = ".jsonl";
    private static final String WRITE_PREFIX = ".writing-";
    private static long lastUpdatedAt;

    private final File directory;
    private final File activeFile;

    ConversationStore(Context context) {
        this(new File(context.getFilesDir(), "conversations"),
                context.getSharedPreferences("simpledeep", Context.MODE_PRIVATE));
    }

    private ConversationStore(File directory, SharedPreferences legacy) {
        this(directory, legacy.getString("chat", "[]"), legacy.getString("draft", ""));
    }

    ConversationStore(File directory, String legacyChat, String legacyDraft) {
        this.directory = directory;
        this.activeFile = new File(directory, "current");
        synchronized (LOCK) {
            try {
                Files.createDirectories(directory.toPath());
                removeAbandonedWrites();
                if (!activeFile.exists() && list().isEmpty()) {
                    List<DeepSeekApi.Message> messages = migrateMessages(legacyChat);
                    Conversation migrated = create(messages, legacyDraft);
                    setActive(migrated.id);
                }
            } catch (IOException | JSONException error) {
                throw new StorageException(error);
            }
        }
    }

    List<Conversation> list() {
        synchronized (LOCK) {
            File[] files = directory.listFiles((parent, name) -> name.endsWith(FILE_SUFFIX));
            if (files == null) throw new StorageException(new IOException("Cannot read conversations"));
            List<Conversation> saved = new ArrayList<>(files.length);
            for (File file : files) {
                String name = file.getName();
                String id = name.substring(0, name.length() - FILE_SUFFIX.length());
                if (validId(id)) saved.add(readHeader(file, id));
            }
            saved.sort(Comparator.comparingLong((Conversation chat) -> chat.updatedAt)
                    .reversed().thenComparing(chat -> chat.id));
            return saved;
        }
    }

    Conversation current() {
        synchronized (LOCK) {
            Conversation active = find(activeId());
            if (active != null) return active;
            List<Conversation> saved = list();
            if (saved.isEmpty()) return startNew();
            Conversation newest = saved.get(0);
            setActive(newest.id);
            return newest;
        }
    }

    Conversation startNew() {
        synchronized (LOCK) {
            Conversation chat = create(Collections.emptyList(), "");
            setActive(chat.id);
            return chat;
        }
    }

    /** Adds an official export once. A repeated import preserves the local copy and its edits. */
    boolean importConversation(String sourceId, String title, long updatedAt, List<DeepSeekApi.Message> messages) {
        String customTitle = title == null || title.trim().isEmpty() ? null : title;
        String id = importId(sourceId, customTitle, updatedAt, messages);
        synchronized (LOCK) {
            if (conversationFile(id).isFile()) return false;
            Conversation chat = new Conversation(id, customTitle, inferTitle(messages), preview(messages),
                    updatedAt > 0 ? updatedAt : updatedAt(), "", messages);
            writeConversation(chat);
            return true;
        }
    }

    private static String importId(String sourceId, String title, long timestamp, List<DeepSeekApi.Message> messages) {
        MessageDigest hash;
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        hashField(hash, "com.simpledeep.chat/official-export/v1");
        if (sourceId != null && !sourceId.trim().isEmpty()) {
            hashField(hash, "id");
            hashField(hash, sourceId);
        } else {
            hashField(hash, "content");
            hashField(hash, title);
            hashField(hash, Long.toString(Math.max(0, timestamp)));
            for (DeepSeekApi.Message message : messages) {
                hashField(hash, message.role);
                hashField(hash, message.content);
                hashField(hash, message.reasoningContent);
                hashField(hash, message.serverToolCallsJson);
                hashField(hash, Integer.toString(message.attachments.size()));
                for (DeepSeekApi.Attachment attachment : message.attachments) {
                    hashField(hash, attachment.fileId);
                    hashField(hash, attachment.name);
                    hashField(hash, Boolean.toString(attachment.image));
                }
                hashField(hash, Integer.toString(message.citations.size()));
                for (DeepSeekApi.Citation citation : message.citations) {
                    hashField(hash, citation.title);
                    hashField(hash, citation.url);
                    hashField(hash, Integer.toString(citation.startIndex));
                    hashField(hash, Integer.toString(citation.endIndex));
                }
            }
        }
        byte[] bytes = hash.digest();
        // UUIDv8 with the RFC variant, distinct from the random UUIDv4 used for local chats.
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x80);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static void hashField(MessageDigest hash, String value) {
        // Neither byte can appear in UTF-8. Empty, absent and adjacent fields stay distinct.
        if (value == null) hash.update((byte) 0xfe);
        else hash.update(value.getBytes(StandardCharsets.UTF_8));
        hash.update((byte) 0xff);
    }

    Conversation select(String id) {
        synchronized (LOCK) {
            Conversation selected = find(id);
            if (selected == null) return current();
            selected.messages.size();
            if (!id.equals(activeId())) setActive(id);
            return selected;
        }
    }

    Conversation save(Conversation conversation, List<DeepSeekApi.Message> messages, String draft) {
        synchronized (LOCK) {
            Conversation saved = find(conversation.id);
            if (saved == null) return current();
            Conversation previous = saved.updatedAt == conversation.updatedAt ? conversation : saved;
            String text = cleanDraft(draft);
            if (text.equals(saved.draft) && sameMessages(previous.messages, messages)) return previous;
            Conversation updated = new Conversation(saved.id, saved.customTitle, inferTitle(messages),
                    preview(messages), updatedAt(), text,
                    Collections.unmodifiableList(new ArrayList<>(messages)));
            writeConversation(updated);
            return updated;
        }
    }

    void rename(Conversation conversation, String title) {
        synchronized (LOCK) {
            Conversation saved = find(conversation.id);
            String name = title.trim();
            if (saved == null || name.isEmpty() || name.equals(saved.customTitle)) return;
            writeConversation(new Conversation(saved.id, name, saved.title, saved.preview,
                    updatedAt(), saved.draft, saved.messages));
        }
    }

    Conversation delete(Conversation conversation) {
        synchronized (LOCK) {
            Conversation active = current();
            File file = conversationFile(conversation.id);
            if (!file.isFile()) return active;
            if (active.id.equals(conversation.id)) {
                List<Conversation> saved = list();
                for (Conversation other : saved) {
                    if (!other.id.equals(conversation.id)) {
                        active = other;
                        break;
                    }
                }
                if (active.id.equals(conversation.id)) active = startNew();
                else setActive(active.id);
            }
            try {
                Files.delete(file.toPath());
            } catch (IOException error) {
                throw new StorageException(error);
            }
            return active;
        }
    }

    private Conversation create(List<DeepSeekApi.Message> messages, String draft) {
        Conversation chat = new Conversation(UUID.randomUUID().toString(), null, inferTitle(messages),
                preview(messages), updatedAt(), cleanDraft(draft), Collections.unmodifiableList(messages));
        writeConversation(chat);
        return chat;
    }

    private static long updatedAt() {
        lastUpdatedAt = Math.max(lastUpdatedAt + 1, System.currentTimeMillis());
        return lastUpdatedAt;
    }

    private Conversation find(String id) {
        if (!validId(id)) return null;
        File file = conversationFile(id);
        return file.isFile() ? readHeader(file, id) : null;
    }

    private File conversationFile(String id) {
        return new File(directory, id + FILE_SUFFIX);
    }

    private String activeId() {
        if (!activeFile.isFile()) return null;
        try {
            return new String(Files.readAllBytes(activeFile.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new StorageException(error);
        }
    }

    private void setActive(String id) {
        writeAtomic(activeFile, writer -> writer.write(id));
    }

    private Conversation readHeader(File file, String id) {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            if (line == null) throw new IOException("Missing conversation header");
            JSONObject header = new JSONObject(line);
            String customTitle = header.optString("customTitle", null);
            long updatedAt = header.getLong("updatedAt");
            // A future date in an export must not advance the clock for unrelated local edits.
            lastUpdatedAt = Math.max(lastUpdatedAt, Math.min(updatedAt, System.currentTimeMillis()));
            return new Conversation(id, customTitle, header.getString("title"),
                    header.getString("preview"), updatedAt, header.getString("draft"),
                    new SavedMessages(file, header.optInt("messageCount", -1)));
        } catch (IOException | JSONException error) {
            throw new StorageException(error);
        }
    }

    private void writeConversation(Conversation chat) {
        writeAtomic(conversationFile(chat.id), writer -> {
            JSONObject header = new JSONObject();
            header.put("customTitle", chat.customTitle);
            header.put("title", chat.title);
            header.put("preview", chat.preview);
            header.put("updatedAt", chat.updatedAt);
            header.put("draft", chat.draft);
            header.put("messageCount", chat.messages.size());
            writer.write(header.toString());
            writer.newLine();
            for (DeepSeekApi.Message message : chat.messages) {
                writer.write(encodeMessage(message).toString());
                writer.newLine();
            }
        });
    }

    private void writeAtomic(File destination, RecordWriter contents) {
        File temporary = null;
        try {
            temporary = Files.createTempFile(directory.toPath(), WRITE_PREFIX, ".tmp").toFile();
            try (FileOutputStream stream = new FileOutputStream(temporary);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                contents.write(writer);
                writer.flush();
                stream.getFD().sync();
            }
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | JSONException error) {
            throw new StorageException(error);
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    private void removeAbandonedWrites() {
        File[] files = directory.listFiles((parent, name) ->
                name.startsWith(WRITE_PREFIX) && name.endsWith(".tmp"));
        if (files == null) return;
        for (File file : files) file.delete();
    }

    private static List<DeepSeekApi.Message> migrateMessages(String text) throws JSONException {
        List<DeepSeekApi.Message> messages = new ArrayList<>();
        JSONArray legacy;
        try {
            legacy = new JSONArray(text == null ? "[]" : text);
        } catch (JSONException invalidLegacyChat) {
            // The source preference remains available for recovery.
            return messages;
        }
        for (int i = 0; i < legacy.length(); i++) {
            Object content = legacy.get(i);
            if (content instanceof String) {
                messages.add(new DeepSeekApi.Message(i % 2 == 0 ? "user" : "assistant", (String) content));
            }
        }
        return messages;
    }

    private static JSONObject encodeMessage(DeepSeekApi.Message message) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("role", message.role);
        data.put("content", message.content);
        if (!message.reasoningContent.isEmpty()) data.put("reasoning", message.reasoningContent);
        if (!message.attachments.isEmpty()) {
            JSONArray attachments = new JSONArray();
            for (DeepSeekApi.Attachment item : message.attachments) {
                attachments.put(new JSONObject().put("fileId", item.fileId).put("name", item.name)
                        .put("image", item.image));
            }
            data.put("attachments", attachments);
        }
        if (!message.citations.isEmpty()) {
            JSONArray citations = new JSONArray();
            for (DeepSeekApi.Citation item : message.citations) {
                citations.put(new JSONObject().put("title", item.title).put("url", item.url)
                        .put("startIndex", item.startIndex).put("endIndex", item.endIndex));
            }
            data.put("citations", citations);
        }
        data.put("serverToolCalls", message.serverToolCallsJson);
        return data;
    }

    private static DeepSeekApi.Message decodeMessage(JSONObject data) throws JSONException {
        List<DeepSeekApi.Attachment> attachments = new ArrayList<>();
        JSONArray fileList = data.optJSONArray("attachments");
        if (fileList != null) {
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject item = fileList.getJSONObject(i);
                attachments.add(new DeepSeekApi.Attachment(item.getString("fileId"),
                        item.getString("name"), item.optBoolean("image", false)));
            }
        }
        List<DeepSeekApi.Citation> citations = new ArrayList<>();
        JSONArray citationList = data.optJSONArray("citations");
        if (citationList != null) {
            for (int i = 0; i < citationList.length(); i++) {
                JSONObject item = citationList.getJSONObject(i);
                citations.add(new DeepSeekApi.Citation(item.getString("title"), item.getString("url"),
                        item.optInt("startIndex", -1), item.optInt("endIndex", -1)));
            }
        }
        return new DeepSeekApi.Message(data.getString("role"), data.optString("content", null),
                attachments, citations, data.optString("serverToolCalls", null),
                data.optString("reasoning", ""));
    }

    private static boolean sameMessages(List<DeepSeekApi.Message> first, List<DeepSeekApi.Message> second) {
        if (first == second) return true;
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            DeepSeekApi.Message left = first.get(i);
            DeepSeekApi.Message right = second.get(i);
            if (left == right) continue;
            try {
                if (!Objects.equals(encodeMessage(left).toString(), encodeMessage(right).toString())) return false;
            } catch (JSONException error) {
                throw new StorageException(error);
            }
        }
        return true;
    }

    private static String inferTitle(List<DeepSeekApi.Message> messages) {
        for (DeepSeekApi.Message message : messages) {
            if (!"user".equals(message.role)) continue;
            String title = compact(message.content, 48);
            if (!title.isEmpty()) return title;
            if (!message.attachments.isEmpty()) return compact(message.attachments.get(0).name, 48);
        }
        return DEFAULT_TITLE;
    }

    private static String preview(List<DeepSeekApi.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            DeepSeekApi.Message message = messages.get(i);
            String preview = compact(message.content, 96);
            if (!preview.isEmpty()) return preview;
            if (!message.attachments.isEmpty()) return compact(message.attachments.get(0).name, 96);
        }
        return "";
    }

    private static String compact(String text, int limit) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        boolean space = false;
        int count = 0;
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = result.length() > 0;
                continue;
            }
            if (count + (space ? 2 : 1) > limit) return result.append('…').toString();
            if (space) {
                result.append(' ');
                count++;
                space = false;
            }
            result.appendCodePoint(point);
            count++;
        }
        return result.toString();
    }

    private static String cleanDraft(String draft) {
        return draft == null ? "" : draft;
    }

    private static boolean validId(String id) {
        if (id == null) return false;
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static final class Conversation {
        final String id;
        final String title;
        final String preview;
        final long updatedAt;
        final List<DeepSeekApi.Message> messages;
        final String draft;
        private final String customTitle;

        private Conversation(String id, String customTitle, String title, String preview, long updatedAt,
                             String draft, List<DeepSeekApi.Message> messages) {
            this.id = id;
            this.customTitle = customTitle;
            this.title = customTitle == null ? title : customTitle;
            this.preview = preview;
            this.updatedAt = updatedAt;
            this.draft = draft;
            this.messages = messages;
        }
    }

    private static final class SavedMessages extends AbstractList<DeepSeekApi.Message> {
        private final File file;
        private final int messageCount;
        private List<DeepSeekApi.Message> loaded;

        SavedMessages(File file, int messageCount) {
            this.file = file;
            this.messageCount = messageCount;
        }

        @Override public DeepSeekApi.Message get(int index) {
            return rows().get(index);
        }

        @Override public int size() {
            return rows().size();
        }

        private List<DeepSeekApi.Message> rows() {
            synchronized (LOCK) {
                if (loaded != null) return loaded;
                List<DeepSeekApi.Message> messages = new ArrayList<>();
                try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                    if (reader.readLine() == null) throw new IOException("Missing conversation header");
                    String line;
                    while ((line = reader.readLine()) != null) {
                        messages.add(decodeMessage(new JSONObject(line)));
                    }
                    if (messageCount >= 0 && messages.size() != messageCount) {
                        throw new IOException("Conversation message count does not match");
                    }
                } catch (IOException | JSONException error) {
                    throw new StorageException(error);
                }
                loaded = messages;
                return loaded;
            }
        }
    }

    private interface RecordWriter {
        void write(BufferedWriter writer) throws IOException, JSONException;
    }

    static final class StorageException extends RuntimeException {
        StorageException(Throwable cause) {
            super("Could not access local conversations", cause);
        }
    }
}

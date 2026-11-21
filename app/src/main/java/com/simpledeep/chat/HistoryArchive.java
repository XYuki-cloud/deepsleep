package com.simpledeep.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class HistoryArchive {
    private static final long MAX_ARCHIVE = 512L * 1024 * 1024;
    private static final int MAX_INDEX = 4 * 1024 * 1024;
    private static final String CACHE_NAME = "deepseek-history-import.tmp";

    static final class Result {
        int added;
        int duplicates;
        int skipped;
    }

    // A previous process may have been killed while staging a non-seekable document.
    static synchronized void clearStaging(File cacheDir) {
        new File(cacheDir, CACHE_NAME).delete();
    }

    static synchronized void read(InputStream source, File cacheDir, ConversationStore store,
                                  Result result) throws IOException {
        BufferedInputStream input = new BufferedInputStream(source);
        input.mark(4);
        boolean zipped = input.read() == 'P' && input.read() == 'K';
        input.reset();
        DeepSeekHistoryReader.Sink save = (id, title, time, messages) -> {
            checkCancelled();
            if (store.importConversation(id, title, time, messages)) result.added++;
            else result.duplicates++;
        };
        if (!zipped) {
            result.skipped += DeepSeekHistoryReader.read(utf8(input), save);
            return;
        }

        // A system document provider may supply a pipe. ZipFile uses the central directory
        // so attachments and account records never need to be decompressed or parsed.
        File staged = new File(cacheDir, CACHE_NAME);
        try {
            try (FileOutputStream out = new FileOutputStream(staged)) {
                transfer(input, out, MAX_ARCHIVE, null);
            }
            try (ZipFile zip = new ZipFile(staged)) {
                importZip(zip, save, result);
            }
        } finally {
            Files.deleteIfExists(staged.toPath());
        }
    }

    private static void importZip(ZipFile zip, DeepSeekHistoryReader.Sink save, Result result)
            throws IOException {
        ZipEntry manifest = zip.getEntry("manifest.json");
        if (manifest == null) {
            ZipEntry legacy = zip.getEntry("conversations.json");
            if (legacy == null) throw new IOException("这个压缩包里没有找到 DeepSeek 会话。请选官网下载的原始 ZIP。");
            verify(zip, legacy, MAX_ARCHIVE);
            try (InputStream in = zip.getInputStream(legacy)) {
                result.skipped += DeepSeekHistoryReader.read(utf8(in), save);
            }
            return;
        }
        Object header = metadata(zip, manifest, 64 * 1024);
        Object version = header instanceof JSONObject ? ((JSONObject) header).opt("export_format_version") : null;
        if (!Integer.valueOf(3).equals(version) && !Long.valueOf(3).equals(version)) {
            throw new IOException("还不支持这个导出版本。请保留原文件。");
        }
        Object index = metadata(zip, zip.getEntry("chat_session_index.json"), MAX_INDEX);
        if (!(index instanceof JSONArray)) throw corrupt();
        JSONArray rows = (JSONArray) index;
        Set<String> ids = new HashSet<>();
        long remaining = MAX_ARCHIVE;
        for (int i = 0; i < rows.length(); i++) {
            checkCancelled();
            JSONObject row = rows.optJSONObject(i);
            Object rawId = row == null ? null : row.opt("id");
            if (!(rawId instanceof String)) throw corrupt();
            String id = (String) rawId;
            if (id.isEmpty() || id.contains("/") || id.contains("\\") || !ids.add(id)) throw corrupt();
            ZipEntry entry = zip.getEntry("chat_sessions/" + id + ".json");
            if (entry == null) {
                result.skipped++;
                continue;
            }
            remaining -= verify(zip, entry, remaining);
            try (InputStream in = zip.getInputStream(entry)) {
                result.skipped += DeepSeekHistoryReader.read(utf8(in), (sourceId, title, time, messages) -> {
                    if (!id.equals(sourceId)) throw corrupt();
                    long indexedTime = DeepSeekHistoryReader.time(row, "updated_at");
                    String indexedTitle = row.opt("title") instanceof String ? row.optString("title") : null;
                    save.accept(id, title == null ? indexedTitle : title, indexedTime > 0 ? indexedTime : time, messages);
                });
            }
        }
    }

    private static InputStreamReader utf8(InputStream in) {
        return new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder());
    }

    private static Object metadata(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        if (entry == null) throw corrupt();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CRC32 crc = new CRC32();
        try (InputStream in = zip.getInputStream(entry)) {
            long size = transfer(in, bytes, limit, crc);
            if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw corrupt();
        }
        return DeepSeekHistoryReader.readMetadata(utf8(new ByteArrayInputStream(bytes.toByteArray())));
    }

    private static long verify(ZipFile zip, ZipEntry entry, long limit) throws IOException {
        if (entry.getSize() < 0 || entry.getSize() > limit) throw tooLarge();
        CRC32 crc = new CRC32();
        try (InputStream in = zip.getInputStream(entry)) {
            long size = transfer(in, null, limit, crc);
            if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw corrupt();
            return size;
        }
    }

    private static long transfer(InputStream in, OutputStream out, long limit, CRC32 crc) throws IOException {
        byte[] buffer = new byte[32768];
        long total = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            checkCancelled();
            total += count;
            if (total > limit) throw tooLarge();
            if (out != null) out.write(buffer, 0, count);
            if (crc != null) crc.update(buffer, 0, count);
        }
        return total;
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }

    private static IOException corrupt() {
        return new IOException("导出包不完整，或格式无法识别。请重新下载官方导出的 ZIP。");
    }

    private static IOException tooLarge() {
        return new IOException("文件超出可处理的大小。可解压后分次选择 chat_sessions 文件夹中的会话 JSON。");
    }
}

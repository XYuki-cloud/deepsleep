package com.simpledeep.chat;

import java.util.regex.Pattern;

final class KeyText {
    private static final Pattern FORMAT = Pattern.compile("sk-[A-Za-z0-9_-]{16,256}");
    private static final Pattern CLIPBOARD_FORMAT = Pattern.compile("sk-[0-9a-fA-F]{32}");
    private static final long AUTO_IMPORT_TIMEOUT_MS = 30 * 60 * 1000;

    private KeyText() {}

    static String parse(CharSequence input) {
        if (input == null || input.length() > 1024) return null;
        String key = input.toString().trim();
        return FORMAT.matcher(key).matches() ? key : null;
    }

    static String fromClipboard(CharSequence input, long copiedAt, long requestedAt, long now) {
        if (requestedAt != 0 && (copiedAt <= requestedAt || copiedAt > now
                || now - requestedAt > AUTO_IMPORT_TIMEOUT_MS)) return null;
        String key = parse(input);
        return key != null && CLIPBOARD_FORMAT.matcher(key).matches() ? key : null;
    }
}

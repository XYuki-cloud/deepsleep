package com.simpledeep.chat;

public final class KeyTextTest {
    public static void main(String[] args) {
        String sample = "sk-0123456789abcdef0123456789abcdef";
        expect(sample, KeyText.parse("\n " + sample + " \r\n"));
        expect(null, KeyText.parse(null));
        expect(null, KeyText.parse("DeepSeek 密钥：" + sample));
        expect(null, KeyText.parse(sample + "\n" + sample));
        expect(null, KeyText.parse("sk-example"));
        expect(null, KeyText.parse("sk-0123456789abc!def"));
        expect(sample, KeyText.fromClipboard(sample, 21L, 20L, 22L));
        expect(null, KeyText.fromClipboard(sample, 19L, 20L, 22L));
        expect(null, KeyText.fromClipboard(sample, 21L, 20L, 1_800_021L));
        expect(null, KeyText.fromClipboard("sk-proj-0123456789abcdef0123456789abcdef", 21L, 20L, 22L));
        expect(sample, KeyText.fromClipboard(sample, 0L, 0L, 22L));
        System.out.println("11 key input checks passed");
    }

    private static void expect(String expected, String actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Unexpected key input result");
        }
    }
}

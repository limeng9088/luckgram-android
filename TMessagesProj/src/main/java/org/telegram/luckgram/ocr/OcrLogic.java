package org.telegram.luckgram.ocr;

/**
 * specs#106: which on-device model to run and the language of a piece of text from its script.
 * The Chinese model also reads Latin text, so it runs first; the Japanese / Korean models run only
 * when kana / hangul show up, and the result with more characters wins.
 *
 * <p>Pure Java, unit-tested in {@code OcrLogicTest}; same rules as iOS {@code LuckgramOcrLogic}.
 */
public final class OcrLogic {

    private OcrLogic() {
    }

    public static boolean isKana(int c) {
        return (c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF) || (c >= 0xFF66 && c <= 0xFF9D);
    }

    public static boolean isHangul(int c) {
        return (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F);
    }

    public static boolean isHan(int c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF);
    }

    public static boolean isLatinLetter(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= 0xC0 && c <= 0x24F);
    }

    /** Characters that count: everything except whitespace. */
    public static int weight(String text) {
        if (text == null) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (!Character.isWhitespace(c)) n++;
            i += Character.charCount(c);
        }
        return n;
    }

    /** Whether the Japanese model should also run on text read by the Chinese one. */
    public static boolean needsJapanese(String text) {
        return count(text, 'k') > 0;
    }

    /** Whether the Korean model should also run. */
    public static boolean needsKorean(String text) {
        return count(text, 'h') > 0;
    }

    /** True when {@code b} read more than {@code a} (ties keep {@code a}). */
    public static boolean better(String a, String b) {
        return weight(b) > weight(a);
    }

    /**
     * Language from the script: kana → "ja", hangul → "ko", Han only → "zh", else null (Latin and
     * others need a language model; ML Kit's own guess is used then).
     */
    public static String scriptLanguage(String text) {
        int kana = count(text, 'k'), hangul = count(text, 'h'), han = count(text, 'z');
        if (kana > 0 && kana * 5 >= han / 2) return "ja";
        if (hangul > 0 && hangul >= han) return "ko";
        if (han > 0 && han * 3 >= count(text, 'l')) return "zh"; // one Han character carries about a word
        return null;
    }

    /** The block's language: the recognizer's guess when it has one, else from the script. */
    public static String blockLanguage(String recognized, String text) {
        String s = scriptLanguage(text);
        if (s != null) return s;
        if (recognized == null || recognized.trim().isEmpty() || "und".equals(recognized)) return null;
        return recognized.trim();
    }

    private static int count(String text, char kind) {
        if (text == null) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            switch (kind) {
                case 'k': if (isKana(c)) n++; break;
                case 'h': if (isHangul(c)) n++; break;
                case 'z': if (isHan(c)) n++; break;
                case 'l': if (isLatinLetter(c)) n++; break;
            }
            i += Character.charCount(c);
        }
        return n;
    }
}

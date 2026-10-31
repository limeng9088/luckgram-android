/*
 * Luckgram — keyword folders.
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.keywordfolders;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;

import java.util.List;

/**
 * Case-insensitive keyword matching.
 * Keywords that contain CJK characters match as substrings; other keywords match whole words only
 * ("invoice" matches "Invoice #12" but not "invoices").
 */
public final class KeywordMatcher {

    /** Messages longer than this are only scanned up to this length (keeps scanning cheap). */
    private static final int MAX_SCAN_LENGTH = 8192;

    private KeywordMatcher() {
    }

    static boolean isCjk(char c) {
        // CJK radicals, kana, ideographs, hangul, CJK compatibility and full-width forms.
        return c >= 0x2E80 && c <= 0x9FFF || c >= 0xAC00 && c <= 0xD7AF || c >= 0xF900 && c <= 0xFAFF || c >= 0xFF00 && c <= 0xFFEF;
    }

    private static boolean isWordChar(char c) {
        return (Character.isLetterOrDigit(c) || c == '_') && !isCjk(c);
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isCjk(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Returns the index of the first match of {@code keyword} in {@code text} at or after {@code from}, or -1. */
    public static int indexOf(CharSequence text, String keyword, int from) {
        if (text == null || keyword == null) {
            return -1;
        }
        final int klen = keyword.length();
        if (klen == 0) {
            return -1;
        }
        final String s = text.toString();
        final int limit = Math.min(s.length(), MAX_SCAN_LENGTH) - klen;
        final boolean substring = containsCjk(keyword);
        final boolean wordStart = isWordChar(keyword.charAt(0));
        final boolean wordEnd = isWordChar(keyword.charAt(klen - 1));
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (!s.regionMatches(true, i, keyword, 0, klen)) {
                continue;
            }
            if (!substring) {
                if (wordStart && i > 0 && isWordChar(s.charAt(i - 1))) {
                    continue;
                }
                if (wordEnd && i + klen < s.length() && isWordChar(s.charAt(i + klen))) {
                    continue;
                }
            }
            return i;
        }
        return -1;
    }

    public static boolean matches(CharSequence text, String keyword) {
        return indexOf(text, keyword, 0) >= 0;
    }

    /** Returns the first keyword found in {@code text}, or null. */
    public static String firstMatch(CharSequence text, List<String> keywords) {
        if (text == null || text.length() == 0 || keywords == null) {
            return null;
        }
        for (int i = 0; i < keywords.size(); i++) {
            String k = keywords.get(i);
            if (matches(text, k)) {
                return k;
            }
        }
        return null;
    }

    public static boolean matchesAny(CharSequence text, List<String> keywords) {
        return firstMatch(text, keywords) != null;
    }

    /** Adds a background highlight span over every keyword hit. Returns the original text when nothing matched. */
    public static CharSequence highlight(CharSequence text, List<String> keywords, int backgroundColor) {
        if (text == null || text.length() == 0 || keywords == null || keywords.isEmpty()) {
            return text;
        }
        SpannableStringBuilder builder = null;
        for (int k = 0; k < keywords.size(); k++) {
            String keyword = keywords.get(k);
            int idx = indexOf(text, keyword, 0);
            while (idx >= 0) {
                if (builder == null) {
                    builder = new SpannableStringBuilder(text);
                }
                int end = Math.min(idx + keyword.length(), builder.length());
                builder.setSpan(new BackgroundColorSpan(backgroundColor), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                idx = indexOf(text, keyword, end);
            }
        }
        return builder != null ? builder : text;
    }
}

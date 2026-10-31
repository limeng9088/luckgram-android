/*
 * Luckgram — private notes (spec 05).
 * Licensed under GNU GPL v. 2 or later.
 */

package org.telegram.luckgram.notes;

import java.util.Locale;

/**
 * Text rules of private notes, free of android.* / org.telegram.* so they run as plain JVM unit
 * tests (TMessagesProj/src/test/java/org/telegram/luckgram/notes/UserNotesTextTest.java).
 * Same rules as iOS {@code LuckgramUserNotesText}.
 *
 * <p>The 2000-character limit counts user-perceived characters (grapheme clusters), like Swift's
 * {@code String.count} on iOS: one emoji, ZWJ sequence, flag or letter + combining marks is one
 * character (specs#60). The cluster rules are the same as 02's {@code QuickRepliesLogic} (specs#50).
 */
public final class UserNotesText {

    public static final int MAX_LENGTH = 2000;

    private UserNotesText() {
    }

    /** Text as stored: trimmed and cut to {@link #MAX_LENGTH} characters (grapheme clusters). Empty means "no note". */
    public static String normalize(String note) {
        String value = note == null ? "" : note.trim();
        if (graphemeCount(value) > MAX_LENGTH) {
            value = value.substring(0, offsetOfGrapheme(value, MAX_LENGTH)).trim();
        }
        return value;
    }

    /** Characters left before the limit (negative if over), as shown by the editor's counter. */
    public static int remaining(CharSequence text) {
        return MAX_LENGTH - graphemeCount(text);
    }

    // ---------------------------------------------------------------- grapheme clusters

    /** Number of user-perceived characters (grapheme clusters) in {@code s}. */
    public static int graphemeCount(CharSequence s) {
        if (s == null) {
            return 0;
        }
        int count = 0;
        int i = 0;
        final int n = s.length();
        while (i < n) {
            i = nextBoundary(s, i);
            count++;
        }
        return count;
    }

    /** UTF-16 offset where grapheme cluster number {@code index} starts (or the length if there are fewer). */
    public static int offsetOfGrapheme(CharSequence s, int index) {
        int i = 0;
        final int n = s.length();
        for (int g = 0; g < index && i < n; g++) {
            i = nextBoundary(s, i);
        }
        return i;
    }

    /**
     * For an edit replacing {@code dest[dstart, dend)} with {@code source}: how many UTF-16 chars of
     * {@code source} can be kept so the note stays within {@link #MAX_LENGTH} characters. Never
     * splits a cluster of {@code source}.
     */
    public static int fittingPrefix(CharSequence source, CharSequence dest, int dstart, int dend) {
        final String kept = dest.subSequence(0, dstart).toString() + dest.subSequence(dend, dest.length());
        final int room = MAX_LENGTH - graphemeCount(kept);
        if (room <= 0) {
            return 0;
        }
        return offsetOfGrapheme(source, room);
    }

    /**
     * End of the grapheme cluster starting at {@code start}: CR LF, combining marks, variation
     * selectors, emoji modifiers, ZWJ sequences, tag sequences and regional-indicator pairs
     * (a practical subset of UAX #29; identical on Android and the plain JVM used by the tests).
     */
    static int nextBoundary(CharSequence s, int start) {
        final int n = s.length();
        if (start >= n) {
            return n;
        }
        int cp = Character.codePointAt(s, start);
        int i = start + Character.charCount(cp);
        if (cp == '\r') {
            return i < n && s.charAt(i) == '\n' ? i + 1 : i;
        }
        if (cp == '\n') {
            return i;
        }
        if (isRegionalIndicator(cp)) {
            if (i < n) {
                final int next = Character.codePointAt(s, i);
                if (isRegionalIndicator(next)) {
                    i += Character.charCount(next);
                }
            }
            return i;
        }
        while (i < n) {
            final int next = Character.codePointAt(s, i);
            if (isExtend(next)) {
                i += Character.charCount(next);
            } else if (next == ZWJ) {
                i += Character.charCount(next);
                if (i < n) {
                    final int joined = Character.codePointAt(s, i);
                    if (joined != '\r' && joined != '\n') {
                        i += Character.charCount(joined);
                    }
                }
            } else {
                break;
            }
        }
        return i;
    }

    private static final int ZWJ = 0x200D;

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isExtend(int cp) {
        if (cp >= 0xFE00 && cp <= 0xFE0F) {
            return true; // variation selectors
        }
        if (cp >= 0x1F3FB && cp <= 0x1F3FF) {
            return true; // skin tone modifiers
        }
        if (cp >= 0xE0020 && cp <= 0xE007F) {
            return true; // tag characters (subdivision flags)
        }
        if (cp >= 0xE0100 && cp <= 0xE01EF) {
            return true; // variation selectors supplement
        }
        if (cp == 0x200C) {
            return true; // ZWNJ
        }
        final int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    /** First non-empty line, trimmed; shown in the collapsed chat bar and in "All notes". */
    public static String firstLine(String note) {
        if (note == null) {
            return "";
        }
        for (String line : note.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    private static String fold(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** "All notes" search: an empty query matches everything; otherwise the note text or the name contains it. */
    public static boolean matches(String query, String note, String name) {
        String q = fold(query == null ? "" : query.trim());
        if (q.isEmpty()) {
            return true;
        }
        return fold(note).contains(q) || fold(name).contains(q);
    }

    /** Line shown under the name in "All notes": the first line containing the query, else the first line. */
    public static String snippet(String note, String query) {
        String q = fold(query == null ? "" : query.trim());
        if (!q.isEmpty() && note != null) {
            for (String line : note.split("\n")) {
                if (fold(line).contains(q)) {
                    return line.trim();
                }
            }
        }
        return firstLine(note);
    }
}

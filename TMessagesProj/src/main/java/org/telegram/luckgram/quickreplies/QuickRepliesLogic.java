/*
 * Luckgram: pure logic of quick replies (feature 02).
 *
 * No android.* / org.telegram.* dependencies, so it is unit tested on the plain JVM
 * (TMessagesProj/src/test/java/org/telegram/luckgram/quickreplies).
 */
package org.telegram.luckgram.quickreplies;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class QuickRepliesLogic {

    public static final int MAX_COUNT = 100;
    /** In user-perceived characters (grapheme clusters), like Swift's String.count on iOS. */
    public static final int MAX_LENGTH = 1000;

    private QuickRepliesLogic() {
    }

    /**
     * Trims the template and cuts it to {@link #MAX_LENGTH} characters, counted as grapheme
     * clusters, without splitting an emoji (ZWJ sequences, skin tones, flags, keycaps) or a
     * letter from its combining marks.
     *
     * @return null if nothing is left after trimming
     */
    public static String normalize(CharSequence text) {
        if (text == null) {
            return null;
        }
        String s = text.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        s = truncate(s, MAX_LENGTH).trim();
        return s.isEmpty() ? null : s;
    }

    public static boolean canAdd(int currentCount) {
        return currentCount < MAX_COUNT;
    }

    /** Templates starting with {@code query}, case insensitive, in their stored order. */
    public static ArrayList<String> matching(List<String> items, String query) {
        final ArrayList<String> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        final String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (String s : items) {
            if (s != null && s.toLowerCase(Locale.ROOT).startsWith(q)) {
                result.add(s);
            }
        }
        return result;
    }

    /** Moves the item at {@code from} to {@code to} (drag to reorder), returning a new list. */
    public static ArrayList<String> move(List<String> items, int from, int to) {
        final ArrayList<String> result = new ArrayList<>(items);
        if (from < 0 || from >= result.size() || to < 0 || to >= result.size() || from == to) {
            return result;
        }
        final String item = result.remove(from);
        result.add(to, item);
        return result;
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

    /** The first {@code max} grapheme clusters of {@code s}. */
    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.substring(0, offsetOfGrapheme(s, max));
    }

    /**
     * For an edit replacing {@code dest[dstart, dend)} with {@code source}: how many UTF-16 chars of
     * {@code source} can be kept so the result stays within {@code max} grapheme clusters. Never
     * splits a cluster of {@code source}.
     */
    public static int fittingPrefix(CharSequence source, CharSequence dest, int dstart, int dend, int max) {
        final String kept = dest.subSequence(0, dstart).toString() + dest.subSequence(dend, dest.length());
        final int room = max - graphemeCount(kept);
        if (room <= 0) {
            return 0;
        }
        return offsetOfGrapheme(source, room);
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
     * End of the grapheme cluster starting at {@code start}. A practical subset of Unicode
     * extended grapheme clusters (UAX #29): CR LF, combining marks, variation selectors, emoji
     * modifiers, ZWJ sequences, tag sequences (subdivision flags) and regional-indicator pairs.
     * Implemented here rather than with java.text.BreakIterator so Android (ICU) and the JVM used
     * by the unit tests give the same result.
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
                // ZWJ glues the following pictograph (family emoji, rainbow flag, ...)
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
            return true; // variation selectors (emoji / text presentation)
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
}

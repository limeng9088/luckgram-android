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
    public static final int MAX_LENGTH = 1000;

    private QuickRepliesLogic() {
    }

    /**
     * Trims the template and cuts it to {@link #MAX_LENGTH} characters without splitting a
     * surrogate pair (emoji).
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
        if (s.length() > MAX_LENGTH) {
            int end = MAX_LENGTH;
            if (Character.isHighSurrogate(s.charAt(end - 1))) {
                end--;
            }
            s = s.substring(0, end).trim();
        }
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
}

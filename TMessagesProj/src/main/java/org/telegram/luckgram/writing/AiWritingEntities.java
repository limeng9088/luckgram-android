package org.telegram.luckgram.writing;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;

/**
 * Pure logic of spec 21 (unit-tested): the AI rewrites plain text, so formatting, mentions by name,
 * links and custom emoji of the original are put back afterwards. Each formatted range is looked up
 * in the result by its exact text, in the original order; a range whose text the AI changed is
 * dropped (the result then just has less formatting, like before this existed).
 */
public final class AiWritingEntities {

    private AiWritingEntities() {
    }

    /**
     * @param original the text sent to the AI
     * @param ranges   {start, end} of each formatted range in {@code original}
     * @param result   the AI's text
     * @return for each range, its new start in {@code result} (its length is unchanged), or -1
     */
    public static int[] relocate(String original, int[][] ranges, String result) {
        final int n = ranges.length;
        final int[] out = new int[n];
        Arrays.fill(out, -1);
        final Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.<Integer>comparingInt(i -> ranges[i][0]).thenComparingInt(i -> -ranges[i][1]));

        // same text at the same place may be claimed once (two "😀" must land on two different ones),
        // but different ranges may overlap (bold around a mention)
        final HashSet<String> claimed = new HashSet<>();
        int cursor = 0;
        for (int i : order) {
            final int start = ranges[i][0], end = ranges[i][1];
            if (start < 0 || end > original.length() || start >= end) continue;
            final String s = original.substring(start, end);
            if (s.trim().isEmpty()) continue;
            int at = find(result, s, cursor, claimed);
            if (at < 0) at = find(result, s, 0, claimed);
            if (at < 0) continue;
            claimed.add(at + ":" + s);
            out[i] = at;
            cursor = at + 1;
        }
        return out;
    }

    private static int find(String text, String s, int from, HashSet<String> claimed) {
        int at = text.indexOf(s, from);
        while (at >= 0 && claimed.contains(at + ":" + s)) {
            at = text.indexOf(s, at + 1);
        }
        return at;
    }
}

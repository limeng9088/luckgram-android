/*
 * Luckgram: splits the transcript reply into Telegram-sized messages. Pure Java (unit tested).
 */
package org.telegram.luckgram.callrec;

import java.util.ArrayList;

final class CallRecText {

    private CallRecText() {
    }

    /**
     * Splits {@code text} into parts of at most {@code maxChunk} characters (before the prefix),
     * preferring a line end, then a space, in the second half of each chunk. Every part after the
     * first starts with {@code continuedPrefix} and a line break. Blank parts are dropped.
     */
    static ArrayList<String> split(String text, int maxChunk, String continuedPrefix) {
        final ArrayList<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChunk);
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                int sp = text.lastIndexOf(' ', end);
                int half = start + maxChunk / 2;
                int brk = nl > half ? nl : (sp > half ? sp : -1);
                if (brk > start) {
                    end = brk;
                }
            }
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) {
                out.add(out.isEmpty() ? part : continuedPrefix + "\n" + part);
            }
            start = end;
        }
        return out;
    }
}

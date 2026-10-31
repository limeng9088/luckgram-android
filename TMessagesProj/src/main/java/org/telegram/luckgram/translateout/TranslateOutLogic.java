/*
 * Luckgram: pure logic of "translate before sending" (feature 01).
 *
 * No android.* / org.telegram.* dependencies, so it is unit tested on the plain JVM
 * (TMessagesProj/src/test/java/org/telegram/luckgram/translateout).
 */
package org.telegram.luckgram.translateout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TranslateOutLogic {

    /** Telegram's "unknown language" code. */
    public static final String UNKNOWN_LANGUAGE = "und";
    public static final int MAX_RECENT = 5;

    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<![\\w@])@[A-Za-z0-9_]{3,32}");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(?:https?://|www\\.|t\\.me/)[^\\s]+");
    private static final String URL_TRAILING_PUNCTUATION = ".,;:!?)]}\"'";

    private TranslateOutLogic() {
    }

    /**
     * Normalizes a detected / stored language code to the base code used for translation:
     * lower case, region dropped ("pt_BR" -> "pt"), every Chinese variant -> "zh".
     *
     * @return null for empty or unknown ("und") languages
     */
    public static String normalizeLanguage(String lng) {
        if (lng == null) {
            return null;
        }
        lng = lng.trim();
        if (lng.isEmpty() || UNKNOWN_LANGUAGE.equalsIgnoreCase(lng)) {
            return null;
        }
        lng = lng.toLowerCase(Locale.ROOT).replace('_', '-');
        if (lng.startsWith("zh")) {
            return "zh";
        }
        final int dash = lng.indexOf('-');
        return dash > 0 ? lng.substring(0, dash) : lng;
    }

    /** Puts {@code lang} first, removes duplicates and keeps at most {@link #MAX_RECENT} entries. */
    public static ArrayList<String> updateRecent(List<String> recent, String lang) {
        final ArrayList<String> result = new ArrayList<>();
        if (lang != null && !lang.isEmpty()) {
            result.add(lang);
        }
        if (recent != null) {
            for (String code : recent) {
                if (result.size() >= MAX_RECENT) {
                    break;
                }
                if (code != null && !code.isEmpty() && !result.contains(code)) {
                    result.add(code);
                }
            }
        }
        return result;
    }

    /** Parses the stored comma separated recent list, dropping empty and duplicate entries. */
    public static ArrayList<String> parseRecent(String stored) {
        final ArrayList<String> result = new ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return result;
        }
        for (String code : stored.split(",")) {
            code = code.trim();
            if (!code.isEmpty() && !result.contains(code)) {
                result.add(code);
            }
        }
        return result;
    }

    public static String joinRecent(List<String> recent) {
        final StringBuilder sb = new StringBuilder();
        for (String code : recent) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(code);
        }
        return sb.toString();
    }

    /** A range of the text the translator must leave untouched. */
    public static final class ProtectedRange {
        public final int start;
        public final int end;
        public final boolean url;

        public ProtectedRange(int start, int end, boolean url) {
            this.start = start;
            this.end = end;
            this.url = url;
        }

        @Override
        public String toString() {
            return (url ? "url" : "mention") + "[" + start + "," + end + ")";
        }
    }

    /**
     * Plain @usernames and links in {@code text} that aren't already covered by one of the
     * {@code occupied} ranges (pairs of [start, end), e.g. text mentions / custom emoji). Trailing
     * punctuation is not part of a link.
     */
    public static ArrayList<ProtectedRange> protectedRanges(String text, List<int[]> occupied) {
        final ArrayList<ProtectedRange> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        final ArrayList<int[]> taken = new ArrayList<>();
        if (occupied != null) {
            taken.addAll(occupied);
        }
        // links first: "https://t.me/@name" is one link, not a link cut by a mention
        collect(text, URL_PATTERN, true, taken, result);
        collect(text, MENTION_PATTERN, false, taken, result);
        Collections.sort(result, (x, y) -> Integer.compare(x.start, y.start));
        return result;
    }

    private static void collect(String text, Pattern pattern, boolean url, ArrayList<int[]> taken, ArrayList<ProtectedRange> into) {
        final Matcher m = pattern.matcher(text);
        while (m.find()) {
            final int start = m.start();
            int end = m.end();
            if (url) {
                while (end > start && URL_TRAILING_PUNCTUATION.indexOf(text.charAt(end - 1)) >= 0) {
                    end--;
                }
            }
            if (end <= start || overlaps(taken, start, end)) {
                continue;
            }
            taken.add(new int[]{start, end});
            into.add(new ProtectedRange(start, end, url));
        }
    }

    private static boolean overlaps(List<int[]> ranges, int start, int end) {
        for (int[] r : ranges) {
            if (start < r[1] && r[0] < end) {
                return true;
            }
        }
        return false;
    }
}

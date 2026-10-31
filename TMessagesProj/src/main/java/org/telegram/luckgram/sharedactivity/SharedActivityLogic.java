/*
 * Luckgram — shared-groups activity on user profiles (spec 16).
 * Licensed under GNU GPL v. 2 or later.
 */

package org.telegram.luckgram.sharedactivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure rules of spec 16, free of android.* / org.telegram.* so they run as plain JVM tests.
 * Same rules as iOS {@code LuckgramSharedActivityLogic}.
 */
public final class SharedActivityLogic {

    /** Messages taken from each common group (messages.search, from_id = the user). */
    public static final int PER_GROUP = 20;
    /** At most this many messages are shown. */
    public static final int MAX_ITEMS = 100;
    /** At most this many search requests run at the same time. */
    public static final int MAX_PARALLEL = 3;
    /** Results are reused for this long; pull to refresh / refresh reloads. */
    public static final long CACHE_MILLIS = 10 * 60 * 1000L;
    /** Longest FLOOD_WAIT we sit out; anything longer skips that group. */
    public static final int MAX_FLOOD_WAIT_SECONDS = 120;

    private SharedActivityLogic() {
    }

    public interface Dated {
        int date();

        long sortId();
    }

    /** Merges the per-group lists, newest first (ties: higher id first), keeping at most {@code limit}. */
    public static <T extends Dated> ArrayList<T> newestFirst(List<? extends List<T>> perGroup, int limit) {
        ArrayList<T> all = new ArrayList<>();
        for (List<T> list : perGroup) {
            if (list != null) {
                all.addAll(list);
            }
        }
        all.sort((a, b) -> {
            int c = Integer.compare(b.date(), a.date());
            return c != 0 ? c : Long.compare(b.sortId(), a.sortId());
        });
        if (all.size() > limit) {
            return new ArrayList<>(all.subList(0, limit));
        }
        return all;
    }

    /**
     * Seconds to wait for a Telegram FLOOD_WAIT_N / FLOOD_PREMIUM_WAIT_N error, or -1 when the
     * error is something else.
     */
    public static int floodWaitSeconds(String errorText) {
        if (errorText == null) {
            return -1;
        }
        String[] prefixes = {"FLOOD_WAIT_", "FLOOD_PREMIUM_WAIT_"};
        for (String prefix : prefixes) {
            if (errorText.startsWith(prefix)) {
                try {
                    int s = Integer.parseInt(errorText.substring(prefix.length()));
                    return Math.max(s, 0);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    public static boolean isFresh(long loadedAtMillis, long nowMillis) {
        return loadedAtMillis > 0 && nowMillis >= loadedAtMillis && nowMillis - loadedAtMillis < CACHE_MILLIS;
    }

    /** Header: "最近消息 · 3 个共同群组" / "Recent messages · 3 groups in common". */
    public static String header(int groupCount, boolean chinese) {
        if (chinese) {
            return "最近消息 · " + groupCount + " 个共同群组";
        }
        return String.format(Locale.US, "Recent messages · %d %s in common", groupCount, groupCount == 1 ? "group" : "groups");
    }
}

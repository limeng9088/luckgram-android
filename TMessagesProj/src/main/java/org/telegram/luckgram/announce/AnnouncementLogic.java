package org.telegram.luckgram.announce;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.luckgram.account.AccountLogic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Pure logic of the chat-list announcement banner (task S8, luckgram-specs#33): parsing
 * {@code GET /v1/announcements}, choosing the banner, remembering closed ones. Only java.* and
 * org.json (JVM unit tests); same rules as LuckgramAnnouncementLogic.swift on iOS.
 */
public final class AnnouncementLogic {

    private AnnouncementLogic() {
    }

    /** Re-fetch at most this often while the app is in the foreground. */
    public static final long REFRESH_INTERVAL_MS = 30 * 60_000L;
    /** After a failed fetch wait this long before trying again. */
    public static final long RETRY_AFTER_FAILURE_MS = 5 * 60_000L;
    /** How many closed announcement IDs are remembered. */
    public static final int MAX_DISMISSED = 50;

    public static final class Item {
        public final String id;
        public final String title;
        public final String body;
        public final String url;
        public final long endsAtMs;

        public Item(String id, String title, String body, String url, long endsAtMs) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.url = url;
            this.endsAtMs = endsAtMs;
        }
    }

    /** The server already filtered and sorted (highest priority first). */
    public static List<Item> parse(JSONObject response) {
        List<Item> out = new ArrayList<>();
        JSONArray arr = response.optJSONArray("announcements");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String id = a.optString("id", "");
            String title = a.optString("title", "");
            if (id.isEmpty() || title.isEmpty()) continue;
            String url = a.optString("url", "");
            if (!url.startsWith("https://")) url = "";
            out.add(new Item(id, title, a.optString("body", ""), url, AccountLogic.parseTime(a.optString("ends_at", ""))));
        }
        return out;
    }

    /** The banner to show: the first one not closed and not yet ended, or null. */
    public static Item pick(List<Item> items, Collection<String> dismissed, long nowMs) {
        if (items == null) return null;
        for (Item a : items) {
            if (dismissed.contains(a.id)) continue;
            if (a.endsAtMs > 0 && nowMs >= a.endsAtMs) continue;
            return a;
        }
        return null;
    }

    /** Adds id to the closed list (newest last), keeping at most MAX_DISMISSED. */
    public static List<String> dismiss(List<String> dismissed, String id) {
        List<String> out = new ArrayList<>(dismissed);
        out.remove(id);
        out.add(id);
        while (out.size() > MAX_DISMISSED) out.remove(0);
        return out;
    }

    public static boolean shouldRefresh(long nowMs, long lastSuccessMs, long lastFailureMs) {
        if (lastFailureMs > 0 && nowMs - lastFailureMs < RETRY_AFTER_FAILURE_MS) return false;
        return lastSuccessMs == 0 || nowMs - lastSuccessMs >= REFRESH_INTERVAL_MS;
    }

    public static List<String> parseDismissed(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static String serializeDismissed(List<String> ids) {
        return new JSONArray(ids).toString();
    }
}

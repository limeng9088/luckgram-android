package org.telegram.luckgram.discover;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Pure helpers for the block list shared by People Nearby (25 / S10) and Square (26 / S11).
 * Ids are the opaque server user ids (nearby {@code items[].id} == square {@code author.id}).
 * The server only returns ids, so the display name seen when blocking is cached locally.
 */
public final class BlockLogic {

    private BlockLogic() {
    }

    public static final String PATH = "/v1/blocks";

    /** {@code GET /v1/blocks?cursor=} — cursor is opaque, null for the first page. */
    public static String listPath(String cursor) {
        if (cursor == null || cursor.isEmpty()) return PATH;
        try {
            return PATH + "?cursor=" + URLEncoder.encode(cursor, "UTF-8");
        } catch (Exception e) {
            return PATH;
        }
    }

    public static String deletePath(String userId) {
        try {
            return PATH + "/" + URLEncoder.encode(userId, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return PATH + "/" + userId;
        }
    }

    public static JSONObject blockBody(String userId) throws Exception {
        return new JSONObject().put("user", userId);
    }

    /** {@code {items:[{user}]}} — {@code user} is an id string (an {@code {id}} object is tolerated). */
    public static List<String> parseIds(JSONObject json) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        JSONArray items = json == null ? null : json.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String id;
                JSONObject u = it.optJSONObject("user");
                if (u != null) {
                    id = u.optString("id", "");
                } else {
                    id = it.optString("user", "");
                }
                if (!id.isEmpty()) out.add(id);
            }
        }
        return new ArrayList<>(out);
    }

    /** Opaque next cursor, or null on the last page. */
    public static String nextCursor(JSONObject json) {
        if (json == null || json.isNull("next_cursor")) return null;
        String c = json.optString("next_cursor", "");
        return c.isEmpty() ? null : c;
    }

    // ---------------------------------------------------------------- local name cache (JSON object id -> name)

    public static String putName(String cache, String id, String name) {
        JSONObject o = parseCache(cache);
        try {
            if (name == null || name.trim().isEmpty()) {
                o.remove(id);
            } else {
                o.put(id, name.trim());
            }
        } catch (Exception ignore) {
        }
        return o.toString();
    }

    public static String removeName(String cache, String id) {
        JSONObject o = parseCache(cache);
        o.remove(id);
        return o.toString();
    }

    public static String name(String cache, String id) {
        String n = parseCache(cache).optString(id, "");
        return n.isEmpty() ? null : n;
    }

    private static JSONObject parseCache(String cache) {
        if (cache == null || cache.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(cache);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---------------------------------------------------------------- texts

    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        private String t(String zhText, String enText) {
            return zh ? zhText : enText;
        }

        public String title() { return t("已拉黑的人", "Blocked People"); }
        public String info() { return t("拉黑后，你和对方在「附近的人」和「广场」里互相看不到。这里的拉黑和 Telegram 的拉黑互不影响。", "You and the people you block can't see each other in People Nearby and Square. This is separate from blocking on Telegram."); }
        public String empty() { return t("你还没有拉黑任何人", "You haven't blocked anyone"); }
        public String unknownUser() { return t("Luckgram 用户", "Luckgram user"); }
        public String unblock() { return t("取消拉黑", "Unblock"); }
        public String unblockConfirm(String name) { return t("取消拉黑 " + name + "？之后你们可能会在「附近的人」和「广场」里再次看到对方。", "Unblock " + name + "? You may see each other again in People Nearby and Square."); }
        public String unblocked() { return t("已取消拉黑", "Unblocked"); }
        public String loading() { return t("加载中…", "Loading…"); }
        public String loadMore() { return t("加载更多", "Load more"); }
        public String retry() { return t("重试", "Retry"); }
        public String cancel() { return t("取消", "Cancel"); }
        public String connectFailed() { return t("暂时无法连接服务，请稍后再试", "The service isn't reachable right now. Please try again later."); }
    }
}

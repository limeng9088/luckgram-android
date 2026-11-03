package org.telegram.luckgram.agents;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * specs#88 (30): the AI agent market without Android dependencies (plain-JVM unit tests). Parses
 * the S13 responses, builds request paths, keeps the local cache and the "used" list.
 *
 * <p>The server never sends an agent's system instruction; nothing here stores one.
 */
public final class AgentMarketLogic {

    private AgentMarketLogic() {
    }

    /** Page size of {@code GET /v1/agents} (server allows 1..50). */
    public static final int PAGE_SIZE = 20;
    /** Most agents kept in the "used" list ("我的"). */
    public static final int MAX_USED = 50;
    /** Most agent details kept in the cache. */
    public static final int MAX_CACHED_DETAILS = 100;
    /** Key prefix of the AI assistant conversation of a market agent (extension point 4 of spec 18). */
    public static final String CONVERSATION_KEY_PREFIX = "market:";
    /** Request field of /v1/ai/chat task=agent naming the market agent (S13). */
    public static final String PARAM_AGENT_ID = "agent_id";
    /** Server error codes (S13 / S12). */
    public static final String ERROR_NOT_PRO = "not_pro";
    public static final String ERROR_AGENT_UNAVAILABLE = "agent_unavailable";

    /** Avatar color when the server gives none (or an invalid one). */
    public static final int DEFAULT_COLOR = 0xFF5B8DEF;

    // ---------------------------------------------------------------- model

    /** A string in both app languages. */
    public static final class Text {
        public final String zh, en;

        public Text(String zh, String en) {
            this.zh = zh == null ? "" : zh;
            this.en = en == null ? "" : en;
        }

        public String pick(boolean chinese) {
            String s = chinese ? zh : en;
            if (s.isEmpty()) s = chinese ? en : zh;
            return s;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("zh", zh).put("en", en);
        }

        static Text parse(Object o) {
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                return new Text(j.optString("zh", ""), j.optString("en", ""));
            }
            if (o instanceof String) return new Text((String) o, (String) o);
            return new Text("", "");
        }

        boolean contains(String lowerQuery) {
            return zh.toLowerCase(Locale.ROOT).contains(lowerQuery) || en.toLowerCase(Locale.ROOT).contains(lowerQuery);
        }
    }

    public static final class Agent {
        public String id;
        public Text name = new Text("", "");
        public Text description = new Text("", "");
        public String category = "";
        public int color = DEFAULT_COLOR;
        public String emoji = "";
        public String imageUrl = "";
        public final ArrayList<Text> examples = new ArrayList<>();
        public String author = "luckgram";
        public boolean proOnly;
        public String modelTier = "standard";
        public long users;

        public boolean isPremium() {
            return "premium".equals(modelTier);
        }

        /** What the round avatar shows: the emoji, else the first character of the name. */
        public String avatarText(boolean chinese) {
            if (!emoji.isEmpty()) return emoji;
            String n = name.pick(chinese).trim();
            return n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        }

        public boolean matches(String query) {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            return q.isEmpty() || name.contains(q) || description.contains(q);
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id).put("name", name.toJson()).put("description", description.toJson()).put("category", category);
            JSONObject avatar = new JSONObject().put("color", colorString(color));
            if (!emoji.isEmpty()) avatar.put("emoji", emoji);
            if (!imageUrl.isEmpty()) avatar.put("image_url", imageUrl);
            o.put("avatar", avatar);
            JSONArray ex = new JSONArray();
            for (Text t : examples) ex.put(t.toJson());
            o.put("examples", ex).put("author", author).put("pro_only", proOnly).put("model_tier", modelTier).put("users", users);
            return o;
        }
    }

    public static final class Category {
        public final String id;
        public final Text name;
        public final int count;

        public Category(String id, Text name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    public static final class Page {
        public final List<Agent> items;
        /** null on the last page. */
        public final String nextCursor;

        public Page(List<Agent> items, String nextCursor) {
            this.items = items;
            this.nextCursor = nextCursor;
        }
    }

    // ---------------------------------------------------------------- parsing

    /** One agent of {@code /v1/agents} or {@code /v1/agents/{id}}; null without an id. */
    public static Agent parseAgent(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        if (id.isEmpty()) return null;
        Agent a = new Agent();
        a.id = id;
        a.name = Text.parse(o.opt("name"));
        a.description = Text.parse(o.opt("description"));
        a.category = o.optString("category", "");
        JSONObject avatar = o.optJSONObject("avatar");
        if (avatar != null) {
            a.color = parseColor(avatar.optString("color", ""), DEFAULT_COLOR);
            a.emoji = avatar.optString("emoji", "");
            String url = avatar.optString("image_url", "");
            a.imageUrl = url.startsWith("https://") ? url : "";
        }
        JSONArray ex = o.optJSONArray("examples");
        if (ex != null) {
            for (int i = 0; i < ex.length(); i++) {
                Text t = Text.parse(ex.opt(i));
                if (!t.zh.isEmpty() || !t.en.isEmpty()) a.examples.add(t);
            }
        }
        a.author = o.optString("author", "luckgram");
        a.proOnly = o.optBoolean("pro_only", false);
        a.modelTier = o.optString("model_tier", "standard");
        a.users = Math.max(0, o.optLong("users", 0));
        // never keep an instruction, even if a server ever sent one
        return a;
    }

    /** {@code {"items":[…],"next_cursor":…|null}} */
    public static Page parsePage(JSONObject o) {
        ArrayList<Agent> items = new ArrayList<>();
        JSONArray arr = o == null ? null : o.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Agent a = parseAgent(arr.optJSONObject(i));
                if (a != null) items.add(a);
            }
        }
        String next = null;
        if (o != null && !o.isNull("next_cursor")) {
            next = o.optString("next_cursor", "");
            if (next.isEmpty()) next = null;
        }
        return new Page(items, next);
    }

    /** {@code {"categories":[{id,name{zh,en},count}]}}, in the server's order. */
    public static List<Category> parseCategories(JSONObject o) {
        ArrayList<Category> out = new ArrayList<>();
        JSONArray arr = o == null ? null : o.optJSONArray("categories");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null || c.optString("id", "").isEmpty()) continue;
                out.add(new Category(c.optString("id"), Text.parse(c.opt("name")), c.optInt("count", 0)));
            }
        }
        return out;
    }

    /** {@code #RRGGBB} → ARGB, or {@code def}. */
    public static int parseColor(String s, int def) {
        if (s == null || !s.matches("#[0-9a-fA-F]{6}")) return def;
        return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
    }

    static String colorString(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- requests

    /** {@code /v1/agents?category=&q=&cursor=&limit=}; empty values are left out. */
    public static String listPath(String category, String query, String cursor, int limit) {
        StringBuilder sb = new StringBuilder("/v1/agents?limit=").append(limit);
        if (category != null && !category.isEmpty()) sb.append("&category=").append(encode(category));
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) {
            // the server accepts at most 50 characters
            if (q.codePointCount(0, q.length()) > 50) q = q.substring(0, q.offsetByCodePoints(0, 50));
            sb.append("&q=").append(encode(q));
        }
        if (cursor != null && !cursor.isEmpty()) sb.append("&cursor=").append(encode(cursor));
        return sb.toString();
    }

    public static String detailPath(String id) {
        return "/v1/agents/" + encode(id);
    }

    public static final String CATEGORIES_PATH = "/v1/agents/categories";

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** Extra fields of {@code POST /v1/ai/chat} for a market agent (task=agent). */
    public static JSONObject chatParams(String agentId) {
        try {
            return new JSONObject().put(PARAM_AGENT_ID, agentId);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static String conversationKey(String agentId) {
        return CONVERSATION_KEY_PREFIX + agentId;
    }

    /** spec 30 需求 5: a free user can see a Pro agent but has to upgrade to chat. */
    public static boolean needsUpgrade(Agent agent, boolean isPro) {
        return agent != null && agent.proOnly && !isPro;
    }

    /** "1.2万人使用" / "1.2K users". */
    public static String formatUsers(long users, boolean chinese) {
        String n;
        if (chinese) {
            if (users >= 100_000_000L) n = trim(users / 100_000_000.0) + "亿";
            else if (users >= 10_000L) n = trim(users / 10_000.0) + "万";
            else n = String.valueOf(users);
            return n + "人使用";
        }
        if (users >= 1_000_000L) n = trim(users / 1_000_000.0) + "M";
        else if (users >= 1_000L) n = trim(users / 1_000.0) + "K";
        else n = String.valueOf(users);
        return n + (users == 1 ? " user" : " users");
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.1f", Math.floor(v * 10) / 10);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ---------------------------------------------------------------- cache (spec 30 需求 7)

    /**
     * The local cache: categories, the first page of each list ("" = all) and details, as one
     * JSON object. Search results are not cached; offline, search filters the cached agents.
     */
    public static final class Cache {
        public final JSONObject json;

        public Cache(String stored) {
            JSONObject j;
            try {
                j = stored == null || stored.isEmpty() ? new JSONObject() : new JSONObject(stored);
            } catch (Exception e) {
                j = new JSONObject();
            }
            json = j;
        }

        @Override
        public String toString() {
            return json.toString();
        }

        public void putCategories(JSONObject response) {
            try {
                json.put("categories", response);
            } catch (Exception ignore) {
            }
        }

        public List<Category> categories() {
            return parseCategories(json.optJSONObject("categories"));
        }

        /** Stores the first page of {@code category} ("" = all). */
        public void putFirstPage(String category, JSONObject response) {
            try {
                JSONObject lists = json.optJSONObject("lists");
                if (lists == null) json.put("lists", lists = new JSONObject());
                lists.put(category == null ? "" : category, response);
            } catch (Exception ignore) {
            }
        }

        public Page firstPage(String category) {
            JSONObject lists = json.optJSONObject("lists");
            JSONObject page = lists == null ? null : lists.optJSONObject(category == null ? "" : category);
            return page == null ? null : parsePage(page);
        }

        /** Every cached agent (lists and details), each once, for offline search. */
        public List<Agent> allAgents() {
            ArrayList<Agent> out = new ArrayList<>();
            ArrayList<String> seen = new ArrayList<>();
            JSONObject lists = json.optJSONObject("lists");
            if (lists != null) {
                for (Iterator<String> it = lists.keys(); it.hasNext(); ) {
                    for (Agent a : parsePage(lists.optJSONObject(it.next())).items) {
                        if (!seen.contains(a.id)) {
                            seen.add(a.id);
                            out.add(a);
                        }
                    }
                }
            }
            JSONObject details = json.optJSONObject("details");
            if (details != null) {
                for (Iterator<String> it = details.keys(); it.hasNext(); ) {
                    Agent a = parseAgent(details.optJSONObject(it.next()));
                    if (a != null && !seen.contains(a.id)) {
                        seen.add(a.id);
                        out.add(a);
                    }
                }
            }
            return out;
        }

        /** Offline list: cached agents of {@code category} ("" = all) matching {@code query}. */
        public List<Agent> offline(String category, String query) {
            ArrayList<Agent> out = new ArrayList<>();
            boolean all = category == null || category.isEmpty();
            boolean plain = query == null || query.trim().isEmpty();
            if (plain) {
                Page p = firstPage(category);
                if (p != null) return p.items;
            }
            for (Agent a : allAgents()) {
                if ((all || category.equals(a.category)) && a.matches(query)) out.add(a);
            }
            return out;
        }

        public void putDetail(Agent agent) {
            if (agent == null) return;
            try {
                JSONObject details = json.optJSONObject("details");
                if (details == null) json.put("details", details = new JSONObject());
                details.remove(agent.id);
                details.put(agent.id, agent.toJson());
                while (details.length() > MAX_CACHED_DETAILS) {
                    details.remove(details.keys().next());
                }
            } catch (Exception ignore) {
            }
        }

        public Agent detail(String id) {
            JSONObject details = json.optJSONObject("details");
            Agent a = details == null ? null : parseAgent(details.optJSONObject(id));
            if (a != null) return a;
            for (Agent c : allAgents()) if (c.id.equals(id)) return c;
            return null;
        }
    }

    // ---------------------------------------------------------------- used agents (spec 30 需求 6)

    /** The stored "used" list: agents (snapshots), most recently used first. */
    public static List<Agent> parseUsed(String stored) {
        ArrayList<Agent> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(stored == null || stored.isEmpty() ? "[]" : stored);
            for (int i = 0; i < arr.length(); i++) {
                Agent a = parseAgent(arr.optJSONObject(i));
                if (a != null) out.add(a);
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static String usedToString(List<Agent> used) {
        JSONArray arr = new JSONArray();
        for (Agent a : used) {
            try {
                arr.put(a.toJson());
            } catch (Exception ignore) {
            }
        }
        return arr.toString();
    }

    /** Puts {@code agent} first (replacing an older snapshot), keeping at most {@link #MAX_USED}. */
    public static List<Agent> markUsed(List<Agent> used, Agent agent) {
        ArrayList<Agent> out = new ArrayList<>();
        out.add(agent);
        for (Agent a : used) {
            if (!a.id.equals(agent.id) && out.size() < MAX_USED) out.add(a);
        }
        return out;
    }

    /** Replaces the snapshot of an agent already in the list (fresh name / avatar), keeping its place. */
    public static List<Agent> refreshUsed(List<Agent> used, Agent agent) {
        ArrayList<Agent> out = new ArrayList<>(used);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).id.equals(agent.id)) out.set(i, agent);
        }
        return out;
    }

    public static List<Agent> removeUsed(List<Agent> used, String id) {
        ArrayList<Agent> out = new ArrayList<>();
        for (Agent a : used) if (!a.id.equals(id)) out.add(a);
        return out;
    }
}

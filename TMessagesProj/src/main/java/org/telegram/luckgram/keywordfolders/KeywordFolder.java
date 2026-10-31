/*
 * Luckgram — keyword folders.
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.keywordfolders;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * A local, per-account folder whose content is "chats whose recent messages mention any keyword".
 * Never synced to Telegram cloud folders.
 */
public class KeywordFolder {

    public static final int SCOPE_PRIVATE = 1;
    public static final int SCOPE_GROUPS = 2;
    public static final int SCOPE_CHANNELS = 4;
    public static final int SCOPE_ALL = SCOPE_PRIVATE | SCOPE_GROUPS | SCOPE_CHANNELS;

    public int id;
    public String name = "";
    public ArrayList<String> keywords = new ArrayList<>();
    public int scope = SCOPE_ALL;
    public int order;

    public KeywordFolder copy() {
        KeywordFolder f = new KeywordFolder();
        f.id = id;
        f.name = name;
        f.keywords = new ArrayList<>(keywords);
        f.scope = scope;
        f.order = order;
        return f;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        JSONArray k = new JSONArray();
        for (String s : keywords) {
            k.put(s);
        }
        o.put("keywords", k);
        o.put("scope", scope);
        o.put("order", order);
        return o;
    }

    static KeywordFolder fromJson(JSONObject o) {
        KeywordFolder f = new KeywordFolder();
        f.id = o.optInt("id");
        f.name = o.optString("name", "");
        JSONArray k = o.optJSONArray("keywords");
        if (k != null) {
            for (int i = 0; i < k.length(); i++) {
                String s = k.optString(i, "").trim();
                if (!s.isEmpty()) {
                    f.keywords.add(s);
                }
            }
        }
        f.scope = o.optInt("scope", SCOPE_ALL);
        if ((f.scope & SCOPE_ALL) == 0) {
            f.scope = SCOPE_ALL;
        }
        f.order = o.optInt("order");
        return f;
    }
}

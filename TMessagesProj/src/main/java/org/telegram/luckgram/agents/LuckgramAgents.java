package org.telegram.luckgram.agents;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramFeatures;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.ai.LuckgramAiChatActivity;
import org.telegram.luckgram.ai.LuckgramAiConversation;
import org.telegram.luckgram.ai.LuckgramAiStore;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * spec 22: AI agents. Five presets (local configuration below) and the user's own agents, stored
 * per account in LuckgramSettings (removed on logout). Each agent is one conversation of the AI
 * assistant (spec 18 extension point "conversation properties": key {@code agent:<id>},
 * {@code task=agent} and the agent's instruction as the system message).
 */
public final class LuckgramAgents {

    private LuckgramAgents() {
    }

    /** spec 22 需求 4 */
    public static final int MAX_NAME = 30;
    public static final int MAX_PROMPT = 4000;

    /** Avatar colors offered when creating an agent. */
    public static final int[] COLORS = {0xFF5B8DEF, 0xFF4CAF50, 0xFFFF9800, 0xFFE91E63, 0xFF9C27B0, 0xFF00ACC1, 0xFF795548, 0xFF607D8B};

    private static final String KEY_CUSTOM = "ai_agents_custom";
    private static final String KEY_PINNED = "ai_agents_pinned";

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(LuckgramFeatures.AI_AGENTS) && LuckgramAiChatActivity.isEnabled();
    }

    public static final class Agent {
        public String id;
        public final boolean preset;
        String nameZh, nameEn, descZh, descEn;
        public int color;
        public String prompt;

        Agent(String id, boolean preset) {
            this.id = id;
            this.preset = preset;
        }

        public String name() {
            return preset ? LuckgramStrings.get(nameZh, nameEn) : nameZh;
        }

        public String description() {
            return preset ? LuckgramStrings.get(descZh, descEn) : (descZh == null ? "" : descZh);
        }

        public String initial() {
            String n = name().trim();
            return n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase();
        }

        /** For custom agents: the user's name and description. */
        public void setCustom(String name, String description, String prompt, int color) {
            this.nameZh = this.nameEn = name;
            this.descZh = this.descEn = description;
            this.prompt = prompt;
            this.color = color;
        }
    }

    private static Agent preset(String id, String nameZh, String nameEn, String descZh, String descEn, int color, String prompt) {
        Agent a = new Agent(id, true);
        a.nameZh = nameZh;
        a.nameEn = nameEn;
        a.descZh = descZh;
        a.descEn = descEn;
        a.color = color;
        a.prompt = prompt;
        return a;
    }

    /** spec 22 需求 2: the five presets (same ids, texts and colors on iOS). */
    public static List<Agent> presets() {
        ArrayList<Agent> list = new ArrayList<>();
        list.add(preset("travel", "旅行规划师", "Travel planner", "按天安排行程、预算和交通", "Day-by-day itineraries, budgets and transport", 0xFF00ACC1,
            "You are a travel planner. Ask for missing essentials (dates, budget, interests) only when needed. Plan trips day by day with morning / afternoon / evening, rough costs, transport between places and practical tips. Keep it realistic and concise."));
        list.add(preset("copywriter", "文案写手", "Copywriter", "广告、社媒、产品文案", "Ads, social posts and product copy", 0xFFFF9800,
            "You are a copywriter. Write punchy, clear marketing copy: headlines, social posts, product descriptions and slogans. Offer two or three variants when useful, match the requested tone and platform, and keep claims honest."));
        list.add(preset("language", "语言老师", "Language teacher", "陪你练口语、改错、讲语法", "Practice conversation, corrections, grammar", 0xFF4CAF50,
            "You are a friendly language teacher. Practice conversation in the language the user is learning, keep sentences at their level, correct mistakes gently after each reply (show the corrected sentence and a short why), and explain grammar simply when asked."));
        list.add(preset("researcher", "研究员", "Researcher", "整理资料、对比观点、给出处", "Organize facts, compare views, cite sources", 0xFF5B8DEF,
            "You are a careful researcher. Answer with well-organized facts, compare viewpoints, separate what is established from what is uncertain, and say where information usually comes from. Never invent sources or numbers; say when you are not sure."));
        list.add(preset("coder", "编程助手", "Coding assistant", "写代码、找 bug、解释原理", "Write code, find bugs, explain concepts", 0xFF607D8B,
            "You are a senior software engineer. Give working, idiomatic code in fenced code blocks with the language name, explain briefly why it works, point out bugs and edge cases, and ask for the language or framework when it matters."));
        return list;
    }

    // ---------------------------------------------------------------- custom agents

    public static List<Agent> custom(int account) {
        ArrayList<Agent> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(LuckgramSettings.getStringForAccount(account, KEY_CUSTOM, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Agent a = new Agent(o.optString("id", UUID.randomUUID().toString()), false);
                a.setCustom(o.optString("name"), o.optString("desc"), o.optString("prompt"), o.optInt("color", COLORS[0]));
                out.add(a);
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    private static void saveCustom(int account, List<Agent> agents) {
        JSONArray arr = new JSONArray();
        try {
            for (Agent a : agents) {
                arr.put(new JSONObject().put("id", a.id).put("name", a.nameZh).put("desc", a.descZh == null ? "" : a.descZh)
                    .put("prompt", a.prompt).put("color", a.color));
            }
        } catch (Exception ignore) {
        }
        LuckgramSettings.setStringForAccount(account, KEY_CUSTOM, arr.toString());
    }

    public static Agent newCustom() {
        Agent a = new Agent(UUID.randomUUID().toString(), false);
        a.setCustom("", "", "", COLORS[0]);
        return a;
    }

    /** Adds or replaces a custom agent. */
    public static void save(int account, Agent agent) {
        List<Agent> list = custom(account);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(agent.id)) {
                list.set(i, agent);
                replaced = true;
            }
        }
        if (!replaced) list.add(agent);
        saveCustom(account, list);
    }

    /** Deletes a custom agent, its conversation and its chat list entry. */
    public static void delete(int account, Agent agent) {
        List<Agent> list = custom(account);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(agent.id)) list.remove(i);
        }
        saveCustom(account, list);
        setPinned(account, agent.id, false);
        LuckgramAiStore store = LuckgramAiStore.getInstance(account);
        for (LuckgramAiConversation c : store.list()) {
            if (conversationKey(agent).equals(c.key)) store.delete(c.id);
        }
    }

    public static Agent find(int account, String id) {
        for (Agent a : presets()) if (a.id.equals(id)) return a;
        for (Agent a : custom(account)) if (a.id.equals(id)) return a;
        return null;
    }

    // ---------------------------------------------------------------- chat list (spec 22 需求 5)

    public static LinkedHashSet<String> pinned(int account) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            JSONArray arr = new JSONArray(LuckgramSettings.getStringForAccount(account, KEY_PINNED, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Exception ignore) {
        }
        return out;
    }

    public static boolean isPinned(int account, String id) {
        return pinned(account).contains(id);
    }

    public static void setPinned(int account, String id, boolean value) {
        LinkedHashSet<String> set = pinned(account);
        if (value) set.add(id); else set.remove(id);
        LuckgramSettings.setStringForAccount(account, KEY_PINNED, new JSONArray(set).toString());
    }

    /** Pinned agents that still exist, in pin order. */
    public static List<Agent> pinnedAgents(int account) {
        ArrayList<Agent> out = new ArrayList<>();
        if (!isEnabled()) return out;
        for (String id : pinned(account)) {
            Agent a = find(account, id);
            if (a != null) out.add(a);
        }
        return out;
    }

    // ---------------------------------------------------------------- conversations

    static String conversationKey(Agent agent) {
        return "agent:" + agent.id;
    }

    /** spec 22 需求 3: one conversation per agent, task=agent with the agent's instruction. */
    public static void open(BaseFragment from, Agent agent) {
        LuckgramAiConversation c = LuckgramAiStore.getInstance(from.getCurrentAccount())
            .findOrCreate(conversationKey(agent), LuckgramAI.TASK_AGENT, agent.prompt, agent.name());
        if (!agent.name().equals(c.title)) {
            c.title = agent.name();
        }
        LuckgramAiChatActivity.open(from, c.id);
    }
}

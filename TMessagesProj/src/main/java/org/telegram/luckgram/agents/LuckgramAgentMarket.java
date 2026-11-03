package org.telegram.luckgram.agents;

import android.app.Activity;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramFeatures;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.ai.LuckgramAiChatActivity;
import org.telegram.luckgram.ai.LuckgramAiConversation;
import org.telegram.luckgram.ai.LuckgramAiStore;
import org.telegram.luckgram.pro.LuckgramPro;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.List;

/**
 * specs#88 (30): the AI agent market on Android. Talks to S13 ({@code /v1/agents…}), keeps the
 * per-account cache and the agents the user has chatted with ("我的"), and opens a market agent as
 * an AI assistant conversation with {@code task=agent} + {@code agent_id} (the system instruction
 * stays on the server).
 */
public final class LuckgramAgentMarket {

    private LuckgramAgentMarket() {
    }

    private static final String KEY_CACHE = "ai_agent_market_cache";
    private static final String KEY_USED = "ai_agent_market_used";

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(LuckgramFeatures.AI_AGENT_MARKET) && LuckgramAgents.isEnabled();
    }

    // ---------------------------------------------------------------- local data (per account, cleared on logout)

    public static AgentMarketLogic.Cache cache(int account) {
        return new AgentMarketLogic.Cache(LuckgramSettings.getStringForAccount(account, KEY_CACHE, null));
    }

    public static void saveCache(int account, AgentMarketLogic.Cache cache) {
        LuckgramSettings.setStringForAccount(account, KEY_CACHE, cache.toString());
    }

    public static List<AgentMarketLogic.Agent> used(int account) {
        return AgentMarketLogic.parseUsed(LuckgramSettings.getStringForAccount(account, KEY_USED, "[]"));
    }

    private static void saveUsed(int account, List<AgentMarketLogic.Agent> used) {
        LuckgramSettings.setStringForAccount(account, KEY_USED, AgentMarketLogic.usedToString(used));
    }

    public static AgentMarketLogic.Agent findUsed(int account, String id) {
        for (AgentMarketLogic.Agent a : used(account)) if (a.id.equals(id)) return a;
        return null;
    }

    /** Fresh data from the server for an agent already in "我的" (name, avatar …). */
    public static void refreshUsed(int account, AgentMarketLogic.Agent agent) {
        if (findUsed(account, agent.id) != null) saveUsed(account, AgentMarketLogic.refreshUsed(used(account), agent));
    }

    /** Removes a market agent from "我的": its conversation and chat list entry too. */
    public static void remove(int account, String id) {
        saveUsed(account, AgentMarketLogic.removeUsed(used(account), id));
        LuckgramAgents.setPinned(account, LuckgramAgents.marketAgentId(id), false);
        LuckgramAiStore store = LuckgramAiStore.getInstance(account);
        String key = AgentMarketLogic.conversationKey(id);
        for (LuckgramAiConversation c : store.list()) {
            if (key.equals(c.key)) store.delete(c.id);
        }
    }

    // ---------------------------------------------------------------- server (S13)

    public interface Callback<T> {
        void onResult(T result);

        void onError(LuckgramAIError error);
    }

    public static LuckgramAI.Request loadCategories(int account, Callback<List<AgentMarketLogic.Category>> callback) {
        return LuckgramAI.getInstance().requestJson("GET", AgentMarketLogic.CATEGORIES_PATH, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                AgentMarketLogic.Cache c = cache(account);
                c.putCategories(result);
                saveCache(account, c);
                callback.onResult(AgentMarketLogic.parseCategories(result));
            }

            @Override
            public void onError(LuckgramAIError error) {
                callback.onError(error);
            }
        });
    }

    /** One page of the list; the first page without a search is cached. */
    public static LuckgramAI.Request loadPage(int account, String category, String query, String cursor, Callback<AgentMarketLogic.Page> callback) {
        String path = AgentMarketLogic.listPath(category, query, cursor, AgentMarketLogic.PAGE_SIZE);
        boolean cacheable = (cursor == null || cursor.isEmpty()) && (query == null || query.trim().isEmpty());
        return LuckgramAI.getInstance().requestJson("GET", path, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                AgentMarketLogic.Page page = AgentMarketLogic.parsePage(result);
                if (cacheable) {
                    AgentMarketLogic.Cache c = cache(account);
                    c.putFirstPage(category, result);
                    saveCache(account, c);
                }
                for (AgentMarketLogic.Agent a : page.items) refreshUsed(account, a);
                callback.onResult(page);
            }

            @Override
            public void onError(LuckgramAIError error) {
                callback.onError(error);
            }
        });
    }

    public static LuckgramAI.Request loadDetail(int account, String id, Callback<AgentMarketLogic.Agent> callback) {
        return LuckgramAI.getInstance().requestJson("GET", AgentMarketLogic.detailPath(id), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                AgentMarketLogic.Agent a = AgentMarketLogic.parseAgent(result.optJSONObject("agent"));
                if (a == null) {
                    callback.onError(new LuckgramAIError(LuckgramAIError.AGENT_UNAVAILABLE, 404));
                    return;
                }
                AgentMarketLogic.Cache c = cache(account);
                c.putDetail(a);
                saveCache(account, c);
                refreshUsed(account, a);
                callback.onResult(a);
            }

            @Override
            public void onError(LuckgramAIError error) {
                callback.onError(error);
            }
        });
    }

    // ---------------------------------------------------------------- chat (spec 30 需求 4, 5)

    /**
     * "开始聊天": Pro-only agents ask a free user to upgrade first (the server checks too, S13);
     * otherwise opens (or continues) the agent's conversation and lists it under "我的".
     */
    public static void open(BaseFragment from, AgentMarketLogic.Agent agent) {
        if (AgentMarketLogic.needsUpgrade(agent, LuckgramPro.isPro())) {
            showUpgrade(from, agent);
            return;
        }
        int account = from.getCurrentAccount();
        saveUsed(account, AgentMarketLogic.markUsed(used(account), agent));
        String title = agent.name.pick(LuckgramStrings.isChinese());
        LuckgramAiStore store = LuckgramAiStore.getInstance(account);
        LuckgramAiConversation c = store.findOrCreate(AgentMarketLogic.conversationKey(agent.id), LuckgramAI.TASK_AGENT, null, title);
        c.systemPrompt = null; // never on the client: the server injects it by agent_id
        c.requestParams = AgentMarketLogic.chatParams(agent.id);
        c.title = title;
        store.save(c);
        LuckgramAiChatActivity.open(from, c.id);
    }

    /** spec 30 需求 5: the Pro upgrade prompt (spec 29). */
    public static void showUpgrade(BaseFragment from, AgentMarketLogic.Agent agent) {
        Activity activity = from.getParentActivity();
        if (activity == null) return;
        String name = agent == null ? "" : agent.name.pick(LuckgramStrings.isChinese());
        new AlertDialog.Builder(activity, from.getResourceProvider())
            .setTitle(LuckgramStrings.get("Pro 专享智能体", "Pro agent"))
            .setMessage(LuckgramStrings.get("「" + name + "」是 Luckgram Pro 专享智能体。升级到 Pro 后即可开始聊天。",
                "\"" + name + "\" is available with Luckgram Pro. Upgrade to Pro to start chatting."))
            .setPositiveButton(LuckgramStrings.get("升级 Pro", "Upgrade to Pro"), (d, w) -> from.presentFragment(new org.telegram.luckgram.account.LuckgramProActivity()))
            .setNegativeButton(LuckgramStrings.get("取消", "Cancel"), null)
            .show();
    }
}

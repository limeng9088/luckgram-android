package org.telegram.luckgram.discover;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.ApplicationLoader;

import java.util.List;

/**
 * Client for the block list shared by People Nearby (25) and Square (26): {@code POST /v1/blocks},
 * {@code DELETE /v1/blocks/{user}}, {@code GET /v1/blocks}. Use these instead of calling the
 * endpoints directly so the local name cache (for {@link BlockedUsersActivity}) stays in sync.
 */
public final class DiscoverBlocks {

    private DiscoverBlocks() {
    }

    private static final String PREFS = "luckgram_blocks";
    private static final String KEY_NAMES = "names";

    public static final class Page {
        public final List<String> ids;
        public final String nextCursor;

        Page(List<String> ids, String nextCursor) {
            this.ids = ids;
            this.nextCursor = nextCursor;
        }
    }

    public static BlockLogic.Texts tx() {
        return new BlockLogic.Texts(LuckgramStrings.isChinese());
    }

    /** Blocks {@code userId}; {@code displayName} is remembered locally for the blocked list. */
    public static LuckgramAI.Request block(String userId, String displayName, LuckgramAI.Callback<JSONObject> callback) {
        JSONObject body;
        try {
            body = BlockLogic.blockBody(userId);
        } catch (Exception e) {
            body = new JSONObject();
        }
        return LuckgramAI.getInstance().requestJson("POST", BlockLogic.PATH, body, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                prefs().edit().putString(KEY_NAMES, BlockLogic.putName(names(), userId, displayName)).apply();
                if (callback != null) callback.onResult(result);
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    public static LuckgramAI.Request unblock(String userId, LuckgramAI.Callback<JSONObject> callback) {
        return LuckgramAI.getInstance().requestJson("DELETE", BlockLogic.deletePath(userId), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                prefs().edit().putString(KEY_NAMES, BlockLogic.removeName(names(), userId)).apply();
                if (callback != null) callback.onResult(result);
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (callback != null) callback.onError(error);
            }
        });
    }

    public static LuckgramAI.Request list(String cursor, LuckgramAI.Callback<Page> callback) {
        return LuckgramAI.getInstance().requestJson("GET", BlockLogic.listPath(cursor), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                callback.onResult(new Page(BlockLogic.parseIds(result), BlockLogic.nextCursor(result)));
            }

            @Override
            public void onError(LuckgramAIError error) {
                callback.onError(error);
            }
        });
    }

    /** Name seen when blocking, or null if this device doesn't know it. */
    public static String nameOf(String userId) {
        return BlockLogic.name(names(), userId);
    }

    /** Logout / unlink: the list belongs to the linked user, forget the cached names. */
    public static void clearLocal() {
        prefs().edit().clear().apply();
    }

    private static String names() {
        return prefs().getString(KEY_NAMES, "");
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

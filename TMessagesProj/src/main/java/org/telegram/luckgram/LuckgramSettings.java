package org.telegram.luckgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.NotificationCenter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * Luckgram switch and config storage (same name and keys on iOS).
 *
 * <ul>
 *   <li>Stored per device in the {@code luckgram_settings} SharedPreferences file; never synced
 *       to the cloud and not part of Telegram's backup agent (so nothing survives a reinstall).</li>
 *   <li>Shared by all accounts on the device. A feature whose spec says "per account" uses the
 *       {@code *ForAccount} variants; those values are removed when that account logs out.</li>
 *   <li>Every change posts {@link NotificationCenter#luckgramSettingsChanged} on the global
 *       NotificationCenter with the key as the only argument.</li>
 *   <li>When the last account logs out, everything Luckgram stored on the device is wiped.</li>
 * </ul>
 */
public final class LuckgramSettings {

    private LuckgramSettings() {
    }

    private static final String PREFS = "luckgram_settings";

    private static SharedPreferences prefs;

    public static SharedPreferences prefs() {
        if (prefs == null) {
            prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    // ---------------------------------------------------------------- registered switches

    /** Switch state of a registered feature (falls back to the registry default). */
    public static boolean isEnabled(String key) {
        LuckgramFeature f = LuckgramFeatures.find(key);
        return getBool(key, f != null && f.defaultValue);
    }

    public static void setEnabled(String key, boolean enabled) {
        setBool(key, enabled);
    }

    // ---------------------------------------------------------------- generic values (device)

    public static boolean getBool(String key, boolean def) {
        return prefs().getBoolean(key, def);
    }

    public static void setBool(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        notifyChanged(key);
    }

    public static String getString(String key, String def) {
        return prefs().getString(key, def);
    }

    public static void setString(String key, String value) {
        if (value == null) {
            prefs().edit().remove(key).apply();
        } else {
            prefs().edit().putString(key, value).apply();
        }
        notifyChanged(key);
    }

    public static int getInt(String key, int def) {
        return prefs().getInt(key, def);
    }

    public static void setInt(String key, int value) {
        prefs().edit().putInt(key, value).apply();
        notifyChanged(key);
    }

    public static void remove(String key) {
        prefs().edit().remove(key).apply();
        notifyChanged(key);
    }

    // ---------------------------------------------------------------- per-account values

    private static String accountKey(int account, String key) {
        return "acc" + account + "." + key;
    }

    public static boolean getBoolForAccount(int account, String key, boolean def) {
        return getBool(accountKey(account, key), def);
    }

    public static void setBoolForAccount(int account, String key, boolean value) {
        setBool(accountKey(account, key), value);
    }

    public static String getStringForAccount(int account, String key, String def) {
        return getString(accountKey(account, key), def);
    }

    public static void setStringForAccount(int account, String key, String value) {
        setString(accountKey(account, key), value);
    }

    public static int getIntForAccount(int account, String key, int def) {
        return getInt(accountKey(account, key), def);
    }

    public static void setIntForAccount(int account, String key, int value) {
        setInt(accountKey(account, key), value);
    }

    // ---------------------------------------------------------------- change notification

    private static void notifyChanged(String key) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.luckgramSettingsChanged, key));
    }

    // ---------------------------------------------------------------- remote config (GET /v1/config, task S4)

    private static final String KEY_REMOTE_CONFIG = "_remote_config";
    private static JSONObject remoteConfig;

    /**
     * Merges the body of {@code GET /v1/config} (a flat JSON object of {@code client: true} keys,
     * e.g. {@code "features.quick_replies": true}, {@code "ai.transcribe_max_mb": 25}) and caches it.
     * Fetching and TTL handling are task S4.
     */
    public static synchronized void mergeRemoteConfig(JSONObject config) {
        if (config == null) {
            return;
        }
        JSONObject merged = remoteConfig();
        Iterator<String> it = config.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                merged.put(k, config.get(k));
            } catch (Exception ignore) {
            }
        }
        remoteConfig = merged;
        prefs().edit().putString(KEY_REMOTE_CONFIG, merged.toString()).apply();
        notifyChanged(null);
    }

    private static synchronized JSONObject remoteConfig() {
        if (remoteConfig == null) {
            try {
                remoteConfig = new JSONObject(prefs().getString(KEY_REMOTE_CONFIG, "{}"));
            } catch (Exception e) {
                remoteConfig = new JSONObject();
            }
        }
        return remoteConfig;
    }

    /** Whether the server allows the feature ({@code features.<key>}); true when unknown. */
    public static boolean isRemotelyAllowed(String featureKey) {
        Object v = remoteConfig().opt("features." + featureKey);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof JSONObject) {
            return ((JSONObject) v).optBoolean("enabled", true);
        }
        return true;
    }

    /** A {@code client: true} number from /v1/config, or {@code def} (the built-in default with the same name). */
    public static long remoteLong(String key, long def) {
        Object v = remoteConfig().opt(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    // ---------------------------------------------------------------- debug tools

    /**
     * Debug builds and QA test builds ({@code -PLUCKGRAM_QA=true}) only; never in a normal release
     * build (spec 00: debug_demo 仅调试构建可见, issues #37 / #44).
     */
    public static boolean isDebugToolsVisible() {
        return BuildConfig.DEBUG || BuildConfig.QA_BUILD;
    }

    // ---------------------------------------------------------------- logout cleanup

    /**
     * Called from {@code UserConfig.clearConfig()} when an account logs out or is deleted.
     *
     * @param noAccountsLeft true when no logged-in account remains on the device
     */
    public static void onAccountCleared(int account, boolean noAccountsLeft) {
        SharedPreferences.Editor editor = prefs().edit();
        if (noAccountsLeft) {
            editor.clear();
        } else {
            String prefix = "acc" + account + ".";
            ArrayList<String> remove = new ArrayList<>();
            for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    remove.add(e.getKey());
                }
            }
            for (String k : remove) {
                editor.remove(k);
            }
        }
        editor.apply();
        if (noAccountsLeft) {
            synchronized (LuckgramSettings.class) {
                remoteConfig = null;
            }
            LuckgramAI.getInstance().resetLocalState();
        }
        notifyChanged(null);
    }
}

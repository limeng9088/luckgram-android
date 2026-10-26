/*
 * Luckgram: quick reply templates (feature 02, "quick_replies").
 *
 * Templates are stored locally per account and never uploaded. This is unrelated to Telegram
 * Business "quick replies" (cloud shortcuts), which stay untouched.
 */
package org.telegram.luckgram.quickreplies;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class QuickRepliesStore {

    /** Switch key, registered in the Luckgram feature registry ("消息与语言" group). Default: on. */
    public static final String FEATURE_KEY = "quick_replies";
    public static final boolean FEATURE_DEFAULT = true;

    public static final int MAX_COUNT = 100;
    public static final int MAX_LENGTH = 1000;

    private static final String DEVICE_PREFS = "luckgram_settings";
    private static final String ACCOUNT_PREFS_PREFIX = "luckgram_quick_replies_";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_SEEDED = "seeded";

    private static final ArrayList<Runnable> listeners = new ArrayList<>();

    private QuickRepliesStore() {
    }

    /** Device level switch shared by all accounts (same storage as LuckgramSettings, feature 00). */
    public static boolean isEnabled() {
        try {
            return ApplicationLoader.applicationContext
                .getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(FEATURE_KEY, FEATURE_DEFAULT);
        } catch (Exception e) {
            return FEATURE_DEFAULT;
        }
    }

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(ACCOUNT_PREFS_PREFIX + account, Context.MODE_PRIVATE);
    }

    /** Templates of the account, in user order. Seeds the 4 examples on first use. */
    public static ArrayList<String> get(int account) {
        final SharedPreferences p = prefs(account);
        if (!p.getBoolean(KEY_SEEDED, false)) {
            final ArrayList<String> seeded = QuickRepliesStrings.presets();
            write(account, seeded, true);
            return seeded;
        }
        final ArrayList<String> result = new ArrayList<>();
        final String raw = p.getString(KEY_ITEMS, null);
        if (!TextUtils.isEmpty(raw)) {
            try {
                final JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    final String s = array.optString(i, null);
                    if (!TextUtils.isEmpty(s)) {
                        result.add(s);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return result;
    }

    public static void set(int account, List<String> items) {
        write(account, items, true);
        notifyChanged();
    }

    private static void write(int account, List<String> items, boolean seeded) {
        final JSONArray array = new JSONArray();
        for (int i = 0; i < items.size() && i < MAX_COUNT; i++) {
            array.put(items.get(i));
        }
        prefs(account).edit().putString(KEY_ITEMS, array.toString()).putBoolean(KEY_SEEDED, seeded).apply();
    }

    /** Normalizes user input; returns null if it is empty after trimming. */
    public static String normalize(CharSequence text) {
        if (text == null) {
            return null;
        }
        String s = text.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH);
        }
        return s;
    }

    /** Templates starting with {@code query} (case insensitive), for the "/" suggestions. */
    public static ArrayList<String> search(int account, String query) {
        final ArrayList<String> result = new ArrayList<>();
        if (!isEnabled()) {
            return result;
        }
        final String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (String s : get(account)) {
            if (s.toLowerCase(Locale.ROOT).startsWith(q)) {
                result.add(s);
            }
        }
        return result;
    }

    /** Wipes the account's templates (called on logout). */
    public static void clearAccount(int account) {
        try {
            prefs(account).edit().clear().apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
        notifyChanged();
    }

    public static void addListener(Runnable r) {
        if (!listeners.contains(r)) {
            listeners.add(r);
        }
    }

    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void notifyChanged() {
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    }
}

/*
 * Luckgram — private notes (spec 05).
 * Licensed under GNU GPL v. 2 or later, like the Telegram for Android sources it builds on.
 */

package org.telegram.luckgram.notes;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Map;

/**
 * Local, per-account storage of private notes about users, groups and channels.
 *
 * Notes are keyed by peer id (user id &gt; 0, chat/channel id &lt; 0, i.e. Telegram's dialog id for
 * non-secret chats) and stored in a SharedPreferences file bound to the account's Telegram user id,
 * so two accounts never see each other's notes even if an account slot is reused.
 * Nothing is uploaded; the file is excluded from backups (BackupAgent only backs up saved tokens)
 * and it is wiped in {@link #wipeAccount(int, long)} on logout / account deletion.
 */
public final class UserNotesStore {

    /** Feature switch key, registered in the Luckgram settings registry (spec 00). */
    public static final String FEATURE_KEY = "user_notes";
    public static final boolean FEATURE_DEFAULT = true;

    public static final int MAX_LENGTH = UserNotesText.MAX_LENGTH;

    private static final String PREFS_PREFIX = "luckgram_notes_";
    private static final String KEY_NOTE = "n_";
    private static final String KEY_EXPANDED = "e_";
    private static final String KEY_TIME = "t_";

    private UserNotesStore() {
    }

    // ------------------------------------------------------------------ feature switch

    /**
     * Whether the private notes feature is enabled. Device-wide setting, default on.
     * Reads the shared Luckgram settings store used by the foundation registry.
     */
    public static boolean isEnabled() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("luckgram_settings", Context.MODE_PRIVATE)
                .getBoolean(FEATURE_KEY, FEATURE_DEFAULT);
    }

    // ------------------------------------------------------------------ notes

    private static SharedPreferences prefs(int account) {
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId == 0) {
            return null;
        }
        return prefsFor(selfId);
    }

    private static SharedPreferences prefsFor(long selfId) {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_PREFIX + selfId, Context.MODE_PRIVATE);
    }

    /** @return the note for the peer or {@code null} if none. */
    public static String getNote(int account, long peerId) {
        if (peerId == 0) {
            return null;
        }
        SharedPreferences p = prefs(account);
        if (p == null) {
            return null;
        }
        String note = p.getString(KEY_NOTE + peerId, null);
        return TextUtils.isEmpty(note) ? null : note;
    }

    public static boolean hasNote(int account, long peerId) {
        return getNote(account, peerId) != null;
    }

    /** Saves (or deletes, if blank) a note. Text is trimmed and limited to {@link #MAX_LENGTH} characters. */
    public static void setNote(int account, long peerId, String note) {
        SharedPreferences p = prefs(account);
        if (p == null || peerId == 0) {
            return;
        }
        String value = UserNotesText.normalize(note);
        SharedPreferences.Editor e = p.edit();
        if (value.isEmpty()) {
            e.remove(KEY_NOTE + peerId).remove(KEY_TIME + peerId).remove(KEY_EXPANDED + peerId);
        } else {
            e.putString(KEY_NOTE + peerId, value).putLong(KEY_TIME + peerId, System.currentTimeMillis());
        }
        e.apply();
    }

    /** Whether the chat top bar for this peer is expanded (remembered per chat, default collapsed). */
    public static boolean isBarExpanded(int account, long peerId) {
        SharedPreferences p = prefs(account);
        return p != null && p.getBoolean(KEY_EXPANDED + peerId, false);
    }

    public static void setBarExpanded(int account, long peerId, boolean expanded) {
        SharedPreferences p = prefs(account);
        if (p != null) {
            p.edit().putBoolean(KEY_EXPANDED + peerId, expanded).apply();
        }
    }

    public static final class Entry {
        public final long peerId;
        public final String note;
        public final long updatedAt;

        Entry(long peerId, String note, long updatedAt) {
            this.peerId = peerId;
            this.note = note;
            this.updatedAt = updatedAt;
        }
    }

    /** All notes of the account, most recently edited first. */
    public static ArrayList<Entry> getAll(int account) {
        ArrayList<Entry> result = new ArrayList<>();
        SharedPreferences p = prefs(account);
        if (p == null) {
            return result;
        }
        Map<String, ?> all = p.getAll();
        for (Map.Entry<String, ?> it : all.entrySet()) {
            String key = it.getKey();
            if (!key.startsWith(KEY_NOTE) || !(it.getValue() instanceof String)) {
                continue;
            }
            long peerId;
            try {
                peerId = Long.parseLong(key.substring(KEY_NOTE.length()));
            } catch (NumberFormatException ignore) {
                continue;
            }
            String note = (String) it.getValue();
            if (TextUtils.isEmpty(note)) {
                continue;
            }
            Object t = all.get(KEY_TIME + peerId);
            result.add(new Entry(peerId, note, t instanceof Long ? (Long) t : 0));
        }
        java.util.Collections.sort(result, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    /** First non-empty line of a note. */
    public static String firstLine(String note) {
        return UserNotesText.firstLine(note);
    }

    /** Removes every note of the given account. Called on logout and account deletion. */
    public static void wipeAccount(int account, long selfId) {
        if (selfId == 0) {
            return;
        }
        prefsFor(selfId).edit().clear().commit();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                ApplicationLoader.applicationContext.deleteSharedPreferences(PREFS_PREFIX + selfId);
            }
        } catch (Exception ignore) {
        }
    }
}

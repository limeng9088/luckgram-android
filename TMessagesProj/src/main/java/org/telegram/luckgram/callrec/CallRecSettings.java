/*
 * Luckgram: settings for call recording.
 *
 * Switches are device level and shared by all accounts (spec 00 §5), stored under the same
 * preferences file / keys that the "Luckgram features" page (LuckgramSettings, feature 00) uses.
 *
 * The save-to chat is per account, because a dialog id only means something inside one account.
 * It lives in that account's UserConfig preferences, which Telegram wipes on logout
 * (UserConfig.clearConfig), so nothing is left behind when the account is removed.
 */
package org.telegram.luckgram.callrec;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

public final class CallRecSettings {

    private CallRecSettings() {
    }

    /** Device-level Luckgram switches (same file as LuckgramSettings / other features). */
    public static final String DEVICE_PREFS = "luckgram_settings";

    /** Main switch. Default off; turning it on requires the legal reminder (spec 04 §1). */
    public static final String KEY_ENABLED = "call_recording";
    /** Start recording automatically when a one-on-one call connects. Default off. */
    public static final String KEY_AUTO = "call_recording_auto";
    /** Post a transcript + summary reply via the AI gateway. Default on. */
    public static final String KEY_AI_TRANSCRIPT = "call_recording_ai_transcript";

    /** Per-account key inside UserConfig preferences. 0 / missing = Saved Messages. */
    private static final String KEY_TARGET_DIALOG = "luckgram_call_recording_target";

    private static SharedPreferences devicePrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        try {
            return devicePrefs().getBoolean(KEY_ENABLED, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Use {@link CallRecSettingsActivity#requestEnable} to turn it on so the legal reminder is shown. */
    public static void setEnabled(boolean enabled) {
        devicePrefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean isAutoRecord() {
        return devicePrefs().getBoolean(KEY_AUTO, false);
    }

    public static void setAutoRecord(boolean value) {
        devicePrefs().edit().putBoolean(KEY_AUTO, value).apply();
    }

    public static boolean isAiTranscriptEnabled() {
        return devicePrefs().getBoolean(KEY_AI_TRANSCRIPT, true);
    }

    public static void setAiTranscriptEnabled(boolean value) {
        devicePrefs().edit().putBoolean(KEY_AI_TRANSCRIPT, value).apply();
    }

    /** Dialog the recordings go to; 0 means Saved Messages. */
    public static long getTargetDialogId(int account) {
        return UserConfig.getInstance(account).getPreferences().getLong(KEY_TARGET_DIALOG, 0);
    }

    public static void setTargetDialogId(int account, long dialogId) {
        SharedPreferences.Editor editor = UserConfig.getInstance(account).getPreferences().edit();
        if (dialogId == 0 || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            editor.remove(KEY_TARGET_DIALOG);
        } else {
            editor.putLong(KEY_TARGET_DIALOG, dialogId);
        }
        editor.apply();
    }
}

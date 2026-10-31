/*
 * Luckgram: settings for call recording.
 *
 * Switches are device level and shared by all accounts (spec 00 §5) and go through 00's
 * LuckgramSettings, so the "Luckgram features" page and remote config apply to them.
 *
 * The save-to chat is per account, because a dialog id only means something inside one account.
 * It lives in that account's UserConfig preferences, which Telegram wipes on logout
 * (UserConfig.clearConfig), so nothing is left behind when the account is removed.
 */
package org.telegram.luckgram.callrec;

import android.content.SharedPreferences;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.UserConfig;

public final class CallRecSettings {

    private CallRecSettings() {
    }

    /** Main switch. Default off; turning it on requires the legal reminder (spec 04 §1). */
    public static final String KEY_ENABLED = "call_recording";
    /** Start recording automatically when a one-on-one call connects. Default off. */
    public static final String KEY_AUTO = "call_recording_auto";
    /** Post a transcript + summary reply via the AI gateway. Default on. */
    public static final String KEY_AI_TRANSCRIPT = "call_recording_ai_transcript";

    /** Per-account key inside UserConfig preferences. 0 / missing = Saved Messages. */
    private static final String KEY_TARGET_DIALOG = "luckgram_call_recording_target";

    /**
     * Local switch AND remote config ({@code features.call_recording}), through 00's
     * LuckgramSettings (limeng9088/luckgram-specs#43, #54). Default off (registry).
     */
    public static boolean isEnabled() {
        try {
            return LuckgramSettings.isEnabled(KEY_ENABLED);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Use {@link CallRecSettingsActivity#requestEnable} (own page) or the registry's confirmEnable
     * (Luckgram features page) to turn it on, so the legal reminder is shown first.
     */
    public static void setEnabled(boolean enabled) {
        LuckgramSettings.setEnabled(KEY_ENABLED, enabled);
    }

    public static boolean isAutoRecord() {
        return LuckgramSettings.getBool(KEY_AUTO, false);
    }

    public static void setAutoRecord(boolean value) {
        LuckgramSettings.setBool(KEY_AUTO, value);
    }

    public static boolean isAiTranscriptEnabled() {
        return LuckgramSettings.getBool(KEY_AI_TRANSCRIPT, true);
    }

    public static void setAiTranscriptEnabled(boolean value) {
        LuckgramSettings.setBool(KEY_AI_TRANSCRIPT, value);
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

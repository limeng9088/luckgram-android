package org.telegram.luckgram.voicebar;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.compactinput.LuckgramCompactInput;

/**
 * Luckgram specs#145: WeChat-style voice messages. A toggle left of the input field swaps the field
 * for a 「按住 说话」 bar; holding it records with a full-screen overlay (release to send, slide to
 * 「取消」 or 「转文字」). Only with the compact input bar (specs#94) on.
 * Keep the key in sync with iOS ({@code LuckgramVoiceBar.featureKey}).
 */
public final class LuckgramVoiceBar {

    public static final String FEATURE_KEY = "wechat_voice_bar";

    /** Where the finger is while holding. */
    public static final int ZONE_SEND = 0;
    public static final int ZONE_CANCEL = 1;
    public static final int ZONE_TEXT = 2;

    private LuckgramVoiceBar() {
    }

    public static boolean isEnabled() {
        return LuckgramCompactInput.isEnabled() && LuckgramSettings.isEnabled(FEATURE_KEY);
    }

    /**
     * The zone for a finger position. Above {@code actionLineY} (the top of the release area) the
     * left half cancels and the right half converts to text; below it, releasing sends.
     */
    public static int zoneFor(float x, float y, float width, float actionLineY) {
        if (y >= actionLineY || width <= 0) {
            return ZONE_SEND;
        }
        return x < width / 2f ? ZONE_CANCEL : ZONE_TEXT;
    }

    /** m:ss for the overlay's timer. */
    public static String formatDuration(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}

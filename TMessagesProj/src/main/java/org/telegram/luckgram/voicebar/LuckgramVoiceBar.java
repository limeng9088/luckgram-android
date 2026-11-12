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

    private LuckgramVoiceBar() {
    }

    public static boolean isEnabled() {
        return LuckgramCompactInput.isEnabled() && LuckgramSettings.isEnabled(FEATURE_KEY);
    }
}

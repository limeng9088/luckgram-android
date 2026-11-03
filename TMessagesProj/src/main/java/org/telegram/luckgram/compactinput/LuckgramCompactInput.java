package org.telegram.luckgram.compactinput;

import org.telegram.luckgram.LuckgramSettings;

/**
 * Luckgram specs#94: WeChat-style input bar. When on, the paperclip becomes 「+」 and the Luckgram
 * input buttons (quick replies, voice input, AI writing, translate, ✦) move into the 「+」 panel.
 * Keep the key in sync with iOS ({@code LuckgramCompactInput.featureKey}).
 */
public final class LuckgramCompactInput {

    public static final String FEATURE_KEY = "compact_input_bar";

    private LuckgramCompactInput() {
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(FEATURE_KEY);
    }
}

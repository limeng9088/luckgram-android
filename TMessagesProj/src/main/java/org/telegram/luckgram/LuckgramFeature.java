package org.telegram.luckgram;

import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * One entry of the Luckgram feature registry ({@link LuckgramFeatures}). The settings page is
 * generated from these entries.
 */
public final class LuckgramFeature {

    /** Settings page groups, in display order. */
    public enum Group {
        ACCOUNT_PRIVACY,
        MESSAGES_LANGUAGE,
        MEDIA_CALLS_STORAGE,
        PROFILE_ORGANIZE,
        AI,
        /** Only shown when {@link LuckgramSettings#isDebugToolsVisible()}. */
        DEBUG
    }

    public final String key;
    public final Group group;
    public final boolean defaultValue;
    final String titleZh, titleEn, descZh, descEn;
    /** Opens the feature's own settings page, or null if it has none. */
    public Utilities.Callback0Return<BaseFragment> subpage;
    public boolean debugOnly;

    private LuckgramFeature(String key, Group group, boolean defaultValue, String titleZh, String titleEn, String descZh, String descEn) {
        this.key = key;
        this.group = group;
        this.defaultValue = defaultValue;
        this.titleZh = titleZh;
        this.titleEn = titleEn;
        this.descZh = descZh;
        this.descEn = descEn;
    }

    /**
     * @param key          storage key (also the iOS key), snake_case
     * @param defaultValue default switch state, as written in the feature's spec
     */
    public static LuckgramFeature toggle(String key, Group group, boolean defaultValue, String titleZh, String titleEn, String descZh, String descEn) {
        return new LuckgramFeature(key, group, defaultValue, titleZh, titleEn, descZh, descEn);
    }

    public LuckgramFeature subpage(Utilities.Callback0Return<BaseFragment> subpage) {
        this.subpage = subpage;
        return this;
    }

    public LuckgramFeature debugOnly() {
        this.debugOnly = true;
        return this;
    }

    public String title() {
        return LuckgramStrings.get(titleZh, titleEn);
    }

    public String description() {
        return LuckgramStrings.get(descZh, descEn);
    }

    public boolean isEnabled() {
        return LuckgramSettings.isEnabled(key);
    }

    public boolean isVisible() {
        return !debugOnly || LuckgramSettings.isDebugToolsVisible();
    }
}

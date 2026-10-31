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
    /** Optional check before the switch goes from off to on (e.g. a legal notice); see {@link #confirmEnable}. */
    public ConfirmEnable confirmEnable;
    /** Optional check before the switch goes from on to off (e.g. 11 asks for the main passcode). */
    public ConfirmEnable confirmDisable;
    /** Optional: hide the row while this returns true (e.g. 11 hides itself in the second space). */
    public Utilities.Callback0Return<Boolean> hiddenWhen;

    /**
     * Called before a feature is switched on from the settings page. Call {@code done.run(true)} to
     * switch it on, {@code done.run(false)} to leave it off (issue #58). Not called when switching off.
     */
    public interface ConfirmEnable {
        void confirm(BaseFragment fragment, Utilities.Callback<Boolean> done);
    }

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

    public LuckgramFeature confirmEnable(ConfirmEnable confirm) {
        this.confirmEnable = confirm;
        return this;
    }

    public LuckgramFeature confirmDisable(ConfirmEnable confirm) {
        this.confirmDisable = confirm;
        return this;
    }

    public LuckgramFeature hiddenWhen(Utilities.Callback0Return<Boolean> hidden) {
        this.hiddenWhen = hidden;
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
        if (hiddenWhen != null && hiddenWhen.run()) {
            return false;
        }
        return !debugOnly || LuckgramSettings.isDebugToolsVisible();
    }
}

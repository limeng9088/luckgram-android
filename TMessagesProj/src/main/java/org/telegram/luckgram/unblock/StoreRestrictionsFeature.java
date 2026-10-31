package org.telegram.luckgram.unblock;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

/** Spec 13 (受限频道): switch, explanation dialog. */
public final class StoreRestrictionsFeature {

    public static final String KEY = "store_restrictions_off";

    private StoreRestrictionsFeature() {
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(KEY);
    }

    /** Called by the settings page before the switch goes from off to on. */
    public static void confirmEnable(BaseFragment fragment, Utilities.Callback<Boolean> done) {
        if (fragment == null || fragment.getParentActivity() == null) {
            done.run(false);
            return;
        }
        final boolean[] answered = {false};
        AlertDialog dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
            .setTitle(LuckgramStrings.get("受限频道", "Store-restricted channels"))
            .setMessage(LuckgramStrings.get(
                "打开后，仅在商店版 Telegram 中被隐藏的频道和群组可以查看。Telegram 对所有平台封禁的内容，以及你所在地区依法屏蔽的内容，仍然无法查看。",
                "When on, channels and groups that are hidden only in the App Store / Google Play versions of Telegram can be viewed. Content Telegram blocks on all platforms, and content blocked by law in your region, stays unavailable."))
            .setPositiveButton(LuckgramStrings.get("打开", "Turn On"), (d, w) -> {
                answered[0] = true;
                done.run(true);
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
                answered[0] = true;
                done.run(false);
            })
            .create();
        dialog.setOnDismissListener(d -> {
            if (!answered[0]) {
                done.run(false);
            }
        });
        fragment.showDialog(dialog);
    }
}

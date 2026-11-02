package org.telegram.luckgram.onboarding;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.View;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * specs#78: the Luckgram privacy policy dialog. Shown on the welcome page until the user taps
 * Continue, and to logged-in users once more when {@code app.privacy_version} is raised ("privacy
 * policy updated"). It can't be dismissed by tapping outside or Back; "Privacy Policy" opens the
 * page and leaves it open. Continue stores the agreement with its time and policy version.
 */
public final class PrivacyDialog {

    private static boolean updateCheckedThisRun;

    private PrivacyDialog() {
    }

    public static boolean isAccepted() {
        return LuckgramSettings.getBool(WelcomeLogic.ACCEPTED_KEY, false);
    }

    private static long remoteVersion() {
        return LuckgramSettings.remoteLong(WelcomeLogic.PRIVACY_VERSION_KEY, 0);
    }

    private static void accept() {
        LuckgramSettings.setBool(WelcomeLogic.ACCEPTED_KEY, true);
        LuckgramSettings.setString(WelcomeLogic.ACCEPTED_AT_KEY, String.valueOf(System.currentTimeMillis()));
        LuckgramSettings.setString(WelcomeLogic.ACCEPTED_VERSION_KEY, String.valueOf(remoteVersion()));
    }

    private static long acceptedVersion() {
        try {
            return Long.parseLong(LuckgramSettings.getString(WelcomeLogic.ACCEPTED_VERSION_KEY, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Shows the dialog in {@code activity} ({@code fragment} may be null). {@code onAccepted} runs
     * after Continue.
     */
    public static AlertDialog show(Activity activity, BaseFragment fragment, boolean updated, Runnable onAccepted) {
        if (activity == null) {
            return null;
        }
        boolean zh = LuckgramStrings.isChinese();
        WelcomeLogic.Texts texts = new WelcomeLogic.Texts(zh);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(updated ? texts.privacyUpdatedTitle() : texts.privacyTitle());
        builder.setMessage(updated ? texts.privacyUpdatedText() : texts.privacyText());
        builder.setNeutralButton(texts.privacyPolicyButton(), null);
        builder.setPositiveButton(texts.continueButton(), (dialog, which) -> {
            accept();
            if (onAccepted != null) {
                onAccepted.run();
            }
        });
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        if (fragment != null) {
            fragment.showDialog(dialog);
        } else {
            dialog.show();
        }
        // "Privacy Policy" opens the page and leaves the dialog open.
        View policy = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (policy != null) {
            policy.setOnClickListener(v -> Browser.openUrl(activity,
                WelcomeLogic.privacyUrl(LuckgramSettings.remoteConfig().getString(WelcomeLogic.PRIVACY_URL_KEY, null), zh)));
        }
        return dialog;
    }

    /** At launch with an account logged in: "privacy policy updated" once if the version went up. */
    public static void checkUpdate(Activity activity) {
        if (updateCheckedThisRun) {
            return;
        }
        updateCheckedThisRun = true;
        AndroidUtilities.runOnUIThread(() -> {
            if (activity == null || activity.isFinishing()) {
                return;
            }
            if (WelcomeLogic.shouldPromptUpdate(UserConfig.getActivatedAccountsCount() > 0, remoteVersion(), acceptedVersion())) {
                show(activity, null, true, null);
            }
        }, 1500);
    }
}

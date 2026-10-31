/*
 * Luckgram: the legal reminder must come before call recording is switched on (spec 04 §1),
 * including when the switch is flipped on the "Luckgram features" page, which writes the value
 * directly (limeng9088/luckgram-specs#58).
 *
 * Watches the call_recording switch: switched on without acknowledgement -> show the reminder;
 * "我知道了" acknowledges it, "取消" / dismiss switches it back off. Until acknowledged,
 * CallRecSettings.isEnabled() stays false, so no record button can appear in between.
 */
package org.telegram.luckgram.callrec;

import android.app.Activity;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

public final class CallRecLegalGuard implements NotificationCenter.NotificationCenterDelegate {

    private static CallRecLegalGuard instance;
    private boolean showing;

    private CallRecLegalGuard() {
    }

    /** Called from the registry entry (LuckgramFeatures), i.e. before the switch can be shown. */
    public static synchronized void install() {
        if (instance != null) {
            return;
        }
        instance = new CallRecLegalGuard();
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getGlobalInstance().addObserver(instance, NotificationCenter.luckgramSettingsChanged);
            // A switch left on without acknowledgement (e.g. the app was killed while the
            // reminder was up) counts as off.
            if (LuckgramSettings.getBool(CallRecSettings.KEY_ENABLED, false) && !CallRecSettings.isLegalAcknowledged()) {
                LuckgramSettings.setEnabled(CallRecSettings.KEY_ENABLED, false);
            }
        });
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.luckgramSettingsChanged) {
            return;
        }
        if (args.length > 0 && args[0] != null && !CallRecSettings.KEY_ENABLED.equals(args[0])) {
            return;
        }
        final boolean on = LuckgramSettings.getBool(CallRecSettings.KEY_ENABLED, false);
        if (!on) {
            if (CallRecSettings.isLegalAcknowledged()) {
                LuckgramSettings.setBool(CallRecSettings.KEY_LEGAL_ACK, false);
            }
            return;
        }
        if (CallRecSettings.isLegalAcknowledged() || showing) {
            return;
        }
        final BaseFragment fragment = LaunchActivity.getLastFragment();
        final Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null) {
            LuckgramSettings.setEnabled(CallRecSettings.KEY_ENABLED, false);
            return;
        }
        showing = true;
        CallRecSettingsActivity.showLegalReminder(activity, fragment.getResourceProvider(), agreed -> {
            showing = false;
            if (agreed) {
                CallRecSettings.setEnabled(true);
                CallRecSettingsActivity.maybeAskAiConsent(activity);
            } else {
                LuckgramSettings.setEnabled(CallRecSettings.KEY_ENABLED, false);
            }
        });
    }
}

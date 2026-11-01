package org.telegram.luckgram.update;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;

/**
 * Update check (task S5, luckgram-specs#30, specs/server/S5-version.md item 3):
 * <ul>
 *   <li>{@code GET /v1/version} when the app comes to the foreground, at most once a day (the
 *       last successful check is kept on the device, so restarts do not count);</li>
 *   <li>a newer version shows "有新版本 x.y.z · 更新" on top of Settings
 *       ({@code SettingsActivity}), which opens the download link;</li>
 *   <li>below the minimum version a full-screen page with only the Update button covers the app
 *       until the user has installed a version that is not below the minimum.</li>
 * </ul>
 * Only device state is sent (platform, version, build), so no AI consent is needed.
 */
public final class LuckgramUpdates {

    private static final String KEY_INFO = "update_info";
    private static final String KEY_LAST_CHECK = "update_last_check";

    private static long lastFailureMs;
    private static boolean inFlight;
    private static Dialog forceDialog;

    private LuckgramUpdates() {
    }

    /** Version code reported to and published by CI (GitHub run number); 0 for local builds. */
    public static long currentBuild() {
        return BuildConfig.LUCKGRAM_BUILD;
    }

    /** Main thread. */
    public static void checkIfDue() {
        long now = System.currentTimeMillis();
        long last = LuckgramSettings.prefs().getLong(KEY_LAST_CHECK, 0);
        if (inFlight || !UpdateLogic.shouldCheck(now, last, lastFailureMs)) {
            return;
        }
        inFlight = true;
        String path = UpdateLogic.path("android", BuildConfig.BUILD_VERSION_STRING, currentBuild());
        LuckgramAI.getInstance().requestJson("GET", path, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                inFlight = false;
                lastFailureMs = 0;
                UpdateLogic.Info info = UpdateLogic.parse(result);
                LuckgramSettings.prefs().edit()
                        .putString(KEY_INFO, UpdateLogic.serialize(info))
                        .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                        .apply();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.luckgramUpdateChanged);
            }

            @Override
            public void onError(LuckgramAIError error) {
                inFlight = false;
                lastFailureMs = System.currentTimeMillis();
            }
        });
    }

    public static UpdateLogic.State state() {
        UpdateLogic.Info info = UpdateLogic.deserialize(LuckgramSettings.getString(KEY_INFO, ""));
        return UpdateLogic.evaluate(info, BuildConfig.BUILD_VERSION_STRING, currentBuild(), LuckgramStrings.isChinese());
    }

    /** "有新版本 x.y.z · 更新". */
    public static String rowText(UpdateLogic.State s) {
        return LuckgramStrings.get("有新版本 " + s.latest + " · 更新", "New version " + s.latest + " · Update");
    }

    public static void openDownload(Activity activity, UpdateLogic.State s) {
        if (activity != null && s.url.startsWith("https://")) {
            Browser.openUrl(activity, s.url);
        }
    }

    /** LaunchActivity: on resume and when a check finished. Shows or removes the force page. */
    public static void showForceIfNeeded(Activity activity) {
        UpdateLogic.State s = state();
        if (!s.forceUpdate) {
            if (forceDialog != null) {
                try {
                    forceDialog.dismiss();
                } catch (Exception ignore) {
                }
                forceDialog = null;
            }
            return;
        }
        if (activity == null || activity.isFinishing() || (forceDialog != null && forceDialog.isShowing())) {
            return;
        }
        forceDialog = createForceDialog(activity, s);
        try {
            forceDialog.show();
        } catch (Exception e) {
            forceDialog = null;
        }
    }

    private static Dialog createForceDialog(Activity activity, UpdateLogic.State s) {
        Dialog d = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        // Back does nothing: the only way on is to update.
        d.setOnKeyListener((dialog, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        d.setOnDismissListener(dialog -> {
            if (forceDialog == dialog) {
                forceDialog = null;
            }
        });

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = AndroidUtilities.dp(32);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(activity);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setText(LuckgramStrings.get("需要更新", "Update required"));
        box.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(activity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        String body = LuckgramStrings.get(
                "当前版本 " + BuildConfig.BUILD_VERSION_STRING + " 已停止支持，请更新到 " + s.latest + " 后继续使用。",
                "Version " + BuildConfig.BUILD_VERSION_STRING + " is no longer supported. Update to " + s.latest + " to continue.");
        if (!TextUtils.isEmpty(s.notes)) {
            body += "\n\n" + s.notes;
        }
        text.setText(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AndroidUtilities.dp(12);
        box.addView(text, lp);

        TextView button = new TextView(activity);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(8));
        bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        button.setBackground(bg);
        button.setText(LuckgramStrings.get("更新", "Update"));
        button.setOnClickListener(v -> openDownload(activity, s));
        lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48));
        lp.topMargin = AndroidUtilities.dp(24);
        box.addView(button, lp);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        FrameLayout center = new FrameLayout(activity);
        center.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        scroll.addView(center, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        d.setContentView(scroll);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(null);
        }
        return d;
    }
}

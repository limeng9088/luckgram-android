package org.telegram.luckgram.nearby;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.discover.DiscoverBlocks;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

/**
 * Device-wide state of "Visible nearby" (spec 25): the expiry time, the 1-hour reminder and the
 * cleanup on logout / unlink. Network calls go through {@link LuckgramAI#requestJson}; nothing is
 * sent while the {@code nearby} switch is off.
 */
public final class NearbyController {

    private NearbyController() {
    }

    private static final String KEY_EXPIRES = "nearby.expires_at";
    private static final String KEY_INTRO = "nearby.intro_done";
    private static final String KEY_BIO = "nearby.bio";
    private static final String KEY_PHOTO = "nearby.show_photo";
    /** Telegram account whose username is on the uploaded card. */
    private static final String KEY_ACCOUNT = "nearby.account";
    private static final String CHANNEL = "luckgram_nearby";
    private static final int NOTIFICATION_ID = 0x4c4e4231;

    public static boolean isFeatureEnabled() {
        return LuckgramSettings.isEnabled(NearbyLogic.FEATURE_KEY);
    }

    // ---------------------------------------------------------------- local state

    public static long expiresAt() {
        try {
            return Long.parseLong(LuckgramSettings.getString(KEY_EXPIRES, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** True while the last successful PUT has not expired yet. */
    public static boolean isVisible() {
        long e = expiresAt();
        return e > 0 && !NearbyLogic.isExpired(e, System.currentTimeMillis());
    }

    public static boolean introDone() {
        return LuckgramSettings.getBool(KEY_INTRO, false);
    }

    public static void setIntroDone() {
        LuckgramSettings.setBool(KEY_INTRO, true);
    }

    public static String bio() {
        return LuckgramSettings.getString(KEY_BIO, "");
    }

    public static void setBio(String bio) {
        LuckgramSettings.setString(KEY_BIO, NearbyLogic.cleanBio(bio));
    }

    public static boolean showPhoto() {
        return LuckgramSettings.getBool(KEY_PHOTO, true);
    }

    public static void setShowPhoto(boolean show) {
        LuckgramSettings.setBool(KEY_PHOTO, show);
    }

    /** Server state from {@code PUT} or {@code GET /v1/nearby/me}. */
    public static void applyState(int account, NearbyLogic.MyState s) {
        if (s.visible && s.expiresAtMs > System.currentTimeMillis()) {
            LuckgramSettings.setString(KEY_EXPIRES, Long.toString(s.expiresAtMs));
            if (account >= 0) LuckgramSettings.setInt(KEY_ACCOUNT, account);
            scheduleReminder(s.expiresAtMs);
        } else {
            clearLocal();
        }
    }

    private static void clearLocal() {
        LuckgramSettings.remove(KEY_EXPIRES);
        cancelReminder();
    }

    // ---------------------------------------------------------------- server

    public static LuckgramAI.Request turnOff(LuckgramAI.Callback<JSONObject> callback) {
        clearLocal();
        return LuckgramAI.getInstance().requestJson("DELETE", "/v1/nearby/me", null, callback);
    }

    /**
     * Logout of an account (called from {@code MessagesController.performLogout} before the
     * account and, for the last account, the device token are cleared): delete the card now.
     */
    public static void onLogout(int account) {
        DiscoverBlocks.clearLocal();
        if (!isVisible() || LuckgramSettings.getInt(KEY_ACCOUNT, account) != account) return;
        clearLocal();
        LuckgramAI.getInstance().sendWithCurrentToken("DELETE", "/v1/nearby/me");
    }

    /** S6 unlink: the server deletes the card itself; only forget it here. */
    public static void onUnlinked() {
        DiscoverBlocks.clearLocal();
        clearLocal();
    }

    /** Errors that mean the server no longer has (or won't keep) the card. */
    public static void onError(LuckgramAIError e) {
        if (e != null && ("not_linked".equals(e.code) || "banned".equals(e.code))) {
            clearLocal();
        }
    }

    // ---------------------------------------------------------------- reminder

    private static PendingIntent reminderIntent(Context ctx, int flags) {
        Intent i = new Intent(ctx, NearbyController.ReminderReceiver.class);
        return PendingIntent.getBroadcast(ctx, NOTIFICATION_ID, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleReminder(long expiresAtMs) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = reminderIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT);
            long at = NearbyLogic.reminderAt(expiresAtMs, System.currentTimeMillis());
            if (at == 0) {
                am.cancel(pi);
                return;
            }
            // Inexact is fine (a few minutes late at most) and needs no exact-alarm permission.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void cancelReminder() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(reminderIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT));
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    static void showReminder(Context ctx) {
        if (!isFeatureEnabled() || !isVisible()) return;
        NearbyLogic.Texts tx = new NearbyLogic.Texts(LuckgramStrings.isChinese());
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, tx.channelName(), NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(ctx, LaunchActivity.class);
        open.setAction(Intent.ACTION_VIEW);
        open.putExtra(EXTRA_OPEN_NEARBY, true);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle(tx.reminderTitle())
            .setContentText(tx.reminderText())
            .setAutoCancel(true)
            .setContentIntent(pi);
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** LaunchActivity intent extra: open the People Nearby page (from the reminder). */
    public static final String EXTRA_OPEN_NEARBY = "luckgram_open_nearby";

    public static class ReminderReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            showReminder(context);
        }
    }
}

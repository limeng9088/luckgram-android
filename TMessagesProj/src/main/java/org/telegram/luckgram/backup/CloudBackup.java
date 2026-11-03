package org.telegram.luckgram.backup;

import android.util.Base64;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSecureStore;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.account.SilentLink;
import org.telegram.luckgram.accounts.LuckgramAccounts;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Luckgram 32: cloud backup (Pro). The 10 backup file is encrypted on this device with the user's
 * password (PBKDF2 + AES-GCM, {@link LuckgramBackupFormat}) and only that ciphertext is uploaded to
 * {@code /v1/backup} (S15). The password is never sent; it is kept on this device (Keystore) only
 * when the user turns on the weekly automatic backup.
 */
public final class CloudBackup {

    private static final String KEY_AUTO = "cloud_backup.auto";
    private static final String KEY_LAST_AUTO = "cloud_backup.last_auto";
    private static final String KEY_LAST_TRY = "cloud_backup.last_try";
    /** A failed weekly upload is retried after this long, not on every app start. */
    private static final long RETRY_MS = 6L * 60 * 60 * 1000;
    /** Keystore-encrypted, see {@link LuckgramSecureStore}. Never uploaded. */
    private static final String SECRET_PASSWORD = "cloud_backup.password";

    private static boolean autoRunning;

    private CloudBackup() {
    }

    // ---------------------------------------------------------------- strings

    public static String headerText() {
        return LuckgramStrings.get("云备份", "Cloud backup");
    }

    public static String proOnlyText() {
        return LuckgramStrings.get("Pro 专享：把加密后的账号备份存到云端，换手机后登录任意一个账号即可恢复。",
                "Pro: keep an encrypted account backup in the cloud and restore it on a new phone after logging in to any one account.");
    }

    public static String uploadText() {
        return LuckgramStrings.get("备份到云端", "Back up to cloud");
    }

    public static String restoreText() {
        return LuckgramStrings.get("从云端恢复", "Restore from cloud");
    }

    public static String deleteText() {
        return LuckgramStrings.get("删除云备份", "Delete cloud backup");
    }

    public static String deleteConfirmText() {
        return LuckgramStrings.get("删除后云端不再保存这份备份，无法撤销。", "The backup will be removed from the cloud. This can't be undone.");
    }

    public static String autoText() {
        return LuckgramStrings.get("每周自动备份", "Back up weekly");
    }

    public static String autoInfoText() {
        return LuckgramStrings.get("开启时需要在本机记住备份密码（保存在系统 Keystore，不会上传）。关闭后会从本机删除这个密码。",
                "Requires remembering the backup password on this device (kept in the system Keystore, never uploaded). Turning it off removes the password from this device.");
    }

    public static String rememberText() {
        return LuckgramStrings.get("在本机记住备份密码（存系统 Keystore），用于每周自动备份",
                "Remember the backup password on this device (system Keystore) for the weekly backup");
    }

    public static String cloudWarningText() {
        return LuckgramStrings.get("云端只保存加密后的数据，Luckgram 无法解密；忘记密码将无法恢复。",
                "The cloud only keeps encrypted data that Luckgram can't decrypt. If you forget the password, the backup can't be restored.");
    }

    public static String loadingText() {
        return LuckgramStrings.get("正在读取云备份…", "Checking cloud backup…");
    }

    public static String noneText() {
        return LuckgramStrings.get("云端还没有备份", "No cloud backup yet");
    }

    public static String statusText(CloudBackupLogic.Meta meta) {
        String when = meta.createdAt > 0
                ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(new Date(meta.createdAt))
                : "—";
        return LuckgramStrings.get("最近一次云备份：" + when + " · " + meta.accounts + " 个账号",
                "Last cloud backup: " + when + " · " + meta.accounts + (meta.accounts == 1 ? " account" : " accounts"));
    }

    public static String uploadedText() {
        return LuckgramStrings.get("已备份到云端", "Backed up to cloud");
    }

    public static String deletedText() {
        return LuckgramStrings.get("云备份已删除", "Cloud backup deleted");
    }

    public static String needAccountText() {
        return LuckgramStrings.get("请先登录任意一个账号，再从云端恢复。", "Log in to any one account first, then restore from the cloud.");
    }

    public static String notLinkedText() {
        return LuckgramStrings.get("暂时无法连接 Luckgram 服务，请稍后再试。", "Can't reach the Luckgram service right now. Try again later.");
    }

    public static String errorText(LuckgramAIError error) {
        if (error != null && "not_pro".equals(error.code)) {
            return LuckgramStrings.get("云备份是 Pro 专享功能。", "Cloud backup is a Pro feature.");
        }
        if (error != null && "not_linked".equals(error.code)) {
            return notLinkedText();
        }
        return error != null ? error.getLocalizedMessage() : notLinkedText();
    }

    // ---------------------------------------------------------------- requests

    /** {@code GET /v1/backup}: {@code null} result when there is no backup. Callbacks on the UI thread. */
    public static void fetch(int account, LuckgramAI.Callback<CloudBackupLogic.Meta> callback) {
        withLink(account, callback, () -> LuckgramAI.getInstance().requestJson("GET", CloudBackupLogic.PATH, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                callback.onResult(CloudBackupLogic.parseMeta(result));
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (CloudBackupLogic.isNotFound(error.code, error.httpStatus)) {
                    callback.onResult(null);
                } else {
                    callback.onError(error);
                }
            }
        }));
    }

    /** The encrypted file of the latest cloud backup, or {@code null} when there is none. */
    public static void download(int account, LuckgramAI.Callback<byte[]> callback) {
        fetch(account, new LuckgramAI.Callback<CloudBackupLogic.Meta>() {
            @Override
            public void onResult(CloudBackupLogic.Meta meta) {
                if (meta == null || meta.data == null) {
                    callback.onResult(null);
                    return;
                }
                byte[] data;
                try {
                    data = Base64.decode(meta.data, Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    data = null;
                }
                callback.onResult(data);
            }

            @Override
            public void onError(LuckgramAIError error) {
                callback.onError(error);
            }
        });
    }

    /**
     * Encrypts every logged-in account with {@code password} on a background thread (same file as
     * 10's export) and uploads the ciphertext. Keys are wiped right after encryption.
     */
    public static void upload(int account, String password, LuckgramAI.Callback<CloudBackupLogic.Meta> callback) {
        List<Integer> slots = LuckgramAccounts.orderedAccounts();
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<LuckgramBackupFormat.Account> items = LuckgramBackup.collect(slots);
            String encoded = null;
            int count = items.size();
            byte[] data = null;
            try {
                if (count > 0) {
                    data = LuckgramBackupFormat.encrypt(items, password);
                    encoded = Base64.encodeToString(data, Base64.NO_WRAP);
                }
            } catch (Throwable e) {
                FileLog.e("luckgram cloud backup: encrypt failed " + e.getClass().getSimpleName());
            } finally {
                for (LuckgramBackupFormat.Account item : items) {
                    item.wipe();
                }
                if (data != null) {
                    Arrays.fill(data, (byte) 0);
                }
            }
            final String body64 = encoded;
            AndroidUtilities.runOnUIThread(() -> {
                if (body64 == null) {
                    callback.onError(new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
                    return;
                }
                long now = System.currentTimeMillis();
                JSONObject body;
                try {
                    body = CloudBackupLogic.uploadBody(body64, count, now);
                } catch (Exception e) {
                    callback.onError(new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
                    return;
                }
                withLink(account, callback, () -> LuckgramAI.getInstance().requestJson("PUT", CloudBackupLogic.PATH, body, new LuckgramAI.Callback<JSONObject>() {
                    @Override
                    public void onResult(JSONObject result) {
                        CloudBackupLogic.Meta meta = CloudBackupLogic.parseMeta(result);
                        callback.onResult(meta != null ? meta : new CloudBackupLogic.Meta(count, now, null));
                    }

                    @Override
                    public void onError(LuckgramAIError error) {
                        callback.onError(error);
                    }
                }));
            });
        });
    }

    /** {@code DELETE /v1/backup}. */
    public static void delete(int account, LuckgramAI.Callback<Boolean> callback) {
        withLink(account, callback, () -> LuckgramAI.getInstance().requestJson("DELETE", CloudBackupLogic.PATH, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                callback.onResult(true);
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (CloudBackupLogic.isNotFound(error.code, error.httpStatus)) {
                    callback.onResult(true);
                } else {
                    callback.onError(error);
                }
            }
        }));
    }

    /** The backup belongs to the Telegram user linked to this device (S6); link silently first. */
    private static void withLink(int account, LuckgramAI.Callback<?> callback, Runnable request) {
        if (account < 0 || !UserConfig.getInstance(account).isClientActivated()) {
            callback.onError(new LuckgramAIError("not_linked", 0));
            return;
        }
        SilentLink.ensure(account, linked -> {
            if (linked) {
                request.run();
            } else {
                callback.onError(new LuckgramAIError("not_linked", 0));
            }
        });
    }

    /** Any logged-in account, for restoring on the login screen (needs S6 link to the same user). */
    public static int anyLoggedInAccount() {
        if (UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return UserConfig.selectedAccount;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                return a;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- weekly automatic backup

    public static boolean isAutoEnabled() {
        return LuckgramSettings.prefs().getBoolean(KEY_AUTO, false) && hasRememberedPassword();
    }

    public static boolean hasRememberedPassword() {
        String password = LuckgramSecureStore.get(SECRET_PASSWORD);
        return password != null && password.length() >= LuckgramBackupFormat.MIN_PASSWORD_LENGTH;
    }

    /** Turns the weekly backup on with {@code password} kept in the Keystore, or off (password removed). */
    public static void setAuto(String password) {
        if (password != null && password.length() >= LuckgramBackupFormat.MIN_PASSWORD_LENGTH) {
            LuckgramSecureStore.set(SECRET_PASSWORD, password);
            LuckgramSettings.prefs().edit().putBoolean(KEY_AUTO, hasRememberedPassword()).apply();
        } else {
            LuckgramSecureStore.set(SECRET_PASSWORD, null);
            LuckgramSettings.prefs().edit().putBoolean(KEY_AUTO, false).remove(KEY_LAST_AUTO).apply();
        }
    }

    /** Marks a successful upload so the weekly timer restarts. */
    public static void markUploaded() {
        LuckgramSettings.prefs().edit().putLong(KEY_LAST_AUTO, System.currentTimeMillis()).apply();
    }

    /** Called when the app comes to the foreground; uploads at most once a week, Pro only. */
    public static void runAutoIfDue() {
        if (autoRunning || !LuckgramAccounts.isPro()) {
            return;
        }
        boolean enabled = LuckgramSettings.prefs().getBoolean(KEY_AUTO, false);
        if (!enabled) {
            return;
        }
        String password = LuckgramSecureStore.get(SECRET_PASSWORD);
        long last = LuckgramSettings.prefs().getLong(KEY_LAST_AUTO, 0);
        if (!CloudBackupLogic.autoBackupDue(true, password != null, last, System.currentTimeMillis())) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastTry = LuckgramSettings.prefs().getLong(KEY_LAST_TRY, 0);
        if (lastTry > 0 && lastTry <= now && now - lastTry < RETRY_MS) {
            return;
        }
        int account = anyLoggedInAccount();
        if (account < 0) {
            return;
        }
        LuckgramSettings.prefs().edit().putLong(KEY_LAST_TRY, now).apply();
        autoRunning = true;
        upload(account, password, new LuckgramAI.Callback<CloudBackupLogic.Meta>() {
            @Override
            public void onResult(CloudBackupLogic.Meta result) {
                autoRunning = false;
                markUploaded();
            }

            @Override
            public void onError(LuckgramAIError error) {
                autoRunning = false;
                FileLog.d("luckgram cloud backup: weekly upload failed " + error.code);
            }
        });
    }
}

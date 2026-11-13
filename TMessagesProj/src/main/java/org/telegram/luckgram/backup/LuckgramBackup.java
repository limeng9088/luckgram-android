package org.telegram.luckgram.backup;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.accounts.LuckgramAccounts;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Luckgram account backup: exports the chosen accounts to an encrypted {@code .luckgram-backup} file
 * ({@link LuckgramBackupFormat}) and restores accounts from one (user 2026-11-13: local file backup;
 * the cloud backup of 32 is on hold, {@link CloudBackup#ENABLED}). Auth keys are only ever held in
 * memory and wiped as soon as they are used; nothing about them is logged. Only the ciphertext is
 * written to disk.
 */
public final class LuckgramBackup {

    private LuckgramBackup() {
    }

    public static final String FEATURE_KEY = "account_backup";
    private static final long MAX_FILE_SIZE = 1024 * 1024;

    // ---------------------------------------------------------------- strings

    public static String titleText() {
        return LuckgramStrings.get("账号备份", "Account backup");
    }

    public static String subtitleText() {
        return LuckgramStrings.get("把账号导出为加密文件，换手机后导入", "Export accounts to an encrypted file and import them on a new phone");
    }

    public static String selectHeaderText() {
        return LuckgramStrings.get("选择要导出的账号", "Accounts to export");
    }

    public static String exportText() {
        return LuckgramStrings.get("导出为文件", "Export to file");
    }

    public static String restoreText() {
        return LuckgramStrings.get("从备份恢复", "Restore from backup");
    }

    public static String saveToDeviceText() {
        return LuckgramStrings.get("保存到手机", "Save to device");
    }

    public static String shareText() {
        return LuckgramStrings.get("分享…", "Share…");
    }

    public static String savedText() {
        return LuckgramStrings.get("备份文件已保存", "Backup file saved");
    }

    public static String exportFailedText() {
        return LuckgramStrings.get("导出失败", "Export failed");
    }

    /** specs#150: where the exported file is and what it is called. */
    public static String exportHintText() {
        return LuckgramStrings.get("导出的文件以 ." + LuckgramBackupFormat.FILE_EXTENSION + " 结尾，例如 luckgram-20261113-0930." + LuckgramBackupFormat.FILE_EXTENSION + "。请存到你之后能找到的地方（如「下载」或「文件」）。",
                "The exported file ends in ." + LuckgramBackupFormat.FILE_EXTENSION + ", e.g. luckgram-20261113-0930." + LuckgramBackupFormat.FILE_EXTENSION + ". Keep it somewhere you can find it later (e.g. Downloads or Files).");
    }

    /** specs#150: which file to pick when restoring. */
    public static String restoreHintText() {
        return LuckgramStrings.get("恢复时选择以 ." + LuckgramBackupFormat.FILE_EXTENSION + " 结尾的备份文件（一般在「下载」或「文件」里）。",
                "To restore, pick the backup file ending in ." + LuckgramBackupFormat.FILE_EXTENSION + " (usually in Downloads or Files).");
    }

    public static String infoText() {
        return LuckgramStrings.get("备份文件里只有登录信息和备注名，不包含消息、联系人或媒体。文件用你设置的密码加密，iPhone 和 Android 通用。",
                "The file holds only the logins and the local names, no messages, contacts or media. It is encrypted with your password and works on both iPhone and Android.");
    }

    public static String warningText() {
        return LuckgramStrings.get("这个文件等于你账号的钥匙。任何人拿到文件和密码就能登录你的账号。不要发给别人，不要存到不信任的地方。",
                "This file is the key to your accounts. Anyone with the file and the password can log in to them. Don't send it to anyone and don't keep it anywhere you don't trust.");
    }

    public static String passwordTitleText() {
        return LuckgramStrings.get("设置备份密码", "Set a backup password");
    }

    public static String passwordHintText() {
        return LuckgramStrings.get("密码（至少 8 位）", "Password (at least 8 characters)");
    }

    public static String passwordRepeatHintText() {
        return LuckgramStrings.get("再次输入密码", "Repeat password");
    }

    public static String passwordTooShortText() {
        return LuckgramStrings.get("密码至少 8 位", "The password must have at least 8 characters");
    }

    public static String passwordMismatchText() {
        return LuckgramStrings.get("两次输入的密码不一致", "The passwords don't match");
    }

    public static String enterPasswordTitleText() {
        return LuckgramStrings.get("输入备份密码", "Enter the backup password");
    }

    public static String wrongPasswordText() {
        return LuckgramStrings.get("密码不正确", "Incorrect password");
    }

    public static String waitText(int seconds) {
        return LuckgramStrings.get("请 " + seconds + " 秒后再试", "Try again in " + seconds + " s");
    }

    public static String notBackupText() {
        return LuckgramStrings.get("这不是 Luckgram 备份文件，或文件已损坏", "This isn't a Luckgram backup file, or the file is damaged");
    }

    public static String chooseAccountsText() {
        return LuckgramStrings.get("选择要恢复的账号", "Accounts to restore");
    }

    public static String alreadyLoggedInText() {
        return LuckgramStrings.get("已登录", "Already logged in");
    }

    public static String restoreButtonText() {
        return LuckgramStrings.get("恢复", "Restore");
    }

    public static String selectAtLeastOneText() {
        return LuckgramStrings.get("请至少选择一个账号", "Select at least one account");
    }

    public static String noRoomText() {
        return LuckgramStrings.get("已达到账号上限，无法恢复更多账号", "The account limit is reached; no more accounts can be restored");
    }

    /**
     * Like {@link #resultText(int, int, int)}, plus the accounts left out because the account
     * limit was reached: told apart from real failures, with the limit and, for free users,
     * that Pro has no limit.
     */
    public static String resultText(int restored, int skipped, int failed, int noRoom) {
        StringBuilder sb = new StringBuilder(resultText(restored, skipped, failed));
        if (noRoom > 0) {
            int max = LuckgramAccounts.maxAccounts();
            if (LuckgramAccounts.offerUpgrade()) {
                sb.append(LuckgramStrings.get(
                        "，" + noRoom + " 个因达到免费版上限（" + max + " 个）未恢复，升级 Pro 可登录的账号数量不限",
                        ", " + noRoom + " not restored: free accounts can log in to at most " + max + " accounts. Upgrade to Pro for unlimited accounts"));
            } else {
                sb.append(LuckgramStrings.get(
                        "，" + noRoom + " 个因达到账号上限（" + max + " 个）未恢复",
                        ", " + noRoom + " not restored: the limit of " + max + " accounts is reached"));
            }
        }
        return sb.toString();
    }

    public static String resultText(int restored, int skipped, int failed) {
        StringBuilder sb = new StringBuilder();
        sb.append(LuckgramStrings.get("已恢复 " + restored + " 个账号", restored + " account(s) restored"));
        if (skipped > 0) {
            sb.append(LuckgramStrings.get("，" + skipped + " 个已登录的账号已跳过", ", " + skipped + " already logged in skipped"));
        }
        if (failed > 0) {
            sb.append(LuckgramStrings.get("，" + failed + " 个账号恢复失败（会话可能已在其他设备上被终止）", ", " + failed + " failed (the session may have been terminated on another device)"));
        }
        return sb.toString();
    }

    public static String accountLabel(long userId, String name) {
        return name == null || name.isEmpty() ? LuckgramStrings.get("账号 " + userId, "Account " + userId) : name + " · " + userId;
    }

    // ---------------------------------------------------------------- read keys

    /** Reads the auth keys of {@code accounts} (account slots). Caller wipes the result. */
    public static ArrayList<LuckgramBackupFormat.Account> collect(List<Integer> accounts) {
        ArrayList<LuckgramBackupFormat.Account> result = new ArrayList<>();
        for (int account : accounts) {
            UserConfig userConfig = UserConfig.getInstance(account);
            if (!userConfig.isClientActivated()) {
                continue;
            }
            byte[] raw = ConnectionsManager.native_luckgramExportAuth(account);
            if (raw == null || raw.length != 4 + 8 + 256) {
                if (raw != null) {
                    Arrays.fill(raw, (byte) 0);
                }
                continue;
            }
            int dcId = (raw[0] & 0xff) | (raw[1] & 0xff) << 8 | (raw[2] & 0xff) << 16 | (raw[3] & 0xff) << 24;
            long salt = 0;
            for (int i = 7; i >= 0; i--) {
                salt = (salt << 8) | (raw[4 + i] & 0xff);
            }
            byte[] key = Arrays.copyOfRange(raw, 12, raw.length);
            Arrays.fill(raw, (byte) 0);
            String alias = LuckgramAccounts.getAlias(account);
            result.add(new LuckgramBackupFormat.Account(userConfig.getClientUserId(), dcId, key, salt, alias));
        }
        return result;
    }

    // ---------------------------------------------------------------- export

    /** Reads the keys and encrypts them on a background thread; {@code done} gets the file bytes (null on failure) on the UI thread. */
    public static void encrypt(List<Integer> accounts, String password, Utilities.Callback<byte[]> done) {
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<LuckgramBackupFormat.Account> items = collect(accounts);
            byte[] data = null;
            try {
                if (!items.isEmpty()) {
                    data = LuckgramBackupFormat.encrypt(items, password);
                }
            } catch (Throwable e) {
                FileLog.e("luckgram backup: export failed " + e.getClass().getSimpleName());
            } finally {
                for (LuckgramBackupFormat.Account item : items) {
                    item.wipe();
                }
            }
            final byte[] result = data;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    public static String fileName() {
        return "luckgram-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + "." + LuckgramBackupFormat.FILE_EXTENSION;
    }

    /** "Save to device": writes the (already encrypted) file to the document the user created. */
    public static boolean writeTo(Uri uri, byte[] data) {
        try (OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                return false;
            }
            out.write(data);
            return true;
        } catch (Throwable e) {
            FileLog.e("luckgram backup: save failed " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** "Share…": writes the encrypted file to the app's private cache and opens the share sheet. */
    public static boolean share(Activity activity, byte[] data) {
        try {
            File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/luckgram-backup");
            deleteOldFiles(dir);
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File file = new File(dir, fileName());
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
            }
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, exportText()));
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Files from earlier shares are only kept until the next one. */
    private static void deleteOldFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ---------------------------------------------------------------- import

    public static byte[] readFile(Uri uri) throws Exception {
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalStateException("no stream");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_FILE_SIZE) {
                    throw new IllegalStateException("too large");
                }
            }
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- restore

    public static boolean isLoggedIn(long userId) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig userConfig = UserConfig.getInstance(a);
            if (userConfig.isClientActivated() && userConfig.getClientUserId() == userId) {
                return true;
            }
        }
        return false;
    }

    /** Result of {@link #restore}: counts and the first restored account slot (-1 if none). */
    public static final class Result {
        public int restored;
        public int skipped;
        public int failed;
        public int noRoom;
        public int firstAccount = -1;
    }

    /**
     * Restores {@code accounts} into free account slots, {@code preferredSlot} first (the empty
     * account of the login screen, or -1). Wipes every key. {@code done} runs on the UI thread.
     */
    public static void restore(List<LuckgramBackupFormat.Account> accounts, int preferredSlot, Utilities.Callback<Result> done) {
        Result result = new Result();
        ArrayList<LuckgramBackupFormat.Account> pending = new ArrayList<>();
        HashSet<Integer> usedSlots = new HashSet<>();
        ArrayList<Integer> slots = new ArrayList<>();
        for (LuckgramBackupFormat.Account account : accounts) {
            if (isLoggedIn(account.userId)) {
                result.skipped++;
                account.wipe();
                continue;
            }
            // Luckgram 32: restore stays within the free / Pro account limit
            int slot = LuckgramAccounts.activatedCount() + usedSlots.size() < LuckgramAccounts.maxAccounts() ? freeSlot(preferredSlot, usedSlots) : -1;
            if (slot < 0) {
                result.noRoom++;
                account.wipe();
                continue;
            }
            usedSlots.add(slot);
            slots.add(slot);
            pending.add(account);
        }
        if (pending.isEmpty()) {
            done.run(result);
            return;
        }
        final int[] remaining = {pending.size()};
        for (int i = 0; i < pending.size(); i++) {
            LuckgramBackupFormat.Account account = pending.get(i);
            int slot = slots.get(i);
            restoreOne(account, slot, user -> {
                if (user != null) {
                    result.restored++;
                    if (result.firstAccount < 0) {
                        result.firstAccount = slot;
                    }
                } else {
                    result.failed++;
                }
                remaining[0]--;
                if (remaining[0] == 0) {
                    done.run(result);
                }
            });
        }
    }

    private static int freeSlot(int preferredSlot, HashSet<Integer> used) {
        if (preferredSlot >= 0 && preferredSlot < UserConfig.MAX_ACCOUNT_COUNT && !used.contains(preferredSlot) && !UserConfig.getInstance(preferredSlot).isClientActivated()) {
            return preferredSlot;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!used.contains(a) && !UserConfig.getInstance(a).isClientActivated() && a != UserConfig.selectedAccount) {
                return a;
            }
        }
        return -1;
    }

    private static void restoreOne(LuckgramBackupFormat.Account account, int slot, Utilities.Callback<TLRPC.User> done) {
        final long userId = account.userId;
        final String name = account.name;
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(slot);
        // The native side copies the key; wipe ours right away.
        ConnectionsManager.native_luckgramImportAuth(slot, account.dcId, account.authKey, account.serverSalt);
        account.wipe();
        connectionsManager.setUserId(userId);

        TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        req.id.add(new TLRPC.TL_inputUserSelf());
        connectionsManager.sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            TLRPC.User user = null;
            if (response instanceof Vector) {
                Vector<?> vector = (Vector<?>) response;
                if (!vector.objects.isEmpty() && vector.objects.get(0) instanceof TLRPC.User) {
                    user = (TLRPC.User) vector.objects.get(0);
                }
            }
            if (user == null || user.id != userId) {
                FileLog.e("luckgram backup: restore failed for account slot " + slot + (error != null ? " " + error.code : ""));
                connectionsManager.setUserId(0);
                connectionsManager.cleanup(true);
                done.run(null);
                return;
            }
            onRestored(slot, user, name);
            done.run(user);
        }), ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    /** Same steps as LoginActivity.onAuthSuccess. */
    private static void onRestored(int slot, TLRPC.User user, String name) {
        MessagesController.getInstance(slot).cleanup();
        UserConfig.getInstance(slot).clearConfig();
        MessagesController.getInstance(slot).cleanup();
        UserConfig.getInstance(slot).setCurrentUser(user);
        UserConfig.getInstance(slot).saveConfig(true);
        MessagesStorage.getInstance(slot).cleanup(true);
        ArrayList<TLRPC.User> users = new ArrayList<>();
        users.add(user);
        MessagesStorage.getInstance(slot).putUsersAndChats(users, null, true, true);
        MessagesController.getInstance(slot).putUser(user, false);
        ContactsController.getInstance(slot).checkAppAccount();
        MessagesController.getInstance(slot).checkPromoInfo(true);
        ConnectionsManager.getInstance(slot).updateDcSettings();
        MessagesController.getInstance(slot).loadAppConfig();
        MessagesController.getInstance(slot).loadWebBrowserConfig();
        MessagesController.getInstance(slot).checkPeerColors(false);
        if (name != null && !name.isEmpty()) {
            LuckgramAccounts.setAlias(slot, name);
        }
    }
}

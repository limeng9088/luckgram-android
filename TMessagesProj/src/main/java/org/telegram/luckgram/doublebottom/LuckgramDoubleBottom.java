package org.telegram.luckgram.doublebottom;

import android.text.TextUtils;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Luckgram 11 (double space): a second passcode. Unlocking with it shows only the accounts the user
 * picked; unlocking with the main passcode shows everything. The second passcode is stored as a
 * salted SHA-256 hash, exactly like Telegram's own passcode ({@code SharedConfig.checkPasscode}).
 * Same behaviour as iOS {@code LuckgramDoubleBottom}.
 */
public final class LuckgramDoubleBottom {

    private LuckgramDoubleBottom() {
    }

    public static final String FEATURE_KEY = "double_bottom";

    private static final String KEY_HASH = "double_bottom.hash";
    private static final String KEY_SALT = "double_bottom.salt";
    private static final String KEY_VISIBLE = "double_bottom.visible";
    private static final String KEY_ACTIVE = "double_bottom.active";

    // ---------------------------------------------------------------- strings

    public static String titleText() {
        return LuckgramStrings.get("双重空间", "Double space");
    }

    public static String descriptionText() {
        return LuckgramStrings.get("用第二个密码解锁时只显示选定的账号", "A second passcode that shows only the accounts you pick");
    }

    public static String needPasscodeText() {
        return LuckgramStrings.get("请先在「隐私与安全 → 密码锁」里开启应用密码锁。", "Turn on Passcode Lock in Privacy and Security first.");
    }

    public static String openPasscodeText() {
        return LuckgramStrings.get("开启密码锁", "Turn on Passcode Lock");
    }

    public static String setPasscodeText() {
        return LuckgramStrings.get("设置第二密码", "Set second passcode");
    }

    public static String changePasscodeText() {
        return LuckgramStrings.get("修改第二密码", "Change second passcode");
    }

    public static String enterSecondText() {
        return LuckgramStrings.get("输入第二密码", "Enter the second passcode");
    }

    public static String repeatSecondText() {
        return LuckgramStrings.get("再次输入第二密码", "Repeat the second passcode");
    }

    public static String sameAsMainText() {
        return LuckgramStrings.get("第二密码不能与主密码相同", "The second passcode must differ from the main passcode");
    }

    public static String mismatchText() {
        return LuckgramStrings.get("两次输入的密码不一致", "The passcodes don't match");
    }

    public static String pinRuleText() {
        return LuckgramStrings.get("请输入 4 位数字", "Enter 4 digits");
    }

    public static String visibleHeaderText() {
        return LuckgramStrings.get("第二密码下可见的账号", "Accounts visible with the second passcode");
    }

    public static String visibleInfoText() {
        return LuckgramStrings.get("用第二密码解锁时，只有这些账号会出现在账号列表、快速切换和通知中。至少选一个。", "With the second passcode, only these accounts appear in the account list, the account switcher and notifications. Pick at least one.");
    }

    public static String atLeastOneText() {
        return LuckgramStrings.get("至少选择一个账号", "Pick at least one account");
    }

    public static String turnOffText() {
        return LuckgramStrings.get("关闭双重空间", "Turn off double space");
    }

    public static String enterMainText() {
        return LuckgramStrings.get("输入主密码", "Enter the main passcode");
    }

    public static String wrongMainText() {
        return LuckgramStrings.get("主密码不正确", "Incorrect main passcode");
    }

    public static String savedText() {
        return LuckgramStrings.get("第二密码已设置", "Second passcode set");
    }

    // ---------------------------------------------------------------- state

    public static boolean isPasscodeOn() {
        return !TextUtils.isEmpty(SharedConfig.passcodeHash);
    }

    public static boolean hasSecondPasscode() {
        return !TextUtils.isEmpty(LuckgramSettings.getString(KEY_HASH, null));
    }

    /** Switch on, a second passcode set and the app passcode on. */
    public static boolean isConfigured() {
        return LuckgramSettings.isEnabled(FEATURE_KEY) && hasSecondPasscode() && isPasscodeOn();
    }

    /** The app was unlocked with the second passcode. */
    public static boolean isActive() {
        return isConfigured() && LuckgramSettings.getBool(KEY_ACTIVE, false);
    }

    private static void setActive(boolean active) {
        if (LuckgramSettings.getBool(KEY_ACTIVE, false) != active) {
            LuckgramSettings.setBool(KEY_ACTIVE, active);
        }
    }

    public static Set<Long> visibleUserIds() {
        HashSet<Long> result = new HashSet<>();
        String value = LuckgramSettings.getString(KEY_VISIBLE, null);
        if (TextUtils.isEmpty(value)) {
            return result;
        }
        for (String part : value.split(",")) {
            try {
                result.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    public static void setVisibleUserIds(Set<Long> ids) {
        LuckgramSettings.setString(KEY_VISIBLE, ids.isEmpty() ? null : TextUtils.join(",", ids));
    }

    /** Whether the account must not appear anywhere (lists, switcher, notifications, badge). */
    public static boolean isHidden(int account) {
        if (!isActive()) {
            return false;
        }
        long userId = UserConfig.getInstance(account).getClientUserId();
        return userId != 0 && !visibleUserIds().contains(userId);
    }

    /** First logged-in account that is visible right now, or -1. */
    public static int firstVisibleAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && !isHidden(a)) {
                return a;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- passcode

    public static void setSecondPasscode(String passcode) {
        byte[] salt = new byte[16];
        Utilities.random.nextBytes(salt);
        LuckgramSettings.setString(KEY_SALT, Utilities.bytesToHex(salt));
        LuckgramSettings.setString(KEY_HASH, hash(passcode, salt));
    }

    public static boolean checkSecondPasscode(String passcode) {
        String stored = LuckgramSettings.getString(KEY_HASH, null);
        String saltHex = LuckgramSettings.getString(KEY_SALT, null);
        if (TextUtils.isEmpty(stored) || TextUtils.isEmpty(saltHex)) {
            return false;
        }
        return stored.equals(hash(passcode, Utilities.hexToBytes(saltHex)));
    }

    /**
     * Called by the passcode screen with what the user typed. Returns true when it is the second
     * passcode (the app opens in the second space); otherwise the main-passcode check goes on and
     * a successful main unlock leaves the second space.
     */
    public static boolean onUnlockAttempt(String passcode) {
        if (!isConfigured()) {
            return false;
        }
        if (checkSecondPasscode(passcode)) {
            setActive(true);
            return true;
        }
        return false;
    }

    /** Main passcode accepted. */
    public static void onMainUnlock() {
        setActive(false);
    }

    /** Switches the feature off and forgets the second passcode (after the main passcode was entered). */
    public static void turnOff() {
        LuckgramSettings.remove(KEY_HASH);
        LuckgramSettings.remove(KEY_SALT);
        LuckgramSettings.remove(KEY_VISIBLE);
        LuckgramSettings.remove(KEY_ACTIVE);
        LuckgramSettings.setEnabled(FEATURE_KEY, false);
    }

    /** A logged-out account no longer counts as visible. */
    public static void onLogout(long userId) {
        Set<Long> ids = visibleUserIds();
        if (ids.remove(userId)) {
            setVisibleUserIds(ids);
        }
    }

    /** Same construction as SharedConfig.checkPasscode: SHA-256(salt ‖ passcode ‖ salt). */
    private static String hash(String passcode, byte[] salt) {
        try {
            byte[] passcodeBytes = passcode.getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[32 + passcodeBytes.length];
            System.arraycopy(salt, 0, bytes, 0, 16);
            System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
            System.arraycopy(salt, 0, bytes, passcodeBytes.length + 16, 16);
            return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    public static ArrayList<Integer> filterVisible(ArrayList<Integer> accounts) {
        if (!isActive()) {
            return accounts;
        }
        ArrayList<Integer> result = new ArrayList<>();
        for (int account : accounts) {
            if (!isHidden(account)) {
                result.add(account);
            }
        }
        return result;
    }
}

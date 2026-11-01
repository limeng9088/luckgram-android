package org.telegram.luckgram.accounts;

import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * Luckgram 09 (more accounts): account limit, account order, per-account alias / notifications and
 * the background-connection cap. Same constants and keys as iOS (LuckgramAccounts.swift).
 *
 * <ul>
 *   <li>Order and "recently used" are device-wide lists of user ids ({@code accounts.order},
 *       {@code accounts.recent}); a user id leaves both lists when that account logs out.</li>
 *   <li>Alias and the notification switch are per-account values ({@code *ForAccount}), removed by
 *       {@link LuckgramSettings} when the account logs out.</li>
 * </ul>
 */
public final class LuckgramAccounts {

    private LuckgramAccounts() {
    }

    /** Maximum number of accounts logged in at the same time (spec 09: 20). */
    public static final int MAX_ACCOUNTS = 20;
    /** The settings account list shows this many accounts before it collapses. */
    public static final int COLLAPSED_COUNT = 5;
    /** Accounts that keep the background (push) connection; the rest are woken by push. */
    public static final int BACKGROUND_CONNECTIONS = 5;

    private static final String KEY_ORDER = "accounts.order";
    private static final String KEY_RECENT = "accounts.recent";
    private static final String KEY_ALIAS = "accounts.alias";
    private static final String KEY_NOTIFY = "accounts.notify";

    // ---------------------------------------------------------------- strings

    public static String limitReachedText() {
        return LuckgramStrings.get("最多可登录 " + MAX_ACCOUNTS + " 个账号", "You can log in to at most " + MAX_ACCOUNTS + " accounts");
    }

    public static String showAllText(int count) {
        return LuckgramStrings.get("显示全部 " + count + " 个账号", "Show all " + count + " accounts");
    }

    public static String showLessText() {
        return LuckgramStrings.get("收起", "Show less");
    }

    public static String manageText() {
        return LuckgramStrings.get("管理账号", "Manage accounts");
    }

    public static String manageInfoText() {
        return LuckgramStrings.get("拖动可以调整账号顺序，点按账号可以设置备注名。备注名只保存在本机，用于账号列表和通知标题。",
                "Drag to reorder accounts. Tap an account to set its name. Names are kept on this device only and are used in the account list and notification titles.");
    }

    public static String aliasText() {
        return LuckgramStrings.get("备注名", "Name on this device");
    }

    public static String editAliasText() {
        return LuckgramStrings.get("设置备注名", "Set name");
    }

    public static String clearAliasText() {
        return LuckgramStrings.get("清除备注名", "Remove name");
    }

    public static String aliasHintText() {
        return LuckgramStrings.get("例如：工作", "e.g. Work");
    }

    public static String notificationsText() {
        return LuckgramStrings.get("通知", "Notifications");
    }

    public static String notificationsInfoText() {
        return LuckgramStrings.get("关闭后，这个账号收到新消息时不再弹出通知。", "When off, new messages for this account don't show a notification.");
    }

    public static String notificationsOffText() {
        return LuckgramStrings.get("通知已关闭", "Notifications off");
    }

    public static void showLimitReached() {
        try {
            Toast.makeText(ApplicationLoader.applicationContext, limitReachedText(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------------------- activated accounts

    public static int activatedCount() {
        return UserConfig.getActivatedAccountsCount();
    }

    /** All logged-in accounts in the user's order (new accounts at the end, by login time). */
    public static ArrayList<Integer> orderedAccounts() {
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts.add(a);
            }
        }
        final HashMap<Long, Integer> position = positions(KEY_ORDER);
        Collections.sort(accounts, (o1, o2) -> {
            Integer p1 = position.get(UserConfig.getInstance(o1).getClientUserId());
            Integer p2 = position.get(UserConfig.getInstance(o2).getClientUserId());
            if (p1 != null && p2 != null) {
                return Integer.compare(p1, p2);
            } else if (p1 != null) {
                return -1;
            } else if (p2 != null) {
                return 1;
            }
            return Long.compare(UserConfig.getInstance(o1).loginTime, UserConfig.getInstance(o2).loginTime);
        });
        return accounts;
    }

    /**
     * Saves a new order. {@code accounts} may be a subset (e.g. the settings list without the
     * current account): the listed accounts take the slots they had before, so accounts that are
     * not in the list keep their place.
     */
    public static void setOrder(ArrayList<Integer> accounts) {
        ArrayList<Integer> all = orderedAccounts();
        ArrayList<Integer> slots = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (accounts.contains(all.get(i))) {
                slots.add(i);
            }
        }
        int k = 0;
        for (int i = 0; i < slots.size() && k < accounts.size(); i++) {
            all.set(slots.get(i), accounts.get(k++));
        }
        ArrayList<Long> ids = new ArrayList<>();
        for (int account : all) {
            ids.add(UserConfig.getInstance(account).getClientUserId());
        }
        LuckgramSettings.setString(KEY_ORDER, TextUtils.join(",", ids));
    }

    // ---------------------------------------------------------------- per-account values

    public static String getAlias(int account) {
        String alias = LuckgramSettings.getStringForAccount(account, KEY_ALIAS, null);
        return TextUtils.isEmpty(alias) ? null : alias;
    }

    /** Longest local name (same as iOS). */
    public static final int ALIAS_MAX_LENGTH = 32;

    public static void setAlias(int account, String alias) {
        alias = alias == null ? null : alias.trim();
        if (alias != null && alias.length() > ALIAS_MAX_LENGTH) {
            alias = alias.substring(0, ALIAS_MAX_LENGTH);
        }
        LuckgramSettings.setStringForAccount(account, KEY_ALIAS, TextUtils.isEmpty(alias) ? null : alias);
    }

    /** Alias if set, otherwise the account's first name. */
    public static String label(int account) {
        String alias = getAlias(account);
        if (alias != null) {
            return alias;
        }
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        return user != null ? UserObject.getFirstName(user) : "";
    }

    /** Name shown in account lists: "Alias (Name)" when an alias is set. */
    public static String displayName(int account) {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        String name = user != null ? UserObject.getUserName(user) : "";
        String alias = getAlias(account);
        return alias == null ? name : alias + " (" + name + ")";
    }

    public static boolean isNotificationsEnabled(int account) {
        return LuckgramSettings.getBoolForAccount(account, KEY_NOTIFY, true);
    }

    public static void setNotificationsEnabled(int account, boolean enabled) {
        LuckgramSettings.setBoolForAccount(account, KEY_NOTIFY, enabled);
    }

    /** Prefix for notification titles ("[Work] "), only when more than one account is logged in. */
    public static String notificationPrefix(int account) {
        if (activatedCount() <= 1) {
            return "";
        }
        String label = label(account);
        return TextUtils.isEmpty(label) ? "" : "[" + label + "] ";
    }

    // ---------------------------------------------------------------- background connections

    /** Call when an account becomes the current one. */
    public static void onAccountUsed(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        if (userId == 0) {
            return;
        }
        ArrayList<Long> recent = ids(KEY_RECENT);
        recent.remove(userId);
        recent.add(0, userId);
        while (recent.size() > MAX_ACCOUNTS) {
            recent.remove(recent.size() - 1);
        }
        LuckgramSettings.setString(KEY_RECENT, TextUtils.join(",", recent));
        applyBackgroundConnections();
    }

    /** Removes a logged-out user from the order and recent lists. */
    public static void onLogout(long userId) {
        for (String key : new String[]{KEY_ORDER, KEY_RECENT}) {
            ArrayList<Long> list = ids(key);
            if (list.remove(userId)) {
                LuckgramSettings.setString(key, list.isEmpty() ? null : TextUtils.join(",", list));
            }
        }
    }

    /**
     * Whether the account keeps Telegram's background (push) connection: the current account and
     * the most recently used ones, {@link #BACKGROUND_CONNECTIONS} in total.
     */
    public static boolean keepsBackgroundConnection(int account) {
        if (account == UserConfig.selectedAccount) {
            return true;
        }
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return false;
        }
        ArrayList<Integer> byRecent = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                byRecent.add(a);
            }
        }
        if (byRecent.size() <= BACKGROUND_CONNECTIONS) {
            return true;
        }
        final HashMap<Long, Integer> position = positions(KEY_RECENT);
        final int selected = UserConfig.selectedAccount;
        Collections.sort(byRecent, (o1, o2) -> {
            if (o1 == selected) return -1;
            if (o2 == selected) return 1;
            Integer p1 = position.get(UserConfig.getInstance(o1).getClientUserId());
            Integer p2 = position.get(UserConfig.getInstance(o2).getClientUserId());
            if (p1 != null && p2 != null) {
                return Integer.compare(p1, p2);
            } else if (p1 != null) {
                return -1;
            } else if (p2 != null) {
                return 1;
            }
            return Long.compare(UserConfig.getInstance(o2).loginTime, UserConfig.getInstance(o1).loginTime);
        });
        int index = byRecent.indexOf(account);
        return index >= 0 && index < BACKGROUND_CONNECTIONS;
    }

    /** Re-applies the background-connection cap to every logged-in account. */
    public static void applyBackgroundConnections() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            ConnectionsManager connectionsManager = ConnectionsManager.getInstance(a);
            connectionsManager.setPushConnectionEnabled(connectionsManager.isPushConnectionEnabled());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static ArrayList<Long> ids(String key) {
        ArrayList<Long> result = new ArrayList<>();
        String value = LuckgramSettings.getString(key, null);
        if (TextUtils.isEmpty(value)) {
            return result;
        }
        for (String part : value.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id != 0 && !result.contains(id)) {
                    result.add(id);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    private static HashMap<Long, Integer> positions(String key) {
        ArrayList<Long> list = ids(key);
        HashMap<Long, Integer> map = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            map.put(list.get(i), i);
        }
        return map;
    }
}

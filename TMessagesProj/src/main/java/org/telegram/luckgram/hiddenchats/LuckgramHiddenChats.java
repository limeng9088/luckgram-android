package org.telegram.luckgram.hiddenchats;

import android.os.Bundle;
import android.text.TextUtils;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.PasscodeViewDialog;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Luckgram 12 (hidden chats): chats the user hid from the chat list. Kept per account on this
 * device only ({@code LuckgramSettings}, never synced). Hidden chats appear in no list or folder,
 * are left out of search and the badge, and don't notify unless the user chose otherwise.
 * Same behaviour as iOS {@code LuckgramHiddenChats}.
 */
public final class LuckgramHiddenChats {

    private LuckgramHiddenChats() {
    }

    public static final String FEATURE_KEY = "hidden_chats";

    private static final String KEY_IDS = "hidden_chats.ids";
    private static final String KEY_LOCK = "hidden_chats.lock";
    private static final String KEY_NOTIFY = "hidden_chats.notify";
    private static final String KEY_HINT_SHOWN = "hidden_chats.hint_shown";

    private static final HashSet<Long>[] cache = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];

    // ---------------------------------------------------------------- strings

    public static String titleText() {
        return LuckgramStrings.get("隐藏的聊天", "Hidden chats");
    }

    public static String hideText() {
        return LuckgramStrings.get("隐藏", "Hide");
    }

    public static String unhideText() {
        return LuckgramStrings.get("取消隐藏", "Unhide");
    }

    public static String hiddenToastText() {
        return LuckgramStrings.get("已隐藏", "Hidden");
    }

    public static String undoText() {
        return LuckgramStrings.get("撤销", "Undo");
    }

    public static String unhiddenToastText() {
        return LuckgramStrings.get("已取消隐藏", "Unhidden");
    }

    public static String hintText() {
        return LuckgramStrings.get("在聊天列表下拉可以看到隐藏的聊天", "Pull down the chat list to see hidden chats");
    }

    public static String searchHiddenText() {
        return LuckgramStrings.get("在隐藏的聊天中搜索", "Search in hidden chats");
    }

    public static String searchMatchesText(int count) {
        return LuckgramStrings.get(count + " 个隐藏的聊天匹配", count + " hidden chat(s) match");
    }

    public static String lockText() {
        return LuckgramStrings.get("进入时需要验证", "Require passcode to open");
    }

    public static String lockInfoText() {
        return LuckgramStrings.get("打开隐藏的聊天前要求输入应用密码或使用面容 / 指纹。需要先开启 Telegram 的密码锁。", "Ask for the app passcode or Face ID / fingerprint before opening hidden chats. Telegram's Passcode Lock must be on first.");
    }

    public static String notifyText() {
        return LuckgramStrings.get("隐藏的聊天照常通知", "Notify for hidden chats");
    }

    public static String notifyInfoText() {
        return LuckgramStrings.get("关闭时，隐藏聊天的新消息不弹通知，也不计入角标。", "When off, new messages in hidden chats show no notification and don't count in the badge.");
    }

    public static String openText() {
        return LuckgramStrings.get("打开隐藏的聊天", "Open hidden chats");
    }

    public static String emptyText() {
        return LuckgramStrings.get("长按聊天选择「隐藏」，它就会出现在这里。", "Long-press a chat and choose Hide; it will show up here.");
    }

    // ---------------------------------------------------------------- settings

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(FEATURE_KEY);
    }

    public static boolean isLockEnabled() {
        return LuckgramSettings.getBool(KEY_LOCK, false);
    }

    public static void setLockEnabled(boolean value) {
        LuckgramSettings.setBool(KEY_LOCK, value);
    }

    public static boolean isNotifyEnabled() {
        return LuckgramSettings.getBool(KEY_NOTIFY, false);
    }

    public static void setNotifyEnabled(boolean value) {
        LuckgramSettings.setBool(KEY_NOTIFY, value);
    }

    // ---------------------------------------------------------------- ids

    private static synchronized HashSet<Long> ids(int account) {
        HashSet<Long> set = cache[account];
        if (set == null) {
            set = new HashSet<>();
            String value = LuckgramSettings.getStringForAccount(account, KEY_IDS, null);
            if (!TextUtils.isEmpty(value)) {
                for (String part : value.split(",")) {
                    try {
                        set.add(Long.parseLong(part.trim()));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            cache[account] = set;
        }
        return set;
    }

    private static synchronized void save(int account, HashSet<Long> set) {
        cache[account] = set;
        LuckgramSettings.setStringForAccount(account, KEY_IDS, set.isEmpty() ? null : TextUtils.join(",", set));
    }

    public static boolean hasHidden(int account) {
        return isEnabled() && !ids(account).isEmpty();
    }

    public static boolean isHidden(int account, long dialogId) {
        return isEnabled() && ids(account).contains(dialogId);
    }

    public static void setHidden(int account, long dialogId, boolean hidden) {
        HashSet<Long> set = new HashSet<>(ids(account));
        if (hidden ? !set.add(dialogId) : !set.remove(dialogId)) {
            return;
        }
        save(account, set);
        onChanged(account);
    }

    private static void onChanged(int account) {
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        NotificationsController.getInstance(account).updateBadge();
    }

    /** Shown once, the first time a chat is hidden. */
    public static boolean takeFirstHint() {
        if (LuckgramSettings.getBool(KEY_HINT_SHOWN, false)) {
            return false;
        }
        LuckgramSettings.setBool(KEY_HINT_SHOWN, true);
        return true;
    }

    // ---------------------------------------------------------------- lists

    /** The list without hidden chats; the same list when nothing in it is hidden. */
    public static ArrayList<TLRPC.Dialog> filter(int account, ArrayList<TLRPC.Dialog> dialogs) {
        if (dialogs == null || dialogs.isEmpty() || !hasHidden(account)) {
            return dialogs;
        }
        HashSet<Long> set = ids(account);
        boolean any = false;
        for (int i = 0, n = dialogs.size(); i < n; i++) {
            TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog != null && set.contains(dialog.id)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return dialogs;
        }
        ArrayList<TLRPC.Dialog> result = new ArrayList<>(dialogs.size());
        for (int i = 0, n = dialogs.size(); i < n; i++) {
            TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog == null || !set.contains(dialog.id)) {
                result.add(dialog);
            }
        }
        return result;
    }

    /** The hidden chats, in chat-list order. */
    public static ArrayList<TLRPC.Dialog> hiddenDialogs(int account) {
        ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        if (!hasHidden(account)) {
            return result;
        }
        HashSet<Long> set = ids(account);
        List<TLRPC.Dialog> all = MessagesController.getInstance(account).getAllDialogs();
        for (int i = 0, n = all.size(); i < n; i++) {
            TLRPC.Dialog dialog = all.get(i);
            if (dialog != null && set.contains(dialog.id)) {
                result.add(dialog);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- notifications

    /** New messages in a hidden chat don't notify and don't count, unless the user chose otherwise. */
    public static boolean suppressNotifications(int account, long dialogId) {
        return isHidden(account, dialogId) && !isNotifyEnabled();
    }

    // ---------------------------------------------------------------- opening the list

    /** Whether the pull-down gesture on the chat list opens the hidden chats. */
    public static boolean canPullDown(int account) {
        return hasHidden(account);
    }

    public static boolean isPasscodeOn() {
        return !TextUtils.isEmpty(SharedConfig.passcodeHash);
    }

    /** Opens the hidden chats list, after the app passcode when the user asked for that. */
    public static void open(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (isLockEnabled() && isPasscodeOn()) {
            PasscodeViewDialog dialog = new PasscodeViewDialog(fragment.getParentActivity());
            dialog.passcodeView.luckgramVerifyOnly = true;
            dialog.passcodeView.setDelegate(view -> present(fragment));
            dialog.show();
            dialog.passcodeView.onShow(true, true, -1, -1, null, null);
        } else {
            present(fragment);
        }
    }

    private static void present(BaseFragment fragment) {
        Bundle args = new Bundle();
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_LUCKGRAM_HIDDEN);
        fragment.presentFragment(new DialogsActivity(args));
    }
}

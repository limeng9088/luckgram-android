/*
 * Luckgram — select all messages from one sender (spec 14).
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.selectall;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Pages through {@code messages.search(from_id = sender)} in one chat and hands every page to the
 * chat screen, which adds the messages to its multi-select state (no 100 cap while this runs).
 */
public class SelectAllFromSender {

    /** Feature switch, registered in {@link org.telegram.luckgram.LuckgramFeatures} (default on). */
    public static final String SETTING_KEY = "select_all_from_sender";
    /** Server page size for messages.search. */
    static final int PAGE_SIZE = 100;
    /** Batch size for forward/delete requests (Telegram accepts at most 100 ids per call). */
    public static final int BATCH_SIZE = 100;

    public interface Delegate {
        /** One page of the sender's messages, newest first. UI thread. */
        void onPage(ArrayList<MessageObject> page);

        /** Loading stopped: {@code complete} = every message was loaded. UI thread. */
        void onFinished(boolean complete, int total);
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(SETTING_KEY);
    }

    private final int account;
    private final long dialogId;
    private final long senderId;
    private final int topMsgId;
    private final Delegate delegate;
    private final HashSet<Integer> seen = new HashSet<>();
    private int requestId;
    private boolean cancelled;
    private int loaded;

    /**
     * @param senderId sender to match, or 0 for every message (broadcast channels)
     * @param topMsgId topic / thread root, or 0 for the whole chat
     */
    public SelectAllFromSender(int account, long dialogId, long senderId, int topMsgId, Delegate delegate) {
        this.account = account;
        this.dialogId = dialogId;
        this.senderId = senderId;
        this.topMsgId = topMsgId;
        this.delegate = delegate;
    }

    public void start() {
        loadPage(0);
    }

    public void cancel() {
        cancelled = true;
        if (requestId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
            requestId = 0;
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    private void loadPage(int offsetId) {
        if (cancelled) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = mc.getInputPeer(dialogId);
        if (senderId != 0) {
            req.from_id = mc.getInputPeer(senderId);
        }
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        req.offset_id = offsetId;
        req.limit = PAGE_SIZE;
        if (topMsgId != 0) {
            req.top_msg_id = topMsgId;
            req.flags |= 2;
        }
        if (req.peer == null || senderId != 0 && req.from_id == null) {
            delegate.onFinished(false, loaded);
            return;
        }
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            requestId = 0;
            if (cancelled) {
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                delegate.onFinished(false, loaded);
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            mc.putUsers(res.users, false);
            mc.putChats(res.chats, false);
            ArrayList<MessageObject> page = new ArrayList<>();
            int minId = Integer.MAX_VALUE;
            for (int i = 0; i < res.messages.size(); i++) {
                TLRPC.Message m = res.messages.get(i);
                if (m == null || m instanceof TLRPC.TL_messageEmpty) {
                    continue;
                }
                minId = Math.min(minId, m.id);
                if (!seen.add(m.id) || m.action != null && !(m.action instanceof TLRPC.TL_messageActionEmpty)) {
                    continue;
                }
                page.add(new MessageObject(account, m, false, false));
            }
            loaded += page.size();
            if (!page.isEmpty()) {
                delegate.onPage(page);
            }
            boolean more = res.messages.size() >= PAGE_SIZE && minId != Integer.MAX_VALUE && minId > 1;
            if (more && !cancelled) {
                loadPage(minId);
            } else {
                delegate.onFinished(true, loaded);
            }
        }));
    }

    // region strings (Chinese + English, same wording as iOS)

    /** Menu title: "全选 Noah 的消息"; in private chats "全选对方的消息" / "全选我的消息". */
    public static String menuTitle(int account, long dialogId, long senderId) {
        long self = UserConfig.getInstance(account).getClientUserId();
        if (dialogId > 0) {
            return senderId == self
                ? LuckgramStrings.get("全选我的消息", "Select All My Messages")
                : LuckgramStrings.get("全选对方的消息", "Select All Their Messages");
        }
        String name;
        MessagesController mc = MessagesController.getInstance(account);
        if (senderId > 0) {
            TLRPC.User user = mc.getUser(senderId);
            name = senderId == self ? LuckgramStrings.get("我", "Me") : UserObject.getFirstName(user);
        } else {
            TLRPC.Chat chat = mc.getChat(-senderId);
            name = chat != null ? chat.title : "";
        }
        return LuckgramStrings.get("全选 " + name + " 的消息", "Select All from " + name);
    }

    public static String loadingText() {
        return LuckgramStrings.get("正在加载全部消息，按返回可取消", "Loading all messages… Press back to cancel");
    }

    public static String doneText(int count) {
        return LuckgramStrings.get("已选中全部 " + count + " 条", "All " + count + " messages selected");
    }

    public static String failedText(int count) {
        return LuckgramStrings.get("加载中断，已选中 " + count + " 条", "Loading stopped, " + count + " selected");
    }

    public static String cantDeleteText(int count) {
        return LuckgramStrings.get("有 " + count + " 条消息你没有权限删除，已跳过", count + " messages can't be deleted by you and were skipped");
    }

    // endregion
}

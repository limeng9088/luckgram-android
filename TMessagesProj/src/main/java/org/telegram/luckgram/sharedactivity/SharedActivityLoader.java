/*
 * Luckgram — shared-groups activity on user profiles (spec 16).
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.sharedactivity;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Loads what a user recently wrote in the groups you share with them, using only Telegram:
 * {@code messages.getCommonChats} (all pages), then {@code messages.search(from_id = user)} with
 * {@link SharedActivityLogic#PER_GROUP} messages per group, at most
 * {@link SharedActivityLogic#MAX_PARALLEL} requests at a time, sitting out FLOOD_WAIT. Results are
 * merged newest first, capped at {@link SharedActivityLogic#MAX_ITEMS} and cached for 10 minutes.
 */
public final class SharedActivityLoader {

    /** Feature switch, registered in {@link org.telegram.luckgram.LuckgramFeatures} (default on). */
    public static final String SETTING_KEY = "shared_activity";

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(SETTING_KEY);
    }

    public static final class Result {
        public final ArrayList<MessageObject> messages;
        public final int groupCount;
        final long loadedAt;

        Result(ArrayList<MessageObject> messages, int groupCount, long loadedAt) {
            this.messages = messages;
            this.groupCount = groupCount;
            this.loadedAt = loadedAt;
        }
    }

    public interface Callback {
        /** UI thread. {@code result} is never null; on failure it is empty. */
        void onLoaded(Result result);
    }

    private static final HashMap<String, Result> cache = new HashMap<>();

    private static String key(int account, long userId) {
        return account + ":" + userId;
    }

    /** Cached result younger than 10 minutes, or null. UI thread. */
    public static Result cached(int account, long userId) {
        Result r = cache.get(key(account, userId));
        return r != null && SharedActivityLogic.isFresh(r.loadedAt, System.currentTimeMillis()) ? r : null;
    }

    private static final class Item implements SharedActivityLogic.Dated {
        final TLRPC.Message message;

        Item(TLRPC.Message message) {
            this.message = message;
        }

        @Override
        public int date() {
            return message.date;
        }

        @Override
        public long sortId() {
            return message.id;
        }
    }

    private final int account;
    private final long userId;
    private final int classGuid;
    private final Callback callback;
    private final ArrayList<TLRPC.Chat> groups = new ArrayList<>();
    private final ArrayDeque<TLRPC.Chat> queue = new ArrayDeque<>();
    private final ArrayList<ArrayList<Item>> perGroup = new ArrayList<>();
    private final ArrayList<Integer> requestIds = new ArrayList<>();
    private int running;
    private boolean cancelled;
    private boolean finished;

    public SharedActivityLoader(int account, long userId, int classGuid, Callback callback) {
        this.account = account;
        this.userId = userId;
        this.classGuid = classGuid;
        this.callback = callback;
    }

    public void load() {
        loadCommonChats(0);
    }

    public void cancel() {
        cancelled = true;
        ConnectionsManager cm = ConnectionsManager.getInstance(account);
        for (int id : requestIds) {
            cm.cancelRequest(id, true);
        }
        requestIds.clear();
    }

    private void loadCommonChats(long maxId) {
        if (cancelled) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_getCommonChats req = new TLRPC.TL_messages_getCommonChats();
        req.user_id = mc.getInputUser(userId);
        if (req.user_id == null || req.user_id instanceof TLRPC.TL_inputUserEmpty) {
            finish();
            return;
        }
        req.max_id = maxId;
        req.limit = 100;
        send(req, (response, error) -> {
            if (error != null) {
                int wait = SharedActivityLogic.floodWaitSeconds(error.text);
                if (wait >= 0 && wait <= SharedActivityLogic.MAX_FLOOD_WAIT_SECONDS) {
                    AndroidUtilities.runOnUIThread(() -> loadCommonChats(maxId), wait * 1000L + 500);
                } else {
                    startSearches();
                }
                return;
            }
            TLRPC.messages_Chats res = (TLRPC.messages_Chats) response;
            mc.putChats(res.chats, false);
            for (TLRPC.Chat chat : res.chats) {
                // Only groups you are in yourself (spec 16 requirement 6).
                if (chat != null && !ChatObject.isNotInChat(chat) && !(ChatObject.isChannel(chat) && !chat.megagroup)) {
                    groups.add(chat);
                }
            }
            if (res.chats.size() >= req.limit && !res.chats.isEmpty()) {
                loadCommonChats(res.chats.get(res.chats.size() - 1).id);
            } else {
                startSearches();
            }
        });
    }

    private void startSearches() {
        queue.addAll(groups);
        pump();
    }

    private void pump() {
        if (cancelled || finished) {
            return;
        }
        while (running < SharedActivityLogic.MAX_PARALLEL && !queue.isEmpty()) {
            search(queue.poll());
        }
        if (running == 0 && queue.isEmpty()) {
            finish();
        }
    }

    private void search(TLRPC.Chat chat) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = mc.getInputPeer(-chat.id);
        req.from_id = mc.getInputPeer(userId);
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        req.limit = SharedActivityLogic.PER_GROUP;
        if (req.peer == null || req.from_id == null) {
            return;
        }
        running++;
        send(req, (response, error) -> {
            running--;
            if (error != null) {
                int wait = SharedActivityLogic.floodWaitSeconds(error.text);
                if (wait >= 0 && wait <= SharedActivityLogic.MAX_FLOOD_WAIT_SECONDS) {
                    // Sit the wait out, then try this group again (spec 16 requirement 5).
                    running++;
                    AndroidUtilities.runOnUIThread(() -> {
                        running--;
                        queue.addFirst(chat);
                        pump();
                    }, wait * 1000L + 500);
                    return;
                }
            } else if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                mc.putUsers(res.users, false);
                mc.putChats(res.chats, false);
                ArrayList<Item> items = new ArrayList<>();
                for (TLRPC.Message m : res.messages) {
                    if (m != null && !(m instanceof TLRPC.TL_messageEmpty) && (m.action == null || m.action instanceof TLRPC.TL_messageActionEmpty)) {
                        items.add(new Item(m));
                    }
                }
                perGroup.add(items);
            }
            pump();
        });
    }

    private void finish() {
        if (finished || cancelled) {
            return;
        }
        finished = true;
        ArrayList<Item> merged = SharedActivityLogic.newestFirst(perGroup, SharedActivityLogic.MAX_ITEMS);
        ArrayList<MessageObject> messages = new ArrayList<>(merged.size());
        for (Item item : merged) {
            messages.add(new MessageObject(account, item.message, false, true));
        }
        Result result = new Result(messages, groups.size(), System.currentTimeMillis());
        cache.put(key(account, userId), result);
        callback.onLoaded(result);
    }

    private void send(TLObject req, ResponseOnUi onResponse) {
        int[] holder = new int[1];
        holder[0] = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            requestIds.remove((Integer) holder[0]);
            if (!cancelled) {
                onResponse.run(response, error);
            }
        }));
        requestIds.add(holder[0]);
        if (classGuid != 0) {
            ConnectionsManager.getInstance(account).bindRequestToGuid(holder[0], classGuid);
        }
    }

    private interface ResponseOnUi {
        void run(TLObject response, TLRPC.TL_error error);
    }

    // region strings (Chinese + English, same wording as iOS)

    public static String tabTitle() {
        return LuckgramStrings.get("动态", "Activity");
    }

    public static String header(int groupCount) {
        return SharedActivityLogic.header(groupCount, LuckgramStrings.isChinese());
    }

    public static String refreshText() {
        return LuckgramStrings.get("刷新", "Refresh");
    }

    public static String emptyNoGroups() {
        return LuckgramStrings.get("你们没有共同群组", "You have no groups in common");
    }

    public static String emptyNoMessages() {
        return LuckgramStrings.get("最近没有在共同群组里发过消息", "No recent messages in your groups in common");
    }

    // endregion
}

package org.telegram.luckgram.account;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Silent S6 device ↔ account link, shared by People Nearby (25) and Square (26). Nothing about
 * "linking an account" is shown: {@code POST /v1/link/start} → {@code /start <nonce>} is sent to
 * the bot from the current account → poll {@code GET /v1/link?nonce=} until the server has it →
 * the bot chat is deleted locally. Callers show their own short "connecting" / failure notice.
 * Extracted from w-1136's Square code so both features keep one copy.
 * <p>
 * specs#209: each Telegram account has its own device token ({@link AccountTokens}), so the link
 * state here is kept per Telegram user id too; switching accounts never reuses another account's
 * link.
 */
public final class SilentLink {

    private SilentLink() {
    }

    public interface Listener {
        /** Main thread. {@code linked} is false when the link couldn't be made (offline, timeout, ...). */
        void onResult(boolean linked);
    }

    /** How long the silent link may take before giving up (nonce TTL is 10 minutes; be quicker). */
    private static final long LINK_TIMEOUT_MS = 90_000;

    /** Main thread only. Keyed by Telegram user id. */
    private static final HashMap<Long, Boolean> linked = new HashMap<>();
    private static final HashSet<Long> linking = new HashSet<>();
    private static final HashMap<Long, ArrayList<Listener>> waiting = new HashMap<>();
    /** Why the last silent link of a user failed, when there is something to tell (limit_reached). */
    private static final HashMap<Long, String> failure = new HashMap<>();

    private static long uid(int account) {
        UserConfig uc = UserConfig.getInstance(account);
        return uc.isClientActivated() ? uc.getClientUserId() : 0;
    }

    /** Last known state of the selected account: TRUE / FALSE, or null when not checked yet. */
    public static Boolean cached() {
        return cached(UserConfig.selectedAccount);
    }

    public static Boolean cached(int account) {
        return linked.get(uid(account));
    }

    /** Forget every account's cached state (logout, delete my data). */
    public static void clearCache() {
        linked.clear();
        failure.clear();
    }

    /** Forget {@code account}'s cached state (unlink, a {@code not_linked} answer). */
    public static void clearCache(int account) {
        long u = uid(account);
        linked.remove(u);
        failure.remove(u);
    }

    /**
     * The text to show when the last silent link of {@code account} failed for a reason the user
     * can act on (the Telegram account already has the most devices), else null.
     */
    public static String failureMessage(int account) {
        return failure.get(uid(account));
    }

    /** {@code GET /v1/link} for the selected account; {@code done} runs either way. */
    public static void refresh(Runnable done) {
        refresh(UserConfig.selectedAccount, done);
    }

    /** {@code GET /v1/link} as {@code account}; {@code done} runs either way (state in {@link #cached(int)}). */
    public static void refresh(int account, Runnable done) {
        final long u = uid(account);
        LuckgramAI.getInstance().requestJson(account, "GET", "/v1/link", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                linked.put(u, result.optBoolean("linked", false));
                if (done != null) done.run();
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (done != null) done.run();
            }
        });
    }

    /**
     * Calls {@code listener} once this device is linked to {@code account}'s Telegram user, linking
     * it in the background first if needed. Concurrent calls share one attempt.
     */
    public static void ensure(int account, Listener listener) {
        final long u = uid(account);
        if (u == 0) {
            AndroidUtilities.runOnUIThread(() -> listener.onResult(false));
            return;
        }
        if (Boolean.TRUE.equals(linked.get(u))) {
            listener.onResult(true);
            return;
        }
        ArrayList<Listener> list = waiting.get(u);
        if (list == null) waiting.put(u, list = new ArrayList<>());
        list.add(listener);
        if (!linking.add(u)) return;
        failure.remove(u);
        refresh(account, () -> {
            if (Boolean.TRUE.equals(linked.get(u))) {
                finish(u, true);
            } else {
                start(account, u);
            }
        });
    }

    private static void start(int account, long u) {
        LuckgramAI.getInstance().requestJson(account, "POST", "/v1/link/start", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                AccountLogic.LinkStart st = AccountLogic.parseStart(result);
                String bot = AccountLogic.botUsername(st.url);
                if (bot == null || st.nonce.isEmpty()) {
                    finish(u, false);
                    return;
                }
                long expiresAt = Math.min(st.expiresAtMs > 0 ? st.expiresAtMs : Long.MAX_VALUE, System.currentTimeMillis() + LINK_TIMEOUT_MS);
                MessagesController.getInstance(account).getUserNameResolver().resolve(bot, peerId -> {
                    if (peerId == null || peerId == 0) {
                        finish(u, false);
                        return;
                    }
                    try {
                        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                            AccountLogic.startCommand(st.nonce), peerId, null, null, null, false, null, null, null, true, 0, 0, null, false));
                    } catch (Throwable e) {
                        FileLog.e(e);
                        finish(u, false);
                        return;
                    }
                    poll(account, u, st.nonce, peerId, expiresAt);
                });
            }

            @Override
            public void onError(LuckgramAIError error) {
                if ("already_linked".equals(error.code)) {
                    linked.put(u, true);
                    finish(u, true);
                } else {
                    finish(u, false);
                }
            }
        });
    }

    private static void poll(int account, long u, String nonce, long botPeerId, long expiresAt) {
        if (!AccountLogic.shouldPoll(null, System.currentTimeMillis(), expiresAt)) {
            finish(u, false);
            return;
        }
        LuckgramAI.getInstance().requestJson(account, "GET", "/v1/link?nonce=" + nonce, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                AccountLogic.LinkStatus s = AccountLogic.parseLink(result);
                if (s.linked || "linked".equals(s.request)) {
                    linked.put(u, true);
                    clearBotChat(account, botPeerId);
                    finish(u, true);
                } else if (AccountLogic.shouldPoll(s.request, System.currentTimeMillis(), expiresAt)) {
                    AndroidUtilities.runOnUIThread(() -> poll(account, u, nonce, botPeerId, expiresAt), AccountLogic.POLL_INTERVAL_MS);
                } else {
                    if ("limit_reached".equals(s.request)) {
                        // specs#209: tell the user instead of failing silently.
                        failure.put(u, new AccountLogic.Texts(org.telegram.luckgram.LuckgramStrings.isChinese()).linkResult(s.request, s.maxDevices));
                        clearBotChat(account, botPeerId);
                    }
                    finish(u, false);
                }
            }

            @Override
            public void onError(LuckgramAIError error) {
                // Offline for a moment: keep trying until the deadline.
                AndroidUtilities.runOnUIThread(() -> poll(account, u, nonce, botPeerId, expiresAt), AccountLogic.POLL_INTERVAL_MS);
            }
        });
    }

    /**
     * The bot conversation is removed from the chat list so no trace of the link is left (spec 26
     * item 2): the whole dialog is deleted, not just its history, which would leave a
     * "History was cleared" row at the top (acceptance P1). Done now and again a little later:
     * the server records the link before the bot replies, so the reply may land after the first
     * delete (review P2).
     */
    private static void clearBotChat(int account, long botPeerId) {
        Runnable clear = () -> {
            try {
                MessagesController.getInstance(account).deleteDialog(botPeerId, 0);
                // The late reply may already have raised a notification.
                org.telegram.messenger.NotificationsController.getInstance(account).removeNotificationsForDialog(botPeerId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        };
        clear.run();
        for (long d : AccountLogic.BOT_CLEANUP_DELAYS_MS) {
            AndroidUtilities.runOnUIThread(clear, d);
        }
        // The reply can come later still (review P2): watch the bot chat for a while and delete
        // whatever arrives there right away.
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        final NotificationCenter.NotificationCenterDelegate[] watcher = {null};
        watcher[0] = (id, acc, args) -> {
            if (id == NotificationCenter.didReceiveNewMessages && args.length > 0 && args[0] instanceof Long
                && (Long) args[0] == botPeerId && !(args.length > 2 && Boolean.TRUE.equals(args[2]))) {
                // Not inside the notification dispatch itself.
                AndroidUtilities.runOnUIThread(clear);
            }
        };
        nc.addObserver(watcher[0], NotificationCenter.didReceiveNewMessages);
        AndroidUtilities.runOnUIThread(() -> nc.removeObserver(watcher[0], NotificationCenter.didReceiveNewMessages), AccountLogic.BOT_WATCH_MS);
    }

    private static void finish(long u, boolean ok) {
        linking.remove(u);
        ArrayList<Listener> pending = waiting.remove(u);
        ArrayList<Listener> list = pending != null ? pending : new ArrayList<>();
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : list) l.onResult(ok);
        });
    }
}

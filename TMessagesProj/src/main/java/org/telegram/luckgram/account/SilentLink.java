package org.telegram.luckgram.account;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;

import java.util.ArrayList;

/**
 * Silent S6 device ↔ account link, shared by People Nearby (25) and Square (26). Nothing about
 * "linking an account" is shown: {@code POST /v1/link/start} → {@code /start <nonce>} is sent to
 * the bot from the current account → poll {@code GET /v1/link?nonce=} until the server has it →
 * the bot chat is deleted locally. Callers show their own short "connecting" / failure notice.
 * Extracted from w-1136's Square code so both features keep one copy.
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

    private static Boolean linked;
    private static boolean linking;
    private static final ArrayList<Listener> waiting = new ArrayList<>();

    /** Last known state: TRUE / FALSE, or null when not checked yet. */
    public static Boolean cached() {
        return linked;
    }

    /** Forget the cached state (unlink, logout, a {@code not_linked} answer). */
    public static void clearCache() {
        linked = null;
    }

    /** {@code GET /v1/link}; {@code done} runs either way (state in {@link #cached()}). */
    public static void refresh(Runnable done) {
        LuckgramAI.getInstance().requestJson("GET", "/v1/link", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                linked = result.optBoolean("linked", false);
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
        if (Boolean.TRUE.equals(linked)) {
            listener.onResult(true);
            return;
        }
        waiting.add(listener);
        if (linking) return;
        linking = true;
        refresh(() -> {
            if (Boolean.TRUE.equals(linked)) {
                finish(true);
            } else {
                start(account);
            }
        });
    }

    private static void start(int account) {
        LuckgramAI.getInstance().requestJson("POST", "/v1/link/start", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                AccountLogic.LinkStart st = AccountLogic.parseStart(result);
                String bot = AccountLogic.botUsername(st.url);
                if (bot == null || st.nonce.isEmpty()) {
                    finish(false);
                    return;
                }
                long expiresAt = Math.min(st.expiresAtMs > 0 ? st.expiresAtMs : Long.MAX_VALUE, System.currentTimeMillis() + LINK_TIMEOUT_MS);
                MessagesController.getInstance(account).getUserNameResolver().resolve(bot, peerId -> {
                    if (peerId == null || peerId == 0) {
                        finish(false);
                        return;
                    }
                    try {
                        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                            AccountLogic.startCommand(st.nonce), peerId, null, null, null, false, null, null, null, true, 0, 0, null, false));
                    } catch (Throwable e) {
                        FileLog.e(e);
                        finish(false);
                        return;
                    }
                    poll(account, st.nonce, peerId, expiresAt);
                });
            }

            @Override
            public void onError(LuckgramAIError error) {
                if ("already_linked".equals(error.code)) {
                    linked = true;
                    finish(true);
                } else {
                    finish(false);
                }
            }
        });
    }

    private static void poll(int account, String nonce, long botPeerId, long expiresAt) {
        if (!AccountLogic.shouldPoll(null, System.currentTimeMillis(), expiresAt)) {
            finish(false);
            return;
        }
        LuckgramAI.getInstance().requestJson("GET", "/v1/link?nonce=" + nonce, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                AccountLogic.LinkStatus s = AccountLogic.parseLink(result);
                if (s.linked || "linked".equals(s.request)) {
                    linked = true;
                    clearBotChat(account, botPeerId);
                    finish(true);
                } else if (AccountLogic.shouldPoll(s.request, System.currentTimeMillis(), expiresAt)) {
                    AndroidUtilities.runOnUIThread(() -> poll(account, nonce, botPeerId, expiresAt), AccountLogic.POLL_INTERVAL_MS);
                } else {
                    finish(false);
                }
            }

            @Override
            public void onError(LuckgramAIError error) {
                // Offline for a moment: keep trying until the deadline.
                AndroidUtilities.runOnUIThread(() -> poll(account, nonce, botPeerId, expiresAt), AccountLogic.POLL_INTERVAL_MS);
            }
        });
    }

    /**
     * The bot conversation is cleaned up so the user isn't bothered by it, now and again a little
     * later: the server records the link before the bot replies, so the reply may land after the
     * first delete (review P2).
     */
    private static void clearBotChat(int account, long botPeerId) {
        Runnable clear = () -> {
            try {
                MessagesController.getInstance(account).deleteDialog(botPeerId, 1);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        };
        clear.run();
        for (long d : AccountLogic.BOT_CLEANUP_DELAYS_MS) {
            AndroidUtilities.runOnUIThread(clear, d);
        }
    }

    private static void finish(boolean ok) {
        linking = false;
        ArrayList<Listener> list = new ArrayList<>(waiting);
        waiting.clear();
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : list) l.onResult(ok);
        });
    }
}

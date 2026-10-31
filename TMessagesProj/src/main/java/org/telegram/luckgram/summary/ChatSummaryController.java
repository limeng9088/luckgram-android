package org.telegram.luckgram.summary;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * spec 19: loads a range of a chat's history straight from Telegram (nothing is changed or marked
 * read), summarizes it with {@code task=summary} (long ranges in parts, then merged) and answers
 * questions about it with {@code task=chat}. Callbacks on the UI thread.
 */
public final class ChatSummaryController {

    public interface Callback<T> {
        void onResult(T result);

        void onError(LuckgramAIError error);
    }

    /** Telegram returns at most 100 messages per request. */
    private static final int PAGE = 100;

    private final int account;
    private final long dialogId;
    private final long threadId;
    private boolean cancelled;
    private LuckgramAI.Request request;
    private int fetchRequestId;

    /** Messages of the current range, oldest first. */
    private final ArrayList<ChatSummaryLogic.Line> lines = new ArrayList<>();
    private final HashSet<Integer> ids = new HashSet<>();

    public ChatSummaryController(int account, long dialogId, long threadId) {
        this.account = account;
        this.dialogId = dialogId;
        this.threadId = threadId;
    }

    public HashSet<Integer> messageIds() {
        return ids;
    }

    public void cancel() {
        cancelled = true;
        if (request != null) request.cancel();
        if (fetchRequestId != 0) ConnectionsManager.getInstance(account).cancelRequest(fetchRequestId, true);
    }

    private static int maxChars() {
        return (int) Math.min(Integer.MAX_VALUE, LuckgramSettings.remoteLong("ai.max_input_chars", 60000));
    }

    // ---------------------------------------------------------------- history

    /** Loads the last {@code count} messages (newest backwards), then calls {@code done} with how many were found. */
    public void load(int count, Callback<Integer> done) {
        cancelled = false;
        lines.clear();
        ids.clear();
        loadPage(0, count, new ArrayList<>(), new HashMap<>(), done);
    }

    private void loadPage(int offsetId, int remaining, ArrayList<TLRPC.Message> acc, HashMap<Long, String> names, Callback<Integer> done) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.InputPeer peer = mc.getInputPeer(dialogId);
        TLObject req;
        int limit = Math.min(PAGE, remaining);
        if (threadId != 0) {
            TLRPC.TL_messages_getReplies r = new TLRPC.TL_messages_getReplies();
            r.peer = peer;
            r.msg_id = (int) threadId;
            r.offset_id = offsetId;
            r.limit = limit;
            req = r;
        } else {
            TLRPC.TL_messages_getHistory r = new TLRPC.TL_messages_getHistory();
            r.peer = peer;
            r.offset_id = offsetId;
            r.limit = limit;
            req = r;
        }
        fetchRequestId = ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            fetchRequestId = 0;
            if (cancelled) return;
            if (!(res instanceof TLRPC.messages_Messages)) {
                if (acc.isEmpty()) {
                    done.onError(new LuckgramAIError(LuckgramAIError.NETWORK_ERROR, 0));
                } else {
                    finishLoad(acc, names, done);
                }
                return;
            }
            TLRPC.messages_Messages m = (TLRPC.messages_Messages) res;
            mc.putUsers(m.users, false);
            mc.putChats(m.chats, false);
            for (TLRPC.User u : m.users) names.put(u.id, UserObject.getUserName(u));
            for (TLRPC.Chat c : m.chats) names.put(-c.id, c.title);
            int minId = Integer.MAX_VALUE;
            for (TLRPC.Message msg : m.messages) {
                minId = Math.min(minId, msg.id);
                acc.add(msg);
            }
            int left = remaining - m.messages.size();
            if (m.messages.isEmpty() || left <= 0 || m.messages.size() < limit) {
                finishLoad(acc, names, done);
            } else {
                loadPage(minId, left, acc, names, done);
            }
        }));
    }

    private void finishLoad(ArrayList<TLRPC.Message> acc, HashMap<Long, String> names, Callback<Integer> done) {
        Collections.sort(acc, (a, b) -> Integer.compare(a.id, b.id));
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        for (TLRPC.Message msg : acc) {
            if (msg instanceof TLRPC.TL_messageService || ids.contains(msg.id)) continue;
            String text = describe(msg);
            if (TextUtils.isEmpty(text)) continue;
            String sender = senderName(msg, names);
            lines.add(new ChatSummaryLogic.Line(msg.id, sender, fmt.format(new Date(msg.date * 1000L)), text));
            ids.add(msg.id);
        }
        done.onResult(lines.size());
    }

    private String senderName(TLRPC.Message msg, HashMap<Long, String> names) {
        long id = 0;
        if (msg.from_id instanceof TLRPC.TL_peerUser) id = msg.from_id.user_id;
        else if (msg.from_id instanceof TLRPC.TL_peerChannel) id = -msg.from_id.channel_id;
        else if (msg.from_id instanceof TLRPC.TL_peerChat) id = -msg.from_id.chat_id;
        else if (msg.peer_id instanceof TLRPC.TL_peerChannel) id = -msg.peer_id.channel_id; // channel posts
        else if (msg.peer_id instanceof TLRPC.TL_peerUser) id = msg.out ? 0 : msg.peer_id.user_id;
        if (msg.out && id == 0) {
            return LuckgramStrings.get("我", "Me");
        }
        String name = names.get(id);
        if (name == null && id > 0) {
            TLRPC.User u = MessagesController.getInstance(account).getUser(id);
            if (u != null) name = UserObject.getUserName(u);
        }
        return TextUtils.isEmpty(name) ? "?" : name;
    }

    /** Text of a message, with media as a placeholder followed by its caption. */
    static String describe(TLRPC.Message msg) {
        String text = msg.message == null ? "" : msg.message.trim();
        String media = null;
        TLRPC.MessageMedia m = msg.media;
        if (m instanceof TLRPC.TL_messageMediaPhoto) {
            media = LuckgramStrings.get("[图片]", "[Photo]");
        } else if (m instanceof TLRPC.TL_messageMediaDocument && m.document != null) {
            TLRPC.Document d = m.document;
            boolean voice = false, video = false, round = false, sticker = false, gif = false, music = false;
            String fileName = null;
            for (TLRPC.DocumentAttribute a : d.attributes) {
                if (a instanceof TLRPC.TL_documentAttributeAudio) {
                    if (a.voice) voice = true; else music = true;
                } else if (a instanceof TLRPC.TL_documentAttributeVideo) {
                    if (a.round_message) round = true; else video = true;
                } else if (a instanceof TLRPC.TL_documentAttributeSticker || a instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                    sticker = true;
                } else if (a instanceof TLRPC.TL_documentAttributeAnimated) {
                    gif = true;
                } else if (a instanceof TLRPC.TL_documentAttributeFilename) {
                    fileName = a.file_name;
                }
            }
            if (voice) media = LuckgramStrings.get("[语音]", "[Voice message]");
            else if (round) media = LuckgramStrings.get("[视频消息]", "[Video message]");
            else if (sticker) media = LuckgramStrings.get("[贴纸]", "[Sticker]");
            else if (gif) media = "[GIF]";
            else if (video) media = LuckgramStrings.get("[视频]", "[Video]");
            else if (music) media = LuckgramStrings.get("[音乐]", "[Music]");
            else media = LuckgramStrings.get("[文件", "[File") + (fileName != null ? " " + fileName : "") + "]";
        } else if (m instanceof TLRPC.TL_messageMediaGeo || m instanceof TLRPC.TL_messageMediaGeoLive || m instanceof TLRPC.TL_messageMediaVenue) {
            media = LuckgramStrings.get("[位置]", "[Location]");
        } else if (m instanceof TLRPC.TL_messageMediaContact) {
            media = LuckgramStrings.get("[联系人]", "[Contact]");
        } else if (m instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.TL_messageMediaPoll p = (TLRPC.TL_messageMediaPoll) m;
            String q = p.poll != null && p.poll.question != null ? p.poll.question.text : "";
            media = LuckgramStrings.get("[投票] ", "[Poll] ") + q;
        }
        if (media == null) return text;
        return text.isEmpty() ? media : media + " " + text;
    }

    // ---------------------------------------------------------------- AI

    /** Summarizes the loaded range; the result is the raw model text (bullets with [#ID] references). */
    public void summarize(Context context, Callback<String> callback) {
        List<String> parts = ChatSummaryLogic.split(lines, maxChars());
        if (parts.isEmpty()) {
            callback.onResult("");
            return;
        }
        summarizePart(context, parts, 0, new ArrayList<>(), callback);
    }

    private void summarizePart(Context context, List<String> parts, int index, ArrayList<String> partial, Callback<String> callback) {
        if (index >= parts.size()) {
            if (partial.size() == 1) {
                callback.onResult(partial.get(0));
                return;
            }
            // spec 19 需求 5: merge the part summaries
            StringBuilder merge = new StringBuilder(ChatSummaryLogic.header(true));
            for (int i = 0; i < partial.size(); i++) {
                merge.append("Part ").append(i + 1).append(":\n").append(partial.get(i)).append("\n\n");
            }
            String text = merge.toString();
            if (text.length() > maxChars()) text = text.substring(0, maxChars());
            chat(context, LuckgramAI.TASK_SUMMARY, text, callback);
            return;
        }
        chat(context, LuckgramAI.TASK_SUMMARY, parts.get(index), new Callback<String>() {
            @Override
            public void onResult(String result) {
                partial.add(result);
                summarizePart(context, parts, index + 1, partial, callback);
            }

            @Override
            public void onError(LuckgramAIError error) {
                callback.onError(error);
            }
        });
    }

    /** Answers a question about the loaded range (the most recent messages that fit). */
    public void ask(Context context, String question, Callback<String> callback) {
        String tail = "\n\nQuestion: " + question + "\nAnswer briefly, in the language of the question. " + ChatSummaryLogic.CITE_RULE;
        String head = "Chat messages (oldest first):\n";
        int budget = Math.max(1000, maxChars() - tail.length() - head.length() - 100);
        ArrayList<String> picked = new ArrayList<>();
        int size = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String s = lines.get(i).format();
            if (s.length() > ChatSummaryLogic.MAX_LINE_CHARS) s = s.substring(0, ChatSummaryLogic.MAX_LINE_CHARS) + "…";
            if (size + s.length() + 1 > budget) break;
            picked.add(0, s);
            size += s.length() + 1;
        }
        chat(context, LuckgramAI.TASK_CHAT, head + TextUtils.join("\n", picked) + tail, callback);
    }

    private void chat(Context context, String task, String content, Callback<String> callback) {
        if (cancelled) return;
        request = LuckgramAI.getInstance().chat(context, task, Collections.singletonList(LuckgramAI.Message.user(content)), false, new LuckgramAI.ChatListener() {
            @Override
            public void onDelta(String delta, String text) {
            }

            @Override
            public void onComplete(String text) {
                request = null;
                if (!cancelled) callback.onResult(text == null ? "" : text.trim());
            }

            @Override
            public void onError(LuckgramAIError error) {
                request = null;
                if (!cancelled) callback.onError(error);
            }
        });
    }
}

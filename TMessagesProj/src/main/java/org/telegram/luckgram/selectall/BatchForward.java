/*
 * Luckgram — forward a large selection in batches with progress and retry (spec 14, #62).
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.selectall;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards more than 100 messages to one chat as a sequence of {@code messages.forwardMessages}
 * requests ({@link SelectAllFromSender#BATCH_SIZE} ids each, oldest first), reporting progress per
 * batch. A failed batch stops the sequence and can be retried; the forwarded copies arrive through
 * the returned updates, so nothing is shown twice. Same behaviour as iOS {@code luckgramBatchForward}.
 */
public final class BatchForward {

    public interface Listener {
        /** {@code done} batches finished out of {@code total}. UI thread. */
        void onProgress(int done, int total);

        /** Batch {@code index} (0-based) failed; {@link #retry()} resends it. UI thread. */
        void onBatchFailed(int index, int total, TLRPC.TL_error error);

        /** Every batch went through. UI thread. */
        void onDone(int messageCount);
    }

    private final int account;
    private final long toDialogId;
    private final long topMsgId;
    private final boolean notify;
    private final Listener listener;
    private final ArrayList<ArrayList<MessageObject>> batches = new ArrayList<>();
    private final int messageCount;
    private int index;
    private int requestId;
    private boolean cancelled;

    public BatchForward(int account, ArrayList<MessageObject> messages, long toDialogId, long topMsgId, boolean notify, Listener listener) {
        this.account = account;
        this.toDialogId = toDialogId;
        this.topMsgId = topMsgId;
        this.notify = notify;
        this.listener = listener;
        // Group by source chat (ids are only unique per chat), then oldest first, then 100 per request.
        LinkedHashMap<Long, ArrayList<MessageObject>> bySource = new LinkedHashMap<>();
        for (MessageObject m : messages) {
            if (m == null || m.getId() <= 0) {
                continue;
            }
            ArrayList<MessageObject> list = bySource.get(m.getDialogId());
            if (list == null) {
                bySource.put(m.getDialogId(), list = new ArrayList<>());
            }
            list.add(m);
        }
        int count = 0;
        for (Map.Entry<Long, ArrayList<MessageObject>> e : bySource.entrySet()) {
            ArrayList<MessageObject> list = e.getValue();
            Collections.sort(list, (a, b) -> Integer.compare(a.getId(), b.getId()));
            for (int start = 0; start < list.size(); start += SelectAllFromSender.BATCH_SIZE) {
                batches.add(new ArrayList<>(list.subList(start, Math.min(list.size(), start + SelectAllFromSender.BATCH_SIZE))));
            }
            count += list.size();
        }
        messageCount = count;
    }

    public int batchCount() {
        return batches.size();
    }

    public void start() {
        listener.onProgress(0, batches.size());
        sendNext();
    }

    /** Resends the batch that failed and continues with the rest. */
    public void retry() {
        if (!cancelled && requestId == 0) {
            sendNext();
        }
    }

    public void cancel() {
        cancelled = true;
        if (requestId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
            requestId = 0;
        }
    }

    private void sendNext() {
        if (cancelled) {
            return;
        }
        if (index >= batches.size()) {
            listener.onDone(messageCount);
            return;
        }
        ArrayList<MessageObject> batch = batches.get(index);
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
        req.to_peer = mc.getInputPeer(toDialogId);
        req.from_peer = mc.getInputPeer(batch.get(0).getDialogId());
        req.silent = !notify || MessagesController.getNotificationsSettings(account).getBoolean("silent_" + toDialogId, false);
        if (topMsgId != 0) {
            req.top_msg_id = (int) topMsgId;
            req.flags |= 512;
        }
        for (MessageObject m : batch) {
            req.id.add(m.getId());
            req.random_id.add(Utilities.random.nextLong());
        }
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            requestId = 0;
            if (cancelled) {
                return;
            }
            if (error != null || !(response instanceof TLRPC.Updates)) {
                listener.onBatchFailed(index, batches.size(), error);
                return;
            }
            mc.processUpdates((TLRPC.Updates) response, false);
            index++;
            listener.onProgress(index, batches.size());
            sendNext();
        }), ConnectionsManager.RequestFlagInvokeAfter);
    }

    /** Text of {@code messages} oldest first, for "copy in chronological order". */
    public static CharSequence chronologicalText(ArrayList<MessageObject> messages) {
        ArrayList<MessageObject> sorted = new ArrayList<>(messages);
        Collections.sort(sorted, (a, b) -> {
            int c = Integer.compare(a.messageOwner.date, b.messageOwner.date);
            return c != 0 ? c : Integer.compare(a.getId(), b.getId());
        });
        StringBuilder sb = new StringBuilder();
        for (MessageObject m : sorted) {
            CharSequence text = m.messageOwner == null ? null : (m.caption != null ? m.caption : m.messageText);
            if (text == null || text.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(text);
        }
        return sb;
    }
}

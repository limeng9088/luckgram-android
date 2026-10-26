/*
 * Luckgram: posts a finished call recording (spec 04 §4–5).
 *
 * 1. Sends the Ogg/Opus file as an audio document (not a voice message) to Saved Messages or the
 *    chat chosen in settings, named "通话 <name> <date time>.ogg" with the caption
 *    "与 <name> 的通话 · <duration>".
 * 2. If the AI transcript switch is on and the user has agreed to AI processing, transcribes the
 *    file and summarises it via the Luckgram AI gateway, then replies to the audio message with
 *    the summary followed by the full transcript.
 * 3. Deletes the temporary file once the upload and the transcription no longer need it.
 */
package org.telegram.luckgram.callrec;

import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class CallRecUploader implements NotificationCenter.NotificationCenterDelegate {

    /** Telegram's text message limit is 4096; leave room for headers. */
    private static final int MAX_TEXT_CHUNK = 4000;

    private static final Set<String> pendingPaths = Collections.synchronizedSet(new HashSet<>());

    static boolean isPending(File file) {
        return pendingPaths.contains(file.getAbsolutePath());
    }

    private final int account;
    private final TLRPC.User peer;
    private final File file;
    private final long durationMs;
    private final long startedAtWallMs;

    private long dialogId;
    private String fileName;

    private boolean uploadDone;
    private boolean aiDone;
    private MessageObject sentAudio;
    private String aiReply;

    static void upload(int account, TLRPC.User peer, File file, long durationMs, long startedAtWallMs) {
        new CallRecUploader(account, peer, file, durationMs, startedAtWallMs).start();
    }

    private CallRecUploader(int account, TLRPC.User peer, File file, long durationMs, long startedAtWallMs) {
        this.account = account;
        this.peer = peer;
        this.file = file;
        this.durationMs = durationMs;
        this.startedAtWallMs = startedAtWallMs;
    }

    private void start() {
        final UserConfig userConfig = UserConfig.getInstance(account);
        if (!userConfig.isClientActivated()) {
            // Logged out while the call was ending: nothing to save into, drop the file.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return;
        }
        pendingPaths.add(file.getAbsolutePath());

        final long selfId = userConfig.getClientUserId();
        long target = CallRecSettings.getTargetDialogId(account);
        if (target != 0 && !canPostTo(target)) {
            target = 0;
        }
        dialogId = target == 0 ? selfId : target;

        final String peerName = peerName();
        final String dateTime = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date(startedAtWallMs));
        fileName = sanitizeFileName(CallRecStrings.fileTitle(peerName, dateTime)) + ".ogg";
        final int durationSec = (int) Math.max(1, Math.round(durationMs / 1000.0));
        final String caption = CallRecStrings.caption(peerName, AndroidUtilities.formatShortDuration(durationSec));

        final TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = 0;
        document.date = ConnectionsManager.getInstance(account).getCurrentTime();
        document.file_reference = new byte[0];
        document.mime_type = "audio/ogg";
        document.size = file.length();
        document.dc_id = 0;
        final TLRPC.TL_documentAttributeFilename nameAttr = new TLRPC.TL_documentAttributeFilename();
        nameAttr.file_name = fileName;
        document.attributes.add(nameAttr);
        final TLRPC.TL_documentAttributeAudio audioAttr = new TLRPC.TL_documentAttributeAudio();
        audioAttr.duration = durationSec;
        audioAttr.voice = false;
        audioAttr.title = fileName.substring(0, fileName.length() - 4);
        audioAttr.performer = peerName;
        audioAttr.flags |= 1 | 2;
        document.attributes.add(audioAttr);

        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messageReceivedByServer);

        final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                document, null, file.getAbsolutePath(), dialogId, null, null, caption, null, null, null,
                true, 0, 0, 0, null, null, false);
        AccountInstance.getInstance(account).getSendMessagesHelper().sendMessage(params);

        startAi();
    }

    private String peerName() {
        String name = UserObject.getUserName(peer);
        return TextUtils.isEmpty(name) ? "?" : name;
    }

    private boolean canPostTo(long did) {
        final MessagesController mc = MessagesController.getInstance(account);
        if (DialogObject.isUserDialog(did)) {
            return mc.getUser(did) != null;
        }
        if (DialogObject.isChatDialog(did)) {
            final TLRPC.Chat chat = mc.getChat(-did);
            return chat != null && !ChatObject.isNotInChat(chat) && ChatObject.canSendDocument(chat);
        }
        return false;
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
    }

    // ---------------------------------------------------------------- upload

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id != NotificationCenter.messageReceivedByServer || acc != account || args.length < 4) {
            return;
        }
        if (!(args[2] instanceof TLRPC.Message) || !(args[3] instanceof Long) || (Long) args[3] != dialogId) {
            return;
        }
        final TLRPC.Message message = (TLRPC.Message) args[2];
        if (!isOurDocument(message)) {
            return;
        }
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messageReceivedByServer);
        sentAudio = new MessageObject(account, message, false, false);
        uploadDone = true;
        toast(CallRecStrings.savedToast(targetName()));
        maybeFinish();
    }

    private boolean isOurDocument(TLRPC.Message message) {
        if (message.media == null || message.media.document == null) {
            return false;
        }
        for (TLRPC.DocumentAttribute attribute : message.media.document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeFilename && fileName.equals(attribute.file_name)) {
                return true;
            }
        }
        return false;
    }

    private String targetName() {
        if (dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return CallRecStrings.savedMessages();
        }
        final MessagesController mc = MessagesController.getInstance(account);
        if (DialogObject.isUserDialog(dialogId)) {
            return UserObject.getUserName(mc.getUser(dialogId));
        }
        final TLRPC.Chat chat = mc.getChat(-dialogId);
        return chat != null ? chat.title : "";
    }

    // ---------------------------------------------------------------- AI

    private void startAi() {
        final CallRecAi ai = CallRecAi.get();
        if (!CallRecSettings.isAiTranscriptEnabled() || ai == null || !ai.hasConsent()) {
            aiDone = true;
            return;
        }
        if (file.length() > CallRecAi.MAX_TRANSCRIBE_BYTES) {
            finishAi(CallRecStrings.tooLongForTranscript());
            return;
        }
        ai.transcribe(file, (text, error) -> {
            if (error != null || text == null) {
                finishAi(CallRecStrings.transcriptFailed(error != null ? error : "?"));
                return;
            }
            final String transcript = text.trim();
            if (transcript.isEmpty()) {
                finishAi(CallRecStrings.transcriptHeader() + "\n" + CallRecStrings.transcriptEmpty());
                return;
            }
            ai.summarize(transcript, (summary, summaryError) -> {
                final StringBuilder sb = new StringBuilder();
                if (summaryError == null && summary != null && !summary.trim().isEmpty()) {
                    sb.append(CallRecStrings.summaryHeader()).append('\n').append(summary.trim()).append("\n\n");
                }
                sb.append(CallRecStrings.transcriptHeader()).append('\n').append(transcript);
                finishAi(sb.toString());
            });
        });
    }

    private void finishAi(String reply) {
        aiReply = reply;
        aiDone = true;
        maybeFinish();
    }

    private void maybeFinish() {
        if (!uploadDone || !aiDone) {
            return;
        }
        // Neither the upload nor the transcription needs the file any more (spec 04 §5).
        pendingPaths.remove(file.getAbsolutePath());
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        if (aiReply != null && sentAudio != null) {
            for (String chunk : CallRecText.split(aiReply, MAX_TEXT_CHUNK, CallRecStrings.transcriptContinued())) {
                final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                        chunk, dialogId, sentAudio, null, null, false, null, null, null, true, 0, 0, null, false);
                AccountInstance.getInstance(account).getSendMessagesHelper().sendMessage(params);
            }
        }
    }

    private static void toast(String text) {
        try {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}

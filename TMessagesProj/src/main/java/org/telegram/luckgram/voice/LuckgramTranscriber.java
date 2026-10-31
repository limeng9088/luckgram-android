package org.telegram.luckgram.voice;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.text.TextUtils;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramFeatures;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;

/**
 * spec 17: free transcription of voice messages and round videos for non-Premium accounts, through
 * the Luckgram AI gateway ({@link LuckgramAI#transcribe}). Premium accounts keep using Telegram's own
 * transcription and never reach this class.
 *
 * <p>The result is written to the message's {@code voiceTranscription} and saved with
 * {@link MessagesStorage#updateMessageVoiceTranscription}, the same local cache Telegram uses for
 * its own transcriptions, so opening the chat again shows it without a new request (and it is
 * cleared with the account's data on logout). A failure is kept in memory only and shown in the
 * bubble as "转写失败 · 重试"; tapping the transcribe button again retries.
 */
public final class LuckgramTranscriber {

    private LuckgramTranscriber() {
    }

    /** Messages being transcribed, keyed by {@link #key}. */
    private static final HashMap<String, Job> running = new HashMap<>();
    /** Messages whose last attempt failed (not persisted). */
    private static final HashSet<String> failed = new HashSet<>();

    /** True when non-Premium transcription should go through Luckgram for this account. */
    public static boolean isAvailable(int account) {
        return !UserConfig.getInstance(account).isPremium() && LuckgramSettings.isEnabled(LuckgramFeatures.FREE_TRANSCRIBE);
    }

    public static boolean handles(MessageObject messageObject) {
        return messageObject != null && messageObject.messageOwner != null && (messageObject.isVoice() || messageObject.isRoundVideo())
            && isAvailable(messageObject.currentAccount);
    }

    static String key(MessageObject m) {
        return m.currentAccount + "_" + m.getDialogId() + "_" + m.getId();
    }

    public static boolean isTranscribing(MessageObject m) {
        return m != null && running.containsKey(key(m));
    }

    public static boolean isFailed(MessageObject m) {
        return m != null && m.messageOwner != null && failed.contains(key(m));
    }

    public static String failureText() {
        return LuckgramStrings.get("转写失败 · 重试", "Transcription failed · Retry");
    }

    /**
     * Starts (or retries) a transcription. {@code context} is used for the one-time AI consent
     * dialog (spec 00); if the user declines, nothing is sent and the button just stops loading.
     */
    public static void start(Context context, MessageObject m) {
        if (m == null || m.messageOwner == null || isTranscribing(m)) {
            return;
        }
        String k = key(m);
        if (failed.remove(k)) {
            m.messageOwner.voiceTranscription = null;
            m.messageOwner.voiceTranscriptionFinal = false;
        }
        Job job = new Job(context, m);
        running.put(k, job);
        job.begin();
    }

    /** Collapses the transcription (no request). */
    public static void close(MessageObject m) {
        if (m == null || m.messageOwner == null) {
            return;
        }
        String k = key(m);
        Job job = running.remove(k);
        if (job != null) {
            job.cancel();
        }
        if (failed.remove(k)) {
            // the failure text was never saved; drop it so the next tap starts over
            m.messageOwner.voiceTranscription = null;
            m.messageOwner.voiceTranscriptionFinal = false;
        }
        m.messageOwner.voiceTranscriptionOpen = false;
        MessagesStorage.getInstance(m.currentAccount).updateMessageVoiceTranscriptionOpen(m.getDialogId(), m.getId(), m.messageOwner);
        notifyUpdate(m, false);
    }

    private static void notifyUpdate(MessageObject m, boolean open) {
        NotificationCenter.getInstance(m.currentAccount).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, m, null, null, (Boolean) open, (Boolean) true);
    }

    private static final class Job implements NotificationCenter.NotificationCenterDelegate {
        final Context context;
        final MessageObject m;
        final String key;
        String fileName;
        boolean cancelled;
        LuckgramAI.Request request;

        Job(Context context, MessageObject m) {
            this.context = context;
            this.m = m;
            this.key = key(m);
        }

        void begin() {
            notifyUpdate(m, m.messageOwner.voiceTranscriptionOpen);
            TLRPC.Document document = m.getDocument();
            if (document == null) {
                fail(new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
                return;
            }
            File file = FileLoader.getInstance(m.currentAccount).getPathToMessage(m.messageOwner);
            if (file != null && file.exists() && file.length() > 0) {
                read(file);
                return;
            }
            if (!TextUtils.isEmpty(m.messageOwner.attachPath)) {
                File local = new File(m.messageOwner.attachPath);
                if (local.exists() && local.length() > 0) {
                    read(local);
                    return;
                }
            }
            // Not downloaded yet: download it first, as playing would.
            fileName = FileLoader.getAttachFileName(document);
            NotificationCenter nc = NotificationCenter.getInstance(m.currentAccount);
            nc.addObserver(this, NotificationCenter.fileLoaded);
            nc.addObserver(this, NotificationCenter.fileLoadFailed);
            FileLoader.getInstance(m.currentAccount).loadFile(document, m, FileLoader.PRIORITY_HIGH, 0);
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (fileName == null || args.length == 0 || !fileName.equals(args[0])) {
                return;
            }
            removeObservers();
            if (cancelled) {
                return;
            }
            if (id == NotificationCenter.fileLoaded && args.length > 1 && args[1] instanceof File) {
                read((File) args[1]);
            } else if (id == NotificationCenter.fileLoaded) {
                read(FileLoader.getInstance(m.currentAccount).getPathToMessage(m.messageOwner));
            } else {
                fail(new LuckgramAIError(LuckgramAIError.NETWORK_ERROR, 0));
            }
        }

        private void removeObservers() {
            NotificationCenter nc = NotificationCenter.getInstance(m.currentAccount);
            nc.removeObserver(this, NotificationCenter.fileLoaded);
            nc.removeObserver(this, NotificationCenter.fileLoadFailed);
        }

        void read(File file) {
            final boolean round = m.isRoundVideo();
            Utilities.globalQueue.postRunnable(() -> {
                byte[] audio = null;
                String mime;
                try {
                    if (round) {
                        audio = extractAudioTrack(file);
                        mime = "audio/mp4";
                    } else {
                        audio = readAll(file);
                        String docMime = m.getDocument() != null ? m.getDocument().mime_type : null;
                        mime = docMime != null && docMime.startsWith("audio/") ? docMime : "audio/ogg";
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    mime = null;
                }
                final byte[] data = audio;
                final String finalMime = mime;
                AndroidUtilities.runOnUIThread(() -> {
                    if (cancelled) {
                        return;
                    }
                    if (data == null || data.length == 0 || finalMime == null) {
                        fail(new LuckgramAIError(LuckgramAIError.INVALID_REQUEST, 0));
                        return;
                    }
                    upload(data, finalMime);
                });
            });
        }

        void upload(byte[] data, String mime) {
            request = LuckgramAI.getInstance().transcribe(context, data, mime, null, new LuckgramAI.Callback<LuckgramAI.Transcription>() {
                @Override
                public void onResult(LuckgramAI.Transcription t) {
                    if (cancelled) {
                        return;
                    }
                    String text = t.text == null ? "" : t.text.trim();
                    if (t.partial && t.error != null) {
                        text += "\n\n" + LuckgramStrings.get("（后面的部分未转写：", "(The rest was not transcribed: ")
                            + t.error.getLocalizedMessage() + LuckgramStrings.get("）", ")");
                    }
                    succeed(text);
                }

                @Override
                public void onError(LuckgramAIError error) {
                    if (cancelled) {
                        return;
                    }
                    fail(error);
                }
            });
        }

        void succeed(String text) {
            running.remove(key);
            failed.remove(key);
            TLRPC.Message owner = m.messageOwner;
            owner.voiceTranscription = text;
            owner.voiceTranscriptionFinal = true;
            owner.voiceTranscriptionOpen = true;
            owner.voiceTranscriptionId = 0;
            org.telegram.ui.Components.TranscribeButton.openVideoTranscription(m);
            MessagesStorage.getInstance(m.currentAccount).updateMessageVoiceTranscription(m.getDialogId(), m.getId(), text, owner);
            NotificationCenter.getInstance(m.currentAccount).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, m, (Long) 0L, text, (Boolean) true, (Boolean) true);
        }

        void fail(LuckgramAIError error) {
            running.remove(key);
            removeObservers();
            if (error != null && (LuckgramAIError.CONSENT_DENIED.equals(error.code) || LuckgramAIError.CANCELLED.equals(error.code))) {
                // the user declined the AI consent dialog: just stop loading
                notifyUpdate(m, m.messageOwner.voiceTranscriptionOpen && m.messageOwner.voiceTranscription != null);
                return;
            }
            if (error != null) {
                FileLog.d("luckgram transcribe failed: " + error.code);
            }
            failed.add(key);
            // shown via MessageObject.getVoiceTranscription(); never saved to the database
            TLRPC.Message owner = m.messageOwner;
            owner.voiceTranscription = "";
            owner.voiceTranscriptionFinal = true;
            owner.voiceTranscriptionOpen = true;
            org.telegram.ui.Components.TranscribeButton.openVideoTranscription(m);
            notifyUpdate(m, true);
        }

        void cancel() {
            cancelled = true;
            removeObservers();
            if (request != null) {
                request.cancel();
            }
        }
    }

    static byte[] readAll(File file) throws IOException {
        long len = file.length();
        if (len <= 0 || len > Integer.MAX_VALUE) {
            throw new IOException("bad length " + len);
        }
        byte[] out = new byte[(int) len];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            if (off != out.length) {
                throw new IOException("short read");
            }
        }
        return out;
    }

    /** Copies the AAC track of a round video into an audio-only MP4 (no re-encoding). */
    static byte[] extractAudioTrack(File video) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        File out = new File(ApplicationLoader.applicationContext.getCacheDir(), "lg_transcribe_" + System.nanoTime() + ".m4a");
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(video.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                throw new IOException("no audio track");
            }
            extractor.selectTrack(track);
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dst = muxer.addTrack(format);
            muxer.start();
            int size = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 256 * 1024;
            ByteBuffer buffer = ByteBuffer.allocate(Math.max(size, 64 * 1024));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                info.offset = 0;
                info.size = extractor.readSampleData(buffer, 0);
                if (info.size < 0) {
                    break;
                }
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(dst, buffer, info);
                extractor.advance();
            }
            muxer.stop();
            return readAll(out);
        } finally {
            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Exception ignore) {
            }
            extractor.release();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }
}

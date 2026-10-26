/*
 * Luckgram: records one-on-one calls (spec 04).
 *
 * Taps the two audio paths the call already uses (WebRtcAudioRecord = our microphone,
 * WebRtcAudioTrack = the other side's decoded audio), mixes them to mono 48 kHz and encodes
 * Ogg/Opus into a private temporary file. When the recording stops (button, hang-up, the other
 * side hanging up, network failure, the call turning into a group call or the service dying),
 * whatever has been recorded is finalised and handed to {@link CallRecUploader}.
 *
 * Driven from VoIPService (state changes / destroy) and from the record button in VoIPFragment.
 * All public methods must be called on the UI thread.
 */
package org.telegram.luckgram.callrec;

import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;
import org.webrtc.voiceengine.WebRtcAudioRecord;
import org.webrtc.voiceengine.WebRtcAudioTrack;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CallRecorder {

    private static final String TEMP_DIR = "luckgram_callrec";
    /** Recordings shorter than this are dropped (accidental double tap). */
    private static final long MIN_DURATION_MS = 1000;

    private static CallRecorder instance;

    public static CallRecorder getInstance() {
        if (instance == null) {
            instance = new CallRecorder();
        }
        return instance;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LuckgramCallRec");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    // Session state (UI thread).
    private Session session;
    /** Call for which auto-record has already fired, so stopping by hand is not undone. */
    private long autoHandledCallId;
    private boolean cleanedUp;

    /** Per-recording state; audio fields only touched on the worker thread. */
    private static final class Session {
        final long callId;
        final int account;
        final TLRPC.User peer;
        final File file;
        final long startedAtElapsed;
        final long startedAtWallMs;
        final CallRecMixer mixer = new CallRecMixer();
        CallRecOpusEncoder encoder;
        volatile boolean stopped;
        boolean writeFailed;

        Session(long callId, int account, TLRPC.User peer, File file) {
            this.callId = callId;
            this.account = account;
            this.peer = peer;
            this.file = file;
            this.startedAtElapsed = SystemClock.elapsedRealtime();
            this.startedAtWallMs = System.currentTimeMillis();
        }
    }

    private CallRecorder() {
    }

    // ---------------------------------------------------------------- public API (UI thread)

    /** Whether the record button belongs on this call: a one-on-one call, not a group call / stream. */
    public static boolean isEligible(@Nullable VoIPService service) {
        return service != null
                && service.privateCall != null
                && service.groupCall == null
                && !service.isConference()
                && service.getUser() != null;
    }

    public boolean isRecording() {
        return session != null;
    }

    public long getElapsedMs() {
        return session == null ? 0 : SystemClock.elapsedRealtime() - session.startedAtElapsed;
    }

    public void addListener(Runnable listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Record button. */
    public void toggle(VoIPService service) {
        if (session != null) {
            stop(true);
        } else if (!CallRecSettings.isEnabled()) {
            toast(CallRecStrings.enableFirst());
        } else if (isEligible(service) && service.getCallState() == VoIPService.STATE_ESTABLISHED) {
            start(service);
        }
    }

    /** Hook: VoIPService.dispatchStateChanged. */
    public static void onCallStateChanged(VoIPService service, int state) {
        AndroidUtilities.runOnUIThread(() -> getInstance().handleState(service, state));
    }

    /** Hook: VoIPService.onDestroy — save whatever was recorded. */
    public static void onCallServiceDestroyed() {
        AndroidUtilities.runOnUIThread(() -> {
            CallRecorder r = getInstance();
            if (r.session != null) {
                r.stop(true);
            }
        });
    }

    // ---------------------------------------------------------------- internals

    private void handleState(VoIPService service, int state) {
        cleanupStaleFilesOnce();
        if (session != null) {
            boolean sameCall = service != null && service.getCallID() == session.callId;
            if (state == VoIPService.STATE_HANGING_UP || state == VoIPService.STATE_ENDED
                    || state == VoIPService.STATE_FAILED || state == VoIPService.STATE_BUSY
                    || !sameCall || !isEligible(service)) {
                stop(true);
            }
            return;
        }
        if (state == VoIPService.STATE_ESTABLISHED && isEligible(service)
                && CallRecSettings.isEnabled() && CallRecSettings.isAutoRecord()
                && autoHandledCallId != service.getCallID()) {
            autoHandledCallId = service.getCallID();
            start(service);
        }
    }

    private void start(VoIPService service) {
        if (session != null) {
            return;
        }
        final File dir = getTempDir();
        if (dir == null) {
            toast(CallRecStrings.startFailed());
            return;
        }
        final File file = new File(dir, "call_" + System.currentTimeMillis() + ".ogg");
        final Session s = new Session(service.getCallID(), service.getAccount(), service.getUser(), file);
        session = s;
        autoHandledCallId = service.getCallID();

        worker.execute(() -> {
            s.encoder = CallRecOpusEncoder.create(file);
            if (s.encoder == null) {
                FileLog.e("luckgram callrec: cannot create encoder");
                AndroidUtilities.runOnUIThread(() -> {
                    if (session == s) {
                        detachTaps();
                        session = null;
                        notifyListeners();
                        toast(CallRecStrings.startFailed());
                    }
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                });
            }
        });

        WebRtcAudioRecord.setOnAudioSamplesReady(samples -> {
            if (s.stopped) {
                return;
            }
            final byte[] data = samples.getData();
            final int rate = samples.getSampleRate();
            final int channels = samples.getChannelCount();
            // While muted the other side hears nothing, so the recording gets silence too.
            final VoIPService current = VoIPService.getSharedInstance();
            final boolean muted = current == null || current.isMicMute();
            worker.execute(() -> {
                if (s.stopped || s.encoder == null) {
                    return;
                }
                s.mixer.pushNear(data, data.length, rate, channels, muted);
                drain(s);
            });
        });
        WebRtcAudioTrack.setPlayoutSamplesCallback((data, length, sampleRate, channels) -> {
            if (s.stopped) {
                return;
            }
            worker.execute(() -> {
                if (s.stopped || s.encoder == null) {
                    return;
                }
                s.mixer.pushFar(data, length, sampleRate, channels);
                drain(s);
            });
        });
        notifyListeners();
    }

    private static void drain(Session s) {
        if (s.writeFailed) {
            return;
        }
        s.mixer.drain(frame -> {
            if (!s.writeFailed && !s.encoder.writeFrame(frame)) {
                s.writeFailed = true;
                FileLog.e("luckgram callrec: writeFrame failed");
            }
        });
    }

    /** Stops the current recording; when {@code save} the file is uploaded, otherwise deleted. */
    private void stop(boolean save) {
        final Session s = session;
        if (s == null) {
            return;
        }
        session = null;
        detachTaps();
        notifyListeners();
        worker.execute(() -> {
            // Runs after every block already queued by the taps, so nothing recorded is lost.
            s.stopped = true;
            boolean ok = false;
            long durationMs = 0;
            if (s.encoder != null) {
                short[] tail = new short[CallRecMixer.FRAME];
                int tailCount = s.mixer.flush(frame -> {
                    if (!s.writeFailed && !s.encoder.writeFrame(frame)) {
                        s.writeFailed = true;
                    }
                }, tail);
                ok = s.encoder.finish(tail, tailCount) && !s.writeFailed;
                durationMs = s.encoder.getDurationMs();
            }
            final boolean keep = save && ok && durationMs >= MIN_DURATION_MS && s.file.length() > 0;
            if (!keep) {
                //noinspection ResultOfMethodCallIgnored
                s.file.delete();
                return;
            }
            final long finalDurationMs = durationMs;
            AndroidUtilities.runOnUIThread(() -> CallRecUploader.upload(s.account, s.peer, s.file, finalDurationMs, s.startedAtWallMs));
        });
    }

    private static void detachTaps() {
        WebRtcAudioRecord.setOnAudioSamplesReady(null);
        WebRtcAudioTrack.setPlayoutSamplesCallback(null);
    }

    private void notifyListeners() {
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    }

    @Nullable
    static File getTempDir() {
        // Not under Telegram's media cache: SendMessagesHelper moves files from there into the
        // permanent cache after upload, and we want to delete ours instead.
        File dir = new File(ApplicationLoader.getFilesDirFixed(), TEMP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    /**
     * Removes temporary recordings left over from a previous process (killed during upload, or
     * an upload that never completed). Runs once per process, on the first call event.
     */
    private void cleanupStaleFilesOnce() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        worker.execute(() -> {
            File dir = new File(ApplicationLoader.getFilesDirFixed(), TEMP_DIR);
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (!CallRecUploader.isPending(f) && now - f.lastModified() > 60_000) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        });
    }

    private static void toast(String text) {
        try {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}

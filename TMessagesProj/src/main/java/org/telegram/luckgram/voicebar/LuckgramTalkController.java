package org.telegram.luckgram.voicebar;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * specs#145: drives one 「按住 说话」 hold: starts Telegram's voice recorder under its own guid (so the
 * regular recording UI of the input view stays out of it), shows {@link LuckgramTalkOverlay}, and on
 * release sends, cancels or transcribes the recording.
 */
public class LuckgramTalkController implements NotificationCenter.NotificationCenterDelegate {

    public interface Host {
        Activity activity();

        /** False (after showing its own hint / permission request) when recording cannot start now. */
        boolean prepareToRecord();

        /** Starts {@link MediaController#startRecording} for this chat under {@code guid}. */
        void startRecording(int guid);

        /** Recording is on / off: typing status, the chat's record overlay. */
        void onRecordingChanged(boolean recording);

        void onVoiceSent();

        void onTranscribing(boolean transcribing);

        void onTranscribed(String text);

        /** {@code text} is already localized. */
        void showHint(String text);
    }

    private final Host host;
    private final int account;
    private final int guid = ConnectionsManager.generateClassGuid();

    private LuckgramTalkOverlay overlay;
    private boolean holding;
    private boolean started;
    private double peakAmplitude;
    private int zone;
    private long downTime;
    private boolean awaitingText;
    private boolean converting;
    private LuckgramAI.Request request;
    private boolean destroyed;

    public LuckgramTalkController(Host host, int account) {
        this.host = host;
        this.account = account;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.recordStarted);
        nc.addObserver(this, NotificationCenter.recordStartError);
        nc.addObserver(this, NotificationCenter.recordStopped);
        nc.addObserver(this, NotificationCenter.recordProgressChanged);
        nc.addObserver(this, NotificationCenter.audioDidSent);
        nc.addObserver(this, NotificationCenter.audioRecordTooShort);
    }

    public int guid() {
        return guid;
    }

    public boolean isHolding() {
        return holding;
    }

    public boolean isBusy() {
        return holding || awaitingText || request != null;
    }

    /** Touch events of the 「按住 说话」 bar. */
    public boolean onTouch(View bar, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isBusy() || !host.prepareToRecord()) {
                    return false;
                }
                begin(bar);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (holding && overlay != null) {
                    setZone(bar, zoneFor(event));
                }
                return holding;
            case MotionEvent.ACTION_UP:
                if (holding) {
                    setZone(bar, zoneFor(event));
                    finish(zone);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (holding) {
                    finish(LuckgramVoiceBarLogic.ZONE_CANCEL);
                }
                return true;
        }
        return holding;
    }

    private int zoneFor(MotionEvent event) {
        if (overlay == null || overlay.getWidth() == 0) {
            return LuckgramVoiceBarLogic.ZONE_SEND;
        }
        final int[] loc = new int[2];
        overlay.getLocationOnScreen(loc);
        return LuckgramVoiceBarLogic.zoneFor(event.getRawX() - loc[0], event.getRawY() - loc[1], overlay.getWidth(), overlay.actionLineY());
    }

    private void setZone(View bar, int newZone) {
        if (zone != newZone) {
            zone = newZone;
            try {
                bar.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {
            }
        }
        if (overlay != null) {
            overlay.setZone(newZone);
        }
    }

    private void begin(View bar) {
        final Activity activity = host.activity();
        if (activity == null) {
            return;
        }
        holding = true;
        started = false;
        peakAmplitude = 0;
        zone = LuckgramVoiceBarLogic.ZONE_SEND;
        downTime = SystemClock.elapsedRealtime();
        if (bar.getParent() != null) {
            bar.getParent().requestDisallowInterceptTouchEvent(true);
        }
        try {
            bar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignore) {
        }
        overlay = new LuckgramTalkOverlay(activity);
        ((ViewGroup) activity.getWindow().getDecorView()).addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.startRecording(guid);
        host.onRecordingChanged(true);
    }

    private void finish(int how) {
        holding = false;
        final boolean toText = how == LuckgramVoiceBarLogic.ZONE_TEXT && (started || SystemClock.elapsedRealtime() - downTime >= 400) && !LuckgramVoiceBarLogic.isSilent(peakAmplitude);
        if (!toText) {
            removeOverlay();
        }
        host.onRecordingChanged(false);
        if (how == LuckgramVoiceBarLogic.ZONE_CANCEL) {
            MediaController.getInstance().stopRecording(0, false, 0, false, 0);
            return;
        }
        if (!started && SystemClock.elapsedRealtime() - downTime < 400) {
            // released before the recorder even started
            MediaController.getInstance().stopRecording(0, false, 0, false, 0);
            host.showHint(tooShort());
            return;
        }
        if (how == LuckgramVoiceBarLogic.ZONE_TEXT) {
            if (LuckgramVoiceBarLogic.isSilent(peakAmplitude)) {
                // nothing to convert; a model would make something up
                MediaController.getInstance().stopRecording(0, false, 0, false, 0);
                host.showHint(nothingHeard());
                return;
            }
            awaitingText = true;
            setConverting(true);
            MediaController.getInstance().stopRecording(2, false, 0, false, 0);
        } else {
            MediaController.getInstance().stopRecording(1, true, 0, false, 0);
        }
    }

    /** 「转文字」 is running: the overlay stays as the text bubble until it ends; a tap on it cancels. */
    private void setConverting(boolean value) {
        converting = value;
        if (value) {
            if (overlay != null) {
                overlay.setConverting(true, this::cancel);
            }
        } else {
            removeOverlay();
        }
        host.onTranscribing(value);
    }

    private void removeOverlay() {
        if (overlay != null) {
            overlay.remove();
            overlay = null;
        }
    }

    private static String nothingHeard() {
        return org.telegram.luckgram.LuckgramStrings.get("没有听到内容", "Nothing was heard");
    }

    private static String tooShort() {
        return org.telegram.luckgram.LuckgramStrings.get("说话时间太短", "Message too short");
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (destroyed || args.length == 0 || !(args[0] instanceof Integer) || (Integer) args[0] != guid) {
            return;
        }
        if (id == NotificationCenter.recordStarted) {
            started = true;
            if (overlay != null) {
                overlay.start();
            }
        } else if (id == NotificationCenter.recordProgressChanged) {
            if (args.length > 1 && args[1] instanceof Double) {
                peakAmplitude = Math.max(peakAmplitude, (Double) args[1]);
                if (overlay != null) {
                    overlay.addAmplitude((Double) args[1]);
                }
            }
        } else if (id == NotificationCenter.recordStartError) {
            if (holding) {
                holding = false;
                removeOverlay();
                host.onRecordingChanged(false);
                host.showHint(org.telegram.luckgram.LuckgramStrings.get("无法开始录音", "Couldn't start recording"));
            }
        } else if (id == NotificationCenter.recordStopped) {
            // stopped from outside (a call, the time limit…) while still holding
            if (holding) {
                holding = false;
                removeOverlay();
                host.onRecordingChanged(false);
            }
        } else if (id == NotificationCenter.audioRecordTooShort) {
            if (awaitingText) {
                awaitingText = false;
                setConverting(false);
            }
            host.showHint(tooShort());
        } else if (id == NotificationCenter.audioDidSent) {
            final String path = args.length > 2 && args[2] instanceof String ? (String) args[2] : null;
            if (awaitingText) {
                awaitingText = false;
                if (path == null) {
                    setConverting(false);
                    return;
                }
                transcribe(new File(path));
            } else if (path == null) {
                host.onVoiceSent();
            } else {
                try {
                    new File(path).delete();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private void transcribe(File file) {
        final Context context = host.activity();
        Utilities.globalQueue.postRunnable(() -> {
            byte[] data = null;
            try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                final byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                data = out.toByteArray();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            // the recording is only needed for this request; nothing is kept on disk
            try {
                file.delete();
            } catch (Throwable ignore) {
            }
            final byte[] audio = data;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || !converting) {
                    return; // cancelled while the file was read
                }
                if (audio == null || audio.length == 0 || context == null) {
                    failText(null);
                    return;
                }
                request = LuckgramAI.getInstance().transcribe(context, audio, "audio/ogg", null, new LuckgramAI.Callback<LuckgramAI.Transcription>() {
                    @Override
                    public void onResult(LuckgramAI.Transcription t) {
                        request = null;
                        if (destroyed) {
                            return;
                        }
                        final String text = t == null || t.text == null ? "" : t.text.trim();
                        setConverting(false);
                        if (text.isEmpty() || LuckgramVoiceBarLogic.isSilenceHallucination(text, LuckgramVoiceBarLogic.isWeak(peakAmplitude))) {
                            host.showHint(nothingHeard());
                        } else {
                            host.onTranscribed(text);
                        }
                    }

                    @Override
                    public void onError(LuckgramAIError error) {
                        request = null;
                        if (destroyed) {
                            return;
                        }
                        failText(error);
                    }
                });
            });
        });
    }

    private void failText(LuckgramAIError error) {
        setConverting(false);
        String reason = error != null ? error.getLocalizedMessage() : null;
        host.showHint(org.telegram.luckgram.LuckgramStrings.get("转文字失败", "Couldn't convert to text") + (reason != null && !reason.isEmpty() ? "：" + reason : ""));
    }

    /** Leaves the chat / mode: drop whatever is going on. */
    public void cancel() {
        if (holding) {
            holding = false;
            removeOverlay();
            host.onRecordingChanged(false);
            MediaController.getInstance().stopRecording(0, false, 0, false, 0);
        }
        if (request != null) {
            request.cancel();
            request = null;
        }
        if (awaitingText || converting) {
            awaitingText = false;
            setConverting(false);
        }
    }

    public void destroy() {
        cancel();
        destroyed = true;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.recordStarted);
        nc.removeObserver(this, NotificationCenter.recordStartError);
        nc.removeObserver(this, NotificationCenter.recordStopped);
        nc.removeObserver(this, NotificationCenter.recordProgressChanged);
        nc.removeObserver(this, NotificationCenter.audioDidSent);
        nc.removeObserver(this, NotificationCenter.audioRecordTooShort);
    }
}

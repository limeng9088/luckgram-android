package org.telegram.luckgram.voice;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.voicebar.LuckgramTalkOverlay;
import org.telegram.luckgram.voicebar.LuckgramVoiceBarLogic;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;

/**
 * Luckgram specs#171: 「按住 说话」 in the AI assistant. Same overlay and zones as the chat's voice bar
 * (specs#145), but nothing is sent to Telegram: the recording is transcribed and handed to the page.
 * Release = send what was said to the assistant; slide up right = into the field; slide up left = cancel.
 */
public class LuckgramAiTalk {

    public interface Host {
        Activity activity();

        /** False while a reply is being generated. */
        boolean canStart();

        /** Holding / converting changed: refresh the bar's text. */
        void onStateChanged();

        void onSend(String text);

        void onText(String text);

        void showHint(String text);
    }

    private static final long MINIMUM_MS = 700;
    private static final long MAX_RECORD_MS = 2 * 60 * 1000;

    private final Host host;
    private LuckgramTalkOverlay overlay;
    private boolean holding;
    private boolean converting;
    private int zone;
    private long downTime;

    private volatile boolean recording;
    private Thread recordThread;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private double peakAmplitude;
    private double loudSeconds;
    private long amplitudeTime;
    private LuckgramAI.Request request;

    public LuckgramAiTalk(Host host) {
        this.host = host;
    }

    public boolean isHolding() {
        return holding;
    }

    public boolean isConverting() {
        return converting;
    }

    public String barText() {
        if (converting) {
            return LuckgramStrings.get("正在转文字…", "Converting to text…");
        }
        return holding ? LuckgramStrings.get("松开 发送", "Release to send") : LuckgramStrings.get("按住 说话", "Hold to talk");
    }

    /** The bar's touch listener. */
    public boolean onTouch(View bar, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (holding || converting || !host.canStart()) {
                    return true;
                }
                begin(bar);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (holding && overlay != null && overlay.getWidth() > 0) {
                    final int[] loc = new int[2];
                    overlay.getLocationOnScreen(loc);
                    final int next = LuckgramVoiceBarLogic.zoneFor(event.getRawX() - loc[0], event.getRawY() - loc[1], overlay.getWidth(), overlay.actionLineY());
                    if (next != zone) {
                        zone = next;
                        overlay.setZone(next);
                        bar.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                finish(zone);
                return true;
            case MotionEvent.ACTION_CANCEL:
                finish(LuckgramVoiceBarLogic.ZONE_CANCEL);
                return true;
        }
        return true;
    }

    private void begin(View bar) {
        final Activity activity = host.activity();
        if (activity == null) {
            return;
        }
        final int minBuffer = AudioRecord.getMinBufferSize(LuckgramDictationSheet.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, LuckgramDictationSheet.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer, LuckgramDictationSheet.SAMPLE_RATE));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                throw new IllegalStateException("AudioRecord not initialized");
            }
            record.startRecording();
        } catch (Throwable e) {
            FileLog.e(e);
            host.showHint(LuckgramStrings.get("无法使用麦克风", "Can't use the microphone"));
            return;
        }
        holding = true;
        zone = LuckgramVoiceBarLogic.ZONE_SEND;
        downTime = SystemClock.elapsedRealtime();
        peakAmplitude = 0;
        loudSeconds = 0;
        amplitudeTime = 0;
        synchronized (pcm) {
            pcm.reset();
        }
        bar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);

        overlay = new LuckgramTalkOverlay(activity);
        ((ViewGroup) activity.getWindow().getDecorView()).addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setZone(zone);
        overlay.start();
        host.onStateChanged();

        recording = true;
        final long startedAt = downTime;
        recordThread = new Thread(() -> {
            final byte[] buf = new byte[Math.max(minBuffer, 3200)];
            try {
                while (recording) {
                    final int n = record.read(buf, 0, buf.length);
                    if (n < 0) {
                        // the microphone is gone (taken by a call, another app…): stop instead of spinning
                        AndroidUtilities.runOnUIThread(() -> {
                            if (holding) {
                                finish(LuckgramVoiceBarLogic.ZONE_CANCEL);
                                host.showHint(LuckgramStrings.get("无法使用麦克风", "Can't use the microphone"));
                            }
                        });
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    synchronized (pcm) {
                        pcm.write(buf, 0, n);
                    }
                    final double amplitude = rms(buf, n);
                    AndroidUtilities.runOnUIThread(() -> onAmplitude(amplitude));
                    if (SystemClock.elapsedRealtime() - startedAt > MAX_RECORD_MS) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (holding) {
                                finish(zone);
                            }
                        });
                        break;
                    }
                }
            } finally {
                try {
                    record.stop();
                } catch (Throwable ignore) {
                }
                record.release();
            }
        }, "lg-ai-talk");
        recordThread.start();
    }

    /** RMS of 16-bit little-endian PCM, the scale {@link LuckgramVoiceBarLogic} and the overlay expect. */
    private static double rms(byte[] pcm, int length) {
        long sum = 0;
        int count = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            final int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long) s * s;
            count++;
        }
        return count == 0 ? 0 : Math.sqrt(sum / (double) count);
    }

    private void onAmplitude(double amplitude) {
        if (!holding) {
            return;
        }
        peakAmplitude = Math.max(peakAmplitude, amplitude);
        final long now = SystemClock.elapsedRealtime();
        if (amplitudeTime > 0) {
            loudSeconds = LuckgramVoiceBarLogic.loudSeconds(loudSeconds, amplitude, (now - amplitudeTime) / 1000.0);
        }
        amplitudeTime = now;
        if (overlay != null) {
            overlay.addAmplitude(amplitude);
        }
    }

    private void stopRecording() {
        recording = false;
        if (recordThread != null) {
            try {
                recordThread.join(1000);
            } catch (InterruptedException ignore) {
            }
            recordThread = null;
        }
    }

    private void removeOverlay() {
        if (overlay != null) {
            overlay.remove();
            overlay = null;
        }
    }

    private void finish(int how) {
        if (!holding) {
            return;
        }
        holding = false;
        final long duration = SystemClock.elapsedRealtime() - downTime;
        stopRecording();
        final byte[] audio;
        synchronized (pcm) {
            audio = pcm.toByteArray();
            pcm.reset();
        }
        if (how == LuckgramVoiceBarLogic.ZONE_CANCEL) {
            removeOverlay();
            host.onStateChanged();
            return;
        }
        if (duration < MINIMUM_MS) {
            removeOverlay();
            host.onStateChanged();
            host.showHint(LuckgramStrings.get("说话时间太短", "Message too short"));
            return;
        }
        if (audio.length == 0 || LuckgramVoiceBarLogic.isSilent(peakAmplitude, loudSeconds)) {
            // nothing to convert; a model would make something up
            removeOverlay();
            host.onStateChanged();
            host.showHint(nothingHeard());
            return;
        }
        final Activity activity = host.activity();
        if (activity == null) {
            removeOverlay();
            host.onStateChanged();
            return;
        }
        converting = true;
        if (overlay != null) {
            overlay.setConverting(true, this::cancel);
        }
        host.onStateChanged();
        final boolean weak = LuckgramVoiceBarLogic.isWeak(peakAmplitude);
        final boolean toField = how == LuckgramVoiceBarLogic.ZONE_TEXT;
        request = LuckgramAI.getInstance().transcribe(activity, DictationAudio.toWav(audio, LuckgramDictationSheet.SAMPLE_RATE), "audio/wav", null, new LuckgramAI.Callback<LuckgramAI.Transcription>() {
            @Override
            public void onResult(LuckgramAI.Transcription t) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!converting) {
                        return;
                    }
                    endConverting();
                    final String text = t == null || t.text == null ? "" : t.text.trim();
                    if (text.isEmpty() || LuckgramVoiceBarLogic.isSilenceHallucination(text, weak)) {
                        host.showHint(nothingHeard());
                    } else if (toField) {
                        host.onText(text);
                    } else {
                        host.onSend(text);
                    }
                });
            }

            @Override
            public void onError(LuckgramAIError error) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!converting) {
                        return;
                    }
                    endConverting();
                    if (error != null && (LuckgramAIError.CONSENT_DENIED.equals(error.code) || LuckgramAIError.CANCELLED.equals(error.code))) {
                        return;
                    }
                    host.showHint(LuckgramStrings.get("转文字失败", "Couldn't convert to text"));
                });
            }
        });
    }

    private void endConverting() {
        converting = false;
        request = null;
        removeOverlay();
        host.onStateChanged();
    }

    /** Leaving the page, or a tap on the overlay while converting: drop whatever is going on. */
    public void cancel() {
        if (holding) {
            holding = false;
            stopRecording();
            synchronized (pcm) {
                pcm.reset();
            }
        }
        if (request != null) {
            request.cancel();
            request = null;
        }
        converting = false;
        removeOverlay();
        host.onStateChanged();
    }

    private static String nothingHeard() {
        return LuckgramStrings.get("没有听到内容", "Nothing was heard");
    }
}

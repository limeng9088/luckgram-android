package org.telegram.luckgram.voice;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramFeatures;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

/**
 * spec 17 voice input: records from the microphone with a live level waveform, then transcribes
 * it with {@link LuckgramAI#transcribe} and, if "AI tidy" is on, cleans it up with
 * {@code task=writing, effect=dictation}. The text is handed to {@link Callback#onText}; the caller
 * inserts it at the cursor. Nothing is kept on disk: the audio only lives in memory.
 */
public class LuckgramDictationSheet extends BottomSheet {

    public interface Callback {
        void onText(String text);
    }

    static final int SAMPLE_RATE = 16000;
    /** Memory guard for one dictation (10 min of 16 kHz mono PCM ≈ 19 MB). */
    private static final long MAX_RECORD_MS = 10 * 60 * 1000;

    private static final int STATE_RECORDING = 0, STATE_WORKING = 1, STATE_ERROR = 2;

    private final Callback callback;
    private final TextView status;
    private final WaveformView waveform;
    private final ButtonWithCounterView button;
    private int state = STATE_RECORDING;

    private volatile boolean recording;
    private Thread recordThread;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private byte[] wav;
    private LuckgramAI.Request request;
    private long startedAt;

    public LuckgramDictationSheet(Context context, Theme.ResourcesProvider resourcesProvider, Callback callback) {
        super(context, false, resourcesProvider);
        this.callback = callback;
        fixNavigationBar();
        setCanDismissWithSwipe(false);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(LuckgramStrings.get("语音输入", "Voice input"));
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 4, 22, 8));

        waveform = new WaveformView(context);
        layout.addView(waveform, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 72, 22, 8, 22, 8));

        status = new TextView(context);
        status.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setGravity(Gravity.CENTER);
        layout.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 12));

        button = new ButtonWithCounterView(context, resourcesProvider);
        button.setOnClickListener(v -> onButton());
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 8));

        setCustomView(layout);
        setState(STATE_RECORDING, null);
    }

    private void setState(int newState, String error) {
        state = newState;
        switch (newState) {
            case STATE_RECORDING:
                status.setText(LuckgramStrings.get("正在聆听…说完点「停止」", "Listening… tap Stop when done"));
                button.setText(LuckgramStrings.get("停止", "Stop"), false);
                button.setLoading(false);
                break;
            case STATE_WORKING:
                status.setText(LuckgramStrings.get("正在转写…", "Transcribing…"));
                button.setText(LuckgramStrings.get("停止", "Stop"), false);
                button.setLoading(true);
                break;
            case STATE_ERROR:
                status.setText(error);
                button.setText(LuckgramStrings.get("重试", "Retry"), false);
                button.setLoading(false);
                break;
        }
    }

    private void onButton() {
        if (state == STATE_RECORDING) {
            stopRecording();
            if (pcm.size() < SAMPLE_RATE / 5) { // < 0.1 s of audio
                dismiss();
                return;
            }
            wav = DictationAudio.toWav(pcm.toByteArray(), SAMPLE_RATE);
            transcribe();
        } else if (state == STATE_ERROR && wav != null) {
            transcribe();
        }
    }

    @Override
    public void show() {
        super.show();
        startRecording();
    }

    @SuppressLint("MissingPermission") // checked by the caller (ChatActivityEnterView)
    private void startRecording() {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer, SAMPLE_RATE));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                throw new IllegalStateException("AudioRecord not initialized");
            }
            record.startRecording();
        } catch (Throwable e) {
            FileLog.e(e);
            setState(STATE_ERROR, LuckgramStrings.get("无法使用麦克风", "Can't use the microphone"));
            wav = null;
            return;
        }
        recording = true;
        startedAt = SystemClock.elapsedRealtime();
        recordThread = new Thread(() -> {
            byte[] buf = new byte[Math.max(minBuffer, 3200)];
            try {
                while (recording) {
                    int n = record.read(buf, 0, buf.length);
                    if (n <= 0) {
                        continue;
                    }
                    synchronized (pcm) {
                        pcm.write(buf, 0, n);
                    }
                    final float level = DictationAudio.level(buf, n);
                    AndroidUtilities.runOnUIThread(() -> waveform.push(level));
                    if (SystemClock.elapsedRealtime() - startedAt > MAX_RECORD_MS) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (state == STATE_RECORDING) {
                                onButton();
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
        }, "lg-dictation");
        recordThread.start();
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

    private void transcribe() {
        setState(STATE_WORKING, null);
        request = LuckgramAI.getInstance().transcribe(getContext(), wav, "audio/wav", null, new LuckgramAI.Callback<LuckgramAI.Transcription>() {
            @Override
            public void onResult(LuckgramAI.Transcription t) {
                String text = t.text == null ? "" : t.text.trim();
                if (text.isEmpty()) {
                    setState(STATE_ERROR, LuckgramStrings.get("没有识别到文字", "No words recognized"));
                    return;
                }
                if (LuckgramSettings.isEnabled(LuckgramFeatures.DICTATION_AI_TIDY)) {
                    tidy(text);
                } else {
                    finish(text);
                }
            }

            @Override
            public void onError(LuckgramAIError error) {
                onAiError(error);
            }
        });
    }

    private void tidy(String raw) {
        status.setText(LuckgramStrings.get("正在整理…", "Tidying up…"));
        request = LuckgramAI.getInstance().chat(getContext(), LuckgramAI.TASK_WRITING, "dictation",
            Collections.singletonList(LuckgramAI.Message.user(raw)), false, new LuckgramAI.ChatListener() {
                @Override
                public void onDelta(String delta, String text) {
                }

                @Override
                public void onComplete(String text) {
                    String tidy = text == null ? "" : text.trim();
                    finish(tidy.isEmpty() ? raw : tidy);
                }

                @Override
                public void onError(LuckgramAIError error) {
                    // keep the plain transcript rather than losing what was said
                    finish(raw);
                }
            });
    }

    private void onAiError(LuckgramAIError error) {
        if (error != null && (LuckgramAIError.CONSENT_DENIED.equals(error.code) || LuckgramAIError.CANCELLED.equals(error.code))) {
            dismiss();
            return;
        }
        setState(STATE_ERROR, error == null ? LuckgramStrings.errorMessage(null) : error.getLocalizedMessage());
    }

    private void finish(String text) {
        request = null;
        if (isShowing()) {
            dismiss();
        }
        if (callback != null) {
            callback.onText(text);
        }
    }

    @Override
    public void dismiss() {
        stopRecording();
        if (request != null) {
            request.cancel();
            request = null;
        }
        super.dismiss();
    }

    /** Scrolling bars of recent input levels. */
    private class WaveformView extends View {
        private final float[] levels = new float[48];
        private int head;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        WaveformView(Context context) {
            super(context);
        }

        void push(float level) {
            // perceived loudness: a square root keeps quiet speech visible
            levels[head] = (float) Math.sqrt(level);
            head = (head + 1) % levels.length;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
            int n = levels.length;
            float slot = getWidth() / (float) n;
            float barW = Math.max(dp(2), slot * 0.55f);
            float h = getHeight();
            for (int i = 0; i < n; i++) {
                float v = levels[(head + i) % n];
                float barH = Math.max(dp(3), v * h);
                float cx = slot * i + slot / 2f;
                rect.set(cx - barW / 2f, (h - barH) / 2f, cx + barW / 2f, (h + barH) / 2f);
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
            }
        }
    }
}

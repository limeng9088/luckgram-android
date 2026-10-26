/*
 * Luckgram: the "录音 / 录音 0:21" pill shown under the call status in VoIPFragment.
 */
package org.telegram.luckgram.callrec;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.voip.VoIPService;

public class CallRecButton extends TextView {

    private static final int IDLE_BG = 0x33FFFFFF;
    private static final int RECORDING_BG = 0xFFE5393B;

    private final GradientDrawable background = new GradientDrawable();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable listener = this::update;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            update();
            if (isAttachedToWindow() && CallRecorder.getInstance().isRecording()) {
                AndroidUtilities.runOnUIThread(this, 500);
            }
        }
    };
    private boolean recording;

    public CallRecButton(@NonNull Context context) {
        super(context);
        setTextColor(Color.WHITE);
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        setTypeface(AndroidUtilities.bold());
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setPadding(AndroidUtilities.dp(28), 0, AndroidUtilities.dp(14), 0);
        setMinWidth(AndroidUtilities.dp(88));
        background.setCornerRadius(AndroidUtilities.dp(16));
        setBackground(background);
        dotPaint.setColor(Color.WHITE);
        setOnClickListener(v -> {
            CallRecorder.getInstance().toggle(VoIPService.getSharedInstance());
            update();
        });
        update();
    }

    /** Shown only for one-on-one calls that are connected (or already recording) with the switch on. */
    public boolean shouldBeVisible() {
        if (CallRecorder.getInstance().isRecording()) {
            return true;
        }
        if (!CallRecSettings.isEnabled()) {
            return false;
        }
        VoIPService service = VoIPService.getSharedInstance();
        return CallRecorder.isEligible(service) && service.getCallState() == VoIPService.STATE_ESTABLISHED;
    }

    public void update() {
        final CallRecorder recorder = CallRecorder.getInstance();
        final boolean wasRecording = recording;
        recording = recorder.isRecording();
        if (recording) {
            setText(CallRecStrings.recording(AndroidUtilities.formatShortDuration((int) (recorder.getElapsedMs() / 1000))));
            setContentDescription(CallRecStrings.stopRecordingDescription());
            background.setColor(RECORDING_BG);
            if (!wasRecording) {
                AndroidUtilities.cancelRunOnUIThread(ticker);
                AndroidUtilities.runOnUIThread(ticker, 500);
            }
        } else {
            setText(CallRecStrings.record());
            setContentDescription(CallRecStrings.record());
            background.setColor(IDLE_BG);
        }
        setVisibility(shouldBeVisible() ? View.VISIBLE : View.GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Record dot left of the label.
        final float r = AndroidUtilities.dp(4);
        dotPaint.setAlpha(recording && (System.currentTimeMillis() / 500) % 2 == 1 ? 120 : 255);
        canvas.drawCircle(AndroidUtilities.dp(16), getHeight() / 2f, r, dotPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        CallRecorder.getInstance().addListener(listener);
        recording = false;
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        CallRecorder.getInstance().removeListener(listener);
        AndroidUtilities.cancelRunOnUIThread(ticker);
    }
}

package org.telegram.luckgram.voicebar;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;

/**
 * specs#145: the full-screen overlay shown while 「按住 说话」 is held. Display only: the touch stays
 * with the bar, which reports the finger position through {@link #setZone}.
 */
public class LuckgramTalkOverlay extends View {

    private static final int BARS = 28;
    private static final int COLOR_BUBBLE = 0xFF95EC69;
    private static final int COLOR_CANCEL = 0xFFFA5151;
    private static final int COLOR_TEXT_ZONE = 0xFFFFFFFF;

    private final Paint dimPaint = new Paint();
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint glyphPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private final float[] levels = new float[BARS];
    private int zone = LuckgramVoiceBarLogic.ZONE_SEND;
    private long startedAt;
    private float appear;
    private long lastFrame;
    private float cancelScale = 1f, textScale = 1f;

    public LuckgramTalkOverlay(Context context) {
        super(context);
        dimPaint.setColor(0xB3000000);
        labelPaint.setTextSize(dp(14));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTextSize(dp(22));
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTypeface(Typeface.DEFAULT_BOLD);
        timerPaint.setTextSize(dp(13));
        timerPaint.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Recording has really started: the timer runs from here. */
    public void start() {
        startedAt = SystemClock.elapsedRealtime();
        invalidate();
    }

    public void setZone(int zone) {
        if (this.zone != zone) {
            this.zone = zone;
            invalidate();
        }
    }

    /** {@code amplitude} as posted with {@code recordProgressChanged} (roughly 0..1). */
    public void addAmplitude(double amplitude) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1);
        levels[BARS - 1] = (float) Math.max(0, Math.min(1, amplitude));
        invalidate();
    }

    /** Y (in this view) above which the finger is in the cancel / to-text area. */
    public float actionLineY() {
        return getHeight() - arcHeight();
    }

    private float arcHeight() {
        return dp(132);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        appear = Math.min(1f, appear + dt / 0.18f);
        cancelScale = approach(cancelScale, zone == LuckgramVoiceBarLogic.ZONE_CANCEL ? 1.25f : 1f, dt);
        textScale = approach(textScale, zone == LuckgramVoiceBarLogic.ZONE_TEXT ? 1.25f : 1f, dt);

        final int w = getWidth(), h = getHeight();
        dimPaint.setAlpha((int) (0xB3 * appear));
        canvas.drawRect(0, 0, w, h, dimPaint);

        final int alpha = (int) (255 * appear);
        final float arcTop = h - arcHeight();

        // bubble with the live waveform
        final float bubbleW = zone == LuckgramVoiceBarLogic.ZONE_SEND ? dp(180) : dp(110);
        final float bubbleH = dp(68);
        float bubbleCx = w / 2f;
        if (zone == LuckgramVoiceBarLogic.ZONE_CANCEL) {
            bubbleCx = w * 0.28f;
        } else if (zone == LuckgramVoiceBarLogic.ZONE_TEXT) {
            bubbleCx = w * 0.72f;
        }
        final float bubbleCy = arcTop - dp(190);
        bubblePaint.setColor(zone == LuckgramVoiceBarLogic.ZONE_CANCEL ? COLOR_CANCEL : COLOR_BUBBLE);
        bubblePaint.setAlpha(alpha);
        rect.set(bubbleCx - bubbleW / 2f, bubbleCy - bubbleH / 2f, bubbleCx + bubbleW / 2f, bubbleCy + bubbleH / 2f);
        canvas.drawRoundRect(rect, dp(14), dp(14), bubblePaint);
        path.rewind();
        path.moveTo(bubbleCx - dp(7), rect.bottom - 1);
        path.lineTo(bubbleCx, rect.bottom + dp(7));
        path.lineTo(bubbleCx + dp(7), rect.bottom - 1);
        path.close();
        canvas.drawPath(path, bubblePaint);

        barPaint.setColor(zone == LuckgramVoiceBarLogic.ZONE_CANCEL ? 0xFFFFFFFF : 0xE6000000);
        barPaint.setAlpha((int) (alpha * 0.9f));
        final float barW = dp(2.5f), gap = dp(2.5f);
        final int shown = Math.min(BARS, (int) ((bubbleW - dp(28)) / (barW + gap)));
        final float total = shown * barW + (shown - 1) * gap;
        float x = bubbleCx - total / 2f;
        for (int i = BARS - shown; i < BARS; i++) {
            final float bh = dp(4) + levels[i] * dp(30);
            rect.set(x, bubbleCy - bh / 2f, x + barW, bubbleCy + bh / 2f);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint);
            x += barW + gap;
        }
        if (zone == LuckgramVoiceBarLogic.ZONE_SEND && startedAt != 0) {
            canvas.drawText(LuckgramVoiceBarLogic.formatDuration(now - startedAt), bubbleCx, bubbleCy + bubbleH / 2f + dp(30), whiteTimer(alpha));
        }

        // the two targets
        final float targetY = arcTop - dp(70);
        drawTarget(canvas, w * 0.22f, targetY, cancelScale, zone == LuckgramVoiceBarLogic.ZONE_CANCEL, COLOR_CANCEL, "✕",
            LuckgramStrings.get("取消", "Cancel"), alpha, true);
        drawTarget(canvas, w * 0.78f, targetY, textScale, zone == LuckgramVoiceBarLogic.ZONE_TEXT, COLOR_TEXT_ZONE, LuckgramStrings.get("文", "Aa"),
            LuckgramStrings.get("转文字", "To text"), alpha, false);

        // release area
        final boolean send = zone == LuckgramVoiceBarLogic.ZONE_SEND;
        arcPaint.setColor(send ? 0xFFE6E6E6 : 0xFF4C4C4C);
        arcPaint.setAlpha(alpha);
        path.rewind();
        path.moveTo(0, h);
        path.lineTo(0, arcTop + dp(36));
        path.quadTo(w / 2f, arcTop - dp(36), w, arcTop + dp(36));
        path.lineTo(w, h);
        path.close();
        canvas.drawPath(path, arcPaint);
        if (send) {
            labelPaint.setColor(0xFFFFFFFF);
            labelPaint.setAlpha((int) (alpha * 0.85f));
            canvas.drawText(LuckgramStrings.get("松开 发送", "Release to send"), w / 2f, arcTop - dp(14), labelPaint);
        }

        if (appear < 1f || Math.abs(cancelScale - (zone == LuckgramVoiceBarLogic.ZONE_CANCEL ? 1.25f : 1f)) > 0.005f
                || Math.abs(textScale - (zone == LuckgramVoiceBarLogic.ZONE_TEXT ? 1.25f : 1f)) > 0.005f || (send && startedAt != 0)) {
            postInvalidateOnAnimation();
        }
    }

    private TextPaint whiteTimer(int alpha) {
        timerPaint.setColor(0xFFFFFFFF);
        timerPaint.setAlpha((int) (alpha * 0.85f));
        return timerPaint;
    }

    private void drawTarget(Canvas canvas, float cx, float cy, float scale, boolean active, int activeColor, String glyph, String label, int alpha, boolean lightGlyphWhenActive) {
        final float r = dp(34) * scale;
        circlePaint.setColor(active ? activeColor : 0xFF3A3A3A);
        circlePaint.setAlpha(alpha);
        canvas.drawCircle(cx, cy, r, circlePaint);
        glyphPaint.setColor(active ? (lightGlyphWhenActive ? 0xFFFFFFFF : 0xFF000000) : 0xFFB8B8B8);
        glyphPaint.setAlpha(alpha);
        canvas.drawText(glyph, cx, cy - (glyphPaint.descent() + glyphPaint.ascent()) / 2f, glyphPaint);
        if (active) {
            labelPaint.setColor(0xFFFFFFFF);
            labelPaint.setAlpha((int) (alpha * 0.85f));
            canvas.drawText(label, cx, cy - r - dp(12), labelPaint);
        }
    }

    private static float approach(float value, float target, float dt) {
        final float step = dt / 0.12f * 0.25f;
        if (value < target) {
            return Math.min(target, value + step);
        }
        return Math.max(target, value - step);
    }

    public void remove() {
        AndroidUtilities.removeFromParent(this);
    }
}

package org.telegram.luckgram.voicebar;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;

/**
 * specs#145: the full-screen overlay shown while 「按住 说话」 is held. Display only: the touch stays
 * with the bar, which reports the finger position through {@link #setZone}.
 *
 * Layout, bottom up: the release area (an arc, 「松开 发送」), two tilted pills (「取消」 left,
 * 「滑到这里 转文字」 right) and the voice bubble, which is wide and green while sending, a small red
 * square over 「取消」, and a full-width text bubble over 「转文字」.
 */
public class LuckgramTalkOverlay extends View {

    private static final int BARS = 24;
    private static final int COLOR_BUBBLE = 0xFF95EC69;
    private static final int COLOR_CANCEL = 0xFFFA5151;
    private static final float TILT = 7f;

    private final Paint dimPaint = new Paint();
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint arcTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubbleTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private LinearGradient arcGradient;
    private int arcGradientHeight;

    private final float[] levels = new float[BARS];
    private int zone = LuckgramVoiceBarLogic.ZONE_SEND;
    private boolean converting;
    private float appear;
    private long lastFrame;

    // animated towards the current zone's values
    private boolean laidOut;
    private float bubbleLeft, bubbleRight, bubbleTop, bubbleTailX;
    private float cancelActive, textActive, sendActive = 1f;

    public LuckgramTalkOverlay(Context context) {
        super(context);
        dimPaint.setColor(0x99000000);
        pillTextPaint.setTextSize(dp(16));
        pillTextPaint.setTextAlign(Paint.Align.CENTER);
        pillTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        hintPaint.setTextSize(dp(14));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        arcTextPaint.setTextAlign(Paint.Align.CENTER);
        arcTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        bubbleTextPaint.setTextSize(dp(18));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Recording has really started. */
    public void start() {
        invalidate();
    }

    public void setZone(int zone) {
        if (this.zone != zone) {
            this.zone = zone;
            invalidate();
        }
    }

    /** The recording is being converted to text: only the text bubble stays; a tap cancels. */
    public void setConverting(boolean converting, Runnable onTap) {
        this.converting = converting;
        if (converting) {
            zone = LuckgramVoiceBarLogic.ZONE_TEXT;
            setOnClickListener(v -> {
                if (onTap != null) {
                    onTap.run();
                }
            });
        } else {
            setOnClickListener(null);
            setClickable(false);
        }
        invalidate();
    }

    /** {@code amplitude} as posted with {@code recordProgressChanged} (16-bit RMS). */
    public void addAmplitude(double amplitude) {
        System.arraycopy(levels, 1, levels, 0, BARS - 1);
        levels[BARS - 1] = (float) Math.max(0, Math.min(1, amplitude / 1800.0));
        invalidate();
    }

    /** Y (in this view) above which the finger is in the cancel / to-text area. */
    public float actionLineY() {
        return getHeight() - dp(118);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        final int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        final boolean cancel = zone == LuckgramVoiceBarLogic.ZONE_CANCEL;
        final boolean text = zone == LuckgramVoiceBarLogic.ZONE_TEXT;
        final boolean send = !cancel && !text;

        // targets for this zone
        final float pillCy = h - dp(168);
        final float pillH = dp(72);
        final float bubbleBottom = Math.min(h * 0.613f, pillCy - pillH / 2f - dp(96));
        final float tLeft, tRight, tTop, tTail;
        if (cancel) {
            tLeft = dp(36);
            tRight = tLeft + dp(78);
            tTop = bubbleBottom - dp(78);
            tTail = (tLeft + tRight) / 2f;
        } else if (text) {
            tLeft = dp(16);
            tRight = w - dp(16);
            tTop = bubbleBottom - dp(92);
            tTail = w * 0.76f;
        } else {
            final float bw = Math.min(w * 0.58f, dp(240));
            tLeft = (w - bw) / 2f;
            tRight = tLeft + bw;
            tTop = bubbleBottom - dp(76);
            tTail = w / 2f;
        }
        if (!laidOut) {
            laidOut = true;
            bubbleLeft = tLeft;
            bubbleRight = tRight;
            bubbleTop = tTop;
            bubbleTailX = tTail;
        }
        final float k = 1f - (float) Math.exp(-dt / 0.055f);
        bubbleLeft += (tLeft - bubbleLeft) * k;
        bubbleRight += (tRight - bubbleRight) * k;
        bubbleTop += (tTop - bubbleTop) * k;
        bubbleTailX += (tTail - bubbleTailX) * k;
        cancelActive += ((cancel ? 1f : 0f) - cancelActive) * k;
        textActive += ((text ? 1f : 0f) - textActive) * k;
        sendActive += ((send ? 1f : 0f) - sendActive) * k;
        appear = Math.min(1f, appear + dt / 0.18f);
        final int alpha = (int) (255 * appear);

        dimPaint.setAlpha((int) (0x99 * appear));
        canvas.drawRect(0, 0, w, h, dimPaint);

        // ---- bubble
        bubblePaint.setColor(ColorUtils.blendARGB(COLOR_BUBBLE, COLOR_CANCEL, cancelActive));
        bubblePaint.setAlpha(alpha);
        rect.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom);
        canvas.drawRoundRect(rect, dp(16), dp(16), bubblePaint);
        final float tailX = Math.max(bubbleLeft + dp(22), Math.min(bubbleRight - dp(22), bubbleTailX));
        path.rewind();
        path.moveTo(tailX - dp(9), bubbleBottom - 1);
        path.lineTo(tailX, bubbleBottom + dp(9));
        path.lineTo(tailX + dp(9), bubbleBottom - 1);
        path.close();
        canvas.drawPath(path, bubblePaint);

        final float barW = dp(2), gap = dp(1.5f);
        barPaint.setColor(0xFF000000);
        barPaint.setAlpha((int) (alpha * 0.42f));
        if (text) {
            // what will happen, then the small live waveform bottom right
            bubbleTextPaint.setColor(0xFF000000);
            bubbleTextPaint.setAlpha((int) (alpha * (converting ? 0.9f : 0.45f) * textActive));
            final String label = converting
                ? LuckgramStrings.get("正在转文字…", "Converting to text…")
                : LuckgramStrings.get("松手后转成文字", "Release to convert to text");
            canvas.drawText(label, bubbleLeft + dp(20), bubbleTop + dp(38), bubbleTextPaint);
            if (!converting) {
                drawBars(canvas, bubbleRight - dp(20) - (12 * barW + 11 * gap), bubbleBottom - dp(20), 12, barW, gap, dp(2), dp(10));
            }
        } else {
            final int count = cancel ? 8 : BARS;
            final float total = count * barW + (count - 1) * gap;
            drawBars(canvas, (bubbleLeft + bubbleRight) / 2f - total / 2f, (bubbleTop + bubbleBottom) / 2f, count, barW, gap, dp(3), dp(cancel ? 14 : 22));
        }

        if (converting) {
            hintPaint.setColor(0xFFFFFFFF);
            hintPaint.setAlpha((int) (alpha * 0.6f));
            canvas.drawText(LuckgramStrings.get("轻点取消", "Tap to cancel"), w / 2f, bubbleBottom + dp(40), hintPaint);
            if (appear < 1f || Math.abs(bubbleLeft - tLeft) > 0.5f) {
                postInvalidateOnAnimation();
            }
            return;
        }

        // ---- the two pills
        drawPill(canvas, true, w * 0.47f, pillCy, pillH, w * 0.22f, cancelActive, alpha,
            LuckgramStrings.get("取消", "Cancel"), LuckgramStrings.get("松手 取消", "Release to cancel"));
        drawPill(canvas, false, w * 0.53f, pillCy, pillH, w * 0.77f, textActive, alpha,
            LuckgramStrings.get("滑到这里 转文字", "Slide here for text"), LuckgramStrings.get("松手 编辑文字", "Release to edit text"));

        // ---- release area
        final float apex = h - dp(112), edge = h - dp(90);
        path.rewind();
        path.moveTo(0, h);
        path.lineTo(0, edge);
        path.quadTo(w / 2f, 2 * apex - edge, w, edge);
        path.lineTo(w, h);
        path.close();
        arcPaint.setShader(null);
        arcPaint.setColor(0xFF4A4A4A);
        arcPaint.setAlpha(alpha);
        canvas.drawPath(path, arcPaint);
        if (sendActive > 0.01f) {
            if (arcGradient == null || arcGradientHeight != h) {
                arcGradientHeight = h;
                arcGradient = new LinearGradient(0, apex, 0, h, 0xFFDEDEDE, 0xFF9C9C9C, Shader.TileMode.CLAMP);
            }
            arcPaint.setShader(arcGradient);
            arcPaint.setAlpha((int) (alpha * sendActive));
            canvas.drawPath(path, arcPaint);
            arcPaint.setShader(null);
        }
        final float arcTextY = h - dp(62);
        arcTextPaint.setTextSize(dp(17));
        arcTextPaint.setColor(0xFF161616);
        arcTextPaint.setAlpha((int) (alpha * sendActive));
        canvas.drawText(LuckgramStrings.get("松开 发送", "Release to send"), w / 2f, arcTextY, arcTextPaint);
        arcTextPaint.setTextSize(dp(16));
        arcTextPaint.setColor(0xFFC4C4C4);
        arcTextPaint.setAlpha((int) (alpha * (1f - sendActive)));
        canvas.drawText(LuckgramStrings.get("语音", "Voice"), w / 2f, arcTextY + dp(10), arcTextPaint);

        final boolean settled = appear >= 1f && Math.abs(bubbleLeft - tLeft) < 0.5f && Math.abs(bubbleRight - tRight) < 0.5f && Math.abs(bubbleTop - tTop) < 0.5f
            && Math.abs(cancelActive - (cancel ? 1f : 0f)) < 0.01f && Math.abs(textActive - (text ? 1f : 0f)) < 0.01f && Math.abs(sendActive - (send ? 1f : 0f)) < 0.01f;
        if (!settled) {
            postInvalidateOnAnimation();
        }
    }

    /** The newest {@code count} levels, left to right, centered on {@code cy}. */
    private void drawBars(Canvas canvas, float x, float cy, int count, float barW, float gap, float minH, float maxH) {
        for (int i = BARS - count; i < BARS; i++) {
            final float bh = minH + levels[i] * (maxH - minH);
            rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint);
            x += barW + gap;
        }
    }

    /**
     * A pill running off the screen edge, tilted up towards the middle. {@code innerX} is its end
     * near the middle, {@code textX} where its label is centered, {@code active} 0..1.
     */
    private void drawPill(Canvas canvas, boolean left, float innerX, float cy, float height, float textX, float active, int alpha, String label, String hint) {
        canvas.save();
        canvas.rotate(left ? -TILT : TILT, innerX, cy);
        final float grow = dp(4) * active;
        final float half = height / 2f + grow;
        if (left) {
            rect.set(-dp(80), cy - half, innerX + grow, cy + half);
        } else {
            rect.set(innerX - grow, cy - half, getWidth() + dp(80), cy + half);
        }
        pillPaint.setColor(ColorUtils.blendARGB(0xFF5A5A5A, 0xFFE2E2E2, active));
        pillPaint.setAlpha(alpha);
        canvas.drawRoundRect(rect, half, half, pillPaint);
        pillTextPaint.setColor(ColorUtils.blendARGB(0xFFEDEDED, 0xFF111111, active));
        pillTextPaint.setAlpha(alpha);
        canvas.drawText(label, textX, cy - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f, pillTextPaint);
        if (active > 0.01f) {
            hintPaint.setColor(0xFFB8B8B8);
            hintPaint.setAlpha((int) (alpha * active));
            canvas.drawText(hint, textX, cy - half - dp(14), hintPaint);
        }
        canvas.restore();
    }

    public void remove() {
        AndroidUtilities.removeFromParent(this);
    }
}

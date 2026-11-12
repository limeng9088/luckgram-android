package org.telegram.luckgram.square;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Pull-to-refresh for the Square feed, laid over the top of the list (the list itself never moves
 * or empties). While the finger pulls down at the top, a round indicator follows it and fills its
 * arc; past the threshold it ticks once and a release starts the refresh, during which the arc
 * spins in place. When the refresh ends the indicator shrinks away and a small pill says what
 * happened ("3 new posts" / "You're up to date") for a moment.
 */
public class SquareRefreshView extends FrameLayout {

    public interface Delegate {
        /** The user pulled past the threshold and let go. */
        void onRefresh();
    }

    /** The indicator moves half as far as the finger. */
    private static final float DRAG_RATE = 0.5f;
    private static final int SIZE_DP = 40;
    /** Resting place of the indicator while refreshing, from the top of this view. */
    private static final int REST_DP = 14;
    private static final long PILL_MS = 1300;

    private final Indicator indicator;
    private final TextView pill;
    private final Delegate delegate;
    private final Theme.ResourcesProvider resourcesProvider;

    private float startY = -1f;
    private boolean pulling;
    private boolean armed;
    private boolean refreshing;
    private final Runnable hidePill = this::hidePill;

    public SquareRefreshView(Context context, Delegate delegate, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.delegate = delegate;
        this.resourcesProvider = resourcesProvider;
        // the indicator comes out from under the top edge
        setClipChildren(true);
        setClipToPadding(true);

        indicator = new Indicator(context);
        indicator.setBackground(Theme.createCircleDrawable(dp(SIZE_DP), Theme.getColor(Theme.key_dialogBackground, resourcesProvider)));
        indicator.setElevation(dp(3));
        indicator.setVisibility(INVISIBLE);
        addView(indicator, LayoutHelper.createFrame(SIZE_DP, SIZE_DP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        pill = new TextView(context);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        pill.setTypeface(AndroidUtilities.bold());
        pill.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
        pill.setSingleLine(true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(12), dp(5), dp(12), dp(5));
        pill.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
        pill.setElevation(dp(2));
        pill.setVisibility(INVISIBLE);
        addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 16, 6, 16, 0));
    }

    /** Height this view needs: room for the indicator at rest, the pill and their shadows. */
    public static int heightDp() {
        return SIZE_DP + REST_DP + 16;
    }

    // ---------------------------------------------------------------- pulling

    /** Watches the list's touches; never takes them, so scrolling and taps work as before. */
    public void attach(RecyclerView list) {
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                onListTouch(rv, e);
                return false;
            }
        });
    }

    private void onListTouch(RecyclerView rv, MotionEvent e) {
        boolean atTop = !rv.canScrollVertically(-1);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = atTop ? e.getY() : -1f;
                pulling = false;
                armed = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (refreshing) break;
                if (!atTop) {
                    // the list scrolled away from the top: start over when it comes back
                    startY = -1f;
                    if (pulling) cancelPull();
                    break;
                }
                if (startY < 0) startY = e.getY();
                float distance = e.getY() - startY;
                if (distance > 0) {
                    pulling = true;
                    setPull(distance);
                } else if (pulling) {
                    cancelPull();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (pulling && !refreshing) {
                    if (armed && atTop) {
                        pulling = false;
                        delegate.onRefresh();
                    } else {
                        cancelPull();
                    }
                }
                startY = -1f;
                break;
            case MotionEvent.ACTION_CANCEL:
                if (pulling && !refreshing) cancelPull();
                startY = -1f;
                break;
        }
    }

    private float travelMax() {
        return dp(SIZE_DP + REST_DP);
    }

    private void setPull(float fingerDistance) {
        float max = travelMax();
        float travel = fingerDistance * DRAG_RATE;
        // past the threshold the indicator barely moves further
        if (travel > max) travel = max + (travel - max) * 0.2f;
        float progress = Math.min(1f, travel / max);
        hidePillNow();
        indicator.animate().cancel();
        indicator.setVisibility(VISIBLE);
        indicator.setScaleX(1f);
        indicator.setScaleY(1f);
        indicator.setAlpha(Math.min(1f, progress * 1.6f));
        indicator.setTranslationY(travel - dp(SIZE_DP));
        indicator.setPull(progress);
        boolean nowArmed = travel >= max;
        if (nowArmed && !armed) {
            try {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {
            }
        }
        armed = nowArmed;
    }

    private void cancelPull() {
        pulling = false;
        armed = false;
        indicator.animate().cancel();
        indicator.animate().translationY(-dp(SIZE_DP + 8)).alpha(0f).setDuration(180)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT)
            .withEndAction(() -> indicator.setVisibility(INVISIBLE)).start();
    }

    // ---------------------------------------------------------------- refreshing

    public boolean isRefreshing() {
        return refreshing;
    }

    /** A refresh is running: the indicator settles at its resting place and spins. */
    public void showRefreshing(String description) {
        pulling = false;
        armed = false;
        hidePillNow();
        indicator.setContentDescription(description);
        indicator.animate().cancel();
        if (!refreshing || indicator.getVisibility() != VISIBLE) {
            if (indicator.getVisibility() != VISIBLE) {
                // started without a pull (menu button, tab tapped again): pop in at the resting place
                indicator.setVisibility(VISIBLE);
                indicator.setTranslationY(dp(REST_DP));
                indicator.setAlpha(0f);
                indicator.setScaleX(0.6f);
                indicator.setScaleY(0.6f);
            }
            indicator.animate().translationY(dp(REST_DP)).alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
        refreshing = true;
        indicator.setSpinning(true);
    }

    /**
     * The refresh ended (or there is none for the list now shown).
     *
     * @param result shown in the pill for a moment; null for nothing
     */
    public void finish(String result) {
        pulling = false;
        armed = false;
        boolean wasShown = indicator.getVisibility() == VISIBLE;
        refreshing = false;
        indicator.animate().cancel();
        if (wasShown) {
            indicator.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                .withEndAction(() -> {
                    indicator.setSpinning(false);
                    indicator.setVisibility(INVISIBLE);
                    indicator.setScaleX(1f);
                    indicator.setScaleY(1f);
                }).start();
        } else {
            indicator.setSpinning(false);
        }
        if (result != null) showPill(result, wasShown ? 120 : 0);
    }

    private void showPill(String text, long delay) {
        removeCallbacks(hidePill);
        pill.animate().cancel();
        pill.setText(text);
        pill.setVisibility(VISIBLE);
        pill.setAlpha(0f);
        pill.setScaleX(0.92f);
        pill.setScaleY(0.92f);
        pill.setTranslationY(-dp(10));
        pill.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(delay).setDuration(220)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(null).start();
        announceForAccessibility(text);
        postDelayed(hidePill, PILL_MS + delay);
    }

    private void hidePill() {
        pill.animate().cancel();
        pill.animate().alpha(0f).translationY(-dp(8)).setStartDelay(0).setDuration(220)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT)
            .withEndAction(() -> pill.setVisibility(INVISIBLE)).start();
    }

    private void hidePillNow() {
        removeCallbacks(hidePill);
        if (pill.getVisibility() == VISIBLE) {
            pill.animate().cancel();
            pill.setVisibility(INVISIBLE);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(hidePill);
    }

    // ---------------------------------------------------------------- indicator

    /** The round indicator: an arc that fills with the pull, then spins while refreshing. */
    private final class Indicator extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float pull;
        private boolean spinning;
        private long spinStart;

        Indicator(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(2.5f));
        }

        void setPull(float progress) {
            pull = progress;
            if (spinning) spinning = false;
            invalidate();
        }

        void setSpinning(boolean value) {
            if (spinning == value) return;
            spinning = value;
            spinStart = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = dp(11);
            oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
            if (spinning) {
                long t = SystemClock.uptimeMillis() - spinStart;
                float start = (t * 0.4f) % 360f;
                float sweep = 150f + 110f * (float) Math.sin(t / 380.0);
                canvas.drawArc(oval, start - 90f, sweep, false, paint);
                postInvalidateOnAnimation();
            } else {
                paint.setAlpha((int) (255 * (0.4f + 0.6f * pull)));
                canvas.drawArc(oval, -90f + 270f * pull, 20f + 290f * pull, false, paint);
            }
        }
    }
}

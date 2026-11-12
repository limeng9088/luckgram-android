package org.telegram.luckgram.square;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import org.telegram.ui.ActionBar.Theme;

/**
 * Placeholder shown while the first page of a Square tab loads: a few grey post outlines (avatar,
 * name, text lines, picture) that pulse gently, so the page has its shape at once instead of an
 * empty screen with a spinner.
 */
public class SquareSkeletonView extends View {

    private static final int CARDS = 3;
    private static final int CARD_DP = 262;
    private static final int GAP_DP = 8;

    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint block = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Theme.ResourcesProvider resourcesProvider;

    public SquareSkeletonView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(CARDS * (CARD_DP + GAP_DP)));
    }

    private void bar(Canvas canvas, float left, float top, float width, float height) {
        rect.set(left, top, left + width, top + height);
        canvas.drawRoundRect(rect, height / 2f, height / 2f, block);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        card.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        block.setColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
        // one slow pulse for the whole page
        double phase = (SystemClock.uptimeMillis() % 1200L) / 1200.0 * 2 * Math.PI;
        block.setAlpha((int) (255 * (0.72 + 0.28 * Math.sin(phase))));
        float side = dp(16);
        float inner = w - 2 * side;
        for (int i = 0; i < CARDS; i++) {
            float top = i * dp(CARD_DP + GAP_DP);
            canvas.drawRect(0, top, w, top + dp(CARD_DP), card);
            canvas.drawCircle(side + dp(20), top + dp(32), dp(20), block);
            bar(canvas, side + dp(50), top + dp(18), dp(120), dp(12));
            bar(canvas, side + dp(50), top + dp(38), dp(72), dp(10));
            bar(canvas, side, top + dp(68), inner, dp(12));
            bar(canvas, side, top + dp(88), inner, dp(12));
            bar(canvas, side, top + dp(108), inner * 0.6f, dp(12));
            rect.set(side, top + dp(132), w - side, top + dp(246));
            canvas.drawRoundRect(rect, dp(8), dp(8), block);
        }
        if (isShown()) postInvalidateOnAnimation();
    }
}

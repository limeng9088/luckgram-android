package org.telegram.luckgram.square;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Plain tab bar for the Square feed ("Latest" / "Popular"): equal-width text tabs on a white row,
 * the selected one highlighted with an underline that slides when the selection changes
 * (acceptance P2: the earlier slider control looked like a settings slider, not tabs).
 */
public class SquareTabsView extends FrameLayout {

    private static final int HEIGHT_DP = 44;

    private final LinearLayout row;
    private final TextView[] labels;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Theme.ResourcesProvider resourcesProvider;
    private int selected;
    /** Indicator position in tab units (0 = first tab), animated towards {@link #selected}. */
    private float indicator;
    private ValueAnimator animator;

    public SquareTabsView(Context context, String[] titles, int selected, Utilities.Callback<Integer> onSelect, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.selected = selected;
        this.indicator = selected;
        setWillNotDraw(false);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        labels = new TextView[titles.length];
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            TextView tv = new TextView(context);
            tv.setText(titles[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            tv.setTypeface(AndroidUtilities.bold());
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_profile_tabSelector, resourcesProvider)));
            tv.setOnClickListener(v -> {
                if (index == this.selected) return;
                select(index, true);
                onSelect.run(index);
            });
            labels[i] = tv;
            row.addView(tv, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateColors();
    }

    /** Moves the highlight to {@code index} without notifying the callback. */
    public void select(int index, boolean animated) {
        if (index < 0 || index >= labels.length) return;
        selected = index;
        updateColors();
        if (animator != null) animator.cancel();
        if (!animated) {
            indicator = index;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(indicator, index);
        animator.setDuration(220);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> {
            indicator = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public int getSelected() {
        return selected;
    }

    private void updateColors() {
        for (int i = 0; i < labels.length; i++) {
            labels[i].setTextColor(Theme.getColor(i == selected ? Theme.key_profile_tabSelectedText : Theme.key_profile_tabText, resourcesProvider));
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (labels.length == 0) return;
        int height = getMeasuredHeight();
        // thin divider under the whole row
        canvas.drawRect(0, height - 1, getMeasuredWidth(), height, Theme.dividerPaint);
        // underline under the selected tab, a little narrower than the tab itself
        float tabWidth = (float) getMeasuredWidth() / labels.length;
        float inset = dp(16);
        float left = indicator * tabWidth + inset;
        float right = left + tabWidth - 2 * inset;
        linePaint.setColor(Theme.getColor(Theme.key_profile_tabSelectedLine, resourcesProvider));
        float thickness = dp(2.5f);
        canvas.drawRoundRect(left, height - thickness, right, height, thickness / 2, thickness / 2, linePaint);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}

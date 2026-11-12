package org.telegram.luckgram.compactinput;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Luckgram specs#94: the WeChat-style 「+」 panel that takes the keyboard's place under the input
 * bar. A grid of 2 rows × 4 columns per page; more items page sideways with page dots.
 * specs#147: colored icon squares, the grid centered in the panel, press and entrance motion.
 * The host ({@code ChatActivityEnterView}) decides which items exist and handles the taps.
 */
@SuppressLint("ViewConstructor")
public class LuckgramPlusPanel extends FrameLayout {

    public static final class Item {
        public final int id;
        public final int icon;
        public final CharSequence title;
        /** Shown dimmed (e.g. no permission to send media); still tappable so the host can explain why. */
        public final boolean dimmed;
        /** Position in the color table of {@link LuckgramPlusPanelColors}; the host's ids start at 1. */
        final int colorIndex;

        public Item(int id, int icon, CharSequence title, boolean dimmed) {
            this.id = id;
            this.icon = icon;
            this.title = title;
            this.dimmed = dimmed;
            this.colorIndex = id - 1;
        }
    }

    public interface Delegate {
        void onItemClick(Item item);
    }

    private static final int COLUMNS = 4;
    private static final int ROWS = 2;
    private static final int PER_PAGE = COLUMNS * ROWS;
    /** dp: icon square 60 + gap 7 + one title line 16. */
    private static final int CELL_HEIGHT = 83;
    private static final int ROW_SPACING = 20;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;
    private final ViewPager pager;
    private final DotsView dots;
    private final ArrayList<Item> items = new ArrayList<>();
    private int pageCount;
    /** The panel is about to slide in: the cells of the page in view come up one after another. */
    private boolean animateCells;
    private final Runnable stopAnimatingCells = () -> animateCells = false;

    public LuckgramPlusPanel(Context context, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;

        pager = new ViewPager(context);
        pager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return pageCount;
            }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                View page = createPage(position);
                container.addView(page);
                return page;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }

            @Override
            public int getItemPosition(@NonNull Object object) {
                return POSITION_NONE;
            }
        });
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                dots.setProgress(position + positionOffset);
            }
        });
        addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 38));

        dots = new DotsView(context);
        // kept clear of the system gesture bar at the bottom edge
        addView(dots, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 22, Gravity.BOTTOM, 0, 0, 0, 16));

        updateColors();
    }

    public void setItems(List<Item> newItems) {
        items.clear();
        items.addAll(newItems);
        animateCells = getVisibility() != VISIBLE;
        removeCallbacks(stopAnimatingCells);
        if (animateCells) {
            postDelayed(stopAnimatingCells, 600);
        }
        pageCount = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
        dots.setCount(pageCount);
        dots.setVisibility(pageCount > 1 ? VISIBLE : INVISIBLE);
        pager.getAdapter().notifyDataSetChanged();
        if (pager.getCurrentItem() >= pageCount) {
            pager.setCurrentItem(0, false);
        }
        dots.setProgress(pager.getCurrentItem());
    }

    public void updateColors() {
        setBackgroundColor(getThemedColor(Theme.key_chat_emojiPanelBackground));
        dots.invalidate();
        if (pager.getAdapter() != null) {
            pager.getAdapter().notifyDataSetChanged();
        }
    }

    private View createPage(int page) {
        LinearLayout column = new LinearLayout(getContext()) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // the two rows sit in the middle of the panel
                final int free = MeasureSpec.getSize(heightMeasureSpec) - dp(ROWS * CELL_HEIGHT + (ROWS - 1) * ROW_SPACING);
                setPadding(dp(12), Math.max(dp(10), free / 2), dp(12), 0);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        // rows start at the same line on every page, also when the last page has one row only
        column.setGravity(Gravity.TOP);

        column.setClipChildren(false);
        final boolean animate = animateCells && page == pager.getCurrentItem();
        if (animate) {
            animateCells = false;
        }
        for (int r = 0; r < ROWS; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            for (int c = 0; c < COLUMNS; c++) {
                int index = page * PER_PAGE + r * COLUMNS + c;
                final boolean filled = index < items.size();
                View cell = filled ? createCell(items.get(index)) : new View(getContext());
                // an empty slot only keeps the column width: a plain View with WRAP_CONTENT takes all the height on offer,
                // which made a half-filled page as tall as two panels and pushed its row out of the top
                row.addView(cell, LayoutHelper.createLinear(0, filled ? LayoutHelper.WRAP_CONTENT : 0, 1f, Gravity.TOP));
                if (animate && index < items.size()) {
                    final float alpha = cell.getAlpha();
                    cell.setAlpha(0f);
                    cell.setScaleX(0.82f);
                    cell.setScaleY(0.82f);
                    cell.setTranslationY(dp(10));
                    cell.animate().alpha(alpha).scaleX(1f).scaleY(1f).translationY(0f)
                            .setStartDelay(40 + (r + c) * 28L).setDuration(240)
                            .setInterpolator(new OvershootInterpolator(1.6f)).start();
                }
            }
            column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, r == 0 ? 0 : ROW_SPACING, 0, 0));
        }
        return column;
    }

    private View createCell(Item item) {
        final boolean dark = isDark();

        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setContentDescription(item.title);

        ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(16), dp(16), dp(16), dp(16));
        icon.setImageResource(item.icon);
        icon.setColorFilter(new PorterDuffColorFilter(LuckgramPlusPanelColors.icon(dark), PorterDuff.Mode.SRC_IN));
        GradientDrawable square = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, LuckgramPlusPanelColors.square(item.colorIndex));
        square.setCornerRadius(dp(18));
        icon.setBackground(square);
        cell.addView(icon, LayoutHelper.createLinear(60, 60, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(getContext());
        title.setText(item.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        title.setTextColor(LuckgramPlusPanelColors.title(dark));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 2, 7, 2, 0));

        cell.setAlpha(item.dimmed ? LuckgramPlusPanelColors.DIMMED_ALPHA : 1f);
        // pressed: the square sinks a little and springs back, no grey box around the whole cell
        cell.setOnTouchListener((v, event) -> {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                icon.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).setInterpolator(null).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                icon.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
            }
            return false;
        });
        cell.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onItemClick(item);
            }
        });
        return cell;
    }

    /** Dark when the panel background is dark (follows per-chat themes too), like iOS's overallDarkAppearance. */
    private boolean isDark() {
        return ColorUtils.calculateLuminance(getThemedColor(Theme.key_chat_emojiPanelBackground) | 0xFF000000) < 0.5;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count;
        private float progress;

        DotsView(Context context) {
            super(context);
        }

        void setCount(int count) {
            this.count = count;
            invalidate();
        }

        void setProgress(float progress) {
            this.progress = progress;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (count <= 1) {
                return;
            }
            final float r = dp(3);
            final float gap = dp(10);
            final float total = (count - 1) * gap;
            final float x0 = (getMeasuredWidth() - total) / 2f;
            final float cy = getMeasuredHeight() / 2f;
            final boolean dark = isDark();
            final int active = LuckgramPlusPanelColors.dotCurrent(dark);
            final int inactive = LuckgramPlusPanelColors.dotOther(dark);
            for (int i = 0; i < count; i++) {
                float d = Math.min(1f, Math.abs(progress - i));
                paint.setColor(ColorUtils.blendARGB(active, inactive, d));
                canvas.drawCircle(x0 + i * gap, cy, r, paint);
            }
        }
    }
}

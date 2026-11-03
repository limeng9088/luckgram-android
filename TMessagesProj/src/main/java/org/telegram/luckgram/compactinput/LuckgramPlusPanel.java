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
import android.view.View;
import android.view.ViewGroup;
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

        public Item(int id, int icon, CharSequence title, boolean dimmed) {
            this.id = id;
            this.icon = icon;
            this.title = title;
            this.dimmed = dimmed;
        }
    }

    public interface Delegate {
        void onItemClick(Item item);
    }

    private static final int COLUMNS = 4;
    private static final int ROWS = 2;
    private static final int PER_PAGE = COLUMNS * ROWS;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;
    private final ViewPager pager;
    private final DotsView dots;
    private final ArrayList<Item> items = new ArrayList<>();
    private int pageCount;

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
        addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 22));

        dots = new DotsView(context);
        addView(dots, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 22, Gravity.BOTTOM));

        updateColors();
    }

    public void setItems(List<Item> newItems) {
        items.clear();
        items.addAll(newItems);
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
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), dp(14), dp(12), 0);
        for (int r = 0; r < ROWS; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < COLUMNS; c++) {
                int index = page * PER_PAGE + r * COLUMNS + c;
                View cell = index < items.size() ? createCell(items.get(index)) : new View(getContext());
                row.addView(cell, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.TOP));
            }
            column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, r == 0 ? 0 : 14, 0, 0));
        }
        return column;
    }

    private View createCell(Item item) {
        final int bg = getThemedColor(Theme.key_chat_emojiPanelBackground);
        final int text = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int iconColor = getThemedColor(Theme.key_chat_emojiPanelIcon);

        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setContentDescription(item.title);

        ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(item.icon);
        icon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        GradientDrawable square = new GradientDrawable();
        square.setCornerRadius(dp(14));
        square.setColor(ColorUtils.blendARGB(bg, text, Theme.isCurrentThemeDark() ? 0.12f : 0.06f));
        icon.setBackground(square);
        cell.addView(icon, LayoutHelper.createLinear(58, 58, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(getContext());
        title.setText(item.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 2, 6, 2, 0));

        cell.setAlpha(item.dimmed ? 0.4f : 1f);
        cell.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        cell.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onItemClick(item);
            }
        });
        return cell;
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
            final int active = getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
            final int inactive = ColorUtils.setAlphaComponent(active, 0x55);
            for (int i = 0; i < count; i++) {
                float d = Math.min(1f, Math.abs(progress - i));
                paint.setColor(ColorUtils.blendARGB(active, inactive, d));
                canvas.drawCircle(x0 + i * gap, cy, r, paint);
            }
        }
    }
}

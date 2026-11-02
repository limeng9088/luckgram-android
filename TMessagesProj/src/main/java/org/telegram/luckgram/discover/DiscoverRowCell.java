package org.telegram.luckgram.discover;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/** A Discover tab row (spec 27): colourful icon, title, one-line description and a chevron. */
public class DiscoverRowCell extends LinearLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private final GradientDrawable iconBackground;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final ImageView arrowView;
    private boolean needDivider;

    public DiscoverRowCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);

        FrameLayout iconLayout = new FrameLayout(context);
        iconBackground = new GradientDrawable();
        iconBackground.setCornerRadius(dp(9));
        iconLayout.setBackground(iconBackground);
        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconView.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        iconLayout.addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(VERTICAL);
        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setSingleLine(true);
        textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setSingleLine(true);
        textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        arrowView = new ImageView(context);
        arrowView.setImageResource(R.drawable.msg_arrowright);
        arrowView.setScaleType(ImageView.ScaleType.CENTER);

        if (LocaleController.isRTL) {
            arrowView.setScaleX(-1);
            addView(arrowView, LayoutHelper.createLinear(24, 24, 0, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
            addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL, 12, 0, 16, 0));
            addView(iconLayout, LayoutHelper.createLinear(34, 34, 0, Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
        } else {
            addView(iconLayout, LayoutHelper.createLinear(34, 34, 0, Gravity.CENTER_VERTICAL, 18, 0, 0, 0));
            addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL, 16, 0, 12, 0));
            addView(arrowView, LayoutHelper.createLinear(24, 24, 0, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        }
        updateColors();
    }

    private void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        arrowView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.SRC_IN));
    }

    public void set(int entry, boolean divider) {
        iconBackground.setColors(new int[]{DiscoverEntries.iconColorTop(entry), DiscoverEntries.iconColorBottom(entry)});
        iconBackground.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        iconView.setImageResource(DiscoverEntries.icon(entry));
        titleView.setText(DiscoverEntries.title(entry));
        subtitleView.setText(DiscoverEntries.subtitle(entry));
        needDivider = divider;
        updateColors();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider && Theme.dividerPaint != null) {
            int left = LocaleController.isRTL ? 0 : dp(68);
            int right = LocaleController.isRTL ? getWidth() - dp(68) : getWidth();
            canvas.drawLine(left, getHeight() - 1, right, getHeight() - 1, Theme.dividerPaint);
        }
    }

    public static class Factory extends UItem.UItemFactory<DiscoverRowCell> {
        static { setup(new Factory()); }

        @Override
        public DiscoverRowCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new DiscoverRowCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((DiscoverRowCell) view).set(item.intValue, divider);
        }

        /** {@code entry}: a {@link DiscoverEntries} constant; the item id is {@code idBase + entry}. */
        public static UItem of(int idBase, int entry) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = idBase + entry;
            item.intValue = entry;
            return item;
        }
    }
}

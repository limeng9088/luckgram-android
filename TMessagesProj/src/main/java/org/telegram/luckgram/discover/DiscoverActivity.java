package org.telegram.luckgram.discover;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.core.view.ViewCompat;

import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * The Discover tab (spec 27): the third main tab, holding the entries of People Nearby (25) and
 * Square (26). Hosted by {@link MainTabsActivity} with {@code hasMainTabs}; it can also be pushed
 * as an ordinary page (then it has a back button). Rows follow the feature switches live.
 */
public class DiscoverActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private static final int ID_ENTRY_BASE = 100;

    private boolean hasMainTabs;
    private UniversalRecyclerView listView;
    private int navigationBarHeight;

    public DiscoverActivity() {
        this(null);
    }

    public DiscoverActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.luckgramSettingsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.luckgramSettingsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean hasOwnBackground() {
        return true;
    }

    @Override
    public View createView(Context context) {
        if (!hasMainTabs) {
            actionBar.setBackButtonDrawable(new BackDrawable(false));
        }
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(DiscoverEntries.pageTitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        listView.setClipToPadding(false);

        SizeNotifierFrameLayout contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                if (actionBar != null) {
                    measureChildWithMargins(actionBar, widthMeasureSpec, 0, heightMeasureSpec, 0);
                }
                checkUi_listViewPadding();
                super.onMeasure(
                    View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(heightMeasureSpec), View.MeasureSpec.EXACTLY)
                );
            }
        };
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = contentView;
        // As a tab page the view fills the whole page (under the action bar and the tab bar): the
        // insets arrive through the pager, like CallLogActivity / ContactsActivity.
        ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onInsetsInternal);
        return fragmentView;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        navigationBarHeight = bottom;
        checkUi_listViewPadding();
    }

    private void checkUi_listViewPadding() {
        if (listView == null) return;
        int topPadding = (actionBar != null ? actionBar.getMeasuredHeight() : 0) + dp(8);
        int bottomPadding = navigationBarHeight + (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0);
        if (listView.getPaddingTop() != topPadding || listView.getPaddingBottom() != bottomPadding) {
            listView.setPadding(0, topPadding, 0, bottomPadding);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        List<Integer> entries = DiscoverEntries.visible();
        if (entries.isEmpty()) {
            items.add(UItem.asCenterShadow(DiscoverEntries.emptyText()));
            return;
        }
        for (int entry : entries) {
            items.add(DiscoverRowCell.Factory.of(ID_ENTRY_BASE, entry));
        }
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id > ID_ENTRY_BASE) {
            DiscoverEntries.open(this, item.id - ID_ENTRY_BASE);
        }
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.luckgramSettingsChanged) {
            update();
        }
    }

    // ---------------------------------------------------------------- main tab

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    @Override
    public boolean canParentTabsSlide(android.view.MotionEvent ev, boolean forward) {
        return true;
    }
}

package org.telegram.luckgram.square;

import android.content.Context;
import android.view.View;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Square feed (spec 26, luckgram-specs#64): "Latest" / "Popular" / "For you" tabs, 20 per page with
 * a cursor, compose. Browsing needs no link; writes go through {@link SquareUi#requireLink}.
 *
 * <p>The list is never emptied to reload. A refresh (pull down, menu button, tapping the current
 * tab) keeps the posts on screen under a spinning indicator and swaps the new page in when it has
 * arrived; a failure leaves everything as it was. Each tab keeps its own list and position
 * ({@link SquareLogic.Feed}); the first load shows post outlines; further pages load by themselves
 * shortly before the end of the list.
 */
public class SquareActivity extends UniversalFragment {

    private static final int MENU_COMPOSE = 1;
    private static final int MENU_REFRESH = 2;
    private static final int MENU_PROFILE = 3;
    private static final int ID_RETRY = 2;
    private static final int ID_TABS = 3;
    private static final int ID_SKELETON = 4;
    private static final int ID_FOOTER = 10; // + SquareLogic.Feed.FOOTER_*
    private static final int ID_POST = 1000;

    private final SquareLogic.Texts tx = SquareUi.tx();
    /**
     * One list per tab. Kept for as long as the app runs, so closing the page and opening it again
     * shows the same posts at the same place without a request (unless older than 5 minutes).
     * The first page of each tab is also saved on the device: after a restart the page opens with
     * those posts and refreshes over them.
     */
    private static final HashMap<String, SquareLogic.Feed> feeds = new HashMap<>();
    private static final String KEY_SAVED = "square_feed_page_";
    private SquareLogic.Feed feed = feedFor(SquareLogic.TAB_LATEST);
    private final HashMap<SquareLogic.Feed, LuckgramAI.Request> refreshRequests = new HashMap<>();
    private final HashMap<SquareLogic.Feed, LuckgramAI.Request> moreRequests = new HashMap<>();
    /** Gives up on a page that does not come ({@link SquareLogic.Feed#REQUEST_TIMEOUT_MS}); one per running request. */
    private final HashMap<LuckgramAI.Request, Runnable> timeouts = new HashMap<>();

    private void startTimeout(LuckgramAI.Request request, Runnable onTimeout) {
        if (request == null) return;
        Runnable r = () -> {
            if (timeouts.remove(request) == null || request.isCancelled()) return;
            request.cancel();
            onTimeout.run();
        };
        timeouts.put(request, r);
        AndroidUtilities.runOnUIThread(r, SquareLogic.Feed.REQUEST_TIMEOUT_MS);
    }

    private void stopTimeout(LuckgramAI.Request request) {
        Runnable r = request == null ? null : timeouts.remove(request);
        if (r != null) AndroidUtilities.cancelRunOnUIThread(r);
    }
    private SquareTabsView tabs;
    private SquareRefreshView refreshView;
    private SquareSkeletonView skeleton;
    private final View[] footers = new View[6];

    /** Posts whose counts changed on other pages (a like, a comment) → their state there; applied in place on return. */
    public static final HashMap<String, SquareLogic.Post> updatedPosts = new HashMap<>();

    /** Called by the post / author pages when a post's like or comment count changed there. */
    static void noteChanged(SquareLogic.Post p) {
        if (p != null && !p.id.isEmpty()) updatedPosts.put(p.id, p);
    }

    /** A new post, or all own data deleted: the first page is different now, so the feed goes back to the top and refreshes. */
    public static boolean posted;
    /** Posts deleted / found gone on other pages; taken out of the lists on return, no reload. */
    public static final HashSet<String> removedPosts = new HashSet<>();
    /** Authors blocked on other pages; their posts are taken out on return. */
    public static final HashSet<String> blockedAuthors = new HashSet<>();

    private static final String[] TAB_ORDER = {SquareLogic.TAB_LATEST, SquareLogic.TAB_HOT, SquareLogic.TAB_RECOMMEND};

    private static int tabIndex(String tab) {
        for (int i = 0; i < TAB_ORDER.length; i++) if (TAB_ORDER[i].equals(tab)) return i;
        return 0;
    }

    private static SquareLogic.Feed feedFor(String tab) {
        SquareLogic.Feed f = feeds.get(tab);
        if (f == null) {
            feeds.put(tab, f = new SquareLogic.Feed(tab));
            SquareLogic.Saved saved = SquareLogic.decodeSaved(org.telegram.luckgram.LuckgramSettings.getString(KEY_SAVED + tab, null), System.currentTimeMillis());
            if (saved != null) f.restore(saved.page, saved.savedAtMs);
        }
        return f;
    }

    /** The saved first pages no longer match (a post deleted, an author blocked): the next start loads fresh ones. */
    private static void dropSavedPages() {
        for (String tab : TAB_ORDER) org.telegram.luckgram.LuckgramSettings.remove(KEY_SAVED + tab);
    }

    /** Forgets every list, in memory and on the device (unlink, logout, "delete my Square data"). */
    public static void clearFeeds() {
        feeds.clear();
        dropSavedPages();
    }

    // ---------------------------------------------------------------- post cards

    /** A built post card, reused until the post's content changes (no rebuild, no image flicker on list updates). */
    private static final class Card {
        View view;
        String signature;
        int itemId;
    }

    private final IdentityHashMap<SquareLogic.Post, Card> cards = new IdentityHashMap<>();
    private int nextItemId = ID_POST;

    private static String signature(SquareLogic.Post p) {
        return p.liked + "|" + p.likeCount + "|" + p.commentCount + "|" + p.status + "|" + p.hiddenReason + "|" + p.text.hashCode();
    }

    private Card cardFor(SquareLogic.Post p) {
        Card c = cards.get(p);
        String signature = signature(p);
        if (c == null || !signature.equals(c.signature)) {
            if (c == null) cards.put(p, c = new Card());
            c.view = SquareUi.postView(getContext(), p, false, actions);
            c.signature = signature;
            c.itemId = nextItemId++;
            postViews.put(c.view, p);
        }
        return c;
    }

    /** Forgets the cards of posts that are in no tab any more. */
    private void pruneCards() {
        int live = 0;
        for (SquareLogic.Feed f : feeds.values()) live += f.posts.size();
        if (cards.size() <= live) return;
        IdentityHashMap<SquareLogic.Post, Boolean> keep = new IdentityHashMap<>();
        for (SquareLogic.Feed f : feeds.values()) for (SquareLogic.Post p : f.posts) keep.put(p, true);
        cards.keySet().retainAll(keep.keySet());
    }

    // ---------------------------------------------------------------- specs#115 behavior events

    /** Impressions / views per tab; a refresh starts a new one (contract: once per load). */
    private final HashMap<SquareLogic.Feed, SquareEvents.Tracker> trackers = new HashMap<>();
    /** Post views bound to the list → their post. */
    private final java.util.WeakHashMap<View, SquareLogic.Post> postViews = new java.util.WeakHashMap<>();
    private final Runnable checkVisible = this::trackVisible;

    private SquareEvents.Tracker tracker(SquareLogic.Feed f) {
        SquareEvents.Tracker t = trackers.get(f);
        if (t == null) trackers.put(f, t = new SquareEvents.Tracker(f.tab));
        return t;
    }

    /** Posts at least half visible → their position; reported as impressions / views. */
    private void trackVisible() {
        if (listView == null || isPaused || feed.posts.isEmpty()) return;
        java.util.HashMap<String, Integer> visible = new java.util.HashMap<>();
        int h = listView.getHeight();
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            View content = child instanceof android.view.ViewGroup && ((android.view.ViewGroup) child).getChildCount() == 1
                ? ((android.view.ViewGroup) child).getChildAt(0) : child;
            SquareLogic.Post p = postViews.get(content);
            if (p == null) p = postViews.get(child);
            if (p == null || child.getHeight() <= 0) continue;
            int shown = Math.min(child.getBottom(), h) - Math.max(child.getTop(), 0);
            if (shown * 2 >= child.getHeight()) {
                int rank = feed.posts.indexOf(p);
                if (rank >= 0) visible.put(p.id, rank);
            }
        }
        SquareEventReporter.add(tracker(feed).update(visible, System.currentTimeMillis()));
    }

    private void scheduleTrack() {
        AndroidUtilities.cancelRunOnUIThread(checkVisible);
        AndroidUtilities.runOnUIThread(checkVisible, 300);
    }

    private void event(String type, SquareLogic.Post p) {
        Integer rank = tracker(feed).rankOf(p.id);
        SquareEventReporter.add(new SquareEvents.Event(type, p.id, feed.tab, rank == null ? feed.posts.indexOf(p) : rank, 0, System.currentTimeMillis()));
    }

    // ---------------------------------------------------------------- page

    /** "Latest" / "Popular" / "For you" as a plain tab bar with an underline (acceptance P2). One instance, reused across list updates. */
    private SquareTabsView tabsView() {
        if (tabs == null) {
            // specs#115: 最新 / 热门 / 推荐 (default stays 最新)
            tabs = new SquareTabsView(getContext(), new String[]{tx.latest(), tx.hot(), tx.recommend()}, tabIndex(feed.tab), which -> {
                selectTab(TAB_ORDER[Math.max(0, Math.min(TAB_ORDER.length - 1, which))]);
            }, getResourceProvider());
            // the tab that is already open: back to the top and refresh, as in every feed app
            tabs.setOnReselect(this::refreshByUser);
        }
        return tabs;
    }

    @Override
    protected CharSequence getTitle() {
        return tx.title();
    }

    @Override
    public View createView(Context context) {
        tabs = null;
        skeleton = null;
        java.util.Arrays.fill(footers, null);
        cards.clear();
        View v = super.createView(context);
        actionBar.setAllowOverlayTitle(false); // keep our title; the chat list shows the connection state
        // The tab row stays at the top while the list scrolls under it, so any tab is one tap away
        // from anywhere in the list (and each tab's position is worth keeping).
        android.widget.FrameLayout root = (android.widget.FrameLayout) v;
        ((android.widget.FrameLayout.LayoutParams) listView.getLayoutParams()).topMargin = AndroidUtilities.dp(44);
        root.addView(tabsView(), org.telegram.ui.Components.LayoutHelper.createFrame(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, 44, android.view.Gravity.TOP));
        // Pull down at the top of the list to refresh (spec 26 item 2); the menu button does the same.
        refreshView = new SquareRefreshView(context, this::refreshByUser, getResourceProvider());
        // below the tab row
        root.addView(refreshView, org.telegram.ui.Components.LayoutHelper.createFrame(
            org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, SquareRefreshView.heightDp(), android.view.Gravity.TOP, 0, 44, 0, 0));
        refreshView.attach(listView);
        installSideSwipeGuard();
        // back where the reader was (the list of the last visit)
        if (feed.scrollIndex > 0 || feed.scrollOffset != 0) listView.layoutManager.scrollToPositionWithOffset(feed.scrollIndex, feed.scrollOffset);
        if (feed.refreshing && !feed.posts.isEmpty()) refreshView.showRefreshing(tx.refreshing());
        listView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                scheduleTrack();
                maybeLoadMore();
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_REFRESH, R.drawable.msg_retry).setContentDescription(tx.refresh());
        menu.addItem(MENU_COMPOSE, R.drawable.msg_edit).setContentDescription(tx.compose());
        menu.addItem(MENU_PROFILE, R.drawable.msg_contacts).setContentDescription(tx.editProfile());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_REFRESH) {
                    refreshByUser();
                } else if (id == MENU_COMPOSE) {
                    SquareUi.requireProfile(SquareActivity.this, () -> presentFragment(new SquareComposeActivity()));
                } else if (id == MENU_PROFILE) {
                    SquareUi.openProfileEditor(SquareActivity.this);
                }
            }
        });
        return v;
    }

    @Override
    public boolean onFragmentCreate() {
        SquareUi.prefetchMe();
        posted = false;
        updatedPosts.clear();
        removedPosts.clear();
        blockedAuthors.clear();
        // whatever an earlier page was loading went away with it
        for (SquareLogic.Feed f : feeds.values()) f.cancelLoading();
        // posts from the last visit (or the saved first page) are already there; ask only when they are old
        if (feed.isStale(System.currentTimeMillis())) refresh(feed, false);
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        // what other Square pages did while this one was covered: applied in place, the list stays where it is
        if (!updatedPosts.isEmpty()) {
            ArrayList<SquareLogic.Post> changed = new ArrayList<>(updatedPosts.values());
            for (SquareLogic.Feed f : feeds.values()) f.mergeSilently(changed);
            updatedPosts.clear();
        }
        if (!removedPosts.isEmpty() || !blockedAuthors.isEmpty()) {
            dropSavedPages();
            for (SquareLogic.Feed f : feeds.values()) {
                for (String id : removedPosts) f.removePost(id);
                for (String id : blockedAuthors) f.removeAuthor(id);
            }
            removedPosts.clear();
            blockedAuthors.clear();
        }
        update();
        if (posted) {
            // a new post: every tab is out of date; show this one from the top
            posted = false;
            for (SquareLogic.Feed f : feeds.values()) f.loadedAtMs = 0;
            refreshByUser();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // a view ends when the page goes away; send what we have (contract: also on background)
        AndroidUtilities.cancelRunOnUIThread(checkVisible);
        SquareEventReporter.add(tracker(feed).hideAll(System.currentTimeMillis()));
        SquareEventReporter.flush(true);
        saveScroll();
        feed.markSeen(System.currentTimeMillis());
    }

    /**
     * A sideways drag on a post is not a tap: without this the card under the finger opened its
     * post when the finger was lifted (a drag to the right is the system's "back" gesture already).
     */
    private void installSideSwipeGuard() {
        final int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        listView.addOnItemTouchListener(new androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
            private float downX, downY;

            @Override
            public boolean onInterceptTouchEvent(androidx.recyclerview.widget.RecyclerView rv, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                    float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                    // taking the gesture sends "cancel" to the card; the rest of it does nothing
                    return dx > slop && dx > dy * 1.5f;
                }
                return false;
            }
        });
    }

    /** Remembers where the reader is in the open tab. */
    private void saveScroll() {
        if (listView == null) return;
        int position = listView.layoutManager.findFirstVisibleItemPosition();
        View first = position < 0 ? null : listView.layoutManager.findViewByPosition(position);
        feed.scrollIndex = Math.max(0, position);
        feed.scrollOffset = first == null ? 0 : first.getTop() - listView.getPaddingTop();
    }

    @Override
    public void onFragmentDestroy() {
        for (LuckgramAI.Request r : refreshRequests.values()) r.cancel();
        for (LuckgramAI.Request r : moreRequests.values()) r.cancel();
        refreshRequests.clear();
        moreRequests.clear();
        for (Runnable r : timeouts.values()) AndroidUtilities.cancelRunOnUIThread(r);
        timeouts.clear();
        for (SquareLogic.Feed f : feeds.values()) f.cancelLoading();
        AndroidUtilities.cancelRunOnUIThread(checkVisible);
        super.onFragmentDestroy();
    }

    private void update() {
        update(true);
    }

    /** @param animated false when the whole list was swapped (refresh, tab change) */
    private void update(boolean animated) {
        if (listView != null) listView.adapter.update(animated);
        scheduleTrack();
    }

    private void scrollToTop() {
        if (listView != null) listView.layoutManager.scrollToPositionWithOffset(0, 0);
    }

    // ---------------------------------------------------------------- tabs

    private void selectTab(String next) {
        if (next.equals(feed.tab)) return;
        // leave the old tab as it is, position included
        SquareEventReporter.add(tracker(feed).hideAll(System.currentTimeMillis()));
        saveScroll();
        feed.markSeen(System.currentTimeMillis());
        feed = feedFor(next);
        update(false);
        if (listView != null) listView.layoutManager.scrollToPositionWithOffset(feed.scrollIndex, feed.scrollOffset);
        if (refreshView != null) {
            if (feed.refreshing && !feed.posts.isEmpty()) refreshView.showRefreshing(tx.refreshing());
            else refreshView.finish(null);
        }
        if (feed.isStale(System.currentTimeMillis())) refresh(feed, false);
    }

    // ---------------------------------------------------------------- loading

    /** Pull down, menu button, or a tap on the open tab: back to the top, refresh, say what came. */
    private void refreshByUser() {
        scrollToTop();
        if (feed.refreshing) {
            if (refreshView != null && !feed.posts.isEmpty()) refreshView.showRefreshing(tx.refreshing());
            return;
        }
        refresh(feed, true);
    }

    /**
     * Loads the first page of {@code f}. Its posts stay on screen meanwhile and are replaced in one
     * step when the page arrives; on failure nothing changes.
     */
    private void refresh(final SquareLogic.Feed f, final boolean byUser) {
        if (!f.beginRefresh()) return;
        LuckgramAI.Request more = moreRequests.remove(f);
        if (more != null) {
            stopTimeout(more);
            more.cancel();
            f.loadingMore = false;
        }
        final boolean hadList = f.loadedOnce && !f.posts.isEmpty();
        if (f == feed) {
            if (refreshView != null) {
                // an empty tab shows post outlines instead of the indicator
                if (!f.posts.isEmpty()) refreshView.showRefreshing(tx.refreshing());
                else refreshView.finish(null);
            }
            update();
        }
        // The refresh failed (an error, no network, or no answer in time): the list is still
        // there; say why it did not update.
        final Utilities.Callback<LuckgramAIError> failed = error -> {
            refreshRequests.remove(f);
            f.failRefresh();
            if (f != feed) return;
            update();
            if (refreshView != null) refreshView.finish(null);
            if (error != null) SquareUi.showError(SquareActivity.this, error);
            else SquareUi.bulletin(SquareActivity.this).createErrorBulletin(tx.loadFailed()).show();
        };
        if (!org.telegram.messenger.ApplicationLoader.isNetworkOnline()) {
            // no connection at all: say so now instead of spinning; a moment later so the indicator is seen
            AndroidUtilities.runOnUIThread(() -> {
                if (f.refreshing && !refreshRequests.containsKey(f)) failed.run(null);
            }, 600);
            return;
        }
        final LuckgramAI.Request[] self = new LuckgramAI.Request[1];
        self[0] = LuckgramAI.getInstance().requestJson("GET", SquareLogic.feedPath(f.tab, null), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                if (self[0] != null && self[0].isCancelled()) return;
                stopTimeout(self[0]);
                refreshRequests.remove(f);
                // a new load: impressions count again (contract: once per load)
                SquareEventReporter.add(tracker(f).hideAll(System.currentTimeMillis()));
                trackers.remove(f);
                long now = System.currentTimeMillis();
                int fresh = f.applyRefresh(SquareLogic.parsePosts(result), now);
                // kept for the next start of the app
                org.telegram.luckgram.LuckgramSettings.setString(KEY_SAVED + f.tab, SquareLogic.encodeSaved(result, now));
                pruneCards();
                if (f != feed) return;
                update(false);
                scrollToTop();
                if (refreshView != null) refreshView.finish(hadList ? tx.refreshed(fresh) : null);
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (self[0] != null && self[0].isCancelled()) return;
                stopTimeout(self[0]);
                failed.run(error);
            }
        });
        if (f.refreshing) {
            refreshRequests.put(f, self[0]);
            startTimeout(self[0], () -> failed.run(null));
        }
    }

    /** The next page, requested a few posts before the end so the reader rarely sees it load. */
    private void maybeLoadMore() {
        if (listView == null) return;
        int last = listView.layoutManager.findLastVisibleItemPosition();
        if (last < 0) return;
        // rows: (post, divider) pairs, then the footer
        if (feed.shouldLoadMore(last / 2)) loadMore(feed);
    }

    private void loadMore(final SquareLogic.Feed f) {
        if (!f.canLoadMore()) return;
        final int generation = f.beginMore();
        if (f == feed) update();
        final LuckgramAI.Request[] self = new LuckgramAI.Request[1];
        self[0] = LuckgramAI.getInstance().requestJson("GET", SquareLogic.feedPath(f.tab, f.nextCursor), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                if (self[0] != null && self[0].isCancelled()) return;
                stopTimeout(self[0]);
                moreRequests.remove(f);
                if (f.applyMore(SquareLogic.parsePosts(result), generation) && f == feed) update();
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (self[0] != null && self[0].isCancelled()) return;
                stopTimeout(self[0]);
                moreRequests.remove(f);
                f.failMore(generation);
                // quiet: the footer turns into "tap to retry"
                if (f == feed) update();
            }
        });
        if (f.loadingMore) {
            moreRequests.put(f, self[0]);
            startTimeout(self[0], () -> {
                moreRequests.remove(f);
                f.failMore(generation);
                if (f == feed) update();
            });
        }
    }

    // ---------------------------------------------------------------- rows

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        for (int i = 0; i < feed.posts.size(); i++) {
            Card c = cardFor(feed.posts.get(i));
            items.add(UItem.asCustom(c.itemId, c.view));
            items.add(UItem.asShadow(null));
        }
        int footer = feed.footer();
        if (footer == SquareLogic.Feed.FOOTER_SKELETON) {
            if (skeleton == null) skeleton = new SquareSkeletonView(getContext(), getResourceProvider());
            items.add(UItem.asCustom(ID_SKELETON, skeleton));
        } else if (footer == SquareLogic.Feed.FOOTER_FIRST_ERROR) {
            items.add(UItem.asCustom(ID_FOOTER + footer, footerView(footer)));
            items.add(UItem.asButton(ID_RETRY, tx.retry()).accent());
        } else {
            items.add(UItem.asCustom(ID_FOOTER + footer, footerView(footer)));
        }
        pruneCards();
    }

    /** The row under the posts: loading the next page / failed, tap to retry / end / empty. One view per kind. */
    private View footerView(int kind) {
        if (footers[kind] != null) return footers[kind];
        Context ctx = getContext();
        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        boolean tall = kind == SquareLogic.Feed.FOOTER_EMPTY || kind == SquareLogic.Feed.FOOTER_FIRST_ERROR;
        row.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(tall ? 72 : 18), AndroidUtilities.dp(24), AndroidUtilities.dp(tall ? 24 : 18));
        String text;
        int color = org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteGrayText;
        if (kind == SquareLogic.Feed.FOOTER_LOADING_MORE) {
            org.telegram.ui.Components.RadialProgressView progress = new org.telegram.ui.Components.RadialProgressView(ctx, getResourceProvider());
            progress.setSize(AndroidUtilities.dp(18));
            progress.setStrokeWidth(2f);
            progress.setProgressColor(org.telegram.ui.ActionBar.Theme.getColor(color, getResourceProvider()));
            row.addView(progress, org.telegram.ui.Components.LayoutHelper.createLinear(20, 20, 0, 0, 8, 0));
            text = tx.loading();
        } else if (kind == SquareLogic.Feed.FOOTER_MORE_ERROR) {
            text = tx.moreFailed();
            color = org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueText4;
            row.setBackground(org.telegram.ui.ActionBar.Theme.getSelectorDrawable(false));
            row.setOnClickListener(v -> loadMore(feed));
        } else if (kind == SquareLogic.Feed.FOOTER_EMPTY) {
            text = tx.empty();
        } else if (kind == SquareLogic.Feed.FOOTER_FIRST_ERROR) {
            text = tx.loadFailed();
        } else {
            text = tx.noMore();
        }
        android.widget.TextView label = new android.widget.TextView(ctx);
        label.setText(text);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, tall ? 15 : 13);
        label.setGravity(android.view.Gravity.CENTER);
        label.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(color, getResourceProvider()));
        row.addView(label, org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT));
        row.setLayoutParams(new android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return footers[kind] = row;
    }

    final SquareUi.PostActions actions = new SquareUi.PostActions() {
        @Override
        public void onAuthor(SquareLogic.Post p) {
            presentFragment(new SquareAuthorActivity(p.author));
        }

        @Override
        public void onOpen(SquareLogic.Post p) {
            event(SquareEvents.OPEN, p);
            presentFragment(new SquarePostActivity(p));
        }

        @Override
        public void onLike(SquareLogic.Post p) {
            SquareUi.toggleLike(SquareActivity.this, p, SquareActivity.this::update);
        }

        @Override
        public void onVideo(SquareLogic.Post p) {
            if (p.video != null) presentFragment(new SquareVideoActivity(p.video));
        }

        @Override
        public void onMore(SquareLogic.Post p) {
            // specs#115: 「不感兴趣」 only in the recommend tab; the post goes away at once
            Runnable notInterested = SquareLogic.TAB_RECOMMEND.equals(feed.tab) ? () -> {
                event(SquareEvents.NOT_INTERESTED, p);
                feed.posts.remove(p);
                dropSavedPages();
                update();
                SquareUi.bulletin(SquareActivity.this).createSimpleBulletin(R.raw.done, tx.notInterestedDone()).show();
            } : null;
            showPostMenu(SquareActivity.this, p, () -> {
                for (SquareLogic.Feed f : feeds.values()) f.removePost(p.id);
                dropSavedPages();
                update();
            }, () -> {
                for (SquareLogic.Feed f : feeds.values()) f.removeAuthor(p.author.id);
                dropSavedPages();
                update();
            }, notInterested);
        }
    };

    static void removeAuthor(List<SquareLogic.Post> list, String authorId) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).author.id.equals(authorId)) list.remove(i);
        }
    }

    /** Post "⋯" menu: delete (own) / report / block author. */
    static void showPostMenu(BaseFragment f, SquareLogic.Post p, Runnable onDeleted, Runnable onBlocked) {
        showPostMenu(f, p, onDeleted, onBlocked, null);
    }

    /** @param onNotInterested non-null in the recommend tab: adds 「不感兴趣」 (specs#115) */
    static void showPostMenu(BaseFragment f, SquareLogic.Post p, Runnable onDeleted, Runnable onBlocked, Runnable onNotInterested) {
        if (f.getParentActivity() == null) return;
        SquareLogic.Texts tx = SquareUi.tx();
        boolean mine = SquareLogic.canDeletePost(p, SquareUi.myAuthorId);
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (mine) {
            labels.add(tx.delete());
            actions.add(() -> new org.telegram.ui.ActionBar.AlertDialog.Builder(f.getParentActivity())
                .setTitle(tx.delete())
                .setMessage(tx.deletePostConfirm())
                .setPositiveButton(tx.delete(), (d, w) -> LuckgramAI.getInstance().requestJson("DELETE", "/v1/square/posts/" + SquareLogic.pathId(p.id), null, new LuckgramAI.Callback<JSONObject>() {
                    @Override
                    public void onResult(JSONObject result) {
                        SquareUi.bulletin(f).createSimpleBulletin(R.raw.done, tx.deleted()).show();
                        removedPosts.add(p.id);
                        onDeleted.run();
                    }

                    @Override
                    public void onError(LuckgramAIError error) {
                        SquareUi.showError(f, error);
                    }
                }))
                .setNegativeButton(tx.cancel(), null)
                .show());
        } else {
            if (onNotInterested != null) {
                labels.add(tx.notInterested());
                actions.add(onNotInterested);
            }
            labels.add(tx.reportPost());
            actions.add(() -> SquareUi.report(f, "post", p.id));
            labels.add(tx.reportAuthor());
            actions.add(() -> SquareUi.report(f, "user", p.author.id));
            labels.add(tx.block());
            actions.add(() -> SquareUi.block(f, p.author, onBlocked));
        }
        new org.telegram.ui.ActionBar.AlertDialog.Builder(f.getParentActivity())
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> AndroidUtilities.runOnUIThread(actions.get(which)))
            .show();
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            refresh(feed, true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

package org.telegram.luckgram.square;

import android.content.Context;
import android.view.View;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Square feed (spec 26, luckgram-specs#64): "Latest" / "Popular" tabs, 20 per page with a cursor,
 * refresh, compose. Browsing needs no link; writes go through {@link SquareUi#requireLink}.
 */
public class SquareActivity extends UniversalFragment {

    private static final int MENU_COMPOSE = 1;
    private static final int MENU_REFRESH = 2;
    private static final int MENU_PROFILE = 3;
    private static final int ID_LOAD_MORE = 1;
    private static final int ID_RETRY = 2;
    private static final int ID_POST = 1000;
    private static final int ID_TABS = 3;

    private final SquareLogic.Texts tx = SquareUi.tx();
    private String tab = SquareLogic.TAB_LATEST;
    private final ArrayList<SquareLogic.Post> posts = new ArrayList<>();
    private String nextCursor;
    private boolean loading;
    private boolean loadedOnce;
    private boolean failed;
    private LuckgramAI.Request inFlight;
    /** Set by detail / compose pages so the feed reloads when it comes back. */
    public static boolean dirty;
    private SquareTabsView tabs;

    private static final String[] TAB_ORDER = {SquareLogic.TAB_LATEST, SquareLogic.TAB_HOT, SquareLogic.TAB_RECOMMEND};

    private static int tabIndex(String tab) {
        for (int i = 0; i < TAB_ORDER.length; i++) if (TAB_ORDER[i].equals(tab)) return i;
        return 0;
    }

    // ---------------------------------------------------------------- specs#115 behavior events

    private SquareEvents.Tracker tracker = new SquareEvents.Tracker(SquareLogic.TAB_LATEST);
    /** Post views currently bound to the list → index in {@link #posts}. */
    private final java.util.WeakHashMap<View, SquareLogic.Post> postViews = new java.util.WeakHashMap<>();
    private final Runnable checkVisible = this::trackVisible;

    /** Posts at least half visible → their position; reported as impressions / views. */
    private void trackVisible() {
        if (listView == null || isPaused || posts.isEmpty()) return;
        java.util.HashMap<String, Integer> visible = new java.util.HashMap<>();
        int h = listView.getHeight();
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            SquareLogic.Post p = postViews.get(child);
            if (p == null || child.getHeight() <= 0) continue;
            int shown = Math.min(child.getBottom(), h) - Math.max(child.getTop(), 0);
            if (shown * 2 >= child.getHeight()) {
                int rank = posts.indexOf(p);
                if (rank >= 0) visible.put(p.id, rank);
            }
        }
        SquareEventReporter.add(tracker.update(visible, System.currentTimeMillis()));
    }

    private void scheduleTrack() {
        AndroidUtilities.cancelRunOnUIThread(checkVisible);
        AndroidUtilities.runOnUIThread(checkVisible, 300);
    }

    private void event(String type, SquareLogic.Post p) {
        Integer rank = tracker.rankOf(p.id);
        SquareEventReporter.add(new SquareEvents.Event(type, p.id, tab, rank == null ? posts.indexOf(p) : rank, 0, System.currentTimeMillis()));
    }

    /** "Latest" / "Popular" as a plain tab bar with an underline (acceptance P2). One instance, reused across list updates. */
    private SquareTabsView tabsView() {
        if (tabs == null) {
            // specs#115: 最新 / 热门 / 推荐 (default stays 最新)
            tabs = new SquareTabsView(getContext(), new String[]{tx.latest(), tx.hot(), tx.recommend()}, tabIndex(tab), which -> {
                String next = TAB_ORDER[Math.max(0, Math.min(TAB_ORDER.length - 1, which))];
                if (!next.equals(tab)) {
                    tab = next;
                    reload();
                }
            }, getResourceProvider());
        }
        return tabs;
    }

    @Override
    protected CharSequence getTitle() {
        return tx.title();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        actionBar.setAllowOverlayTitle(false); // keep our title; the chat list shows the connection state
        installPullToRefresh();
        listView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                scheduleTrack();
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
                    reload();
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
        reload();
        return super.onFragmentCreate();
    }

    private void installPullToRefresh() {
        // Pull down at the top of the list to refresh (spec 26 item 2); the menu button does the same.
        listView.addOnItemTouchListener(new androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
            private float startY = -1f;

            @Override
            public boolean onInterceptTouchEvent(androidx.recyclerview.widget.RecyclerView rv, android.view.MotionEvent e) {
                int action = e.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    startY = !rv.canScrollVertically(-1) ? e.getY() : -1f;
                } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                    if (action == android.view.MotionEvent.ACTION_UP && startY >= 0 && !loading && e.getY() - startY > AndroidUtilities.dp(160) && !rv.canScrollVertically(-1)) {
                        reload();
                    }
                    startY = -1f;
                }
                return false;
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (dirty) {
            dirty = false;
            reload();
        } else {
            update();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // a view ends when the page goes away; send what we have (contract: also on background)
        AndroidUtilities.cancelRunOnUIThread(checkVisible);
        SquareEventReporter.add(tracker.hideAll(System.currentTimeMillis()));
        SquareEventReporter.flush(true);
    }

    @Override
    public void onFragmentDestroy() {
        if (inFlight != null) inFlight.cancel();
        AndroidUtilities.cancelRunOnUIThread(checkVisible);
        super.onFragmentDestroy();
    }

    private void update() {
        if (listView != null) listView.adapter.update(true);
        scheduleTrack();
    }

    private void reload() {
        // a new load: impressions count again (contract: once per load)
        SquareEventReporter.add(tracker.hideAll(System.currentTimeMillis()));
        tracker = new SquareEvents.Tracker(tab);
        posts.clear();
        nextCursor = null;
        loadedOnce = false;
        load();
    }

    private void load() {
        if (inFlight != null) inFlight.cancel();
        loading = true;
        failed = false;
        update();
        final String requestedTab = tab;
        inFlight = LuckgramAI.getInstance().requestJson("GET", SquareLogic.feedPath(tab, nextCursor), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                if (!requestedTab.equals(tab)) return;
                SquareLogic.Page<SquareLogic.Post> page = SquareLogic.parsePosts(result);
                SquareLogic.appendUnique(posts, page.items);
                nextCursor = page.nextCursor;
                loading = false;
                loadedOnce = true;
                update();
            }

            @Override
            public void onError(LuckgramAIError error) {
                loading = false;
                failed = true;
                update();
                SquareUi.showError(SquareActivity.this, error);
            }
        });
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustom(ID_TABS, tabsView()));
        for (int i = 0; i < posts.size(); i++) {
            View postView = SquareUi.postView(getContext(), posts.get(i), false, actions);
            postViews.put(postView, posts.get(i));
            items.add(UItem.asCustom(ID_POST + i, postView));
            items.add(UItem.asShadow(null));
        }
        if (loading) {
            items.add(UItem.asButtonLoadingValue(0, tx.loading()).setEnabled(false));
        } else if (failed) {
            items.add(UItem.asButton(ID_RETRY, tx.refresh()).accent());
        } else if (loadedOnce && posts.isEmpty()) {
            items.add(UItem.asShadow(tx.empty()));
        } else if (nextCursor != null) {
            items.add(UItem.asButton(ID_LOAD_MORE, tx.loadMore()).accent());
        } else if (!posts.isEmpty()) {
            items.add(UItem.asShadow(tx.noMore()));
        }
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
            Runnable notInterested = SquareLogic.TAB_RECOMMEND.equals(tab) ? () -> {
                event(SquareEvents.NOT_INTERESTED, p);
                posts.remove(p);
                update();
                SquareUi.bulletin(SquareActivity.this).createSimpleBulletin(R.raw.done, tx.notInterestedDone()).show();
            } : null;
            showPostMenu(SquareActivity.this, p, () -> {
                posts.remove(p);
                update();
            }, () -> {
                removeAuthor(posts, p.author.id);
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
                        dirty = true;
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
            actions.add(() -> SquareUi.block(f, p.author, () -> {
                dirty = true;
                onBlocked.run();
            }));
        }
        new org.telegram.ui.ActionBar.AlertDialog.Builder(f.getParentActivity())
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> AndroidUtilities.runOnUIThread(actions.get(which)))
            .show();
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_LOAD_MORE && !loading) {
            load();
        } else if (item.id == ID_RETRY) {
            if (posts.isEmpty()) reload();
            else load();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

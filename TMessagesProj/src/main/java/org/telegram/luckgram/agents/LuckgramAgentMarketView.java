package org.telegram.luckgram.agents;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * specs#88 (30) 需求 2: the "市场" tab — search field, category tabs from the server, the agents
 * (avatar, name, one-line description, users, Pro badge), paged, pull down to refresh, the cache
 * when offline (需求 7).
 */
@SuppressLint("ViewConstructor")
public class LuckgramAgentMarketView extends LinearLayout {

    private final BaseFragment fragment;
    private final int account;
    private final boolean zh = LuckgramStrings.isChinese();

    private final EditTextBoldCursor search;
    private final LinearLayout tabsRow;
    private final RecyclerListView listView;
    private final Adapter adapter = new Adapter();

    private List<AgentMarketLogic.Category> categories = new ArrayList<>();
    private String category = "";
    private String query = "";
    private final ArrayList<AgentMarketLogic.Agent> items = new ArrayList<>();
    private String nextCursor;
    private boolean loading;
    private boolean offline;
    private LuckgramAIError error;
    private LuckgramAI.Request pageRequest, categoriesRequest;
    private int generation;
    private final Runnable searchRunnable = this::reload;

    public LuckgramAgentMarketView(Context context, BaseFragment fragment) {
        super(context);
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        search = new EditTextBoldCursor(context);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setHint(LuckgramStrings.get("搜索智能体", "Search agents"));
        search.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        search.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        search.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        search.setCursorWidth(1.5f);
        search.setPadding(dp(40), 0, dp(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Theme.getColor(Theme.key_graySection));
        search.setBackground(bg);
        FrameLayout searchFrame = new FrameLayout(context);
        searchFrame.addView(search, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));
        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.msg_search);
        searchIcon.setScaleType(ImageView.ScaleType.CENTER);
        searchIcon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
        searchFrame.addView(searchIcon, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
        addView(searchFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (q.equals(query)) return;
                query = q;
                AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                AndroidUtilities.runOnUIThread(searchRunnable, 350);
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            AndroidUtilities.hideKeyboard(search);
            return true;
        });

        HorizontalScrollView tabsScroll = new HorizontalScrollView(context);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsRow = new LinearLayout(context);
        tabsRow.setOrientation(HORIZONTAL);
        tabsRow.setPadding(dp(8), 0, dp(8), 0);
        tabsScroll.addView(tabsRow);
        addView(tabsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        listView = new RecyclerListView(context);
        LinearLayoutManager lm = new LinearLayoutManager(context);
        listView.setLayoutManager(lm);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position < items.size()) {
                fragment.presentFragment(new LuckgramAgentDetailActivity(items.get(position)));
            } else if (error != null && !loading) {
                reload();
            }
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy != 0) AndroidUtilities.hideKeyboard(search);
                if (!loading && nextCursor != null && lm.findLastVisibleItemPosition() >= items.size() - 4) {
                    loadMore();
                }
            }
        });
        installPullToRefresh();
        addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        AgentMarketLogic.Cache cache = LuckgramAgentMarket.cache(account);
        categories = cache.categories();
        buildTabs();
        AgentMarketLogic.Page cached = cache.firstPage("");
        if (cached != null) {
            items.addAll(cached.items);
        }
        refreshCategories();
        reload();
    }

    /** Pull down at the top of the list to refresh (需求 2). */
    private void installPullToRefresh() {
        listView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float startY = -1f;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    startY = !rv.canScrollVertically(-1) ? e.getY() : -1f;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (action == MotionEvent.ACTION_UP && startY >= 0 && !loading && e.getY() - startY > dp(120) && !rv.canScrollVertically(-1)) {
                        refresh();
                    }
                    startY = -1f;
                }
                return false;
            }
        });
    }

    /** Reloads categories and the list (pull to refresh, or the menu button). */
    public void refresh() {
        refreshCategories();
        reload();
    }

    public void onDestroy() {
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        if (pageRequest != null) pageRequest.cancel();
        if (categoriesRequest != null) categoriesRequest.cancel();
    }

    /** Called when the tab is shown again (e.g. back from a detail page). */
    public void onResume() {
        adapter.notifyDataSetChanged();
    }

    private void refreshCategories() {
        if (categoriesRequest != null) categoriesRequest.cancel();
        categoriesRequest = LuckgramAgentMarket.loadCategories(account, new LuckgramAgentMarket.Callback<List<AgentMarketLogic.Category>>() {
            @Override
            public void onResult(List<AgentMarketLogic.Category> result) {
                categoriesRequest = null;
                categories = result;
                boolean known = category.isEmpty();
                for (AgentMarketLogic.Category c : result) known |= c.id.equals(category);
                if (!known) {
                    category = "";
                    reload();
                }
                buildTabs();
            }

            @Override
            public void onError(LuckgramAIError error) {
                categoriesRequest = null;
            }
        });
    }

    private void buildTabs() {
        tabsRow.removeAllViews();
        addTab("", LuckgramStrings.get("全部", "All"));
        for (AgentMarketLogic.Category c : categories) {
            if (c.count > 0 || c.id.equals(category)) addTab(c.id, c.name.pick(zh));
        }
    }

    private void addTab(String id, String title) {
        TextView tab = new TextView(getContext());
        tab.setText(title);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(14), 0, dp(14), 0);
        boolean selected = id.equals(category);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(selected ? Theme.getColor(Theme.key_featuredStickers_addButton) : Theme.getColor(Theme.key_graySection));
        tab.setBackground(bg);
        tab.setTextColor(selected ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (selected) tab.setTypeface(AndroidUtilities.bold());
        tab.setOnClickListener(v -> {
            if (id.equals(category)) return;
            category = id;
            buildTabs();
            reload();
        });
        tabsRow.addView(tab, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));
    }

    private void reload() {
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        nextCursor = null;
        load(null);
    }

    private void loadMore() {
        if (nextCursor != null) load(nextCursor);
    }

    private void load(String cursor) {
        if (pageRequest != null) pageRequest.cancel();
        final int gen = ++generation;
        final boolean first = cursor == null;
        final String cat = category, q = query;
        loading = true;
        error = null;
        if (first) {
            // show what we have for this tab while loading
            items.clear();
            items.addAll(LuckgramAgentMarket.cache(account).offline(cat, q));
        }
        adapter.notifyDataSetChanged();
        pageRequest = LuckgramAgentMarket.loadPage(account, cat, q, cursor, new LuckgramAgentMarket.Callback<AgentMarketLogic.Page>() {
            @Override
            public void onResult(AgentMarketLogic.Page page) {
                if (gen != generation) return;
                pageRequest = null;
                loading = false;
                offline = false;
                if (first) items.clear();
                for (AgentMarketLogic.Agent a : page.items) {
                    boolean dup = false;
                    for (AgentMarketLogic.Agent b : items) dup |= b.id.equals(a.id);
                    if (!dup) items.add(a);
                }
                nextCursor = page.nextCursor;
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onError(LuckgramAIError e) {
                if (gen != generation) return;
                pageRequest = null;
                loading = false;
                if (LuckgramAIError.CANCELLED.equals(e.code)) return;
                error = e;
                offline = first && !items.isEmpty();
                nextCursor = null;
                adapter.notifyDataSetChanged();
            }
        });
    }

    // ---------------------------------------------------------------- list

    private static final int TYPE_AGENT = 0, TYPE_FOOTER = 1;

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_AGENT || error != null;
        }

        @Override
        public int getItemCount() {
            return items.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position < items.size() ? TYPE_AGENT : TYPE_FOOTER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v;
            if (viewType == TYPE_AGENT) {
                v = new MarketAgentCell(getContext());
            } else {
                TextView t = new TextView(getContext());
                t.setGravity(Gravity.CENTER);
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                t.setPadding(dp(24), dp(16), dp(24), dp(16));
                v = t;
            }
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_AGENT) {
                ((MarketAgentCell) holder.itemView).set(items.get(position), position < items.size() - 1);
                return;
            }
            TextView t = (TextView) holder.itemView;
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            if (loading) {
                t.setText(LuckgramStrings.get("加载中…", "Loading…"));
            } else if (error != null) {
                String msg = offline
                    ? LuckgramStrings.get("离线，显示的是缓存。点此重试", "Offline: showing saved results. Tap to retry")
                    : LuckgramStrings.errorMessage(error.code) + "\n" + LuckgramStrings.get("点此重试", "Tap to retry");
                t.setText(msg);
                t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            } else if (items.isEmpty()) {
                t.setText(query.isEmpty() ? LuckgramStrings.get("这里还没有智能体", "No agents here yet") : LuckgramStrings.get("没有找到相关智能体", "No matching agents"));
            } else {
                t.setText("");
            }
        }
    }

    /** One market agent row: avatar, name + Pro badge, one-line description, users. */
    static class MarketAgentCell extends FrameLayout {
        private final LuckgramAgentsActivity.AgentAvatar avatarDrawable = new LuckgramAgentsActivity.AgentAvatar();
        private final TextView title, pro, subtitle, users;
        private boolean divider;

        MarketAgentCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            ImageView avatar = new ImageView(context);
            avatar.setImageDrawable(avatarDrawable);
            addView(avatar, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.TOP, 14, 11, 0, 0));
            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            titleRow.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            pro = proBadge(context);
            titleRow.addView(pro, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
            addView(titleRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 72, 10, 16, 0));
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            addView(subtitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 72, 34, 16, 0));
            users = new TextView(context);
            users.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            users.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            users.setSingleLine(true);
            addView(users, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 72, 55, 16, 0));
        }

        void set(AgentMarketLogic.Agent agent, boolean divider) {
            boolean zh = LuckgramStrings.isChinese();
            this.divider = divider;
            avatarDrawable.set(agent.color, agent.avatarText(zh));
            title.setText(agent.name.pick(zh));
            pro.setVisibility(agent.proOnly ? VISIBLE : GONE);
            subtitle.setText(agent.description.pick(zh));
            users.setText(AgentMarketLogic.formatUsers(agent.users, zh));
            setContentDescription(agent.name.pick(zh) + (agent.proOnly ? ", Pro" : "") + ", " + agent.description.pick(zh));
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(76), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(@NonNull android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (divider) {
                canvas.drawLine(dp(72), getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    public interface TabListener {
        void onTab(boolean market);
    }

    /** spec 30 需求 1: the "我的" | "市场" tabs at the top of the agents page; starts on "我的". */
    public static View createTabs(Context context, TabListener listener) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(HORIZONTAL);
        bar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bar.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(16));
        track.setColor(Theme.getColor(Theme.key_graySection));
        LinearLayout seg = new LinearLayout(context);
        seg.setOrientation(HORIZONTAL);
        seg.setBackground(track);
        seg.setPadding(dp(2), dp(2), dp(2), dp(2));
        bar.addView(seg, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        TextView[] tabs = new TextView[2];
        String[] titles = {LuckgramStrings.get("我的", "Mine"), LuckgramStrings.get("市场", "Market")};
        Runnable[] select = new Runnable[2];
        for (int i = 0; i < 2; i++) {
            TextView t = new TextView(context);
            t.setText(titles[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            t.setTypeface(AndroidUtilities.bold());
            tabs[i] = t;
            seg.addView(t, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
        for (int i = 0; i < 2; i++) {
            final int index = i;
            select[i] = () -> {
                for (int j = 0; j < 2; j++) {
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(dp(14));
                    bg.setColor(j == index ? Theme.getColor(Theme.key_windowBackgroundWhite) : 0);
                    tabs[j].setBackground(bg);
                    tabs[j].setTextColor(Theme.getColor(j == index ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
                    tabs[j].setSelected(j == index);
                }
            };
            tabs[i].setOnClickListener(v -> {
                if (v.isSelected()) return;
                select[index].run();
                listener.onTab(index == 1);
            });
        }
        select[0].run();
        return bar;
    }

    /** The small "Pro" badge (spec 30 需求 2). */
    static TextView proBadge(Context context) {
        TextView pro = new TextView(context);
        pro.setText("Pro");
        pro.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        pro.setTypeface(AndroidUtilities.bold());
        pro.setTextColor(0xFFFFFFFF);
        pro.setPadding(dp(6), dp(1), dp(6), dp(1));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF7B5CFF, 0xFFE55BD8});
        bg.setCornerRadius(dp(6));
        pro.setBackground(bg);
        return pro;
    }
}

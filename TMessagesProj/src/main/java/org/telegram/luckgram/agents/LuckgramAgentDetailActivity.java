package org.telegram.luckgram.agents;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.pro.LuckgramPro;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * specs#88 (30) 需求 3: avatar, name, description, author (Luckgram 官方), three example questions,
 * "开始聊天". Shows the cached copy first and refreshes it from the server.
 */
public class LuckgramAgentDetailActivity extends BaseFragment {

    private AgentMarketLogic.Agent agent;
    private LuckgramAI.Request request;
    private boolean unavailable;

    private final LuckgramAgentsActivity.AgentAvatar avatarDrawable = new LuckgramAgentsActivity.AgentAvatar();
    private TextView nameView, proView, descView, metaView, noteView;
    private LinearLayout examplesView;
    private TextView examplesHeader;
    private ButtonWithCounterView button;

    public LuckgramAgentDetailActivity(AgentMarketLogic.Agent agent) {
        this.agent = agent;
    }

    @Override
    public boolean onFragmentCreate() {
        AgentMarketLogic.Agent cached = LuckgramAgentMarket.cache(currentAccount).detail(agent.id);
        if (cached != null && agent.examples.isEmpty()) agent = cached;
        request = LuckgramAgentMarket.loadDetail(currentAccount, agent.id, new LuckgramAgentMarket.Callback<AgentMarketLogic.Agent>() {
            @Override
            public void onResult(AgentMarketLogic.Agent result) {
                request = null;
                agent = result;
                unavailable = false;
                bind();
            }

            @Override
            public void onError(LuckgramAIError error) {
                request = null;
                // 404: taken down; other errors keep the cached copy (offline, 需求 7)
                unavailable = error.httpStatus == 404 || LuckgramAIError.AGENT_UNAVAILABLE.equals(error.code);
                bind();
            }
        });
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (request != null) request.cancel();
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        bind(); // Pro may have changed after the upgrade page
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuckgramStrings.get("智能体", "Agent"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;

        ScrollView scroll = new ScrollView(context);
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(col);
        frame.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 80));

        ImageView avatar = new ImageView(context);
        avatar.setImageDrawable(avatarDrawable);
        col.addView(avatar, LayoutHelper.createLinear(88, 88, Gravity.CENTER_HORIZONTAL));

        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER);
        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        nameView.setGravity(Gravity.CENTER);
        nameRow.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        proView = LuckgramAgentMarketView.proBadge(context);
        nameRow.addView(proView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        col.addView(nameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        metaView = new TextView(context);
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        metaView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        metaView.setGravity(Gravity.CENTER);
        col.addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        descView = new TextView(context);
        descView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        descView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        descView.setGravity(Gravity.CENTER);
        descView.setLineSpacing(dp(2), 1f);
        col.addView(descView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        examplesHeader = new TextView(context);
        examplesHeader.setText(LuckgramStrings.get("可以这样问", "Try asking"));
        examplesHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        examplesHeader.setTypeface(AndroidUtilities.bold());
        examplesHeader.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        col.addView(examplesHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 8));
        examplesView = new LinearLayout(context);
        examplesView.setOrientation(LinearLayout.VERTICAL);
        col.addView(examplesView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        noteView = new TextView(context);
        noteView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        noteView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        noteView.setGravity(Gravity.CENTER);
        col.addView(noteView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        button = new ButtonWithCounterView(context, getResourceProvider());
        button.setOnClickListener(v -> {
            if (unavailable) return;
            LuckgramAgentMarket.open(this, agent);
        });
        frame.addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 0, 16, 16));

        bind();
        return fragmentView;
    }

    private void bind() {
        if (fragmentView == null) return;
        boolean zh = LuckgramStrings.isChinese();
        avatarDrawable.set(agent.color, agent.avatarText(zh));
        nameView.setText(agent.name.pick(zh));
        proView.setVisibility(agent.proOnly ? View.VISIBLE : View.GONE);
        String author = "luckgram".equals(agent.author) ? LuckgramStrings.get("Luckgram 官方", "By Luckgram") : agent.author;
        metaView.setText(author + " · " + AgentMarketLogic.formatUsers(agent.users, zh));
        descView.setText(agent.description.pick(zh));

        examplesView.removeAllViews();
        int n = Math.min(3, agent.examples.size());
        examplesHeader.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < n; i++) {
            TextView ex = new TextView(getParentActivity());
            ex.setText(agent.examples.get(i).pick(zh));
            ex.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            ex.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            ex.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(getThemedColor(Theme.key_graySection));
            ex.setBackground(bg);
            examplesView.addView(ex, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }

        boolean needsPro = AgentMarketLogic.needsUpgrade(agent, LuckgramPro.isPro());
        if (unavailable) {
            noteView.setText(LuckgramStrings.get("这个智能体已下架。", "This agent is no longer available."));
            noteView.setVisibility(View.VISIBLE);
        } else if (needsPro) {
            noteView.setText(LuckgramStrings.get("Luckgram Pro 专享，升级后即可聊天。", "Available with Luckgram Pro."));
            noteView.setVisibility(View.VISIBLE);
        } else {
            noteView.setVisibility(View.GONE);
        }
        button.setText(LuckgramStrings.get("开始聊天", "Start chat"), false);
        button.setEnabled(!unavailable);
        button.setAlpha(unavailable ? 0.5f : 1f);
    }
}

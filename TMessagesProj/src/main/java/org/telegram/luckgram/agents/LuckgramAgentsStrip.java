package org.telegram.luckgram.agents;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/**
 * spec 22 需求 5: agents added to the chat list, shown like contacts at the top of the chat list
 * (local only). Tapping one opens its conversation.
 */
public class LuckgramAgentsStrip extends HorizontalScrollView {

    private final BaseFragment fragment;
    private final LinearLayout row;

    public LuckgramAgentsStrip(Context context, BaseFragment fragment) {
        super(context);
        this.fragment = fragment;
        setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        addView(row);
    }

    /** Rebuilds the strip; returns false when there is nothing to show. */
    public boolean update() {
        row.removeAllViews();
        List<LuckgramAgents.Agent> agents = LuckgramAgents.pinnedAgents(fragment.getCurrentAccount());
        for (LuckgramAgents.Agent agent : agents) {
            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(6), dp(4), dp(6), dp(4));
            item.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            ImageView avatar = new ImageView(getContext());
            LuckgramAgentsActivity.AgentAvatar d = new LuckgramAgentsActivity.AgentAvatar();
            d.set(agent.color, agent.initial());
            avatar.setImageDrawable(d);
            item.addView(avatar, LayoutHelper.createLinear(48, 48));
            TextView name = new TextView(getContext());
            name.setText(agent.name());
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            item.addView(name, LayoutHelper.createLinear(64, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            item.setContentDescription(agent.name());
            item.setOnClickListener(v -> LuckgramAgents.open(fragment, agent));
            row.addView(item);
        }
        return !agents.isEmpty();
    }
}

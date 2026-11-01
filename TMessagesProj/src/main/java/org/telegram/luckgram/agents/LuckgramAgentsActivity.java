package org.telegram.luckgram.agents;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.ai.LuckgramAiRegistry;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** spec 22 需求 1: presets, the user's agents and "创建你自己的智能体". */
public class LuckgramAgentsActivity extends BaseFragment {

    /** spec 22 平台入口提示: the one registration line, called from the AI assistant's registry. */
    public static void register() {
        LuckgramAiRegistry.registerHeaderEntry(new LuckgramAiRegistry.HeaderEntry("agents", "智能体", "Agents", R.drawable.msg_bots,
            page -> page.presentFragment(new LuckgramAgentsActivity())).visibleWhen(LuckgramAgents::isEnabled));
    }

    private static final int TYPE_HEADER = 0, TYPE_AGENT = 1, TYPE_CREATE = 2, TYPE_SHADOW = 3;

    private static final class Row {
        final int type;
        final String header;
        final LuckgramAgents.Agent agent;

        Row(int type, String header, LuckgramAgents.Agent agent) {
            this.type = type;
            this.header = header;
            this.agent = agent;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        rows.clear();
        rows.add(new Row(TYPE_HEADER, LuckgramStrings.get("热门智能体", "Popular agents"), null));
        for (LuckgramAgents.Agent a : LuckgramAgents.presets()) rows.add(new Row(TYPE_AGENT, null, a));
        rows.add(new Row(TYPE_SHADOW, null, null));
        rows.add(new Row(TYPE_HEADER, LuckgramStrings.get("我的智能体", "My agents"), null));
        for (LuckgramAgents.Agent a : LuckgramAgents.custom(currentAccount)) rows.add(new Row(TYPE_AGENT, null, a));
        rows.add(new Row(TYPE_CREATE, null, null));
        rows.add(new Row(TYPE_SHADOW, null, null));
        if (listView != null && listView.getAdapter() != null) listView.getAdapter().notifyDataSetChanged();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuckgramStrings.get("智能体", "Agents"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                int t = holder.getItemViewType();
                return t == TYPE_AGENT || t == TYPE_CREATE;
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }

            @Override
            public int getItemViewType(int position) {
                return rows.get(position).type;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v;
                if (viewType == TYPE_HEADER) {
                    v = new HeaderCell(context);
                    v.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                } else if (viewType == TYPE_SHADOW) {
                    v = new ShadowSectionCell(context);
                } else {
                    v = new AgentCell(context);
                    v.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                }
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(v);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                Row r = rows.get(position);
                if (r.type == TYPE_HEADER) {
                    ((HeaderCell) holder.itemView).setText(r.header);
                } else if (r.type == TYPE_AGENT) {
                    ((AgentCell) holder.itemView).set(r.agent);
                } else if (r.type == TYPE_CREATE) {
                    ((AgentCell) holder.itemView).setCreate();
                }
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            Row r = rows.get(position);
            if (r.type == TYPE_AGENT) {
                LuckgramAgents.open(this, r.agent);
            } else if (r.type == TYPE_CREATE) {
                presentFragment(new LuckgramAgentEditActivity(null));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            Row r = rows.get(position);
            if (r.type != TYPE_AGENT) return false;
            showOptions(view, r.agent);
            return true;
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        reload();
        return fragmentView;
    }

    private void showOptions(View anchor, LuckgramAgents.Agent agent) {
        ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.msg_discussion, LuckgramStrings.get("开始对话", "Open chat"), () -> LuckgramAgents.open(this, agent));
        boolean pinned = LuckgramAgents.isPinned(currentAccount, agent.id);
        o.add(pinned ? R.drawable.msg_unpin : R.drawable.msg_pin, pinned ? LuckgramStrings.get("从聊天列表移除", "Remove from chat list") : LuckgramStrings.get("添加到聊天列表", "Add to chat list"), () -> {
            LuckgramAgents.setPinned(currentAccount, agent.id, !pinned);
            reload();
        });
        if (!agent.preset) {
            o.add(R.drawable.msg_edit, LuckgramStrings.get("编辑", "Edit"), () -> presentFragment(new LuckgramAgentEditActivity(agent.id)));
            o.add(R.drawable.msg_delete, LuckgramStrings.get("删除", "Delete"), true, () -> LuckgramAgentEditActivity.confirmDelete(this, agent, this::reload));
        }
        o.show();
    }

    /** A round avatar with the agent's initial in its color. */
    public static final class AgentAvatar extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        private String initial = "";

        public AgentAvatar() {
            text.setColor(0xFFFFFFFF);
            text.setTypeface(AndroidUtilities.bold());
            text.setTextAlign(Paint.Align.CENTER);
        }

        public void set(int color, String initial) {
            this.color = color;
            this.initial = initial;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            paint.setColor(color);
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, paint);
            text.setTextSize(r * 0.9f);
            canvas.drawText(initial, b.exactCenterX(), b.exactCenterY() - (text.descent() + text.ascent()) / 2f, text);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private class AgentCell extends FrameLayout {
        private final ImageView avatar;
        private final AgentAvatar avatarDrawable = new AgentAvatar();
        private final TextView title;
        private final TextView subtitle;
        private final ImageView pin;

        AgentCell(Context context) {
            super(context);
            avatar = new ImageView(context);
            avatar.setImageDrawable(avatarDrawable);
            avatar.setScaleType(ImageView.ScaleType.CENTER);
            addView(avatar, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 72, 10, 48, 0));
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            addView(subtitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 72, 34, 48, 0));
            pin = new ImageView(context);
            pin.setScaleType(ImageView.ScaleType.CENTER);
            pin.setImageResource(R.drawable.ic_ab_other);
            pin.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon));
            pin.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
            addView(pin, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        }

        void set(LuckgramAgents.Agent agent) {
            avatarDrawable.set(agent.color, agent.initial());
            avatar.clearColorFilter();
            avatar.setImageDrawable(avatarDrawable);
            title.setText(agent.name());
            title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            subtitle.setText(agent.description());
            subtitle.setVisibility(VISIBLE);
            pin.setVisibility(VISIBLE);
            pin.setContentDescription(LuckgramStrings.get("更多", "More"));
            pin.setOnClickListener(v -> showOptions(v, agent));
        }

        void setCreate() {
            avatar.setImageResource(R.drawable.msg_add);
            avatar.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlueIcon));
            title.setText(LuckgramStrings.get("创建你自己的智能体", "Create your own agent"));
            title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
            subtitle.setVisibility(GONE);
            pin.setVisibility(GONE);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
        }
    }
}

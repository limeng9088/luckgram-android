package org.telegram.luckgram.ai;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/** spec 18 需求 6: list of stored AI conversations; tap to open, long-press to delete. */
public class LuckgramAiHistoryActivity extends BaseFragment implements LuckgramAiStore.Listener {

    private final Utilities.Callback<LuckgramAiConversation> onPick;
    private final ArrayList<LuckgramAiConversation> items = new ArrayList<>();
    private RecyclerListView listView;
    private TextView emptyView;

    public LuckgramAiHistoryActivity(Utilities.Callback<LuckgramAiConversation> onPick) {
        super();
        this.onPick = onPick;
    }

    @Override
    public boolean onFragmentCreate() {
        LuckgramAiStore.getInstance(currentAccount).addListener(this);
        reload();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        LuckgramAiStore.getInstance(currentAccount).removeListener(this);
        super.onFragmentDestroy();
    }

    private void reload() {
        items.clear();
        List<LuckgramAiConversation> all = LuckgramAiStore.getInstance(currentAccount).list();
        items.addAll(all);
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onConversationsChanged() {
        reload();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LuckgramStrings.get("历史对话", "Chat history"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return true;
            }

            @Override
            public int getItemCount() {
                return items.size();
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerListView.Holder(new Row(parent.getContext()));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ((Row) holder.itemView).set(items.get(position), position != items.size() - 1);
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) return;
            LuckgramAiConversation c = items.get(position);
            finishFragment();
            if (onPick != null) onPick.run(c);
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) return false;
            LuckgramAiConversation c = items.get(position);
            ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_delete, LuckgramStrings.get("删除对话", "Delete chat"), true, () -> confirmDelete(c))
                .show();
            return true;
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LuckgramStrings.get("还没有对话", "No chats yet"));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        return fragmentView;
    }

    private void confirmDelete(LuckgramAiConversation c) {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(LuckgramStrings.get("删除对话", "Delete chat"))
            .setMessage(LuckgramStrings.get("删除后无法恢复。", "This can't be undone."))
            .setPositiveButton(LuckgramStrings.get("删除", "Delete"), (d, w) -> LuckgramAiStore.getInstance(currentAccount).delete(c.id))
            .setNegativeButton(LuckgramStrings.cancel(), null)
            .create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private class Row extends FrameLayout {
        private final TextView title;
        private final TextView subtitle;
        private final TextView date;
        private boolean divider;

        Row(Context context) {
            super(context);
            setWillNotDraw(false);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 20, 10, 90, 0));
            date = new TextView(context);
            date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            date.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText3));
            addView(date, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 12, 20, 0));
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            addView(subtitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 20, 36, 20, 0));
        }

        void set(LuckgramAiConversation c, boolean divider) {
            this.divider = divider;
            title.setText(TextUtils.isEmpty(c.title) ? LuckgramStrings.get("新对话", "New chat") : c.title);
            String last = "";
            for (int i = c.messages.size() - 1; i >= 0; i--) {
                String t = c.messages.get(i).text;
                if (!TextUtils.isEmpty(t)) {
                    last = t.replace('\n', ' ');
                    break;
                }
            }
            subtitle.setText(last);
            date.setText(LocaleController.stringForMessageListDate(c.updatedAt / 1000));
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(66), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (divider) {
                canvas.drawLine(dp(20), getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}

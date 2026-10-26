/*
 * Luckgram: quick reply picker shown from the chat input (feature 02).
 * Tap a template to insert it into the input field, long press to send it right away.
 */
package org.telegram.luckgram.quickreplies;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

public class QuickRepliesSheet extends BottomSheet {

    public interface Delegate {
        void onInsert(String text);

        void onSend(String text);

        void onManage();
    }

    private static final int ROW_HEIGHT = 52;
    private static final int MAX_VISIBLE_ROWS = 8;

    private final Theme.ResourcesProvider resourcesProvider;

    public QuickRepliesSheet(Context context, int account, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context, false, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        fixNavigationBar();

        final ArrayList<String> items = QuickRepliesStore.get(account);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final FrameLayout header = new FrameLayout(context);
        final TextView title = new TextView(context);
        title.setText(QuickRepliesStrings.title());
        title.setTextColor(color(Theme.key_dialogTextBlack));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 22, 0, 110, 0));

        final TextView manage = new TextView(context);
        manage.setText(QuickRepliesStrings.manage());
        manage.setTextColor(color(Theme.key_dialogTextBlue2));
        manage.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        manage.setTypeface(AndroidUtilities.bold());
        manage.setGravity(Gravity.CENTER);
        manage.setPadding(dp(12), 0, dp(12), 0);
        manage.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(color(Theme.key_dialogTextBlue2), 0.12f), Theme.RIPPLE_MASK_ALL));
        manage.setOnClickListener(v -> {
            dismiss();
            delegate.onManage();
        });
        header.addView(manage, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 0, 0, 0));

        if (items.isEmpty()) {
            final TextView empty = new TextView(context);
            empty.setText(QuickRepliesStrings.empty());
            empty.setTextColor(color(Theme.key_dialogTextGray3));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            empty.setGravity(Gravity.CENTER);
            layout.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 24, 22, 16));

            final TextView add = new TextView(context);
            add.setText(QuickRepliesStrings.add());
            add.setTextColor(color(Theme.key_featuredStickers_buttonText));
            add.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            add.setTypeface(AndroidUtilities.bold());
            add.setGravity(Gravity.CENTER);
            add.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), color(Theme.key_featuredStickers_addButton), color(Theme.key_featuredStickers_addButtonPressed)));
            add.setOnClickListener(v -> {
                dismiss();
                delegate.onManage();
            });
            layout.addView(add, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 16));
        } else {
            final TextView hint = new TextView(context);
            hint.setText(QuickRepliesStrings.hint());
            hint.setTextColor(color(Theme.key_dialogTextGray3));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 6));

            final RecyclerView listView = new RecyclerView(context);
            listView.setLayoutManager(new LinearLayoutManager(context));
            listView.setVerticalScrollBarEnabled(true);
            listView.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @NonNull
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    final TextView row = new TextView(parent.getContext());
                    row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                    row.setTextColor(color(Theme.key_dialogTextBlack));
                    row.setSingleLine(true);
                    row.setEllipsize(TextUtils.TruncateAt.END);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(22), 0, dp(22), 0);
                    row.setBackground(Theme.getSelectorDrawable(false, resourcesProvider));
                    row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT)));
                    return new RecyclerView.ViewHolder(row) {
                    };
                }

                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                    final String text = items.get(position);
                    final TextView row = (TextView) holder.itemView;
                    row.setText(Emoji.replaceEmoji(text.replace('\n', ' '), row.getPaint().getFontMetricsInt(), false));
                    row.setOnClickListener(v -> {
                        dismiss();
                        delegate.onInsert(text);
                    });
                    row.setOnLongClickListener(v -> {
                        dismiss();
                        delegate.onSend(text);
                        return true;
                    });
                }

                @Override
                public int getItemCount() {
                    return items.size();
                }
            });
            final int rows = Math.min(items.size(), MAX_VISIBLE_ROWS);
            // a half row peeks out when the list scrolls
            final int height = dp(ROW_HEIGHT) * rows + (items.size() > MAX_VISIBLE_ROWS ? dp(ROW_HEIGHT / 2) : 0);
            layout.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        }

        setCustomView(layout);
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }
}

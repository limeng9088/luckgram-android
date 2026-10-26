/*
 * Luckgram: quick reply templates management ("Luckgram 功能 → 快捷回复 → 管理", feature 02).
 * Add, edit, delete and drag to reorder. Stored per account, locally.
 */
package org.telegram.luckgram.quickreplies;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;

public class QuickRepliesManageActivity extends BaseFragment {

    private static final int MENU_ADD = 1;
    private static final int TYPE_INFO = 0;
    private static final int TYPE_ITEM = 1;

    private final ArrayList<String> items = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private ItemTouchHelper touchHelper;
    private boolean orderChanged;

    @Override
    public boolean onFragmentCreate() {
        items.addAll(QuickRepliesStore.get(currentAccount));
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        saveOrderIfNeeded();
        super.onFragmentDestroy();
    }

    @Override
    public void onPause() {
        saveOrderIfNeeded();
        super.onPause();
    }

    private void saveOrderIfNeeded() {
        if (orderChanged) {
            orderChanged = false;
            QuickRepliesStore.set(currentAccount, items);
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(QuickRepliesStrings.title());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_ADD) {
                    openEditor(-1);
                }
            }
        });
        actionBar.createMenu().addItem(MENU_ADD, R.drawable.msg_add).setContentDescription(QuickRepliesStrings.add());

        final FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
        fragmentView = frameLayout;

        final RecyclerView listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                if (viewHolder.getItemViewType() != TYPE_ITEM) {
                    return makeMovementFlags(0, 0);
                }
                return super.getMovementFlags(recyclerView, viewHolder);
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                if (to.getItemViewType() != TYPE_ITEM) {
                    return false;
                }
                final int fromIndex = from.getAdapterPosition() - 1;
                final int toIndex = to.getAdapterPosition() - 1;
                if (fromIndex < 0 || toIndex < 0 || fromIndex >= items.size() || toIndex >= items.size()) {
                    return false;
                }
                Collections.swap(items, fromIndex, toIndex);
                adapter.notifyItemMoved(from.getAdapterPosition(), to.getAdapterPosition());
                orderChanged = true;
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                saveOrderIfNeeded();
            }
        });
        touchHelper.attachToRecyclerView(listView);

        return fragmentView;
    }

    private void openEditor(int index) {
        if (getParentActivity() == null) {
            return;
        }
        if (index < 0 && !QuickRepliesLogic.canAdd(items.size())) {
            BulletinFactory.of(this).createErrorBulletin(QuickRepliesStrings.limitReached()).show();
            return;
        }
        final Context context = getParentActivity();
        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        editText.setHint(QuickRepliesStrings.placeholder());
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField, getResourceProvider()), Theme.getColor(Theme.key_dialogInputFieldActivated, getResourceProvider()), Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setMaxLines(6);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(QuickRepliesStore.MAX_LENGTH)});
        editText.setPadding(0, dp(6), 0, dp(6));
        if (index >= 0) {
            editText.setText(items.get(index));
            editText.setSelection(editText.length());
        }
        final FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 6, 24, 0));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(index < 0 ? QuickRepliesStrings.add() : QuickRepliesStrings.edit());
        builder.setView(container);
        builder.setPositiveButton(QuickRepliesStrings.save(), (dialog, which) -> {
            final String text = QuickRepliesStore.normalize(editText.getText());
            if (text == null) {
                return;
            }
            if (index < 0) {
                if (!QuickRepliesLogic.canAdd(items.size())) {
                    BulletinFactory.of(this).createErrorBulletin(QuickRepliesStrings.limitReached()).show();
                    return;
                }
                items.add(text);
            } else if (index < items.size()) {
                items.set(index, text);
            }
            QuickRepliesStore.set(currentAccount, items);
            adapter.notifyDataSetChanged();
        });
        builder.setNegativeButton(QuickRepliesStrings.cancel(), null);
        if (index >= 0) {
            builder.setNeutralButton(QuickRepliesStrings.delete(), (dialog, which) -> confirmDelete(index));
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 150));
        showDialog(dialog);
        if (index >= 0) {
            final View deleteButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (deleteButton instanceof TextView) {
                ((TextView) deleteButton).setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
            }
        }
    }

    private void confirmDelete(int index) {
        if (getParentActivity() == null || index < 0 || index >= items.size()) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(QuickRepliesStrings.delete())
            .setMessage(QuickRepliesStrings.deleteConfirm())
            .setPositiveButton(QuickRepliesStrings.delete(), (d, w) -> {
                if (index < items.size()) {
                    items.remove(index);
                    QuickRepliesStore.set(currentAccount, items);
                    adapter.notifyDataSetChanged();
                }
            })
            .setNegativeButton(QuickRepliesStrings.cancel(), null)
            .makeRed(AlertDialog.BUTTON_POSITIVE)
            .create();
        showDialog(dialog);
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemCount() {
            return 1 + items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_INFO : TYPE_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_INFO) {
                final TextView info = new TextView(parent.getContext());
                info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, getResourceProvider()));
                info.setPadding(dp(21), dp(12), dp(21), dp(12));
                info.setText(QuickRepliesStrings.manageInfo());
                view = info;
            } else {
                view = new ItemRow(parent.getContext());
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() != TYPE_ITEM) {
                return;
            }
            final ItemRow row = (ItemRow) holder.itemView;
            row.set(items.get(position - 1), position < items.size());
            row.setOnClickListener(v -> {
                final int index = holder.getAdapterPosition() - 1;
                if (index >= 0 && index < items.size()) {
                    openEditor(index);
                }
            });
            row.handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder);
                }
                return false;
            });
        }
    }

    @SuppressLint("ViewConstructor")
    private class ItemRow extends LinearLayout {
        final TextView text;
        final ImageView handle;
        private boolean divider;

        ItemRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(52));
            setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()), Theme.getColor(Theme.key_listSelector, getResourceProvider())));
            setWillNotDraw(false);

            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 21, 10, 8, 10));

            handle = new ImageView(context);
            handle.setImageResource(R.drawable.list_reorder);
            handle.setScaleType(ImageView.ScaleType.CENTER);
            handle.setColorFilter(Theme.getColor(Theme.key_stickers_menu, getResourceProvider()));
            handle.setContentDescription(QuickRepliesStrings.reorder());
            addView(handle, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        }

        void set(String value, boolean divider) {
            this.divider = divider;
            text.setText(Emoji.replaceEmoji(value, text.getPaint().getFontMetricsInt(), false));
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (divider) {
                canvas.drawLine(dp(21), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}

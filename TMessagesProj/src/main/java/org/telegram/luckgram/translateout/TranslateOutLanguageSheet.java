/*
 * Luckgram: target-language picker for "translate before sending".
 * Frequently used languages first, then all languages; searchable by name in any language.
 */
package org.telegram.luckgram.translateout;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;

public class TranslateOutLanguageSheet extends BottomSheet {

    public interface Callback {
        void onSelected(String code);
    }

    private static class Item {
        final String header;
        final String code;
        final String name;
        final String own;
        final String query;

        Item(String header) {
            this.header = header;
            this.code = null;
            this.name = null;
            this.own = null;
            this.query = null;
        }

        Item(String code, String name, String own) {
            this.header = null;
            this.code = code;
            this.name = name;
            this.own = own;
            this.query = (name + " " + (own == null ? "" : own) + " " + code).toLowerCase(Locale.ROOT);
        }
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final String selected;
    private final Callback callback;
    private final ArrayList<Item> common = new ArrayList<>();
    private final ArrayList<Item> all = new ArrayList<>();
    private final ArrayList<Item> shown = new ArrayList<>();
    private final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private final TextView emptyView;

    public TranslateOutLanguageSheet(Context context, int account, String selected, Theme.ResourcesProvider resourcesProvider, Callback callback) {
        super(context, true, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        this.selected = selected;
        this.callback = callback;
        fixNavigationBar();
        buildLists(account);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final TextView title = new TextView(context);
        title.setText(TranslateOutStrings.pickerTitle());
        title.setTextColor(color(Theme.key_dialogTextBlack));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 4, 22, 10));

        final FrameLayout searchBox = new FrameLayout(context);
        searchBox.setBackground(Theme.createRoundRectDrawable(dp(18), color(Theme.key_dialogSearchBackground)));
        final ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.msg_search);
        searchIcon.setScaleType(ImageView.ScaleType.CENTER);
        searchIcon.setColorFilter(color(Theme.key_dialogSearchHint));
        searchBox.addView(searchIcon, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        final EditTextBoldCursor search = new EditTextBoldCursor(context);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        search.setHintTextColor(color(Theme.key_dialogSearchHint));
        search.setTextColor(color(Theme.key_dialogSearchText));
        search.setCursorColor(color(Theme.key_dialogSearchText));
        search.setCursorSize(dp(20));
        search.setCursorWidth(1.5f);
        search.setBackground(null);
        search.setPadding(0, 0, 0, 0);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setHint(TranslateOutStrings.search());
        searchBox.addView(search, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL, 42, 0, 12, 0));
        layout.addView(searchBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 14, 0, 14, 6));

        final FrameLayout listContainer = new FrameLayout(context);
        final RecyclerView listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(true);
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listContainer.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(TranslateOutStrings.noResults());
        emptyView.setTextColor(color(Theme.key_dialogTextGray3));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        listContainer.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 80, Gravity.TOP));

        final int listHeight = (int) (Math.max(AndroidUtilities.displaySize.y, dp(400)) * 0.55f);
        layout.addView(listContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(s == null ? null : s.toString());
            }
        });

        filter(null);
        setCustomView(layout);
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private void buildLists(int account) {
        final LinkedHashSet<String> commonCodes = new LinkedHashSet<>();
        if (selected != null) {
            commonCodes.add(selected);
        }
        commonCodes.addAll(TranslateOutController.getRecentLanguages(account));
        for (String c : TranslateOutController.COMMON_LANGUAGES) {
            commonCodes.add(c);
        }
        for (String code : commonCodes) {
            common.add(item(code));
        }
        final ArrayList<TranslateController.Language> languages = TranslateController.getLanguages();
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (TranslateController.Language l : languages) {
            String code = l.code;
            if (code == null) continue;
            code = code.toLowerCase(Locale.ROOT);
            if (code.startsWith("zh")) {
                code = "zh";
            }
            if (!seen.add(code)) continue;
            all.add(item(code));
        }
        java.util.Collections.sort(all, (a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    private static Item item(String code) {
        final String name = TranslateOutController.languageName(code);
        String own = TranslateOutController.ownLanguageName(code);
        if (own != null && own.equalsIgnoreCase(name)) {
            own = null;
        }
        return new Item(code, name, own);
    }

    private void filter(String q) {
        shown.clear();
        if (TextUtils.isEmpty(q) || TextUtils.isEmpty(q.trim())) {
            shown.add(new Item(TranslateOutStrings.common()));
            shown.addAll(common);
            shown.add(new Item(TranslateOutStrings.all()));
            shown.addAll(all);
        } else {
            final String query = q.trim().toLowerCase(Locale.ROOT);
            for (Item it : all) {
                if (it.query.contains(query)) {
                    shown.add(it);
                }
            }
        }
        emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return shown.get(position).header != null ? 0 : 1;
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            final View view;
            if (viewType == 0) {
                final TextView header = new TextView(context);
                header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                header.setTypeface(AndroidUtilities.bold());
                header.setTextColor(color(Theme.key_windowBackgroundWhiteBlueHeader));
                header.setPadding(dp(22), dp(14), dp(22), dp(6));
                view = header;
            } else {
                view = new LanguageRow(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Item item = shown.get(position);
            if (item.header != null) {
                ((TextView) holder.itemView).setText(item.header);
            } else {
                final LanguageRow row = (LanguageRow) holder.itemView;
                row.set(item, item.code.equals(selected));
                row.setOnClickListener(v -> {
                    dismiss();
                    callback.onSelected(item.code);
                });
            }
        }
    }

    private class LanguageRow extends LinearLayout {
        private final TextView name;
        private final TextView own;
        private final ImageView check;

        LanguageRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(52));
            setPadding(dp(22), dp(6), dp(18), dp(6));
            setBackground(Theme.getSelectorDrawable(false, resourcesProvider));

            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTextColor(color(Theme.key_dialogTextBlack));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            own = new TextView(context);
            own.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            own.setTextColor(color(Theme.key_dialogTextGray3));
            own.setSingleLine(true);
            own.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(own);
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            check = new ImageView(context);
            check.setImageResource(R.drawable.checkbig);
            check.setColorFilter(color(Theme.key_dialogTextBlue2));
            check.setScaleType(ImageView.ScaleType.CENTER);
            addView(check, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }

        void set(Item item, boolean isSelected) {
            name.setText(item.name);
            own.setText(item.own);
            own.setVisibility(TextUtils.isEmpty(item.own) ? GONE : VISIBLE);
            check.setVisibility(isSelected ? VISIBLE : INVISIBLE);
        }
    }
}

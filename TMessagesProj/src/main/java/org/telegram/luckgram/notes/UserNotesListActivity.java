/*
 * Luckgram — private notes (spec 05).
 * Licensed under GNU GPL v. 2 or later.
 */

package org.telegram.luckgram.notes;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.Locale;

/** "All notes": every private note of the current account, searchable by note text and name. */
public class UserNotesListActivity extends BaseFragment {

    private static final int VIEW_TYPE_NOTE = 0;
    private static final int VIEW_TYPE_INFO = 1;

    private static final class Item {
        final long peerId;
        final Object peer; // TLRPC.User or TLRPC.Chat, may be null
        final String name;
        final String note;

        Item(long peerId, Object peer, String name, String note) {
            this.peerId = peerId;
            this.peer = peer;
            this.name = name;
            this.note = note;
        }
    }

    private final ArrayList<Item> allItems = new ArrayList<>();
    private final ArrayList<Item> shownItems = new ArrayList<>();
    private String query = "";

    private RecyclerListView listView;
    private ListAdapter adapter;
    private EmptyTextProgressView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(UserNotesStrings.allNotes());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchCollapse() {
                applyQuery("");
            }

            @Override
            public void onTextChanged(EditText editText) {
                applyQuery(editText.getText().toString());
            }
        });
        searchItem.setSearchFieldHint(UserNotesStrings.searchHint());
        searchItem.setContentDescription(UserNotesStrings.searchHint());

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        emptyView = new EmptyTextProgressView(context);
        emptyView.showTextView();
        emptyView.setShowAtCenter(true);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setEmptyView(emptyView);
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= shownItems.size()) {
                return;
            }
            Item item = shownItems.get(position);
            Bundle args = new Bundle();
            if (item.peerId > 0) {
                args.putLong("user_id", item.peerId);
            } else {
                args.putLong("chat_id", -item.peerId);
            }
            presentFragment(new ProfileActivity(args));
        });
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        allItems.clear();
        for (UserNotesStore.Entry e : UserNotesStore.getAll(currentAccount)) {
            Object peer = null;
            String name = null;
            if (e.peerId > 0) {
                TLRPC.User user = getMessagesController().getUser(e.peerId);
                if (user == null) {
                    user = getMessagesStorage().getUserSync(e.peerId);
                    if (user != null) {
                        getMessagesController().putUser(user, true);
                    }
                }
                if (user != null) {
                    peer = user;
                    name = UserObject.getUserName(user);
                }
            } else {
                TLRPC.Chat chat = getMessagesController().getChat(-e.peerId);
                if (chat == null) {
                    chat = getMessagesStorage().getChatSync(-e.peerId);
                    if (chat != null) {
                        getMessagesController().putChat(chat, true);
                    }
                }
                if (chat != null) {
                    peer = chat;
                    name = chat.title;
                }
            }
            if (TextUtils.isEmpty(name)) {
                name = UserNotesStrings.unknownPeer();
            }
            allItems.add(new Item(e.peerId, peer, name, e.note));
        }
        applyQuery(query);
    }

    private void applyQuery(String q) {
        query = q == null ? "" : q.trim();
        shownItems.clear();
        String needle = query.toLowerCase(Locale.getDefault());
        for (Item item : allItems) {
            if (needle.isEmpty()
                    || item.note.toLowerCase(Locale.getDefault()).contains(needle)
                    || item.name.toLowerCase(Locale.getDefault()).contains(needle)) {
                shownItems.add(item);
            }
        }
        if (emptyView != null) {
            emptyView.setText(query.isEmpty() ? UserNotesStrings.noNotes() : UserNotesStrings.noResults());
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** Text shown under the name: the line of the note that matches the query, else the first line. */
    private String snippet(Item item) {
        if (!query.isEmpty()) {
            String needle = query.toLowerCase(Locale.getDefault());
            for (String line : item.note.split("\n")) {
                if (line.toLowerCase(Locale.getDefault()).contains(needle)) {
                    return line.trim();
                }
            }
        }
        return UserNotesStore.firstLine(item.note);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_NOTE;
        }

        @Override
        public int getItemCount() {
            return shownItems.isEmpty() ? 0 : shownItems.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position < shownItems.size() ? VIEW_TYPE_NOTE : VIEW_TYPE_INFO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_NOTE) {
                view = new UserCell(context, 6, 0, false);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context);
                cell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                view = cell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_NOTE) {
                Item item = shownItems.get(position);
                ((UserCell) holder.itemView).setData(item.peer, item.name, snippet(item), 0, position != shownItems.size() - 1);
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText(UserNotesStrings.editInfo());
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null, Theme.key_actionBarDefaultSearch));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null, null, null, Theme.key_actionBarDefaultSearchPlaceholder));
        list.add(new ThemeDescription(emptyView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_emptyListPlaceholder));
        return list;
    }
}

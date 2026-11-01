package org.telegram.luckgram.square;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Author card (spec 26 item 3): display name, avatar, bio and the author's posts; "Send message"
 * when the author chose to show a public username. Report / block from the "⋯" menu.
 */
public class SquareAuthorActivity extends UniversalFragment {

    private static final int MENU_MORE = 1;
    private static final int SUB_REPORT = 10;
    private static final int SUB_BLOCK = 11;
    private static final int ID_HEADER = 1;
    private static final int ID_MESSAGE = 2;
    private static final int ID_LOAD_MORE = 3;
    private static final int ID_RETRY = 4;
    private static final int ID_POST = 1000;

    private final SquareLogic.Texts tx = SquareUi.tx();
    private final String userId;
    private SquareLogic.Author author;
    private final ArrayList<SquareLogic.Post> posts = new ArrayList<>();
    private String nextCursor;
    private boolean loading;
    private boolean loadedOnce;
    private boolean failed;
    private final ArrayList<LuckgramAI.Request> requests = new ArrayList<>();

    public SquareAuthorActivity(SquareLogic.Author author) {
        this.author = author;
        this.userId = author.id;
    }

    public SquareAuthorActivity(String userId) {
        this.userId = userId;
    }

    @Override
    protected CharSequence getTitle() {
        return author != null && !author.displayName.isEmpty() ? author.displayName : tx.title();
    }

    private boolean isMe() {
        return SquareUi.myAuthorId != null && SquareUi.myAuthorId.equals(userId);
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        actionBar.setAllowOverlayTitle(false); // keep our title; the chat list shows the connection state
        ActionBarMenu menu = actionBar.createMenu();
        if (!isMe()) {
            ActionBarMenuItem more = menu.addItem(MENU_MORE, R.drawable.ic_ab_other);
            more.setContentDescription(tx.more());
            more.addSubItem(SUB_REPORT, R.drawable.msg_report, tx.reportAuthor());
            more.addSubItem(SUB_BLOCK, R.drawable.msg_block, tx.block());
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == SUB_REPORT) {
                    SquareUi.report(SquareAuthorActivity.this, "user", userId);
                } else if (id == SUB_BLOCK && author != null) {
                    SquareUi.block(SquareAuthorActivity.this, author, () -> {
                        SquareActivity.dirty = true;
                        finishFragment();
                    });
                }
            }
        });
        return v;
    }

    @Override
    public boolean onFragmentCreate() {
        SquareUi.prefetchMe();
        loadAuthor();
        reloadPosts();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        for (LuckgramAI.Request r : requests) r.cancel();
        super.onFragmentDestroy();
    }

    private void update() {
        if (listView != null) listView.adapter.update(true);
        if (actionBar != null) actionBar.setTitle(getTitle());
    }

    private void loadAuthor() {
        requests.add(LuckgramAI.getInstance().requestJson("GET", "/v1/square/users/" + SquareLogic.pathId(userId), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                SquareLogic.Author a = SquareLogic.parseAuthor(result.optJSONObject("author") != null ? result.optJSONObject("author") : result);
                if (!a.id.isEmpty() || author == null) author = a;
                update();
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (author == null && (error.httpStatus == 404 || "not_found".equals(error.code))) {
                    SquareUi.bulletin(SquareAuthorActivity.this).createErrorBulletin(tx.authorGone()).show();
                    finishFragment();
                }
            }
        }));
    }

    private void reloadPosts() {
        posts.clear();
        nextCursor = null;
        loadedOnce = false;
        loadPosts();
    }

    private void loadPosts() {
        loading = true;
        failed = false;
        update();
        String path = SquareLogic.withCursor("/v1/square/users/" + SquareLogic.pathId(userId) + "/posts", nextCursor);
        requests.add(LuckgramAI.getInstance().requestJson("GET", path, null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
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
                if (!LuckgramAIError.CANCELLED.equals(error.code)) SquareUi.showError(SquareAuthorActivity.this, error);
            }
        }));
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context ctx = getContext();
        if (author != null) {
            items.add(UItem.asCustom(ID_HEADER, headerView(ctx)));
            if (author.username != null && !author.username.isEmpty()) {
                items.add(UItem.asButton(ID_MESSAGE, R.drawable.msg_message, tx.sendMessage()).accent());
            }
            items.add(UItem.asShadow(null));
        }
        items.add(UItem.asHeader(tx.authorPosts()));
        for (int i = 0; i < posts.size(); i++) {
            items.add(UItem.asCustom(ID_POST + i, SquareUi.postView(ctx, posts.get(i), false, actions)));
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

    private View headerView(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        box.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(20), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
        box.addView(SquareUi.avatar(ctx, author, 80), LayoutHelper.createLinear(80, 80));
        TextView name = new TextView(ctx);
        name.setText(author.displayName);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setGravity(Gravity.CENTER);
        box.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        if (author.username != null && !author.username.isEmpty()) {
            TextView u = new TextView(ctx);
            u.setText("@" + author.username);
            u.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            u.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            box.addView(u, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }
        if (!TextUtils.isEmpty(author.bio)) {
            TextView bio = new TextView(ctx);
            bio.setText(author.bio);
            bio.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            bio.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            bio.setGravity(Gravity.CENTER);
            box.addView(bio, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }
        return box;
    }

    private final SquareUi.PostActions actions = new SquareUi.PostActions() {
        @Override
        public void onAuthor(SquareLogic.Post p) {
            // already on the author's page
        }

        @Override
        public void onOpen(SquareLogic.Post p) {
            presentFragment(new SquarePostActivity(p));
        }

        @Override
        public void onLike(SquareLogic.Post p) {
            SquareUi.toggleLike(SquareAuthorActivity.this, p, () -> {
                SquareActivity.dirty = true;
                update();
            });
        }

        @Override
        public void onVideo(SquareLogic.Post p) {
            if (p.video != null) presentFragment(new SquareVideoActivity(p.video));
        }

        @Override
        public void onMore(SquareLogic.Post p) {
            SquareActivity.showPostMenu(SquareAuthorActivity.this, p, () -> {
                posts.remove(p);
                update();
            }, SquareAuthorActivity.this::finishFragment);
        }
    };

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_MESSAGE && author != null && author.username != null) {
            getMessagesController().openByUserName(author.username, this, 1);
        } else if (item.id == ID_LOAD_MORE && !loading) {
            loadPosts();
        } else if (item.id == ID_RETRY) {
            if (posts.isEmpty()) reloadPosts();
            else loadPosts();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

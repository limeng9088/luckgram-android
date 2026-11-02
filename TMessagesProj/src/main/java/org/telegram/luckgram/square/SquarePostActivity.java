package org.telegram.luckgram.square;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * One post with its comments (spec 26 item 5): one level of comments plus one level of replies,
 * each ≤ 300 characters. Comment authors delete their own comments; the post author can delete
 * any comment under the post. Reading needs no link, writing goes through {@link SquareUi#requireLink}.
 */
public class SquarePostActivity extends UniversalFragment {

    private static final int ID_POST = 1;
    private static final int ID_COMMENTS_HEADER = 2;
    private static final int ID_INPUT = 3;
    private static final int ID_RETRY = 4;
    private static final int ID_COMMENT = 1000;

    private final SquareLogic.Texts tx = SquareUi.tx();
    private final String postId;
    private SquareLogic.Post post;
    private final ArrayList<SquareLogic.Comment> comments = new ArrayList<>();
    private List<SquareLogic.Comment> threaded = new ArrayList<>();
    private boolean loadingPost;
    private boolean loadingComments;
    private boolean failed;
    private boolean sending;
    private SquareLogic.Comment replyTo;
    private EditTextBoldCursor input;
    private String draft = "";
    private final ArrayList<LuckgramAI.Request> requests = new ArrayList<>();

    public SquarePostActivity(SquareLogic.Post post) {
        this.post = post;
        this.postId = post.id;
    }

    public SquarePostActivity(String postId) {
        this.postId = postId;
    }

    @Override
    protected CharSequence getTitle() {
        return tx.title();
    }

    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        actionBar.setAllowOverlayTitle(false); // keep our title; the chat list shows the connection state
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        return v;
    }

    @Override
    public boolean onFragmentCreate() {
        SquareUi.prefetchMe();
        reload();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        for (LuckgramAI.Request r : requests) r.cancel();
        super.onFragmentDestroy();
    }

    private void update() {
        if (listView != null) listView.adapter.update(true);
    }

    private void reload() {
        failed = false;
        loadPost();
        loadComments();
    }

    private void loadPost() {
        loadingPost = true;
        update();
        requests.add(LuckgramAI.getInstance().requestJson("GET", "/v1/square/posts/" + SquareLogic.pathId(postId), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                loadingPost = false;
                SquareLogic.Post p = SquareLogic.parsePost(result);
                if (!p.id.isEmpty()) post = p;
                update();
            }

            @Override
            public void onError(LuckgramAIError error) {
                loadingPost = false;
                if (error.httpStatus == 404 || "not_found".equals(error.code)) {
                    SquareUi.bulletin(SquarePostActivity.this).createErrorBulletin(tx.postGone()).show();
                    SquareActivity.dirty = true;
                    finishFragment();
                    return;
                }
                if (post == null) failed = true;
                update();
                SquareUi.showError(SquarePostActivity.this, error);
            }
        }));
    }

    private void loadComments() {
        loadingComments = true;
        update();
        requests.add(LuckgramAI.getInstance().requestJson("GET", "/v1/square/posts/" + SquareLogic.pathId(postId) + "/comments", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                loadingComments = false;
                comments.clear();
                comments.addAll(SquareLogic.parseComments(result).items);
                rethread();
                update();
            }

            @Override
            public void onError(LuckgramAIError error) {
                loadingComments = false;
                update();
                if (!LuckgramAIError.CANCELLED.equals(error.code)) SquareUi.showError(SquarePostActivity.this, error);
            }
        }));
    }

    private void rethread() {
        threaded = SquareLogic.thread(new ArrayList<>(comments));
        if (post != null) post.commentCount = comments.size();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context ctx = getContext();
        if (post != null) {
            items.add(UItem.asCustom(ID_POST, SquareUi.postView(ctx, post, true, actions)));
        } else if (loadingPost) {
            items.add(UItem.asButtonLoadingValue(ID_POST, tx.loading()).setEnabled(false));
        } else if (failed) {
            items.add(UItem.asButton(ID_RETRY, tx.refresh()).accent());
            return;
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(tx.commentsHeader()));
        if (loadingComments && comments.isEmpty()) {
            items.add(UItem.asButtonLoadingValue(ID_COMMENTS_HEADER, tx.commentsLoading()).setEnabled(false));
        } else if (threaded.isEmpty()) {
            items.add(UItem.asShadow(tx.noComments()));
        } else {
            for (int i = 0; i < threaded.size(); i++) {
                final SquareLogic.Comment c = threaded.get(i);
                items.add(UItem.asCustom(ID_COMMENT + i, SquareUi.commentView(ctx, c, v -> showCommentMenu(c))));
            }
            items.add(UItem.asShadow(null));
        }
        items.add(UItem.asCustom(ID_INPUT, inputView(ctx)));
    }

    private View inputView(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        if (replyTo != null) {
            LinearLayout banner = new LinearLayout(ctx);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(8), 0);
            TextView t = new TextView(ctx);
            t.setText(tx.replyingTo(replyTo.author.displayName));
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            banner.addView(t, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            TextView cancel = new TextView(ctx);
            cancel.setText(tx.cancelReply());
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            cancel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            cancel.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4));
            cancel.setOnClickListener(v -> {
                replyTo = null;
                update();
            });
            banner.addView(cancel);
            box.addView(banner);
        }
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4));
        if (input == null) {
            input = new EditTextBoldCursor(ctx);
            input.setHint(tx.commentHint());
            input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            input.setBackgroundDrawable(null);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SquareLogic.COMMENT_MAX * 2)});
            input.setMaxLines(4);
            input.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10));
            input.setText(draft);
        }
        if (input.getParent() instanceof LinearLayout) {
            ((LinearLayout) input.getParent()).removeView(input);
        }
        row.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        ImageView send = new ImageView(ctx);
        send.setImageResource(R.drawable.msg_send);
        send.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        send.setContentDescription(tx.send());
        send.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        send.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        send.setAlpha(sending ? 0.5f : 1f);
        send.setOnClickListener(v -> sendComment());
        row.addView(send, LayoutHelper.createLinear(44, 44));
        box.addView(row);
        return box;
    }

    private void sendComment() {
        if (sending || post == null || input == null) return;
        String text = input.getText().toString();
        draft = text;
        if (!SquareLogic.isValidComment(text)) {
            SquareUi.bulletin(this).createErrorBulletin(SquareLogic.length(text.trim()) > SquareLogic.COMMENT_MAX ? tx.tooLong() : tx.needContent()).show();
            return;
        }
        SquareUi.requireProfile(this, () -> {
            sending = true;
            update();
            try {
                String parentId = replyTo != null ? SquareLogic.replyParentId(comments, replyTo) : null;
                requests.add(LuckgramAI.getInstance().requestJson("POST", "/v1/square/posts/" + SquareLogic.pathId(postId) + "/comments", SquareLogic.commentBody(text, parentId), new LuckgramAI.Callback<JSONObject>() {
                    @Override
                    public void onResult(JSONObject result) {
                        sending = false;
                        SquareLogic.Comment c = SquareLogic.parseComment(result);
                        if (!c.id.isEmpty()) {
                            if (!c.author.id.isEmpty()) SquareUi.myAuthorId = c.author.id;
                            comments.add(c);
                            rethread();
                        }
                        replyTo = null;
                        draft = "";
                        if (input != null) {
                            input.setText("");
                            AndroidUtilities.hideKeyboard(input);
                        }
                        SquareActivity.dirty = true;
                        update();
                        SquareUi.bulletin(SquarePostActivity.this).createSimpleBulletin(R.raw.done, tx.commentSent()).show();
                    }

                    @Override
                    public void onError(LuckgramAIError error) {
                        sending = false;
                        update();
                        SquareUi.showError(SquarePostActivity.this, error);
                    }
                }));
            } catch (Exception e) {
                FileLog.e(e);
                sending = false;
                update();
            }
        });
    }

    /** Comment tap: reply / delete (own, or any under my post) / report. */
    private void showCommentMenu(SquareLogic.Comment c) {
        if (getParentActivity() == null) return;
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        labels.add(tx.reply());
        acts.add(() -> {
            replyTo = c;
            update();
            if (input != null) {
                input.requestFocus();
                AndroidUtilities.showKeyboard(input);
            }
        });
        if (SquareLogic.canDeleteComment(c, post, SquareUi.myAuthorId)) {
            labels.add(tx.delete());
            acts.add(() -> new AlertDialog.Builder(getParentActivity())
                .setTitle(tx.delete())
                .setMessage(tx.deleteCommentConfirm())
                .setPositiveButton(tx.delete(), (d, w) -> deleteComment(c))
                .setNegativeButton(tx.cancel(), null)
                .show());
        }
        if (!c.author.id.equals(SquareUi.myAuthorId)) {
            labels.add(tx.reportComment());
            acts.add(() -> SquareUi.report(this, "comment", c.id));
        }
        new AlertDialog.Builder(getParentActivity())
            .setTitle(c.author.displayName)
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> AndroidUtilities.runOnUIThread(acts.get(which)))
            .show();
    }

    private void deleteComment(SquareLogic.Comment c) {
        requests.add(LuckgramAI.getInstance().requestJson("DELETE", "/v1/square/comments/" + SquareLogic.pathId(c.id), null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                // Replies to a deleted comment go with it (they would become orphans / top comments).
                for (int i = comments.size() - 1; i >= 0; i--) {
                    SquareLogic.Comment x = comments.get(i);
                    if (x == c || c.id.equals(x.parentId)) comments.remove(i);
                }
                if (replyTo == c) replyTo = null;
                rethread();
                SquareActivity.dirty = true;
                update();
                SquareUi.bulletin(SquarePostActivity.this).createSimpleBulletin(R.raw.done, tx.deleted()).show();
            }

            @Override
            public void onError(LuckgramAIError error) {
                SquareUi.showError(SquarePostActivity.this, error);
            }
        }));
    }

    private final SquareUi.PostActions actions = new SquareUi.PostActions() {
        @Override
        public void onAuthor(SquareLogic.Post p) {
            presentFragment(new SquareAuthorActivity(p.author));
        }

        @Override
        public void onOpen(SquareLogic.Post p) {
            if (input != null) {
                input.requestFocus();
                AndroidUtilities.showKeyboard(input);
            }
        }

        @Override
        public void onLike(SquareLogic.Post p) {
            SquareUi.toggleLike(SquarePostActivity.this, p, () -> {
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
            SquareActivity.showPostMenu(SquarePostActivity.this, p, SquarePostActivity.this::finishFragment, SquarePostActivity.this::finishFragment);
        }
    };

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) reload();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

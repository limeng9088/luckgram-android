package org.telegram.luckgram.square;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.account.LuckgramAccountActivity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Shared pieces of the Square pages (spec 26): link / profile checks before writes, error
 * messages, report and block dialogs, and the post card view.
 */
public final class SquareUi {

    private SquareUi() {
    }

    public static final SquareLogic.Texts tx() {
        return new SquareLogic.Texts(LuckgramStrings.isChinese());
    }

    public static boolean isFeatureEnabled() {
        return LuckgramSettings.isEnabled(SquareLogic.FEATURE_KEY);
    }

    // ---------------------------------------------------------------- link / profile

    private static Boolean linked;
    private static JSONObject profile;
    /** Author id of this device's user in Square (from the profile response), if known. */
    public static String myAuthorId;

    /** Forget cached state (unlink, logout, "delete my Square data"). */
    public static void clearCache() {
        linked = null;
        profile = null;
        myAuthorId = null;
    }

    public static void refreshLink(Runnable done) {
        LuckgramAI.getInstance().requestJson("GET", "/v1/link", null, new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject result) {
                linked = result.optBoolean("linked", false);
                if (!linked) {
                    profile = null;
                    myAuthorId = null;
                }
                if (done != null) done.run();
            }

            @Override
            public void onError(LuckgramAIError error) {
                if (done != null) done.run();
            }
        });
    }

    /** Runs {@code then} if this device is linked (S6); otherwise shows the link prompt. */
    public static void requireLink(BaseFragment f, Runnable then) {
        if (Boolean.TRUE.equals(linked)) {
            then.run();
            return;
        }
        refreshLink(() -> {
            if (Boolean.TRUE.equals(linked)) then.run();
            else showLinkPrompt(f);
        });
    }

    public static void showLinkPrompt(BaseFragment f) {
        if (f.getParentActivity() == null) return;
        SquareLogic.Texts tx = tx();
        new AlertDialog.Builder(f.getParentActivity())
            .setTitle(tx.needLinkTitle())
            .setMessage(tx.needLinkText())
            .setPositiveButton(tx.goLink(), (d, w) -> f.presentFragment(new LuckgramAccountActivity()))
            .setNegativeButton(tx.cancel(), null)
            .show();
    }

    /** Linked and has a Square profile: then {@code then}; otherwise asks for whatever is missing. */
    public static void requireProfile(BaseFragment f, Runnable then) {
        requireLink(f, () -> {
            if (profile != null) {
                then.run();
                return;
            }
            LuckgramAI.getInstance().requestJson("GET", "/v1/square/profile", null, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    if (result.optString("display_name", "").isEmpty()) {
                        editProfile(f, null, then);
                    } else {
                        setProfile(result);
                        then.run();
                    }
                }

                @Override
                public void onError(LuckgramAIError error) {
                    if ("not_found".equals(error.code) || "profile_required".equals(error.code) || error.httpStatus == 404) {
                        editProfile(f, null, then);
                    } else {
                        showError(f, error);
                    }
                }
            });
        });
    }

    private static void setProfile(JSONObject p) {
        profile = p;
        String id = p.optString("id", "");
        if (!id.isEmpty()) myAuthorId = id;
    }

    /** Edit (or first fill in) the Square profile; {@code then} runs after a successful save. */
    public static void editProfile(BaseFragment f, JSONObject current, Runnable then) {
        Context ctx = f.getParentActivity();
        if (ctx == null) return;
        SquareLogic.Texts tx = tx();
        JSONObject cur = current != null ? current : profile;
        TLRPC.User me = f.getUserConfig().getCurrentUser();
        String myUsername = UserObject.getPublicUsername(me);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);
        if (cur == null) {
            TextView info = new TextView(ctx);
            info.setText(tx.profileNeeded());
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            info.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            box.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
        EditText name = field(ctx, tx.displayName(), 64, false);
        name.setText(cur != null ? cur.optString("display_name", "") : (me != null ? UserObject.getUserName(me) : ""));
        box.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        EditText bio = field(ctx, tx.bio(), SquareLogic.PROFILE_BIO_MAX, true);
        bio.setText(cur != null ? cur.optString("bio", "") : "");
        box.addView(bio, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        final boolean[] showUsername = {cur != null ? !cur.optString("username", "").isEmpty() : false};
        org.telegram.ui.Cells.CheckBoxCell check = null;
        if (!TextUtils.isEmpty(myUsername)) {
            check = new org.telegram.ui.Cells.CheckBoxCell(ctx, 1);
            check.setText(tx.useMyUsername(myUsername), "", showUsername[0], false);
            final org.telegram.ui.Cells.CheckBoxCell c = check;
            check.setOnClickListener(v -> {
                showUsername[0] = !showUsername[0];
                c.setChecked(showUsername[0], true);
            });
            box.addView(check, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -22, 8, -22, 0));
        }
        new AlertDialog.Builder(ctx)
            .setTitle(cur == null ? tx.profileTitle() : tx.editProfile())
            .setView(box)
            .setPositiveButton(tx.save(), (d, w) -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) return;
                try {
                    JSONObject body = new JSONObject()
                        .put("display_name", n)
                        .put("bio", bio.getText().toString().trim())
                        .put("username", showUsername[0] && myUsername != null ? myUsername : "");
                    LuckgramAI.getInstance().requestJson("PUT", "/v1/square/profile", body, new LuckgramAI.Callback<JSONObject>() {
                        @Override
                        public void onResult(JSONObject result) {
                            setProfile(result.optString("display_name", "").isEmpty() ? body : result);
                            BulletinFactory.of(f).createSimpleBulletin(R.raw.done, tx.saved()).show();
                            if (then != null) then.run();
                        }

                        @Override
                        public void onError(LuckgramAIError error) {
                            showError(f, error);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            })
            .setNegativeButton(tx.cancel(), null)
            .show();
    }

    private static EditText field(Context ctx, String hint, int max, boolean multiline) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        e.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMaxLines(3);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    // ---------------------------------------------------------------- errors

    public static void showError(BaseFragment f, LuckgramAIError error) {
        if (error == null || LuckgramAIError.CANCELLED.equals(error.code) || f.getParentActivity() == null) return;
        SquareLogic.Texts tx = tx();
        if ("not_linked".equals(error.code)) {
            linked = false;
            showLinkPrompt(f);
            return;
        }
        if ("profile_required".equals(error.code)) {
            profile = null;
            editProfile(f, null, null);
            return;
        }
        String text;
        if (LuckgramAIError.RATE_LIMITED.equals(error.code)) {
            text = tx.waitSeconds(error.retryAfterSeconds);
        } else if ("banned".equals(error.code)) {
            text = tx.banned();
        } else {
            text = error.getLocalizedMessage();
        }
        BulletinFactory.of(f).createErrorBulletin(text).show();
    }

    // ---------------------------------------------------------------- report / block

    /** {@code target}: post, comment or user. */
    public static void report(BaseFragment f, String target, String id) {
        requireLink(f, () -> {
            if (f.getParentActivity() == null) return;
            SquareLogic.Texts tx = tx();
            CharSequence[] labels = new CharSequence[SquareLogic.REPORT_REASONS.length];
            for (int i = 0; i < labels.length; i++) labels[i] = tx.reportReason(SquareLogic.REPORT_REASONS[i]);
            new AlertDialog.Builder(f.getParentActivity())
                .setTitle(tx.reportTitle())
                .setItems(labels, (d, which) -> {
                    try {
                        LuckgramAI.getInstance().requestJson("POST", "/v1/square/report", SquareLogic.reportBody(target, id, SquareLogic.REPORT_REASONS[which]), new LuckgramAI.Callback<JSONObject>() {
                            @Override
                            public void onResult(JSONObject result) {
                                BulletinFactory.of(f).createSimpleBulletin(R.raw.done, tx.reported()).show();
                            }

                            @Override
                            public void onError(LuckgramAIError error) {
                                showError(f, error);
                            }
                        });
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                })
                .setNegativeButton(tx.cancel(), null)
                .show();
        });
    }

    /** Blocks the author (shared with People Nearby, S10/S11) after a confirmation. */
    public static void block(BaseFragment f, SquareLogic.Author author, Runnable done) {
        requireLink(f, () -> {
            if (f.getParentActivity() == null) return;
            SquareLogic.Texts tx = tx();
            new AlertDialog.Builder(f.getParentActivity())
                .setTitle(tx.block())
                .setMessage(tx.blockConfirm(author.displayName))
                .setPositiveButton(tx.block(), (d, w) -> {
                    try {
                        LuckgramAI.getInstance().requestJson("POST", "/v1/blocks", new JSONObject().put("user", author.id), new LuckgramAI.Callback<JSONObject>() {
                            @Override
                            public void onResult(JSONObject result) {
                                BulletinFactory.of(f).createSimpleBulletin(R.raw.done, tx.blocked()).show();
                                if (done != null) done.run();
                            }

                            @Override
                            public void onError(LuckgramAIError error) {
                                showError(f, error);
                            }
                        });
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                })
                .setNegativeButton(tx.cancel(), null)
                .show();
        });
    }

    /** Toggles the like; updates {@code p} and calls {@code changed} with the server counts. */
    public static void toggleLike(BaseFragment f, SquareLogic.Post p, Runnable changed) {
        requireLink(f, () -> {
            boolean like = !p.liked;
            LuckgramAI.getInstance().requestJson(like ? "POST" : "DELETE", "/v1/square/posts/" + SquareLogic.pathId(p.id) + "/like", null, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    p.liked = result.optBoolean("liked", like);
                    p.likeCount = Math.max(0, result.optInt("like_count", p.likeCount + (like ? 1 : -1)));
                    changed.run();
                }

                @Override
                public void onError(LuckgramAIError error) {
                    showError(f, error);
                }
            });
        });
    }

    // ---------------------------------------------------------------- views

    public interface PostActions {
        void onAuthor(SquareLogic.Post p);
        void onOpen(SquareLogic.Post p);
        void onLike(SquareLogic.Post p);
        void onMore(SquareLogic.Post p);
    }

    public static View avatar(Context ctx, SquareLogic.Author a, int sizeDp) {
        BackupImageView v = new BackupImageView(ctx);
        v.setRoundRadius(AndroidUtilities.dp(sizeDp / 2f));
        AvatarDrawable placeholder = new AvatarDrawable();
        placeholder.setInfo(a.id.hashCode(), a.displayName, null);
        if (a.avatarUrl != null) {
            v.setImage(a.avatarUrl, sizeDp + "_" + sizeDp, placeholder);
        } else {
            v.setImageDrawable(placeholder);
        }
        return v;
    }

    private static TextView text(Context ctx, CharSequence s, int sizeDp, int colorKey) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(Theme.getColor(colorKey));
        return t;
    }

    /** A post card: author, time / review status, text, image grid, like and comment counts. */
    public static View postView(Context ctx, SquareLogic.Post p, boolean full, PostActions actions) {
        SquareLogic.Texts tx = tx();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(4));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(avatar(ctx, p.author, 40), LayoutHelper.createLinear(40, 40));
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(ctx, p.author.displayName, 15, Theme.key_windowBackgroundWhiteBlackText);
        name.setTypeface(AndroidUtilities.bold());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);
        String sub = tx.timeAgo(p.createdAtMs, System.currentTimeMillis());
        if (SquareLogic.STATUS_PENDING.equals(p.status)) sub = sub + " · " + tx.pending();
        TextView time = text(ctx, sub, 13, Theme.key_windowBackgroundWhiteGrayText);
        names.addView(time);
        head.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        TextView more = text(ctx, "⋯", 20, Theme.key_windowBackgroundWhiteGrayText);
        more.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(4), 0);
        more.setContentDescription(tx.more());
        more.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        more.setOnClickListener(v -> actions.onMore(p));
        head.addView(more, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        View.OnClickListener author = v -> actions.onAuthor(p);
        head.getChildAt(0).setOnClickListener(author);
        names.setOnClickListener(author);
        card.addView(head);

        if (SquareLogic.STATUS_REJECTED.equals(p.status)) {
            TextView r = text(ctx, tx.rejectedReason(p.rejectReason), 13, Theme.key_text_RedRegular);
            card.addView(r, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }
        if (!p.text.isEmpty()) {
            TextView body = text(ctx, p.text, 15, Theme.key_windowBackgroundWhiteBlackText);
            body.setTextIsSelectable(full);
            if (!full) {
                body.setMaxLines(8);
                body.setEllipsize(TextUtils.TruncateAt.END);
            }
            card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }
        if (!p.images.isEmpty()) {
            card.addView(imageGrid(ctx, p), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        TextView like = text(ctx, (p.liked ? "♥ " : "♡ ") + tx.likes(p.likeCount), 14,
            p.liked ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText);
        like.setPadding(0, AndroidUtilities.dp(10), AndroidUtilities.dp(24), AndroidUtilities.dp(10));
        like.setOnClickListener(v -> actions.onLike(p));
        bar.addView(like);
        TextView comments = text(ctx, "💬 " + tx.comments(p.commentCount), 14, Theme.key_windowBackgroundWhiteGrayText);
        comments.setPadding(0, AndroidUtilities.dp(10), AndroidUtilities.dp(24), AndroidUtilities.dp(10));
        comments.setOnClickListener(v -> actions.onOpen(p));
        bar.addView(comments);
        card.addView(bar);
        if (!full) {
            card.setOnClickListener(v -> actions.onOpen(p));
        }
        return card;
    }

    /** Nine-grid: 1 image wide, otherwise 3 per row. */
    private static View imageGrid(Context ctx, SquareLogic.Post p) {
        int n = p.images.size();
        int cols = n == 1 ? 1 : (n == 4 ? 2 : 3);
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r * cols < n; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                FrameLayout cell = new FrameLayout(ctx);
                if (i < n) {
                    BackupImageView img = new BackupImageView(ctx);
                    img.setRoundRadius(AndroidUtilities.dp(6));
                    img.getImageReceiver().setAllowStartAnimation(false);
                    img.setImage(p.images.get(i).url, null, Theme.createRoundRectDrawable(AndroidUtilities.dp(6), Theme.getColor(Theme.key_windowBackgroundGray)));
                    cell.addView(img, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                }
                int height = n == 1 ? 200 : (cols == 2 ? 140 : 96);
                row.addView(cell, LayoutHelper.createLinear(0, height, 1f, c > 0 ? 4 : 0, 0, 0, 0));
            }
            grid.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, r > 0 ? 4 : 0, 0, 0));
        }
        return grid;
    }

    public static View commentView(Context ctx, SquareLogic.Comment c, View.OnClickListener onClick) {
        SquareLogic.Texts tx = tx();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(Theme.getSelectorDrawable(true));
        int indent = c.depth > 0 ? 48 : 16;
        row.setPadding(AndroidUtilities.dp(indent), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        row.addView(avatar(ctx, c.author, 32), LayoutHelper.createLinear(32, 32));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(ctx, c.author.displayName + "  ·  " + tx.timeAgo(c.createdAtMs, System.currentTimeMillis())
            + (SquareLogic.STATUS_PENDING.equals(c.status) ? "  ·  " + tx.pending() : ""), 13, Theme.key_windowBackgroundWhiteGrayText);
        col.addView(name);
        if (SquareLogic.STATUS_REJECTED.equals(c.status)) {
            col.addView(text(ctx, tx.rejectedReason(c.rejectReason), 13, Theme.key_text_RedRegular));
        }
        col.addView(text(ctx, c.text, 15, Theme.key_windowBackgroundWhiteBlackText));
        row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
        row.setOnClickListener(onClick);
        row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }
}

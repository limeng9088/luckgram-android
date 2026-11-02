package org.telegram.luckgram.square;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.account.SilentLink;
import org.telegram.luckgram.discover.DiscoverBlocks;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Shared pieces of the Square pages (spec 26): the silent S6 link before the first write, the
 * profile check, error messages, report and block dialogs, and the post card view (text, image
 * grid or inline video). Nothing about "linking an account" is ever shown to the user.
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

    public static long maxPostsPerDay() {
        return LuckgramSettings.remoteLong("square.max_posts_per_day", SquareLogic.DEFAULT_MAX_POSTS_PER_DAY);
    }

    public static long maxVideoSeconds() {
        return Math.max(1, LuckgramSettings.remoteLong("square.max_video_seconds", SquareLogic.DEFAULT_MAX_VIDEO_SECONDS));
    }

    // ---------------------------------------------------------------- bulletins

    /**
     * Bulletins (toasts) for the Square pages. They are attached to the page's own content view
     * instead of the navigation container, and pushed up by however much the soft keyboard covers
     * that view, so they stay visible on the compose page and above the comment box, where the
     * keyboard is open (acceptance 2026-11-01: errors on the compose page were never seen).
     * Falls back to the stock fragment bulletin when the page has no attached content view.
     */
    public static BulletinFactory bulletin(BaseFragment f) {
        View v = f != null ? f.getFragmentView() : null;
        if (!(v instanceof FrameLayout) || !v.isAttachedToWindow()) {
            return BulletinFactory.of(f);
        }
        FrameLayout container = (FrameLayout) v;
        if (!(container.getTag(R.id.bulletin_delegate_tag) instanceof Bulletin.Delegate)) {
            Bulletin.addDelegate(container, new Bulletin.Delegate() {
                @Override
                public int getBottomOffset(int tag) {
                    return keyboardOverlap(container);
                }
            });
        }
        return BulletinFactory.of(container, f.getResourceProvider());
    }

    /** How many px of the bottom of {@code view} the soft keyboard covers; 0 when the layout already shrank above it. */
    static int keyboardOverlap(View view) {
        Rect visible = new Rect();
        view.getWindowVisibleDisplayFrame(visible);
        if (visible.bottom <= 0) return 0;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return Math.max(0, location[1] + view.getHeight() - visible.bottom);
    }

    // ---------------------------------------------------------------- link (S6, silent)

    private static JSONObject profile;
    /** Author id of this device's user in Square (from the profile response), if known. */
    public static String myAuthorId;

    /** Forget cached state (unlink, logout, "delete my Square data"). */
    public static void clearCache() {
        SilentLink.clearCache();
        profile = null;
        myAuthorId = null;
    }

    /**
     * Learns who "I" am in Square (specs#68): when a page opens and the device is linked, the
     * profile is fetched quietly so own posts and comments get their delete entry. No profile
     * yet (404) is fine — nothing is shown.
     */
    public static void prefetchMe() {
        if (myAuthorId != null) return;
        Runnable fetch = () -> {
            if (!Boolean.TRUE.equals(SilentLink.cached()) || myAuthorId != null) return;
            LuckgramAI.getInstance().requestJson("GET", "/v1/square/profile", null, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    if (!result.optString("display_name", "").isEmpty()) setProfile(result);
                }

                @Override
                public void onError(LuckgramAIError error) {
                    // not_found / offline: stay unknown until the next page open
                }
            });
        };
        if (SilentLink.cached() == null) refreshLink(fetch);
        else fetch.run();
    }

    /** {@code GET /v1/link} through the shared {@link SilentLink}; an unlinked answer also drops the cached profile. */
    public static void refreshLink(Runnable done) {
        SilentLink.refresh(() -> {
            if (Boolean.FALSE.equals(SilentLink.cached())) {
                profile = null;
                myAuthorId = null;
            }
            if (done != null) done.run();
        });
    }

    /**
     * Runs {@code then} once this device is linked to the current Telegram account (S6). If it is
     * not yet, the link is made in the background by the shared {@link SilentLink} (also used by
     * People Nearby): {@code POST /v1/link/start} → the app sends {@code /start <nonce>} to the
     * bot from the current account → poll until the server has it → the bot dialog is deleted
     * locally. The user only sees a short "connecting" notice.
     */
    public static void requireLink(BaseFragment f, Runnable then) {
        if (Boolean.TRUE.equals(SilentLink.cached())) {
            then.run();
            return;
        }
        if (SilentLink.cached() == null) {
            refreshLink(() -> {
                if (Boolean.TRUE.equals(SilentLink.cached())) then.run();
                else autoLink(f, then);
            });
        } else {
            autoLink(f, then);
        }
    }

    private static void autoLink(BaseFragment f, Runnable then) {
        final SquareLogic.Texts tx = tx();
        if (f.getParentActivity() != null) {
            bulletin(f).createSimpleBulletin(R.raw.chats_infotip, tx.connecting()).show();
        }
        SilentLink.ensure(f.getCurrentAccount(), ok -> {
            if (ok) {
                then.run();
            } else if (f.getParentActivity() != null) {
                bulletin(f).createErrorBulletin(tx.connectFailed()).show();
            }
        });
    }

    // ---------------------------------------------------------------- profile

    /** Linked and has a Square profile: then {@code then}; otherwise fills in whatever is missing first. */
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
                    if (isNoProfile(error)) {
                        profile = null;
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

    /**
     * Edit (or first fill in) the Square profile on {@link SquareProfileActivity}; {@code then} runs
     * after a successful save (e.g. continues to the compose page).
     */
    public static void editProfile(BaseFragment f, JSONObject current, Runnable then) {
        if (f == null || f.getParentActivity() == null) return;
        try {
            f.presentFragment(new SquareProfileActivity(current != null ? current : profile, then));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** The profile button: the current profile if there is one (loaded if needed), otherwise the first fill-in. */
    public static void openProfileEditor(BaseFragment f) {
        requireLink(f, () -> {
            if (profile != null) {
                editProfile(f, profile, null);
                return;
            }
            LuckgramAI.getInstance().requestJson("GET", "/v1/square/profile", null, new LuckgramAI.Callback<JSONObject>() {
                @Override
                public void onResult(JSONObject result) {
                    if (!result.optString("display_name", "").isEmpty()) setProfile(result);
                    editProfile(f, profile, null);
                }

                @Override
                public void onError(LuckgramAIError error) {
                    if (isNoProfile(error)) {
                        editProfile(f, null, null);
                    } else {
                        showError(f, error);
                    }
                }
            });
        });
    }

    /** Saved on {@link SquareProfileActivity}. */
    static void onProfileSaved(JSONObject p) {
        setProfile(p);
    }

    /** No Square profile yet: 404 (not_found) on GET /v1/square/profile, or 403 profile_required on a write. */
    static boolean isNoProfile(LuckgramAIError error) {
        return error != null && ("not_found".equals(error.code) || "profile_required".equals(error.code) || error.httpStatus == 404);
    }

    /** The account's small profile photo (already downloaded, ~160 px JPEG) as base64, or null. Same limit as 25. */
    static String avatarBase64(int account, TLRPC.User me) {
        try {
            if (me == null || me.photo == null || me.photo.photo_small == null) return null;
            java.io.File f = org.telegram.messenger.FileLoader.getInstance(account).getPathToAttach(me.photo.photo_small, true);
            if (f == null || !f.exists() || f.length() == 0 || f.length() > org.telegram.luckgram.nearby.NearbyLogic.AVATAR_MAX_BYTES * 3 / 4) return null;
            return android.util.Base64.encodeToString(SquareVideos.readAll(f), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }


    // ---------------------------------------------------------------- errors

    public static String errorText(LuckgramAIError error) {
        SquareLogic.Texts tx = tx();
        if (LuckgramAIError.RATE_LIMITED.equals(error.code)) return tx.waitSeconds(error.retryAfterSeconds);
        if ("banned".equals(error.code)) return tx.banned();
        if ("not_linked".equals(error.code)) return tx.connectFailed();
        if ("invalid_video".equals(error.code)) return tx.videoRejected();
        if ("square_disabled".equals(error.code)) return tx.squareDisabled();
        if ("rate_limit_unavailable".equals(error.code)) return tx.waitSeconds(error.retryAfterSeconds); // 503: try again shortly
        return error.getLocalizedMessage();
    }

    public static void showError(BaseFragment f, LuckgramAIError error) {
        if (error == null || LuckgramAIError.CANCELLED.equals(error.code) || f.getParentActivity() == null) return;
        if ("not_linked".equals(error.code)) {
            // The server lost the link (e.g. unlinked elsewhere): make it again silently next time.
            SilentLink.clearCache();
        }
        if ("profile_required".equals(error.code)) {
            profile = null;
            editProfile(f, null, null);
            return;
        }
        bulletin(f).createErrorBulletin(errorText(error)).show();
    }

    // ---------------------------------------------------------------- report / block / like

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
                                bulletin(f).createSimpleBulletin(R.raw.done, tx.reported()).show();
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

    /**
     * Blocks the author after a confirmation, through the block list shared with People Nearby
     * (S10/S11, {@link DiscoverBlocks}) so the "Blocked people" page knows the name.
     */
    public static void block(BaseFragment f, SquareLogic.Author author, Runnable done) {
        requireLink(f, () -> {
            if (f.getParentActivity() == null) return;
            SquareLogic.Texts tx = tx();
            new AlertDialog.Builder(f.getParentActivity())
                .setTitle(tx.block())
                .setMessage(tx.blockConfirm(author.displayName))
                .setPositiveButton(tx.block(), (d, w) -> DiscoverBlocks.block(author.id, author.displayName, new LuckgramAI.Callback<JSONObject>() {
                    @Override
                    public void onResult(JSONObject result) {
                        bulletin(f).createSimpleBulletin(R.raw.done, tx.blocked()).show();
                        if (done != null) done.run();
                    }

                    @Override
                    public void onError(LuckgramAIError error) {
                        showError(f, error);
                    }
                }))
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
        /** Full-screen playback with sound. */
        default void onVideo(SquareLogic.Post p) {
        }
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

    /** A post card: author, time, text, image grid or video, like and comment counts. */
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
        if (p.isHidden()) sub = (sub.isEmpty() ? "" : sub + " · ") + tx.hidden();
        TextView time = text(ctx, sub, 13, p.isHidden() ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText);
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

        if (p.isHidden()) {
            TextView r = text(ctx, tx.hiddenInfo(p.hiddenReason), 13, Theme.key_text_RedRegular);
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
        if (p.video != null) {
            int width = AndroidUtilities.displaySize.x - AndroidUtilities.dp(32);
            int height = SquareVideoView.heightFor(width, p.video);
            SquareVideoView video = new SquareVideoView(ctx, p.video, () -> actions.onVideo(p));
            card.addView(video, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (height / AndroidUtilities.density), 0, 8, 0, 0));
        } else if (!p.images.isEmpty()) {
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

    /** Nine-grid: 1 image wide, otherwise 3 per row (2 for exactly 4). */
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
        boolean hidden = SquareLogic.STATUS_HIDDEN.equals(c.status);
        TextView name = text(ctx, c.author.displayName + "  ·  " + tx.timeAgo(c.createdAtMs, System.currentTimeMillis())
            + (hidden ? "  ·  " + tx.hidden() : ""), 13, Theme.key_windowBackgroundWhiteGrayText);
        col.addView(name);
        col.addView(text(ctx, c.text, 15, Theme.key_windowBackgroundWhiteBlackText));
        row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
        row.setOnClickListener(onClick);
        row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }
}

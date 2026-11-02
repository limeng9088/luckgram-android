package org.telegram.luckgram.square;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;

/**
 * Inline video of a Square post (spec 26 item 3): cover with the duration badge, muted autoplay
 * (looping) while on screen, tap for the full-screen player with sound. Playback is released
 * whenever the view leaves the window, so a feed never keeps more than the visible players.
 */
public class SquareVideoView extends FrameLayout {

    private final SquareLogic.Video video;
    private final BackupImageView cover;
    private final TextureView texture;
    private final TextView badge;
    private VideoPlayer player;
    private boolean firstFrame;

    public SquareVideoView(Context ctx, SquareLogic.Video video, Runnable onOpen) {
        super(ctx);
        this.video = video;
        setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(6), 0xFF000000));
        setClipToOutline(true);

        cover = new BackupImageView(ctx);
        cover.setRoundRadius(AndroidUtilities.dp(6));
        cover.getImageReceiver().setAllowStartAnimation(false);
        if (video.coverUrl != null) {
            cover.setImage(video.coverUrl, null, Theme.createRoundRectDrawable(AndroidUtilities.dp(6), Theme.getColor(Theme.key_windowBackgroundGray)));
        }
        addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        texture = new TextureView(ctx);
        texture.setOpaque(false);
        texture.setAlpha(0f);
        addView(texture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        badge = new TextView(ctx);
        badge.setText("▶ " + SquareLogic.formatDuration(video.durationSeconds));
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(3), AndroidUtilities.dp(8), AndroidUtilities.dp(3));
        badge.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0x99000000));
        addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START, 8, 0, 0, 8));

        setOnClickListener(v -> onOpen.run());
    }

    /** Height for the given width: the video's aspect ratio, capped so a portrait clip doesn't fill the screen. */
    public static int heightFor(int widthPx, SquareLogic.Video v) {
        if (v.width <= 0 || v.height <= 0) return (int) (widthPx * 9f / 16f);
        float ratio = (float) v.height / v.width;
        ratio = Math.max(9f / 16f, Math.min(ratio, 5f / 4f));
        return (int) (widthPx * ratio);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startMuted();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }

    private void startMuted() {
        if (player != null || video.url == null) return;
        try {
            player = new VideoPlayer(false, true);
            player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                }

                @Override
                public void onError(VideoPlayer p, Exception e) {
                    // Keep the cover; the full-screen player reports errors to the user.
                    FileLog.e(e);
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                }

                @Override
                public void onRenderedFirstFrame() {
                    firstFrame = true;
                    AndroidUtilities.runOnUIThread(() -> texture.animate().alpha(1f).setDuration(150).start());
                }
            });
            player.setTextureView(texture);
            player.setMute(true);
            player.setLooping(true);
            player.preparePlayer(Uri.parse(org.telegram.luckgram.LuckgramAI.rewriteApiUrl(video.url)), "other");
            player.play();
        } catch (Throwable e) {
            FileLog.e(e);
            release();
        }
    }

    private void release() {
        if (player != null) {
            try {
                player.releasePlayer(false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            player = null;
        }
        firstFrame = false;
        texture.setAlpha(0f);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility != View.VISIBLE) release();
    }
}

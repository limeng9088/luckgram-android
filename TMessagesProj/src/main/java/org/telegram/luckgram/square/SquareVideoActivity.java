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
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.VideoPlayer;

/** Full-screen playback of a Square video with sound (spec 26 item 3). Tap toggles pause. */
public class SquareVideoActivity extends BaseFragment {

    private final SquareLogic.Video video;
    private VideoPlayer player;
    private TextureView texture;
    private RadialProgressView progress;
    private TextView error;
    private int videoWidth;
    private int videoHeight;

    public SquareVideoActivity(SquareLogic.Video video) {
        this.video = video;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setBackgroundColor(0xFF000000);
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setTitleColor(Color.WHITE);
        actionBar.setTitle(SquareLogic.formatDuration(video.durationSeconds));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                layoutTexture(getMeasuredWidth(), getMeasuredHeight());
            }
        };
        root.setBackgroundColor(0xFF000000);
        fragmentView = root;

        texture = new TextureView(context);
        root.addView(texture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        progress = new RadialProgressView(context);
        progress.setProgressColor(Color.WHITE);
        root.addView(progress, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        error = new TextView(context);
        error.setTextColor(Color.WHITE);
        error.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        error.setGravity(Gravity.CENTER);
        error.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        error.setVisibility(View.GONE);
        root.addView(error, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        root.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause();
            else player.play();
        });
        start();
        return root;
    }

    private void layoutTexture(int w, int h) {
        if (texture == null || w == 0 || h == 0) return;
        int vw = videoWidth > 0 ? videoWidth : video.width;
        int vh = videoHeight > 0 ? videoHeight : video.height;
        if (vw <= 0 || vh <= 0) {
            vw = 16;
            vh = 9;
        }
        float scale = Math.min((float) w / vw, (float) h / vh);
        int tw = (int) (vw * scale);
        int th = (int) (vh * scale);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) texture.getLayoutParams();
        if (lp.width != tw || lp.height != th) {
            lp.width = tw;
            lp.height = th;
            lp.gravity = Gravity.CENTER;
            texture.post(() -> texture.requestLayout());
        }
    }

    private void start() {
        try {
            player = new VideoPlayer(true, false);
            player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    if (progress != null) progress.setVisibility(playbackState == 2 /* buffering */ ? View.VISIBLE : View.GONE);
                }

                @Override
                public void onError(VideoPlayer p, Exception e) {
                    FileLog.e(e);
                    if (progress != null) progress.setVisibility(View.GONE);
                    if (error != null) {
                        error.setText(SquareUi.tx().videoFailed());
                        error.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                    videoWidth = width;
                    videoHeight = height;
                    if (fragmentView != null) layoutTexture(fragmentView.getWidth(), fragmentView.getHeight());
                }

                @Override
                public void onRenderedFirstFrame() {
                    if (progress != null) progress.setVisibility(View.GONE);
                }
            });
            player.setTextureView(texture);
            player.setMute(false);
            player.setLooping(true);
            player.preparePlayer(Uri.parse(video.url), "other");
            player.play();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    public void onFragmentDestroy() {
        if (player != null) {
            try {
                player.releasePlayer(false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            player = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }
}

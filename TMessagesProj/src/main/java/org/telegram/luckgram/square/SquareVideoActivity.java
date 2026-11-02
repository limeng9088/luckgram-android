package org.telegram.luckgram.square;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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

/**
 * Full-screen playback of a Square video with sound (spec 26 item 3). Bottom bar with play/pause,
 * elapsed / total time and a draggable progress bar; a tap on the picture toggles pause (a large
 * play icon shows while paused). Playback stops on the last frame and can be replayed
 * (acceptance P1).
 */
public class SquareVideoActivity extends BaseFragment {

    private static final int SEEK_MAX = 1000;
    private static final int STATE_BUFFERING = 2;
    private static final int STATE_ENDED = 4;

    private final SquareLogic.Video video;
    private VideoPlayer player;
    private TextureView texture;
    private RadialProgressView progress;
    private TextView error;
    private ImageView centerIcon;
    private ImageView playButton;
    private TextView elapsed;
    private TextView total;
    private SeekBar seekBar;
    private int videoWidth;
    private int videoHeight;
    private boolean ended;
    private boolean dragging;
    private boolean destroyed;

    public SquareVideoActivity(SquareLogic.Video video) {
        this.video = video;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setBackgroundColor(0xFF000000);
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setTitleColor(Color.WHITE);
        actionBar.setTitle(SquareUi.tx().videoTitle());
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

        // Large play / replay icon in the middle while paused or finished.
        centerIcon = new ImageView(context);
        centerIcon.setScaleType(ImageView.ScaleType.CENTER);
        centerIcon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x80000000);
        centerIcon.setBackground(circle);
        centerIcon.setVisibility(View.GONE);
        centerIcon.setOnClickListener(v -> togglePlay());
        root.addView(centerIcon, LayoutHelper.createFrame(72, 72, Gravity.CENTER));

        // Bottom bar: play/pause, elapsed, progress bar, total.
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x99000000);
        bar.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        bar.setClickable(true);

        playButton = new ImageView(context);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playButton.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        playButton.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
        playButton.setContentDescription(SquareUi.tx().play());
        playButton.setOnClickListener(v -> togglePlay());
        bar.addView(playButton, LayoutHelper.createLinear(44, 44));

        elapsed = timeLabel(context);
        bar.addView(elapsed, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        seekBar = new SeekBar(context);
        seekBar.setMax(SEEK_MAX);
        seekBar.getProgressDrawable().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        seekBar.getThumb().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (fromUser) elapsed.setText(SquareLogic.formatDuration(SquareLogic.wholeSeconds(value * durationMs() / SEEK_MAX)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (player == null) return;
                long target = sb.getProgress() * durationMs() / SEEK_MAX;
                try {
                    player.seekTo(target);
                    if (ended) {
                        ended = false;
                        player.play();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                updateControls();
            }
        });
        bar.addView(seekBar, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        total = timeLabel(context);
        total.setText(SquareLogic.formatDuration(video.durationSeconds));
        bar.addView(total, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        root.setOnClickListener(v -> togglePlay());
        start();
        updateControls();
        AndroidUtilities.runOnUIThread(tick, 250);
        return root;
    }

    private TextView timeLabel(Context context) {
        TextView tv = new TextView(context);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setMinWidth(AndroidUtilities.dp(36));
        tv.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private long durationMs() {
        long d = 0;
        try {
            if (player != null) d = player.getDuration();
        } catch (Throwable ignore) {
        }
        if (d <= 0 || d == Long.MIN_VALUE + 1 /* C.TIME_UNSET */) d = video.durationSeconds * 1000L;
        return Math.max(1, d);
    }

    private void togglePlay() {
        if (player == null) return;
        try {
            if (ended) {
                ended = false;
                player.seekTo(0);
                player.play();
            } else if (player.isPlaying()) {
                player.pause();
            } else {
                player.play();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        updateControls();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (player != null && !dragging) {
                long pos = 0;
                try {
                    pos = Math.max(0, player.getCurrentPosition());
                } catch (Throwable ignore) {
                }
                long dur = durationMs();
                if (ended) pos = dur;
                seekBar.setProgress((int) Math.min(SEEK_MAX, pos * SEEK_MAX / dur));
                elapsed.setText(SquareLogic.formatDuration(SquareLogic.wholeSeconds(pos)));
                total.setText(SquareLogic.formatDuration(SquareLogic.wholeSeconds(dur)));
            }
            AndroidUtilities.runOnUIThread(this, 250);
        }
    };

    private void updateControls() {
        if (playButton == null) return;
        boolean playing = false;
        try {
            playing = player != null && player.isPlaying() && !ended;
        } catch (Throwable ignore) {
        }
        SquareLogic.Texts tx = SquareUi.tx();
        playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setContentDescription(playing ? tx.pause() : (ended ? tx.replay() : tx.play()));
        if (playing || error.getVisibility() == View.VISIBLE) {
            centerIcon.setVisibility(View.GONE);
        } else {
            centerIcon.setImageResource(ended ? R.drawable.msg_retry : R.drawable.ic_play);
            centerIcon.setContentDescription(ended ? tx.replay() : tx.play());
            centerIcon.setVisibility(View.VISIBLE);
        }
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
                    if (progress != null) progress.setVisibility(playbackState == STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    if (playbackState == STATE_ENDED && !ended) {
                        // Stay on the last frame; the user can replay.
                        ended = true;
                        try {
                            player.pause();
                        } catch (Throwable ignore) {
                        }
                    }
                    updateControls();
                }

                @Override
                public void onError(VideoPlayer p, Exception e) {
                    FileLog.e(e);
                    if (progress != null) progress.setVisibility(View.GONE);
                    if (error != null) {
                        error.setText(SquareUi.tx().videoFailed());
                        error.setVisibility(View.VISIBLE);
                    }
                    updateControls();
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
                    updateControls();
                }
            });
            player.setTextureView(texture);
            player.setMute(false);
            player.setLooping(false);
            player.preparePlayer(Uri.parse(org.telegram.luckgram.LuckgramAI.rewriteApiUrl(video.url)), "other");
            player.play();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (player != null) player.pause();
        updateControls();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        AndroidUtilities.cancelRunOnUIThread(tick);
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

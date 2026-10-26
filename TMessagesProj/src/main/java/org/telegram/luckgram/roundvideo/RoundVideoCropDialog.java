/*
 * Luckgram: send a gallery video as a round video message (spec 03-round-video).
 */
package org.telegram.luckgram.roundvideo;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.Components.VideoTimelinePlayView;

import java.io.File;
import java.util.Locale;

/**
 * Full-screen crop page: circular viewfinder over the video, drag to move, pinch to zoom,
 * a timeline to pick up to 60 seconds when the video is longer, then converts with a cancellable
 * progress and sends the result as a round video message.
 */
public class RoundVideoCropDialog extends Dialog {

    private static final float MAX_ZOOM = 4f;

    private final ChatActivity chatActivity;
    private final String path;
    private final Runnable onSent;
    private final RoundVideoConverter.Meta meta;

    private FrameLayout root;
    private FrameLayout videoContainer;
    private TextureView textureView;
    private OverlayView overlayView;
    private VideoTimelinePlayView timelineView;
    private TextView rangeTextView;
    private TextView hintTextView;
    private View sendButton;
    private TextView cancelButton;
    private FrameLayout progressLayout;
    private TextView progressTextView;

    private VideoPlayer videoPlayer;
    private RoundVideoConverter converter;
    private boolean sent;

    // viewfinder geometry, in videoContainer coordinates
    private float circleCx, circleCy, circleD;
    private float baseScale = 1f;
    private float zoom = 1f;
    private float tx, ty;

    private float leftProgress = 0f;
    private float rightProgress = 1f;
    private float convertProgress;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (videoPlayer == null) {
                return;
            }
            long duration = meta.durationMs;
            if (duration > 0) {
                long pos = videoPlayer.getCurrentPosition();
                long endMs = (long) (rightProgress * duration);
                if (pos >= endMs - 30 || pos < (long) (leftProgress * duration) - 300) {
                    videoPlayer.seekTo((long) (leftProgress * duration));
                    pos = (long) (leftProgress * duration);
                }
                if (timelineView != null && !timelineView.isDragging()) {
                    timelineView.setProgress(Math.max(0, Math.min(1, pos / (float) duration)));
                }
            }
            AndroidUtilities.runOnUIThread(this, 50);
        }
    };

    public RoundVideoCropDialog(ChatActivity chatActivity, String path, Runnable onSent) {
        super(chatActivity.getParentActivity(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.chatActivity = chatActivity;
        this.path = path;
        this.onSent = onSent;
        this.meta = RoundVideoConverter.Meta.read(path);
        if (meta.durationMs > RoundVideoFeature.MAX_DURATION_MS) {
            rightProgress = RoundVideoFeature.MAX_DURATION_MS / (float) meta.durationMs;
        }
        setCancelable(true);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        Context context = getContext();

        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        // ---- title ----
        TextView titleView = new TextView(context);
        titleView.setText(RoundVideoFeature.getString(RoundVideoFeature.STR_TITLE));
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP | Gravity.LEFT));

        // ---- video + viewfinder ----
        videoContainer = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (changed) {
                    layoutVideo();
                }
            }
        };
        videoContainer.setClipChildren(true);
        root.addView(videoContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, 56, 0, meta.durationMs > RoundVideoFeature.MAX_DURATION_MS ? 196 : 132));

        textureView = new TextureView(context);
        videoContainer.addView(textureView, new FrameLayout.LayoutParams(1, 1, Gravity.LEFT | Gravity.TOP));

        overlayView = new OverlayView(context);
        videoContainer.addView(overlayView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * detector.getScaleFactor()));
                applyTransform();
                return true;
            }
        });
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private float lastX, lastY;
            private int lastPointers;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (converter != null) {
                    return true;
                }
                scaleDetector.onTouchEvent(event);
                int pointers = event.getPointerCount();
                float x = 0, y = 0;
                for (int i = 0; i < pointers; i++) {
                    x += event.getX(i);
                    y += event.getY(i);
                }
                x /= pointers;
                y /= pointers;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || pointers != lastPointers || action == MotionEvent.ACTION_POINTER_UP) {
                    lastX = x;
                    lastY = y;
                    lastPointers = action == MotionEvent.ACTION_POINTER_UP ? -1 : pointers;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    tx += x - lastX;
                    ty += y - lastY;
                    lastX = x;
                    lastY = y;
                    applyTransform();
                }
                return true;
            }
        });

        // ---- bottom panel ----
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        root.addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT));

        hintTextView = new TextView(context);
        hintTextView.setTextColor(0xb3ffffff);
        hintTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hintTextView.setGravity(Gravity.CENTER);
        hintTextView.setText(RoundVideoFeature.getString(RoundVideoFeature.STR_HINT));
        bottom.addView(hintTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 8));

        if (meta.durationMs > RoundVideoFeature.MAX_DURATION_MS) {
            timelineView = new VideoTimelinePlayView(context);
            timelineView.setVideoPath(path, 0, 0, rightProgress, 0);
            timelineView.setMaxProgressDiff(rightProgress);
            timelineView.setMinProgressDiff(Math.min(1f, 1000f / meta.durationMs));
            timelineView.setDelegate(new VideoTimelinePlayView.VideoTimelineViewDelegate() {
                @Override
                public void onLeftProgressChanged(float progress) {
                    leftProgress = progress;
                    rightProgress = timelineView.getRightProgress();
                    seekTo(progress);
                    updateRangeText();
                }

                @Override
                public void onRightProgressChanged(float progress) {
                    rightProgress = progress;
                    leftProgress = timelineView.getLeftProgress();
                    seekTo(progress);
                    updateRangeText();
                }

                @Override
                public void onPlayProgressChanged(float progress) {
                    seekTo(progress);
                }

                @Override
                public void didStartDragging(int type) {
                    if (videoPlayer != null) {
                        videoPlayer.pause();
                    }
                }

                @Override
                public void didStopDragging(int type) {
                    if (videoPlayer != null) {
                        if (type != VideoTimelinePlayView.TYPE_PROGRESS) {
                            seekTo(leftProgress);
                        }
                        videoPlayer.play();
                    }
                }
            });
            bottom.addView(timelineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 0, 0, 0, 0));

            rangeTextView = new TextView(context);
            rangeTextView.setTextColor(Color.WHITE);
            rangeTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            rangeTextView.setGravity(Gravity.CENTER);
            bottom.addView(rangeTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 4));
            updateRangeText();
        }

        FrameLayout buttons = new FrameLayout(context);
        bottom.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 72));

        cancelButton = new TextView(context);
        cancelButton.setText(RoundVideoFeature.getString(RoundVideoFeature.STR_CANCEL));
        cancelButton.setTextColor(Color.WHITE);
        cancelButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        cancelButton.setGravity(Gravity.CENTER);
        cancelButton.setPadding(dp(20), 0, dp(20), 0);
        cancelButton.setBackground(Theme.createSelectorDrawable(0x33ffffff, Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        cancelButton.setOnClickListener(v -> onCancelPressed());
        buttons.addView(cancelButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        ImageView sendImage = new ImageView(context);
        sendImage.setScaleType(ImageView.ScaleType.CENTER);
        sendImage.setImageResource(R.drawable.attach_send);
        sendImage.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setShape(GradientDrawable.OVAL);
        sendBg.setColor(Theme.getColor(Theme.key_chat_editMediaButton));
        sendImage.setBackground(sendBg);
        sendImage.setContentDescription(RoundVideoFeature.getString(RoundVideoFeature.STR_SEND));
        sendImage.setOnClickListener(v -> startConversion());
        sendButton = sendImage;
        buttons.addView(sendImage, LayoutHelper.createFrame(56, 56, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

        // ---- conversion progress ----
        progressLayout = new FrameLayout(context);
        progressLayout.setVisibility(View.GONE);
        progressLayout.setClickable(true);
        root.addView(progressLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout progressColumn = new LinearLayout(context);
        progressColumn.setOrientation(LinearLayout.VERTICAL);
        progressColumn.setGravity(Gravity.CENTER);
        progressColumn.setBackgroundColor(Color.BLACK);
        progressColumn.setMinimumHeight(dp(meta.durationMs > RoundVideoFeature.MAX_DURATION_MS ? 196 : 132));
        progressLayout.addView(progressColumn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        progressTextView = new TextView(context);
        progressTextView.setTextColor(Color.WHITE);
        progressTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        progressTextView.setGravity(Gravity.CENTER);
        progressColumn.addView(progressTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 8));

        TextView progressCancel = new TextView(context);
        progressCancel.setText(RoundVideoFeature.getString(RoundVideoFeature.STR_CANCEL));
        progressCancel.setTextColor(Color.WHITE);
        progressCancel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        progressCancel.setGravity(Gravity.CENTER);
        progressCancel.setPadding(dp(24), 0, dp(24), 0);
        progressCancel.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), 0x33ffffff, 0x55ffffff));
        progressCancel.setOnClickListener(v -> cancelConversion());
        progressColumn.addView(progressCancel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 24));

        if (meta.width <= 0 || meta.height <= 0 || meta.durationMs <= 0) {
            Toast.makeText(context, RoundVideoFeature.getString(RoundVideoFeature.STR_FAILED), Toast.LENGTH_SHORT).show();
            AndroidUtilities.runOnUIThread(this::dismiss);
            return;
        }
        setupPlayer();
    }

    private void setupPlayer() {
        videoPlayer = new VideoPlayer();
        videoPlayer.setTextureView(textureView);
        videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
            }

            @Override
            public void onError(VideoPlayer player, Exception e) {
                FileLog.e(e);
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            }

            @Override
            public void onRenderedFirstFrame() {
            }
        });
        videoPlayer.preparePlayer(Uri.fromFile(new File(path)), "other");
        videoPlayer.setLooping(true);
        videoPlayer.play();
        AndroidUtilities.runOnUIThread(progressRunnable, 50);
    }

    private void seekTo(float progress) {
        if (videoPlayer != null) {
            videoPlayer.seekTo((long) (progress * meta.durationMs));
        }
    }

    private void updateRangeText() {
        if (rangeTextView == null) {
            return;
        }
        long ms = selectedEndMs() - selectedStartMs();
        rangeTextView.setText(String.format(RoundVideoFeature.getString(RoundVideoFeature.STR_SELECTED), AndroidUtilities.formatShortDuration((int) Math.round(ms / 1000.0))));
    }

    private long selectedStartMs() {
        return meta.durationMs > RoundVideoFeature.MAX_DURATION_MS ? (long) (leftProgress * meta.durationMs) : 0;
    }

    private long selectedEndMs() {
        if (meta.durationMs > RoundVideoFeature.MAX_DURATION_MS) {
            long start = selectedStartMs();
            long end = (long) (rightProgress * meta.durationMs);
            return Math.min(end, start + RoundVideoFeature.MAX_DURATION_MS);
        }
        return meta.durationMs;
    }

    private void layoutVideo() {
        int w = videoContainer.getMeasuredWidth();
        int h = videoContainer.getMeasuredHeight();
        if (w <= 0 || h <= 0 || meta.width <= 0 || meta.height <= 0) {
            return;
        }
        circleD = Math.min(w, h) - dp(32);
        circleCx = w / 2f;
        circleCy = h / 2f;
        baseScale = circleD / Math.min(meta.width, meta.height);
        int vw = Math.round(meta.width * baseScale);
        int vh = Math.round(meta.height * baseScale);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        if (lp.width != vw || lp.height != vh) {
            lp.width = vw;
            lp.height = vh;
            lp.leftMargin = Math.round(circleCx - vw / 2f);
            lp.topMargin = Math.round(circleCy - vh / 2f);
            textureView.setLayoutParams(lp);
        }
        applyTransform();
    }

    private void applyTransform() {
        if (baseScale <= 0) {
            return;
        }
        float dispW = meta.width * baseScale * zoom;
        float dispH = meta.height * baseScale * zoom;
        float maxTx = Math.max(0, (dispW - circleD) / 2f);
        float maxTy = Math.max(0, (dispH - circleD) / 2f);
        tx = Math.max(-maxTx, Math.min(maxTx, tx));
        ty = Math.max(-maxTy, Math.min(maxTy, ty));
        textureView.setScaleX(zoom);
        textureView.setScaleY(zoom);
        textureView.setTranslationX(tx);
        textureView.setTranslationY(ty);
        overlayView.invalidate();
    }

    private void startConversion() {
        if (converter != null || sent) {
            return;
        }
        float s = baseScale * zoom;
        float dispW = meta.width * s;
        float dispH = meta.height * s;
        float cropSide = Math.min(Math.min(meta.width, meta.height), circleD / s);
        float cropX = (dispW / 2f - tx - circleD / 2f) / s;
        float cropY = (dispH / 2f - ty - circleD / 2f) / s;
        cropX = Math.max(0, Math.min(meta.width - cropSide, cropX));
        cropY = Math.max(0, Math.min(meta.height - cropSide, cropY));

        if (videoPlayer != null) {
            videoPlayer.pause();
        }
        convertProgress = 0;
        setConverting(true);

        boolean secret = DialogObject.isEncryptedDialog(chatActivity.getDialogId());
        converter = new RoundVideoConverter(chatActivity.getCurrentAccount(), path, meta, secret);
        final RoundVideoConverter current = converter;
        converter.start(cropX, cropY, cropSide, selectedStartMs(), selectedEndMs(), new RoundVideoConverter.Listener() {
            @Override
            public void onProgress(float progress) {
                if (converter != current) {
                    return;
                }
                convertProgress = progress;
                updateProgressText();
                overlayView.invalidate();
            }

            @Override
            public void onDone(File file, VideoEditedInfo info) {
                if (converter != current) {
                    file.delete();
                    return;
                }
                converter = null;
                sent = true;
                MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, 0, 0, file.getAbsolutePath(), 0, true, 0, 0, 0);
                chatActivity.sendMedia(entry, info, true, 0, 0, false, 0);
                dismiss();
                if (onSent != null) {
                    onSent.run();
                }
            }

            @Override
            public void onError() {
                if (converter != current) {
                    return;
                }
                converter = null;
                setConverting(false);
                Toast.makeText(getContext(), RoundVideoFeature.getString(RoundVideoFeature.STR_FAILED), Toast.LENGTH_SHORT).show();
                if (videoPlayer != null) {
                    videoPlayer.play();
                }
            }

            @Override
            public void onCancelled() {
                if (converter == current) {
                    converter = null;
                    setConverting(false);
                    if (videoPlayer != null) {
                        videoPlayer.play();
                    }
                }
            }
        });
    }

    private void cancelConversion() {
        if (converter != null) {
            // The converter thread stops at its next check and deletes the partial output file
            // itself; nothing is sent. The UI is restored right away.
            converter.cancel();
            converter = null;
            setConverting(false);
            if (videoPlayer != null) {
                videoPlayer.play();
            }
        }
    }

    private void setConverting(boolean converting) {
        progressLayout.setVisibility(converting ? View.VISIBLE : View.GONE);
        sendButton.setEnabled(!converting);
        if (timelineView != null) {
            timelineView.setEnabled(!converting);
        }
        updateProgressText();
        overlayView.invalidate();
    }

    private void updateProgressText() {
        progressTextView.setText(String.format(Locale.US, RoundVideoFeature.getString(RoundVideoFeature.STR_CONVERTING), (int) (convertProgress * 100)));
    }

    private void onCancelPressed() {
        if (converter != null) {
            cancelConversion();
        } else {
            dismiss();
        }
    }

    @Override
    public void onBackPressed() {
        onCancelPressed();
    }

    @Override
    public void dismiss() {
        if (converter != null) {
            converter.cancel();
            converter = null;
        }
        AndroidUtilities.cancelRunOnUIThread(progressRunnable);
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
        if (timelineView != null) {
            timelineView.destroy();
        }
        try {
            super.dismiss();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Dims everything outside the circular viewfinder and draws the conversion progress ring. */
    private class OverlayView extends View {
        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        OverlayView(Context context) {
            super(context);
            dimPaint.setColor(0xb0000000);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(2));
            strokePaint.setColor(0xccffffff);
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeWidth(dp(4));
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setColor(Theme.getColor(Theme.key_chat_editMediaButton));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (circleD <= 0) {
                return;
            }
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            path.addCircle(circleCx, circleCy, circleD / 2f, Path.Direction.CW);
            canvas.drawPath(path, dimPaint);
            canvas.drawCircle(circleCx, circleCy, circleD / 2f, strokePaint);
            if (progressLayout != null && progressLayout.getVisibility() == View.VISIBLE) {
                float r = circleD / 2f + dp(6);
                rect.set(circleCx - r, circleCy - r, circleCx + r, circleCy + r);
                canvas.drawArc(rect, -90, 360 * Math.max(0.01f, convertProgress), false, progressPaint);
            }
        }
    }
}

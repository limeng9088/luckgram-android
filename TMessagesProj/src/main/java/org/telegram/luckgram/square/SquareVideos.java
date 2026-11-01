package org.telegram.luckgram.square;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import org.telegram.luckgram.roundvideo.RoundVideoConverter;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.video.MediaCodecVideoConvertor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * Prepares a picked video for Square (spec 26 item 3): copied out of the content provider,
 * transcoded with Telegram's converter to H.264/AAC MP4 with the long edge ≤ 1280 px (the
 * converter writes a fresh container, so location and other metadata never leave the device),
 * trimmed to the first {@code maxSeconds} when the user agreed, checked against 50 MB, plus the
 * first frame as a JPEG cover.
 */
public final class SquareVideos {

    private SquareVideos() {
    }

    /** What the picker returned, before any work is done. */
    public static final class Source {
        public final File file;
        public final RoundVideoConverter.Meta meta;

        Source(File file, RoundVideoConverter.Meta meta) {
            this.file = file;
            this.meta = meta;
        }

        public int durationSeconds() {
            return (int) ((meta.durationMs + 999) / 1000);
        }
    }

    /** A video ready to upload. */
    public static final class Prepared {
        public final File mp4;
        public final byte[] coverJpeg;
        public final Bitmap thumbnail;
        public final int width;
        public final int height;
        public final int durationSeconds;

        Prepared(File mp4, byte[] coverJpeg, Bitmap thumbnail, int width, int height, int durationSeconds) {
            this.mp4 = mp4;
            this.coverJpeg = coverJpeg;
            this.thumbnail = thumbnail;
            this.width = width;
            this.height = height;
            this.durationSeconds = durationSeconds;
        }

        public void delete() {
            try {
                if (mp4 != null && mp4.exists()) mp4.delete();
            } catch (Throwable ignore) {
            }
            if (thumbnail != null && !thumbnail.isRecycled()) thumbnail.recycle();
        }
    }

    public interface Listener {
        void onProgress(float progress);
        void onDone(Prepared prepared);
        /** {@code tooBig}: the result was still over 50 MB; otherwise the file could not be read or converted. */
        void onError(boolean tooBig);
    }

    private static File cacheFile(String suffix) {
        return new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE),
            "luckgram_square_" + System.currentTimeMillis() + "_" + Utilities.random.nextInt(Integer.MAX_VALUE) + suffix);
    }

    /** Worker thread: copies the picked video into the cache and reads its size and duration. */
    public static Source open(Uri uri) {
        File copy = cacheFile(".src");
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            if (in == null || !AndroidUtilities.copyFile(in, copy)) {
                return null;
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        RoundVideoConverter.Meta meta = RoundVideoConverter.Meta.read(copy.getAbsolutePath());
        if (meta.width <= 0 || meta.height <= 0 || meta.durationMs <= 0) {
            copy.delete();
            return null;
        }
        return new Source(copy, meta);
    }

    /**
     * Transcodes {@code source} (keeping at most {@code keepSeconds} from the start) on its own
     * thread. The listener is called on the UI thread. The source copy is deleted afterwards.
     */
    public static long maxBytes() {
        return Math.max(1, org.telegram.luckgram.LuckgramSettings.remoteLong("square.max_video_mb", SquareLogic.DEFAULT_MAX_VIDEO_MB)) * 1024 * 1024;
    }

    public static Runnable prepare(Source source, int keepSeconds, Listener listener) {
        final boolean[] cancelled = {false};
        final RoundVideoConverter.Meta meta = source.meta;
        final int[] size = SquareLogic.videoSize(meta.width, meta.height);
        final int bitrate = SquareLogic.videoBitrate(size[0], size[1], meta.bitrate);
        final long endMs = Math.min(meta.durationMs, (long) keepSeconds * 1000L);
        final File output = cacheFile(".mp4");
        Thread thread = new Thread(() -> {
            boolean error;
            try {
                VideoEditedInfo info = new VideoEditedInfo();
                info.originalPath = source.file.getAbsolutePath();
                info.muted = false;
                info.volume = 1f;
                info.originalWidth = meta.width;
                info.originalHeight = meta.height;
                info.resultWidth = size[0];
                info.resultHeight = size[1];
                info.rotationValue = 0; // the decoder output is already display-oriented
                info.framerate = meta.framerate;
                info.bitrate = bitrate;
                info.originalBitrate = meta.bitrate > 0 ? meta.bitrate : -1;
                info.originalDuration = meta.durationMs * 1000L;
                info.startTime = -1;
                info.endTime = endMs < meta.durationMs ? endMs * 1000L : -1;
                info.estimatedDuration = endMs;
                info.avatarStartTime = -1;
                MediaController.VideoConvertorListener callback = new MediaController.VideoConvertorListener() {
                    @Override
                    public boolean checkConversionCanceled() {
                        return cancelled[0];
                    }

                    @Override
                    public void didWriteData(long availableSize, float progress) {
                        if (!cancelled[0]) {
                            AndroidUtilities.runOnUIThread(() -> {
                                if (!cancelled[0]) listener.onProgress(Utilities.clamp(progress, 1f, 0f));
                            });
                        }
                    }
                };
                MediaCodecVideoConvertor convertor = new MediaCodecVideoConvertor();
                MediaCodecVideoConvertor.ConvertVideoParams params = MediaCodecVideoConvertor.ConvertVideoParams.of(
                    source.file.getAbsolutePath(), output, 0,
                    0, false,
                    meta.width, meta.height,
                    size[0], size[1],
                    meta.framerate, bitrate, info.originalBitrate,
                    -1, info.endTime, -1,
                    true, endMs * 1000L, // the converter's duration is in microseconds
                    callback,
                    info
                );
                error = convertor.convertVideo(params);
            } catch (Throwable e) {
                FileLog.e(e);
                error = true;
            }
            final boolean failed = error || !output.exists() || output.length() == 0;
            final boolean tooBig = !failed && output.length() > maxBytes();
            byte[] cover = null;
            Bitmap thumb = null;
            if (!failed && !tooBig && !cancelled[0]) {
                Bitmap frame = firstFrame(output.getAbsolutePath());
                if (frame != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (frame.compress(Bitmap.CompressFormat.JPEG, SquareImages.JPEG_QUALITY, out)) {
                        cover = out.toByteArray();
                    }
                    int[] ts = SquareImages.thumbnailSize(frame.getWidth(), frame.getHeight(), 256);
                    thumb = Bitmap.createScaledBitmap(frame, ts[0], ts[1], true);
                    if (thumb != frame) frame.recycle();
                }
            }
            try {
                source.file.delete();
            } catch (Throwable ignore) {
            }
            final byte[] coverJpeg = cover;
            final Bitmap thumbnail = thumb;
            AndroidUtilities.runOnUIThread(() -> {
                if (cancelled[0] || failed || tooBig || coverJpeg == null) {
                    try {
                        output.delete();
                    } catch (Throwable ignore) {
                    }
                    if (thumbnail != null) thumbnail.recycle();
                    if (!cancelled[0]) listener.onError(tooBig);
                    return;
                }
                listener.onDone(new Prepared(output, coverJpeg, thumbnail, size[0], size[1], (int) ((endMs + 999) / 1000)));
            });
        }, "LuckgramSquareVideo");
        thread.start();
        return () -> cancelled[0] = true;
    }

    /** First frame of the transcoded file (already display-oriented, no rotation to apply). */
    static Bitmap firstFrame(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            Bitmap b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return b != null ? b : r.getFrameAtTime();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignore) {
            }
        }
    }

    public static byte[] readAll(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}

/*
 * Luckgram: send a gallery video as a round video message (spec 03-round-video).
 */
package org.telegram.luckgram.roundvideo;

import android.graphics.Matrix;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.video.MediaCodecVideoConvertor;

import java.io.File;

/**
 * Transcodes a region of a video into a square H.264 file with the same parameters Telegram
 * uses for camera round videos (side {@code roundVideoSize}, bitrate {@code roundVideoBitrate}).
 * Conversion runs on its own thread; it can be cancelled, in which case the partial output file
 * is deleted and no callback but {@code onCancelled} is invoked.
 */
public class RoundVideoConverter {

    public interface Listener {
        void onProgress(float progress);

        void onDone(File file, VideoEditedInfo info);

        void onError();

        void onCancelled();
    }

    /** Video info in display orientation (rotation metadata already applied). */
    public static class Meta {
        public int width;          // display width
        public int height;         // display height
        public int rotation;
        public long durationMs;
        public int framerate = 30;
        public int bitrate = -1;
        public boolean hasAudio;

        public static Meta read(String path) {
            Meta m = new Meta();
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(path);
                m.width = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                m.height = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                m.rotation = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                m.durationMs = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                m.bitrate = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
                m.hasAudio = "yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignore) {
                }
            }
            if (m.rotation == 90 || m.rotation == 270) {
                int t = m.width;
                m.width = m.height;
                m.height = t;
            }
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(path);
                int index = MediaController.findTrack(extractor, false);
                if (index >= 0) {
                    MediaFormat format = extractor.getTrackFormat(index);
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        m.framerate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                extractor.release();
            }
            if (m.framerate <= 0 || m.framerate > 30) {
                m.framerate = 30;
            }
            return m;
        }

        private static int parse(String s) {
            try {
                return s == null ? 0 : (int) Long.parseLong(s.trim());
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private final String path;
    private final Meta meta;
    private final int account;
    private final boolean secret;
    private volatile boolean cancelled;
    private File outputFile;

    public RoundVideoConverter(int account, String path, Meta meta, boolean secret) {
        this.account = account;
        this.path = path;
        this.meta = meta;
        this.secret = secret;
    }

    /**
     * @param cropX     left of the square crop, in display-oriented video pixels
     * @param cropY     top of the square crop, in display-oriented video pixels
     * @param cropSide  side of the square crop, in display-oriented video pixels
     * @param startMs   start of the selected range
     * @param endMs     end of the selected range (exclusive)
     */
    public void start(float cropX, float cropY, float cropSide, long startMs, long endMs, Listener listener) {
        final int side = Math.max(240, MessagesController.getInstance(account).roundVideoSize);
        final int bitrate = Math.max(300, MessagesController.getInstance(account).roundVideoBitrate) * 1024;

        outputFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE),
                "luckgram_round_" + System.currentTimeMillis() + "_" + Utilities.random.nextInt(Integer.MAX_VALUE) + ".mp4");

        final long durationMs = Math.max(1, endMs - startMs);

        VideoEditedInfo info = new VideoEditedInfo();
        info.originalPath = path;
        info.roundVideo = true;
        info.muted = false;
        info.volume = 1f;
        info.account = account;
        info.originalWidth = meta.width;
        info.originalHeight = meta.height;
        info.resultWidth = side;
        info.resultHeight = side;
        info.rotationValue = 0; // the decoder output is already display-oriented
        info.framerate = meta.framerate;
        info.bitrate = bitrate;
        info.originalBitrate = meta.bitrate > 0 ? meta.bitrate : -1;
        info.originalDuration = meta.durationMs * 1000L;
        info.startTime = startMs > 0 ? startMs * 1000L : -1;
        info.endTime = endMs < meta.durationMs ? endMs * 1000L : -1;
        info.estimatedDuration = durationMs;
        info.avatarStartTime = -1;

        // Map the chosen square of the (display-oriented) source onto the output square.
        Matrix matrix = new Matrix();
        matrix.postTranslate(-cropX, -cropY);
        matrix.postScale(side / cropSide, side / cropSide);
        info.cropState = new MediaController.CropState();
        info.cropState.useMatrix = matrix;

        final long startUs = info.startTime;
        final long endUs = info.endTime;
        final long convertDuration;
        if (startUs > 0 && endUs > 0) {
            convertDuration = endUs - startUs;
        } else if (endUs > 0) {
            convertDuration = endUs;
        } else if (startUs > 0) {
            convertDuration = info.originalDuration - startUs;
        } else {
            convertDuration = info.originalDuration;
        }

        Thread thread = new Thread(() -> {
            boolean error;
            try {
                MediaController.VideoConvertorListener callback = new MediaController.VideoConvertorListener() {
                    @Override
                    public boolean checkConversionCanceled() {
                        return cancelled;
                    }

                    @Override
                    public void didWriteData(long availableSize, float progress) {
                        if (!cancelled) {
                            AndroidUtilities.runOnUIThread(() -> {
                                if (!cancelled) {
                                    listener.onProgress(Utilities.clamp(progress, 1f, 0f));
                                }
                            });
                        }
                    }
                };
                MediaCodecVideoConvertor convertor = new MediaCodecVideoConvertor();
                MediaCodecVideoConvertor.ConvertVideoParams params = MediaCodecVideoConvertor.ConvertVideoParams.of(
                        path, outputFile, 0,
                        0, secret,
                        meta.width, meta.height,
                        side, side,
                        meta.framerate, bitrate, info.originalBitrate,
                        startUs, endUs, -1,
                        true, convertDuration,
                        callback,
                        info
                );
                error = convertor.convertVideo(params);
            } catch (Throwable e) {
                FileLog.e(e);
                error = true;
            }
            final boolean failed = error || !outputFile.exists() || outputFile.length() == 0;
            if (cancelled || failed) {
                deleteOutput();
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (cancelled) {
                    listener.onCancelled();
                } else if (failed) {
                    listener.onError();
                } else {
                    VideoEditedInfo sendInfo = new VideoEditedInfo();
                    sendInfo.roundVideo = true;
                    sendInfo.startTime = -1;
                    sendInfo.endTime = -1;
                    sendInfo.originalPath = outputFile.getAbsolutePath();
                    sendInfo.resultWidth = sendInfo.originalWidth = side;
                    sendInfo.resultHeight = sendInfo.originalHeight = side;
                    sendInfo.framerate = meta.framerate;
                    sendInfo.estimatedDuration = durationMs;
                    sendInfo.originalDuration = durationMs * 1000L;
                    sendInfo.estimatedSize = outputFile.length();
                    listener.onDone(outputFile, sendInfo);
                }
            });
        }, "LuckgramRoundVideo");
        thread.start();
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    private void deleteOutput() {
        try {
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}

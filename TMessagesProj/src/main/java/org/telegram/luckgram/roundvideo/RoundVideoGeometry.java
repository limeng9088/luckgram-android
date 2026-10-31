/*
 * Luckgram: send a gallery video as a round video message (spec 03-round-video).
 */
package org.telegram.luckgram.roundvideo;

/**
 * Pure geometry and rules of the round video feature: no Android dependencies, so it is unit
 * tested on the JVM (TMessagesProj/src/test/java, workflow luckgram-unit-tests.yml).
 */
public final class RoundVideoGeometry {

    /** Longest round video Telegram accepts, ms. */
    public static final long MAX_DURATION_MS = 60_000L;
    public static final float MAX_ZOOM = 4f;
    public static final int MIN_OUTPUT_SIDE = 240;

    private RoundVideoGeometry() {
    }

    /** Whether "send as round video" may be offered for the current attachment selection. */
    public static boolean canOffer(boolean enabled, boolean editing, int selectedCount, boolean isVideo, boolean isLivePhoto,
                                   boolean fileExists, long starsPrice, boolean scheduleMode, boolean canSendRoundVideo) {
        return enabled && !editing && selectedCount == 1 && isVideo && !isLivePhoto && fileExists
                && starsPrice <= 0 && !scheduleMode && canSendRoundVideo;
    }

    /** Display size {width, height} of a video with rotation metadata applied. */
    public static int[] displaySize(int width, int height, int rotation) {
        int r = ((rotation % 360) + 360) % 360;
        return r == 90 || r == 270 ? new int[]{height, width} : new int[]{width, height};
    }

    /** Output square side: the server-configured round video size, never below the minimum. */
    public static int outputSide(int configuredSize) {
        return Math.max(MIN_OUTPUT_SIDE, configuredSize);
    }

    // ---- time range ----

    /** Initial right handle (0..1) of the timeline: the first 60 s, or the whole video. */
    public static float initialRightProgress(long durationMs) {
        return durationMs > MAX_DURATION_MS ? MAX_DURATION_MS / (float) durationMs : 1f;
    }

    /** Whether the timeline is shown at all. */
    public static boolean needsTimeline(long durationMs) {
        return durationMs > MAX_DURATION_MS;
    }

    /** Selected range {startMs, endMs}, never longer than 60 s and inside the video. */
    public static long[] selectedRange(long durationMs, float leftProgress, float rightProgress) {
        if (!needsTimeline(durationMs)) {
            return new long[]{0, Math.max(0, durationMs)};
        }
        float l = clamp01(leftProgress);
        float r = Math.max(l, clamp01(rightProgress));
        long start = (long) (l * durationMs);
        long end = Math.min((long) (r * durationMs), start + MAX_DURATION_MS);
        end = Math.min(end, durationMs);
        return new long[]{start, end};
    }

    // ---- viewfinder ----

    /** Scale at zoom 1 that makes the shorter video side exactly fill the circle. */
    public static float baseScale(float circleDiameter, int displayWidth, int displayHeight) {
        return circleDiameter / Math.min(displayWidth, displayHeight);
    }

    public static float clampZoom(float zoom) {
        return Math.max(1f, Math.min(MAX_ZOOM, zoom));
    }

    /**
     * Clamps the drag offset {tx, ty} (screen px, from centred) so the video always covers the
     * whole circle: no black edges can be selected.
     */
    public static float[] clampOffset(int displayWidth, int displayHeight, float baseScale, float zoom, float circleDiameter, float tx, float ty) {
        float w = displayWidth * baseScale * zoom;
        float h = displayHeight * baseScale * zoom;
        float maxX = Math.max(0, (w - circleDiameter) / 2f);
        float maxY = Math.max(0, (h - circleDiameter) / 2f);
        return new float[]{Math.max(-maxX, Math.min(maxX, tx)), Math.max(-maxY, Math.min(maxY, ty))};
    }

    /**
     * The square of the display-oriented source under the circle, {x, y, side} in source pixels.
     * Offsets are expected to be clamped; the result is clamped to the source anyway.
     */
    public static float[] cropSquare(int displayWidth, int displayHeight, float baseScale, float zoom, float circleDiameter, float tx, float ty) {
        float s = baseScale * zoom;
        float side = Math.min(Math.min(displayWidth, displayHeight), circleDiameter / s);
        float x = (displayWidth * s / 2f - tx - circleDiameter / 2f) / s;
        float y = (displayHeight * s / 2f - ty - circleDiameter / 2f) / s;
        x = Math.max(0, Math.min(displayWidth - side, x));
        y = Math.max(0, Math.min(displayHeight - side, y));
        return new float[]{x, y, side};
    }

    /** Affine mapping source -> output square as {scale, translateX, translateY}. */
    public static float[] cropToOutput(float cropX, float cropY, float cropSide, int outputSide) {
        float scale = outputSide / cropSide;
        return new float[]{scale, -cropX * scale, -cropY * scale};
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}

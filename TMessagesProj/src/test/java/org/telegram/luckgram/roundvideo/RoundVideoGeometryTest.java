package org.telegram.luckgram.roundvideo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Spec 03 acceptance criteria that can be checked without a device. */
class RoundVideoGeometryTest {

    private static final float EPS = 0.01f;

    // ---- entry point visibility ----

    @Test
    void offeredOnlyForSingleVideo() {
        assertTrue(RoundVideoGeometry.canOffer(true, false, 1, true, false, true, 0, false, true));
        // 验收：多选两个视频时不出现该选项
        assertFalse(RoundVideoGeometry.canOffer(true, false, 2, true, false, true, 0, false, true));
        assertFalse(RoundVideoGeometry.canOffer(true, false, 0, true, false, true, 0, false, true));
        // image, live photo, missing file
        assertFalse(RoundVideoGeometry.canOffer(true, false, 1, false, false, true, 0, false, true));
        assertFalse(RoundVideoGeometry.canOffer(true, false, 1, true, true, true, 0, false, true));
        assertFalse(RoundVideoGeometry.canOffer(true, false, 1, true, false, false, 0, false, true));
    }

    @Test
    void hiddenWhenSwitchOffOrNotAllowed() {
        assertFalse(RoundVideoGeometry.canOffer(false, false, 1, true, false, true, 0, false, true)); // switch off
        assertFalse(RoundVideoGeometry.canOffer(true, true, 1, true, false, true, 0, false, true));   // editing
        assertFalse(RoundVideoGeometry.canOffer(true, false, 1, true, false, true, 5, false, true));  // paid media
        assertFalse(RoundVideoGeometry.canOffer(true, false, 1, true, false, true, 0, true, true));   // scheduled
        assertFalse(RoundVideoGeometry.canOffer(true, false, 1, true, false, true, 0, false, false)); // round videos banned
    }

    // ---- 60 s limit ----

    @Test
    void shortVideoUsesWholeLengthWithoutTimeline() {
        assertFalse(RoundVideoGeometry.needsTimeline(10_000));
        assertFalse(RoundVideoGeometry.needsTimeline(60_000));
        assertEquals(1f, RoundVideoGeometry.initialRightProgress(10_000), EPS);
        assertArrayEquals(new long[]{0, 10_000}, RoundVideoGeometry.selectedRange(10_000, 0.3f, 0.5f));
    }

    @Test
    void twoMinuteVideoSelectsAtMostSixtySeconds() {
        long d = 120_000;
        assertTrue(RoundVideoGeometry.needsTimeline(d));
        float right = RoundVideoGeometry.initialRightProgress(d);
        assertEquals(0.5f, right, EPS);
        assertArrayEquals(new long[]{0, 60_000}, RoundVideoGeometry.selectedRange(d, 0f, right));
        // window moved to the end
        assertArrayEquals(new long[]{60_000, 120_000}, RoundVideoGeometry.selectedRange(d, 0.5f, 1f));
        // a too-wide selection is capped at 60 s
        long[] r = RoundVideoGeometry.selectedRange(d, 0.1f, 1f);
        assertEquals(60_000, r[1] - r[0]);
        // shorter selection keeps its length
        r = RoundVideoGeometry.selectedRange(d, 0.25f, 0.5f);
        assertEquals(30_000, r[1] - r[0]);
        // inverted / out-of-range input never yields a negative or out-of-video range
        r = RoundVideoGeometry.selectedRange(d, 1.2f, -1f);
        assertTrue(r[0] >= 0 && r[1] <= d && r[1] >= r[0]);
    }

    // ---- rotation ----

    @Test
    void rotationMetadataSwapsDisplaySize() {
        // 手机竖拍：1920x1080 stored with rotation 90 is shown as 1080x1920
        assertArrayEquals(new int[]{1080, 1920}, RoundVideoGeometry.displaySize(1920, 1080, 90));
        assertArrayEquals(new int[]{1080, 1920}, RoundVideoGeometry.displaySize(1920, 1080, 270));
        assertArrayEquals(new int[]{1920, 1080}, RoundVideoGeometry.displaySize(1920, 1080, 180));
        assertArrayEquals(new int[]{1920, 1080}, RoundVideoGeometry.displaySize(1920, 1080, 0));
        assertArrayEquals(new int[]{1080, 1920}, RoundVideoGeometry.displaySize(1920, 1080, -90));
    }

    // ---- crop: square, no stretching, no black edges ----

    @Test
    void defaultCropIsCentredSquareOfShorterSide() {
        // portrait 1080x1920 in a 900 px circle
        float base = RoundVideoGeometry.baseScale(900, 1080, 1920);
        float[] c = RoundVideoGeometry.cropSquare(1080, 1920, base, 1f, 900, 0, 0);
        assertEquals(0, c[0], 0.5f);
        assertEquals((1920 - 1080) / 2f, c[1], 0.5f);
        assertEquals(1080, c[2], 0.5f);
        // landscape 1920x1080
        base = RoundVideoGeometry.baseScale(900, 1920, 1080);
        c = RoundVideoGeometry.cropSquare(1920, 1080, base, 1f, 900, 0, 0);
        assertEquals((1920 - 1080) / 2f, c[0], 0.5f);
        assertEquals(0, c[1], 0.5f);
        assertEquals(1080, c[2], 0.5f);
    }

    @Test
    void dragIsClampedSoCropStaysInsideVideo() {
        int w = 1920, h = 1080;
        float circle = 900;
        float base = RoundVideoGeometry.baseScale(circle, w, h);
        // drag far right: offset clamped, crop touches the left edge
        float[] o = RoundVideoGeometry.clampOffset(w, h, base, 1f, circle, 10_000, 10_000);
        assertEquals((w * base - circle) / 2f, o[0], EPS);
        assertEquals(0, o[1], EPS); // no vertical slack at zoom 1 for a landscape video
        float[] c = RoundVideoGeometry.cropSquare(w, h, base, 1f, circle, o[0], o[1]);
        assertEquals(0, c[0], 0.5f);
        // drag far left: crop touches the right edge
        o = RoundVideoGeometry.clampOffset(w, h, base, 1f, circle, -10_000, 0);
        c = RoundVideoGeometry.cropSquare(w, h, base, 1f, circle, o[0], o[1]);
        assertEquals(w - c[2], c[0], 0.5f);
    }

    @Test
    void pinchZoomShrinksCropAndIsClamped() {
        assertEquals(1f, RoundVideoGeometry.clampZoom(0.2f), EPS);
        assertEquals(RoundVideoGeometry.MAX_ZOOM, RoundVideoGeometry.clampZoom(50f), EPS);
        int w = 1080, h = 1920;
        float circle = 900;
        float base = RoundVideoGeometry.baseScale(circle, w, h);
        float[] c = RoundVideoGeometry.cropSquare(w, h, base, 2f, circle, 0, 0);
        assertEquals(540, c[2], 0.5f);
        assertEquals((w - 540) / 2f, c[0], 0.5f);
        assertEquals((h - 540) / 2f, c[1], 0.5f);
        // zoomed in, the whole surface can be panned; crop still inside
        float[] o = RoundVideoGeometry.clampOffset(w, h, base, 2f, circle, -1e6f, -1e6f);
        c = RoundVideoGeometry.cropSquare(w, h, base, 2f, circle, o[0], o[1]);
        assertEquals(w - c[2], c[0], 0.5f);
        assertEquals(h - c[2], c[1], 0.5f);
    }

    @Test
    void outputIsSquareWithUniformScale() {
        // 验收：正方形、不拉伸 — one scale for both axes, crop corners map to output corners
        int side = RoundVideoGeometry.outputSide(384);
        assertEquals(384, side);
        assertEquals(RoundVideoGeometry.MIN_OUTPUT_SIDE, RoundVideoGeometry.outputSide(0));
        float[] m = RoundVideoGeometry.cropToOutput(420, 0, 1080, side);
        assertEquals(0, 420 * m[0] + m[1], EPS);
        assertEquals(side, (420 + 1080) * m[0] + m[1], EPS);
        assertEquals(0, 0 * m[0] + m[2], EPS);
        assertEquals(side, 1080 * m[0] + m[2], EPS);
    }
}

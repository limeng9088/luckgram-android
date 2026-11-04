package org.telegram.luckgram.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OcrResultTest {

    private static OcrResult.Line line(String text, float x, float y, float w, float h) {
        return new OcrResult.Line(text, new OcrResult.Box(x, y, w, h), null);
    }

    @Test
    public void boxesAreNormalizedTopLeft() {
        OcrResult.Box b = OcrResult.Box.fromPixels(100, 50, 300, 150, 1000, 500);
        assertEquals(0.1f, b.x, 1e-6);
        assertEquals(0.1f, b.y, 1e-6);
        assertEquals(0.2f, b.width, 1e-6);
        assertEquals(0.2f, b.height, 1e-6);
        // Vision: origin bottom-left; a box at the bottom ends up with a large y
        OcrResult.Box v = OcrResult.Box.fromBottomLeft(0.1f, 0.0f, 0.5f, 0.2f);
        assertEquals(0.8f, v.y, 1e-6);
        // clamped to the image
        OcrResult.Box c = OcrResult.Box.fromPixels(-10, -10, 2000, 600, 1000, 500);
        assertEquals(0f, c.x, 1e-6);
        assertEquals(1f, c.width, 1e-6);
        assertEquals(1f, c.height, 1e-6);
    }

    @Test
    public void blockJoinsLinesAndUnionsBoxes() {
        OcrResult.Block b = OcrResult.Block.of(Arrays.asList(line("第一行", 0.1f, 0.1f, 0.3f, 0.05f), line("second line", 0.1f, 0.16f, 0.5f, 0.05f)), "zh");
        assertEquals("第一行\nsecond line", b.text);
        assertEquals(0.1f, b.box.x, 1e-6);
        assertEquals(0.5f, b.box.width, 1e-6);
        assertEquals(0.11f, b.box.height, 1e-6);
        assertEquals("zh", b.language);
        assertNull(OcrResult.Block.of(Collections.emptyList(), "und").language);
    }

    @Test
    public void readingOrderTopToBottomLeftToRight() {
        OcrResult.Block right = OcrResult.Block.of(Collections.singletonList(line("B", 0.6f, 0.1f, 0.2f, 0.1f)), null);
        OcrResult.Block left = OcrResult.Block.of(Collections.singletonList(line("A", 0.1f, 0.12f, 0.2f, 0.1f)), null);
        OcrResult.Block below = OcrResult.Block.of(Collections.singletonList(line("C", 0.1f, 0.5f, 0.2f, 0.1f)), null);
        List<OcrResult.Block> sorted = OcrResult.readingOrder(Arrays.asList(below, right, left));
        assertEquals("A", sorted.get(0).text);
        assertEquals("B", sorted.get(1).text);
        assertEquals("C", sorted.get(2).text);
    }

    @Test
    public void textLanguageAndTap() {
        OcrResult.Block ja = new OcrResult.Block("メニュー\nラーメン", "ja", new OcrResult.Box(0.1f, 0.1f, 0.4f, 0.2f), null, null);
        OcrResult.Block en = new OcrResult.Block("Menu", "en", new OcrResult.Box(0.1f, 0.6f, 0.2f, 0.1f), null, null);
        OcrResult r = new OcrResult(800, 600, OcrResult.mainLanguage(Arrays.asList(ja, en)), "device", Arrays.asList(ja, en));
        assertEquals("メニュー\nラーメン\nMenu", r.text());
        assertEquals("ja", r.language);
        assertTrue(r.hasText());
        assertSame(en, r.blockAt(0.2f, 0.65f));
        assertNull(r.blockAt(0.9f, 0.9f));
        assertEquals(OcrResult.SOURCE_DEVICE, new OcrResult(1, 1, null, "other", null).source);
        assertFalse(new OcrResult(1, 1, null, "ai", null).hasText());
    }
}

package org.telegram.luckgram.imagetranslate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ImageTranslateLogicTest {

    @Test
    public void targetLanguageOrder() {
        assertEquals("en", ImageTranslateLogic.targetLanguage("en", "fr", "zh", "de"));
        assertEquals("fr", ImageTranslateLogic.targetLanguage("", "fr", "zh", "de"));
        assertEquals("zh", ImageTranslateLogic.targetLanguage(null, " ", "zh", "de"));
        assertEquals("de", ImageTranslateLogic.targetLanguage(null, null, null, "de"));
        assertEquals("en", ImageTranslateLogic.targetLanguage(null, null, null, null));
    }

    @Test
    public void header() {
        assertEquals("日本語 → 中文 ▾", ImageTranslateLogic.header("日本語", "中文"));
        assertEquals("→ English ▾", ImageTranslateLogic.header(null, "English"));
    }

    @Test
    public void shouldTranslate() {
        assertTrue(ImageTranslateLogic.shouldTranslate("ラーメン", "ja", null, "zh"));
        assertFalse(ImageTranslateLogic.shouldTranslate("拉面", "zh-Hans", null, "zh"));
        assertFalse(ImageTranslateLogic.shouldTranslate("¥ 1,200", null, "ja", "zh"));
        // block language unknown: the image language decides
        assertFalse(ImageTranslateLogic.shouldTranslate("Menu", null, "en-US", "en"));
        assertTrue(ImageTranslateLogic.shouldTranslate("Menu", null, null, "zh"));
    }

    @Test
    public void batchesRespectLimits() {
        List<String> texts = new ArrayList<>(Collections.nCopies(45, "abc"));
        List<List<Integer>> b = ImageTranslateLogic.batches(texts);
        assertEquals(3, b.size());
        assertEquals(20, b.get(0).size());
        assertEquals(Integer.valueOf(40), b.get(2).get(0));

        char[] big = new char[2000];
        Arrays.fill(big, 'x');
        List<List<Integer>> c = ImageTranslateLogic.batches(Arrays.asList(new String(big), new String(big), "a"));
        assertEquals(2, c.size());
        assertEquals(Arrays.asList(1, 2), c.get(1));
    }

    /** Fake layout: characters per line = width / (0.6 × size), line height = 1.2 × size. */
    private static final ImageTranslateLogic.Measurer FAKE = (text, size, width) -> {
        int perLine = Math.max(1, (int) (width / (0.6f * size)));
        int lines = (text.length() + perLine - 1) / perLine;
        return lines * 1.2f * size;
    };

    @Test
    public void fitFontSize() {
        // fits at full size
        assertEquals(36f, ImageTranslateLogic.fitFontSize("ab", 400, 50, 40, FAKE), 0.01f);
        // long text shrinks but stays inside
        float s = ImageTranslateLogic.fitFontSize("a long translated sentence that wraps", 200, 50, 40, FAKE);
        assertTrue(s < 36f && s >= ImageTranslateLogic.MIN_FONT_PX);
        assertTrue(FAKE.height("a long translated sentence that wraps", s, 200) <= 50);
        // never below the minimum
        assertEquals(ImageTranslateLogic.MIN_FONT_PX, ImageTranslateLogic.fitFontSize(new String(new char[500]).replace('\0', 'x'), 50, 10, 10, FAKE), 0.01f);
    }

    @Test
    public void rects() {
        assertArrayEquals(new float[]{10, 20, 60, 70}, ImageTranslateLogic.pixelRect(0.1f, 0.2f, 0.5f, 0.5f, 100, 100), 0.001f);
        assertArrayEquals(new float[]{90, 0, 100, 10}, ImageTranslateLogic.pixelRect(0.9f, -0.1f, 0.5f, 0.2f, 100, 100), 0.001f);
        assertArrayEquals(new float[]{0, 18.8f, 61.2f, 71.2f}, ImageTranslateLogic.coverRect(new float[]{0, 20, 60, 70}, 10, 100, 100), 0.001f);
    }

    @Test
    public void colors() {
        assertTrue(ImageTranslateLogic.darkText(255, 255, 255));
        assertFalse(ImageTranslateLogic.darkText(0, 0, 0));
        assertFalse(ImageTranslateLogic.darkText(30, 60, 120));
        assertTrue(ImageTranslateLogic.darkText(250, 220, 120));
    }

    @Test
    public void hitTestPicksSmallest() {
        List<float[]> boxes = Arrays.asList(new float[]{0, 0, 1, 1}, new float[]{0.2f, 0.2f, 0.2f, 0.1f});
        assertEquals(1, ImageTranslateLogic.hitTest(boxes, 0.3f, 0.25f));
        assertEquals(0, ImageTranslateLogic.hitTest(boxes, 0.8f, 0.8f));
        assertEquals(-1, ImageTranslateLogic.hitTest(boxes.subList(1, 2), 0.8f, 0.8f));
    }

    @Test
    public void copyText() {
        assertEquals("Ramen\n¥ 1,200\nGyoza", ImageTranslateLogic.copyText(Arrays.asList("ラーメン", "¥ 1,200", "餃子"), Arrays.asList("Ramen", null, "Gyoza")));
    }
}

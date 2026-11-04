package org.telegram.luckgram.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OcrLogicTest {

    @Test
    public void scriptLanguage() {
        assertEquals("zh", OcrLogic.scriptLanguage("今天下午三点开会"));
        assertEquals("ja", OcrLogic.scriptLanguage("ラーメン 800円"));
        assertEquals("ja", OcrLogic.scriptLanguage("東京駅の近くにあります"));
        assertEquals("ko", OcrLogic.scriptLanguage("안녕하세요"));
        assertNull(OcrLogic.scriptLanguage("Hello world"));
        assertEquals("zh", OcrLogic.scriptLanguage("会议 Meeting 3pm 下午"));
        assertNull(OcrLogic.scriptLanguage(""));
    }

    @Test
    public void whichModelsRun() {
        assertFalse(OcrLogic.needsJapanese("中文 English"));
        assertTrue(OcrLogic.needsJapanese("メニュー"));
        assertTrue(OcrLogic.needsKorean("메뉴"));
        assertFalse(OcrLogic.needsKorean("menu"));
    }

    @Test
    public void betterAndWeight() {
        assertEquals(4, OcrLogic.weight(" a b\nc d "));
        assertTrue(OcrLogic.better("ab", "abc"));
        assertFalse(OcrLogic.better("abc", "a b c"));
        assertEquals("fr", OcrLogic.blockLanguage("fr", "Bonjour"));
        assertNull(OcrLogic.blockLanguage("und", "Bonjour"));
        assertEquals("ja", OcrLogic.blockLanguage("zh", "カレー"));
    }
}

package org.telegram.luckgram.translatein;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TranslateInLogicTest {

    private static final List<String> ZH = Collections.singletonList("zh");

    @Test
    public void buttonForForeignIncomingTextOnly() {
        assertTrue(TranslateInLogic.showButton(true, false, true, "en", ZH, "zh"));
        assertFalse("feature off", TranslateInLogic.showButton(false, false, true, "en", ZH, "zh"));
        assertFalse("own message", TranslateInLogic.showButton(true, true, true, "en", ZH, "zh"));
        assertFalse("no text", TranslateInLogic.showButton(true, false, false, "en", ZH, "zh"));
        assertFalse("not detected yet", TranslateInLogic.showButton(true, false, true, null, ZH, "zh"));
        assertFalse("und", TranslateInLogic.showButton(true, false, true, "und", ZH, "zh"));
    }

    @Test
    public void doNotTranslateLanguagesHideTheButton() {
        assertFalse(TranslateInLogic.showButton(true, false, true, "zh-TW", ZH, "zh"));
        // acceptance: add English to "don't translate" -> the button disappears
        assertFalse(TranslateInLogic.showButton(true, false, true, "en", Arrays.asList("zh", "en"), "zh"));
        assertTrue(TranslateInLogic.showButton(true, false, true, "es", Arrays.asList("zh", "en"), "zh"));
        // already in the target language
        assertFalse(TranslateInLogic.showButton(true, false, true, "en", Collections.<String>emptyList(), "en_US"));
        // nb and no are the same
        assertFalse(TranslateInLogic.showButton(true, false, true, "nb", Collections.singletonList("no"), "zh"));
    }

    @Test
    public void baseLanguage() {
        assertEquals("en", TranslateInLogic.base("en_US"));
        assertEquals("zh", TranslateInLogic.base("zh-Hant"));
        assertEquals("no", TranslateInLogic.base("nb"));
        assertNull(TranslateInLogic.base(" "));
        assertNull(TranslateInLogic.base("und"));
        assertNull(TranslateInLogic.base(null));
    }

    @Test
    public void targetLanguageIsOnlyTheUsersChoice() {
        // specs#93: never defaults to the app / system language
        assertEquals("es", TranslateInLogic.targetLanguage("es"));
        assertEquals("ja", TranslateInLogic.targetLanguage(" ja "));
        org.junit.Assert.assertNull(TranslateInLogic.targetLanguage(""));
        org.junit.Assert.assertNull(TranslateInLogic.targetLanguage(null));
    }

    @Test
    public void headerAndMenuLabels() {
        assertEquals("已翻译 · 英语 → 中文", TranslateInLogic.header("英语", "中文", true));
        assertEquals("已翻译 · 中文", TranslateInLogic.header(null, "中文", true));
        assertEquals("Translated · English → Chinese", TranslateInLogic.header("English", "Chinese", false));
        assertEquals("翻译", TranslateInLogic.menuLabel(false, true));
        assertEquals("撤销翻译", TranslateInLogic.menuLabel(true, true));
        assertEquals("Undo translation", TranslateInLogic.menuLabel(true, false));
    }

    @Test
    public void stateKeyChangesWithEditedText() {
        assertEquals("1:-100:42:abc", TranslateInLogic.stateKey(1, -100, 42, "abc"));
        assertNotEquals(TranslateInLogic.stateKey(1, -100, 42, "abc"), TranslateInLogic.stateKey(1, -100, 42, "abd"));
        assertNotEquals(TranslateInLogic.stateKey(0, -100, 42, "abc"), TranslateInLogic.stateKey(1, -100, 42, "abc"));
    }

    @Test
    public void languagesRoundTrip() {
        assertArrayEquals(new String[]{"en", "zh"}, TranslateInLogic.decodeLanguages(TranslateInLogic.encodeLanguages("en", "zh")));
        assertArrayEquals(new String[]{null, "zh"}, TranslateInLogic.decodeLanguages(TranslateInLogic.encodeLanguages(null, "zh")));
        assertArrayEquals(new String[]{null, null}, TranslateInLogic.decodeLanguages(null));
    }
}

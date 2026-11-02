package org.telegram.luckgram.translateout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TranslateFallbackLogicTest {

    @Test
    public void fallsBackOnlyWhenTelegramGaveNoText() {
        assertTrue(TranslateFallbackLogic.shouldFallback(false));
        assertFalse(TranslateFallbackLogic.shouldFallback(true));
    }

    @Test
    public void logsTelegramErrorTimeoutOrEmpty() {
        assertEquals("TRANSLATIONS_DISABLED_ALT", TranslateFallbackLogic.telegramFailure("TRANSLATIONS_DISABLED_ALT", false));
        assertEquals("FLOOD_WAIT_12", TranslateFallbackLogic.telegramFailure(" FLOOD_WAIT_12 ", false));
        assertEquals(TranslateFallbackLogic.TIMEOUT, TranslateFallbackLogic.telegramFailure(null, true));
        assertEquals(TranslateFallbackLogic.TIMEOUT, TranslateFallbackLogic.telegramFailure("X", true));
        assertEquals(TranslateFallbackLogic.EMPTY_RESULT, TranslateFallbackLogic.telegramFailure(null, false));
        assertEquals(TranslateFallbackLogic.EMPTY_RESULT, TranslateFallbackLogic.telegramFailure("", false));
    }

    @Test
    public void aiLanguageIsIso639() {
        assertEquals("en", TranslateFallbackLogic.aiLanguage("en"));
        assertEquals("en", TranslateFallbackLogic.aiLanguage("en_US"));
        assertEquals("de", TranslateFallbackLogic.aiLanguage("DE-de"));
        assertEquals("zh", TranslateFallbackLogic.aiLanguage("zh"));
        assertEquals("zh", TranslateFallbackLogic.aiLanguage("zh_CN"));
        assertEquals("zh", TranslateFallbackLogic.aiLanguage("zh-hans"));
        assertEquals("zh-Hant", TranslateFallbackLogic.aiLanguage("zh_TW"));
        assertEquals("zh-Hant", TranslateFallbackLogic.aiLanguage("zh-Hant"));
        assertEquals("zh-Hant", TranslateFallbackLogic.aiLanguage("zh-HK"));
        assertEquals("pt-BR", TranslateFallbackLogic.aiLanguage("pt_br"));
        assertEquals("pt", TranslateFallbackLogic.aiLanguage("pt-PT"));
        assertEquals("no", TranslateFallbackLogic.aiLanguage("nb"));
        assertEquals("he", TranslateFallbackLogic.aiLanguage("iw"));
        assertEquals("id", TranslateFallbackLogic.aiLanguage("in"));
        assertEquals("fil", TranslateFallbackLogic.aiLanguage("fil"));
        assertNull(TranslateFallbackLogic.aiLanguage(null));
        assertNull(TranslateFallbackLogic.aiLanguage(""));
        assertNull(TranslateFallbackLogic.aiLanguage("und"));
        assertNull(TranslateFallbackLogic.aiLanguage("x"));
        assertNull(TranslateFallbackLogic.aiLanguage("english"));
        assertNull(TranslateFallbackLogic.aiLanguage("e1"));
    }

    @Test
    public void failureKindFromServerCode() {
        assertEquals(TranslateFallbackLogic.FAIL_NETWORK, TranslateFallbackLogic.failureKind("network_error"));
        assertEquals(TranslateFallbackLogic.FAIL_QUOTA, TranslateFallbackLogic.failureKind("quota_exceeded"));
        assertEquals(TranslateFallbackLogic.FAIL_TOO_LONG, TranslateFallbackLogic.failureKind("input_too_large"));
        assertEquals(TranslateFallbackLogic.FAIL_CONSENT, TranslateFallbackLogic.failureKind("consent_denied"));
        assertEquals(TranslateFallbackLogic.FAIL_OTHER, TranslateFallbackLogic.failureKind("upstream_unavailable"));
        assertEquals(TranslateFallbackLogic.FAIL_OTHER, TranslateFallbackLogic.failureKind(null));
    }

    @Test
    public void failureMessagesSayWhy() {
        assertTrue(TranslateFallbackLogic.failureMessage(TranslateFallbackLogic.FAIL_NETWORK, true).contains("网络"));
        assertEquals("网络不通，请检查网络或代理", TranslateFallbackLogic.failureMessage(TranslateFallbackLogic.FAIL_NETWORK, true));
        assertEquals("今日翻译次数已用完，明天再来", TranslateFallbackLogic.failureMessage(TranslateFallbackLogic.FAIL_QUOTA, true));
        assertEquals("这段文字太长，无法翻译", TranslateFallbackLogic.failureMessage(TranslateFallbackLogic.FAIL_TOO_LONG, true));
        assertEquals("翻译失败，请稍后再试", TranslateFallbackLogic.failureMessage(TranslateFallbackLogic.FAIL_OTHER, true));
        // declining AI consent means "Telegram only": generic message
        assertEquals(TranslateFallbackLogic.failureMessage(TranslateFallbackLogic.FAIL_OTHER, true),
            TranslateFallbackLogic.failureMessage(TranslateFallbackLogic.FAIL_CONSENT, true));
        assertNotEquals(TranslateFallbackLogic.failureMessage(TranslateFallbackLogic.FAIL_NETWORK, false),
            TranslateFallbackLogic.failureMessage(TranslateFallbackLogic.FAIL_OTHER, false));
    }
}

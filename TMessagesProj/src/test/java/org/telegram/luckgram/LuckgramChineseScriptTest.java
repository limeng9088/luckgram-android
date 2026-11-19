package org.telegram.luckgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** luckgram-android#139: ?script= for /v1/ai/transcribe follows the UI language. */
public class LuckgramChineseScriptTest {

    @Test
    public void chineseCodes() {
        for (String c : new String[]{"zh", "zh-hans", "zh-hans-beta", "zh-Hans-CN", "zh_CN", "classic-zh-cn", "zhcncc"}) {
            assertEquals(c, "Hans", LuckgramChineseScript.of(c));
        }
        for (String c : new String[]{"zh-hant", "zh-hant-beta", "zh-Hant-TW", "zh_TW", "zh-HK", "zh-mo", "taiwan"}) {
            assertEquals(c, "Hant", LuckgramChineseScript.of(c));
        }
    }

    @Test
    public void otherLanguagesHaveNoScript() {
        for (String c : new String[]{"en", "ja", "ko", "en-US", "", "und"}) {
            assertNull(c, LuckgramChineseScript.of(c));
        }
        assertNull(LuckgramChineseScript.of(null));
    }

    @Test
    public void uiLanguageFirstThenSystem() {
        assertEquals("Hant", LuckgramChineseScript.pick("zh-hant-beta", "zh-hant", "zh-Hans-CN"));
        assertEquals("Hant", LuckgramChineseScript.pick("en", "en", "zh-Hant-HK"));
        assertEquals("Hans", LuckgramChineseScript.pick(null, null, "zh-CN"));
        assertNull(LuckgramChineseScript.pick("en", "en", "en-US"));
    }
}

package org.telegram.luckgram.proxyexit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/** specs#117: whoami parsing and the "🇸🇬 Singapore · 1.2.3.4" label. */
public class ExitInfoTest {

    @Test
    public void parsesWhoami() {
        ExitInfo e = ExitInfo.parse("{\"ip\":\"45.39.75.124\",\"country\":\"sg\",\"city\":\"Singapore\"}");
        assertEquals("45.39.75.124", e.ip);
        assertEquals("SG", e.country);
        assertEquals("Singapore", e.city);
    }

    @Test
    public void rejectsJunk() {
        assertNull(ExitInfo.parse(null));
        assertNull(ExitInfo.parse("not json"));
        assertNull(ExitInfo.parse("{\"country\":\"SG\"}"));
        assertEquals("", ExitInfo.parse("{\"ip\":\"1.2.3.4\",\"country\":\"XX\"}").country);
        assertEquals("", ExitInfo.parse("{\"ip\":\"1.2.3.4\",\"country\":\"T1\"}").country);
        assertEquals("", ExitInfo.parse("{\"ip\":\"1.2.3.4\",\"country\":\"USA\"}").country);
    }

    @Test
    public void flagFromCode() {
        assertEquals("🇸🇬", ExitInfo.flag("SG"));
        assertEquals("🇺🇸", ExitInfo.flag("US"));
        assertEquals("", ExitInfo.flag(""));
        assertEquals("", ExitInfo.flag("sg"));
    }

    @Test
    public void labelInEnglishAndChinese() {
        ExitInfo e = new ExitInfo("45.39.75.124", "SG", "");
        assertEquals("🇸🇬 Singapore · 45.39.75.124", e.label(Locale.ENGLISH));
        assertEquals("🇸🇬 新加坡 · 45.39.75.124", e.label(Locale.SIMPLIFIED_CHINESE));
        assertEquals("1.2.3.4", new ExitInfo("1.2.3.4", "", "").label(Locale.ENGLISH));
    }

    @Test
    public void freshForTenMinutes() {
        assertTrue(ExitInfo.isFresh(1000, 1000 + ExitInfo.TTL_MS - 1));
        assertFalse(ExitInfo.isFresh(1000, 1000 + ExitInfo.TTL_MS));
        assertFalse(ExitInfo.isFresh(0, 5));
        assertFalse(ExitInfo.isFresh(1000, 500));
    }
}

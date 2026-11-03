package org.telegram.luckgram.translateout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class TranslateCacheTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    public void keyDependsOnLanguageAndTextAndHidesText() {
        final String k = TranslateCache.key("zh", "Hello");
        assertEquals(k, TranslateCache.key("zh", "Hello"));
        assertNotEquals(k, TranslateCache.key("en", "Hello"));
        assertNotEquals(k, TranslateCache.key("zh", "Hello!"));
        assertEquals(64, k.length());
        assertFalse(k.contains("Hello"));
    }

    @Test
    public void hitWithinSevenDaysMissAfter() {
        final TranslateCache c = new TranslateCache();
        final String k = TranslateCache.key("zh", "Hello");
        c.put(k, "你好", "en", 1000);
        assertEquals("你好", c.get(k, 1000 + 6 * DAY).text);
        assertEquals("en", c.get(k, 1000 + 6 * DAY).fromLang);
        assertNull(c.get(k, 1000 + 7 * DAY + 1));
        assertEquals(0, c.size());
    }

    @Test
    public void keepsAtMostMaxEntriesDroppingLeastRecentlyUsed() {
        final TranslateCache c = new TranslateCache(3, TranslateCache.MAX_AGE_MS);
        c.put("a", "A", null, 1);
        c.put("b", "B", null, 2);
        c.put("c", "C", null, 3);
        assertNotNull(c.get("a", 4)); // a is now most recently used
        c.put("d", "D", null, 5);
        assertEquals(3, c.size());
        assertNull(c.get("b", 6));
        assertNotNull(c.get("a", 6));
        assertNotNull(c.get("c", 6));
        assertNotNull(c.get("d", 6));
    }

    @Test
    public void defaultLimitIs2000() {
        final TranslateCache c = new TranslateCache();
        for (int i = 0; i < 2100; i++) {
            c.put("k" + i, "v" + i, null, i);
        }
        assertEquals(TranslateCache.MAX_ENTRIES, c.size());
        assertNull(c.get("k0", 3000));
        assertNotNull(c.get("k2099", 3000));
    }

    @Test
    public void ignoresEmptyResults() {
        final TranslateCache c = new TranslateCache();
        c.put("k", "", null, 1);
        c.put("k2", null, null, 1);
        assertEquals(0, c.size());
    }

    @Test
    public void serializeRoundTripSkipsExpired() {
        final TranslateCache c = new TranslateCache();
        c.put("old", "旧", null, 0);
        c.put("new", "新", "en", 8 * DAY);
        final TranslateCache r = TranslateCache.parse(c.serialize(), 8 * DAY + 1);
        assertNull(r.get("old", 8 * DAY + 1));
        assertEquals("新", r.get("new", 8 * DAY + 1).text);
        assertEquals("en", r.get("new", 8 * DAY + 1).fromLang);
        assertTrue(TranslateCache.parse("not json", 0).size() == 0);
        assertEquals(0, TranslateCache.parse(null, 0).size());
    }
}

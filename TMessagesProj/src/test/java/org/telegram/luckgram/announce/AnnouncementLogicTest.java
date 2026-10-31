package org.telegram.luckgram.announce;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AnnouncementLogicTest {

    private static final String RESPONSE = "{\"announcements\":["
        + "{\"id\":\"a1\",\"title\":\"新版本\",\"body\":\"去更新\",\"url\":\"https://luckgram.cc/x\",\"priority\":50,\"starts_at\":\"2026-11-01T00:00:00Z\",\"ends_at\":\"2026-11-02T00:00:00Z\"},"
        + "{\"id\":\"a2\",\"title\":\"第二条\",\"body\":\"\",\"url\":\"javascript:alert(1)\",\"priority\":10,\"ends_at\":\"2026-11-03T00:00:00Z\"},"
        + "{\"id\":\"\",\"title\":\"no id\"},{\"id\":\"a3\",\"title\":\"\"}]}";

    @Test
    public void parsesAndDropsBadEntries() throws Exception {
        List<AnnouncementLogic.Item> items = AnnouncementLogic.parse(new JSONObject(RESPONSE));
        assertEquals(2, items.size());
        assertEquals("a1", items.get(0).id);
        assertEquals("https://luckgram.cc/x", items.get(0).url);
        assertEquals("", items.get(1).url); // only https links are opened
        assertEquals(0, AnnouncementLogic.parse(new JSONObject("{}")).size());
    }

    @Test
    public void picksFirstOpenAndUnexpired() throws Exception {
        List<AnnouncementLogic.Item> items = AnnouncementLogic.parse(new JSONObject(RESPONSE));
        long nov1 = 1793491200000L; // 2026-11-01T00:00:00Z
        assertEquals("a1", AnnouncementLogic.pick(items, Collections.emptyList(), nov1).id);
        assertEquals("a2", AnnouncementLogic.pick(items, Collections.singletonList("a1"), nov1).id);
        // a1 ends at 2026-11-02T00:00Z: gone at that instant even without a new fetch.
        assertEquals("a2", AnnouncementLogic.pick(items, Collections.emptyList(), nov1 + 86_400_000L).id);
        assertNull(AnnouncementLogic.pick(items, Arrays.asList("a1", "a2"), nov1));
        assertNull(AnnouncementLogic.pick(null, Collections.emptyList(), nov1));
    }

    @Test
    public void dismissedListIsCappedAndRoundTrips() {
        List<String> d = new ArrayList<>();
        for (int i = 0; i < 60; i++) d = AnnouncementLogic.dismiss(d, "id" + i);
        assertEquals(AnnouncementLogic.MAX_DISMISSED, d.size());
        assertEquals("id10", d.get(0));
        d = AnnouncementLogic.dismiss(d, "id10"); // re-closing moves it to the end
        assertEquals("id10", d.get(d.size() - 1));
        assertEquals(d, AnnouncementLogic.parseDismissed(AnnouncementLogic.serializeDismissed(d)));
        assertTrue(AnnouncementLogic.parseDismissed("garbage").isEmpty());
        assertTrue(AnnouncementLogic.parseDismissed(null).isEmpty());
    }

    @Test
    public void refreshSchedule() {
        long now = 10_000_000_000L;
        assertTrue(AnnouncementLogic.shouldRefresh(now, 0, 0));
        assertFalse(AnnouncementLogic.shouldRefresh(now, now - 60_000, 0));
        assertTrue(AnnouncementLogic.shouldRefresh(now, now - AnnouncementLogic.REFRESH_INTERVAL_MS, 0));
        assertFalse(AnnouncementLogic.shouldRefresh(now, 0, now - 60_000)); // failed a minute ago
        assertTrue(AnnouncementLogic.shouldRefresh(now, 0, now - AnnouncementLogic.RETRY_AFTER_FAILURE_MS));
    }
}

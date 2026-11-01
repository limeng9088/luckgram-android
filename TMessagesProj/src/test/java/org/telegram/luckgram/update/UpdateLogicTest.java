package org.telegram.luckgram.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class UpdateLogicTest {

    private static final long H = 60 * 60_000L;

    @Test
    public void compareVersions() {
        assertEquals(0, UpdateLogic.compareVersions("13.0", "13.0.0"));
        assertEquals(-1, UpdateLogic.compareVersions("13.0.3", "13.0.10"));
        assertEquals(1, UpdateLogic.compareVersions("13.1", "13.0.99"));
        assertEquals(1, UpdateLogic.compareVersions("2", "1.9.9.9"));
        assertEquals(0, UpdateLogic.compareVersions("garbage", "0"));
        assertEquals(-1, UpdateLogic.compareVersions("", "0.0.1"));
    }

    @Test
    public void newerByBuildOnlyWhenBothKnown() {
        assertTrue(UpdateLogic.isNewer("13.0.3", 36, "13.0.3", 6));
        assertFalse(UpdateLogic.isNewer("13.0.3", 6, "13.0.3", 36));
        assertFalse(UpdateLogic.isNewer("13.0.3", 36, "13.0.3", 0));
        assertFalse(UpdateLogic.isNewer("13.0.3", 0, "13.0.3", 6));
        assertTrue(UpdateLogic.isNewer("13.0.4", 1, "13.0.3", 99));
        assertFalse(UpdateLogic.isNewer("13.0.2", 99, "13.0.3", 1));
    }

    @Test
    public void checkAtMostOncePerDay() {
        long now = 1_000_000_000_000L;
        assertTrue(UpdateLogic.shouldCheck(now, 0, 0));
        assertFalse(UpdateLogic.shouldCheck(now, now - 23 * H, 0));
        assertTrue(UpdateLogic.shouldCheck(now, now - 24 * H, 0));
        assertTrue(UpdateLogic.shouldCheck(now, now + H, 0)); // clock went back
        assertFalse(UpdateLogic.shouldCheck(now, 0, now - H / 2)); // recent failure
        assertTrue(UpdateLogic.shouldCheck(now, 0, now - H));
    }

    @Test
    public void path() {
        assertEquals("/v1/version?platform=android&app_version=13.0.3&build=36", UpdateLogic.path("android", "13.0.3", 36));
        assertEquals("/v1/version?platform=android&app_version=13.0.3", UpdateLogic.path("android", "13.0.3", 0));
        assertEquals("/v1/version?platform=android", UpdateLogic.path("android", "13.0.3-beta&x=1", 36));
    }

    private static UpdateLogic.Info info(String latest, long build, String min, String url) {
        return new UpdateLogic.Info(latest, build, min, url, "修复问题", "Bug fixes");
    }

    @Test
    public void evaluate() {
        String url = "https://example.com/releases/a.apk";
        UpdateLogic.State s = UpdateLogic.evaluate(info("13.0.4", 40, "13.0.0", url), "13.0.3", 36, true);
        assertTrue(s.updateAvailable);
        assertFalse(s.forceUpdate);
        assertEquals("13.0.4", s.latest);
        assertEquals(url, s.url);
        assertEquals("修复问题", s.notes);
        assertEquals("Bug fixes", UpdateLogic.evaluate(info("13.0.4", 40, "13.0.0", url), "13.0.3", 36, false).notes);

        // up to date / already installed the cached latest
        assertFalse(UpdateLogic.evaluate(info("13.0.3", 36, "13.0.0", url), "13.0.3", 36, true).updateAvailable);
        assertFalse(UpdateLogic.evaluate(info("13.0.4", 40, "", url), "13.0.4", 40, true).updateAvailable);

        // below min -> force
        s = UpdateLogic.evaluate(info("13.1.0", 50, "13.1", url), "13.0.3", 36, true);
        assertTrue(s.forceUpdate);
        assertTrue(s.updateAvailable);
        // equal to min is fine
        assertFalse(UpdateLogic.evaluate(info("13.1.0", 50, "13.0.3", url), "13.0.3", 36, true).forceUpdate);

        // no https link -> nothing (a force screen without a way out would lock the user in)
        assertFalse(UpdateLogic.evaluate(info("13.1.0", 50, "13.1", "http://example.com/a.apk"), "13.0.3", 36, true).forceUpdate);
        assertFalse(UpdateLogic.evaluate(info("13.1.0", 50, "13.1", ""), "13.0.3", 36, true).updateAvailable);
        assertFalse(UpdateLogic.evaluate(null, "13.0.3", 36, true).updateAvailable);
    }

    @Test
    public void parseAndPersist() throws Exception {
        JSONObject j = new JSONObject("{\"platform\":\"android\",\"latest\":\"13.0.4\",\"build\":40,\"min\":\"13.0.0\","
                + "\"url\":\"https://example.com/a.apk\",\"notes\":{\"zh\":\"中\",\"en\":\"en\"},\"update_available\":true,\"force_update\":false}");
        UpdateLogic.Info i = UpdateLogic.deserialize(UpdateLogic.serialize(UpdateLogic.parse(j)));
        assertEquals("13.0.4", i.latest);
        assertEquals(40, i.build);
        assertEquals("13.0.0", i.min);
        assertEquals("https://example.com/a.apk", i.url);
        assertEquals("中", i.notesZh);
        assertEquals("en", i.notesEn);
        assertNull(UpdateLogic.deserialize("{not json"));
        assertNull(UpdateLogic.deserialize(""));
        // missing fields -> empty, no crash
        UpdateLogic.Info empty = UpdateLogic.parse(new JSONObject("{}"));
        assertEquals("", empty.latest);
        assertEquals(0, empty.build);
    }
}

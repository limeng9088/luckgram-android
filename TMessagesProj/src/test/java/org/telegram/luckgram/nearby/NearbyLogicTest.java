package org.telegram.luckgram.nearby;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NearbyLogicTest {

    @Test
    public void coarsensToTwoDecimals() throws Exception {
        assertEquals("31.23", NearbyLogic.coord(31.230416));
        assertEquals("121.47", NearbyLogic.coord(121.473701));
        assertEquals("-33.87", NearbyLogic.coord(-33.868820));
        assertEquals("0.00", NearbyLogic.coord(-0.001));
        JSONObject body = NearbyLogic.cardBody("A", "bio", "alice", 31.230416, 121.473701, null);
        assertTrue(body.get("lat").toString().matches("-?\\d+(\\.\\d{1,2})?"));
        assertTrue(body.get("lon").toString().matches("-?\\d+(\\.\\d{1,2})?"));
        assertFalse(body.has("avatar"));
        assertEquals("/v1/nearby?lat=31.23&lon=121.47", NearbyLogic.queryPath(31.230416, 121.473701));
        // "" deletes the avatar on the server
        assertEquals("", NearbyLogic.cardBody("A", "", "alice", 1, 1, "").getString("avatar"));
    }

    @Test
    public void bioIsTrimmedAndCut() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 80; i++) b.append('字');
        assertEquals(70, NearbyLogic.cleanBio(b.toString()).length());
        assertEquals("a b", NearbyLogic.cleanBio("  a\nb "));
        assertEquals("", NearbyLogic.cleanBio(null));
        // emoji (surrogate pairs) are not split
        StringBuilder e = new StringBuilder();
        for (int i = 0; i < 71; i++) e.append("😀");
        String cut = NearbyLogic.cleanBio(e.toString());
        assertEquals(70, cut.codePointCount(0, cut.length()));
    }

    @Test
    public void preconditions() {
        assertEquals(NearbyLogic.NEED_USERNAME, NearbyLogic.precondition(null));
        assertEquals(NearbyLogic.NEED_USERNAME, NearbyLogic.precondition(""));
        assertEquals(NearbyLogic.READY, NearbyLogic.precondition("alice"));
    }

    @Test
    public void parsesListDropsSelfAndBadItems() throws Exception {
        JSONObject j = new JSONObject("{\"items\":["
            + "{\"id\":\"1\",\"display_name\":\"A\",\"bio\":\"x\",\"username\":\"a\",\"distance_bucket\":\"1km\",\"avatar_url\":\"https://cdn/x.jpg\"},"
            + "{\"id\":\"2\",\"display_name\":\"Me\",\"username\":\"ME\",\"distance_bucket\":\"500m\"},"
            + "{\"id\":\"\",\"username\":\"nobody\"},"
            + "{\"id\":\"4\",\"username\":\"d\",\"distance_bucket\":\"weird\",\"avatar_url\":\"http://insecure\"}]}");
        List<NearbyLogic.Person> l = NearbyLogic.parseList(j, "me");
        assertEquals(2, l.size());
        assertEquals("https://cdn/x.jpg", l.get(0).avatarUrl);
        assertEquals("d", l.get(1).displayName);
        assertEquals("far", l.get(1).bucket);
        assertEquals(null, l.get(1).avatarUrl);
    }

    @Test
    public void capsAt100() throws Exception {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 150; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(i).append("\",\"username\":\"u").append(i).append("\",\"distance_bucket\":\"3km\"}");
        }
        sb.append("]}");
        assertEquals(100, NearbyLogic.parseList(new JSONObject(sb.toString()), null).size());
    }

    @Test
    public void groupsInBucketOrderKeepingServerOrder() {
        List<NearbyLogic.Person> l = new ArrayList<>();
        String[][] d = {{"a", "far"}, {"b", "500m"}, {"c", "3km"}, {"d", "500m"}};
        for (String[] x : d) {
            NearbyLogic.Person p = new NearbyLogic.Person();
            p.id = x[0];
            p.bucket = x[1];
            l.add(p);
        }
        Map<String, List<NearbyLogic.Person>> g = NearbyLogic.group(l);
        assertEquals("[500m, 3km, far]", g.keySet().toString());
        assertEquals("b", g.get("500m").get(0).id);
        assertEquals("d", g.get("500m").get(1).id);
    }

    @Test
    public void expiryAndReminder() throws Exception {
        long now = 1_000_000_000L;
        long exp = now + 24L * 3600 * 1000;
        assertEquals(exp - 3600_000L, NearbyLogic.reminderAt(exp, now));
        assertEquals(0, NearbyLogic.reminderAt(now + 30 * 60 * 1000, now));
        assertFalse(NearbyLogic.isExpired(exp, now));
        assertTrue(NearbyLogic.isExpired(exp, exp));
        NearbyLogic.MyState s = NearbyLogic.parseMe(new JSONObject("{\"visible\":true,\"expires_at\":\"2026-11-02T08:00:00Z\"}"));
        assertTrue(s.visible);
        assertEquals(1793606400000L, s.expiresAtMs);
        assertFalse(NearbyLogic.parseMe(new JSONObject("{\"visible\":false}")).visible);
    }

    @Test
    public void textsInBothLanguages() {
        NearbyLogic.Texts zh = new NearbyLogic.Texts(true);
        NearbyLogic.Texts en = new NearbyLogic.Texts(false);
        for (String b : NearbyLogic.BUCKETS) {
            assertNotEquals(zh.bucket(b), en.bucket(b));
        }
        for (String r : NearbyLogic.REPORT_REASONS) {
            assertNotEquals(zh.reportReason(r), en.reportReason(r));
        }
        assertEquals("500 米内", zh.bucket("500m"));
        assertEquals("Within 1 km", en.bucket("1km"));
        assertTrue(zh.rateLimited(61).contains("2 分钟"));
        assertTrue(en.rateLimited(0).contains("1 min"));
    }

    @Test
    public void cardNameMatchesIosAndIsCapped() throws Exception {
        assertEquals("Ann", NearbyLogic.cardName("Ann", "Lee", "ann"));
        assertEquals("Lee", NearbyLogic.cardName(" ", "Lee", "ann"));
        assertEquals("ann", NearbyLogic.cardName(null, null, "ann"));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 70; i++) b.append("\uD83D\uDE00");
        String n = NearbyLogic.cardName(b.toString(), null, "u");
        assertEquals(64, n.codePointCount(0, n.length()));
        org.json.JSONObject body = NearbyLogic.cardBody(b.toString(), "", "u", 31.2, 121.4, null);
        assertEquals(64, body.getString("display_name").codePointCount(0, body.getString("display_name").length()));
    }

    @Test
    public void missedReminderAfterReboot() {
        long hour = 60 * 60 * 1000L;
        long exp = 1_000_000_000_000L;
        // Rebooted 30 min before expiry, not reminded yet: remind now.
        assertTrue(NearbyLogic.missedReminder(exp, exp - hour / 2, 0));
        // Already reminded for this expiry: no second one.
        assertFalse(NearbyLogic.missedReminder(exp, exp - hour / 2, exp));
        // Reminder still ahead: the alarm handles it.
        assertFalse(NearbyLogic.missedReminder(exp, exp - 2 * hour, 0));
        // Expired or nothing saved.
        assertFalse(NearbyLogic.missedReminder(exp, exp, 0));
        assertFalse(NearbyLogic.missedReminder(0, exp, 0));
    }

    @Test
    public void providerChainFallsBackToGps() {
        // API 31+: fused first, then GPS once.
        assertEquals(java.util.Arrays.asList("fused", "gps"), NearbyLogic.providerChain(31, true, true, true));
        // No fused provider on the device: network, then GPS.
        assertEquals(java.util.Arrays.asList("network", "gps"), NearbyLogic.providerChain(34, false, true, true));
        // Before 31 fused isn't used even if listed.
        assertEquals(java.util.Arrays.asList("network", "gps"), NearbyLogic.providerChain(30, true, true, true));
        // Only one on.
        assertEquals(java.util.Collections.singletonList("gps"), NearbyLogic.providerChain(29, false, false, true));
        assertEquals(java.util.Collections.singletonList("network"), NearbyLogic.providerChain(29, false, true, false));
        assertTrue(NearbyLogic.providerChain(33, false, false, false).isEmpty());
    }

    @Test
    public void fallbackFixMustBeFresh() {
        long now = 1_800_000_000_000L;
        assertTrue(NearbyLogic.isFreshFix(now - 29 * 60 * 1000L, now));
        assertTrue(NearbyLogic.isFreshFix(now - 30 * 60 * 1000L, now));
        assertFalse(NearbyLogic.isFreshFix(now - 31 * 60 * 1000L, now));
        assertFalse(NearbyLogic.isFreshFix(0, now));
        assertFalse(NearbyLogic.isFreshFix(now + 10 * 60 * 1000L, now));
    }

    @Test
    public void noLinkWordingInTexts() throws Exception {
        for (boolean zh : new boolean[]{true, false}) {
            NearbyLogic.Texts tx = new NearbyLogic.Texts(zh);
            for (java.lang.reflect.Method m : NearbyLogic.Texts.class.getMethods()) {
                if (m.getReturnType() != String.class || m.getParameterCount() != 0 || m.getDeclaringClass() != NearbyLogic.Texts.class) continue;
                String v = (String) m.invoke(tx);
                assertFalse(m.getName() + ": " + v, v.contains("绑定") || v.contains("解绑") || v.toLowerCase(java.util.Locale.ROOT).contains("link"));
            }
        }
    }
}

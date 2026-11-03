package org.telegram.luckgram.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.json.JSONObject;
import org.junit.Test;
import org.telegram.luckgram.account.AccountLogic;

/** specs#87 (29): one Pro state from /v1/me; S12 fields, older fields, missing = free, offline cache. */
public class ProStateTest {

    private static final long NOW = AccountLogic.parseTime("2026-11-03T00:00:00Z");

    @Test
    public void s12Fields() throws Exception {
        JSONObject me = new JSONObject("{\"pro\":{\"active\":true,\"expires_at\":\"2026-12-03T00:00:00Z\"},"
            + "\"credits\":{\"remaining\":37,\"total\":50,\"resets_at\":\"2026-12-01T00:00:00Z\"},"
            + "\"perks\":[\"ai\",\"no_ads\",\"summary\",\"ai\",\"\"],"
            + "\"limits\":{\"accounts_max\":50,\"summary_max_messages\":1000}}");
        ProState s = ProState.parse(me, NOW);
        assertTrue(s.isPro(NOW));
        assertEquals(37, s.credits(NOW));
        assertEquals(50, s.creditsTotal);
        assertEquals(AccountLogic.parseTime("2026-12-01T00:00:00Z"), s.creditsResetsAtMs);
        assertEquals(Arrays.asList("ai", "no_ads", "summary"), s.perks);
        assertTrue(s.hasPerk(ProState.PERK_NO_ADS));
        assertFalse(s.hasPerk(ProState.PERK_AGENT_MARKET));
        assertEquals(50, s.accountsMax(NOW));
        assertEquals(1000, s.summaryMaxMessages(NOW));
    }

    @Test
    public void olderTierFields() throws Exception {
        ProState s = ProState.parse(new JSONObject("{\"tier\":\"pro\",\"pro_expires_at\":\"2026-11-10T00:00:00Z\"}"), NOW);
        assertTrue(s.isPro(NOW));
        assertFalse(s.isPro(AccountLogic.parseTime("2026-11-10T00:00:01Z")));
        ProState free = ProState.parse(new JSONObject("{\"tier\":\"free\",\"pro_expires_at\":null}"), NOW);
        assertFalse(free.isPro(NOW));
    }

    @Test
    public void missingFieldsAreFree() throws Exception {
        ProState s = ProState.parse(new JSONObject("{\"quota\":{}}"), NOW);
        assertFalse(s.isPro(NOW));
        assertEquals(0, s.credits(NOW));
        assertTrue(s.perks.isEmpty());
        assertEquals(ProState.ACCOUNTS_MAX_FREE, s.accountsMax(NOW));
        assertEquals(ProState.SUMMARY_MAX_FREE, s.summaryMaxMessages(NOW));
        assertFalse(ProState.parse(null, NOW).isPro(NOW));
        assertFalse(ProState.free().isPro(NOW));
    }

    @Test
    public void expiredOrInactiveIsFree() throws Exception {
        ProState expired = ProState.parse(new JSONObject("{\"pro\":{\"active\":true,\"expires_at\":\"2026-11-01T00:00:00Z\"},\"credits\":{\"remaining\":5}}"), NOW);
        assertFalse(expired.isPro(NOW));
        assertEquals(0, expired.credits(NOW));
        assertEquals(ProState.SUMMARY_MAX_FREE, expired.summaryMaxMessages(NOW));
        ProState off = ProState.parse(new JSONObject("{\"pro\":{\"active\":false,\"expires_at\":\"2027-01-01T00:00:00Z\"}}"), NOW);
        assertFalse(off.isPro(NOW));
        // active without a known expiry: the server's word decides
        assertTrue(ProState.parse(new JSONObject("{\"pro\":{\"active\":true}}"), NOW).isPro(NOW));
        // Pro without limits: Pro defaults
        assertEquals(ProState.ACCOUNTS_MAX_PRO, ProState.parse(new JSONObject("{\"pro\":{\"active\":true}}"), NOW).accountsMax(NOW));
    }

    @Test
    public void cacheKeepsProOfflineUntilExpiry() throws Exception {
        ProState s = ProState.parse(new JSONObject("{\"pro\":{\"active\":true,\"expires_at\":\"2026-11-20T00:00:00Z\"},"
            + "\"credits\":{\"remaining\":3,\"total\":50},\"perks\":[\"ai\"],\"limits\":{\"summary_max_messages\":500}}"), NOW);
        ProState c = ProState.fromCache(s.toCache());
        assertTrue(c.sameAs(s));
        assertTrue(c.isPro(NOW));
        assertEquals(500, c.summaryMaxMessages(NOW));
        assertEquals(NOW, c.fetchedAtMs);
        assertFalse(c.isPro(AccountLogic.parseTime("2026-11-20T00:00:00Z")));
        assertFalse(ProState.fromCache(null).isPro(NOW));
        assertFalse(ProState.fromCache("not json").isPro(NOW));
    }

    @Test
    public void changeDetection() throws Exception {
        ProState a = ProState.parse(new JSONObject("{\"pro\":{\"active\":true},\"credits\":{\"remaining\":3}}"), NOW);
        ProState b = ProState.parse(new JSONObject("{\"pro\":{\"active\":true},\"credits\":{\"remaining\":2}}"), NOW + 1000);
        assertFalse(a.sameAs(b));
        assertTrue(a.sameAs(ProState.parse(new JSONObject("{\"pro\":{\"active\":true},\"credits\":{\"remaining\":3}}"), NOW + 5000)));
        assertFalse(a.sameAs(null));
    }
}

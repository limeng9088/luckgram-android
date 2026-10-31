package org.telegram.luckgram.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

public class AccountLogicTest {

    @Test
    public void parsesLinkStatus() throws Exception {
        AccountLogic.LinkStatus s = AccountLogic.parseLink(new JSONObject("{\"linked\":true,\"devices\":2,\"max_devices\":5,\"request\":\"linked\"}"));
        assertTrue(s.linked);
        assertEquals(2, s.devices);
        assertEquals(5, s.maxDevices);
        assertEquals("linked", s.request);
        AccountLogic.LinkStatus none = AccountLogic.parseLink(new JSONObject("{\"linked\":false,\"devices\":0,\"max_devices\":5}"));
        assertFalse(none.linked);
        assertNull(none.request);
    }

    @Test
    public void pollsOnlyWhilePendingAndUnexpired() {
        long exp = 1_000_000;
        assertTrue(AccountLogic.shouldPoll("pending", exp - 1, exp));
        assertTrue(AccountLogic.shouldPoll(null, exp - 1, exp));
        assertFalse(AccountLogic.shouldPoll("pending", exp, exp));
        for (String done : new String[]{"linked", "expired", "used", "limit_reached"}) {
            assertFalse(done, AccountLogic.shouldPoll(done, 0, exp));
        }
    }

    @Test
    public void parsesStartAndTimes() throws Exception {
        AccountLogic.LinkStart st = AccountLogic.parseStart(new JSONObject(
            "{\"nonce\":\"abc\",\"url\":\"https://t.me/LuckgramBot?start=abc\",\"expires_at\":\"2026-11-01T00:10:00Z\"}"));
        assertEquals(1793491800000L, st.expiresAtMs);
        assertTrue(AccountLogic.isTelegramLink(st.url));
        assertFalse(AccountLogic.isTelegramLink("https://evil.example/t.me/x"));
        assertFalse(AccountLogic.isTelegramLink("https://t.me/"));
        assertEquals(AccountLogic.parseTime("2026-11-01T08:10:00+08:00"), st.expiresAtMs);
        assertEquals(AccountLogic.parseTime("2026-11-01T00:10:00.123Z"), st.expiresAtMs);
        assertEquals(0, AccountLogic.parseTime("garbage"));
        assertEquals(0, AccountLogic.parseTime(""));
        assertEquals("2026-11-01", AccountLogic.date("2026-10-31T16:30:00Z", TimeZone.getTimeZone("Asia/Shanghai")));
        assertEquals("2026-10-31", AccountLogic.date("2026-10-31T16:30:00Z", TimeZone.getTimeZone("UTC")));
    }

    @Test
    public void parsesBillingAndHidesFreePlans() throws Exception {
        AccountLogic.Billing b = AccountLogic.parseBilling(new JSONObject("{\"enabled\":true,\"linked\":true,\"tier\":\"pro\","
            + "\"pro_expires_at\":\"2026-12-01T00:00:00Z\",\"plans\":[{\"id\":\"pro_month\",\"days\":30,\"stars\":100},{\"id\":\"x\",\"days\":1,\"stars\":0}]}"));
        assertTrue(b.enabled);
        assertTrue(b.pro);
        assertEquals("2026-12-01T00:00:00Z", b.proExpiresAt);
        assertEquals(1, b.plans.size());
        assertEquals("pro_month", b.plans.get(0).id);
        AccountLogic.Billing free = AccountLogic.parseBilling(new JSONObject("{\"enabled\":false,\"linked\":false,\"plans\":[],\"tier\":\"free\",\"pro_expires_at\":null}"));
        assertFalse(free.pro);
        assertNull(free.proExpiresAt);
    }

    @Test
    public void textsInBothLanguages() {
        AccountLogic.Texts zh = new AccountLogic.Texts(true), en = new AccountLogic.Texts(false);
        assertTrue(zh.linkResult("limit_reached", 5).contains("5 台"));
        assertTrue(en.linkResult("limit_reached", 5).contains("5 linked"));
        for (String code : new String[]{"link_unavailable", "already_linked", "not_linked", "billing_disabled", "rate_limited", "network_error", "x", null}) {
            assertFalse(zh.error(code).isEmpty());
            assertFalse(en.error(code).isEmpty());
        }
        assertEquals("Pro，到期 2026-12-01", zh.proUntil("2026-12-01"));
    }
}

package org.telegram.luckgram.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

public class ProPerksTest {

    @Test
    public void rowsFollowFixedOrderAndPerks() {
        List<ProPerks.Row> rows = ProPerks.rows(Arrays.asList("no_ads", "ai", "agent_market", "unknown", "accounts"), new ProPerks.Numbers(), true);
        assertEquals(4, rows.size());
        assertEquals("agent_market", rows.get(0).perk);
        assertEquals("accounts", rows.get(1).perk);
        assertEquals("ai", rows.get(2).perk);
        assertEquals("no_ads", rows.get(3).perk);
        assertTrue(rows.get(0).proOnly);
        assertTrue(rows.get(1).proOnly);
        assertFalse(rows.get(2).proOnly);
        assertEquals(7, ProPerks.rows(Arrays.asList(ProPerks.ORDER), null, false).size());
        assertTrue(ProPerks.rows(Collections.emptyList(), null, true).isEmpty());
    }

    @Test
    public void livePerksPreferMeThenConfig() {
        assertEquals(Arrays.asList("ai"), ProPerks.livePerks(Arrays.asList("ai", "ai"), "summary"));
        assertEquals(Arrays.asList("summary", "no_ads"), ProPerks.livePerks(null, "summary, no_ads,"));
        assertEquals(Arrays.asList("ai", "translate"), ProPerks.livePerks(Collections.emptyList(), "[\"ai\",\"translate\"]"));
        assertTrue(ProPerks.livePerks(null, null).isEmpty());
    }

    @Test
    public void subtitlesUseRemoteNumbers() {
        ProPerks.Numbers n = new ProPerks.Numbers();
        n.accountsMaxPro = 50;
        n.chatPro = 500;
        n.chatFree = 50;
        n.monthlyCredits = 50;
        n.summaryMaxPro = 1000;
        n.translatePro = 2000;
        n.translateFree = 200;
        ProPerks.Texts zh = new ProPerks.Texts(true);
        assertEquals("可同时登录 50 个账号；账号备份加密存到云端。", zh.perkSubtitle("accounts", n));
        assertEquals("AI 对话每天 500 次（免费 50 次），每月另送 50 个 AI 点数，用于高级模型。", zh.perkSubtitle("ai", n));
        assertEquals("总结最多 1000 条消息并可追问（免费 100 条）。", zh.perkSubtitle("summary", n));
        assertEquals("每天翻译 2000 次（免费 200 次）。", zh.perkSubtitle("translate", n));
        // numbers not configured: no made-up numbers
        assertEquals("每天可翻译的次数更多。", zh.perkSubtitle("translate", new ProPerks.Numbers()));
        for (String p : ProPerks.ORDER) {
            String all = zh.perkTitle(p) + zh.perkSubtitle(p, n) + new ProPerks.Texts(false).perkSubtitle(p, n);
            assertFalse(all.contains("自动续费"));
            assertFalse(all.toLowerCase().contains("auto-renew"));
        }
    }

    @Test
    public void creditsLine() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long resets = 1764460800000L; // 2025-11-30T00:00Z
        assertEquals("本月 AI 点数 剩余 12 / 50，30 日重置", new ProPerks.Texts(true).creditsLine(12, 50, resets, utc));
        assertEquals("AI credits this month: 12 / 50 left, resets Nov 30", new ProPerks.Texts(false).creditsLine(12, 50, resets, utc));
        assertEquals("剩余 0 / 50", new ProPerks.Texts(true).creditsValue(-3, 50, 0, utc));
    }

    @Test
    public void summaryRangesNeedPro() {
        assertFalse(ProPerks.summaryNeedsPro(100, false));
        assertTrue(ProPerks.summaryNeedsPro(300, false));
        assertTrue(ProPerks.summaryNeedsPro(500, false));
        assertFalse(ProPerks.summaryNeedsPro(500, true));
        assertEquals(100, ProPerks.summaryUnreadCap(800, false, 1000));
        assertEquals(800, ProPerks.summaryUnreadCap(800, true, 1000));
        assertEquals(1000, ProPerks.summaryUnreadCap(5000, true, 1000));
        assertEquals(40, ProPerks.summaryUnreadCap(40, false, 1000));
    }

    @Test
    public void surveyOnlyOnceFromOnboarding() {
        assertTrue(ProPerks.shouldShowSurvey(true, false));
        assertFalse(ProPerks.shouldShowSurvey(true, true));
        assertFalse(ProPerks.shouldShowSurvey(false, false));
        assertEquals("too_expensive", ProPerks.surveyReason(0));
        assertEquals("prefer_free", ProPerks.surveyReason(1));
        assertEquals("try_first", ProPerks.surveyReason(2));
        assertNull(ProPerks.surveyReason(-1));
        assertNull(ProPerks.surveyReason(3));
        assertEquals(3, new ProPerks.Texts(true).surveyOptions().length);
    }
}

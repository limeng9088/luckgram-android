package org.telegram.luckgram.profileinfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/** Spec 07 (profile details): ID format, registration-date estimate, strings. */
class ProfileInfoLogicTest {

    private static ArrayList<ProfileInfoLogic.Point> bundledTable() throws Exception {
        Path p = Paths.get("TMessagesProj/src/main/res/raw/luckgram_registration_dates.txt");
        return ProfileInfoLogic.parseTable(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    // ---- 需求 1：ID

    @Test
    void userAndBotIdsAreShownAsIs() {
        assertEquals("777000", ProfileInfoLogic.displayId(ProfileInfoLogic.PEER_USER, 777000));
    }

    @Test
    void channelAndSupergroupIdsUseMinus100() {
        assertEquals("-1001234567890", ProfileInfoLogic.displayId(ProfileInfoLogic.PEER_CHANNEL, 1234567890L));
    }

    @Test
    void legacyGroupIdsUseMinus() {
        assertEquals("-123456", ProfileInfoLogic.displayId(ProfileInfoLogic.PEER_LEGACY_GROUP, 123456));
    }

    @Test
    void onlyUsersHaveATgLink() {
        assertEquals("tg://user?id=42", ProfileInfoLogic.userLink(ProfileInfoLogic.PEER_USER, 42));
        assertNull(ProfileInfoLogic.userLink(ProfileInfoLogic.PEER_CHANNEL, 42));
        assertNull(ProfileInfoLogic.userLink(ProfileInfoLogic.PEER_LEGACY_GROUP, 42));
    }

    // ---- 需求 2：注册时间

    @Test
    void bundledTableCovers2013ToRecentYearsQuarterly() throws Exception {
        ArrayList<ProfileInfoLogic.Point> t = bundledTable();
        assertTrue(t.size() >= 45, "rows: " + t.size());
        assertEquals(2013, t.get(0).monthIndex / 12);
        assertTrue(t.get(t.size() - 1).monthIndex / 12 >= 2026);
        for (int i = 1; i < t.size(); i++) {
            assertTrue(t.get(i).id > t.get(i - 1).id);
            assertTrue(t.get(i).monthIndex - t.get(i - 1).monthIndex <= 3, "gap > 1 quarter at row " + i);
        }
    }

    private static int monthsOff(ProfileInfoLogic.Estimate e, int year, int month) {
        return Math.abs((e.year * 12 + e.month) - (year * 12 + month));
    }

    @Test
    void knownAccountsAreWithinSixMonths() throws Exception {
        ArrayList<ProfileInfoLogic.Point> t = bundledTable();
        // Points from the public GetIDs / telegram-id-age data (MIT).
        long[][] known = {
                {101323197L, 2015, 3},
                {369669043L, 2017, 3},
                {805158066L, 2019, 7},
                {1974255900L, 2021, 10},
                {5144324763L, 2022, 4},
                {6872061796L, 2024, 5},
                {8325327280L, 2025, 10},
                {8559682245L, 2025, 11}, // newest data point
        };
        for (long[] k : known) {
            ProfileInfoLogic.Estimate e = ProfileInfoLogic.estimate(t, k[0]);
            assertNotNull(e, "id " + k[0]);
            assertEquals(ProfileInfoLogic.Estimate.MONTH, e.kind);
            assertTrue(monthsOff(e, (int) k[1], (int) k[2]) <= 6, "id " + k[0] + " -> " + e.year + "-" + e.month);
        }
    }

    @Test
    void veryOldIdsAreBefore2014() throws Exception {
        ProfileInfoLogic.Estimate e = ProfileInfoLogic.estimate(bundledTable(), 1000);
        assertEquals(ProfileInfoLogic.Estimate.BEFORE_2014, e.kind);
        assertEquals("早于 2014 年", ProfileInfoLogic.formatEstimate(e, true));
        assertEquals("before 2014", ProfileInfoLogic.formatEstimate(e, false));
    }

    @Test
    void idsNewerThanTheTableAreNotGuessed() throws Exception {
        assertNull(ProfileInfoLogic.estimate(bundledTable(), 9_900_000_000L));
        assertNull(ProfileInfoLogic.estimate(bundledTable(), 0));
        assertNull(ProfileInfoLogic.estimate(new ArrayList<>(), 805158066L));
    }

    @Test
    void estimatesAreMonotonic() throws Exception {
        ArrayList<ProfileInfoLogic.Point> t = bundledTable();
        int last = 0;
        for (long id = 10_000_000L; id < 8_300_000_000L; id += 25_000_000L) {
            ProfileInfoLogic.Estimate e = ProfileInfoLogic.estimate(t, id);
            if (e == null || e.kind != ProfileInfoLogic.Estimate.MONTH) {
                continue;
            }
            int m = e.year * 12 + e.month;
            assertTrue(m >= last, "id " + id);
            last = m;
        }
    }

    @Test
    void formatAlwaysSaysApproximately() {
        ProfileInfoLogic.Estimate e = ProfileInfoLogic.estimate(ProfileInfoLogic.parseTable("100,2019-01\n200,2020-01\n"), 175);
        assertEquals("约 2019 年 10 月", ProfileInfoLogic.formatEstimate(e, true));
        assertEquals("about Oct 2019", ProfileInfoLogic.formatEstimate(e, false));
    }

    @Test
    void malformedAndOutOfOrderLinesAreSkipped() {
        ArrayList<ProfileInfoLogic.Point> t = ProfileInfoLogic.parseTable("# c\n\n100,2019-01\nx,2019-02\n90,2019-03\n150,2018-01\n200,2019-13\n300,2020-01\n");
        assertEquals(2, t.size());
    }
}

package org.telegram.luckgram.sharedactivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class SharedActivityLogicTest {

    static final class M implements SharedActivityLogic.Dated {
        final int date;
        final long id;

        M(int date, long id) {
            this.date = date;
            this.id = id;
        }

        @Override
        public int date() {
            return date;
        }

        @Override
        public long sortId() {
            return id;
        }
    }

    @Test
    void mergesGroupsNewestFirst() {
        List<List<M>> groups = Arrays.asList(
                Arrays.asList(new M(300, 3), new M(100, 1)),
                Arrays.asList(new M(250, 9)),
                Arrays.asList(new M(400, 2), new M(50, 7)));
        ArrayList<M> merged = SharedActivityLogic.newestFirst(groups, 100);
        int[] dates = merged.stream().mapToInt(m -> m.date).toArray();
        assertEquals("[400, 300, 250, 100, 50]", Arrays.toString(dates));
    }

    @Test
    void keepsAtMostOneHundred() {
        List<List<M>> groups = new ArrayList<>();
        for (int g = 0; g < 8; g++) {
            List<M> list = new ArrayList<>();
            for (int i = 0; i < SharedActivityLogic.PER_GROUP; i++) {
                list.add(new M(g * 1000 + i, i));
            }
            groups.add(list);
        }
        ArrayList<M> merged = SharedActivityLogic.newestFirst(groups, SharedActivityLogic.MAX_ITEMS);
        assertEquals(100, merged.size());
        assertEquals(7019, merged.get(0).date);
        for (int i = 1; i < merged.size(); i++) {
            assertTrue(merged.get(i - 1).date >= merged.get(i).date);
        }
    }

    @Test
    void sameDateHigherIdFirstAndNullGroupsIgnored() {
        List<List<M>> groups = Arrays.asList(Arrays.asList(new M(5, 1)), null, Arrays.asList(new M(5, 2)));
        ArrayList<M> merged = SharedActivityLogic.newestFirst(groups, 100);
        assertEquals(2, merged.get(0).id);
    }

    @Test
    void parsesFloodWait() {
        assertEquals(12, SharedActivityLogic.floodWaitSeconds("FLOOD_WAIT_12"));
        assertEquals(3, SharedActivityLogic.floodWaitSeconds("FLOOD_PREMIUM_WAIT_3"));
        assertEquals(-1, SharedActivityLogic.floodWaitSeconds("PEER_ID_INVALID"));
        assertEquals(-1, SharedActivityLogic.floodWaitSeconds("FLOOD_WAIT_x"));
        assertEquals(-1, SharedActivityLogic.floodWaitSeconds(null));
    }

    @Test
    void cacheLastsTenMinutes() {
        long t = 1_000_000L;
        assertTrue(SharedActivityLogic.isFresh(t, t + 9 * 60 * 1000L));
        assertFalse(SharedActivityLogic.isFresh(t, t + 10 * 60 * 1000L));
        assertFalse(SharedActivityLogic.isFresh(0, t));
    }

    @Test
    void headerWording() {
        assertEquals("最近消息 · 3 个共同群组", SharedActivityLogic.header(3, true));
        assertEquals("Recent messages · 3 groups in common", SharedActivityLogic.header(3, false));
        assertEquals("Recent messages · 1 group in common", SharedActivityLogic.header(1, false));
    }

    @Test
    void floodWaitOnlyInsideTimeBudget() {
        // A short wait early on is sat out.
        assertTrue(SharedActivityLogic.canWaitFlood(3, 0));
        // A wait that would end after the budget is not: show what we have instead (16 P1).
        assertFalse(SharedActivityLogic.canWaitFlood(30, 0));
        assertFalse(SharedActivityLogic.canWaitFlood(3, SharedActivityLogic.TIME_BUDGET_MS - 1000));
        // Not a flood error / too long.
        assertFalse(SharedActivityLogic.canWaitFlood(-1, 0));
        assertFalse(SharedActivityLogic.canWaitFlood(SharedActivityLogic.MAX_FLOOD_WAIT_SECONDS + 1, 0));
    }

    @Test
    void partialHeader() {
        assertEquals("最近消息 · 3 个共同群组", SharedActivityLogic.header(3, false, true));
        assertEquals("最近消息 · 3 个共同群组（部分群组未加载完，点「刷新」重试）", SharedActivityLogic.header(3, true, true));
        assertEquals("Recent messages · 1 group in common (some groups didn't load, tap Refresh to retry)", SharedActivityLogic.header(1, true, false));
    }

    @Test
    void activityTabIsNeverAMediaSlot() {
        // sharedMediaData in SharedMediaLayout has 9 slots; the 动态 tab id is 100.
        assertFalse(SharedActivityLogic.isMediaSlot(SharedActivityLogic.TAB_ID, 9));
        assertFalse(SharedActivityLogic.isMediaSlot(SharedActivityLogic.TAB_ID, 1000));
        assertFalse(SharedActivityLogic.isMediaSlot(-1, 9));
        assertFalse(SharedActivityLogic.isMediaSlot(9, 9));
        assertFalse(SharedActivityLogic.isMediaSlot(14, 9)); // TAB_GIFTS
        assertTrue(SharedActivityLogic.isMediaSlot(0, 9));   // TAB_PHOTOVIDEO
        assertTrue(SharedActivityLogic.isMediaSlot(8, 9));
    }
}

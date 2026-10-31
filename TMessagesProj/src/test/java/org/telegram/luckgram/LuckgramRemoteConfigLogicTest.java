package org.telegram.luckgram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Task S4, app side. */
class LuckgramRemoteConfigLogicTest {

    private static final long H = 3600_000L;

    @Test
    void neverFetchedIsStale() {
        assertTrue(LuckgramRemoteConfigLogic.isStale(10 * H, 0, 3600, 0));
    }

    @Test
    void refreshesOnlyAfterTtl() {
        long fetched = 100 * H;
        assertFalse(LuckgramRemoteConfigLogic.isStale(fetched + 59 * 60_000L, fetched, 3600, 0));
        assertTrue(LuckgramRemoteConfigLogic.isStale(fetched + H, fetched, 3600, 0));
        // 验收：TTL 调小后（测试用）按新 TTL 刷新
        assertTrue(LuckgramRemoteConfigLogic.isStale(fetched + 61_000L, fetched, 60, 0));
        // unknown TTL falls back to an hour; clock going backwards forces a refresh
        assertFalse(LuckgramRemoteConfigLogic.isStale(fetched + 1000, fetched, 0, 0));
        assertTrue(LuckgramRemoteConfigLogic.isStale(fetched - 1000, fetched, 3600, 0));
    }

    @Test
    void failureBacksOffButKeepsCache() {
        long fetched = 100 * H, failed = fetched + 2 * H;
        assertFalse(LuckgramRemoteConfigLogic.isStale(failed + 60_000L, fetched, 3600, failed));
        assertTrue(LuckgramRemoteConfigLogic.isStale(failed + LuckgramRemoteConfigLogic.RETRY_AFTER_FAILURE_SECONDS * 1000, fetched, 3600, failed));
    }
}

package org.telegram.luckgram.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccountLimitTest {

    @Test
    public void defaults() {
        assertEquals(5, AccountLimit.DEFAULT_MAX_FREE);
        assertEquals(5, AccountLimit.limit(false, AccountLimit.DEFAULT_MAX_FREE));
        assertEquals(AccountLimit.SLOT_CAPACITY, AccountLimit.limit(true, AccountLimit.DEFAULT_MAX_FREE));
    }

    @Test
    public void freeFollowsRemoteWithinSlots() {
        assertEquals(1, AccountLimit.maxFree(0));
        assertEquals(1, AccountLimit.maxFree(-3));
        assertEquals(3, AccountLimit.maxFree(3));
        assertEquals(20, AccountLimit.maxFree(20));
        assertEquals(50, AccountLimit.maxFree(500));
    }

    @Test
    public void proOnlyBoundBySlots() {
        assertEquals(AccountLimit.SLOT_CAPACITY, AccountLimit.maxPro());
        assertEquals(AccountLimit.SLOT_CAPACITY, AccountLimit.limit(true, 3));
        assertEquals(AccountLimit.SLOT_CAPACITY, AccountLimit.limit(true, 500));
    }

    @Test
    public void freeAboveLimitKeepsAccountsButCannotAdd() {
        int free = AccountLimit.limit(false, 5);
        assertFalse(AccountLimit.canAdd(12, free));
        assertFalse(AccountLimit.canAdd(5, free));
        assertTrue(AccountLimit.canAdd(4, free));
        int pro = AccountLimit.limit(true, 5);
        assertTrue(AccountLimit.canAdd(12, pro));
        assertFalse(AccountLimit.canAdd(AccountLimit.SLOT_CAPACITY, pro));
    }

    @Test
    public void upgradeOfferedOnlyWhenProRaisesTheLimit() {
        assertTrue(AccountLimit.offerUpgrade(false, 5));
        assertFalse(AccountLimit.offerUpgrade(true, 5));
        assertFalse(AccountLimit.offerUpgrade(false, AccountLimit.SLOT_CAPACITY));
    }
}

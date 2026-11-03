package org.telegram.luckgram.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccountLimitTest {

    @Test
    public void defaults() {
        assertEquals(20, AccountLimit.limit(false, 20, 50));
        assertEquals(50, AccountLimit.limit(true, 20, 50));
    }

    @Test
    public void freeNeverBelowTwentyNorAboveSlots() {
        assertEquals(20, AccountLimit.maxFree(5));
        assertEquals(30, AccountLimit.maxFree(30));
        assertEquals(50, AccountLimit.maxFree(500));
    }

    @Test
    public void proNeverBelowFree() {
        assertEquals(22, AccountLimit.maxPro(20, 22));
        assertEquals(30, AccountLimit.maxPro(30, 22));
        assertEquals(50, AccountLimit.maxPro(20, 999));
    }

    @Test
    public void expiredProKeepsAccountsButCannotAdd() {
        int free = AccountLimit.limit(false, 20, 50);
        assertFalse(AccountLimit.canAdd(35, free));
        assertFalse(AccountLimit.canAdd(20, free));
        assertTrue(AccountLimit.canAdd(19, free));
    }

    @Test
    public void upgradeOfferedOnlyWhenProRaisesTheLimit() {
        assertTrue(AccountLimit.offerUpgrade(false, 20, 50));
        assertFalse(AccountLimit.offerUpgrade(true, 20, 50));
        assertFalse(AccountLimit.offerUpgrade(false, 50, 50));
    }
}

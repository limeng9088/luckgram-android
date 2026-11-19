package org.telegram.luckgram.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccountTokensTest {

    @Test
    public void oneKeyPerTelegramUser() {
        assertEquals("_device_token", AccountTokens.key(0));
        assertEquals("_device_token.u777", AccountTokens.key(777));
        assertNotEquals(AccountTokens.key(1), AccountTokens.key(2));
    }

    @Test
    public void recognizesTokenKeys() {
        assertTrue(AccountTokens.isTokenKey("_device_token"));
        assertTrue(AccountTokens.isTokenKey("_device_token.u42"));
        assertFalse(AccountTokens.isTokenKey(AccountTokens.MIGRATED_KEY));
        assertFalse(AccountTokens.isTokenKey("ai_consent"));
        assertFalse(AccountTokens.isTokenKey(null));
    }

    @Test
    public void legacyTokenOnlyForTheOnlyAccount() {
        assertTrue(AccountTokens.adoptsLegacy(5, new long[]{5}));
        // specs#209: two accounts, the old token may be linked to either one.
        assertFalse(AccountTokens.adoptsLegacy(5, new long[]{5, 6}));
        assertFalse(AccountTokens.adoptsLegacy(6, new long[]{5}));
        assertFalse(AccountTokens.adoptsLegacy(0, new long[]{0}));
        assertFalse(AccountTokens.adoptsLegacy(5, new long[0]));
        assertFalse(AccountTokens.adoptsLegacy(5, null));
    }
}

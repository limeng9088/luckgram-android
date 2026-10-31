package org.telegram.luckgram.unblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Spec 13 验收：ios、android、ios-android、all、all 与 ios 并存 五种情况。 */
class StoreRestrictionsTest {

    /** Mirrors MessagesController.getRestrictionReason for a store build of Luckgram. */
    private static String firstReason(boolean switchOn, List<String[]> entries) {
        for (String[] e : entries) {
            String platform = e[0];
            if (StoreRestrictions.ignore(switchOn, platform)) continue;
            if ("all".equals(platform) || "android".equals(platform) || "android-all".equals(platform)) {
                return e[1];
            }
        }
        return null;
    }

    private static String[] entry(String platform, String text) {
        return new String[]{platform, text};
    }

    @Test
    void storeOnlyPlatforms() {
        assertTrue(StoreRestrictions.isStoreOnly("ios"));
        assertTrue(StoreRestrictions.isStoreOnly("android"));
        assertTrue(StoreRestrictions.isStoreOnly("ios-android"));
        assertTrue(StoreRestrictions.isStoreOnly("android-ios"));
        assertTrue(StoreRestrictions.isStoreOnly("IOS"));
        assertFalse(StoreRestrictions.isStoreOnly("all"));
        assertFalse(StoreRestrictions.isStoreOnly("android-all"));
        assertFalse(StoreRestrictions.isStoreOnly("ios-all"));
        assertFalse(StoreRestrictions.isStoreOnly("windows"));      // unknown: keep
        assertFalse(StoreRestrictions.isStoreOnly(""));
        assertFalse(StoreRestrictions.isStoreOnly(null));
    }

    @Test
    void iosOnly() {
        List<String[]> r = Arrays.asList(entry("ios", "ios rule"));
        assertFalse(StoreRestrictions.ignore(false, "ios"));
        assertTrue(StoreRestrictions.ignore(true, "ios"));
        assertNull(firstReason(true, r));
    }

    @Test
    void androidOnly() {
        List<String[]> r = Arrays.asList(entry("android", "store rule"));
        assertEquals("store rule", firstReason(false, r)); // switch off: can't open
        assertNull(firstReason(true, r));                  // switch on: opens
    }

    @Test
    void iosAndAndroid() {
        assertTrue(StoreRestrictions.ignore(true, "ios-android"));
        assertFalse(StoreRestrictions.ignore(false, "ios-android"));
    }

    @Test
    void allPlatformsStays() {
        List<String[]> r = Arrays.asList(entry("all", "banned"));
        assertEquals("banned", firstReason(false, r));
        assertEquals("banned", firstReason(true, r));
        assertEquals("banned2", firstReason(true, Arrays.asList(entry("android-all", "banned2"))));
    }

    @Test
    void allTogetherWithIos() {
        List<String[]> r = Arrays.asList(entry("ios", "store rule"), entry("android", "store rule"), entry("all", "banned"));
        assertEquals("store rule", firstReason(false, r));
        assertEquals("banned", firstReason(true, r)); // store entries skipped, the all-platform one still blocks
    }
}

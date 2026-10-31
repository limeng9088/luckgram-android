package org.telegram.luckgram.unblock;

/**
 * Spec 13 (受限频道): which {@code restriction_reason} entries the "store_restrictions_off" switch
 * may ignore. Pure Java, unit tested on the JVM; same rule on iOS
 * ({@code LuckgramStoreRestrictions.swift}).
 *
 * <p>An entry's {@code platform} is a dash-separated list such as {@code "ios"}, {@code "android"},
 * {@code "ios-android"}, {@code "all"} or {@code "android-all"}. Only entries that name app-store
 * platforms and nothing else are store restrictions. Anything containing {@code "all"} (Telegram's
 * all-platform bans) or an unknown token stays in force, and so does everything the server
 * enforces itself (regional blocks are refused by the server, nothing here touches them).
 */
public final class StoreRestrictions {

    private StoreRestrictions() {
    }

    /** True if the entry applies only to the App Store / Google Play builds. */
    public static boolean isStoreOnly(String platform) {
        if (platform == null || platform.isEmpty()) {
            return false;
        }
        boolean any = false;
        for (String token : platform.toLowerCase(java.util.Locale.ROOT).split("-")) {
            if (token.isEmpty()) {
                continue;
            }
            if (!token.equals("ios") && !token.equals("android")) {
                return false; // "all" or something we don't know: keep it
            }
            any = true;
        }
        return any;
    }

    /** Whether an entry must be skipped: the switch is on and the entry is store-only. */
    public static boolean ignore(boolean switchOn, String platform) {
        return switchOn && isStoreOnly(platform);
    }
}

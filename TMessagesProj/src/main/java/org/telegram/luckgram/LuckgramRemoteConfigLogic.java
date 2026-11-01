package org.telegram.luckgram;

/**
 * Android-independent refresh rule of the remote config client (task S4), unit tested on the JVM.
 * Parsing lives in {@link LuckgramRemoteConfig} (00). Same rule on iOS
 * ({@code LuckgramRemoteConfigLogic.swift}).
 */
public final class LuckgramRemoteConfigLogic {

    /** Used until the server has sent {@code ttl}. */
    public static final long DEFAULT_TTL_SECONDS = 3600;
    /** After a failed fetch, wait this long before trying again (cache stays in use). */
    public static final long RETRY_AFTER_FAILURE_SECONDS = 300;

    private LuckgramRemoteConfigLogic() {
    }

    /** Whether the cached config should be refreshed now. */
    public static boolean isStale(long nowMs, long fetchedAtMs, long ttlSeconds, long lastFailureMs) {
        if (lastFailureMs > 0 && nowMs >= lastFailureMs && nowMs - lastFailureMs < RETRY_AFTER_FAILURE_SECONDS * 1000) {
            return false;
        }
        if (fetchedAtMs <= 0 || nowMs < fetchedAtMs) {
            return true; // never fetched, or the clock went backwards
        }
        long ttl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
        return nowMs - fetchedAtMs >= ttl * 1000;
    }
}

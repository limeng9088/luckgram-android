package org.telegram.luckgram.account;

/**
 * Luckgram 32: how many accounts can be logged in at once. Free and Pro limits come from remote
 * config ({@code accounts.max_free} / {@code accounts.max_pro}); same rules as iOS
 * (LuckgramAccountLimit.swift). Pure logic, unit tested.
 */
public final class AccountLimit {

    /** Slots compiled into the app (UserConfig.MAX_ACCOUNT_COUNT, tgnet Defines.h). */
    public static final int SLOT_CAPACITY = 50;
    /** Spec 09's limit; the free limit never goes below it. */
    public static final int DEFAULT_MAX_FREE = 20;
    public static final int DEFAULT_MAX_PRO = 50;
    public static final String KEY_MAX_FREE = "accounts.max_free";
    public static final String KEY_MAX_PRO = "accounts.max_pro";

    private AccountLimit() {
    }

    /** Free limit: remote value, at least 20, at most the compiled slots. */
    public static int maxFree(long remoteMaxFree) {
        return (int) Math.max(DEFAULT_MAX_FREE, Math.min(SLOT_CAPACITY, remoteMaxFree));
    }

    /** Pro limit: remote value, never below the free limit, at most the compiled slots. */
    public static int maxPro(long remoteMaxFree, long remoteMaxPro) {
        return (int) Math.max(maxFree(remoteMaxFree), Math.min(SLOT_CAPACITY, remoteMaxPro));
    }

    public static int limit(boolean pro, long remoteMaxFree, long remoteMaxPro) {
        return pro ? maxPro(remoteMaxFree, remoteMaxPro) : maxFree(remoteMaxFree);
    }

    /** Pro expiry never logs accounts out: only adding is blocked once {@code activated >= limit}. */
    public static boolean canAdd(int activated, int limit) {
        return activated < limit;
    }

    /** Show the "upgrade to Pro" entry only to free users when Pro actually raises the limit. */
    public static boolean offerUpgrade(boolean pro, long remoteMaxFree, long remoteMaxPro) {
        return !pro && maxPro(remoteMaxFree, remoteMaxPro) > maxFree(remoteMaxFree);
    }
}

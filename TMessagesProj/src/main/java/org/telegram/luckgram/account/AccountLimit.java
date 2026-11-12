package org.telegram.luckgram.account;

/**
 * Luckgram 32 / 135: how many accounts can be logged in at once. Free users get
 * {@code accounts.max_free} (default 10, remote config); Pro has no product limit and is only bound
 * by the slots compiled into the app. Same rules as iOS (LuckgramAccountLimit.swift). Pure logic,
 * unit tested.
 */
public final class AccountLimit {

    /** Slots compiled into the app (UserConfig.MAX_ACCOUNT_COUNT, tgnet Defines.h). */
    public static final int SLOT_CAPACITY = 50;
    /** Spec 135: free users may log in 10 accounts unless remote config says otherwise. */
    public static final int DEFAULT_MAX_FREE = 10;
    public static final String KEY_MAX_FREE = "accounts.max_free";

    private AccountLimit() {
    }

    /** Free limit: remote value, at least 1, at most the compiled slots. */
    public static int maxFree(long remoteMaxFree) {
        return (int) Math.max(1, Math.min(SLOT_CAPACITY, remoteMaxFree));
    }

    /** Pro limit: no product limit, only the compiled slots. */
    public static int maxPro() {
        return SLOT_CAPACITY;
    }

    public static int limit(boolean pro, long remoteMaxFree) {
        return pro ? maxPro() : maxFree(remoteMaxFree);
    }

    /** Never logs accounts out (free users above the limit, expired Pro): only adding is blocked. */
    public static boolean canAdd(int activated, int limit) {
        return activated < limit;
    }

    /** Show the "upgrade to Pro" entry only to free users when Pro actually raises the limit. */
    public static boolean offerUpgrade(boolean pro, long remoteMaxFree) {
        return !pro && maxPro() > maxFree(remoteMaxFree);
    }
}

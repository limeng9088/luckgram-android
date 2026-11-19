package org.telegram.luckgram.account;

/**
 * specs#209: every server identity (AI credits, Pro, Square, People Nearby, backup) follows the
 * Telegram user the device token is linked to, so each logged-in Telegram account has its own
 * device token and link state. Pure rules, unit tested.
 */
public final class AccountTokens {

    private AccountTokens() {
    }

    /** The single token of older versions; now the token used while no account is logged in. */
    public static final String LEGACY_KEY = "_device_token";
    /** Set once the legacy token was handed to an account, or kept because nobody could take it. */
    public static final String MIGRATED_KEY = "_device_token_migrated";

    /** Secure-store key of the device token for Telegram user {@code uid}; 0 = no account. */
    public static String key(long uid) {
        return uid == 0 ? LEGACY_KEY : LEGACY_KEY + ".u" + uid;
    }

    /** Whether {@code prefKey} holds a device token (reset clears them all). */
    public static boolean isTokenKey(String prefKey) {
        return prefKey != null && (prefKey.equals(LEGACY_KEY) || prefKey.startsWith(LEGACY_KEY + ".u"));
    }

    /**
     * Who gets the legacy token on upgrade. It is linked to one unknown Telegram user (the server
     * does not tell), so it goes to {@code uid} only when that is the one account logged in.
     * With several accounts each registers its own token and links itself on first use, never
     * acting as another account.
     */
    public static boolean adoptsLegacy(long uid, long[] loggedIn) {
        return uid != 0 && loggedIn != null && loggedIn.length == 1 && loggedIn[0] == uid;
    }
}

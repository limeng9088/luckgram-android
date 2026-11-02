package org.telegram.luckgram.diag;

/**
 * specs#72: sorts a Telegram error returned before an AI request (e.g. loading history for a
 * summary) so the user sees the real reason instead of "network error". Pure so it is unit tested.
 */
public final class TelegramErrorKind {

    /** No answer from Telegram (timeout, connection lost): a real network error. */
    public static final int NETWORK = 0;
    /** Not allowed to read here (admin required, banned, forbidden). */
    public static final int NO_PERMISSION = 1;
    /** FLOOD_WAIT_N: try again in {@link #floodWaitSeconds} seconds. */
    public static final int FLOOD_WAIT = 2;
    /** The channel or group is private, or the user is no longer in it. */
    public static final int PRIVATE = 3;
    /** The topic, thread or message no longer exists. */
    public static final int NOT_FOUND = 4;
    /** Any other Telegram error: show its code. */
    public static final int OTHER = 5;

    private TelegramErrorKind() {
    }

    /**
     * @param code Telegram's error code (400, 403, 420, ...); 0 or negative means no answer
     * @param text Telegram's error text, e.g. {@code CHANNEL_PRIVATE}
     */
    public static int of(int code, String text) {
        String t = text == null ? "" : text;
        if (t.startsWith("FLOOD_WAIT_") || t.startsWith("FLOOD_PREMIUM_WAIT_") || t.startsWith("SLOWMODE_WAIT_")) {
            return FLOOD_WAIT;
        }
        if (code <= 0 || t.isEmpty()) {
            return NETWORK;
        }
        switch (t) {
            case "CHANNEL_PRIVATE":
            case "CHANNEL_INVALID":
            case "CHAT_INVALID":
            case "PEER_ID_INVALID":
            case "USER_NOT_PARTICIPANT":
                return PRIVATE;
            case "CHAT_ADMIN_REQUIRED":
            case "CHAT_FORBIDDEN":
            case "CHAT_RESTRICTED":
            case "USER_BANNED_IN_CHANNEL":
            case "CHAT_GUEST_SEND_FORBIDDEN":
                return NO_PERMISSION;
            case "MSG_ID_INVALID":
            case "TOPIC_ID_INVALID":
            case "TOPIC_DELETED":
                return NOT_FOUND;
        }
        if (code == 403) {
            return NO_PERMISSION;
        }
        return OTHER;
    }

    /** Seconds from FLOOD_WAIT_N-style texts; 0 if absent. */
    public static int floodWaitSeconds(String text) {
        if (text == null) return 0;
        int i = text.lastIndexOf('_');
        if (i < 0 || i == text.length() - 1) return 0;
        try {
            return Math.max(0, Integer.parseInt(text.substring(i + 1)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

package org.telegram.luckgram.voicebar;

/**
 * specs#145: pure rules of the 「按住 说话」 bar (no Android dependencies; unit-tested).
 * Keep in sync with iOS ({@code LuckgramVoiceBarLogic}).
 */
public final class LuckgramVoiceBarLogic {

    /** Where the finger is while holding. */
    public static final int ZONE_SEND = 0;
    public static final int ZONE_CANCEL = 1;
    public static final int ZONE_TEXT = 2;

    private LuckgramVoiceBarLogic() {
    }

    /**
     * The zone for a finger position. Above {@code actionLineY} (the top of the release area) the
     * left half cancels and the right half converts to text; below it, releasing sends.
     */
    public static int zoneFor(float x, float y, float width, float actionLineY) {
        if (y >= actionLineY || width <= 0) {
            return ZONE_SEND;
        }
        return x < width / 2f ? ZONE_CANCEL : ZONE_TEXT;
    }

    /** m:ss for the overlay's timer. */
    public static String formatDuration(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}

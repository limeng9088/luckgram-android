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

    /** Loudest {@code recordProgressChanged} amplitude (16-bit RMS) below which nothing was said at all. */
    public static final double SILENCE_AMPLITUDE = 30;

    public static boolean isSilent(double peakAmplitude) {
        return peakAmplitude < SILENCE_AMPLITUDE;
    }

    /** What speech-to-text models make up for recordings with no speech in them. Whole-text matches only. */
    private static final String[] SILENCE_PHRASES = {
        "thankyousomuchforwatching", "thankyouforwatching", "thanksforwatching", "subtitlesbytheamaraorgcommunity",
        "ご視聴ありがとうございました", "私のビデオを見てくれてありがとう", "谢谢观看", "謝謝觀看", "字幕由amaraorg社区提供",
    };

    /** The transcript is one of the stock phrases a model invents for silence, not something that was said. */
    public static boolean isSilenceHallucination(String text) {
        if (text == null) {
            return false;
        }
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(Character.toLowerCase(c));
            }
        }
        final String normalized = b.toString();
        for (String phrase : SILENCE_PHRASES) {
            if (phrase.equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}

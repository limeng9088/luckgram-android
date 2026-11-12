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
    public static final double SILENCE_AMPLITUDE = 60;

    public static boolean isSilent(double peakAmplitude) {
        return peakAmplitude < SILENCE_AMPLITUDE;
    }

    /** What speech-to-text models make up for recordings with no speech in them. */
    private static final String[] SILENCE_PHRASES = {
        "thankyousomuchforwatching", "thankyouforwatching", "thanksforwatching", "メッセージなし", "subtitlesbytheamaraorgcommunity",
        "ご視聴ありがとうございました", "私のビデオを見てくれてありがとう", "谢谢观看", "謝謝觀看", "字幕由amaraorg社区提供",
    };

    /**
     * Loudest amplitude below which the recording was at most faint noise: a transcript made only of filler
     * sentences is then not believed either.
     */
    public static final double WEAK_AMPLITUDE = 180;

    public static boolean isWeak(double peakAmplitude) {
        return peakAmplitude < WEAK_AMPLITUDE;
    }

    /**
     * Filler sentences a model strings together for near-silence (「That's it. Thank you. God bless.」). People do
     * say these, so they only count when the recording was faint.
     */
    private static final java.util.Set<String> FILLER_SENTENCES = new java.util.HashSet<>(java.util.Arrays.asList(
        "thankyou", "thanks", "thankyouverymuch", "thatsit", "godbless", "bye", "byebye", "you", "ok", "okay", "yeah",
        "谢谢", "謝謝", "谢谢大家", "ありがとう", "ありがとうございました", "おやすみなさい"
    ));

    private static String normalize(String text) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(Character.toLowerCase(c));
            }
        }
        return b.toString();
    }

    public static boolean isSilenceHallucination(String text) {
        return isSilenceHallucination(text, false);
    }

    /**
     * The transcript is something a model invents for silence, not something that was said:
     * a short text around a stock phrase, one sentence three times over, or (faint recording only) nothing but fillers.
     */
    public static boolean isSilenceHallucination(String text, boolean weakSignal) {
        if (text == null) {
            return false;
        }
        final String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return false;
        }
        // short text built around a stock phrase (「メッセージなし。ご視聴ありがとうございました。」) counts too
        if (normalized.codePointCount(0, normalized.length()) <= 40) {
            for (String phrase : SILENCE_PHRASES) {
                if (normalized.contains(phrase)) {
                    return true;
                }
            }
        }
        final java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        boolean onlyFillers = true;
        for (String part : text.split("[.!?。！？\\n]")) {
            final String sentence = normalize(part);
            if (sentence.isEmpty()) {
                continue;
            }
            final Integer seen = counts.get(sentence);
            final int count = seen == null ? 1 : seen + 1;
            if (count >= 3) {
                return true;
            }
            counts.put(sentence, count);
            if (!FILLER_SENTENCES.contains(sentence)) {
                onlyFillers = false;
            }
        }
        return weakSignal && !counts.isEmpty() && onlyFillers;
    }
}

package org.telegram.luckgram.imagetranslate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * specs#108 image translation: the rules shared with iOS ({@code LuckgramImageTranslateLogic.swift}),
 * free of android.* / org.telegram.* so they run as plain JVM tests.
 *
 * <p>Geometry follows the #106 OCR result: boxes normalized to 0..1, origin top-left, y down.
 */
public final class ImageTranslateLogic {

    /** Feature switch (specs#108 需求 6), default on. */
    public static final String FEATURE_KEY = "image_translate";
    /** LuckgramSettings key of the image-only default language; empty = follow reading translation. */
    public static final String KEY_TO_LANG = "image_translate_to_lang";
    /** Prefix of the per-chat image language, followed by the dialog id. */
    public static final String KEY_CHAT_PREFIX = "image_translate_lang.";
    /** One Telegram translateText request carries at most this many characters. */
    public static final int MAX_BATCH_CHARS = 3000;
    /** And at most this many text pieces. */
    public static final int MAX_BATCH_ITEMS = 20;
    /** Font size bounds, in pixels of the image. */
    public static final float MIN_FONT_PX = 8f;
    /** Inset of the cover rectangle around the text, as a share of the line height. */
    public static final float PADDING_RATIO = 0.12f;

    private ImageTranslateLogic() {
    }

    // ---------------------------------------------------------------- language

    /**
     * specs#108 需求 2: this chat's image language, else the image-only default, else the reading
     * translation language (#105), else the app language. Empty strings count as unset.
     */
    public static String targetLanguage(String perChat, String imageDefault, String readingTarget, String appLanguage) {
        for (String lang : new String[]{perChat, imageDefault, readingTarget, appLanguage}) {
            if (lang != null && !lang.trim().isEmpty()) {
                return lang.trim();
            }
        }
        return "en";
    }

    /** Language code reduced to what decides "same language": lower case, no region; every zh-* is zh. */
    public static String base(String lang) {
        if (lang == null) {
            return "";
        }
        String l = lang.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = l.indexOf('-');
        return dash > 0 ? l.substring(0, dash) : l;
    }

    /** Top bar 「日本語 → 中文 ▾」; without a known source just 「→ 中文 ▾」. */
    public static String header(String fromName, String toName) {
        String to = toName == null ? "" : toName;
        if (fromName == null || fromName.isEmpty()) {
            return "→ " + to + " ▾";
        }
        return fromName + " → " + to + " ▾";
    }

    // ---------------------------------------------------------------- which blocks

    /**
     * Whether a block is worth translating: it has letters (not only digits, prices, symbols) and
     * its language, when known, differs from the target. Untranslated blocks keep the original.
     */
    public static boolean shouldTranslate(String text, String blockLanguage, String imageLanguage, String targetLanguage) {
        if (text == null || !hasLetters(text)) {
            return false;
        }
        String lang = blockLanguage != null && !blockLanguage.isEmpty() ? blockLanguage : imageLanguage;
        return lang == null || lang.isEmpty() || !base(lang).equals(base(targetLanguage));
    }

    static boolean hasLetters(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Splits the texts to translate into Telegram requests ({@link #MAX_BATCH_ITEMS} pieces,
     * {@link #MAX_BATCH_CHARS} characters each). Returns lists of indexes into {@code texts};
     * a single text longer than the limit gets a batch of its own.
     */
    public static List<List<Integer>> batches(List<String> texts) {
        List<List<Integer>> result = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int chars = 0;
        for (int i = 0; i < texts.size(); i++) {
            int len = texts.get(i) == null ? 0 : texts.get(i).length();
            if (!current.isEmpty() && (current.size() >= MAX_BATCH_ITEMS || chars + len > MAX_BATCH_CHARS)) {
                result.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(i);
            chars += len;
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    // ---------------------------------------------------------------- layout

    /** How tall {@code text} is when wrapped to {@code width} at {@code fontSize} (platform text layout). */
    public interface Measurer {
        float height(String text, float fontSize, float width);
    }

    /**
     * Largest font size (≤ the original line height, ≥ {@link #MIN_FONT_PX}) at which
     * {@code text} wrapped to {@code width} fits {@code height}. Binary search, 0.5 px precision.
     *
     * @param lineHeight height of one original line in pixels (block height / line count).
     */
    public static float fitFontSize(String text, float width, float height, float lineHeight, Measurer measurer) {
        float hi = Math.max(MIN_FONT_PX, lineHeight * 0.9f);
        float lo = MIN_FONT_PX;
        if (measurer.height(text, hi, width) <= height) {
            return hi;
        }
        if (measurer.height(text, lo, width) > height) {
            return lo;
        }
        while (hi - lo > 0.5f) {
            float mid = (lo + hi) / 2f;
            if (measurer.height(text, mid, width) <= height) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Pixel rectangle {left, top, right, bottom} of a normalized box, clamped to the image. */
    public static float[] pixelRect(float x, float y, float w, float h, int imageWidth, int imageHeight) {
        float left = clamp(x, 0f, 1f) * imageWidth;
        float top = clamp(y, 0f, 1f) * imageHeight;
        float right = clamp(x + w, 0f, 1f) * imageWidth;
        float bottom = clamp(y + h, 0f, 1f) * imageHeight;
        return new float[]{left, top, right, bottom};
    }

    /** Cover rectangle: the text box grown by {@link #PADDING_RATIO} of a line, clamped to the image. */
    public static float[] coverRect(float[] rect, float lineHeight, int imageWidth, int imageHeight) {
        float pad = lineHeight * PADDING_RATIO;
        return new float[]{
                Math.max(0f, rect[0] - pad), Math.max(0f, rect[1] - pad),
                Math.min(imageWidth, rect[2] + pad), Math.min(imageHeight, rect[3] + pad)};
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---------------------------------------------------------------- colors

    /** Relative luminance 0..1 of an sRGB color (0..255 channels), WCAG formula. */
    public static double luminance(int r, int g, int b) {
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
    }

    private static double lin(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Text on the sampled background: black on light, white on dark (whichever contrasts more). */
    public static boolean darkText(int r, int g, int b) {
        double l = luminance(r, g, b);
        double withBlack = (l + 0.05) / 0.05;
        double withWhite = 1.05 / (l + 0.05);
        return withBlack >= withWhite;
    }

    /** Alpha (0..255) of the cover: opaque enough to hide the original text, still "semi-transparent". */
    public static final int COVER_ALPHA = 235;

    // ---------------------------------------------------------------- hit test

    /**
     * Index of the block under the normalized point, the smallest box when they overlap, or -1.
     * {@code boxes} holds x, y, width, height per block.
     */
    public static int hitTest(List<float[]> boxes, float px, float py) {
        int best = -1;
        float bestArea = Float.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            float[] b = boxes.get(i);
            if (px >= b[0] && px <= b[0] + b[2] && py >= b[1] && py <= b[1] + b[3]) {
                float area = b[2] * b[3];
                if (area < bestArea) {
                    bestArea = area;
                    best = i;
                }
            }
        }
        return best;
    }

    /** Copy text: the translated blocks in reading order (original where a block was not translated). */
    public static String copyText(List<String> originals, List<String> translations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < originals.size(); i++) {
            String t = i < translations.size() ? translations.get(i) : null;
            String piece = t != null && !t.isEmpty() ? t : originals.get(i);
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(piece);
        }
        return sb.toString();
    }
}

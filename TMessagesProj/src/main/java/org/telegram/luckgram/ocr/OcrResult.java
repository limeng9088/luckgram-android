package org.telegram.luckgram.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * specs#106 (OCR) result, shared with specs#108 (image translation). Same names and meaning as
 * iOS {@code LuckgramOcrResult}:
 *
 * <ul>
 *   <li>{@link #imageWidth} / {@link #imageHeight}: pixels of the recognized image, already rotated
 *       upright (EXIF);</li>
 *   <li>{@link #language}: main language of the image as BCP-47 (e.g. "ja"), or null if unsure;</li>
 *   <li>{@link #source}: {@link #SOURCE_DEVICE} (on-device) or {@link #SOURCE_AI} (Luckgram AI fallback);</li>
 *   <li>{@link #blocks}: paragraphs in reading order (top to bottom, left to right).</li>
 * </ul>
 *
 * Coordinates are normalized to 0..1, origin top-left, x right, y down.
 *
 * <p>Pure Java (java.* only), unit-tested in {@code OcrResultTest}.
 */
public final class OcrResult {

    public static final String SOURCE_DEVICE = "device";
    public static final String SOURCE_AI = "ai";

    /** Axis-aligned rectangle, normalized 0..1, origin top-left. */
    public static final class Box {
        public final float x, y, width, height;

        public Box(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /** From pixel edges of an image of {@code w} x {@code h}; clamped to the image. */
        public static Box fromPixels(float left, float top, float right, float bottom, int w, int h) {
            if (w <= 0 || h <= 0) return new Box(0, 0, 0, 0);
            float l = clamp(left / w), t = clamp(top / h), r = clamp(right / w), b = clamp(bottom / h);
            return new Box(Math.min(l, r), Math.min(t, b), Math.abs(r - l), Math.abs(b - t));
        }

        /**
         * From a rectangle normalized with the origin bottom-left (iOS Vision): flips y so the
         * origin is top-left like everywhere else.
         */
        public static Box fromBottomLeft(float x, float y, float width, float height) {
            return new Box(x, clamp(1f - y - height), width, height);
        }

        public float centerY() {
            return y + height / 2f;
        }

        public boolean contains(float px, float py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }

        /** Smallest box around all of {@code boxes} (null for none). */
        public static Box union(List<Box> boxes) {
            if (boxes == null || boxes.isEmpty()) return null;
            float l = 1, t = 1, r = 0, b = 0;
            for (Box bx : boxes) {
                l = Math.min(l, bx.x);
                t = Math.min(t, bx.y);
                r = Math.max(r, bx.x + bx.width);
                b = Math.max(b, bx.y + bx.height);
            }
            return new Box(l, t, Math.max(0, r - l), Math.max(0, b - t));
        }

        private static float clamp(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
    }

    /** Four corners, normalized, starting top-left and going clockwise (for rotated text). */
    public static final class Quad {
        public final float[] xs;
        public final float[] ys;

        public Quad(float[] xs, float[] ys) {
            this.xs = xs;
            this.ys = ys;
        }
    }

    public static final class Line {
        public final String text;
        public final Box box;
        /** Optional. */
        public final Quad quad;

        public Line(String text, Box box, Quad quad) {
            this.text = text == null ? "" : text;
            this.box = box;
            this.quad = quad;
        }
    }

    /** A paragraph: its lines joined with "\n". */
    public static final class Block {
        public final String text;
        /** BCP-47, or null. */
        public final String language;
        public final Box box;
        /** Optional. */
        public final Quad quad;
        public final List<Line> lines;

        public Block(String text, String language, Box box, Quad quad, List<Line> lines) {
            this.lines = lines == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(lines));
            this.text = text != null ? text : joinLines(this.lines);
            this.language = emptyToNull(language);
            this.box = box;
            this.quad = quad;
        }

        /** A block made of lines; text and box come from the lines. */
        public static Block of(List<Line> lines, String language) {
            ArrayList<Box> boxes = new ArrayList<>();
            for (Line l : lines) if (l.box != null) boxes.add(l.box);
            return new Block(null, language, Box.union(boxes), null, lines);
        }
    }

    public final int imageWidth;
    public final int imageHeight;
    public final String language;
    public final String source;
    public final List<Block> blocks;

    public OcrResult(int imageWidth, int imageHeight, String language, String source, List<Block> blocks) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.language = emptyToNull(language);
        this.source = SOURCE_AI.equals(source) ? SOURCE_AI : SOURCE_DEVICE;
        this.blocks = blocks == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(blocks));
    }

    /** All text, blocks joined with "\n" (what "Copy all" copies). */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (b.text.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(b.text);
        }
        return sb.toString();
    }

    public boolean hasText() {
        return !text().trim().isEmpty();
    }

    /** The block under a tap at normalized ({@code x}, {@code y}), or null. */
    public Block blockAt(float x, float y) {
        for (Block b : blocks) if (b.box != null && b.box.contains(x, y)) return b;
        return null;
    }

    /**
     * Reading order: top to bottom; blocks whose vertical centers are within half a block height
     * of each other count as one row and go left to right.
     */
    public static List<Block> readingOrder(List<Block> in) {
        ArrayList<Block> out = new ArrayList<>(in);
        Collections.sort(out, Comparator.comparingDouble(b -> b.box == null ? 0 : b.box.centerY()));
        ArrayList<Block> sorted = new ArrayList<>();
        int i = 0;
        while (i < out.size()) {
            Block first = out.get(i);
            float tol = first.box == null ? 0 : first.box.height / 2f;
            float cy = first.box == null ? 0 : first.box.centerY();
            int j = i;
            ArrayList<Block> row = new ArrayList<>();
            while (j < out.size() && Math.abs((out.get(j).box == null ? 0 : out.get(j).box.centerY()) - cy) <= tol) {
                row.add(out.get(j));
                j++;
            }
            Collections.sort(row, Comparator.comparingDouble(b -> b.box == null ? 0 : b.box.x));
            sorted.addAll(row);
            i = Math.max(j, i + 1);
        }
        return sorted;
    }

    /**
     * Main language of the image: the language with the most characters over the blocks that have
     * one, or null when none do.
     */
    public static String mainLanguage(List<Block> blocks) {
        java.util.LinkedHashMap<String, Integer> count = new java.util.LinkedHashMap<>();
        for (Block b : blocks) {
            if (b.language == null) continue;
            Integer c = count.get(b.language);
            count.put(b.language, (c == null ? 0 : c) + b.text.replaceAll("\\s", "").length());
        }
        String best = null;
        int bestCount = 0;
        for (java.util.Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static String joinLines(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.text);
        }
        return sb.toString();
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() || "und".equals(s) ? null : s.trim();
    }
}

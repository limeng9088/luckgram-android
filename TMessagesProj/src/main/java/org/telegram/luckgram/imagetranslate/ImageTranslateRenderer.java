package org.telegram.luckgram.imagetranslate;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.List;

/**
 * specs#108 需求 3: draws the translations over the original image — a semi-transparent cover in
 * the sampled background color on each text block, the translation inside it with a size that fits
 * the block. The result is a new bitmap, used for display, save and share alike.
 */
public final class ImageTranslateRenderer {

    /** One recognized block with its translation (null = keep the original). */
    public static final class Block {
        /** Normalized box: x, y, width, height (0..1, origin top-left). */
        public final float x, y, width, height;
        public final int lineCount;
        public final String original;
        public String translation;

        public Block(float x, float y, float width, float height, int lineCount, String original) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.lineCount = Math.max(1, lineCount);
            this.original = original == null ? "" : original;
        }

        float[] box() {
            return new float[]{x, y, width, height};
        }
    }

    private ImageTranslateRenderer() {
    }

    /** A copy of {@code source} with the translated blocks drawn on it. Background thread is fine. */
    public static Bitmap render(Bitmap source, List<Block> blocks) {
        Bitmap out = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(out);
        int w = out.getWidth();
        int h = out.getHeight();
        Paint cover = new Paint(Paint.ANTI_ALIAS_FLAG);
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        for (Block b : blocks) {
            if (b.translation == null || b.translation.isEmpty()) {
                continue;
            }
            float[] r = ImageTranslateLogic.pixelRect(b.x, b.y, b.width, b.height, w, h);
            float boxW = r[2] - r[0];
            float boxH = r[3] - r[1];
            if (boxW < 2 || boxH < 2) {
                continue;
            }
            float lineHeight = boxH / b.lineCount;
            float[] c = ImageTranslateLogic.coverRect(r, lineHeight, w, h);
            int bg = sampleBackground(source, c);
            boolean dark = ImageTranslateLogic.darkText(Color.red(bg), Color.green(bg), Color.blue(bg));
            cover.setColor(Color.argb(ImageTranslateLogic.COVER_ALPHA, Color.red(bg), Color.green(bg), Color.blue(bg)));
            float radius = Math.min(lineHeight * 0.2f, 12f);
            canvas.drawRoundRect(new RectF(c[0], c[1], c[2], c[3]), radius, radius, cover);

            text.setColor(dark ? 0xff111111 : 0xffffffff);
            final int width = Math.max(1, (int) boxW);
            float size = ImageTranslateLogic.fitFontSize(b.translation, width, boxH, lineHeight, (t, s, wd) -> {
                text.setTextSize(s);
                return layout(t, text, (int) wd).getHeight();
            });
            text.setTextSize(size);
            StaticLayout layout = layout(b.translation, text, width);
            canvas.save();
            // vertically centered in the block; clipped to the cover when the minimum size still overflows
            canvas.clipRect(c[0], c[1], c[2], c[3]);
            canvas.translate(r[0], r[1] + Math.max(0, (boxH - layout.getHeight()) / 2f));
            layout.draw(canvas);
            canvas.restore();
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout layout(String t, TextPaint paint, int width) {
        return new StaticLayout(t, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, false);
    }

    /** Average color of the pixels just around the cover rectangle (where the background is). */
    static int sampleBackground(Bitmap bitmap, float[] c) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        long r = 0, g = 0, b = 0;
        int n = 0;
        int left = clamp((int) c[0], 0, w - 1);
        int right = clamp((int) c[2] - 1, 0, w - 1);
        int top = clamp((int) c[1], 0, h - 1);
        int bottom = clamp((int) c[3] - 1, 0, h - 1);
        int steps = 24;
        for (int i = 0; i <= steps; i++) {
            int x = left + (right - left) * i / steps;
            int y = top + (bottom - top) * i / steps;
            int[] px = {bitmap.getPixel(x, top), bitmap.getPixel(x, bottom), bitmap.getPixel(left, y), bitmap.getPixel(right, y)};
            for (int p : px) {
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                n++;
            }
        }
        return n == 0 ? Color.WHITE : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Block index at a normalized point (long-press shows original + translation), or -1. */
    public static int hitTest(List<Block> blocks, float nx, float ny) {
        java.util.ArrayList<float[]> boxes = new java.util.ArrayList<>(blocks.size());
        for (Block b : blocks) boxes.add(b.box());
        return ImageTranslateLogic.hitTest(boxes, nx, ny);
    }
}

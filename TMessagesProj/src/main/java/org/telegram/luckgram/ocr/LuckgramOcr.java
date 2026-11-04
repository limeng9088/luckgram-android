package org.telegram.luckgram.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * specs#106: recognizes the text in an image, on the device first (ML Kit, bundled models). Also
 * used by specs#108 (image translation), which only calls {@link #recognize} / {@link #hasText} /
 * {@link #cached}. The last {@value #CACHE_SIZE} results are kept by key (the file's absolute
 * path), so the viewer's "Text" and "Translate" buttons share one recognition per image.
 *
 * <p>Nothing leaves the device: ML Kit's bundled Chinese (also Latin), Japanese and Korean models
 * run in the app. Callbacks run on the UI thread. Error codes: {@link #ERROR_NO_IMAGE},
 * {@link #ERROR_FAILED} ({@link #ERROR_NOT_IMPLEMENTED} is no longer returned).
 */
public final class LuckgramOcr {

    public static final String FEATURE_KEY = "image_ocr";
    public static final String ERROR_NOT_IMPLEMENTED = "not_implemented";
    public static final String ERROR_NO_IMAGE = "no_image";
    public static final String ERROR_FAILED = "ocr_failed";

    static final int CACHE_SIZE = 8;
    /** Longest side handed to the recognizer; larger photos are scaled down first. */
    static final int MAX_SIDE = 2560;

    public interface Callback<T> {
        void onResult(T result);

        void onError(String code);
    }

    private static final Map<String, OcrResult> cache = new LinkedHashMap<String, OcrResult>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, OcrResult> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private LuckgramOcr() {
    }

    /** Switch {@value #FEATURE_KEY} (default on). */
    public static boolean isEnabled() {
        return org.telegram.luckgram.LuckgramSettings.isEnabled(FEATURE_KEY);
    }

    /** The cached result for {@code key} (a file's absolute path), or null. */
    public static OcrResult cached(String key) {
        if (key == null) return null;
        synchronized (cache) {
            return cache.get(key);
        }
    }

    static void store(String key, OcrResult result) {
        if (key == null || result == null) return;
        synchronized (cache) {
            cache.put(key, result);
        }
    }

    /** Recognizes an image file; cached by its absolute path. */
    public static void recognize(Context context, File file, Callback<OcrResult> callback) {
        if (file == null || !file.exists()) {
            fail(callback, ERROR_NO_IMAGE);
            return;
        }
        String key = file.getAbsolutePath();
        OcrResult hit = cached(key);
        if (hit != null) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(hit));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bitmap = decode(file);
            if (bitmap == null) {
                fail(callback, ERROR_NO_IMAGE);
                return;
            }
            run(bitmap, key, callback);
        });
    }

    /** Recognizes a bitmap; {@code key} null = not cached. */
    public static void recognize(Context context, Bitmap bitmap, String key, Callback<OcrResult> callback) {
        if (bitmap == null) {
            fail(callback, ERROR_NO_IMAGE);
            return;
        }
        OcrResult hit = cached(key);
        if (hit != null) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(hit));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> run(bitmap, key, callback));
    }

    /** Whether the image has text; shares the recognition (and cache) with {@link #recognize}. */
    public static void hasText(Context context, File file, Callback<Boolean> callback) {
        recognize(context, file, new Callback<OcrResult>() {
            @Override
            public void onResult(OcrResult result) {
                callback.onResult(result.hasText());
            }

            @Override
            public void onError(String code) {
                callback.onResult(false);
            }
        });
    }

    // ---------------------------------------------------------------- recognition (background thread)

    private static TextRecognizer chinese, japanese, korean;

    private static synchronized TextRecognizer recognizer(char which) {
        switch (which) {
            case 'j':
                if (japanese == null) japanese = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
                return japanese;
            case 'k':
                if (korean == null) korean = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
                return korean;
            default:
                if (chinese == null) chinese = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                return chinese;
        }
    }

    private static void run(Bitmap bitmap, String key, Callback<OcrResult> callback) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            Text best = Tasks.await(recognizer('z').process(image));
            if (OcrLogic.needsJapanese(best.getText())) {
                Text ja = Tasks.await(recognizer('j').process(image));
                if (OcrLogic.better(best.getText(), ja.getText())) best = ja;
            }
            if (OcrLogic.needsKorean(best.getText())) {
                Text ko = Tasks.await(recognizer('k').process(image));
                if (OcrLogic.better(best.getText(), ko.getText())) best = ko;
            }
            OcrResult result = toResult(best, bitmap.getWidth(), bitmap.getHeight());
            store(key, result);
            AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
        } catch (Throwable e) {
            FileLog.e(e);
            fail(callback, ERROR_FAILED);
        }
    }

    static OcrResult toResult(Text text, int w, int h) {
        ArrayList<OcrResult.Block> blocks = new ArrayList<>();
        for (Text.TextBlock tb : text.getTextBlocks()) {
            ArrayList<OcrResult.Line> lines = new ArrayList<>();
            for (Text.Line tl : tb.getLines()) {
                lines.add(new OcrResult.Line(tl.getText(), box(tl.getBoundingBox(), w, h), quad(tl.getCornerPoints(), w, h)));
            }
            String lang = OcrLogic.blockLanguage(tb.getRecognizedLanguage(), tb.getText());
            OcrResult.Box box = tb.getBoundingBox() != null ? box(tb.getBoundingBox(), w, h) : null;
            OcrResult.Block b = box != null
                ? new OcrResult.Block(tb.getText(), lang, box, quad(tb.getCornerPoints(), w, h), lines)
                : OcrResult.Block.of(lines, lang);
            if (!b.text.trim().isEmpty()) blocks.add(b);
        }
        List<OcrResult.Block> ordered = OcrResult.readingOrder(blocks);
        return new OcrResult(w, h, OcrResult.mainLanguage(ordered), OcrResult.SOURCE_DEVICE, ordered);
    }

    private static OcrResult.Box box(Rect r, int w, int h) {
        if (r == null) return new OcrResult.Box(0, 0, 0, 0);
        return OcrResult.Box.fromPixels(r.left, r.top, r.right, r.bottom, w, h);
    }

    private static OcrResult.Quad quad(Point[] p, int w, int h) {
        if (p == null || p.length != 4 || w <= 0 || h <= 0) return null;
        float[] xs = new float[4], ys = new float[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = Math.max(0f, Math.min(1f, p[i].x / (float) w));
            ys[i] = Math.max(0f, Math.min(1f, p[i].y / (float) h));
        }
        return new OcrResult.Quad(xs, ys);
    }

    /** Decodes upright (EXIF) and at most {@link #MAX_SIDE} on the longer side. */
    static Bitmap decode(File file) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > MAX_SIDE * 2) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath(), o);
            if (b == null) return null;
            int rotation = exifRotation(file);
            float scale = Math.min(1f, MAX_SIDE / (float) Math.max(b.getWidth(), b.getHeight()));
            if (rotation == 0 && scale >= 1f) return b;
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rotation);
            Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (out != b) b.recycle();
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static int exifRotation(File file) {
        try {
            androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(file.getAbsolutePath());
            switch (exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void fail(Callback<?> callback, String code) {
        AndroidUtilities.runOnUIThread(() -> callback.onError(code));
    }
}

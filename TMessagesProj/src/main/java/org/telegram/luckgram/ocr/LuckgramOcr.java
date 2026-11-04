package org.telegram.luckgram.ocr;

import android.content.Context;
import android.graphics.Bitmap;

import org.telegram.messenger.AndroidUtilities;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * specs#106: recognizes the text in an image, on the device first (ML Kit, bundled models). Also
 * used by specs#108 (image translation), which only calls {@link #recognize} / {@link #hasText} /
 * {@link #cached}. The last {@value #CACHE_SIZE} results are kept by key (the file's absolute
 * path), so the viewer's "Text" and "Translate" buttons share one recognition per image.
 *
 * <p>Callbacks run on the UI thread. Error codes: {@link #ERROR_NOT_IMPLEMENTED},
 * {@link #ERROR_NO_IMAGE}, {@link #ERROR_FAILED}.
 */
public final class LuckgramOcr {

    public static final String FEATURE_KEY = "image_ocr";
    public static final String ERROR_NOT_IMPLEMENTED = "not_implemented";
    public static final String ERROR_NO_IMAGE = "no_image";
    public static final String ERROR_FAILED = "ocr_failed";

    static final int CACHE_SIZE = 8;

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
        fail(callback, ERROR_NOT_IMPLEMENTED); // implementation: feat/106-image-ocr
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
        fail(callback, ERROR_NOT_IMPLEMENTED); // implementation: feat/106-image-ocr
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

    private static void fail(Callback<?> callback, String code) {
        AndroidUtilities.runOnUIThread(() -> callback.onError(code));
    }
}

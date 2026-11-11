package org.telegram.luckgram.imagetranslate;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.widget.Toast;

import org.telegram.luckgram.ocr.LuckgramImageActions;
import org.telegram.luckgram.ocr.LuckgramOcr;
import org.telegram.luckgram.ocr.OcrResult;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.util.ArrayList;

/**
 * specs#108 entry: 「翻译图片」 in the photo menus and the gallery preview, 「译」 on the viewer
 * (through #106's {@link LuckgramImageActions}). Recognition is #106's {@link LuckgramOcr}
 * (on-device, cached per file); this only translates and draws.
 */
public final class ImageTranslateEntry {

    public static final String ACTION_ID = "image_translate";
    /** Images are decoded at most this large (the OCR boxes are normalized, so any size works). */
    private static final int MAX_SIDE = 2560;
    /** One translation at a time: repeated taps while recognizing or while the page is open do nothing. */
    private static boolean busy;

    private ImageTranslateEntry() {
    }

    public static LuckgramImageActions.Action action() {
        return new LuckgramImageActions.Action(ACTION_ID, "翻译图片", "Translate image", R.drawable.msg_translate, "译", "Tr",
                LuckgramImageActions.PLACE_MENU | LuckgramImageActions.PLACE_VIEWER_BUTTON | LuckgramImageActions.PLACE_ATTACH_PREVIEW,
                message -> ImageTranslateSettings.isEnabled(),
                ImageTranslateEntry::run);
    }

    public static void run(BaseFragment host, File image, MessageObject message) {
        Activity activity = host == null ? null : host.getParentActivity();
        if (activity == null || image == null) {
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        long dialogId = message != null ? message.getDialogId() : 0;
        final ImageTranslateActivity.SendTranslated sender = sender(host);
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        progress.show();
        LuckgramOcr.recognize(activity, image, new LuckgramOcr.Callback<OcrResult>() {
            @Override
            public void onResult(OcrResult result) {
                Utilities.globalQueue.postRunnable(() -> {
                    Bitmap bitmap = decodeUpright(image);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!progress.isShowing()) { // cancelled
                            busy = false;
                            return;
                        }
                        progress.dismiss();
                        if (bitmap == null || !result.hasText()) {
                            busy = false;
                            toast(activity, ImageTranslateSettings.noTextText());
                            return;
                        }
                        open(host, new ImageTranslateActivity(bitmap, blocks(result), result.language, dialogId, sender));
                    });
                });
            }

            @Override
            public void onError(String code) {
                busy = false;
                progress.dismiss();
                toast(activity, ImageTranslateSettings.noTextText());
            }
        });
    }

    /**
     * The full-screen viewer and the gallery preview (PhotoViewer) are a window above the fragment
     * stack: a pushed page would open underneath them. There the page opens in its own window
     * (a sheet) on top; elsewhere it is pushed as usual.
     */
    private static void open(BaseFragment host, ImageTranslateActivity page) {
        page.setOnClosed(() -> busy = false);
        if (org.telegram.ui.PhotoViewer.hasInstance() && org.telegram.ui.PhotoViewer.getInstance().isVisible()) {
            BaseFragment.BottomSheetParams params = new BaseFragment.BottomSheetParams();
            params.occupyNavigationBar = true;
            params.onDismiss = () -> busy = false;
            if (host.showAsSheet(page, params) == null) {
                busy = false;
            }
        } else if (!host.presentFragment(page)) {
            busy = false;
        }
    }

    /** Gallery preview (needs 4): the preview's replacer swaps in the translated image; null elsewhere (no send button). */
    private static ImageTranslateActivity.SendTranslated sender(BaseFragment host) {
        LuckgramImageActions.ImageReplacer replacer = LuckgramImageActions.attachReplacer();
        return replacer == null ? null : replacer::replace;
    }

    static ArrayList<ImageTranslateRenderer.Block> blocks(OcrResult result) {
        ArrayList<ImageTranslateRenderer.Block> list = new ArrayList<>();
        for (OcrResult.Block b : result.blocks) {
            if (b.box == null || b.text == null || b.text.trim().isEmpty()) continue;
            list.add(new ImageTranslateRenderer.Block(b.box.x, b.box.y, b.box.width, b.box.height, Math.max(1, b.lines.size()), b.text));
        }
        return list;
    }

    /** Decodes {@code file} rotated upright (EXIF), like the OCR did, at most {@link #MAX_SIDE} px. */
    static Bitmap decodeUpright(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap == null) return null;
            int rotation = 0;
            boolean flip = false;
            try {
                int o = new ExifInterface(file.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (o) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: flip = true; break;
                }
            } catch (Exception ignore) {
            }
            if (rotation == 0 && !flip) return bitmap;
            Matrix m = new Matrix();
            if (rotation != 0) m.postRotate(rotation);
            if (flip) m.postScale(-1, 1);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}

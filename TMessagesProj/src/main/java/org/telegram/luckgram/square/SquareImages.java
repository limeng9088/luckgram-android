package org.telegram.luckgram.square;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.telegram.messenger.ImageLoader;

import java.io.ByteArrayOutputStream;

/**
 * Prepares a picked photo for Square (spec 26 item 3): decoded with the EXIF orientation applied,
 * scaled so the long edge is at most {@link SquareLogic#IMAGE_MAX_EDGE}, and re-encoded as a plain
 * JPEG. {@link Bitmap#compress} writes no metadata, so EXIF (including GPS) never leaves the
 * device; the server strips it once more.
 */
public final class SquareImages {

    private SquareImages() {
    }

    public static final String CONTENT_TYPE = "image/jpeg";
    public static final int JPEG_QUALITY = 85;

    /** A photo ready to upload. */
    public static final class Prepared {
        public final byte[] jpeg;
        public final int width;
        public final int height;
        /** Small preview for the compose page. */
        public final Bitmap thumbnail;

        Prepared(byte[] jpeg, int width, int height, Bitmap thumbnail) {
            this.jpeg = jpeg;
            this.width = width;
            this.height = height;
            this.thumbnail = thumbnail;
        }
    }

    /** Runs on a worker thread. Returns null when the file cannot be decoded. */
    public static Prepared prepare(Uri uri) {
        Bitmap decoded = ImageLoader.loadBitmap(null, uri, SquareLogic.IMAGE_MAX_EDGE, SquareLogic.IMAGE_MAX_EDGE, true);
        if (decoded == null) {
            return null;
        }
        Bitmap scaled = decoded;
        int[] size = SquareLogic.scaledSize(decoded.getWidth(), decoded.getHeight());
        if (size[0] != decoded.getWidth() || size[1] != decoded.getHeight()) {
            scaled = Bitmap.createScaledBitmap(decoded, size[0], size[1], true);
            if (scaled != decoded) {
                decoded.recycle();
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
            scaled.recycle();
            return null;
        }
        int[] thumbSize = thumbnailSize(scaled.getWidth(), scaled.getHeight(), 256);
        Bitmap thumb = Bitmap.createScaledBitmap(scaled, thumbSize[0], thumbSize[1], true);
        if (thumb != scaled) {
            scaled.recycle();
        }
        return new Prepared(out.toByteArray(), size[0], size[1], thumb);
    }

    static int[] thumbnailSize(int width, int height, int maxEdge) {
        int edge = Math.max(width, height);
        if (edge <= maxEdge) {
            return new int[]{width, height};
        }
        double k = (double) maxEdge / edge;
        return new int[]{Math.max(1, (int) Math.round(width * k)), Math.max(1, (int) Math.round(height * k))};
    }

    /** Decodes a JPEG we produced (for the grid preview of a just-uploaded image). */
    public static Bitmap decode(byte[] jpeg) {
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }
}

package org.telegram.luckgram.imagetranslate;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * The image of the translation page: fit to the view, pinch to zoom, drag to pan, double tap to
 * reset; long press reports the normalized point under the finger (for "original + translation").
 */
@SuppressLint("ViewConstructor")
public class ImageTranslateView extends View {

    public interface LongPress {
        void onLongPress(float nx, float ny);
    }

    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private float scale = 1f;
    private float tx, ty;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private LongPress longPress;

    public ImageTranslateView(Context context) {
        super(context);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float next = Math.max(1f, Math.min(6f, scale * d.getScaleFactor()));
                float f = next / scale;
                tx = d.getFocusX() - (d.getFocusX() - tx) * f;
                ty = d.getFocusY() - (d.getFocusY() - ty) * f;
                scale = next;
                clampPan();
                invalidate();
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale <= 1f) return false;
                tx -= dx;
                ty -= dy;
                clampPan();
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                scale = 1f;
                tx = ty = 0;
                invalidate();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (longPress == null || bitmap == null) return;
                float[] p = {e.getX(), e.getY()};
                inverse.mapPoints(p);
                float nx = p[0] / bitmap.getWidth();
                float ny = p[1] / bitmap.getHeight();
                if (nx >= 0 && nx <= 1 && ny >= 0 && ny <= 1) {
                    longPress.onLongPress(nx, ny);
                }
            }
        });
    }

    public void setOnBlockLongPress(LongPress l) {
        longPress = l;
    }

    /** Swaps the shown bitmap (original / translation) keeping zoom and position: both have the same size. */
    public void setBitmap(Bitmap b) {
        bitmap = b;
        invalidate();
    }

    private void clampPan() {
        float maxX = getWidth() * (scale - 1f);
        float maxY = getHeight() * (scale - 1f);
        tx = Math.min(0, Math.max(-maxX, tx));
        ty = Math.min(0, Math.max(-maxY, ty));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (event.getPointerCount() > 1 || scale > 1f) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float fit = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float dw = bitmap.getWidth() * fit;
        float dh = bitmap.getHeight() * fit;
        matrix.reset();
        matrix.setRectToRect(new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                new RectF((getWidth() - dw) / 2f, (getHeight() - dh) / 2f, (getWidth() + dw) / 2f, (getHeight() + dh) / 2f), Matrix.ScaleToFit.FILL);
        matrix.postScale(scale, scale, 0, 0);
        matrix.postTranslate(tx, ty);
        matrix.invert(inverse);
        canvas.drawBitmap(bitmap, matrix, paint);
    }
}

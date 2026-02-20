package com.example.takess;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Transparent overlay that draws a crop rectangle with draggable edges.
 * The user drags the top/bottom/left/right edges to define the crop region.
 * The dimmed area outside the crop rect is shaded.
 */
public class CropOverlayView extends View {

    public interface OnCropChangeListener {
        void onCropChanged(float leftPct, float topPct, float rightPct, float bottomPct);
    }

    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint handlePaint = new Paint();

    // Crop rect as percentages 0..1
    private float cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f;

    // The actual image rect inside the view (accounting for fitCenter padding)
    private final RectF imageRect = new RectF();

    private static final float HANDLE_TOUCH_RADIUS_DP = 32f;
    private static final float HANDLE_VISUAL_SIZE_DP = 16f;
    private static final float MIN_CROP_PCT = 0.05f; // minimum 5% in each dimension

    private int draggingEdge = EDGE_NONE;
    private static final int EDGE_NONE = 0;
    private static final int EDGE_TOP = 1;
    private static final int EDGE_BOTTOM = 2;
    private static final int EDGE_LEFT = 3;
    private static final int EDGE_RIGHT = 4;

    private OnCropChangeListener listener;
    private boolean cropMode = false;

    public CropOverlayView(Context context) { this(context, null); }
    public CropOverlayView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public CropOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        dimPaint.setColor(Color.argb(140, 0, 0, 0));
        dimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(2));
        borderPaint.setAntiAlias(true);

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setAntiAlias(true);
    }

    public void setCropMode(boolean enabled) {
        this.cropMode = enabled;
        if (enabled) {
            cropLeft = 0f; cropTop = 0f; cropRight = 1f; cropBottom = 1f;
        }
        invalidate();
    }

    public boolean isCropMode() { return cropMode; }

    public void setOnCropChangeListener(OnCropChangeListener l) { this.listener = l; }

    public void setImageRect(RectF rect) {
        imageRect.set(rect);
        invalidate();
    }

    /** Returns crop percentages as [left, top, right, bottom] in range 0..1 */
    public float[] getCropPercents() {
        return new float[]{ cropLeft, cropTop, cropRight, cropBottom };
    }

    public void resetCrop() {
        cropLeft = 0f; cropTop = 0f; cropRight = 1f; cropBottom = 1f;
        invalidate();
        notifyListener();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!cropMode || imageRect.isEmpty()) return;

        float iL = imageRect.left, iT = imageRect.top;
        float iR = imageRect.right, iB = imageRect.bottom;
        float iW = iR - iL, iH = iB - iT;

        // Crop rect in view coordinates
        float cL = iL + cropLeft * iW;
        float cT = iT + cropTop * iH;
        float cR = iL + cropRight * iW;
        float cB = iT + cropBottom * iH;

        // Draw dim outside crop
        canvas.drawRect(iL, iT, iR, cT, dimPaint);        // top strip
        canvas.drawRect(iL, cB, iR, iB, dimPaint);         // bottom strip
        canvas.drawRect(iL, cT, cL, cB, dimPaint);         // left strip
        canvas.drawRect(cR, cT, iR, cB, dimPaint);         // right strip

        // Draw crop border
        canvas.drawRect(cL, cT, cR, cB, borderPaint);

        // Draw edge handles (small circles at midpoints)
        float hs = dp(HANDLE_VISUAL_SIZE_DP) / 2f;
        float midX = (cL + cR) / 2f, midY = (cT + cB) / 2f;

        canvas.drawCircle(midX, cT, hs, handlePaint);   // top
        canvas.drawCircle(midX, cB, hs, handlePaint);   // bottom
        canvas.drawCircle(cL, midY, hs, handlePaint);   // left
        canvas.drawCircle(cR, midY, hs, handlePaint);   // right
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!cropMode || imageRect.isEmpty()) return false;

        float x = event.getX(), y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                draggingEdge = hitTestEdge(x, y);
                return draggingEdge != EDGE_NONE;

            case MotionEvent.ACTION_MOVE:
                if (draggingEdge == EDGE_NONE) return false;
                updateCropFromDrag(draggingEdge, x, y);
                invalidate();
                notifyListener();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingEdge != EDGE_NONE) {
                    performClick();
                }
                draggingEdge = EDGE_NONE;
                return true;
        }
        return false;
    }

    private int hitTestEdge(float x, float y) {
        float iL = imageRect.left, iT = imageRect.top;
        float iW = imageRect.width(), iH = imageRect.height();

        float cL = iL + cropLeft * iW;
        float cT = iT + cropTop * iH;
        float cR = iL + cropRight * iW;
        float cB = iT + cropBottom * iH;
        float midX = (cL + cR) / 2f, midY = (cT + cB) / 2f;

        float r = dp(HANDLE_TOUCH_RADIUS_DP);

        if (dist(x, y, midX, cT) < r) return EDGE_TOP;
        if (dist(x, y, midX, cB) < r) return EDGE_BOTTOM;
        if (dist(x, y, cL, midY) < r) return EDGE_LEFT;
        if (dist(x, y, cR, midY) < r) return EDGE_RIGHT;

        return EDGE_NONE;
    }

    private void updateCropFromDrag(int edge, float x, float y) {
        float iL = imageRect.left, iT = imageRect.top;
        float iW = imageRect.width(), iH = imageRect.height();
        if (iW <= 0 || iH <= 0) return;

        float pctX = Math.max(0, Math.min(1, (x - iL) / iW));
        float pctY = Math.max(0, Math.min(1, (y - iT) / iH));

        switch (edge) {
            case EDGE_TOP:
                cropTop = Math.min(pctY, cropBottom - MIN_CROP_PCT);
                break;
            case EDGE_BOTTOM:
                cropBottom = Math.max(pctY, cropTop + MIN_CROP_PCT);
                break;
            case EDGE_LEFT:
                cropLeft = Math.min(pctX, cropRight - MIN_CROP_PCT);
                break;
            case EDGE_RIGHT:
                cropRight = Math.max(pctX, cropLeft + MIN_CROP_PCT);
                break;
        }
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onCropChanged(cropLeft, cropTop, cropRight, cropBottom);
        }
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}

package com.example.takess;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.File;

/**
 * Small floating preview pinned to top-left.
 * Features:
 *  • 3-second auto-save countdown (paused on interaction)
 *  • Inline crop by dragging edges on the screenshot thumbnail
 *  • Save / Discard
 */
public class ScreenshotPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATH = "image_path";

    private ImageView ivPreview;
    private CropOverlayView cropOverlay;
    private TextView tvCountdown;
    private TextView tvCropInfo;
    private MaterialButton btnCrop;

    private String imagePath;
    private Bitmap currentBitmap;
    private CountDownTimer autoSaveTimer;
    private boolean userInteracted = false;
    private boolean inCropMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screenshot_preview);

        // Tapping outside the card dismisses (auto-save)
        findViewById(R.id.preview_root).setOnClickListener(v -> {
            if (!inCropMode) {
                cancelTimer();
                saveAndFinish();
            }
        });

        ivPreview = findViewById(R.id.iv_preview);
        cropOverlay = findViewById(R.id.crop_overlay);
        tvCountdown = findViewById(R.id.tv_countdown);
        tvCropInfo = findViewById(R.id.tv_crop_info);
        MaterialButton btnDiscard = findViewById(R.id.btn_discard);
        btnCrop = findViewById(R.id.btn_crop);
        MaterialButton btnSave = findViewById(R.id.btn_save);

        imagePath = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        if (imagePath == null) {
            Toast.makeText(this, "No image to preview", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        currentBitmap = BitmapFactory.decodeFile(imagePath);
        if (currentBitmap == null) {
            Toast.makeText(this, "Failed to load screenshot", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ivPreview.setImageBitmap(currentBitmap);

        // Once the ImageView has laid out, compute where the image actually sits
        ivPreview.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        ivPreview.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        updateImageRect();
                    }
                });

        // Crop overlay listener — show live info
        cropOverlay.setOnCropChangeListener((l, t, r, b) -> {
            int w = currentBitmap.getWidth();
            int h = currentBitmap.getHeight();
            int cw = (int) ((r - l) * w);
            int ch = (int) ((b - t) * h);
            tvCropInfo.setText(cw + " × " + ch + " px");
        });

        // ── Button listeners ──
        btnSave.setOnClickListener(v -> {
            cancelTimer();
            if (inCropMode) applyCrop();
            saveAndFinish();
        });

        btnDiscard.setOnClickListener(v -> {
            cancelTimer();
            discardAndFinish();
        });

        btnCrop.setOnClickListener(v -> {
            pauseTimerOnInteraction();
            toggleCropMode();
        });

        startAutoSaveTimer();
    }

    // ──────────────────────────────────────────────
    //  Compute the actual image rect inside the ImageView (fitCenter)
    // ──────────────────────────────────────────────

    private void updateImageRect() {
        if (currentBitmap == null || ivPreview.getDrawable() == null) return;

        float[] values = new float[9];
        Matrix imageMatrix = ivPreview.getImageMatrix();
        imageMatrix.getValues(values);

        float scaleX = values[Matrix.MSCALE_X];
        float scaleY = values[Matrix.MSCALE_Y];
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];

        float imgW = currentBitmap.getWidth() * scaleX;
        float imgH = currentBitmap.getHeight() * scaleY;

        // Account for ImageView padding
        float padL = ivPreview.getPaddingLeft();
        float padT = ivPreview.getPaddingTop();

        RectF rect = new RectF(
                padL + transX,
                padT + transY,
                padL + transX + imgW,
                padT + transY + imgH);
        cropOverlay.setImageRect(rect);
    }

    // ──────────────────────────────────────────────
    //  Crop mode toggle
    // ──────────────────────────────────────────────

    private void toggleCropMode() {
        inCropMode = !inCropMode;
        cropOverlay.setCropMode(inCropMode);

        if (inCropMode) {
            btnCrop.setText("Apply");
            tvCropInfo.setVisibility(View.VISIBLE);
            int w = currentBitmap.getWidth(), h = currentBitmap.getHeight();
            tvCropInfo.setText(w + " × " + h + " px");
        } else {
            // "Apply" was pressed — apply the crop
            applyCrop();
            btnCrop.setText(getString(R.string.crop));
            tvCropInfo.setVisibility(View.GONE);
        }
    }

    private void applyCrop() {
        if (!cropOverlay.isCropMode()) return;

        float[] pct = cropOverlay.getCropPercents();
        int origW = currentBitmap.getWidth();
        int origH = currentBitmap.getHeight();

        int left = (int) (pct[0] * origW);
        int top = (int) (pct[1] * origH);
        int right = (int) (pct[2] * origW);
        int bottom = (int) (pct[3] * origH);

        int cropW = right - left;
        int cropH = bottom - top;
        if (cropW <= 0 || cropH <= 0) return;

        // Clamp
        if (left + cropW > origW) cropW = origW - left;
        if (top + cropH > origH) cropH = origH - top;
        if (cropW <= 0 || cropH <= 0) return;

        Bitmap cropped = Bitmap.createBitmap(currentBitmap, left, top, cropW, cropH);
        currentBitmap.recycle();
        currentBitmap = cropped;
        ivPreview.setImageBitmap(currentBitmap);

        cropOverlay.setCropMode(false);
        inCropMode = false;
        btnCrop.setText(getString(R.string.crop));
        tvCropInfo.setVisibility(View.GONE);

        // Recompute image rect after bitmap change
        ivPreview.post(this::updateImageRect);

        Toast.makeText(this, "Cropped to " + cropW + "×" + cropH, Toast.LENGTH_SHORT).show();
    }

    // ──────────────────────────────────────────────
    //  Auto-save timer
    // ──────────────────────────────────────────────

    private void startAutoSaveTimer() {
        autoSaveTimer = new CountDownTimer(3000, 1000) {
            int secondsLeft = 3;

            @Override
            public void onTick(long millisUntilFinished) {
                secondsLeft = (int) Math.ceil(millisUntilFinished / 1000.0);
                tvCountdown.setText("Auto-saving in " + secondsLeft + "s…");
            }

            @Override
            public void onFinish() {
                tvCountdown.setText("Saving…");
                saveAndFinish();
            }
        };
        autoSaveTimer.start();
    }

    private void pauseTimerOnInteraction() {
        if (!userInteracted) {
            userInteracted = true;
            cancelTimer();
            tvCountdown.setText("Tap Save when ready");
        }
    }

    private void cancelTimer() {
        if (autoSaveTimer != null) {
            autoSaveTimer.cancel();
            autoSaveTimer = null;
        }
    }

    // ──────────────────────────────────────────────
    //  Save / Discard
    // ──────────────────────────────────────────────

    private void saveAndFinish() {
        if (currentBitmap == null || currentBitmap.isRecycled()) {
            finish();
            return;
        }

        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(imagePath);
            currentBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        Intent intent = new Intent(this, ScreenshotService.class);
        intent.setAction(ScreenshotService.ACTION_SAVE_TEMP);
        intent.putExtra("tempPath", imagePath);
        startService(intent);

        finish();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void discardAndFinish() {
        try {
            File f = new File(imagePath);
            if (f.exists()) f.delete();
        } catch (Exception ignored) { }
        Toast.makeText(this, "Screenshot discarded", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        cancelTimer();
        super.onDestroy();
    }
}

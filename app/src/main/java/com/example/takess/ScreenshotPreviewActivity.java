package com.example.takess;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;

/**
 * Shows a screenshot preview after capture. Features:
 *  • 3-second auto-save countdown (paused if user interacts)
 *  • "Crop" — seekbar-based crop dialog with live preview
 *  • "Save" / "Discard" buttons
 */
public class ScreenshotPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATH = "image_path";

    private ImageView ivPreview;
    private TextView tvCountdown;

    private String imagePath;
    private Bitmap currentBitmap;
    private CountDownTimer autoSaveTimer;
    private boolean userInteracted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_screenshot_preview);

        // Handle window insets so bottom bar isn't hidden behind nav bar
        View bottomBar = findViewById(R.id.bottom_bar);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.preview_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            bottomBar.setPadding(0, 0, 0, systemBars.bottom);
            return insets;
        });

        ivPreview = findViewById(R.id.iv_preview);
        tvCountdown = findViewById(R.id.tv_countdown);
        MaterialButton btnDiscard = findViewById(R.id.btn_discard);
        MaterialButton btnCrop = findViewById(R.id.btn_crop);
        MaterialButton btnSave = findViewById(R.id.btn_save);

        imagePath = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        if (imagePath == null) {
            Toast.makeText(this, "No image to preview", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Load bitmap from temp file
        currentBitmap = BitmapFactory.decodeFile(imagePath);
        if (currentBitmap == null) {
            Toast.makeText(this, "Failed to load screenshot", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ivPreview.setImageBitmap(currentBitmap);

        // ── Button listeners ──

        btnSave.setOnClickListener(v -> {
            cancelTimer();
            saveAndFinish();
        });

        btnDiscard.setOnClickListener(v -> {
            cancelTimer();
            discardAndFinish();
        });

        btnCrop.setOnClickListener(v -> {
            pauseTimerOnInteraction();
            showCropDialog();
        });

        // Pause timer on any touch in the preview area
        findViewById(R.id.preview_root).setOnClickListener(v -> pauseTimerOnInteraction());

        // ── Start 3-second auto-save countdown ──
        startAutoSaveTimer();
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

        // Write the (possibly cropped) bitmap back to the temp file,
        // then tell ScreenshotService to save it properly
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(imagePath);
            currentBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        // Tell the service to persist the temp file to the user's chosen storage
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

    // ──────────────────────────────────────────────
    //  Crop dialog — with live preview and clamped seekbars
    // ──────────────────────────────────────────────

    private void showCropDialog() {
        if (currentBitmap == null || currentBitmap.isRecycled()) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_crop, null);
        ImageView ivCropPreview = dialogView.findViewById(R.id.iv_crop_preview);
        TextView tvCropInfo = dialogView.findViewById(R.id.tv_crop_info);
        SeekBar seekTop = dialogView.findViewById(R.id.seekbar_top);
        SeekBar seekBottom = dialogView.findViewById(R.id.seekbar_bottom);
        SeekBar seekLeft = dialogView.findViewById(R.id.seekbar_left);
        SeekBar seekRight = dialogView.findViewById(R.id.seekbar_right);

        final int origW = currentBitmap.getWidth();
        final int origH = currentBitmap.getHeight();
        final int MIN_GAP = 5;

        seekTop.setProgress(0);
        seekBottom.setProgress(100);
        seekLeft.setProgress(0);
        seekRight.setProgress(100);

        ivCropPreview.setImageBitmap(currentBitmap);
        tvCropInfo.setText("Size: " + origW + " × " + origH + " px");

        SeekBar.OnSeekBarChangeListener cropListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;

                if (seekBar == seekTop && seekTop.getProgress() >= seekBottom.getProgress() - MIN_GAP) {
                    seekTop.setProgress(Math.max(0, seekBottom.getProgress() - MIN_GAP));
                }
                if (seekBar == seekBottom && seekBottom.getProgress() <= seekTop.getProgress() + MIN_GAP) {
                    seekBottom.setProgress(Math.min(100, seekTop.getProgress() + MIN_GAP));
                }
                if (seekBar == seekLeft && seekLeft.getProgress() >= seekRight.getProgress() - MIN_GAP) {
                    seekLeft.setProgress(Math.max(0, seekRight.getProgress() - MIN_GAP));
                }
                if (seekBar == seekRight && seekRight.getProgress() <= seekLeft.getProgress() + MIN_GAP) {
                    seekRight.setProgress(Math.min(100, seekLeft.getProgress() + MIN_GAP));
                }

                updateCropPreview(ivCropPreview, tvCropInfo,
                        seekTop.getProgress(), seekBottom.getProgress(),
                        seekLeft.getProgress(), seekRight.getProgress(),
                        origW, origH);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };

        seekTop.setOnSeekBarChangeListener(cropListener);
        seekBottom.setOnSeekBarChangeListener(cropListener);
        seekLeft.setOnSeekBarChangeListener(cropListener);
        seekRight.setOnSeekBarChangeListener(cropListener);

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Apply", (dialog, which) -> {
                    int top = (int) (origH * seekTop.getProgress() / 100f);
                    int bottom = (int) (origH * seekBottom.getProgress() / 100f);
                    int left = (int) (origW * seekLeft.getProgress() / 100f);
                    int right = (int) (origW * seekRight.getProgress() / 100f);

                    if (left >= right) right = Math.min(left + 10, origW);
                    if (top >= bottom) bottom = Math.min(top + 10, origH);
                    if (right > origW) right = origW;
                    if (bottom > origH) bottom = origH;

                    int cropW = right - left;
                    int cropH = bottom - top;
                    if (cropW <= 0 || cropH <= 0) {
                        Toast.makeText(this, "Invalid crop region", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Bitmap cropped = Bitmap.createBitmap(currentBitmap, left, top, cropW, cropH);
                    currentBitmap.recycle();
                    currentBitmap = cropped;
                    ivPreview.setImageBitmap(currentBitmap);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateCropPreview(ImageView preview, TextView info,
                                   int topPct, int bottomPct, int leftPct, int rightPct,
                                   int origW, int origH) {
        try {
            int top = (int) (origH * topPct / 100f);
            int bottom = (int) (origH * bottomPct / 100f);
            int left = (int) (origW * leftPct / 100f);
            int right = (int) (origW * rightPct / 100f);

            int cropW = right - left;
            int cropH = bottom - top;
            if (cropW <= 0 || cropH <= 0) return;
            if (left + cropW > origW || top + cropH > origH) return;

            Bitmap cropped = Bitmap.createBitmap(currentBitmap, left, top, cropW, cropH);
            preview.setImageBitmap(cropped);
            info.setText("Size: " + cropW + " × " + cropH + " px  (from " + origW + "×" + origH + ")");
        } catch (Exception e) {
            // Ignore transient errors during dragging
        }
    }

    @Override
    protected void onDestroy() {
        cancelTimer();
        super.onDestroy();
    }
}


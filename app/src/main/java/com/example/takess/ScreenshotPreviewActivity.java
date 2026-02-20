package com.example.takess;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
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
 *  • "Scroll Capture" — tells ScreenshotService to do a long screenshot
 *  • "Crop" — seekbar-based crop dialog with live preview
 *  • "Resize" — resize with aspect ratio lock and presets
 *  • "Save" / "Discard" buttons
 */
public class ScreenshotPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATH = "image_path";

    private ImageView ivPreview;
    private TextView tvCountdown;
    private MaterialButton btnDiscard, btnScrollCapture, btnCrop, btnResize, btnSave;

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
        btnDiscard = findViewById(R.id.btn_discard);
        btnScrollCapture = findViewById(R.id.btn_scroll_capture);
        btnCrop = findViewById(R.id.btn_crop);
        btnResize = findViewById(R.id.btn_resize);
        btnSave = findViewById(R.id.btn_save);

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

        btnResize.setOnClickListener(v -> {
            pauseTimerOnInteraction();
            showResizeDialog();
        });

        btnScrollCapture.setOnClickListener(v -> {
            pauseTimerOnInteraction();
            startScrollCapture();
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

        // Write the (possibly cropped/resized) bitmap back to the temp file,
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

    private void discardAndFinish() {
        // Delete the temp file
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
        final int MIN_GAP = 5; // minimum gap between opposite seekbars (%)

        seekTop.setProgress(0);
        seekBottom.setProgress(100);
        seekLeft.setProgress(0);
        seekRight.setProgress(100);

        // Show initial preview
        ivCropPreview.setImageBitmap(currentBitmap);
        tvCropInfo.setText("Size: " + origW + " × " + origH + " px");

        // Shared listener for live preview updates with clamping
        SeekBar.OnSeekBarChangeListener cropListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;

                // Clamp: top must be < bottom, left must be < right
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

                    // Final validation
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

    // ──────────────────────────────────────────────
    //  Resize dialog — with aspect ratio lock and presets
    // ──────────────────────────────────────────────

    private void showResizeDialog() {
        if (currentBitmap == null || currentBitmap.isRecycled()) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_resize, null);
        ImageView ivResizePreview = dialogView.findViewById(R.id.iv_resize_preview);
        TextView tvCurrentSize = dialogView.findViewById(R.id.tv_current_size);
        EditText etWidth = dialogView.findViewById(R.id.et_width);
        EditText etHeight = dialogView.findViewById(R.id.et_height);
        CheckBox cbLockAspect = dialogView.findViewById(R.id.cb_lock_aspect);
        MaterialButton btn25 = dialogView.findViewById(R.id.btn_25);
        MaterialButton btn50 = dialogView.findViewById(R.id.btn_50);
        MaterialButton btn75 = dialogView.findViewById(R.id.btn_75);

        final int origW = currentBitmap.getWidth();
        final int origH = currentBitmap.getHeight();
        final float aspectRatio = (float) origW / origH;
        final boolean[] ignoreChange = {false};

        ivResizePreview.setImageBitmap(currentBitmap);
        tvCurrentSize.setText("Current: " + origW + " × " + origH + " px");
        etWidth.setText(String.valueOf(origW));
        etHeight.setText(String.valueOf(origH));

        // Width change → auto-update height if locked
        etWidth.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                if (ignoreChange[0]) return;
                if (cbLockAspect.isChecked() && s.length() > 0) {
                    try {
                        int w = Integer.parseInt(s.toString());
                        int h = Math.max(1, Math.round(w / aspectRatio));
                        ignoreChange[0] = true;
                        etHeight.setText(String.valueOf(h));
                        ignoreChange[0] = false;
                    } catch (NumberFormatException ignored) { }
                }
            }
        });

        // Height change → auto-update width if locked
        etHeight.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                if (ignoreChange[0]) return;
                if (cbLockAspect.isChecked() && s.length() > 0) {
                    try {
                        int h = Integer.parseInt(s.toString());
                        int w = Math.max(1, Math.round(h * aspectRatio));
                        ignoreChange[0] = true;
                        etWidth.setText(String.valueOf(w));
                        ignoreChange[0] = false;
                    } catch (NumberFormatException ignored) { }
                }
            }
        });

        // Preset buttons
        btn25.setOnClickListener(v -> {
            ignoreChange[0] = true;
            etWidth.setText(String.valueOf(Math.max(1, origW / 4)));
            etHeight.setText(String.valueOf(Math.max(1, origH / 4)));
            ignoreChange[0] = false;
        });
        btn50.setOnClickListener(v -> {
            ignoreChange[0] = true;
            etWidth.setText(String.valueOf(Math.max(1, origW / 2)));
            etHeight.setText(String.valueOf(Math.max(1, origH / 2)));
            ignoreChange[0] = false;
        });
        btn75.setOnClickListener(v -> {
            ignoreChange[0] = true;
            etWidth.setText(String.valueOf(Math.max(1, origW * 3 / 4)));
            etHeight.setText(String.valueOf(Math.max(1, origH * 3 / 4)));
            ignoreChange[0] = false;
        });

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Apply", (dialog, which) -> {
                    try {
                        int newW = Integer.parseInt(etWidth.getText().toString());
                        int newH = Integer.parseInt(etHeight.getText().toString());

                        if (newW <= 0 || newH <= 0 || newW > 8192 || newH > 8192) {
                            Toast.makeText(this, "Invalid dimensions (1–8192 px)", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        Bitmap resized = Bitmap.createScaledBitmap(currentBitmap, newW, newH, true);
                        currentBitmap.recycle();
                        currentBitmap = resized;
                        ivPreview.setImageBitmap(currentBitmap);
                        Toast.makeText(this, "Resized to " + newW + "×" + newH, Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ──────────────────────────────────────────────
    //  Scroll Capture (long screenshot)
    // ──────────────────────────────────────────────

    private void startScrollCapture() {
        if (!ScreenshotService.isServiceRunning()) {
            Toast.makeText(this, "Screenshot service not running", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Scroll capture starting…", Toast.LENGTH_SHORT).show();

        // Tell service to start scroll capture, passing the current temp image path
        Intent intent = new Intent(this, ScreenshotService.class);
        intent.setAction(ScreenshotService.ACTION_SCROLL_CAPTURE);
        intent.putExtra("tempPath", imagePath);
        startService(intent);

        // Close preview — the service will reopen a new preview when done
        cancelTimer();
        finish();
    }

    // ──────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        cancelTimer();
        // Don't recycle — the bitmap may be referenced by the service
        super.onDestroy();
    }
}


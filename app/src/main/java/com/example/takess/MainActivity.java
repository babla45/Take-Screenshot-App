package com.example.takess;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private TextView tvCurrentPath;
    private TextView tvStatus;
    private TextView tvServiceStatus;
    private RadioButton rbInternal;
    private RadioButton rbSdCard;
    private Button btnChooseFolder;
    private Button btnToggleService;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private ActivityResultLauncher<Intent> projectionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        prefs = getSharedPreferences("takess_prefs", MODE_PRIVATE);

        tvCurrentPath = findViewById(R.id.tv_current_path);
        tvStatus = findViewById(R.id.tv_status);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        rbInternal = findViewById(R.id.rb_internal);
        rbSdCard = findViewById(R.id.rb_sd_card);
        btnChooseFolder = findViewById(R.id.btn_choose_folder);
        btnToggleService = findViewById(R.id.btn_toggle_service);
        RadioGroup radioGroup = findViewById(R.id.radio_group_storage);

        // ── Folder picker launcher ──
        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri treeUri = result.getData().getData();
                        if (treeUri != null) {
                            getContentResolver().takePersistableUriPermission(treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            prefs.edit().putString("saf_uri", treeUri.toString()).apply();
                            updatePathDisplay();
                            Toast.makeText(this, "Folder selected successfully", Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        // ── MediaProjection consent launcher (one-time) ──
        projectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent serviceIntent = new Intent(this, ScreenshotService.class);
                        serviceIntent.setAction(ScreenshotService.ACTION_INIT);
                        serviceIntent.putExtra("resultCode", result.getResultCode());
                        serviceIntent.putExtra("data", result.getData());

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent);
                        } else {
                            startService(serviceIntent);
                        }

                        Toast.makeText(this, "Screenshot service enabled!", Toast.LENGTH_SHORT).show();
                        // Delay status update slightly to let service start
                        btnToggleService.postDelayed(this::updateServiceStatus, 500);
                    } else {
                        Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                    }
                });

        // ── Service toggle button ──
        btnToggleService.setOnClickListener(v -> {
            if (ScreenshotService.isServiceRunning()) {
                // Stop the service
                Intent stopIntent = new Intent(this, ScreenshotService.class);
                stopIntent.setAction(ScreenshotService.ACTION_STOP);
                startService(stopIntent);
                btnToggleService.postDelayed(this::updateServiceStatus, 500);
            } else {
                // Request MediaProjection consent and start service
                MediaProjectionManager pm =
                        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (pm != null) {
                    projectionLauncher.launch(pm.createScreenCaptureIntent());
                }
            }
        });


        // ── Storage radio buttons ──
        String storageType = prefs.getString("storage_type", "internal");
        if ("sd_card".equals(storageType)) {
            rbSdCard.setChecked(true);
            btnChooseFolder.setEnabled(true);
        } else {
            rbInternal.setChecked(true);
            btnChooseFolder.setEnabled(false);
        }

        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_internal) {
                prefs.edit().putString("storage_type", "internal").apply();
                btnChooseFolder.setEnabled(false);
            } else if (checkedId == R.id.rb_sd_card) {
                prefs.edit().putString("storage_type", "sd_card").apply();
                btnChooseFolder.setEnabled(true);
                String safUri = prefs.getString("saf_uri", null);
                if (safUri == null) {
                    openFolderPicker();
                }
            }
            updatePathDisplay();
        });

        btnChooseFolder.setOnClickListener(v -> openFolderPicker());

        // ── Preview duration EditText ──
        EditText etDuration = findViewById(R.id.et_duration);

        float savedDuration = prefs.getFloat("preview_duration_f", 3f);
        etDuration.setText(formatDuration(savedDuration));

        etDuration.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                try {
                    float val = Float.parseFloat(s.toString());
                    if (val < 0.1f) val = 0.1f;
                    if (val > 60f) val = 60f;
                    prefs.edit().putFloat("preview_duration_f", val).apply();
                } catch (NumberFormatException ignored) { }
            }
        });

        // ── File name prefix EditText ──
        EditText etFilePrefix = findViewById(R.id.et_file_prefix);
        TextView tvFilenamePreview = findViewById(R.id.tv_filename_preview);

        String savedPrefix = prefs.getString("file_prefix", "Screenshot");
        etFilePrefix.setText(savedPrefix);
        updateFilenamePreview(tvFilenamePreview, savedPrefix);

        etFilePrefix.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                String prefix = s.toString().trim();
                if (prefix.isEmpty()) prefix = "Screenshot";
                // Remove characters not safe for filenames
                prefix = prefix.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                prefs.edit().putString("file_prefix", prefix).apply();
                updateFilenamePreview(tvFilenamePreview, prefix);
            }
        });

        // ── Permissions ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
            }
        }

        updatePathDisplay();
        updateServiceStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void updateServiceStatus() {
        if (ScreenshotService.isServiceRunning()) {
            tvServiceStatus.setText(R.string.service_running);
            btnToggleService.setText(R.string.disable_service);
        } else {
            tvServiceStatus.setText(R.string.service_stopped);
            btnToggleService.setText(R.string.enable_service);
        }
    }


    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private void updateFilenamePreview(TextView tv, String prefix) {
        String sample = prefix + "_" +
                new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                        .format(new java.util.Date()) + ".png";
        tv.setText("Preview: " + sample);
    }

    /** Format float: show as integer if whole, otherwise 1 decimal place */
    private String formatDuration(float val) {
        if (val == (int) val) {
            return String.valueOf((int) val);
        }
        return String.format(java.util.Locale.US, "%.1f", val);
    }

    private void updatePathDisplay() {
        String storageType = prefs.getString("storage_type", "internal");

        if ("internal".equals(storageType)) {
            tvCurrentPath.setText("📁 Pictures/TakeSS (Internal Storage)");
            tvStatus.setText("✅ Screenshots will be saved to internal storage");
        } else {
            String safUri = prefs.getString("saf_uri", null);
            if (safUri != null) {
                try {
                    DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(safUri));
                    String folderName = dir != null ? dir.getName() : "Selected folder";
                    tvCurrentPath.setText("📁 " + folderName + " (Custom Folder)");
                    tvStatus.setText("✅ Screenshots will be saved to selected folder");
                } catch (Exception e) {
                    tvCurrentPath.setText("📁 Custom folder (URI saved)");
                    tvStatus.setText("✅ Screenshots will be saved to selected folder");
                }
            } else {
                tvCurrentPath.setText("📁 No folder selected");
                tvStatus.setText("⚠️ Please select a folder for screenshots");
            }
        }
    }
}
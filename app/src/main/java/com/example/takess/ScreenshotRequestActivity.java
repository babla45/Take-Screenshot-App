package com.example.takess;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class ScreenshotRequestActivity extends AppCompatActivity {

    private ActivityResultLauncher<Intent> projectionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean justCollapse = getIntent() != null && getIntent().getBooleanExtra("justCollapse", false);
        boolean captureAfter = getIntent() != null && getIntent().getBooleanExtra("captureAfter", false);

        // If launched to collapse the shade and then capture
        if (justCollapse) {
            if (captureAfter && ScreenshotService.isServiceRunning()) {
                // Tell the service to capture after a delay (shade needs time to collapse)
                Intent captureIntent = new Intent(this, ScreenshotService.class);
                captureIntent.setAction(ScreenshotService.ACTION_CAPTURE);
                captureIntent.putExtra("delayMs", 600);
                startService(captureIntent);
            }
            finish();
            return;
        }

        // If service is already running with a live projection, just capture
        if (ScreenshotService.isServiceRunning()) {
            Intent captureIntent = new Intent(this, ScreenshotService.class);
            captureIntent.setAction(ScreenshotService.ACTION_CAPTURE);
            startService(captureIntent);
            finish();
            return;
        }

        projectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        int resultCode = result.getResultCode();
                        Intent data = result.getData();

                        // Small delay so the consent dialog animates away
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            Intent serviceIntent = new Intent(this, ScreenshotService.class);
                            serviceIntent.setAction(ScreenshotService.ACTION_INIT);
                            serviceIntent.putExtra("resultCode", resultCode);
                            serviceIntent.putExtra("data", data);

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(serviceIntent);
                            } else {
                                startService(serviceIntent);
                            }
                            finish();
                        }, 500);
                    } else {
                        Toast.makeText(this, "Screenshot permission denied", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });

        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager != null) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent());
        } else {
            Toast.makeText(this, "MediaProjection not available", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}


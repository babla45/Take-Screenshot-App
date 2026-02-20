package com.example.takess;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent foreground service that keeps MediaProjection alive.
 *
 * ACTIONS:
 *   ACTION_INIT      – first launch: receives resultCode+data, sets up the projection
 *   ACTION_CAPTURE   – captures a single frame → shows preview (auto-saves in 3 s)
 *   ACTION_SAVE_TEMP – saves a temp-file bitmap to the user's configured storage
 *   ACTION_STOP      – user explicitly stops the service
 */
public class ScreenshotService extends Service {

    private static final String TAG = "ScreenshotService";
    public static final String CHANNEL_ID = "screenshot_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_INIT = "com.example.takess.ACTION_INIT";
    public static final String ACTION_CAPTURE = "com.example.takess.ACTION_CAPTURE";
    public static final String ACTION_SAVE_TEMP = "com.example.takess.ACTION_SAVE_TEMP";
    public static final String ACTION_STOP = "com.example.takess.ACTION_STOP";

    private MediaProjection mediaProjection;
    private boolean isProjectionReady = false;
    private static boolean isRunning = false;

    public static boolean isServiceRunning() {
        return isRunning;
    }

    // ──────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithNotification();

        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (action == null) action = ACTION_INIT;

        switch (action) {
            case ACTION_INIT:
                handleInit(intent);
                break;
            case ACTION_CAPTURE:
                long delayMs = intent.getLongExtra("delayMs", 0);
                if (delayMs > 0) {
                    new Handler(Looper.getMainLooper()).postDelayed(this::handleCapture, delayMs);
                } else {
                    handleCapture();
                }
                break;
            case ACTION_SAVE_TEMP:
                handleSaveTemp(intent);
                break;
            case ACTION_STOP:
                cleanup();
                stopForeground(true);
                stopSelf();
                break;
        }

        return START_STICKY;
    }

    // ──────────────────────────────────────────────
    //  ACTION_INIT
    // ──────────────────────────────────────────────

    private void handleInit(Intent intent) {
        if (isProjectionReady && mediaProjection != null) {
            handleCapture();
            return;
        }

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");
        if (data == null) { showToast("Screen capture permission data missing"); return; }

        MediaProjectionManager pm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (pm == null) { showToast("MediaProjection not available"); return; }

        mediaProjection = pm.getMediaProjection(resultCode, data);
        if (mediaProjection == null) { showToast("Failed to obtain screen capture permission"); return; }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                isProjectionReady = false;
                mediaProjection = null;
                Log.i(TAG, "MediaProjection stopped by system");
            }
        }, new Handler(Looper.getMainLooper()));

        isProjectionReady = true;
        showToast("TakeSS ready! Use the tile or notification button to capture.");
        startForegroundWithNotification();
    }

    // ──────────────────────────────────────────────
    //  ACTION_CAPTURE — single screenshot → preview
    // ──────────────────────────────────────────────

    private void handleCapture() {
        if (!isProjectionReady || mediaProjection == null) {
            showToast("Permission expired. Please re-enable from the app.");
            return;
        }

        captureFrame(bitmap -> {
            if (bitmap == null) {
                showToast("Failed to capture screenshot");
                return;
            }
            String tempPath = saveBitmapToTemp(bitmap);
            bitmap.recycle();
            if (tempPath != null) {
                launchPreview(tempPath);
            } else {
                showToast("Failed to save temporary screenshot");
            }
        });
    }

    // ──────────────────────────────────────────────
    //  ACTION_SAVE_TEMP — persist temp file to storage
    // ──────────────────────────────────────────────

    private void handleSaveTemp(Intent intent) {
        String tempPath = intent.getStringExtra("tempPath");
        if (tempPath == null) return;

        Bitmap bitmap = BitmapFactory.decodeFile(tempPath);
        if (bitmap == null) { showToast("Failed to read screenshot"); return; }

        saveScreenshot(bitmap);
        bitmap.recycle();

        try { //noinspection ResultOfMethodCallIgnored
            new File(tempPath).delete(); } catch (Exception ignored) { }
    }

    // ──────────────────────────────────────────────
    //  Core: capture a single frame
    // ──────────────────────────────────────────────

    interface CaptureCallback {
        void onCaptured(@Nullable Bitmap bitmap);
    }

    @SuppressWarnings("deprecation")
    private void captureFrame(CaptureCallback callback) {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        VirtualDisplay vd = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null, null);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Image image = null;
            Bitmap bitmap = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    bitmap = imageToBitmap(image, width, height);
                    image.close();
                }
            } catch (Exception e) {
                if (image != null) image.close();
                Log.e(TAG, "captureFrame error", e);
            } finally {
                vd.release();
                reader.close();
            }
            callback.onCaptured(bitmap);
        }, 600);
    }

    private Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height,
                Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);

        if (bitmap.getWidth() > width) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            bitmap.recycle();
            return cropped;
        }
        return bitmap;
    }

    // ──────────────────────────────────────────────
    //  Temp file & preview launcher
    // ──────────────────────────────────────────────

    private String saveBitmapToTemp(Bitmap bitmap) {
        try {
            File tempDir = new File(getCacheDir(), "screenshots");
            if (!tempDir.exists()) //noinspection ResultOfMethodCallIgnored
                tempDir.mkdirs();
            String fileName = "temp_ss_" + System.currentTimeMillis() + ".png";
            File tempFile = new File(tempDir, fileName);

            FileOutputStream fos = new FileOutputStream(tempFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            return tempFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "saveBitmapToTemp error", e);
            return null;
        }
    }

    private void launchPreview(String tempPath) {
        Intent previewIntent = new Intent(this, ScreenshotPreviewActivity.class);
        previewIntent.putExtra(ScreenshotPreviewActivity.EXTRA_IMAGE_PATH, tempPath);
        previewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(previewIntent);
    }

    // ──────────────────────────────────────────────
    //  Final save logic
    // ──────────────────────────────────────────────

    private void saveScreenshot(Bitmap bitmap) {
        SharedPreferences prefs = getSharedPreferences("takess_prefs", MODE_PRIVATE);
        String storageType = prefs.getString("storage_type", "internal");
        String safUri = prefs.getString("saf_uri", null);

        String fileName = "Screenshot_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) +
                ".png";

        boolean saved;
        if ("sd_card".equals(storageType) && safUri != null) {
            saved = saveWithSAF(bitmap, safUri, fileName);
        } else {
            saved = saveToInternalStorage(bitmap, fileName);
        }

        showToast(saved ? "Screenshot saved: " + fileName : "Failed to save screenshot");
    }

    private boolean saveWithSAF(Bitmap bitmap, String uriString, String fileName) {
        try {
            Uri treeUri = Uri.parse(uriString);
            DocumentFile directory = DocumentFile.fromTreeUri(this, treeUri);
            if (directory == null || !directory.canWrite()) {
                showToast("Cannot write to selected folder.");
                return false;
            }
            DocumentFile file = directory.createFile("image/png", fileName);
            if (file == null) return false;
            OutputStream os = getContentResolver().openOutputStream(file.getUri());
            if (os == null) return false;
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "saveWithSAF: ", e);
            return false;
        }
    }

    private boolean saveToInternalStorage(Bitmap bitmap, String fileName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveWithMediaStore(bitmap, fileName);
        } else {
            return saveDirectly(bitmap, fileName);
        }
    }

    private boolean saveWithMediaStore(Bitmap bitmap, String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TakeSS");

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return false;
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) return false;
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "saveWithMediaStore: ", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean saveDirectly(Bitmap bitmap, String fileName) {
        File dir = new File(
                android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES), "TakeSS");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

        File file = new File(dir, fileName);
        try {
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaScanIntent.setData(Uri.fromFile(file));
            sendBroadcast(mediaScanIntent);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "saveDirectly: ", e);
            return false;
        }
    }

    // ──────────────────────────────────────────────
    //  Notification
    // ──────────────────────────────────────────────

    private void startForegroundWithNotification() {
        // "Stop" action
        Intent stopIntent = new Intent(this, ScreenshotService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // "Take Screenshot" action — goes through activity to collapse shade first
        Intent captureActivityIntent = new Intent(this, ScreenshotRequestActivity.class);
        captureActivityIntent.putExtra("justCollapse", true);
        captureActivityIntent.putExtra("captureAfter", true);
        captureActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent capturePending = PendingIntent.getActivity(this, 2, captureActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = isProjectionReady
                ? "Ready — tap the tile or \"Take Screenshot\" below"
                : "Starting up…";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TakeSS")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .addAction(R.drawable.ic_notification, "Stop", stopPending);

        if (isProjectionReady) {
            builder.addAction(R.drawable.ic_screenshot_tile, "Take Screenshot", capturePending);
        }

        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ScreenshotService.this, message, Toast.LENGTH_SHORT).show());
    }

    private void cleanup() {
        isProjectionReady = false;
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screenshot Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps screen capture ready so you can take screenshots instantly");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cleanup();
        isRunning = false;
        super.onDestroy();
    }
}

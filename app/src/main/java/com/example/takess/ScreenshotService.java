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
import android.graphics.Canvas;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persistent foreground service that keeps MediaProjection alive.
 *
 * ACTIONS:
 *   ACTION_INIT           – first launch: receives resultCode+data, sets up the projection
 *   ACTION_CAPTURE        – captures a single frame → shows preview (auto-saves in 3 s)
 *   ACTION_SAVE_TEMP      – saves a temp-file bitmap to the user's configured storage
 *   ACTION_SCROLL_CAPTURE – captures multiple frames while auto-scrolling → stitches → preview
 *   ACTION_STOP           – user explicitly stops the service
 */
public class ScreenshotService extends Service {

    private static final String TAG = "ScreenshotService";
    public static final String CHANNEL_ID = "screenshot_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_INIT = "com.example.takess.ACTION_INIT";
    public static final String ACTION_CAPTURE = "com.example.takess.ACTION_CAPTURE";
    public static final String ACTION_SAVE_TEMP = "com.example.takess.ACTION_SAVE_TEMP";
    public static final String ACTION_SCROLL_CAPTURE = "com.example.takess.ACTION_SCROLL_CAPTURE";
    public static final String ACTION_STOP = "com.example.takess.ACTION_STOP";

    private static final int SCROLL_CAPTURE_COUNT = 5;
    private static final long SCROLL_DELAY_MS = 800;

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
            case ACTION_SCROLL_CAPTURE:
                handleScrollCapture(intent);
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

        try { new File(tempPath).delete(); } catch (Exception ignored) { }
    }

    // ──────────────────────────────────────────────
    //  ACTION_SCROLL_CAPTURE — long screenshot
    // ──────────────────────────────────────────────

    private void handleScrollCapture(Intent intent) {
        if (!isProjectionReady || mediaProjection == null) {
            showToast("Permission expired. Please re-enable from the app.");
            return;
        }

        String existingTempPath = intent.getStringExtra("tempPath");
        showToast("Scroll capture: capturing " + SCROLL_CAPTURE_COUNT + " pages…");

        List<Bitmap> frames = new ArrayList<>();

        if (existingTempPath != null) {
            Bitmap first = BitmapFactory.decodeFile(existingTempPath);
            if (first != null) frames.add(first);
            try { new File(existingTempPath).delete(); } catch (Exception ignored) { }
        }

        int remaining = SCROLL_CAPTURE_COUNT - frames.size();
        scrollAndCapture(frames, remaining, () -> {
            if (frames.isEmpty()) {
                showToast("Scroll capture failed — no frames");
                return;
            }
            Bitmap stitched = stitchBitmaps(frames);
            for (Bitmap b : frames) {
                if (!b.isRecycled()) b.recycle();
            }
            if (stitched == null) {
                showToast("Failed to stitch screenshots");
                return;
            }
            String tempPath = saveBitmapToTemp(stitched);
            stitched.recycle();
            if (tempPath != null) {
                launchPreview(tempPath);
            }
        });
    }

    private void scrollAndCapture(List<Bitmap> frames, int remaining, Runnable onDone) {
        if (remaining <= 0) {
            new Handler(Looper.getMainLooper()).post(onDone);
            return;
        }

        performGlobalScroll();

        new Handler(Looper.getMainLooper()).postDelayed(() ->
            captureFrame(bitmap -> {
                if (bitmap != null) frames.add(bitmap);
                scrollAndCapture(frames, remaining - 1, onDone);
            }), SCROLL_DELAY_MS);
    }

    private void performGlobalScroll() {
        SwipeDetectorService.requestScroll();
    }

    private static final float SCROLL_OVERLAP_FRACTION = 0.15f;

    private Bitmap stitchBitmaps(List<Bitmap> bitmaps) {
        if (bitmaps.isEmpty()) return null;
        if (bitmaps.size() == 1) return bitmaps.get(0).copy(Bitmap.Config.ARGB_8888, true);

        int width = bitmaps.get(0).getWidth();

        // Calculate total height using smart overlap detection
        int totalHeight = bitmaps.get(0).getHeight();
        int[] overlaps = new int[bitmaps.size()]; // overlap for each frame (index 0 unused)
        for (int i = 1; i < bitmaps.size(); i++) {
            int detectedOverlap = findOverlap(bitmaps.get(i - 1), bitmaps.get(i));
            overlaps[i] = detectedOverlap;
            totalHeight += bitmaps.get(i).getHeight() - detectedOverlap;
        }

        try {
            Bitmap result = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);

            int yOffset = 0;
            for (int i = 0; i < bitmaps.size(); i++) {
                Bitmap bmp = bitmaps.get(i);
                if (i == 0) {
                    canvas.drawBitmap(bmp, 0, 0, null);
                    yOffset = bmp.getHeight();
                } else {
                    int srcTop = overlaps[i];
                    int srcHeight = bmp.getHeight() - srcTop;
                    if (srcHeight <= 0) continue;
                    Bitmap cropped = Bitmap.createBitmap(bmp, 0, srcTop, bmp.getWidth(), srcHeight);
                    canvas.drawBitmap(cropped, 0, yOffset, null);
                    yOffset += srcHeight;
                    cropped.recycle();
                }
            }

            if (yOffset < totalHeight) {
                Bitmap trimmed = Bitmap.createBitmap(result, 0, 0, width, yOffset);
                result.recycle();
                return trimmed;
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "stitchBitmaps error", e);
            return null;
        }
    }

    /**
     * Find the best overlap between the bottom of topBitmap and the top of bottomBitmap
     * by comparing sampled pixel rows. Returns the number of overlapping rows in bottomBitmap.
     * Falls back to a fixed fraction if no good match is found.
     */
    private int findOverlap(Bitmap topBitmap, Bitmap bottomBitmap) {
        int fallback = (int) (topBitmap.getHeight() * SCROLL_OVERLAP_FRACTION);

        try {
            int w = Math.min(topBitmap.getWidth(), bottomBitmap.getWidth());
            int topH = topBitmap.getHeight();
            int bottomH = bottomBitmap.getHeight();

            // Search window: check up to 40% of the frame height for overlap
            int maxSearchRows = (int) (topH * 0.40f);
            maxSearchRows = Math.min(maxSearchRows, bottomH);

            // Sample 10 evenly-spaced columns for comparison
            int numSamples = Math.min(10, w);
            int[] sampleCols = new int[numSamples];
            for (int s = 0; s < numSamples; s++) {
                sampleCols[s] = (int) ((s + 0.5f) * w / numSamples);
            }

            // For each possible overlap amount, compare bottom rows of topBitmap
            // with top rows of bottomBitmap
            int bestOverlap = -1;
            float bestScore = 0;

            for (int overlap = 10; overlap < maxSearchRows; overlap++) {
                int matchingPixels = 0;
                int totalPixels = 0;

                // Compare a few rows at this overlap level (check every 4th row for speed)
                for (int row = 0; row < overlap; row += 4) {
                    int topRow = topH - overlap + row;
                    int bottomRow = row;

                    if (topRow < 0 || topRow >= topH || bottomRow >= bottomH) continue;

                    for (int col : sampleCols) {
                        if (col >= w) continue;
                        int topPixel = topBitmap.getPixel(col, topRow);
                        int bottomPixel = bottomBitmap.getPixel(col, bottomRow);
                        totalPixels++;

                        // Compare with small tolerance (allow minor compression artifacts)
                        if (pixelsMatch(topPixel, bottomPixel, 15)) {
                            matchingPixels++;
                        }
                    }
                }

                if (totalPixels > 0) {
                    float score = (float) matchingPixels / totalPixels;
                    if (score > bestScore && score > 0.85f) {
                        bestScore = score;
                        bestOverlap = overlap;
                    }
                }
            }

            if (bestOverlap > 0) {
                Log.i(TAG, "findOverlap: detected " + bestOverlap + "px overlap (score=" + bestScore + ")");
                return bestOverlap;
            }
        } catch (Exception e) {
            Log.e(TAG, "findOverlap error, using fallback", e);
        }

        Log.i(TAG, "findOverlap: using fallback " + fallback + "px");
        return fallback;
    }

    /**
     * Compare two ARGB pixels with a per-channel tolerance.
     */
    private boolean pixelsMatch(int pixel1, int pixel2, int tolerance) {
        int r1 = (pixel1 >> 16) & 0xFF, g1 = (pixel1 >> 8) & 0xFF, b1 = pixel1 & 0xFF;
        int r2 = (pixel2 >> 16) & 0xFF, g2 = (pixel2 >> 8) & 0xFF, b2 = pixel2 & 0xFF;
        return Math.abs(r1 - r2) <= tolerance
                && Math.abs(g1 - g2) <= tolerance
                && Math.abs(b1 - b2) <= tolerance;
    }

    // ──────────────────────────────────────────────
    //  Core: capture a single frame
    // ──────────────────────────────────────────────

    interface CaptureCallback {
        void onCaptured(@Nullable Bitmap bitmap);
    }

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
            if (!tempDir.exists()) tempDir.mkdirs();
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
        if (!dir.exists()) dir.mkdirs();

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


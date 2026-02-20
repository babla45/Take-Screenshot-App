package com.example.takess;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;

/**
 * Accessibility service that provides scroll gesture dispatching
 * for long screenshot (scroll capture) functionality.
 */
public class SwipeDetectorService extends AccessibilityService {

    private static final String TAG = "SwipeDetectorService";

    private static boolean isRunning = false;
    private static WeakReference<SwipeDetectorService> instanceRef;

    public static boolean isServiceRunning() {
        return isRunning;
    }

    /**
     * Called by ScreenshotService to perform a scroll gesture for long screenshots.
     * Uses dispatchGesture to simulate a swipe-up (scroll down) gesture.
     */
    public static void requestScroll() {
        SwipeDetectorService service = instanceRef != null ? instanceRef.get() : null;
        if (service != null) {
            service.performScrollGesture();
        } else {
            Log.w(TAG, "requestScroll: Accessibility service not connected");
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        isRunning = true;
        instanceRef = new WeakReference<>(this);
        Log.i(TAG, "SwipeDetectorService connected (scroll support only)");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used
    }

    @Override
    public void onInterrupt() { }

    // ──────────────────────────────────────────────
    //  Scroll via dispatchGesture (swipe up to scroll content down)
    // ──────────────────────────────────────────────

    private void performScrollGesture() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;

        // Swipe from 75% height to 25% height (center of screen horizontally)
        float startX = screenWidth / 2f;
        float startY = screenHeight * 0.75f;
        float endY = screenHeight * 0.25f;

        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(startX, endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 300);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.i(TAG, "Scroll gesture completed");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "Scroll gesture cancelled");
            }
        }, null);

        if (!dispatched) {
            Log.w(TAG, "dispatchGesture failed — gesture not dispatched");
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        instanceRef = null;
        super.onDestroy();
    }
}



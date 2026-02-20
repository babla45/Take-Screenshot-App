package com.example.takess;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class ScreenshotTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(ScreenshotService.isServiceRunning()
                    ? Tile.STATE_ACTIVE
                    : Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.tile_label));
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();

        if (ScreenshotService.isServiceRunning()) {
            // Service is running — send capture directly, skip the Activity middleman
            // startActivityAndCollapse() handles collapsing the shade for us
            Intent captureIntent = new Intent(this, ScreenshotService.class);
            captureIntent.setAction(ScreenshotService.ACTION_CAPTURE);
            captureIntent.putExtra("delayMs", 300L); // wait for shade to collapse
            startService(captureIntent);
            // Still need to collapse the shade
            collapsePanel();
        } else {
            // First time or service was killed → need consent
            Intent intent = new Intent(this, ScreenshotRequestActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("fromTile", true);
            collapseAndStart(intent, 0);
        }
    }

    @SuppressLint("NewApi")
    private void collapsePanel() {
        try {
            // Use a no-op activity just to trigger shade collapse
            Intent collapseIntent = new Intent(this, ScreenshotRequestActivity.class);
            collapseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            collapseIntent.putExtra("justCollapse", true);
            collapseIntent.putExtra("captureAfter", false); // capture already sent above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pi = PendingIntent.getActivity(this, 3, collapseIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(collapseIntent);
            }
        } catch (Exception e) {
            // Shade collapse is best-effort
        }
    }

    @SuppressLint({"NewApi", "StartActivityAndCollapseDeprecated"})
    private void collapseAndStart(Intent intent, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: use PendingIntent overload
            // Use FLAG_MUTABLE so extras are delivered properly after force-stop
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            startActivityAndCollapse(pendingIntent);
        } else {
            // Pre-34: use deprecated Intent overload
            startActivityAndCollapse(intent);
        }
    }
}



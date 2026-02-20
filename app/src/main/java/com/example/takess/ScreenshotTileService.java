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
            // Collapse the shade first, then capture after a short delay
            // so the notification panel isn't in the screenshot
            Intent collapseIntent = new Intent(this, ScreenshotRequestActivity.class);
            collapseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            collapseIntent.putExtra("justCollapse", true);
            collapseIntent.putExtra("captureAfter", true);
            collapseAndStart(collapseIntent, 1);
        } else {
            // First time or service was killed → need consent
            Intent intent = new Intent(this, ScreenshotRequestActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            collapseAndStart(intent, 0);
        }
    }

    @SuppressLint({"NewApi", "StartActivityAndCollapseDeprecated"})
    private void collapseAndStart(Intent intent, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: use PendingIntent overload
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pendingIntent);
        } else {
            // Pre-34: use deprecated Intent overload
            startActivityAndCollapse(intent);
        }
    }
}



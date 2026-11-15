package com.hassine.bubbles;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class BubblesTileService extends TileService {
    private ContentObserver observer;

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        refresh();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (observer == null) {
            observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) { refresh(); }
            };
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(BubbleState.KEY), false, observer);
        }
        refresh();
    }

    @Override
    public void onStopListening() {
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (!BubbleState.hasWritePermission(this)) {
            showSetup();
            return;
        }

        int previous = BubbleState.effective(this);
        boolean desiredOn = previous != 1;
        boolean written = BubbleState.set(this, desiredOn);
        refresh();

        if (!written) {
            Toast.makeText(this,
                    "Bubbles setting could not be changed. Open the setup app.",
                    Toast.LENGTH_LONG).show();
            showSetup();
        } else {
            // Samsung may maintain an additional Floating notifications mode.
            Boolean actual = BubbleState.featureEnabled(this);
            if (actual != null && actual != desiredOn) {
                Toast.makeText(this,
                        "Samsung may need Bubbles selected under Floating notifications.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean authorized = BubbleState.hasWritePermission(this);
        int state = BubbleState.effective(this);

        tile.setLabel("Bubbles");
        tile.setState(!authorized ? Tile.STATE_UNAVAILABLE :
                (state == 1 ? Tile.STATE_ACTIVE :
                (state == 0 ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE)));
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(!authorized ? "Set up" :
                    (state == 1 ? "On" : (state == 0 ? "Off" : "Unknown")));
        }
        tile.setContentDescription(!authorized
                ? "Bubbles: permission setup required" :
                (state == 1 ? "Bubbles on" : (state == 0 ? "Bubbles off" : "Bubbles unknown")));
        tile.updateTile();
    }

    private void showSetup() {
        Intent activity = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, activity, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(activity);
        }
    }
}

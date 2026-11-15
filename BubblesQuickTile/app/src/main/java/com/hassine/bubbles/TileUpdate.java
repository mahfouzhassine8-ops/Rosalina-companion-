package com.hassine.bubbles;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.TileService;

final class TileUpdate {
    private TileUpdate() { }
    static void request(Context context) {
        TileService.requestListeningState(context,
                new ComponentName(context, BubblesTileService.class));
    }
}

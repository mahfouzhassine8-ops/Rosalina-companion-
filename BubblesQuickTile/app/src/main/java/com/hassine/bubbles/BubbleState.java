package com.hassine.bubbles;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

final class BubbleState {
    private BubbleState() { }

    // AOSP's system-wide notification bubble switch. Samsung's separate
    // Smart pop-up view selector may have additional, proprietary behavior.
    static final String KEY = "notification_bubbles";
    static final String GRANT_COMMAND =
            "adb shell pm grant com.hassine.bubbles android.permission.WRITE_SECURE_SETTINGS";

    static boolean hasWritePermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    // -1 means there is no stored setting, or it was not readable.
    static int read(Context context) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(), KEY, -1);
        } catch (SecurityException e) {
            return -1;
        }
    }

    // Android's actual feature-level decision (not a per-app bubble preference).
    static Boolean featureEnabled(Context context) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && Build.VERSION.SDK_INT >= 31) {
                return manager.areBubblesEnabled();
            }
        } catch (RuntimeException ignored) {
            // Some OEM builds may not implement the public API consistently.
        }
        return null;
    }

    static int effective(Context context) {
        Boolean fromSystem = featureEnabled(context);
        if (fromSystem != null) return fromSystem ? 1 : 0;
        int stored = read(context);
        return stored >= 0 ? stored : -1;
    }

    // Never claim success without writing and reading the requested value.
    static boolean set(Context context, boolean enabled) {
        if (!hasWritePermission(context)) return false;
        try {
            int requested = enabled ? 1 : 0;
            if (!Settings.Secure.putInt(context.getContentResolver(), KEY, requested)) {
                return false;
            }
            return read(context) == requested;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean toggle(Context context) {
        int current = effective(context);
        return set(context, current != 1);
    }
}

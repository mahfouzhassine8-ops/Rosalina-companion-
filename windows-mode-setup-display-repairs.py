#!/usr/bin/env python3
"""Windows Mode 7.1.12: screen fill, bounded QGE polling, setup tools, NAT check.

Applies ONLY after Windows Mode 7.1.9, 7.1.10 and 7.1.11 source scripts
to pinned QubeVM 7.1.4. Preserves existing Windows disk/VM/UEFI/network config.
All guest OOBE commands require direct user interaction + confirmation.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else "upstream")/"qube-android-lib"/"src"/"main"
java=root/"java"/"com"/"max2idea"/"android"/"qube"

def patch(path,old,new,expected=1):
    f=java/path
    s=f.read_text()
    count=s.count(old)
    if count!=expected:
        raise RuntimeError(f"Unexpected anchor in {path}: {old[:70]!r} {count} != {expected}")
    f.write_text(s.replace(old,new))
    print("SCOPED JAVA",path)

def patch_res(path,old,new,expected=1):
    f=root/path
    s=f.read_text()
    count=s.count(old)
    if count!=expected:
        raise RuntimeError(f"Unexpected resource anchor in {path}: {old[:70]!r} {count} != {expected}")
    f.write_text(s.replace(old,new))
    print("SCOPED RES",path)

display_file=java/"main/WindowsDisplayOptions.java"
if display_file.exists():
    raise RuntimeError("WindowsDisplayOptions already exists")
display_file.write_text(r'''/*
 * Copyright (C) 2026 Windows Mode contributors
 * QubeVM GPL-3.0 derivative — Android window/display preferences only.
 */
package com.max2idea.android.qube.main;

import android.content.Context;
import com.max2idea.android.qube.machine.MachineController;

public final class WindowsDisplayOptions {
    public static final int FIT = 0;
    public static final int FILL = 1;
    public static final int STRETCH = 2;
    private static final String PREF = "windows_mode_guest_display";
    private static final String SCALE = "screen_scaling";
    private static final String EFFICIENT = "efficient_guest_frame_pump";
    private WindowsDisplayOptions() {}

    public static boolean isWindowsGuest(Context context) {
        return WindowsCpuMode.isTargetWindowsVM(context,
                MachineController.getInstance().getMachine());
    }

    public static int scale(Context context) {
        if (!isWindowsGuest(context))
            return FIT;
        int value = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getInt(SCALE, FIT);
        return value >= FIT && value <= STRETCH ? value : FIT;
    }

    public static void setScale(Context context, int value) {
        if (!isWindowsGuest(context) || value < FIT || value > STRETCH)
            return;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt(SCALE, value).apply();
    }

    public static boolean efficient(Context context) {
        return isWindowsGuest(context)
                && context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(EFFICIENT, true);
    }

    public static void setEfficient(Context context, boolean enabled) {
        if (!isWindowsGuest(context))
            return;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(EFFICIENT, enabled).apply();
    }
}
''')

# Fullscreen view is a display transform, NOT a change to Windows guest
# monitor resolution. Fit has bars, Fill crops, Stretch uses every pixel.
patch("main/QubeQGESurface.java",
      '''        float sx = getWidth() / (float) frameBitmap.getWidth();
        float sy = getHeight() / (float) frameBitmap.getHeight();
        float scale = Math.min(sx, sy);
        frameMatrix.setScale(scale, scale);
        frameMatrix.postTranslate(
                (getWidth() - frameBitmap.getWidth() * scale) / 2f,
                (getHeight() - frameBitmap.getHeight() * scale) / 2f);
        canvas.drawBitmap(frameBitmap, frameMatrix, framePaint);''',
      '''        updateScreenMatrix();
        canvas.drawBitmap(frameBitmap, frameMatrix, framePaint);''')
patch("main/QubeQGESurface.java",
      '''    // NEVER MATCH: QubeQGESurface placeholder''',
      '''    // NEVER MATCH: QubeQGESurface placeholder''',0) if False else None
patch("main/QubeQGESurface.java",
      '''     // Converts a point in view coordinates into framebuffer coordinates, using the same
     // scale/offset onDraw() paints with
    private float[] toFrameBufferPoint(float x, float y) {
        if (frameBitmap == null || getWidth() <= 0 || getHeight() <= 0)
            return new float[]{x, y};
        float scale = Math.min(getWidth() / (float) frameBitmap.getWidth(),
                               getHeight() / (float) frameBitmap.getHeight());
        if (scale <= 0)
            return new float[]{x, y};
        float left = (getWidth() - frameBitmap.getWidth() * scale) / 2f;
        float top = (getHeight() - frameBitmap.getHeight() * scale) / 2f;
        return new float[]{(x - left) / scale, (y - top) / scale};
    }''',
      '''    // Draw and touch inversion must use the same transform, including when
    // the Samsung keyboard, DeX window, or foldable posture changes the view.
    private void updateScreenMatrix() {
        frameMatrix.reset();
        if (frameBitmap == null || getWidth() <= 0 || getHeight() <= 0)
            return;
        float sx = getWidth() / (float) frameBitmap.getWidth();
        float sy = getHeight() / (float) frameBitmap.getHeight();
        int mode = WindowsDisplayOptions.scale(getContext());
        if (mode != WindowsDisplayOptions.STRETCH) {
            float uniform = mode == WindowsDisplayOptions.FILL
                    ? Math.max(sx, sy) : Math.min(sx, sy);
            sx = sy = uniform;
        }
        frameMatrix.setScale(sx, sy);
        frameMatrix.postTranslate(
                (getWidth() - frameBitmap.getWidth() * sx) / 2f,
                (getHeight() - frameBitmap.getHeight() * sy) / 2f);
    }

    private float[] toFrameBufferPoint(float x, float y) {
        if (frameBitmap == null || getWidth() <= 0 || getHeight() <= 0)
            return new float[]{x, y};
        updateScreenMatrix();
        Matrix inverse = new Matrix();
        if (!frameMatrix.invert(inverse))
            return new float[]{x, y};
        float[] pt = new float[]{x, y};
        inverse.mapPoints(pt);
        return pt;
    }''')

# Existing QGE fullscreen setting hides system bars, but still leaves the
# menu toolbar and the letterboxed Windows surface. Add an *explicit*
# immersive mode with Android Back recovery; default behavior unchanged.
patch("main/QubeQGEActivity.java",
      '''    private boolean quit = false;''',
      '''    private boolean quit = false;
    private boolean immersiveWindow = false;
    private boolean toolbarBeforeImmersive = false;
    private long lastFramePollNanos = 0;''')

patch("main/QubeQGEActivity.java",
      '''            pullGfxFrame();
            Choreographer.getInstance().postFrameCallback(this);''',
      '''            if (WindowsDisplayOptions.efficient(QubeQGEActivity.this)) {
                long interval = 1000000000L / 30;
                if (lastFramePollNanos != 0
                        && frameTimeNanos - lastFramePollNanos < interval) {
                    Choreographer.getInstance().postFrameCallback(this);
                    return;
                }
            }
            lastFramePollNanos = frameTimeNanos;
            pullGfxFrame();
            Choreographer.getInstance().postFrameCallback(this);''')

patch("main/QubeQGEActivity.java",
      '''        nativeRefreshRatePushed = false;
        Log.d(TAG, "startGfxLoop: starting, requesting " + QubeSettingsManager.getRefreshRate(this) + "hz");
        applyRefreshRate(QubeSettingsManager.getRefreshRate(this));''',
      '''        nativeRefreshRatePushed = false;
        lastFramePollNanos = 0;
        Log.d(TAG, "startGfxLoop: starting, target=" + effectiveGuestRefreshRate() + "hz");
        applyRefreshRate(effectiveGuestRefreshRate());''')

patch("main/QubeQGEActivity.java",
      '''            int hz = QubeSettingsManager.getRefreshRate(this);
            QubeGfx.nativeSetRefreshRate(hz);''',
      '''            int hz = effectiveGuestRefreshRate();
            QubeGfx.nativeSetRefreshRate(hz);''')

patch("main/QubeQGEActivity.java",
      '''        if (hasFocus && gfxLoopRunning) {
            applyRefreshRate(QubeSettingsManager.getRefreshRate(this));
        }''',
      '''        if (hasFocus && gfxLoopRunning) {
            applyRefreshRate(effectiveGuestRefreshRate());
        }''')

patch("main/QubeQGEActivity.java",
      '''    private void applyRefreshRate(int hz) {''',
      '''    private int effectiveGuestRefreshRate() {
        int requested = QubeSettingsManager.getRefreshRate(this);
        return WindowsDisplayOptions.efficient(this) ? Math.min(30, requested)
                : requested;
    }

    private void applyRefreshRate(int hz) {''')

patch("main/QubeQGEActivity.java",
      '''        } else if (item.getItemId() == R.id.itemHideToolbar) {
            hideToolbar();''',
      '''        } else if (item.getItemId() == R.id.itemImmersiveWindows) {
            toggleWindowsImmersive();
        } else if (item.getItemId() == R.id.itemWindowsScaling) {
            showWindowsScaling();
        } else if (item.getItemId() == R.id.itemWindowsRendering) {
            showWindowsRendering();
        } else if (item.getItemId() == R.id.itemHideToolbar) {
            hideToolbar();''')

patch("main/QubeQGEActivity.java",
      '''    private void handleBackPressed() {
        if (!QubeSettingsManager.getAlwaysShowMenuToolbar(this)) {''',
      '''    private void handleBackPressed() {
        if (immersiveWindow) {
            toggleWindowsImmersive();
            return;
        }
        if (!QubeSettingsManager.getAlwaysShowMenuToolbar(this)) {''')

patch("main/QubeQGEActivity.java",
      '''    private void setupScreen() {''',
      '''    private void toggleWindowsImmersive() {
        if (!WindowsDisplayOptions.isWindowsGuest(this))
            return;
        immersiveWindow = !immersiveWindow;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        ActionBar action = getSupportActionBar();
        if (immersiveWindow) {
            toolbarBeforeImmersive = action != null && action.isShowing();
            controller.hide(WindowInsetsCompat.Type.systemBars());
            if (action != null)
                action.hide();
        } else {
            setupScreen();
            if (toolbarBeforeImmersive && action != null)
                action.show();
        }
        if (mSurface != null) {
            mSurface.requestLayout();
            mSurface.invalidate();
        }
    }

    private void showWindowsScaling() {
        if (!WindowsDisplayOptions.isWindowsGuest(this))
            return;
        String[] choices = {
                "Fit — all Windows content visible (may have borders)",
                "Fill — use all screen space (crops edges)",
                "Stretch — use all screen space (may distort)"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Windows display scaling")
                .setSingleChoiceItems(choices, WindowsDisplayOptions.scale(this),
                        (dialog, choice) -> {
                            WindowsDisplayOptions.setScale(this, choice);
                            mSurface.requestLayout();
                            mSurface.invalidate();
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWindowsRendering() {
        if (!WindowsDisplayOptions.isWindowsGuest(this))
            return;
        String[] choices = {
                "Efficient — 30 frames/sec, less work during setup",
                "Normal — use my existing refresh-rate setting"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Windows rendering")
                .setMessage("Efficient reduces unnecessary QEMU display updates. "
                        + "It may help heat and CPU usage but cannot accelerate "
                        + "the Windows installer or CPU itself. "
                        + "No VM configuration is changed.")
                .setSingleChoiceItems(choices,
                        WindowsDisplayOptions.efficient(this) ? 0 : 1,
                        (dialog, choice) -> {
                            WindowsDisplayOptions.setEfficient(this, choice == 0);
                            lastFramePollNanos = 0;
                            nativeRefreshRatePushed = false;
                            applyRefreshRate(effectiveGuestRefreshRate());
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setupScreen() {''')

# No UI change to non-Windows VMs; these menu items are removed there.
patch("main/QubeQGEActivity.java",
      '''        getMenuInflater().inflate(R.menu.qgeactivitymenu, menu);

        int maxMenuItemsShown = 4;''',
      '''        getMenuInflater().inflate(R.menu.qgeactivitymenu, menu);
        if (!WindowsDisplayOptions.isWindowsGuest(this)) {
            menu.removeItem(R.id.itemImmersiveWindows);
            menu.removeItem(R.id.itemWindowsScaling);
            menu.removeItem(R.id.itemWindowsRendering);
        }

        int maxMenuItemsShown = 4;''')

patch_res("res/menu/qgeactivitymenu.xml",
      '''    <item
        android:id="@+id/itemHideToolbar"''',
      '''    <item
        android:id="@+id/itemImmersiveWindows"
        android:icon="@drawable/toolbar_24px"
        android:title="Windows full screen (Back to exit)" />
    <item
        android:id="@+id/itemWindowsScaling"
        android:icon="@drawable/toolbar_24px"
        android:title="Windows fill / fit display" />
    <item
        android:id="@+id/itemWindowsRendering"
        android:icon="@drawable/refresh_24px"
        android:title="Windows rendering speed" />
    <item
        android:id="@+id/itemHideToolbar"''')

# One accessible "Setup Tools" control groups safe, opt-in guest OOBE
# shortcuts. QMP key delivery succeeds/fails separately from Windows
# actually accepting a command. These strings must not silently run at boot.
patch("virtualkeys/VirtualKey.java",
      '''    OfflineSetup(null, "Offline Account", 0, "Type Windows 11 local account setup command", false),''',
      '''    OfflineSetup(null, "Offline Account", 0, "Type Windows 11 local account setup command", false),
    SetupTools(null, "Setup Tools", 0, "Windows OOBE local-account and network options", false),
    NetworkCheck(null, "Net Check", 0, "Read-only QEMU network adapter report", false),''')

patch("virtualkeys/VirtualKeyLayoutConfig.java",
      '''VirtualKey.DiskCheck, VirtualKey.SetupBypass, VirtualKey.OfflineSetup, VirtualKey.LeftCtrl,''',
      '''VirtualKey.DiskCheck, VirtualKey.SetupBypass, VirtualKey.OfflineSetup, VirtualKey.SetupTools, VirtualKey.NetworkCheck, VirtualKey.LeftCtrl,''')

patch("virtualkeys/VirtualKeyLayoutConfig.java",
      '''VirtualKey.DiskCheck, VirtualKey.SetupBypass, VirtualKey.OfflineSetup)''',
      '''VirtualKey.DiskCheck, VirtualKey.SetupBypass, VirtualKey.OfflineSetup, VirtualKey.SetupTools, VirtualKey.NetworkCheck)''')

patch("virtualkeys/VirtualKeys.java",
      '''            } else if (vk == VirtualKey.OfflineSetup) {''',
      '''            } else if (vk == VirtualKey.SetupTools) {
                view.setOnClickListener(v -> showWindowsSetupTools());
            } else if (vk == VirtualKey.NetworkCheck) {
                view.setOnClickListener(v -> readWindowsNetworkReport());
            } else if (vk == VirtualKey.OfflineSetup) {''')

patch("virtualkeys/VirtualKeys.java",
      '''    private void initToggleKey(final ToggleButton key, final int keyCode) {''',
      r'''    private boolean isWindowsTarget() {
        return com.max2idea.android.qube.main.WindowsCpuMode.isTargetWindowsVM(
                activity,
                com.max2idea.android.qube.machine.MachineController
                        .getInstance().getMachine());
    }

    private void showWindowsSetupTools() {
        if (!isWindowsTarget()) {
            ToastUtils.toastShort(activity, "Setup Tools: Windows 11 ARM64 VM only");
            return;
        }
        String[] actions = {
                "Create local user — open CMD, try local-account command",
                "Offline fallback — set OOBE BypassNRO registry flag",
                "Restart Windows from Command Prompt (after confirmation)"
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Windows 11 Setup Tools")
                .setMessage("Use only during your own Windows 11 first-time setup. "
                        + "Local-account and NRO methods are build-dependent. "
                        + "Nothing runs until you select and confirm an action. "
                        + "No partitions or VM settings are modified.")
                .setItems(actions, (dialog, selected) ->
                        confirmWindowsSetupAction(selected))
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmWindowsSetupAction(int action) {
        final String description;
        if (action == 0) {
            description = "Open Command Prompt with Shift+F10, then send "
                    + "'start ms-cxh:localonly'. Windows may reject this shortcut.";
        } else if (action == 1) {
            description = "Open Command Prompt and add only the BypassNRO "
                    + "OOBE registry value. You must inspect the command result. "
                    + "Some Windows 11 builds ignore this setting.";
        } else if (action == 2) {
            description = "Open Command Prompt and request a Windows restart. "
                    + "This does not erase the QCOW2 but may interrupt unfinished "
                    + "Windows setup work. Use only after you intend to restart.";
        } else {
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Confirm Windows Setup action")
                .setMessage(description)
                .setPositiveButton("Send guest keys", (d, w) -> {
                    if (!isWindowsTarget()) {
                        ToastUtils.toastShort(activity, "Windows target no longer selected");
                        return;
                    }
                    ToastUtils.toastLong(activity, "Sending guest keys; avoid touching Windows.");
                    new Thread(() -> {
                        String command = action == 0 ? "start ms-cxh:localonly"
                                : action == 1
                                ? "reg add hklm\\software\\microsoft\\windows\\currentversion\\oobe /v bypassnro /t reg_dword /d 1 /f"
                                : "shutdown /r /t 0";
                        boolean sent = sendSetupHmpKey("shift-f10");
                        if (sent) sent = waitSetupInput(3500);
                        if (sent) sent = typeSetupCommand(command);
                        final boolean delivered = sent;
                        activity.runOnUiThread(() ->
                                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                        .setTitle(delivered
                                                ? "Keys delivered to QEMU"
                                                : "Key delivery stopped")
                                        .setMessage(delivered
                                                ? "Inspect the Windows screen. QEMU accepted "
                                                + "the keys, but the app cannot verify whether "
                                                + "Windows executed or supports this command. "
                                                + "Only continue if the guest shows the expected result."
                                                : "The keys were not completely delivered. "
                                                + "The app did not change its VM settings.")
                                        .setPositiveButton("OK", null)
                                        .show());
                    }, "WindowsSetupTools").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void readWindowsNetworkReport() {
        com.max2idea.android.qube.machine.Machine vm =
                com.max2idea.android.qube.machine.MachineController
                        .getInstance().getMachine();
        if (!isWindowsTarget() || vm == null)
            return;
        String configured = vm.getNetwork() == null ? "None" : vm.getNetwork();
        String adapter = vm.getNetworkCard() == null
                ? "Not selected" : vm.getNetworkCard();
        ToastUtils.toastShort(activity, "Reading QEMU network (read-only)...");
        new Thread(() -> {
            String pci = QmpClient.sendCommand("{\"execute\":\"query-pci\"}");
            String network = QmpClient.sendCommand(
                    "{\"execute\":\"human-monitor-command\",\"arguments\":"
                    + "{\"command-line\":\"info network\"}}");
            final String report = "Windows Mode read-only network report\n"
                    + "Selected QubeVM mode: " + configured + "\n"
                    + "Selected emulated adapter: " + adapter + "\n"
                    + "User mode is NAT; it does not guarantee Windows "
                    + "has a compatible ARM64 device driver.\n\n"
                    + "[query-pci]\n" + String.valueOf(pci) + "\n\n"
                    + "[HMP info network]\n" + String.valueOf(network);
            activity.runOnUiThread(() ->
                    new androidx.appcompat.app.AlertDialog.Builder(activity)
                            .setTitle("QEMU Network Check")
                            .setMessage("QubeVM selected: " + configured
                                    + "\nEmulated adapter: " + adapter
                                    + "\n\nThis verifies only the VM side. "
                                    + "Windows still needs an ARM64-compatible driver.")
                            .setPositiveButton("Copy Report", (d, w) -> {
                                android.content.ClipboardManager cb =
                                        (android.content.ClipboardManager)
                                        activity.getSystemService(
                                                android.content.Context.CLIPBOARD_SERVICE);
                                if (cb != null)
                                    cb.setPrimaryClip(android.content.ClipData
                                            .newPlainText("QEMU network", report));
                            })
                            .setNegativeButton("Close", null)
                            .show());
        }, "QubeNetworkCheck").start();
    }

    private void initToggleKey(final ToggleButton key, final int keyCode) {''')

# Gates: scoped modifications only and no silent Windows guest input.
surface=(java/"main/QubeQGESurface.java").read_text()
for key in ("WindowsDisplayOptions.FILL", "WindowsDisplayOptions.STRETCH",
            "updateScreenMatrix()", "inverse.mapPoints(pt)"):
    assert key in surface
activity=(java/"main/QubeQGEActivity.java").read_text()
for key in ("effectiveGuestRefreshRate()", "lastFramePollNanos",
            "itemImmersiveWindows", "itemWindowsScaling",
            "itemWindowsRendering", "if (immersiveWindow)"):
    assert key in activity
keys=(java/"virtualkeys/VirtualKeys.java").read_text()
for key in ("showWindowsSetupTools()", "readWindowsNetworkReport()",
            "start ms-cxh:localonly", "bypassnro", "confirmWindowsSetupAction"):
    assert key in keys
assert 'android:id="@+id/itemImmersiveWindows"' in (root/"res/menu/qgeactivitymenu.xml").read_text()
assert 'mNetConfig.setEnabled(true)' not in activity
assert 'setHdaImagePath' not in activity
assert 'setCpuNum(' not in activity
print("FULLSCREEN / RENDER / OOBE / NETWORK READ-ONLY CHECKS PASS")

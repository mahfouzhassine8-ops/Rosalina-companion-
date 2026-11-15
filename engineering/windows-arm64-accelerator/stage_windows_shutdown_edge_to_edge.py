#!/usr/bin/env python3
"""Stage Windows-only safe shutdown and adaptive edge-to-edge rendering.

Must run AFTER the six protected 7.1.14 repair scripts and the 7.1.15
optional WinJIT selector, against a DISPOSABLE pinned QubeVM 7.1.4 checkout.

Does not edit Windows/UEFI images, saved VM settings, QEMU device parameters,
native libraries or unrelated guests. Fullscreen uses Android view transforms;
it does NOT claim to resize Windows' RAM framebuffer or add GPU acceleration.
"""
from pathlib import Path
import sys

source = Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
java = source / "qube-android-lib/src/main/java/com/max2idea/android/qube"
activity = java / "main/QubeQGEActivity.java"
display = java / "main/WindowsDisplayOptions.java"

if not activity.is_file() or not display.is_file():
    raise RuntimeError("Pinned QubeVM Windows Mode source is missing")


def patch(file: Path, before: str, after: str) -> None:
    old = file.read_text()
    count = old.count(before)
    if count != 1:
        raise RuntimeError(f"{file.name}: expected one pinned anchor, got {count}: {before[:100]!r}")
    file.write_text(old.replace(before, after, 1))
    print(f"PASS: staged {file.name}: {before[:58]!r}")


# Preserve all earlier manual scaling selections. New Windows Mode installs
# default to edge-to-edge. Fit and Fill are still available if requested.
patch(display,
      "    public static final int STRETCH = 2;",
      "    public static final int STRETCH = 2;\n"
      "    public static final int AUTO_EDGE_TO_EDGE = 3;")
patch(display,
      ".getInt(SCALE, FIT);",
      ".getInt(SCALE, AUTO_EDGE_TO_EDGE);")
patch(display,
      "value >= FIT && value <= STRETCH ? value : FIT;",
      "value >= FIT && value <= AUTO_EDGE_TO_EDGE ? value : AUTO_EDGE_TO_EDGE;")
patch(display,
      "value < FIT || value > STRETCH",
      "value < FIT || value > AUTO_EDGE_TO_EDGE")

# AUTO uses a uniform scale if guest and host ratios already match. Otherwise
# it fills both dimensions so no bars remain and all taskbar edges stay visible.
# The fallback MAY distort aspect ratio; native matching requires separately
# qualified guest display hardware/ARM64 driver support.
surface = java / "main/QubeQGESurface.java"
patch(surface,
      "        if (mode != WindowsDisplayOptions.STRETCH) {",
      "        if (mode != WindowsDisplayOptions.STRETCH\n"
      "                && mode != WindowsDisplayOptions.AUTO_EDGE_TO_EDGE) {")
patch(activity,
      '                "Stretch — use all screen space (may distort)"',
      '                "Stretch — use all screen space (may distort)",\n'
      '                "Auto edge-to-edge — no bars; may stretch until Windows can match resolution"')

# Fullscreen is enabled when the WINDOWS guest opens, not all QubeVM guests.
# Back exits immersive mode and restores Android toolbar + virtual key controls.
patch(activity,
      "    private boolean toolbarBeforeImmersive = false;",
      "    private boolean toolbarBeforeImmersive = false;\n"
      "    private boolean virtualKeysBeforeImmersive = false;")
patch(activity,
      """            toolbarBeforeImmersive = action != null && action.isShowing();
            controller.hide(WindowInsetsCompat.Type.systemBars());""",
      """            toolbarBeforeImmersive = action != null && action.isShowing();
            virtualKeysBeforeImmersive = mVirtualKeys != null && mVirtualKeys.isShown();
            if (virtualKeysBeforeImmersive)
                mVirtualKeys.toggle();
            controller.hide(WindowInsetsCompat.Type.systemBars());""")
patch(activity,
      """            if (toolbarBeforeImmersive && action != null)
                action.show();
        }
        if (mSurface != null) {""",
      """            if (toolbarBeforeImmersive && action != null)
                action.show();
            if (virtualKeysBeforeImmersive && mVirtualKeys != null
                    && !mVirtualKeys.isShown())
                mVirtualKeys.toggle();
            virtualKeysBeforeImmersive = false;
        }
        if (mSurface != null) {""")
patch(activity,
      """        setupAudio();
        startMachineAndConnect();
        notifyAction(MachineAction.UPDATE_NOTIFICATION, getString(R.string.VMRunning));""",
      """        setupAudio();
        startMachineAndConnect();
        // Window geometry has been created. This does not modify Windows'
        // resolution, guest images, CPU, or any Android VM configuration.
        if (WindowsDisplayOptions.isWindowsGuest(this)) {
            getWindow().getDecorView().post(() -> {
                if (!isFinishing() && !isDestroyed() && !immersiveWindow)
                    toggleWindowsImmersive();
            });
        }
        notifyAction(MachineAction.UPDATE_NOTIFICATION, getString(R.string.VMRunning));""")
patch(activity,
      """        if (hasFocus && gfxLoopRunning) {
            applyRefreshRate(effectiveGuestRefreshRate());
        }""",
      """        if (hasFocus && immersiveWindow) {
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                    .hide(WindowInsetsCompat.Type.systemBars());
            ActionBar bar = getSupportActionBar();
            if (bar != null) bar.hide();
        }
        if (hasFocus && gfxLoopRunning) {
            applyRefreshRate(effectiveGuestRefreshRate());
        }""")

# QubeVM exposes the QMP system_powerdown command but the old menu goes to
# STOP_VM (hard stop). Use the existing ACPI request on a worker thread.
# Do not claim Windows shut down merely because QMP acknowledged the request.
# No auto force-stop, no timeout that kills Windows, no guest command injection.
assert "import com.max2idea.android.qube.qmp.QmpClient;" in activity.read_text()
patch(activity,
      """        } else if (item.getItemId() == R.id.itemShutdown) {
            KeyboardUtils.hideKeyboard(this, mSurface);
            QubeActivityCommon.promptStopVM(this, viewListener);""",
      """        } else if (item.getItemId() == R.id.itemShutdown) {
            KeyboardUtils.hideKeyboard(this, mSurface);
            if (WindowsDisplayOptions.isWindowsGuest(this))
                confirmWindowsGracefulShutdown();
            else
                QubeActivityCommon.promptStopVM(this, viewListener);""")
patch(activity,
      "    private void showWindowsRendering() {",
      """    private volatile boolean windowsGracefulStopPending = false;

    private void confirmWindowsGracefulShutdown() {
        if (!MachineController.getInstance().isRunning()) {
            ToastUtils.toastShort(this, "VM is not running");
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Shut down Windows safely")
                .setMessage("Ask Windows to shut itself down through QEMU ACPI. "
                        + "Keep the VM running while Windows saves and closes. "
                        + "This is NOT a force stop. If Windows does not support "
                        + "the ACPI request, the VM will stay running.")
                .setPositiveButton("Request safe shutdown",
                        (d, which) -> requestWindowsGracefulShutdown())
                .setNeutralButton("Force stop (unsafe)", (d, which) ->
                        QubeActivityCommon.promptStopVM(this, viewListener))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void requestWindowsGracefulShutdown() {
        if (windowsGracefulStopPending || !WindowsDisplayOptions.isWindowsGuest(this))
            return;
        if (!QubeSettingsManager.getEnableQmp(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Safe shutdown unavailable")
                    .setMessage("QEMU QMP is disabled. Enable QMP in VM settings "
                            + "before requesting ACPI shutdown. Nothing has been stopped.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        windowsGracefulStopPending = true;
        new Thread(() -> {
            String response = QmpClient.sendCommand(QmpClient.getPowerDownCommand());
            boolean accepted = response != null
                    && response.contains("\\"return\\"")
                    && !response.contains("\\"error\\"");
            runOnUiThread(() -> {
                windowsGracefulStopPending = false;
                if (isFinishing() || isDestroyed())
                    return;
                new MaterialAlertDialogBuilder(this)
                        .setTitle(accepted ? "Shutdown requested" : "Shutdown request failed")
                        .setMessage(accepted
                                ? "QEMU acknowledged the Windows ACPI power-button "
                                  + "request. Wait until Windows actually stops. "
                                  + "Acknowledgment alone does not mean shutdown is complete."
                                : "The QEMU power-down command was not acknowledged. "
                                  + "Windows has NOT been force-stopped. Verify QMP "
                                  + "and guest ACPI support before trying again.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "WindowsMode-ACPI-Shutdown").start();
    }

    private void showWindowsRendering() {""")

s = activity.read_text()
d = display.read_text()
v = surface.read_text()
assert "QmpClient.getPowerDownCommand()" in s
assert "QubeActivityCommon.promptStopVM(this, viewListener)" in s
assert "if (WindowsDisplayOptions.isWindowsGuest(this))" in s
assert "virtualKeysBeforeImmersive" in s
assert "getWindow().getDecorView().post" in s
assert "AUTO_EDGE_TO_EDGE" in d and "AUTO_EDGE_TO_EDGE" in v
assert not any(x in s for x in ["setHdaImagePath(", "setCpuNum(", "setMemory("])
print("PASS: Windows-only safe ACPI shutdown and adaptive no-bars display staged")

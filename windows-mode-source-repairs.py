#!/usr/bin/env python3
"""Scoped source edits for Windows Mode 7.1.6, applied to pinned QubeVM 7.1.4 checkout."""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "upstream") / "qube-android-lib/src/main/java/com/max2idea/android/qube"

def patch(path, before, after, count=1):
    f = root / path
    text = f.read_text()
    seen = text.count(before)
    if seen != count:
        raise RuntimeError(f"Unexpected {path} source; {before[:45]!r} found {seen}, expected {count}")
    f.write_text(text.replace(before, after))
    print(f"PATCH {path}: {count} verified anchor(s)")

# When the base directory was newly created, upstream reported failure and returned early.
# The machine folder block incorrectly tried to create the base folder a second time.
patch("files/FileInstaller.java",
    '''if (!tmpDir.exists()) {
            if(tmpDir.mkdirs()) {
                ToastUtils.toastShort(activity, activity.getString(R.string.CouldNotCreateBaseDir) + ": " + tmpDir.getPath());
                return;
            }
        }''',
    '''if (!tmpDir.exists()) {
            if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) {
                ToastUtils.toastShort(activity, activity.getString(R.string.CouldNotCreateBaseDir) + ": " + tmpDir.getPath());
                return;
            }
        }''')
patch("files/FileInstaller.java",
    '''if (!tmpDir1.exists()) {
            if(tmpDir.mkdirs()) {
                ToastUtils.toastShort(activity, activity.getString(R.string.CouldNotCreateMachineDir) + ": " + tmpDir.getPath());
            }
        }''',
    '''if (!tmpDir1.exists()) {
            if (!tmpDir1.mkdirs() && !tmpDir1.isDirectory()) {
                ToastUtils.toastShort(activity, activity.getString(R.string.CouldNotCreateMachineDir) + ": " + tmpDir1.getPath());
                return;
            }
        }''')
# Re-extract missing/empty cached ROM/keymap files; do not modify VM disk or shared folder.
patch("files/FileInstaller.java", "if(!file.exists() || force) {",
      "if(!file.isFile() || file.length() == 0 || force) {", 2)

# Supply dedicated keys so Samsung's soft IME doesn't have to generate firmware Enter/Space.
patch("virtualkeys/VirtualKey.java",
      "    Tab(KeyEvent.KEYCODE_TAB, null, 0, null, false),",
      """    Tab(KeyEvent.KEYCODE_TAB, null, 0, null, false),
    Enter(KeyEvent.KEYCODE_ENTER, null, 0, null, false),
    Space(KeyEvent.KEYCODE_SPACE, null, 0, null, false),
    SetupCmd(null, "Shift+F10", 0, "Windows Setup command prompt", false),""")
patch("virtualkeys/VirtualKeyLayoutConfig.java",
      "VirtualKey.Tab, VirtualKey.LeftCtrl, VirtualKey.LeftShift, VirtualKey.LeftAlt,",
      "VirtualKey.Tab, VirtualKey.Enter, VirtualKey.Space, VirtualKey.SetupCmd, VirtualKey.LeftCtrl, VirtualKey.LeftShift, VirtualKey.LeftAlt,")
patch("virtualkeys/VirtualKeyLayoutConfig.java",
      '''                if (!keys.isEmpty())
                    return keys;''',
      '''                if (!keys.isEmpty()) {
                    for (VirtualKey essential : Arrays.asList(VirtualKey.Enter, VirtualKey.Space, VirtualKey.SetupCmd)) {
                        if (!keys.contains(essential))
                            keys.add(essential);
                    }
                    return keys;
                }''')
# The prebuilt native QEMU engine mishandles Shift+F10. The dedicated shortcut uses
# QMP's local Unix socket, not external network access, as a narrow workaround.
patch("virtualkeys/VirtualKeys.java",
      "import com.max2idea.android.qube.keyboard.KeyboardUtils;",
      """import com.max2idea.android.qube.keyboard.KeyboardUtils;
import com.max2idea.android.qube.qmp.QmpClient;
import com.max2idea.android.qube.toast.ToastUtils;""")
patch("virtualkeys/VirtualKeys.java",
      '''            } else if (vk.keyCode != null) {
                if (view instanceof ToggleButton)''',
      r'''            } else if (vk == VirtualKey.SetupCmd) {
                view.setOnClickListener(v -> new Thread(() -> {
                    String response = QmpClient.sendCommand(
                            "{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\"sendkey shift-f10\"}}");
                    if (response == null || !response.contains("return"))
                        activity.runOnUiThread(() -> ToastUtils.toastShort(activity,
                                "Setup shortcut unavailable: check internal QMP"));
                }).start());
            } else if (vk.keyCode != null) {
                if (view instanceof ToggleButton)''')

# A USB tablet is an absolute pointer. Starting the display in Trackpad (REL)
# mode sends mouse events to a device that cannot receive them on QEMU ARM virt.
patch("main/QubeQGEActivity.java",
      '''    private void setupUserInterface() {
        Config.mouseButtonDelay = QubeSettingsManager.getMouseButtonDelay(this);
    }''',
      '''    private void setupUserInterface() {
        Config.mouseButtonDelay = QubeSettingsManager.getMouseButtonDelay(this);
        Machine vm = MachineController.getInstance().getMachine();
        String device = vm == null ? null : vm.getMouse();
        mouseMode = device != null && device.contains("tablet")
                ? MouseMode.TOUCHSCREEN : MouseMode.Trackpad;
    }''')
patch("main/QubeQGEActivity.java",
      "    private boolean relativePointerMode = (mouseMode == MouseMode.Trackpad);",
      '''    private boolean relativePointerMode = (mouseMode == MouseMode.Trackpad);

    private boolean usesTabletDevice() {
        Machine vm = MachineController.getInstance().getMachine();
        return vm != null && vm.getMouse() != null && vm.getMouse().contains("tablet");
    }''')
patch("main/QubeQGEActivity.java",
      '''        if (relativePointerMode) {
            QubeInput.nativeSendPointerEventRel(0, 0, pointerButtonMask);''',
      '''        if (relativePointerMode && !usesTabletDevice()) {
            QubeInput.nativeSendPointerEventRel(0, 0, pointerButtonMask);''')
patch("main/QubeQGEActivity.java",
      '''    public synchronized void sendRelativeMove(float dx, float dy) {
        if (!machineRunning)
            return;
        relativePointerMode = true;''',
      '''    public synchronized void sendRelativeMove(float dx, float dy) {
        if (!machineRunning)
            return;
        if (usesTabletDevice()) {
            relativePointerMode = false;
            cursorX = clamp(cursorX + dx, 0, QubeGfx.nativeGetWidth() - 1);
            cursorY = clamp(cursorY + dy, 0, QubeGfx.nativeGetHeight() - 1);
            sendPointerEvent();
            return;
        }
        relativePointerMode = true;''')

# Fail closed if a required patch is missing. Build compilation then tests the Java code.
for file_name, checks in {
    "files/FileInstaller.java": ("!tmpDir.mkdirs()", "!tmpDir1.mkdirs()", "file.length() == 0"),
    "main/QubeQGEActivity.java": ("usesTabletDevice()", "MouseMode.TOUCHSCREEN"),
    "virtualkeys/VirtualKey.java": ("KEYCODE_ENTER", "KEYCODE_SPACE", 'SetupCmd(null'),
    "virtualkeys/VirtualKeys.java": ("sendkey shift-f10", "QmpClient.sendCommand("),
}.items():
    body = (root / file_name).read_text()
    for needle in checks:
        if needle not in body:
            raise AssertionError(f"Missing {needle!r} in {file_name}")
print("ALL SOURCE PATCH ANCHORS PASS; no VM disk files were accessed")

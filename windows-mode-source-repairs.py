#!/usr/bin/env python3
"""Scoped source edits for Windows Mode 7.1.9, applied to pinned QubeVM 7.1.4 checkout."""
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
# Read-only QMP snapshot: do not change the VM, controller, Windows ISO, or QCOW2.
# The upstream qemu-xhci and AHCI device wiring is preserved exactly.
patch("virtualkeys/VirtualKey.java",
      '    SetupCmd(null, "Shift+F10", 0, "Windows Setup command prompt", false),',
      '''    SetupCmd(null, "Shift+F10", 0, "Windows Setup command prompt", false),
    DiskCheck(null, "Disk Check", 0, "Read-only QEMU storage report", false),''')
patch("virtualkeys/VirtualKeyLayoutConfig.java",
      'VirtualKey.Space, VirtualKey.SetupCmd, VirtualKey.LeftCtrl,',
      'VirtualKey.Space, VirtualKey.SetupCmd, VirtualKey.DiskCheck, VirtualKey.LeftCtrl,')
patch("virtualkeys/VirtualKeyLayoutConfig.java",
      "Arrays.asList(VirtualKey.Enter, VirtualKey.Space, VirtualKey.SetupCmd)",
      "Arrays.asList(VirtualKey.Enter, VirtualKey.Space, VirtualKey.SetupCmd, VirtualKey.DiskCheck)")
patch("virtualkeys/VirtualKeys.java",
      '''            } else if (vk.keyCode != null) {
                if (view instanceof ToggleButton)''',
      r'''            } else if (vk == VirtualKey.DiskCheck) {
                view.setOnClickListener(v -> {
                    ToastUtils.toastShort(activity, "Reading QEMU storage (read-only)...");
                    new Thread(() -> {
                        String block = QmpClient.sendCommand("{\"execute\":\"query-block\"}");
                        String nodes = QmpClient.sendCommand("{\"execute\":\"query-named-block-nodes\"}");
                        String pci = QmpClient.sendCommand("{\"execute\":\"query-pci\"}");
                        String stats = QmpClient.sendCommand("{\"execute\":\"query-blockstats\"}");
                        String tree = QmpClient.sendCommand(
                                "{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\"info qtree\"}}");
                        String summary;
                        if (block == null || block.trim().isEmpty() || block.contains("\"error\"")) {
                            summary = "QMP query-block unavailable: inspect full report";
                        } else {
                            summary = block.contains("hd0")
                                    ? "Hard Disk A (hd0): FOUND in QMP"
                                    : "Hard Disk A (hd0): NOT LISTED by QMP";
                        }
                        String report = "QubeVM read-only storage diagnostic\n"
                                + "No disk, VM, firmware, or OS settings changed.\n\n"
                                + "[query-block]\n" + String.valueOf(block) + "\n"
                                + "[query-named-block-nodes]\n" + String.valueOf(nodes) + "\n"
                                + "[query-pci]\n" + String.valueOf(pci) + "\n"
                                + "[query-blockstats]\n" + String.valueOf(stats) + "\n"
                                + "[HMP info qtree]\n" + String.valueOf(tree) + "\n";
                        final String status = summary;
                        activity.runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(activity)
                                .setTitle("QEMU Disk Check")
                                .setMessage(status + "\n\nCopy the report to share the detailed result. File paths may be included.")
                                .setPositiveButton("Copy Report", (d, w) -> {
                                    android.content.ClipboardManager clipboard =
                                            (android.content.ClipboardManager) activity.getSystemService(
                                                    android.content.Context.CLIPBOARD_SERVICE);
                                    if (clipboard != null)
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                                "QubeVM disk diagnostic", report));
                                })
                                .setNegativeButton("Close", null)
                                .show());
                    }, "QubeDiskCheck").start();
                });
            } else if (vk.keyCode != null) {
                if (view instanceof ToggleButton)''')
for file_name, checks in {
    "virtualkeys/VirtualKey.java": ("DiskCheck(null",),
    "virtualkeys/VirtualKeys.java": ("query-block", "query-named-block-nodes", "query-pci",
                                      "query-blockstats", "info qtree", "Copy Report"),
    "virtualkeys/VirtualKeyLayoutConfig.java": ("VirtualKey.DiskCheck",),
}.items():
    content = (root / file_name).read_text()
    for needle in checks:
        assert needle in content, f"missing {needle!r} in {file_name}"
assert "win11-arm64-drivers.iso" not in (root / "jni/VMExecutor.java").read_text()
print("READ-ONLY STORAGE DIAGNOSTIC PATCH PASS: no QEMU storage arguments modified")

# Windows 11 ARM64 WinPE has no inbox VirtIO block storage driver.
# The prior Win11 ISO was on QEMU if=virtio and could boot via UEFI but was
# inaccessible to Windows Setup. Attach ONLY this Windows installer ISO through
# an emulated USB mass-storage CD-ROM. Keep the existing QCOW2/AHCI arrangement,
# the configured CD selection, other VMs, and all unrelated behavior unchanged.
patch("jni/VMExecutor.java",
      '''            String cdInterface = getMachine().getCDInterface();
            if ("ahci".equals(cdInterface)) {''',
      '''            String cdInterface = getMachine().getCDInterface();
            String selectedIso = getMachine().getCdImagePath();
            String selectedIsoLower = selectedIso == null ? ""
                    : selectedIso.toLowerCase(java.util.Locale.ROOT);
            boolean windowsArmSetupIsoViaVirtio =
                    QubeApplication.arch == Config.Arch.arm64
                    && "com.fold.windowsmode.research".equals(
                            QubeApplication.getInstance().getPackageName())
                    && "virtio".equals(cdInterface)
                    && selectedIsoLower.endsWith(".iso")
                    && (selectedIsoLower.contains("windows11")
                        || selectedIsoLower.contains("win11"));
            if (windowsArmSetupIsoViaVirtio) {
                // Windows Setup must see its install media after UEFI hands off.
                // No changes to Hard Disk A, its AHCI controller or its image.
                paramsList.add("-drive");
                paramsList.add("if=none,id=" + cdDeviceName
                        + ",format=raw,readonly=on,media=cdrom,file="
                        + qemuOpt(cdImagePath));
                addUsbController(paramsList);
                paramsList.add("-device");
                paramsList.add("usb-storage,drive=" + cdDeviceName
                        + ",bus=" + USB_ID + ".0,removable=on");
                Log.i(TAG, "Windows 11 ARM64 setup ISO exposed over USB CD-ROM; "
                        + "saved VM configuration and QCOW2 unchanged");
            } else if ("ahci".equals(cdInterface)) {''')
executor = (root / "jni/VMExecutor.java").read_text()
for required in ('"virtio".equals(cdInterface)', 'selectedIsoLower.contains("windows11")',
                 'usb-storage,drive=', 'media=cdrom,file=', 'readonly=on',
                 'if (windowsArmSetupIsoViaVirtio)', 'else if ("ahci".equals(cdInterface))'):
    assert required in executor, f"missing Win11 USB installer repair: {required}"
# An intentional narrow fix: no disk-interface override, no firmware changes.
assert 'getMachine().getHdaInterface()' in executor
assert 'ide-hd,drive=' in executor
assert 'win11-arm64-drivers.iso' not in executor
print("WIN11 ISO USB CD-ROM PATCH PASS; QCOW2 and AHCI wiring untouched")


# Optional, explicitly user-triggered Windows 11 ARM64 Setup shortcut.
# Runs ONLY while a Windows 11 install ISO is attached to this research app.
# QEMU's existing local QMP HMP sendkey path is used because the Samsung IME's
# Send Text action dropped long strings. No VM media, disk, firmware or settings
# are changed. The two values belong to the temporary WinPE registry.
patch("virtualkeys/VirtualKey.java",
      '    DiskCheck(null, "Disk Check", 0, "Read-only QEMU storage report", false),',
      '''    DiskCheck(null, "Disk Check", 0, "Read-only QEMU storage report", false),
    SetupBypass(null, "Win11 Bypass", 0, "Type TPM/Secure Boot setup registry commands", false),''')
patch("virtualkeys/VirtualKeyLayoutConfig.java",
      "VirtualKey.Space, VirtualKey.SetupCmd, VirtualKey.DiskCheck, VirtualKey.LeftCtrl,",
      "VirtualKey.Space, VirtualKey.SetupCmd, VirtualKey.DiskCheck, VirtualKey.SetupBypass, VirtualKey.LeftCtrl,")
patch("virtualkeys/VirtualKeyLayoutConfig.java",
      "Arrays.asList(VirtualKey.Enter, VirtualKey.Space, VirtualKey.SetupCmd, VirtualKey.DiskCheck)",
      "Arrays.asList(VirtualKey.Enter, VirtualKey.Space, VirtualKey.SetupCmd, VirtualKey.DiskCheck, VirtualKey.SetupBypass)")

patch("virtualkeys/VirtualKeys.java",
      '''            } else if (vk == VirtualKey.DiskCheck) {''',
      r'''            } else if (vk == VirtualKey.SetupBypass) {
                view.setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle("Windows 11 Setup compatibility")
                        .setMessage("On the Windows 11 TPM/Secure Boot warning screen, "
                                + "this will open Command Prompt and send only two "
                                + "LabConfig registry commands through QEMU keyboard input. "
                                + "Your virtual disk and machine settings are not changed. "
                                + "This bypasses Windows 11's hardware checks; the guest remains unsupported.")
                        .setPositiveButton("Send bypass commands", (dialog, which) -> {
                            com.max2idea.android.qube.machine.Machine vm =
                                    com.max2idea.android.qube.machine.MachineController
                                            .getInstance().getMachine();
                            String iso = vm == null ? "" : String.valueOf(vm.getCdImagePath());
                            String lowerIso = iso.toLowerCase(java.util.Locale.ROOT);
                            if (com.max2idea.android.qube.main.QubeApplication.arch
                                        != com.max2idea.android.qube.main.Config.Arch.arm64
                                    || !(lowerIso.contains("windows11") || lowerIso.contains("win11"))
                                    || !lowerIso.endsWith(".iso")) {
                                ToastUtils.toastLong(activity,
                                        "Only available for Windows 11 ARM64 installer ISO");
                                return;
                            }
                            ToastUtils.toastLong(activity, "Sending setup commands. "
                                    + "Keep Windows in the foreground; do not tap guest controls.");
                            new Thread(() -> {
                                boolean ok = sendSetupHmpKey("shift-f10");
                                if (ok) ok = waitSetupInput(3500);
                                if (ok) ok = typeSetupCommand(
                                        "reg add hklm\\system\\setup\\labconfig "
                                        + "/v bypasstpmcheck /t reg_dword /d 1 /f");
                                if (ok) ok = waitSetupInput(1200);
                                if (ok) ok = typeSetupCommand(
                                        "reg add hklm\\system\\setup\\labconfig "
                                        + "/v bypasssecurebootcheck /t reg_dword /d 1 /f");
                                final boolean sent = ok;
                                activity.runOnUiThread(() ->
                                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                                .setTitle(sent
                                                        ? "Windows Setup commands sent"
                                                        : "Windows Setup command delivery failed")
                                                .setMessage(sent
                                                        ? "Check the Command Prompt for TWO "
                                                        + "'The operation completed successfully' messages. "
                                                        + "The app cannot verify guest registry changes. "
                                                        + "If both succeeded, close Command Prompt, "
                                                        + "select Back, and continue Windows Setup. "
                                                        + "Do not format or delete the 64 GB disk yet."
                                                        : "QMP key delivery failed or was interrupted. "
                                                        + "Do not assume the bypass was applied; "
                                                        + "check Command Prompt before continuing.")
                                                .setPositiveButton("OK", null)
                                                .show());
                            }, "QubeWin11SetupBypass").start();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
            } else if (vk == VirtualKey.DiskCheck) {''')
patch("virtualkeys/VirtualKeys.java",
      '''    private void initToggleKey(final ToggleButton key, final int keyCode) {''',
      r'''    // Send HMP keys through the same QMP socket as the already-working Shift+F10.
    // Each key must be released before the next key is sent, especially for
    // USB keyboard input under TCG. Never report command execution as verified.
    private static boolean sendSetupHmpKey(String keyName) {
        String qmp = "{\"execute\":\"human-monitor-command\",\"arguments\":"
                + "{\"command-line\":\"sendkey " + keyName + " 20\"}}";
        String result = QmpClient.sendCommand(qmp);
        if (result == null || !result.contains("\"return\"") || result.contains("\"error\""))
            return false;
        return waitSetupInput(100);
    }

    private static boolean waitSetupInput(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean typeSetupCommand(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String keyName;
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                keyName = String.valueOf(ch);
            } else if (ch == ' ') {
                keyName = "spc";
            } else if (ch == '\\') {
                keyName = "backslash";
            } else if (ch == '/') {
                keyName = "slash";
            } else if (ch == '_') {
                keyName = "shift-minus";
            } else {
                return false;
            }
            if (!sendSetupHmpKey(keyName))
                return false;
        }
        return sendSetupHmpKey("ret");
    }

    private void initToggleKey(final ToggleButton key, final int keyCode) {''')
for file_name, checks in {
    "virtualkeys/VirtualKey.java": ("SetupBypass(null",),
    "virtualkeys/VirtualKeyLayoutConfig.java": ("VirtualKey.SetupBypass",),
    "virtualkeys/VirtualKeys.java": ("sendSetupHmpKey", "typeSetupCommand",
                                    "bypasstpmcheck", "bypasssecurebootcheck",
                                    "spc", "backslash", "shift-minus",
                                    "sendkey ", "the app cannot verify".replace("the", "The")),
}.items():
    content = (root / file_name).read_text()
    for check in checks:
        assert check in content, f"Setup bypass anchor absent {check!r}: {file_name}"
print("WIN11 SETUP BYPASS PATCH PASS: explicit action; no VM disk I/O or storage change")

print("ALL SOURCE PATCH ANCHORS PASS; no VM disk files were accessed")

#!/usr/bin/env python3
"""Scope-limited optional WinJIT selector for protected Windows Mode 7.1.14.

Applies ONLY to a disposable QubeVM source tree AFTER the existing protected
Windows Mode repair scripts, not to the installed APK, guest disk or firmware.
Default remains the original TCG engine. This source stage is NOT APK release
approval and does not claim Windows boot success.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/qemu-source-staging")
base = root/"qube-android-lib/src/main/java/com/max2idea/android/qube"
activity = base/"main/QubeActivity.java"
executor = base/"jni/VMExecutor.java"
mode = base/"main/WindowsWinjitMode.java"


def patch(path, before, after, count=1):
    s = path.read_text()
    found = s.count(before)
    if found != count:
        raise RuntimeError(f"{path}: expected {count} pinned anchors, got {found}: {before[:140]!r}")
    path.write_text(s.replace(before, after))
    print(f"PASS: safe WinJIT Android source change in {path.name}")


if mode.exists():
    raise RuntimeError("WinJIT preference file already exists; not overwriting")

for p in (activity,executor,base/"main/WindowsCpuMode.java"):
    if not p.is_file():
        raise RuntimeError(f"Missing protected Windows 7.1.14 prerequisite: {p}")

# Java preference tied to the already protected Windows guest allowlist.
mode.write_text(r'''package com.max2idea.android.qube.main;

import android.content.Context;
import com.max2idea.android.qube.machine.Machine;

public final class WindowsWinjitMode {
    private static final String PREF = "windows_mode_winjit_runtime";
    private static final String ENABLED = "enabled";
    private WindowsWinjitMode() {}

    public static boolean isTargetWindowsVM(Context context, Machine machine) {
        return WindowsCpuMode.isTargetWindowsVM(context, machine);
    }
    public static boolean enabled(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(ENABLED, false); // Explicit opt-in only. Default TCG.
    }
    public static void setEnabled(Context context, boolean value) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(ENABLED, value).apply();
    }
    public static boolean shouldUse(Context context, Machine machine) {
        return isTargetWindowsVM(context, machine)
                && machine.getEnableKVM() == 0 && enabled(context);
    }
}
''')

patch(executor,
      "import com.max2idea.android.qube.main.WindowsCpuMode;",
      "import com.max2idea.android.qube.main.WindowsCpuMode;\nimport com.max2idea.android.qube.main.WindowsWinjitMode;")

patch(executor,
      "            paramsList.add(tcgParams);",
      '''            if (WindowsWinjitMode.shouldUse(
                    QubeApplication.getInstance(), getMachine())) {
                paramsList.add("winjit");
                Log.w(TAG, "EXPERIMENTAL Windows WinJIT selected on user-supplied "
                        + "test VM. Boot and compatibility NOT YET VERIFIED.");
            } else {
                paramsList.add(tcgParams); // Preserve stable TCG by default.
            }''')

patch(activity,
      "    private static final int WINDOWS_CPU_MODE = 17;",
      "    private static final int WINDOWS_CPU_MODE = 17;\n    private static final int WINDOWS_WINJIT_MODE = 18;")

patch(activity,
      '''            menu.add(0, WINDOWS_CPU_MODE, 0, "Windows CPU Mode")
                    .setIcon(R.drawable.settings_24px);''',
      '''            menu.add(0, WINDOWS_CPU_MODE, 0, "Windows CPU Mode")
                    .setIcon(R.drawable.settings_24px);
            menu.add(0, WINDOWS_WINJIT_MODE, 0, "WinJIT (experimental)")
                    .setIcon(R.drawable.settings_24px);''')

patch(activity,
      '''        } else if (item.getItemId() == WINDOWS_CPU_MODE) {
            showWindowsCpuMode();''',
      '''        } else if (item.getItemId() == WINDOWS_CPU_MODE) {
            showWindowsCpuMode();
        } else if (item.getItemId() == WINDOWS_WINJIT_MODE) {
            showWindowsWinjitMode();''')

patch(activity,
      "    private void showWindowsCpuMode() {",
      '''    private void showWindowsWinjitMode() {
        if (MachineController.getInstance().isRunning()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("WinJIT requires shutdown")
                    .setMessage("Shut down the guest before switching engines.")
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        if (!WindowsWinjitMode.isTargetWindowsVM(this, getMachine())) return;
        final String[] modes = {
                "TCG — existing compatible engine (default)",
                "WinJIT — unverified research engine (test disk copy only)"
        };
        boolean test = WindowsWinjitMode.enabled(this);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Windows 11 ARM64 — Execution Engine")
                .setMessage("WinJIT is experimental. Use only a separate copy "
                        + "of your Windows VM disk and UEFI VARS. It has not "
                        + "passed Windows boot compatibility. Stable TCG "
                        + "remains available at all times. Existing VM "
                        + "configurations and storage are not changed.")
                .setSingleChoiceItems(modes, test ? 1 : 0, (dialog, which) -> {
                    WindowsWinjitMode.setEnabled(this, which == 1);
                    dialog.dismiss();
                    ToastUtils.toastShort(this, which == 1
                            ? "Experimental WinJIT selected for next boot"
                            : "Stable TCG restored for next boot");
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showWindowsCpuMode() {''')

# Release blockers cannot be bypassed by a Java setting. This gate verifies
# we have not touched storage, network, existing VM configuration or package.
for file in (activity, executor, mode):
    assert file.is_file()
assert 'WindowsWinjitMode.shouldUse' in executor.read_text()
assert 'paramsList.add(tcgParams)' in executor.read_text()
assert 'paramsList.add("winjit")' in executor.read_text()
assert 'getEnableKVM() == 0' in mode.read_text()
assert 'getBoolean(ENABLED, false)' in mode.read_text()
for bad in ('setHdaImagePath(', 'setCdImagePath(', 'setCpuNum(', 'setMemory('):
    assert bad not in mode.read_text(), bad
print("PASS: reversible Windows-only opt-in, original TCG default. NO APK RELEASE AUTHORIZED.")

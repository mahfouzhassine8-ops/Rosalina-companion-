#!/usr/bin/env python3
"""Windows Mode 7.1.11 -- safe, reversible Windows 11 ARM64 MTTCG policy.

Run after windows-mode-source-repairs.py and windows-mode-ui-repairs.py
against the pinned QubeVM 7.1.4 checkout.

Only modifies QEMU launch arguments for Windows Mode's named Win11 ARM64
guest with 2+ already configured vCPUs. No changes to the VM database,
virtual CPU count/model, RAM, disk, storage controller, ISO, UEFI, or
any unrelated VM. A Windows-only menu switch restores original behavior.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else "upstream")/"qube-android-lib/src/main/java/com/max2idea/android/qube"

def patch(relative, old, new, count=1):
    target=root/relative
    body=target.read_text()
    found=body.count(old)
    if found!=count:
        raise RuntimeError(f"Unexpected {relative}: anchor {old[:55]!r} found {found}, expected {count}")
    target.write_text(body.replace(old,new))
    print("PERF PATCH",relative)

policy=root/"main/WindowsCpuMode.java"
if policy.exists():
    raise RuntimeError("Refusing to overwrite existing WindowsCpuMode")
policy.write_text(r'''/*
 * Copyright (C) 2026 Windows Mode contributors
 * QubeVM GPL-3.0 derivative; Windows 11 guest-specific runtime CPU policy.
 */
package com.max2idea.android.qube.main;

import android.content.Context;
import com.max2idea.android.qube.machine.Machine;
import java.util.Locale;

public final class WindowsCpuMode {
    private static final String PREF_FILE = "windows_mode_cpu_runtime";
    private static final String PREF_FAST = "windows11_multithreaded_tcg";
    private static final String WINDOWS_PACKAGE = "com.fold.windowsmode.research";

    private WindowsCpuMode() {}

    private static boolean windowsName(String value) {
        if (value == null)
            return false;
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("windows11") || s.contains("windows 11")
                || s.contains("win11");
    }

    public static boolean isTargetWindowsVM(Context context, Machine vm) {
        if (context == null || vm == null
                || !WINDOWS_PACKAGE.equals(context.getPackageName())
                || QubeApplication.arch != Config.Arch.arm64)
            return false;
        String disk = vm.getHdaImagePath();
        String iso = vm.getCdImagePath();
        boolean diskMatch = windowsName(disk)
                && disk.toLowerCase(Locale.ROOT).endsWith(".qcow2");
        boolean isoMatch = windowsName(iso)
                && iso.toLowerCase(Locale.ROOT).endsWith(".iso");
        return diskMatch || isoMatch;
    }

    public static boolean enabled(Context context) {
        // Fast is enabled by default for a matching Windows 11 ARM64 VM.
        // This does not edit the saved machine MTTCG setting.
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(PREF_FAST, true);
    }

    public static void setEnabled(Context context, boolean fast) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_FAST, fast).apply();
    }

    public static boolean shouldForceMultiThread(Context context, Machine vm) {
        return isTargetWindowsVM(context, vm)
                && enabled(context)
                && vm.getCpuNum() > 1
                && vm.getEnableKVM() == 0;
    }
}
''')
print("PERF NEW main/WindowsCpuMode.java")

patch("jni/VMExecutor.java",
      "import com.max2idea.android.qube.main.Config;",
      "import com.max2idea.android.qube.main.Config;\nimport com.max2idea.android.qube.main.WindowsCpuMode;")
patch("jni/VMExecutor.java",
      '''            if (getMachine().getEnableMTTCG() != 0) {
                tcgParams += ",thread=multi";
            } else {
                tcgParams += ",thread=single";
            }''',
      '''            // Windows Mode: use separate host threads for already configured
            // Windows 11 ARM64 guest vCPUs; does not change VM's saved settings.
            boolean fastWin11 = WindowsCpuMode.shouldForceMultiThread(
                    QubeApplication.getInstance(), getMachine());
            if (getMachine().getEnableMTTCG() != 0 || fastWin11) {
                tcgParams += ",thread=multi";
            } else {
                tcgParams += ",thread=single";
            }
            if (WindowsCpuMode.isTargetWindowsVM(
                    QubeApplication.getInstance(), getMachine())) {
                Log.i(TAG, "Windows CPU runtime: " + (fastWin11
                        ? "fast MTTCG" : "saved/compatibility mode")
                        + ", vCPUs=" + getMachine().getCpuNum()
                        + ", selected threads=" + tcgParams
                        + "; stored VM settings unchanged");
            }''')

patch("main/QubeActivity.java",
      "    private static final int POST_NOTIFICATIONS_REQUEST = 16;",
      "    private static final int POST_NOTIFICATIONS_REQUEST = 16;\n    private static final int WINDOWS_CPU_MODE = 17;")

patch("main/QubeActivity.java",
      '''        menu.add(0, SETTINGS, 0, R.string.Settings).setIcon(R.drawable.settings_24px);
        menu.add(0, TOOLS, 0, R.string.advancedTools).setIcon(R.drawable.build_24px);''',
      '''        menu.add(0, SETTINGS, 0, R.string.Settings).setIcon(R.drawable.settings_24px);
        if (!MachineController.getInstance().isRunning()
                && WindowsCpuMode.isTargetWindowsVM(this, getMachine())) {
            menu.add(0, WINDOWS_CPU_MODE, 0, "Windows CPU Mode")
                    .setIcon(R.drawable.settings_24px);
        }
        menu.add(0, TOOLS, 0, R.string.advancedTools).setIcon(R.drawable.build_24px);''')

patch("main/QubeActivity.java",
      '''        } else if (item.getItemId() == SETTINGS) {
            showSettings();
        } else if (item.getItemId() == TOOLS) {''',
      '''        } else if (item.getItemId() == SETTINGS) {
            showSettings();
        } else if (item.getItemId() == WINDOWS_CPU_MODE) {
            showWindowsCpuMode();
        } else if (item.getItemId() == TOOLS) {''')

patch("main/QubeActivity.java",
      '''    private void showSettings() {
        Intent i = new Intent(this, QubeSettingsManager.class);
        startActivity(i);
    }''',
      '''    private void showSettings() {
        Intent i = new Intent(this, QubeSettingsManager.class);
        startActivity(i);
    }

    private void showWindowsCpuMode() {
        if (MachineController.getInstance().isRunning()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Windows CPU Mode")
                    .setMessage("Shut down the VM before changing the CPU mode.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (!WindowsCpuMode.isTargetWindowsVM(this, getMachine()))
            return;
        final String[] labels = {
                "Fast — multithreaded TCG (recommended)",
                "Compatibility — use saved CPU threading"
        };
        boolean faster = WindowsCpuMode.enabled(this);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Windows 11 ARM64 CPU Mode")
                .setMessage("Fast lets the existing Windows CPU cores use separate "
                        + "Android CPU threads. It cannot enable KVM or change your "
                        + "guest CPU count. Compatibility preserves original QEMU "
                        + "threading. If Windows becomes unstable, select Compatibility "
                        + "before its next boot. Changes apply at next VM start.")
                .setSingleChoiceItems(labels, faster ? 0 : 1, (dialog, which) -> {
                    WindowsCpuMode.setEnabled(this, which == 0);
                    dialog.dismiss();
                    ToastUtils.toastShort(this, which == 0
                            ? "Fast CPU mode enabled for next Windows boot"
                            : "Original CPU behavior restored for next boot");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }''')

assert 'tcp' not in policy.read_text() # No new networking
assert 'windows_mode_cpu_runtime' in policy.read_text()
exe=(root/"jni/VMExecutor.java").read_text()
assert 'WindowsCpuMode.shouldForceMultiThread' in exe
assert 'tcgParams += ",thread=multi"' in exe
assert 'getMachine().getCpuNum() + ""' in exe # core count untouched
assert 'getMachine().getHdaInterface()' in exe # storage path untouched
act=(root/"main/QubeActivity.java").read_text()
for text in ["WINDOWS_CPU_MODE", "showWindowsCpuMode()", "WindowsCpuMode.setEnabled"]:
    assert text in act
for forbidden in ['mNetConfig.setEnabled(true)', 'setHdaImagePath(', 'setCpuNum(', 'setMemory(']:
    assert forbidden not in policy.read_text()
print("PERFORMANCE SOURCE GATES PASS — runtime-only, reversible Win11 MTTCG")

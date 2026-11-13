#!/usr/bin/env python3
"""Windows Mode 7.1.14: reversible 4-vs-2 vCPU trial and read-only boot evidence.

Applied after 7.1.13 against pinned QubeVM 7.1.4. No changes to
installed Windows, QCOW2, firmware, ISO, network, or saved vCPU count.
"""
from pathlib import Path
import sys

base=Path(sys.argv[1] if len(sys.argv)>1 else "upstream")
java=base/"qube-android-lib/src/main/java/com/max2idea/android/qube"
res=base/"qube-android-lib/src/main"

def edit(f,old,new,n=1):
    f=Path(f)
    data=f.read_text()
    count=data.count(old)
    if count!=n:
        raise RuntimeError(f"Unexpected source in {f}: {old[:85]!r} found={count} expected={n}")
    f.write_text(data.replace(old,new))
    print("BOOT MEASURE PATCH:",f.name)

def fresh(path,body):
    if path.exists():
        raise RuntimeError(f"Refusing to overwrite {path}")
    path.write_text(body)
    print("BOOT MEASURE NEW:",path.name)

fresh(java/"main/WindowsCpuTrial.java",r'''/*
 * Copyright (C) 2026 Windows Mode contributors
 * GPL-3.0 derivative — optional runtime-only CPU comparison.
 */
package com.max2idea.android.qube.main;

import android.content.Context;
import com.max2idea.android.qube.machine.Machine;

/** No writes to Machine, persistent VM settings, or Windows storage. */
public final class WindowsCpuTrial {
    private static final String PREF = "windows_mode_trial_70114";
    private static final String KEY = "try_two_vcpu";
    private WindowsCpuTrial() {}

    public static boolean supported(Context context, Machine vm) {
        return WindowsCpuMode.isTargetWindowsVM(context, vm)
                && vm.getCpuNum() == 4 && vm.getEnableKVM() == 0;
    }

    public static boolean twoCores(Context context, Machine vm) {
        return supported(context, vm)
                && context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY, false);
    }

    public static void setTwoCores(Context context, Machine vm, boolean enable) {
        if (!supported(context, vm))
            return;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, enable).apply();
    }

    public static int runtimeCores(Context context, Machine vm) {
        if (twoCores(context, vm))
            return 2;
        return vm.getCpuNum();
    }
}
''')

fresh(java/"main/WindowsBootMetrics.java",r'''/*
 * Copyright (C) 2026 Windows Mode contributors
 * GPL-3.0 derivative — read-only host-side timing markers.
 */
package com.max2idea.android.qube.main;

import android.os.SystemClock;

/** Host times only. Does not know when the Windows shell actually loads. */
public final class WindowsBootMetrics {
    private static long started = -1;
    private static long processCpuStart = -1;
    private static long firstFrame = -1;
    private static long wallpaper = -1;
    private static long taskbar = -1;
    private static int guestCores = 0;
    private static String mode = "not started";
    private WindowsBootMetrics() {}

    public static synchronized void begin(int runtimeCores, String cpuMode) {
        started = SystemClock.elapsedRealtime();
        processCpuStart = android.os.Process.getElapsedCpuTime();
        firstFrame = -1;
        wallpaper = -1;
        taskbar = -1;
        guestCores = runtimeCores;
        mode = cpuMode == null ? "unknown" : cpuMode;
    }

    public static synchronized void firstFrameSeen() {
        if (started >= 0 && firstFrame < 0)
            firstFrame = SystemClock.elapsedRealtime();
    }

    public static synchronized void markWallpaper() {
        if (started >= 0 && wallpaper < 0)
            wallpaper = SystemClock.elapsedRealtime();
    }

    public static synchronized void markTaskbar() {
        if (started >= 0 && taskbar < 0)
            taskbar = SystemClock.elapsedRealtime();
    }

    private static String formatMarker(long marker) {
        return marker >= 0 && started >= 0
                ? (marker - started) + " ms after QEMU launch"
                : "not observed / not marked";
    }

    public static synchronized String summary() {
        if (started < 0)
            return "No Windows boot time recorded in this application process.";
        long elapsed = SystemClock.elapsedRealtime() - started;
        long cpu = android.os.Process.getElapsedCpuTime() - processCpuStart;
        return "Windows boot study (host-side, read-only)\n"
                + "Runtime cores: " + guestCores + " | Mode: " + mode + "\n"
                + "Time since QEMU launch: " + elapsed + " ms\n"
                + "Host process CPU time: " + cpu + " ms (sum of all threads; "
                    + "may exceed wall-clock time)\n"
                + "First QEMU framebuffer update: " + formatMarker(firstFrame) + "\n"
                + "Wallpaper (marked manually): " + formatMarker(wallpaper) + "\n"
                + "Start/taskbar (marked manually): " + formatMarker(taskbar) + "\n"
                + "NOTE: First framebuffer could be UEFI/boot graphics, not Windows. "
                    + "Desktop markers require the user to confirm visually.\n";
    }
}
''')

# Preserve the firmware, disk and persistent Machine state. Runtime -smp
# is the *only* guest hardware option changed during user-selected trials.
edit(java/"jni/VMExecutor.java",
'''import com.max2idea.android.qube.main.WindowsInstalledBoot;''',
'''import com.max2idea.android.qube.main.WindowsInstalledBoot;
import com.max2idea.android.qube.main.WindowsCpuTrial;
import com.max2idea.android.qube.main.WindowsBootMetrics;''')
edit(java/"jni/VMExecutor.java",
'''            paramsList.add(getMachine().getCpuNum() + "");''',
'''            int runtimeCores = WindowsCpuTrial.runtimeCores(
                    QubeApplication.getInstance(), getMachine());
            paramsList.add(runtimeCores + "");
            if (WindowsCpuTrial.supported(QubeApplication.getInstance(), getMachine()))
                Log.i(TAG, "Windows CPU trial: runtime vCPU=" + runtimeCores
                        + ", saved vCPU=" + getMachine().getCpuNum()
                        + ", no VM settings edited");''')
edit(java/"jni/VMExecutor.java",
'''            String[] params = prepareParams(QubeApplication.getInstance());
            printParams(params);''',
'''            String[] params = prepareParams(QubeApplication.getInstance());
            if (WindowsCpuMode.isTargetWindowsVM(
                    QubeApplication.getInstance(), getMachine())) {
                int cores = WindowsCpuTrial.runtimeCores(
                        QubeApplication.getInstance(), getMachine());
                WindowsBootMetrics.begin(cores,
                        WindowsCpuMode.enabled(QubeApplication.getInstance())
                                ? "Fast MTTCG" : "Saved/compatibility threading");
            }
            printParams(params);''')

# Integrate mode choice in the existing stopped-VM menu. It is not a
# default change: 4 cores selected unless user deliberately chooses 2.
edit(java/"main/QubeActivity.java",
'''    private static final int WINDOWS_INSTALLED_BOOT = 18;''',
'''    private static final int WINDOWS_INSTALLED_BOOT = 18;
    private static final int WINDOWS_CPU_TRIAL = 19;''')
edit(java/"main/QubeActivity.java",
'''            if (WindowsInstalledBoot.supported(this, getMachine()))
                menu.add(0, WINDOWS_INSTALLED_BOOT, 0, "Windows installed boot")
                        .setIcon(R.drawable.settings_24px);''',
'''            if (WindowsInstalledBoot.supported(this, getMachine()))
                menu.add(0, WINDOWS_INSTALLED_BOOT, 0, "Windows installed boot")
                        .setIcon(R.drawable.settings_24px);
            if (WindowsCpuTrial.supported(this, getMachine()))
                menu.add(0, WINDOWS_CPU_TRIAL, 0, "Windows CPU benchmark")
                        .setIcon(R.drawable.settings_24px);''')
edit(java/"main/QubeActivity.java",
'''        } else if (item.getItemId() == WINDOWS_INSTALLED_BOOT) {
            showWindowsInstalledBoot();''',
'''        } else if (item.getItemId() == WINDOWS_INSTALLED_BOOT) {
            showWindowsInstalledBoot();
        } else if (item.getItemId() == WINDOWS_CPU_TRIAL) {
            showWindowsCpuTrial();''')
edit(java/"main/QubeActivity.java",
'''    private void showWindowsInstalledBoot() {''',
'''    private void showWindowsCpuTrial() {
        if (MachineController.getInstance().isRunning()) {
            ToastUtils.toastShort(this, "Stop the VM before selecting a CPU trial");
            return;
        }
        final Machine vm = getMachine();
        if (!WindowsCpuTrial.supported(this, vm))
            return;
        String[] labels = {
                "4 vCPUs — existing Windows baseline (default)",
                "2 vCPUs — temporary test for comparison"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Windows CPU benchmark")
                .setMessage("Changes ONLY the QEMU -smp count for the next boot. "
                        + "Fast MTTCG remains unchanged. Your saved VM retains four "
                        + "cores, and selecting four here immediately restores it. "
                        + "Changing guest CPU count can trigger Windows hardware "
                        + "rediscovery; use only if you want to compare timed boots. "
                        + "No disk, UEFI, network, RAM, or Windows files are edited.")
                .setSingleChoiceItems(labels, WindowsCpuTrial.twoCores(this, vm)?1:0,
                        (dialog, selected) -> {
                            WindowsCpuTrial.setTwoCores(this, vm, selected==1);
                            dialog.dismiss();
                            ToastUtils.toastShort(this, selected==1
                                    ? "Two-core benchmark selected for next boot"
                                    : "Four-core baseline selected for next boot");
                        })
                .setNegativeButton(android.R.string.cancel,null)
                .show();
    }

    private void showWindowsInstalledBoot() {''')

# Read-only QEMU profiling. User can manually mark guest milestones, but
# reporting never assumes that wallpaper means Start/taskbar is ready.
edit(java/"main/QubeQGEActivity.java",
'''import com.max2idea.android.qube.jni.QubeInput;''',
'''import com.max2idea.android.qube.jni.QubeInput;
import com.max2idea.android.qube.qmp.QmpClient;''')
edit(java/"main/QubeQGEActivity.java",
'''        lastGfxGeneration = generation;
        mSurface.postInvalidate();''',
'''        lastGfxGeneration = generation;
        if (WindowsDisplayOptions.isWindowsGuest(this))
            WindowsBootMetrics.firstFrameSeen();
        mSurface.postInvalidate();''')
edit(java/"main/QubeQGEActivity.java",
'''        } else if (item.getItemId() == R.id.itemWindowsRendering) {
            showWindowsRendering();''',
'''        } else if (item.getItemId() == R.id.itemWindowsRendering) {
            showWindowsRendering();
        } else if (item.getItemId() == R.id.itemWindowsBootReport) {
            showWindowsBootReport();''')
edit(java/"main/QubeQGEActivity.java",
'''            menu.removeItem(R.id.itemWindowsRendering);''',
'''            menu.removeItem(R.id.itemWindowsRendering);
            menu.removeItem(R.id.itemWindowsBootReport);''')
edit(java/"main/QubeQGEActivity.java",
'''    private void toggleWindowsImmersive() {''',
'''    private void showWindowsBootReport() {
        if (!WindowsDisplayOptions.isWindowsGuest(this))
            return;
        String[] actions={
                "Mark wallpaper visible (no taskbar yet)",
                "Mark Start and taskbar visible",
                "Copy QEMU CPU / disk boot report"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Windows startup measurement")
                .setMessage(WindowsBootMetrics.summary()
                        + "\\nThe two desktop milestones are marked ONLY "
                        + "when you select them after visually confirming. "
                        + "Copy Report reads QEMU CPU/JIT and disk counters, "
                        + "but cannot reveal Windows guest processes.")
                .setItems(actions,(dialog,choice)-> {
                    if (choice==0) {
                        WindowsBootMetrics.markWallpaper();
                        ToastUtils.toastShort(this,"Wallpaper time marked");
                    } else if (choice==1) {
                        WindowsBootMetrics.markTaskbar();
                        ToastUtils.toastShort(this,"Taskbar time marked");
                    } else {
                        ToastUtils.toastShort(this,"Collecting read-only QEMU counters");
                        new Thread(()-> {
                            String cpus=QmpClient.sendCommand(
                                    "{\\\"execute\\\":\\\"query-cpus-fast\\\"}");
                            String jit=QmpClient.sendCommand(
                                    "{\\\"execute\\\":\\\"human-monitor-command\\\","
                                    + "\\\"arguments\\\":{\\\"command-line\\\":\\\"info jit\\\"}}");
                            String block=QmpClient.sendCommand(
                                    "{\\\"execute\\\":\\\"query-blockstats\\\"}");
                            String report=WindowsBootMetrics.summary()
                                    + "\\n[query-cpus-fast]\\n" + String.valueOf(cpus)
                                    + "\\n[HMP info jit]\\n" + String.valueOf(jit)
                                    + "\\n[query-blockstats]\\n" + String.valueOf(block);
                            runOnUiThread(()->new MaterialAlertDialogBuilder(this)
                                    .setTitle("QEMU boot report (read-only)")
                                    .setMessage("The report contains current QEMU CPU "
                                            + "and disk statistics. These counters "
                                            + "are cumulative, not per-boot stage. "
                                            + "Only Windows can report its process "
                                            + "and service startup times.")
                                    .setPositiveButton("Copy report",(d,w)->{
                                        ClipboardManager cm=(ClipboardManager)getSystemService(
                                                Context.CLIPBOARD_SERVICE);
                                        if (cm!=null)
                                            cm.setPrimaryClip(ClipData.newPlainText(
                                                    "Windows Mode boot report",report));
                                    })
                                    .setNegativeButton("Close",null)
                                    .show());
                        },"WindowsBootReport").start();
                    }
                })
                .setNegativeButton("Close",null)
                .show();
    }

    private void toggleWindowsImmersive() {''')

edit(res/"res/menu/qgeactivitymenu.xml",
'''    <item
        android:id="@+id/itemWindowsRendering"
        android:icon="@drawable/refresh_24px"
        android:title="Windows rendering speed" />''',
'''    <item
        android:id="@+id/itemWindowsRendering"
        android:icon="@drawable/refresh_24px"
        android:title="Windows rendering speed" />
    <item
        android:id="@+id/itemWindowsBootReport"
        android:icon="@drawable/refresh_24px"
        android:title="Windows startup measurement" />''')

# Guard against accidental broad changes or auto-mutations. The on-device
# test itself remains necessary; source compile does not prove performance.
exe=(java/"jni/VMExecutor.java").read_text()
main=(java/"main/QubeActivity.java").read_text()
screen=(java/"main/QubeQGEActivity.java").read_text()
for x in ("WindowsCpuTrial.runtimeCores","WindowsBootMetrics.begin","WindowsBootMetrics.firstFrameSeen"):
    assert x in exe+screen
for x in ("WindowsCpuTrial.setTwoCores","showWindowsCpuTrial","WINDOWS_CPU_TRIAL"):
    assert x in main
for x in ("query-blockstats","query-cpus-fast","info jit","WindowsBootMetrics.markTaskbar"):
    assert x in screen
assert "getMachine().setCpuNum" not in exe
assert 'getMachine().getHdaImagePath()' in exe
assert 'getMachine().getCdImagePath()' in exe
assert 'WindowsInstalledBoot.enabled(' in exe
assert 'WindowsCpuMode.shouldForceMultiThread' in exe
assert "2" in (java/"main/WindowsCpuTrial.java").read_text()
assert (res/"res/menu/qgeactivitymenu.xml").read_text().count('android:id="@+id/itemWindowsBootReport"') == 1
print("7.1.14 scoped 2/4-core trial + boot instrumentation: source gates PASS")

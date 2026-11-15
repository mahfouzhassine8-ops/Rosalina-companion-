#!/usr/bin/env python3
"""Windows Mode opt-in SECOND GPU test with original boot RAMFB intact.

Run on a disposable QubeVM 7.1.4 checkout after 7.1.19 Java source stages and
stage_qge_fold_gpu_bridge.py. Requires a separate test Windows disk and UEFI VARS
inside the WinJIT-disposable directory: original installed Windows is NEVER
eligible, even if globally enabled. No guest disk, BIOS VARS or machine DB edit.
No automatic GPU console switch: user retains RAMFB recovery at every boot.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1])
src=root/"qube-android-lib/src/main/java/com/max2idea/android/qube"
qge=src/"main/QubeQGEActivity.java"
surface=src/"main/QubeQGESurface.java"
activity=src/"main/QubeActivity.java"
vmexe=src/"jni/VMExecutor.java"
display=src/"main/WindowsDisposableGpuLab.java"
menu=root/"qube-android-lib/src/main/res/menu/qgeactivitymenu.xml"

def patch(path:Path,before:str,after:str):
    old=path.read_text()
    count=old.count(before)
    if count!=1:
        raise RuntimeError(f"{path.name}: expected 1 pinned source anchor got {count}: {before[:110]!r}")
    path.write_text(old.replace(before,after,1))
    print("PASS: disposable Windows GPU patch",path.name)

if not all(x.is_file() for x in [qge,surface,activity,vmexe,menu]):
    raise RuntimeError("Pinned 7.1.19 source not staged")
if display.exists():
    raise RuntimeError("Refusing to overwrite WindowsDisposableGpuLab.java")

display.write_text(r'''package com.max2idea.android.qube.main;

import android.content.Context;
import com.max2idea.android.qube.machine.Machine;

public final class WindowsDisposableGpuLab {
    private static final String PREF = "windows_disposable_gpu_lab";
    private static final String KEY_GPU = "gpu_attached_to_disposable";
    private WindowsDisposableGpuLab(){}

    public static boolean eligible(Context context, Machine vm) {
        // The Windows WinJIT test sandbox uses a verified canonical directory,
        // separate disk and VARS with no shared folder or QEMU extra arguments.
        // Reuse it even for normal TCG. A real installed Windows disk is rejected.
        return WindowsCpuMode.isTargetWindowsVM(context, vm)
            && WindowsWinjitMode.hasDisposableStorage(context, vm)
            && vm.getEnableKVM() == 0
            && "ramfb".equalsIgnoreCase(vm.getVga())
            && vm.getRenderer() == 0;
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getBoolean(KEY_GPU,false);
    }
    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GPU,enabled).apply();
    }
    public static boolean shouldAttach(Context context, Machine vm) {
        return eligible(context,vm) && isEnabled(context);
    }
}
''')

patch(vmexe,
'''import com.max2idea.android.qube.main.WindowsArm64DriverMedia;''',
'''import com.max2idea.android.qube.main.WindowsArm64DriverMedia;
import com.max2idea.android.qube.main.WindowsDisposableGpuLab;''')
patch(vmexe,
'''        addGraphicsOptions(context, paramsList);
        addAudioOptions(paramsList);''',
'''        addGraphicsOptions(context, paramsList);
        addWindowsDisposableGpu(context, paramsList);
        addAudioOptions(paramsList);''')
patch(vmexe,
'''    private void addAdvancedOptions(ArrayList<String> paramsList) {''',
'''    private void addWindowsDisposableGpu(Context context, ArrayList<String> paramsList) {
        if (!WindowsDisposableGpuLab.shouldAttach(context, getMachine())) return;
        // Never replace or remove the boot-safe ramfb. Windows' ARM64
        // viogpudo driver matches PCI VEN_1AF4 DEV_1050 and supports EDID.
        // NOTE: GPU is 2D-only here; hardware 3D is not implied.
        paramsList.add("-device");
        paramsList.add("virtio-gpu-pci,id=winvgpu,edid=on");
    }

    private void addAdvancedOptions(ArrayList<String> paramsList) {''')

patch(activity,
'''    private static final int WINDOWS_ARM64_DRIVERS = 22;''',
'''    private static final int WINDOWS_ARM64_DRIVERS = 22;
    private static final int WINDOWS_DISPOSABLE_GPU_LAB = 23;''')
patch(activity,
'''            menu.add(0, WINDOWS_ARM64_DRIVERS, 0, "Windows ARM64 drivers")
                    .setIcon(R.drawable.settings_24px);''',
'''            menu.add(0, WINDOWS_ARM64_DRIVERS, 0, "Windows ARM64 drivers")
                    .setIcon(R.drawable.settings_24px);
            if (WindowsDisposableGpuLab.eligible(this, getMachine()))
                menu.add(0, WINDOWS_DISPOSABLE_GPU_LAB, 0,
                        "Windows GPU (test disk ONLY)")
                    .setIcon(R.drawable.settings_24px);''')
patch(activity,
'''        } else if (item.getItemId() == WINDOWS_ARM64_DRIVERS) {
            if (!MachineController.getInstance().isRunning())
                WindowsArm64DriverSetup.show(this, getMachine());''',
'''        } else if (item.getItemId() == WINDOWS_ARM64_DRIVERS) {
            if (!MachineController.getInstance().isRunning())
                WindowsArm64DriverSetup.show(this, getMachine());
        } else if (item.getItemId() == WINDOWS_DISPOSABLE_GPU_LAB) {
            if (!MachineController.getInstance().isRunning())
                showWindowsDisposableGpuLab();''')
patch(activity,
'''    private void showWindowsWinjitMode() {''',
'''    private void showWindowsDisposableGpuLab() {
        if (MachineController.getInstance().isRunning() ||
            !WindowsDisposableGpuLab.eligible(this,getMachine())) return;
        final boolean on=WindowsDisposableGpuLab.shouldAttach(this,getMachine());
        String[] choices={
            "Keep original RAMFB video only (recommended)",
            "Add VirtIO GPU as SECOND display on DISPOSABLE VM only"
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle("Windows ARM64 graphics experiment")
            .setMessage("Only a copied Windows disk and separate UEFI VARS in the "
                + "WinJIT-disposable test folder are allowed. Original installed "
                + "Windows never receives the extra GPU. 2D VirtIO GPU/viogpudo "
                + "may support native resolution but Windows compatibility, "
                + "performance and live Fold mode-switching are NOT yet verified. "
                + "RAMFB always remains the boot and rollback display.")
            .setSingleChoiceItems(choices,on?1:0,(d,which)->{
                WindowsDisposableGpuLab.setEnabled(this,which==1);
                d.dismiss();
            }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void showWindowsWinjitMode() {''')

patch(menu,
'''    <item
        android:id="@+id/itemWindowsRendering"''',
'''    <item
        android:id="@+id/itemWindowsGpuLab"
        android:icon="@drawable/toolbar_24px"
        android:title="Windows GPU test display (disposable guest only)" />
    <item
        android:id="@+id/itemWindowsRendering"''')

patch(qge,
'''    private boolean immersiveWindow = false;''',
'''    private boolean immersiveWindow = false;
    private boolean disposableGpuPreviewSelected = false;''')
patch(qge,
'''        } else if (item.getItemId() == R.id.itemWindowsRendering) {
            showWindowsRendering();''',
'''        } else if (item.getItemId() == R.id.itemWindowsRendering) {
            showWindowsRendering();
        } else if (item.getItemId() == R.id.itemWindowsGpuLab) {
            showWindowsGpuLabPreview();''')
patch(qge,
'''            menu.removeItem(R.id.itemWindowsRendering);
        }

        int maxMenuItemsShown''',
'''            menu.removeItem(R.id.itemWindowsRendering);
        }
        if (!WindowsDisposableGpuLab.shouldAttach(this,
                MachineController.getInstance().getMachine()))
            menu.removeItem(R.id.itemWindowsGpuLab);

        int maxMenuItemsShown''')
patch(qge,
'''    private void showWindowsRendering() {''',
'''    // Exposed only for Windows on a sandboxed disposable test disk.
    // Native JNI TRUE means request queued; actual framebuffer response is
    // separately checked. No automatic host-guest display switching.
    public void onFoldGuestSurfaceSize(int width, int height) {
        if (!disposableGpuPreviewSelected ||
            !WindowsDisposableGpuLab.shouldAttach(this,
                    MachineController.getInstance().getMachine()))
            return;
        if (width < 640 || height < 480) return;
        boolean queued = QubeGfx.nativeRequestFoldGpuDisplay(
                true, width, height, Math.max(30, Math.min(120, effectiveGuestRefreshRate())));
        android.util.Log.i(TAG,"Fold GPU size request queued=" + queued
                + " target=" + width + "x" + height
                + " (guest Windows mode NOT verified)");
    }

    private void showWindowsGpuLabPreview() {
        if (!WindowsDisposableGpuLab.shouldAttach(this,
                MachineController.getInstance().getMachine())) return;
        String[] choices={
                "Keep boot RAMFB (safe recovery)",
                "Preview VirtIO GPU console (unverified)",
                "Resend current Fold size to selected GPU"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Windows GPU research — DISPOSABLE disk only")
                .setMessage("The original Windows disk is not eligible. GPU "
                        + "driver must bind inside the disposable Windows VM. "
                        + "QEMU only QUEUES the EDID/fold-size event. This is "
                        + "NOT confirmation Windows changed its desktop resolution. "
                        + "If GPU view is blank, return to RAMFB.")
                .setItems(choices,(d,which)->{
                    int w=mSurface.getWidth(),h=mSurface.getHeight();
                    if (w<640 || h<480) {
                        ToastUtils.toastShort(this,"Fold surface too small or not ready");
                        return;
                    }
                    boolean gpu = which != 0;
                    if (which == 2 && !disposableGpuPreviewSelected)
                        return;
                    boolean queued=QubeGfx.nativeRequestFoldGpuDisplay(
                            gpu,w,h,Math.max(30,Math.min(120,effectiveGuestRefreshRate())));
                    if (queued) disposableGpuPreviewSelected=gpu;
                    ToastUtils.toastShort(this,queued
                        ? "QEMU display request queued — Windows result unverified"
                        : "QEMU request unavailable; no display mode was changed");
                }).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void showWindowsRendering() {''')

patch(surface,
'''    @Override
    protected void onDraw(Canvas canvas) {''',
'''    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        if (w>0 && h>0 && (w!=oldw || h!=oldh))
            QGEActivity.onFoldGuestSurfaceSize(w,h);
    }

    @Override
    protected void onDraw(Canvas canvas) {''')

exe=vmexe.read_text();act=activity.read_text();screen=qge.read_text()
assert 'WindowsWinjitMode.hasDisposableStorage' in display.read_text()
assert 'getBoolean(KEY_GPU,false)' in display.read_text()
assert 'virtio-gpu-pci,id=winvgpu,edid=on' in exe
assert 'addGraphicsOptions(context, paramsList);' in exe
assert 'WINDOWS_DISPOSABLE_GPU_LAB = 23;' in act
assert 'nativeRequestFoldGpuDisplay(' in screen
assert 'onFoldGuestSurfaceSize(w,h)' in surface.read_text()
assert 'getMachine().setHda' not in exe and 'setBiosVars(' not in screen
print("PASS: original Windows VM unchanged; GPU only in sandbox with separate disk/VARS and explicit preview")

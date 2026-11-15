#!/usr/bin/env python3
"""Fail closed for Windows-only DISPOSABLE GPU research VM and Fold input."""
from pathlib import Path
import re
import sys

root=Path(sys.argv[1])
src=root/"qube-android-lib/src/main/java/com/max2idea/android/qube"
n=(src/"main/WindowsDisposableGpuLab.java").read_text()
vm=(src/"jni/VMExecutor.java").read_text()
gui=(src/"main/QubeActivity.java").read_text()
qge=(src/"main/QubeQGEActivity.java").read_text()
surface=(src/"main/QubeQGESurface.java").read_text()
jni=(src/"jni/QubeGfx.java").read_text()
menu=(root/"qube-android-lib/src/main/res/menu/qgeactivitymenu.xml").read_text()
native=(root/"qube-android-lib/src/main/jni/qemu-addons/11.1.3/ui/qge.c").read_text()
bridge=(root/"qube-android-lib/src/main/jni/qube/qge-jni.c").read_text()
assert "WindowsWinjitMode.hasDisposableStorage(context, vm)" in n
assert "WindowsCpuMode.isTargetWindowsVM(context, vm)" in n
assert "getBoolean(KEY_GPU,false)" in n
assert 'ramfb".equalsIgnoreCase(vm.getVga())' in n
assert "vm.getRenderer() == 0" in n
assert "getEnableKVM() == 0" in n
assert "WindowsDisposableGpuLab.shouldAttach(context, getMachine())" in vm
assert 'virtio-gpu-pci,id=winvgpu,edid=on' in vm
assert "addGraphicsOptions(context, paramsList);" in vm
assert "addWindowsDisposableGpu(context, paramsList);" in vm
assert vm.index("addGraphicsOptions(context, paramsList);") < vm.index(
    "addWindowsDisposableGpu(context, paramsList);")
assert "WINDOWS_DISPOSABLE_GPU_LAB = 23;" in gui
assert "WINDOWS_GPU_LAB" not in gui
assert 'showWindowsDisposableGpuLab()' in gui
assert 'itemWindowsGpuLab' in menu and 'itemWindowsGpuLab' in qge
assert "QubeGfx.nativeRequestFoldGpuDisplay(" in qge
assert "public void onFoldGuestSurfaceSize(int width, int height)" in qge
assert "onFoldGuestSurfaceSize(w,h)" in surface
assert "nativeRequestFoldGpuDisplay(" in jni
assert "qube_gfx_request_fold_display" in native and "qube_gfx_request_fold_display" in bridge
assert "qube_gfx_con = target;" in native
assert "qemu_console_lookup_by_index(0)" in native
assert 'qemu_console_lookup_by_device_name("winvgpu"' in native
assert "qemu_console_set_ui_info(target, &ui, true)" in native
assert "aio_bh_schedule_oneshot(qemu_get_aio_context()" in native
assert "return 0;  /* Queued, not successful guest mode-set. */" in native
assert 'Windows 11 ARM64 drivers' in (src/"main/WindowsArm64DriverSetup.java").read_text()
assert "confirmWindowsGracefulShutdown()" in qge
assert "AUTO_EDGE_TO_EDGE" in (src/"main/WindowsDisplayOptions.java").read_text()
menu_ids={};
for name,num in re.findall(r"private static final int (WINDOWS_[A-Z0-9_]+)\s*=\s*(\d+);",gui):
    assert num not in menu_ids.values(),(name,num,menu_ids)
    menu_ids[name]=num
assert set(menu_ids.values()) >= set(["17","18","19","21","22","23"])
# No stored VM/memory/disk changes, hardcoded user's device geometry or
# surprise GPU enablement. True PnP / resolution success still requires Windows.
for text in (n,qge,surface,vm):
    for dangerous in ["setHdaImagePath(", "setBiosVars(", "setCpuNum(", "setMemory("]:
        assert dangerous not in text,dangerous
assert "1600" not in n and "859" not in n, "Use live view bounds, not hardcoded device dimensions"
print("PASS: RAMFB default, disposable-only VirtIO GPU, native UI/EDID queued request, menu identity, Fold geometry")
print("NOT PASS: physical Fold output, installed Windows driver activation, Windows guest mode-set")

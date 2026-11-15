#!/usr/bin/env python3
"""Read-only Windows ARM64 driver-media safety and hardware contract regression.

Requires disposable QubeVM after 7.1.17 stage. Not a real Windows driver test.
"""
from pathlib import Path
import re
import sys

root=Path(sys.argv[1]) / "qube-android-lib/src/main/java/com/max2idea/android/qube"
activity=(root/"main/QubeActivity.java").read_text()
exe=(root/"jni/VMExecutor.java").read_text()
media=(root/"main/WindowsArm64DriverMedia.java").read_text()
ui=(root/"main/WindowsArm64DriverSetup.java").read_text()
screen=(root/"main/QubeQGEActivity.java").read_text()

values={}
for name,value in re.findall(r"private static final int (WINDOWS_[A-Z0-9_]+)\s*=\s*(\d+);",activity):
    assert int(value) not in values.values(),f"Menu ID collision: {name}={value}: {values}"
    values[name]=int(value)
assert values["WINDOWS_INSTALLED_BOOT"]==18
assert values["WINDOWS_WINJIT_MODE"]==19
assert values["WINDOWS_ARM64_DRIVERS"]==20

# The original 7.1.16 safe shutdown and display path are preserved.
assert "confirmWindowsGracefulShutdown();" in screen
assert "AUTO_EDGE_TO_EDGE" in (root/"main/WindowsDisplayOptions.java").read_text()
assert "WindowsArm64DriverSetup.show(this, getMachine());" in activity

# Driver disc is optional, immutable to Windows, additional to existing media.
assert "addRemovableDrives(paramsList);" in exe
assert "addWindowsArm64DriverDisc(context, paramsList);" in exe
assert exe.index("addRemovableDrives(paramsList);") < exe.index(
    "addWindowsArm64DriverDisc(context, paramsList);") < exe.index("addBootOptions(paramsList);")
assert "if=none,id=wmarm64virtio,media=cdrom,format=raw,readonly=on,file=" in exe
assert "usb-storage,drive=wmarm64virtio,bootindex=99" in exe
assert "QubeApplication.getInstance()" in exe
assert "WindowsCpuMode.isTargetWindowsVM" in media
assert "WindowsWinjitMode.shouldUse(c, m)" in media
assert "getBoolean(KEY_ATTACH, false)" in media
assert "SHA256.equals" in media
assert "partial.renameTo(target)" in media
assert "ISO9660" in media
assert "getExternalFilesDir(null)" in media
assert "753 MB" in ui
assert "does not auto-install drivers" in (ui.lower() if False else ui).lower() or "does not auto-install" in ui.lower()

# No guest disk, VM profile, UEFI VARS, driver INSTALL or forced stop are added.
combined=exe+media+ui
for forbidden in ["setHdaImagePath(", "setHdaInterface(", "setCdImagePath(",
                  "setCpuNum(", "setMemory(", "setBiosVars(", "MachineAction.RESET_VM",
                  "MachineAction.STOP_VM", "machineExecutor.stopvm("]:
    assert forbidden not in combined, forbidden
assert "-snapshot" in exe
assert "WindowsWinjitMode.shouldUse" in exe

# Pinned source package, not x64-only guest tools or third-party mirrors.
assert "archive-virtio/virtio-win-0.1.285-1/virtio-win-0.1.285.iso" in media
assert "e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331" in media
assert "virtio-win-guest-tools.exe" not in media
assert "http://" not in media
print("PASS: unique menus, signed ISO gating, Windows-only readonly media, WinJIT isolation, preserved original boot")

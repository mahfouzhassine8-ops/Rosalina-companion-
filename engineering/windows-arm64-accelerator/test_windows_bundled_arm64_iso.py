#!/usr/bin/env python3
"""Regression and integrity checks for the optional compact Windows ARM64 disc.

Runs after the 70118 stage on a disposable pinned QubeVM checkout.
Never touches a Windows guest disk, installer, firmware, host GPU or live VM.
"""
from pathlib import Path
import hashlib
import re
import subprocess
import sys

root=Path(sys.argv[1])
java=root/"qube-android-lib/src/main/java/com/max2idea/android/qube/main"
media=(java/"WindowsArm64DriverMedia.java").read_text()
ui=(java/"WindowsArm64DriverSetup.java").read_text()
executor=(root/"qube-android-lib/src/main/java/com/max2idea/android/qube/jni/VMExecutor.java").read_text()
activity=(java/"QubeActivity.java").read_text()
screen=(java/"QubeQGEActivity.java").read_text()
asset=root/"qube-android-arm/src/main/assets/WindowsArm64Drivers/arm64-driver-disc.iso"

assert asset.is_file()
assert 500000<asset.stat().st_size<100_000_000
with asset.open("rb") as f:
    f.seek(32769)
    assert f.read(5)==b"CD001", "Asset is not ISO9660"
digest=hashlib.sha256(asset.read_bytes()).hexdigest()
sha=re.search(r'private static final String COMPACT_SHA256 = "([0-9a-f]{64})";',media)
assert sha and sha.group(1)==digest, "Android loader must match exact embedded driver ISO bytes"

inventory=subprocess.run(["7z","l","-slt",str(asset)],check=True,text=True,capture_output=True).stdout
for family in ("NetKVM","vioscsi","viostor","vioinput","viorng","viogpudo","vioserial"):
    assert re.search(rf"Path = {family}/w11/ARM64/.*?\.inf",inventory,re.I), family
assert "virtio-win_license.txt" in inventory, "Original Fedora driver redistribution license missing"
assert "WINDRIVERS" not in inventory

for name in ("prepareCompact(", "isCompactVerified(", "getAssets().open(COMPACT_ASSET)",
             "COMPACT_SHA256.equals", "WindowsWinjitMode.shouldUse", "FileOutputStream",
             "new File(root,", "getCanonicalFile()"):
    assert name in media, name
assert "File f = isCompactVerified(c) ? compactFile(c)" in media
assert "isVerified(c) ? isoFile(c) : null" in media
assert 'getBoolean(KEY_ATTACH, false)' in media
assert 'prepareIncludedDisc' in ui
assert 'WindowsARM64-PrepareOfflineDrivers' in ui
assert 'Windows does not auto-install drivers' in ui
assert 'Download full Fedora VirtIO ISO (~753 MB, optional)' in ui
assert 'read-only' in ui.lower()
assert 'getHdaImagePath()' in executor
assert 'addWindowsArm64DriverDisc(context, paramsList);' in executor
assert "readonly=on,file=" in executor
assert "WindowsWinjitMode.shouldUse" in executor
assert "AUTO_EDGE_TO_EDGE" in (java/"WindowsDisplayOptions.java").read_text()
assert "confirmWindowsGracefulShutdown()" in screen
assert "WINDOWS_CPU_TRIAL = 19;" in activity
assert "WINDOWS_WINJIT_MODE = 21;" in activity
assert "WINDOWS_ARM64_DRIVERS = 22;" in activity
assert "APPROVED" not in media, "Do not claim driver or Windows device acceptance"

# The code does not auto-install inside the guest or mutate boot-critical state.
for unsafe in ("setHdaImagePath(", "setCdImagePath(", "setBiosVars(",
               "setCpuNum(", "setMemory(", "MachineAction.STOP_VM",
               "MachineAction.RESET_VM", "pnputil.exe", "msiexec"):
    assert unsafe not in media+ui, unsafe
print("PASS: compact offline ARM64 ISO signed-file provenance, exact SHA, safety, source contracts")

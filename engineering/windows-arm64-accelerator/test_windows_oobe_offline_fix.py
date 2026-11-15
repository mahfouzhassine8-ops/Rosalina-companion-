#!/usr/bin/env python3
"""Regression gate for 7.1.21 Windows OOBE repair; source-only (not device proof)."""
import sys
from pathlib import Path
if len(sys.argv) != 2:
    raise SystemExit("usage: test_windows_oobe_offline_fix.py UPSTREAM")
root = Path(sys.argv[1])
target = root / "qube-android-lib/src/main/java/com/max2idea/android/qube/virtualkeys/VirtualKeys.java"
code = target.read_text()
start = code.index("    private void showWindowsOfflineAccount() {")
end = code.index("    private void showWindowsSetupTools() {", start)
flow = code[start:end]
assert code.count('view.setOnClickListener(v -> showWindowsOfflineAccount());') == 1
assert code.count("    private void showWindowsOfflineAccount() {") == 1
assert "isWindowsTarget()" in flow
assert flow.index('sendSetupHmpKey("shift-f10")') < flow.index('typeSetupCommand("start ms-cxh:localonly")')
assert flow.index("CMD visible - type command") < flow.index('typeSetupCommand("start ms-cxh:localonly")')
assert "No CMD - stop" in flow
assert "does NOT prove" in flow
assert "guest result UNVERIFIED" in flow
assert "Setup Tools > Offline fallback" in flow
assert "setHdaImagePath" not in flow and "setCdImagePath" not in flow
assert "setBiosVars" not in flow and "shutdown /r" not in flow
assert "virtio-net" not in flow and "e1000" not in flow
assert 'typeSetupCommand("start ms-cxh:localonly")' in code
print("PASS: 7.1.21 Offline Account opens CMD before typing, requires visual consent")
print("PASS: no automatic OOBE restart, no VM disk/network/UEFI/hardware mutations")
print("NOTE: QEMU key delivery does not prove Windows OOBE accepted the command")

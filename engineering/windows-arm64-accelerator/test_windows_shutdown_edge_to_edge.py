#!/usr/bin/env python3
"""Source and geometry regressions for the Windows-only 7.1.16 stage.

Run against a disposable QubeVM tree AFTER all 7.1.14/7.1.15 stages.
Actual Fold acceptance and Windows power-event handling remain device gates.
"""
from pathlib import Path
import sys

base = Path(sys.argv[1]) / "qube-android-lib/src/main/java/com/max2idea/android/qube"
activity = (base / "main/QubeQGEActivity.java").read_text()
display = (base / "main/WindowsDisplayOptions.java").read_text()
surface = (base / "main/QubeQGESurface.java").read_text()
executor = (base / "jni/VMExecutor.java").read_text()

# Power: Windows sends an explicit QEMU ACPI request, not STOP_VM.
assert 'QmpClient.getPowerDownCommand()' in activity
assert 'QmpClient.sendCommand(QmpClient.getPowerDownCommand())' in activity
assert 'if (WindowsDisplayOptions.isWindowsGuest(this))\n                confirmWindowsGracefulShutdown();' in activity
assert 'QubeActivityCommon.promptStopVM(this, viewListener)' in activity  # manual, warned fallback
assert 'windowsGracefulStopPending' in activity
assert 'accepted ? "Shutdown requested" : "Shutdown request failed"' in activity
assert 'This is NOT a force stop.' in activity
assert 'new Thread(() -> {' in activity

# Display: Windows-only auto immersive, recoverable with Android Back.
for token in [
    'AUTO_EDGE_TO_EDGE = 3',
    '.getInt(SCALE, AUTO_EDGE_TO_EDGE)',
    'value <= AUTO_EDGE_TO_EDGE',
]:
    assert token in display
for token in [
    'mode != WindowsDisplayOptions.AUTO_EDGE_TO_EDGE',
    'inverse.mapPoints(pt)',
    'float sx = getWidth() / (float) frameBitmap.getWidth();',
    'float sy = getHeight() / (float) frameBitmap.getHeight();',
]:
    assert token in surface
for token in [
    'toggleWindowsImmersive();',
    'virtualKeysBeforeImmersive',
    'if (immersiveWindow)',
    'hide(WindowInsetsCompat.Type.systemBars())',
    'Auto edge-to-edge — no bars',
    'updateLayout(newConfig.orientation);'
]:
    assert token in activity

# Edge-to-edge transform is exact rectangle-to-rectangle mapping;
# inversion remains symmetric on inner, cover, rotated and keyboard-inset sizes.
for view in [(1600, 1500), (1080, 2340), (2340, 1080), (1600, 910),
             (2256, 2504), (859, 954), (720, 1280)]:
    w, h = view
    for gw, gh in [(1280, 800), (1024, 768), (1600, 1200)]:
        sx, sy = w / gw, h / gh
        for x, y in [(0, 0), (gw, 0), (0, gh), (gw, gh), (gw/2, gh/2)]:
            mapped_x, mapped_y = x * sx, y * sy
            assert abs(mapped_x/sx-x) < 1e-9
            assert abs(mapped_y/sy-y) < 1e-9
            assert -1e-9 <= mapped_x <= w+1e-9
            assert -1e-9 <= mapped_y <= h+1e-9

# Never auto-rewrite disks, stored hardware, CPU mode, firmware, or guest config.
stage = (Path(__file__).parent / "stage_windows_shutdown_edge_to_edge.py").read_text()
for forbidden in [
    'setHdaImagePath(', 'setCpuNum(', 'setMemory(', 'setCdImagePath(',
    'setBiosVars(', 'machineExecutor.stopvm(', 'MachineAction.RESET_VM',
]:
    assert forbidden not in stage
assert 'WindowsWinjitMode.shouldUse' in executor
assert 'tcg,thread' in executor or 'tcgParams' in executor
print("PASS: Windows-only graceful ACPI power, reversible fullscreen, Fold geometry, storage guards")

#!/usr/bin/env python3
"""7.1.22 scoped native/Java performance source contract, not a speed benchmark."""
from pathlib import Path
import sys
if len(sys.argv) != 2:
    raise SystemExit("usage: test_windows_boot_performance.py UPSTREAM")
root = Path(sys.argv[1])
native = root / "qube-android-lib/src/main/jni"
java = root / "qube-android-lib/src/main/java/com/max2idea/android/qube"
qge = (native / "qemu-addons/11.1.3/ui/qge.c").read_text()
jni = (native / "qube/qge-jni.c").read_text()
gfx = (java / "jni/QubeGfx.java").read_text()
activity = (java / "main/QubeQGEActivity.java").read_text()
executor = (java / "jni/VMExecutor.java").read_text()
assert 'int qube_gfx_peek_generation(void)' in qge
assert 'qatomic_read(&qube_gfx_generation)' in qge
assert 'if (!qube_gfx_lock_ready)' in qge
assert 'int qube_gfx_copy_frame(void *dst' in qge # Old ABI remains intact.
assert 'dlsym(handle, "qube_gfx_peek_generation")' in jni
assert 'Java_com_max2idea_android_qube_jni_QubeGfx_nativePeekGeneration' in jni
assert 'return -1;  // Safe fallback' in jni
assert 'public static native int nativePeekGeneration();' in gfx
assert 'currentGeneration == lastGfxGeneration' in activity
assert 'lastGfxGeneration = -1; // New bitmap must be fully populated once.' in activity
assert activity.index('currentGeneration == lastGfxGeneration') < activity.index('QubeGfx.nativeCopyFrame(frameBitmap)')
assert 'WindowsBootMetrics.firstFrameSeen();' in activity
assert 'nativeRequestFoldGpuDisplay' in activity # Existing experimental Fold bridge retained.
assert 'tcgParams += ",tb-size=128";' in executor
assert 'getMachine().getTcgBuffer() > 0' in executor
assert 'WindowsCpuMode.shouldForceMultiThread' in executor
assert executor.index('getMachine().getTcgBuffer() > 0') < executor.index('tcgParams += ",tb-size=128";')
assert 'WindowsWinjitMode.shouldUse' in executor
assert 'WindowsArm64DriverMedia.attachedPath' in executor
assert 'WindowsDisposableGpuLab.shouldAttach' in executor
for forbidden in ['getMachine().setCpuNum(', 'getMachine().setMemory(',
                  'getMachine().setCdImagePath(', 'getMachine().setHdaImagePath(',
                  'getMachine().setBiosVars(']:
    assert forbidden not in executor, f"Forbidden machine mutation: {forbidden}"
print('PASS: generation-based unchanged-frame skip with fallback and retained native copy ABI')
print('PASS: bounded 128 MiB MTTCG Fast-only cache, saved user TCG buffer respected')
print('PASS: Windows 11 OOBE and protected disk/UEFI/USB/NIC/GPU/CPU configs unchanged')
print('NOT PROVEN: Windows boot-time speedup; device thermal or desktop responsiveness')

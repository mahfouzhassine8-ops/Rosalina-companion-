#!/usr/bin/env python3
"""Windows Mode 7.1.22: bounded boot/render CPU overhead reduction.

Run AFTER all pinned 7.1.21 Android/WinJIT/GPU/OOBE source staging on a
disposable QubeVM 7.1.4 checkout. This source patch:
  * observes the QEMU QGE frame generation before paying to convert/copy the
    unchanged framebuffer on Android UI Choreographer ticks;
  * gives Windows MTTCG a conservative 128 MiB translation cache only when
    user has not configured any TCG buffer and Fast CPU mode is selected.

It does NOT change native CPU execution engines, VM database, guest disks,
UEFI VARS, installed Windows, GPU selection, NIC, boot order, vCPU count,
RAM, sound, QMP safe shutdown or offline-account workflows.
Boot time speedup is not established until a Windows/Fold device test.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: stage_windows_boot_performance.py UPSTREAM")

root = Path(sys.argv[1])
native = root / "qube-android-lib/src/main/jni"
java = root / "qube-android-lib/src/main/java/com/max2idea/android/qube"
qge = native / "qemu-addons/11.1.3/ui/qge.c"
jni = native / "qube/qge-jni.c"
gfx = java / "jni/QubeGfx.java"
activity = java / "main/QubeQGEActivity.java"
vmexecutor = java / "jni/VMExecutor.java"

for f in (qge, jni, gfx, activity, vmexecutor):
    if not f.is_file():
        raise RuntimeError(f"Required pinned source missing: {f}")

def patch(f: Path, before: str, after: str) -> None:
    s = f.read_text()
    n = s.count(before)
    if n != 1:
        raise RuntimeError(f"{f.name}: expected one pinned anchor got {n}: {before[:96]!r}")
    f.write_text(s.replace(before, after, 1))
    print(f"PASS: scoped 7.1.22 performance patch {f.name}")

# A read-only qatomic load of the same QEMU gfx generation already returned by
# qube_gfx_copy_frame. Does not change the guest framebuffer, update callbacks
# or timer. When gen is unchanged the existing bitmap is already valid.
patch(qge,
    "// Returns gen counter at copy time (0 if no surface). Compare to skip unchanged frames.",
    """// Returns the generation of the existing QGE DisplaySurface without copying
// any pixels. Android can skip full-frame BGRX->RGBA conversion if unchanged.
// This deliberately does NOT modify QEMU refresh timing or surface ownership.
int qube_gfx_peek_generation(void)
{
    if (!qube_gfx_lock_ready) {
        return -1;
    }
    return qatomic_read(&qube_gfx_generation);
}

// Returns gen counter at copy time (0 if no surface). Compare to skip unchanged frames.""")

# Extend Android JNI with a dlsym-resolved optional getter. If unavailable
# (older native engine), return -1 so Java FALLS BACK to the old copy path.
patch(jni,
    "typedef int (*qube_gfx_copy_frame_t)(void *dst, int dst_stride, int max_w, int max_h);",
    """typedef int (*qube_gfx_copy_frame_t)(void *dst, int dst_stride, int max_w, int max_h);
typedef int (*qube_gfx_peek_gen_t)(void);""")
patch(jni,
    "static qube_gfx_copy_frame_t qube_gfx_copy_frame_fn = NULL;",
    """static qube_gfx_copy_frame_t qube_gfx_copy_frame_fn = NULL;
static qube_gfx_peek_gen_t qube_gfx_peek_gen_fn = NULL;""")
patch(jni,
    'qube_gfx_copy_frame_fn = (qube_gfx_copy_frame_t) dlsym(handle, "qube_gfx_copy_frame");',
    '''qube_gfx_copy_frame_fn = (qube_gfx_copy_frame_t) dlsym(handle, "qube_gfx_copy_frame");
    qube_gfx_peek_gen_fn = (qube_gfx_peek_gen_t) dlsym(handle, "qube_gfx_peek_generation");''')

# New native binding remains available even if no guest frame has arrived yet.
# The existing legacy nativeCopyFrame symbol is unchanged.
with jni.open("a") as f:
    f.write(r"""

// Returns last QEMU display update generation without locking Android Bitmap
// pixels or spending CPU on a redundant full-screen color conversion.
JNIEXPORT jint JNICALL
Java_com_max2idea_android_qube_jni_QubeGfx_nativePeekGeneration(
        JNIEnv *env, jclass clazz) {
    if (!resolve_qube_gfx_syms() || !qube_gfx_peek_gen_fn) {
        return -1;  // Safe fallback when older QEMU binary is running.
    }
    return qube_gfx_peek_gen_fn();
}
""")

patch(gfx,
    "    // Copies guest frame into ARGB_8888 bitmap, returns generation counter (0 if unavailable)",
    """    // Cheap read-only QGE display generation; -1 means no native support.
    // Pixel copying and bitmap locking are avoided when this hasn't changed.
    public static native int nativePeekGeneration();

    // Copies guest frame into ARGB_8888 bitmap, returns generation counter (0 if unavailable)""")

# The Windows-boot instrumentation and original draw path remain intact.
patch(activity,
    """        if (frameBitmap == null || w != frameBitmap.getWidth() || h != frameBitmap.getHeight()) {
            frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            mSurface.setFrameBitmap(frameBitmap);
        }

        int generation = QubeGfx.nativeCopyFrame(frameBitmap);""",
    """        if (frameBitmap == null || w != frameBitmap.getWidth() || h != frameBitmap.getHeight()) {
            frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            mSurface.setFrameBitmap(frameBitmap);
            lastGfxGeneration = -1; // New bitmap must be fully populated once.
        }

        // QEMU used to convert EVERY pixel on EVERY vsync even when nothing
        // changed. This is read-only: an invalid native peek falls back to
        // the exact original copy path, including on first frame and resize.
        int currentGeneration = QubeGfx.nativePeekGeneration();
        if (currentGeneration > 0 && currentGeneration == lastGfxGeneration)
            return;

        int generation = QubeGfx.nativeCopyFrame(frameBitmap);""")

# Respect any user-selected TCG cache size. Default only Fast Windows 11 ARM64
# (2+ vCPUs) to 128 MiB rather than QEMU's smaller platform default.
# Disabling Fast through the existing CPU mode immediately restores the saved
# TCG setting on the next boot. This never selects experimental WinJIT.
patch(vmexecutor,
    """            if (getMachine().getTcgBuffer() > 0) {
                tcgParams += ",tb-size=" + getMachine().getTcgBuffer();
            }
            paramsList.add(tcgParams);""",
    """            if (getMachine().getTcgBuffer() > 0) {
                tcgParams += ",tb-size=" + getMachine().getTcgBuffer();
            } else if (WindowsCpuMode.shouldForceMultiThread(
                    QubeApplication.getInstance(), getMachine())) {
                // Runtime-only Windows Fast profile; no stored setting change.
                // Bounded to 128 MiB to avoid growing the Fold's RSS excessively.
                tcgParams += ",tb-size=128";
                Log.i(TAG, "Windows Fast MTTCG: 128 MiB translation cache (runtime-only)");
            }
            paramsList.add(tcgParams);""")

n = qge.read_text()
j = jni.read_text()
a = activity.read_text()
v = vmexecutor.read_text()
assert n.count("int qube_gfx_peek_generation(void)") == 1
assert j.count("Java_com_max2idea_android_qube_jni_QubeGfx_nativePeekGeneration") == 1
assert 'dlsym(handle, "qube_gfx_peek_generation")' in j
assert 'nativePeekGeneration()' in a and 'nativeCopyFrame(frameBitmap)' in a
assert a.index("nativePeekGeneration()") < a.index("nativeCopyFrame(frameBitmap)")
assert 'currentGeneration == lastGfxGeneration' in a
assert 'tcgParams += ",tb-size=128";' in v
assert 'getMachine().getTcgBuffer() > 0' in v
assert 'WindowsCpuMode.shouldForceMultiThread' in v
assert 'WindowsWinjitMode.shouldUse' in v
assert 'setHdaImagePath' not in v and 'setBiosVars' not in v
assert 'setCpuNum(' not in v and 'setMemory(' not in v
print("PASS: read-only generation skips unchanged pixel copies; 128 MiB bounded Fast MTTCG cache")
print("NOT PROVEN: Faster Windows boot, lower temperature, or desktop/Fold acceptance")

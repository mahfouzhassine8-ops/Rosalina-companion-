#!/usr/bin/env python3
"""Research-only QGE <-> VirtIO GPU display/size bridge (QEMU 11.1.3).

Stage on a disposable pinned QubeVM 7.1.4 source copy. No QEMU replacement
library is produced by this staging script. Host control is opt-in only; default
Android capture remains RAMFB (console 0), with no guest/storage changes.

The QEMU callback is scheduled on the main AioContext, not called on Android's
UI/JNI thread; QEMU explicitly documents that qemu_console_set_ui_info()
updates device EDID/DisplayInfo via GraphicHwOps and should be debounced.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1])
native=root/"qube-android-lib/src/main/jni"
qge=native/"qemu-addons/11.1.3/ui/qge.c"
jni=native/"qube/qge-jni.c"
java=root/"qube-android-lib/src/main/java/com/max2idea/android/qube/jni/QubeGfx.java"

def patch(path:Path, before:str, after:str):
    text=path.read_text()
    n=text.count(before)
    if n!=1:
        raise RuntimeError(f"{path}: expected one QubeVM 7.1.4 source anchor, found {n}: {before[:80]!r}")
    path.write_text(text.replace(before,after,1))
    print("PASS: native QGE research patch",path.name)

if not all(p.is_file() for p in (qge,jni,java)):
    raise RuntimeError("Pinned QubeVM QGE bridge files missing")

patch(qge,
  '#include "qemu/osdep.h"',
  '''#include "qemu/osdep.h"
#include "qemu/aio.h"
#include "qemu/main-loop.h"
#include "qapi/error.h"''')

patch(qge,
  '''static DisplayChangeListener qube_gfx_dcl;''',
  r'''static DisplayChangeListener qube_gfx_dcl;

/*
 * QGE Fold resolution research. The QEMU guest boot display is RAMFB and
 * remains QGE console 0 by default. A distinct VirtIO GPU is only attachable
 * on a disposable Windows Mode copy. Calling this entrypoint is explicitly
 * opt-in, never done by normal VM startup.
 *
 * All QEMU console/listener mutation and guest EDID notification runs on
 * the QEMU main-loop context. Android JNI threads MUST NOT touch these APIs.
 */
typedef struct QubeFoldDisplayRequest {
    char *device_id;
    uint32_t width;
    uint32_t height;
    uint32_t refresh_millihz;
} QubeFoldDisplayRequest;

static void qube_gfx_fold_display_bh(void *opaque)
{
    QubeFoldDisplayRequest *req = opaque;
    QemuConsole *target = NULL;
    Error *err = NULL;

    if (!qube_gfx_lock_ready) {
        goto done;
    }

    if (req->device_id[0] == '\0') {
        target = qemu_console_lookup_by_index(0);  /* Boot-safe RAMFB */
    } else {
        /* Explicit QEMU ID allowlist; never switch to an arbitrary guest. */
        if (strcmp(req->device_id, "winvgpu") != 0) {
            goto done;
        }
        target = qemu_console_lookup_by_device_name("winvgpu", 0, &err);
        if (!target) {
            if (err) {
                error_report_err(err);
                err = NULL;
            }
            goto done;
        }
    }

    if (!target || !qemu_console_is_graphic(target)) {
        goto done;
    }
    if (qube_gfx_con != target) {
        qemu_console_unregister_listener(&qube_gfx_dcl);
        qemu_mutex_lock(&qube_gfx_lock);
        qube_gfx_surface = NULL; /* Invalidate old RAMFB/GPU pointer */
        qemu_mutex_unlock(&qube_gfx_lock);
        qatomic_inc(&qube_gfx_generation);
        qube_gfx_con = target;
        qube_gfx_dcl.con = target;
        qemu_console_register_listener(target, &qube_gfx_dcl, &qube_gfx_ops);
    }

    if (req->device_id[0] != '\0' &&
        qemu_console_ui_info_supported(target)) {
        QemuUIInfo ui = {0};
        /* QEMU UI info refresh_rate is MILLIhertz (e.g. 60000). */
        ui.width = req->width;
        ui.height = req->height;
        ui.width_mm = MAX(50, MIN((int)(req->width / 10), 500));
        ui.height_mm = MAX(50, MIN((int)(req->height / 10), 500));
        ui.refresh_rate = req->refresh_millihz;
        /* Allow one second for folding / IME resize to settle. */
        qemu_console_set_ui_info(target, &ui, true);
    }

done:
    if (err) {
        error_free(err);
    }
    g_free(req->device_id);
    g_free(req);
}

/* Asynchronously queues a Fold mode request; return 0 means queued, NOT
 * "Windows applied resolution" or "driver loaded".
 * device="winvgpu" selects the opt-in GPU, device="" restores RAMFB.
 * Export symbol for the existing libqube dlsym JNI bridge.
 */
int qube_gfx_request_fold_display(const char *device, int width,
                                  int height, int refresh_hz)
{
    QubeFoldDisplayRequest *req;
    if (!qube_gfx_lock_ready || !device ||
        (device[0] != '\0' && strcmp(device, "winvgpu") != 0)) {
        return -1;
    }
    if (width < 640 || width > 4096 || height < 480 || height > 4096 ||
        refresh_hz < 30 || refresh_hz > 120) {
        return -2;
    }
    req = g_new0(QubeFoldDisplayRequest, 1);
    req->device_id = g_strdup(device);
    req->width = (uint32_t)width;
    req->height = (uint32_t)height;
    req->refresh_millihz = (uint32_t)refresh_hz * 1000;
    aio_bh_schedule_oneshot(qemu_get_aio_context(),
                            qube_gfx_fold_display_bh, req);
    return 0;  /* Queued, not successful guest mode-set. */
}''')

patch(jni,
'''typedef void (*qube_gfx_set_interval_t)(int ms);''',
'''typedef void (*qube_gfx_set_interval_t)(int ms);
typedef int (*qube_gfx_request_fold_display_t)(const char *, int, int, int);''')
patch(jni,
'''static qube_gfx_set_interval_t qube_gfx_set_interval_fn = NULL;''',
'''static qube_gfx_set_interval_t qube_gfx_set_interval_fn = NULL;
static qube_gfx_request_fold_display_t qube_gfx_request_fold_display_fn = NULL;''')
patch(jni,
'''	qube_gfx_set_interval_fn = (qube_gfx_set_interval_t) dlsym(handle, "qube_gfx_set_interval_ms");''',
'''	qube_gfx_set_interval_fn = (qube_gfx_set_interval_t) dlsym(handle, "qube_gfx_set_interval_ms");
	qube_gfx_request_fold_display_fn = (qube_gfx_request_fold_display_t)
            dlsym(handle, "qube_gfx_request_fold_display");''')

src=jni.read_text()
src+=r'''

// Research-only: schedules a UI-size message on the QEMU main loop.
// No guest disk or VM saved configuration changes. Returning JNI_TRUE means
// QUEUED ONLY; caller must check actual framebuffer size and guest state.
JNIEXPORT jboolean JNICALL
Java_com_max2idea_android_qube_jni_QubeGfx_nativeRequestFoldGpuDisplay(
        JNIEnv *env, jclass clazz, jboolean use_gpu, jint width, jint height,
        jint refresh_hz) {
    if (!resolve_qube_gfx_syms() || !qube_gfx_request_fold_display_fn) {
        return JNI_FALSE;
    }
    const char *device_id = use_gpu == JNI_TRUE ? "winvgpu" : "";
    return qube_gfx_request_fold_display_fn(device_id, width, height, refresh_hz) == 0
           ? JNI_TRUE : JNI_FALSE;
}
'''
jni.write_text(src)

patch(java,
  '''    public static native void nativeSetRefreshRate(int hz);''',
  '''    public static native void nativeSetRefreshRate(int hz);

    // Experimental Windows GPU: true means QEMU has queued a request only,
    // not that Windows has applied a display mode. Never used by default.
    public static native boolean nativeRequestFoldGpuDisplay(
            boolean useGpu, int width, int height, int refreshHz);''')

n=qge.read_text()
j=jni.read_text()
assert 'qemu_console_lookup_by_device_name("winvgpu"' in n
assert 'qemu_console_lookup_by_index(0)' in n
assert 'qemu_console_unregister_listener(' in n
assert 'qemu_console_set_ui_info(target, &ui, true)' in n
assert 'aio_bh_schedule_oneshot(qemu_get_aio_context()' in n
assert 'return 0;  /* Queued, not successful guest mode-set. */' in n
assert 'qube_gfx_request_fold_display_fn' in j
assert 'nativeRequestFoldGpuDisplay' in java.read_text()
print("PASS: staged optional native QGE fold GPU switching and queued guest EDID hints")
print("NOT PROVEN: Windows driver binding or real guest resolution change")

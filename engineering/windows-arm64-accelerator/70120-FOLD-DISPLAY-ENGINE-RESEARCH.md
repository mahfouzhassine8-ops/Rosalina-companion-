# 7.1.20 — Windows 11 ARM64 native Fold video research

**This is a research-only continuation from the original-signature signed
7.1.19 Windows Mode checkpoint. Do not install or replace the user's disk.**
The user requires full inner and outer Fold displays, zero letterboxing,
zero distortion or cropping, and correct touch mapping after fold/unfold and
rotation. No on-device user test until a complete candidate is ready.

## Verified source findings

1. Fedora/Vectras 0.1.285 `viogpudo/w11/ARM64/viogpudo.inf` targets
   `PCI\VEN_1AF4&DEV_1050`, an appropriate QEMU `virtio-gpu-pci`
   hardware ID. The original INF includes `FlexResolution = 1`; this
   is **not proof** of successful Windows mode switching.
2. Pinned QEMU 11.1.3 exposes `qemu_console_set_ui_info` and its VirtIO GPU
   implementation `virtio_gpu_ui_info`. That updates per-scanout requested
   geometry, EDID inputs and sends `VIRTIO_GPU_EVENT_DISPLAY` to the guest.
   The notification does **not force Windows to apply the requested mode**.
3. Pinned QubeVM 7.1.4 `qemu-addons/11.1.3/ui/qge.c` binds its Android
   renderer to `qemu_console_lookup_by_index(0)`. An additional GPU may
   create a second QEMU console that the Android UI cannot see, hence an
   opt-in console selector is required.
4. QEMU 11.1.3 `ui/console.c` exports
   `qemu_console_lookup_by_device_name`,
   `qemu_console_register_listener`,
   `qemu_console_unregister_listener`, and
   `qemu_console_set_ui_info` (debounces 1 s). QEMU requires display
   listener mutation on its main event loop, not direct Android JNI thread.
5. Previous disposable QEMU test for simultaneous RAMFB + VirtIO GPU
   passed: https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43672394121
6. Original 7.1.19 signed APK and 37 ARM64 driver files remain protected;
   neither WinJIT CPU research nor the Windows installation is modified.

## Current code experiment (source-only)

`stage_qge_fold_gpu_bridge.py` adds a bounded optional native request
`qube_gfx_request_fold_display(device,width,height,refresh_hz)`.
Requests are posted via `aio_bh_schedule_oneshot(qemu_get_aio_context())`
and include a strict device allowlist:
- `device=""`: restore QEMU console 0, the original RAMFB guest boot view
- `device="winvgpu"`: select a second virtio-gpu console by exact QEMU device
  ID and notify VirtIO GPU of width, height, 30–120 Hz.
No mode request occurs by default, and a queued native request is **not**
proof Windows actually used the resolution.

The bridge also adds Java JNI method
`QubeGfx.nativeRequestFoldGpuDisplay(...)`.
Its native implementation must be independently compiled into the
ARM64 `libqube.so`, not merely declared in Java. The QEMU engine library
must export the corresponding C symbol; both libraries must be signed
inside the eventual same-identity APK.

## Release-blocking remaining engineering

- Source and native QEMU + JNI compile/link gates must pass.
- A disposable Windows guest must be supplied/tested legally to prove
  `viogpudo` binds 1AF4:1050 and Windows actually applies live dimensions
  through EDID/UIInfo events. No licensed Windows VM disk is present in CI.
- The GPU must be exposed **only to a separate disposable Windows VM with
  separate UEFI VARS**, keeping original RAMFB as a boot-safe fallback.
  No automatic GPU switch on the user's installed VM.
- Android must be able to select primary RAMFB vs secondary GPU console,
  request the view's actual live bounds on fold/unfold/IME/rotation, preserve
  exact inverse touch mapping, and restore RAMFB when GPU mode fails.
- Guest PnP drivers and retained signing catalogs may require user approval
  inside Windows. No silent driver injection, storage-controller switch,
  guest disk writes or assumptions about hardware GPU acceleration.
- A driver ISO alone cannot force Windows resolution changes. If automatic
  mode changes don't work through VirtIO GPU notifications, a guest agent
  and Windows ARM64 display helper are a separate prerequisite.
- The final device acceptance must show **both** displays (and rotations)
  without black bars/crop/stretched proportions, working Start/taskbar,
  correct touches, graceful shutdown, usable networking and sound.
- Only after these gates, produce one same-identity signed APK and perform
  one final Galaxy Fold acceptance test. Do not label research candidates
  device-verified or finished.

## References

- Pinned QEMU 11.1.3 ui/console.c, hw/display/virtio-gpu-base.c
- Pinned QubeVM 7.1.4 qemu-addons/11.1.3/ui/qge.c
- Fedora/Vectras virtio-win-0.1.285 ISO, SHA256
  e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331
- Official virtio-win `viogpu/viogpudo` driver source

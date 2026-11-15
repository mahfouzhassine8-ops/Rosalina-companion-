# Windows Mode 7.1.18 driver source / safety manifest

## Verified source identity

Vectras VM source code: `xoureldeen/Vectras-VM-Android`,
`app/src/main/java/com/vectras/vm/AppConfig.java` defines the VirtIO
Windows disc URL. It uses Red Hat/Fedora's archived release:
`https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/archive-virtio/virtio-win-0.1.285-1/virtio-win-0.1.285.iso`

SHA256 pinned to Microsoft openvmm-deps (Dockerfile.virtio-win):
`e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331`

ISO contains Windows 11 ARM64 signed driver families. App downloads the
full ISO only with explicit user consent and verifies SHA256 before
making it attachable as a read-only **second USB optical disc**.

The original boot NVMe controller, existing Windows installation and
firmware files are unchanged. No automatic guest driver installation
or attachment without user opt-in. No x64-only setup EXEs are executed.

## Driver availability vs applicability

- `NetKVM`: requires virtio-net network adapter; not the current E1000.
- `viostor` / `vioscsi`: require matching VirtIO disk controllers;
  **do not** replace the installed NVMe boot controller.
- `viogpudo`: requires a compatible virtio-gpu display; the current
  ARM64 Windows UEFI bootstrap uses `ramfb`, and changing the boot
  display without validation can break UEFI GOP.
- `vioinput`, `viorng`, `vioser`, `Balloon`, `viosock`, `viofs`,
  `viomem`: conditional on corresponding virtual hardware.
- HDA, USB xHCI/HID, NVMe, UEFI, and ACPI are currently emulated
  and use Windows-compatible drivers where available.
- QEMU guest agent, SPICE integration, reliable USB hotplug/reconnect,
  direct iPhone restore, hardware GPU acceleration, and Windows-native
  aspect-matched foldable display resolutions remain separate gates.

## Release criteria

- Signed APK only after source, native and ISO checks pass.
- Existing package ID/certificate and installed data preserved.
- 7.1.16 safe shutdown/fullscreen options preserved.
- No Windows driver file is claimed installed until Windows confirms it.
- No device test requested of the user before driver integration is
  finished. Source/CI tests are engineering evidence, not device proof.

## 7.1.18 compact built-in, exact-source Windows ARM64 driver disc

The complete Fedora driver ISO passed SHA-256, and only `w11/ARM64`
original INF, SYS, CAT, DLL and BIN driver files are extracted unchanged.
The original `virtio-win_license.txt` is preserved. The separate compact
ISO audit (GitHub Actions 43670731257) produced a **1,841,152-byte ISO**
containing 37 original driver files. The SHA-256 of each newly built ISO
is embedded in the APK's generated Android driver preparation code and
verified again against the exact asset inside the signed APK.

Verified driver families on the compact disc:
`Balloon`, `NetKVM`, `pvpanic`, `viofs`, `viogpudo`,
`vioinput`, `viomem`, `viorng`, `vioscsi`,
`vioserial` (contains `vioser.inf`), and `viostor`.

The compact disc is optional at next boot and is only prepared into
app-scoped storage after the user requests it. QEMU attaches it as a
read-only second USB storage device, preserves the original installer ISO
and NVMe boot controller, and blocks attachment to the experimental WinJIT
disposable-only sandbox.

**Not established**: Windows automatic installation of all drivers,
hardware matching on the installed guest, real GPU acceleration, native
aspect-matched Fold display mode switching, and actual Galaxy Fold success.
Those remain explicit integration and device gates before a user release.

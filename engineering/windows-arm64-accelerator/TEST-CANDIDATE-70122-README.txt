Windows Mode 7.1.22 PERFORMANCE EXPERIMENT — NOT DEVICE-VERIFIED
==================================================================
Purpose: reduce avoidable Android/QEMU rendering CPU work during Windows 11 ARM64
boot and improve the TCG translated-code cache while preserving working 7.1.21.

BEHAVIOR
1. New native QEMU read-only QGE display generation query. Android checks it
   BEFORE locking the bitmap and copying/recoloring millions of pixels each
   vsync. If unchanged since the last copied frame, skip the redundant copy.
   First frame, new bitmaps, and screen resizes always copy. Missing JNI/QEMU
   symbol falls back to the existing full-frame copy path. No polling timer,
   guest EDID, boot renderer, FPS mode or display scanout logic is altered.
2. If Windows 11 ARM64 Fast CPU Mode is selected, 2+ existing vCPUs are present
   and the user's TCG buffer is NOT explicitly configured, request a bounded
   128 MiB TCG translation buffer at runtime. Existing user TCG buffer
   preferences always take precedence. Compatibility CPU Mode restores
   the saved TCG setting. This does NOT select experimental WinJIT.
3. Existing Windows 11 OOBE Offline Account repair, safe ACPI shutdown,
   RAMFB boot display, USB drivers, optional GPU laboratory restrictions,
   Android package ID, original signing identity and runtime WinJIT libraries
   remain unchanged.

PROTECTION
- No access to or modifications of the user's qcow2 disk, Windows installation,
  installed software, license, network, E1000 NIC, UEFI VARS, BIOS, boot order,
  CPU core count, CPU model, RAM amount, graphics device or USB topology.
- Experimental WinJIT and second VirtIO GPU remain disposable-disk ONLY.
- Must stage from signed 7.1.21 baseline and verify original APK signature,
  v3 signer, native QEMU and Android JNI symbols, original code and asset ISO.
- Original native copy function ABI retained. On failure the existing behavior
  can be restored through 7.1.21 or selected Compatibility CPU Mode.

ACCEPTANCE/RELEASE BLOCKERS
- Native ARM64 QEMU and libqube source build must PASS independently.
- Complete Android APK must be built, original signature and version 70122
  verified and a downloadable TEST APK artifact produced.
- Actual reduction in Galaxy Fold CPU use and Windows boot time NOT PROVEN
  without paired on-device measurements and licensed Windows guest.
- Firmware/Windows driver behavior, full-resolution Fold display and WinJIT
  Windows boot remain separate, unverified issues.
- Do not describe rendering improvement as a proven Windows boot speedup.

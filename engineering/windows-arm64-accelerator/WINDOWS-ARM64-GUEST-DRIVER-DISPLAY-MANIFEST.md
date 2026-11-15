# Windows Mode — ARM64 guest driver, input, power and fullscreen compatibility plan

## Scope and baseline

Android package: `com.fold.windowsmode.research`. Protected guest installation and existing
QCOW2/UEFI VARS are not changed by the source stage. Native source branch for
the 7.1.15 WinJIT TEST candidate is `windows-arm64-qemu-accel-research`;
7.1.16 derives from it and keeps default TCG with experimental WinJIT opt-in
restricted to disposable storage.

Actual Windows 11 ARM64 on Fold boot observation: Windows wallpaper, Start bar,
Recycle Bin and File Explorer rendered; user reported slow Start/Power controls.
No Windows driver presence or true hardware acceleration was verified.

## Actual VM devices from supplied Android log

| Current exposed device | Guest driver strategy | Safe next action |
| --- | --- | --- |
| NVMe virtual disk (`-device nvme`) | Windows ARM64 native NVMe driver | Preserve boot disk/controller; verify Device Manager and performance |
| RAM framebuffer (`-device ramfb`) | Firmware framebuffer, Windows Basic Display | Keep boot-proven graphics path; adaptive view mapping in 7.1.16 |
| E1000 Ethernet (`-device e1000`) | Check if inbox Windows ARM64 driver works | Verify link/network before changing adapter |
| USB xHCI, USB keyboard, USB tablet | Windows ARM64 xHCI/HID standard drivers | Verify touch mapping, keyboard, mouse, hotplug |
| Intel HDA and HDA output | Check if Windows ARM64 guest has matching audio driver | Verify audio; don't auto-install unrelated x64 package |
| ACPI power event | Native QEMU `system_powerdown` via existing QMP interface | New explicit safe-shutdown request, no automatic forced stop |
| UEFI ARM64 firmware | Built-in Windows boot environment | Keep existing firmware and VARS untouched |
| TCG CPU runtime; optional WinJIT research | Emulator/translator, **not a Windows driver** | Preserve TCG boot path; WinJIT remains experimental |

## Conditional VirtIO packages (not currently required)

Drivers must be **Windows 11 ARM64** (not x86/x64) and signed by a trusted publisher.
They are useful ONLY after the matching QEMU virtual devices have been safely
added and verified on a disposable guest. Vectras VM's Software & Driver catalog
lists "Windows VirtIO Drivers" but the displayed catalog entry does not prove
that its download contains signed ARM64 binaries.

- `NetKVM`: only for virtio-net, never install as an E1000 replacement driver.
- `viostor` / `vioscsi`: only for corresponding VirtIO storage controllers.
  **Never switch the existing installed Windows boot disk controller blindly.**
- `viogpudo`: only for supported VirtIO GPU, not RAMFB. QEMU's ARM64 Windows
  UEFI GOP requires a linear framebuffer on the documented boot path; replacing
  RAMFB with VirtIO GPU without a boot-tested fallback can blank the display.
- `vioinput`: only for VirtIO input devices, not the existing USB tablet/HID.
- `vioser`, `viosock`, `viofs`, `Balloon`, `viorng`, `viomem`: attach
  matching virtual devices first and validate each package individually.
- QEMU Guest Agent, clipboard, SPICE guest tools: guest-host services that require
  supported host protocols as well as Windows guest installation.
- USB passthrough for iPhone recovery: separate host Android permissions,
  USB device hotplug/re-enumeration and Windows Apple device drivers. **No
  iPhone restore qualification has passed.**

Do not bundle an unchecked driver disc or run an x64 installer in the ARM64
guest. First obtain the upstream release URL and SHA256, unpack and inventory
`*.inf`, `*.sys`, `*.cat` files by architecture, hardware ID and signature,
then select compatible packages on a disposable guest only.

## Fold no-black-bars requirement

- User-facing goal: the Windows desktop reaches all four edges on both the
  inner and outer Fold screens and after live posture/rotation changes.
- 7.1.16 first stage: automatic Windows-only immersive mode and default
  adaptive edge-to-edge view transform; it retains Fit/Fill/Stretch choices and
  restores Android controls with Back. Display geometry and touch inversion
  continue to derive from the live Android view size.
- **Limitation:** with current static RAMFB modes, edge-to-edge may nonuniformly
  scale (distort) guest pixels. The APK cannot claim native proportional,
  zero-distortion resolution matching just from a canvas transform. Native
  matching requires a validated ARM64 guest display adapter/mode-set path.
- No cropping of the taskbar, no touch mismatch, and no persistent Android
  navigation or QubeVM toolbar bars when immersive mode is active.
- Preserve half-open Flex touchpad behavior when deliberately enabled; don't
  break the phone's Back escape or keyboard.
- Fold acceptance MUST exercise inner, cover, rotated inner, rotated cover,
  keyboard up/down, fold/unfold live, immersive exit and re-entry. Capture
  full view bounds and rendered framebuffer dimensions for every state.
- A fully native aspect-matched desktop is a separate release gate; remain
  candid about the temporary stretching fallback.

## Release gates

1. Source-only tests and all original protected tests pass.
2. Android native ARM64 QEMU and WinJIT libs byte-identical to verified inputs;
   no new CPU, disk, firmware or QEMU device flags in this UI-only stage.
3. Windows ARM64 safe ACPI shutdown sends QMP request and WAITs for the guest;
   failed/unsupported request does NOT force-stop Windows.
4. Existing Android package ID and signing certificate retained, versionCode
   increments to 70116; no uninstall or user-data reset.
5. A signed 7.1.16 TEST APK is produced after native/build gates; device
   testing is still mandatory before calling the feature fully verified.

Sources:
- User-supplied QubeVM log (2026-11-14)
- QubeVM 7.1.4 pinned source / QmpClient.java
- https://www.qemu.org/docs/master/system/whpx.html
- https://github.com/virtio-win/kvm-guest-drivers-windows-
- https://getvectras.com/download

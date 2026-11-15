Windows Mode 7.1.19 TEST CANDIDATE — safe shutdown and Fold display not yet device-verified
NOT DEVICE-VERIFIED. This is not a validated Windows/Fold release.

In-place package: com.fold.windowsmode.research, versionCode 70119.
Signed with the original 7.1.14 certificate; verify SIGNATURE_VERIFY.txt,
PACKAGE_VERIFY.txt, NATIVE-GATE-PROVENANCE.json and APK-SHA256.txt.
Keep the installed app's data when updating; do not uninstall or clear data.
This package contains the actual ARM64 QEMU WinJIT backend and translator.
TCG remains default. WinJIT is experimental and must be explicitly enabled.
No speedup, Windows boot success or Fold acceptance is claimed.

Existing Windows disks and default shared UEFI VARS are ineligible for WinJIT.
For a disposable test only, prepare a separate copy of the VM disk and VARS
under Android/data/com.fold.windowsmode.research/files/WinJIT-disposable/.
Configure a separate VM to use those copies; every attached media/kernel file
must be inside that directory. Shared folders and extra arguments must be empty.
A linked path escaping that directory is rejected. Do not move the original
installation there. The app does not copy, migrate, reset or replace your disk.
Select WinJIT (experimental) only with that disposable configuration while
stopped. Test writes are temporary (-snapshot). Select TCG to reverse the option.
The original VM continues to use TCG even if WinJIT was enabled for the test VM.

Passed in the current source engine: Cortex-A53 MIDR/PFR0/CNTFRQ parity with
TCG; Linux kernel + userspace at Cortex-A53 / 1 vCPU / 512 MiB; real guest
loads/stores above 4 GiB with 4 vCPUs and 6 GiB allocated. These tests use
synthetic ROMs or disposable Linux media, without accessing Windows disks.
Linux evidence: Actions run 43565914734. Native source provenance is included.

Known pending final gates: full TCG -cpu max feature parity (WinJIT currently
uses a conservative A57 baseline), full production guest/4-vCPU compatibility,
UEFI/NVMe/Windows desktop, interrupt/reset/storage/display qualification, and
physical Galaxy Fold upgrade/runtime.
The evidence reports actual results. Synthetic guest execution is not Windows
compatibility proof. No Windows image or licensed Windows content is included.

GPL source: https://github.com/mahfouzhassine8-ops/Rosalina-companion-
QubeVM baseline: rhn-1k/QubeVM 0264df6320a76234b47c9df923acb889b709e032.
QEMU 11.1.3: qemu/qemu tag v11.1.3.
WindowsiOS translator: ammaarreshi/WindowsiOS 0071d6975287fd720b9046dfd3bd80b6f2aa765e.
Packaging commit, complete scoped accelerator source and Android source patch
are included. Original upstream public Android test key is preserved, as in
7.1.14; this does not establish a production signing identity.

7.1.19 scoped changes:
- Windows-only QMP system_powerdown request from the Shutdown menu; waits for
  Windows, never silently executes a hard stop or claims QMP ack equals exit.
- The existing explicit Force Stop control is still present behind its warning.
- When Windows Mode's display activity opens, Android immersive edge-to-edge
  is enabled; Android Back restores the toolbar and virtual keys.
- New installations default to edge-to-edge bitmap scaling with no viewport
  black bars. Existing user-selected scaling remains; Fit, Fill and Stretch
  are all preserved.
- The built-in RAMFB guest resolution is NOT modified. A view transform may
  distort the desktop if the Windows guest ratio does not match the screen.
  True native, proportionally correct scaling requires separate ARM64 driver
  and display-modeset qualification. There is NO claim of it in this APK.
- No guest driver ISO is bundled or installed. The host/hardware inventory
  and safe driver research manifest are included with source.
- No changes to guest QCOW2, UEFI VARS, storage controller, VM config, Cobra,
  Infinity, or Rosalina chat/voice/image systems.
- Physical Fold acceptance requires front, inner, live folding/rotation,
  keyboard, input alignment, ACPI shutdown and cold restart. No claim of
  actual Windows guest ACPI shutdown before those tests succeed.

7.1.19 signed-driver ISO integration (guest drivers are NOT auto-installed):
- Reuse exact Vectras VM Fedora VirtIO 0.1.285 ISO source, pin SHA-256:
  e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331
  SHA256 cross-check: microsoft/openvmm-deps Dockerfile.virtio-win.
- In stopped Windows VM, select Windows ARM64 drivers; download over HTTPS
  with byte-limit, ISO9660 marker, streaming SHA-256 and temporary file.
  Opt-in attachment is independent from the verified download.
- Next boot: add a *second* read-only USB CD-ROM at bootindex 99,
  leaving original Win11 installer, NVMe boot disk, UEFI settings and
  stored VM profile alone. If driver ISO isn't verified, NOTHING is attached.
- Special-case WinJIT disposable media: refuse to attach this persistent
  driver ISO while the experimental storage snapshot gate is active.
- Repair pre-existing menu ID collision: Windows installed boot=18,
  experimental WinJIT=19, Windows ARM64 driver tools=20.
- Installed Windows may use Device Manager for signed Win11 ARM64 drivers.
  Neither an x64 driver installer nor any kernel driver is auto-executed.
- This is not proof of Windows ARM64 native VirtIO graphics acceleration,
  hardware pass-through, active driver installation, or Fold proportions.
  Do not switch current boot storage, network or firmware automatically.
- This is a development TEST candidate. User requested NO additional
  phone tests until the full driver and display work is completed.

7.1.19 OFFLINE WINDOWS 11 ARM64 DRIVER DISC:
- From pinned Fedora Vectras version 0.1.285 and original ISO SHA256
  e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331.
- 37 driver binaries, catalogs and INF files copied byte-for-byte. Includes
  NetKVM, viostor, vioscsi, vioinput, viorng, Balloon, vioserial, viofs,
  viogpudo, viomem and pvpanic. Original Fedora driver license included.
- Audit produced 1.84 MB compact guest ISO instead of 753 MB original download.
- Built into APK, preparation/copy to app storage requires user's explicit
  request while VM stopped. Full Fedora ISO download remains optional.
- Media is attached read-only on next boot only if prepared and requested.
  Existing VM disk, NVMe controller, UEFI VARS, Windows installer and CPU
  remain unchanged. WinJIT disposable-storage sandbox not bypassed.
- Copy from app asset includes full SHA256/ISO9660 verification. APK build
  separately proves exact compact ISO bytes are shipped and verifies the
  source-signed app identity and real native QEMU/WinJIT libraries.
- NO automatic Windows kernel-driver installation, no unsupported x64-only
  installers. Matching hardware and ARM64 signing/Device Manager remain
  guest-side requirements.
- Having an ARM64 viogpudo driver does NOT prove Windows GPU acceleration or
  native Fold resolution in the current RAMFB VM. Full no-distortion
  foldable display/mode-set and end-to-end Windows driver acceptance are
  still NOT VERIFIED. No production/release approval.
- The user asked not to be sent intermediate APKs for phone testing before
  the complete Windows/driver/display improvements. Respect that request.

7.1.19 guest-side manual matched-driver installer:
- Bundled ISO now includes INSTALL-MATCHING-ARM64-DRIVERS.cmd.
- No autorun; user must launch from Windows after the disc is attached.
- Requires native Windows ARM64 and administrator permission.
- Uses Microsoft's pnputil /add-driver *.inf /subdirs /install, which stages
  and installs matching present hardware driver packages only; no /force,
  /delete-driver, /reboot or controller change. Windows validates signing.
- A log is written to Windows' %TEMP% folder. This is untested on the installed
  Fold Windows guest; must not be called a device-verified release.

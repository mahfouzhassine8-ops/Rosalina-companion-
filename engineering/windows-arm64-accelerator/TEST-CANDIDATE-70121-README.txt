Windows Mode 7.1.21 — OFFLINE WINDOWS 11 OOBE REPAIR TEST APK
================================================================
STATUS: DEVELOPMENT TEST, NOT VERIFIED ON WINDOWS OR GALAXY FOLD.
DO NOT REPLACE OR REINSTALL YOUR WINDOWS DISK.

Purpose:
  Fix the 7.1.20 Windows Mode Offline Account toolbar control that merely
  typed "start ms-cxh:localonly" without opening Windows Command Prompt.
  The "Offline command sent" acknowledgement only proved that QEMU accepted
  keystrokes, not that Windows executed them or accepted the shortcut.

7.1.21 correction:
  1) Explicitly request guest Shift+F10 via the existing QMP/HMP route.
  2) Wait for the request, then ask the user to LOOK at Windows and confirm
     a guest Command Prompt window is actually visible.
  3) Only after that visual confirmation, send the full local-account command.
  4) Report keyboard delivery only; never claim the Windows guest succeeded.
  5) If local account dialog does not appear, stop retries and offer the
     already-existing Setup Tools > Offline fallback, subject to Windows
     version behavior. No automatic reboot or guest registry mutation.
  6) No boot-time guest commands or independent host-network changes.

Protection:
  Builds from the source and native libs pinned at 7.1.20. The same installed
  Android package ID and exact original certificate remain required by CI.
  Existing Windows QCOW2 disk, UEFI VARS, boot order, RAMFB, CPU, memory, USB,
  E1000 networking, drivers, optional discs and QMP safe shutdown are unchanged.
  Experimental WinJIT and second VirtIO GPU remain subject to existing
  disposable-only protections and are disabled for original Windows disk.
  Signing must pass before any APK can be described as installable.

Limits:
  Windows 11 builds can disable ms-cxh:localonly or the BypassNRO fallback.
  This app-level repair does not establish bypass success on installed Windows.
  E1000 NIC mismatch with bundled VirtIO NetKVM driver is a separate problem.
  No Windows guest credentials, disk modification or remote guest access.
  There is no claim of OOBE completion or on-device testing until observed.

Windows Mode 7.1.15 TEST CANDIDATE — WinJIT/Windows boot not yet verified
NOT DEVICE-VERIFIED. This is not a validated Windows/Fold release.

In-place package: com.fold.windowsmode.research, versionCode 70115.
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

Known pending final gates: full Linux userspace boot, CPU-feature/timer fidelity,
production -cpu max / 4 vCPU / 6 GiB compatibility, UEFI/NVMe/Windows desktop,
interrupts/reset/storage/display, and physical Galaxy Fold upgrade/runtime.
The evidence reports actual results. Synthetic guest execution is not Windows
compatibility proof. No Windows image or licensed Windows content is included.

GPL source: https://github.com/mahfouzhassine8-ops/Rosalina-companion-
QubeVM baseline: rhn-1k/QubeVM 0264df6320a76234b47c9df923acb889b709e032.
QEMU 11.1.3: qemu/qemu tag v11.1.3.
WindowsiOS translator: ammaarreshi/WindowsiOS 0071d6975287fd720b9046dfd3bd80b6f2aa765e.
Packaging commit, complete scoped accelerator source and Android source patch
are included. Original upstream public Android test key is preserved, as in
7.1.14; this does not establish a production signing identity.

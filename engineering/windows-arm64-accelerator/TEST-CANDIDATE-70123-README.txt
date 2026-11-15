Windows Mode 7.1.23 — BACKUP-FIRST SETUP RECOVERY TEST CANDIDATE
================================================================
Scope: User's Samsung Galaxy Fold Windows 11 ARM64 installed VM based on the
already-signed Windows Mode 7.1.22. This does not prove that Windows is repaired.

On-device observation:
- Installed Windows reaches OOBE, then loops to "Why did my PC restart?"
- Guest Shift+F10 / Command Prompt worked.
- "start ms-cxh:localonly" returned "Class not registered" on this Windows build.
- The user's pictures show setuperr.log lines involving setup initialization,
  missing paths, hardware telemetry, EFI/BCD and snapshot errors. These entries
  may be from earlier installation phases and DO NOT prove a corrupt bootloader.
- Windows command prompt displayed 10.0.26300.9457.
- Full Panther setupact.log and current OOBE state remain uninspected.

Official Microsoft setup-log guidance:
https://learn.microsoft.com/en-us/windows-hardware/manufacture/desktop/deployment-troubleshooting-and-log-files?view=windows-11
Official temporary Audit Mode instructions:
https://learn.microsoft.com/en-us/windows-hardware/manufacture/desktop/boot-windows-to-audit-mode-or-oobe?view=windows-11

User experience:
A. When VM is OFF, Windows Mode main menu includes "Windows setup recovery
   backup." On explicit approval:
   * Locate current Windows QCOW2 and selected UEFI vars (default UEFI vars
     resolved to current QubeVM ARM64 VARS path), do NOT change the VM paths.
   * Verify source files exist, are readable and regular, not symlinks, and
     that sufficient free space for both full copies plus safety margin exists.
   * Copy to app-scoped WindowsRecovery/backup-<timestamp> area on same phone.
   * SHA-256 verify the backup payload against source-during-copy digest;
     reject any source changes, VM activity or canceled/incomplete backups.
   * Write VERIFIED-BACKUP.txt only after both copies have passed, with paths,
     lengths and hashes. Original disk/UEFI remain untouched.
   * Do not automatically restore anything. Backups inside app storage can
     disappear if user uninstalls/clears data; preserving them off-phone is best.
B. While running Windows OOBE, virtual keyboard offers "Setup Rescue":
   * Read HKLM\SYSTEM\Setup with REG QUERY from an open guest CMD.
   * Read HKLM\SYSTEM\Setup\Status\ChildCompletion with REG QUERY.
   * Show accurate key-delivery vs guest-accepted distinctions; user sends
     screenshots. NO registry edits, no BCD edits, no account creation.
   * OPTIONAL Ctrl+Shift+F3 Windows Audit Mode only after a completed verified
     full disk+UEFI backup. It may reboot into a TEMPORARY Administrator desktop
     used for troubleshooting, NOT completed Windows setup. No auto Sysprep.
   * Existing Setup Tools, Offline Account, Safe Shutdown are preserved.
C. No auto-retry, WinJIT enable, GPU changes, NetKVM/E1000 changes, boot ISO
   replacement, driver install, or VM reboot on normal usage.

Critical boundaries:
- The new functions are NOT proof of recovery, login, or better boot times.
- No automatic 0xC000000F bootloader reconstruction: the VM visibly boots OOBE.
- No blindly forcing ChildCompletion/setup.exe registry values, which could
  make an incomplete installation look complete while leaving it unusable.
- No automatic new local accounts or passwords.
- Original package ID com.fold.windowsmode.research and signing certificate
  SHA256 a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc
  must match. Version code 70123. No uninstall or data reset.
- Retain 7.1.22 MTTCG/cache, native optimized QEMU graphics ABI, existing
  WinJIT disposable guards, Fold display research and 7.1.21 Offline Account.

Release gate:
Source regression PASS, native provenance PASS, actual Android ARM64
compile PASS, signed APK original signer/version check PASS, verified native
lib bytes and untouched ISO PASS, publish APK with checksum. Hardware tests
must occur only after this build. No claim of device verification in CI.

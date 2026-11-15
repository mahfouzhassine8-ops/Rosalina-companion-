#!/usr/bin/env python3
"""7.1.23 Windows OOBE recovery safety regression checks, not Windows guest proof."""
from pathlib import Path
import sys
if len(sys.argv)!=2:
    raise SystemExit("usage: test_windows_setup_rescue.py UPSTREAM")
root=Path(sys.argv[1])/"qube-android-lib/src/main/java/com/max2idea/android/qube"
app=(root/"main/QubeActivity.java").read_text()
r=(root/"main/WindowsSetupRecovery.java").read_text()
keys=(root/"virtualkeys/VirtualKeys.java").read_text()
enums=(root/"virtualkeys/VirtualKey.java").read_text()
layout=(root/"virtualkeys/VirtualKeyLayoutConfig.java").read_text()
for token in ['WINDOWS_SETUP_RECOVERY = 24', 'WindowsSetupRecovery.show(this,getMachine())']:
    assert token in app,token
assert 'SetupRescue(null' in enums
assert 'VirtualKey.SetupRescue' in layout
assert 'private void showWindowsSetupRescue()' in keys
assert 'private void confirmSetupRescue(int which)' in keys
assert 'CMD visible - read status' in keys
assert 'typeSetupCommand(command)' in keys
assert 'reg query hklm\\\\system\\\\setup' in keys
assert 'reg query hklm\\\\system\\\\setup\\\\status\\\\childcompletion' in keys
assert 'ctrl-shift-f3' in keys and 'Audit Mode - TEMPORARY DESKTOP' in keys
# Never send a guest write command or reboot except opt-in Windows Audit Mode.
flow=keys[keys.index('    private void showWindowsSetupRescue()'):
    keys.index('    private void showWindowsDesktopRecovery()')]
assert 'WindowsSetupRecovery.hasBackup(activity,vm)' in flow
assert flow.index('WindowsSetupRecovery.hasBackup(activity,vm)') < flow.index('sendSetupHmpKey("ctrl-shift-f3")')
for forbidden in ["reg add", "reg delete", "shutdown /r", "bcdedit /set",
                  "bcdboot ", "diskpart", "format ", "sysprep /generalize"]:
    assert forbidden not in flow.lower(),forbidden
# Backup must be a stopped-VM, separately stored, source-sha verified copy.
for token in ['MachineController.getInstance().isRunning()',
              'source.lastModified()', 'MessageDigest.getInstance("SHA-256")',
              'digest.equals(verifyDigest(target))', 'root.getUsableSpace() < needed',
              'if (target.exists()) throw', 'getHdaImagePath()', 'getBiosVars()',
              'Config.UEFI_ARM_VARS', 'VERIFIED-BACKUP.txt',
              'windows-before-repair.qcow2','uefi-vars-before-repair.fd',
              'FileOutputStream','getFD().sync()','deletePartialDir(dir)']:
    assert token in r,token
assert r.index('String diskDigest = safeCopy') < r.index('String varsDigest = safeCopy')
assert r.index('String varsDigest = safeCopy') < r.rindex('VERIFIED-BACKUP.txt')
assert r.index('VERIFIED-BACKUP.txt') < r.index('putString(pathKey(vm),')
for forbidden in ['setHdaImagePath(', 'setBiosVars(', 'setCpuNum(',
                  'setMemory(', 'diskpart', 'renameTo(source', 'RandomAccessFile']:
    assert forbidden not in r,forbidden
assert 'start ms-cxh:localonly' in keys # Prior offline button remains.
assert 'QubeOfflineAccount' in keys
assert 'showWindowsDesktopRecovery()' in keys
print("PASS: separate immutable source snapshots of VM disk and UEFI with verify/space/stop gates")
print("PASS: read-only OOBE registry queries and user-initiated Audit Mode after backup")
print("PASS: existing offline-account, native GPU/WinJIT/USB and disk configuration preserved")
print("NOTE: no real Windows repair or desktop login claimed")

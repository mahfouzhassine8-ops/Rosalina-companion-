#!/usr/bin/env python3
"""Repair Windows 11 OOBE Offline Account guest-input sequence (7.1.21).

Run after the protected 7.1.20 Android source stages. Only edits generated
VirtualKeys.java. Never touches user disk, guest firmware, VM hardware,
preexisting 7.1.20 branch, Android package identity, or signing material.

The old Offline Account control *only typed* start ms-cxh:localonly, without
opening Command Prompt; QMP reporting "sent" did not prove guest execution.
The repaired control first sends Shift+F10, asks the user to VISUALLY confirm
the guest Command Prompt exists, and only then types the command. Neither
keyboard acceptance nor Windows OOBE support is claimed as guest success.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: stage_windows_oobe_offline_fix.py UPSTREAM")

root = Path(sys.argv[1])
target = root / "qube-android-lib/src/main/java/com/max2idea/android/qube/virtualkeys/VirtualKeys.java"
if not target.is_file():
    raise RuntimeError("Pinned staged QubeVM VirtualKeys.java does not exist")
original = target.read_text()

head = "            } else if (vk == VirtualKey.OfflineSetup) {\n"
tail = "            } else if (vk == VirtualKey.SetupBypass) {"
anchor = "    private void showWindowsSetupTools() {"
for label, item in (("old offline branch", head), ("following setup branch", tail), ("setup tools method", anchor)):
    if original.count(item) != 1:
        raise RuntimeError(f"{label}: expected exact one source anchor, got {original.count(item)}")

start = original.index(head)
end = original.index(tail, start)
prior = original[start:end]
for required in (
    'typeSetupCommand("start ms-cxh:localonly")',
    '.setPositiveButton("Type offline command"',
    'setTitle("Windows 11 offline account")',
    'return;',
):
    if required not in prior:
        raise RuntimeError(f"Old Offline Account behavior differs from verified 7.1.20 source: {required}")

replacement = """            } else if (vk == VirtualKey.OfflineSetup) {
                view.setOnClickListener(v -> showWindowsOfflineAccount());
"""
patched = original[:start] + replacement + original[end:]

offline_method = r'''    // Explicit two-step OOBE input: never mistake QEMU accepting keys for
    // Windows opening CMD or accepting the local-account URI.
    private void showWindowsOfflineAccount() {
        if (!isWindowsTarget()) {
            ToastUtils.toastShort(activity, "Windows 11 ARM64 VM required");
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Windows 11 offline account - step 1")
                .setMessage("First request Command Prompt with Shift+F10. "
                        + "The app cannot see Windows guest windows, so it will "
                        + "NOT type the local account command until you confirm "
                        + "Command Prompt is actually visible. "
                        + "No VM settings, disk, network hardware, or UEFI are changed.")
                .setPositiveButton("Open Command Prompt", (dialog, which) -> {
                    new Thread(() -> {
                        boolean delivered = sendSetupHmpKey("shift-f10");
                        if (delivered) delivered = waitSetupInput(3500);
                        final boolean keyDelivered = delivered;
                        activity.runOnUiThread(() -> {
                            if (!keyDelivered) {
                                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                        .setTitle("Could not send Shift+F10")
                                        .setMessage("QEMU did not acknowledge the command. "
                                                + "No local-account command was typed. "
                                                + "Check internal QMP and the running VM.")
                                        .setPositiveButton("OK", null)
                                        .show();
                                return;
                            }
                            new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                    .setTitle("Step 2 - check the Windows screen")
                                    .setMessage("QEMU accepted Shift+F10, but this DOES NOT prove "
                                            + "Windows opened Command Prompt. "
                                            + "Only continue if a black Command Prompt window "
                                            + "is now visible inside Windows. "
                                            + "If it is not visible, cancel; nothing else is sent.")
                                    .setPositiveButton("CMD visible - type command", (d, w) -> {
                                        new Thread(() -> {
                                            boolean sent = typeSetupCommand("start ms-cxh:localonly");
                                            final boolean typed = sent;
                                            activity.runOnUiThread(() ->
                                                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                                            .setTitle(typed
                                                                    ? "Keys delivered - guest result UNVERIFIED"
                                                                    : "Offline command delivery interrupted")
                                                            .setMessage(typed
                                                                    ? "QEMU accepted the local account command's keys. "
                                                                    + "Only Windows can confirm it worked. "
                                                                    + "If no local account screen appears, "
                                                                    + "do NOT repeatedly send the shortcut. "
                                                                    + "Windows may block this method; use "
                                                                    + "Setup Tools > Offline fallback after inspecting "
                                                                    + "the guest result."
                                                                    : "Command was not fully delivered. "
                                                                    + "No VM hardware, disk or UEFI configuration changed.")
                                                            .setPositiveButton("OK", null)
                                                            .show());
                                        }, "WindowsOfflineAccount-TypeAfterConfirm").start();
                                    })
                                    .setNegativeButton("No CMD - stop", null)
                                    .show();
                        });
                    }, "WindowsOfflineAccount-OpenCMD").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

'''
if patched.count(anchor) != 1:
    raise RuntimeError("Expected one existing Setup Tools method after branch patch")
patched = patched.replace(anchor, offline_method + anchor, 1)

for required in ("showWindowsOfflineAccount();", 'sendSetupHmpKey("shift-f10")',
                 'typeSetupCommand("start ms-cxh:localonly")',
                 "CMD visible - type command", "No CMD - stop",
                 "Windows may block this method"):
    if required not in patched:
        raise RuntimeError(f"New offline-account step missing: {required}")
if patched.count('typeSetupCommand("start ms-cxh:localonly")') != 2:
    # One in the new two-step method, one in existing Setup Tools.
    raise RuntimeError("Unexpected extra local-account commands in generated UI")
if "setHdaImagePath(" in offline_method or "setBiosVars(" in offline_method or "shutdown /r" in offline_method:
    raise RuntimeError("Offline account flow must not mutate disk/VM or restart Windows")
target.write_text(patched)
print("PASS: old unguarded Offline Account replaced with CMD-first, visually confirmed guest input")

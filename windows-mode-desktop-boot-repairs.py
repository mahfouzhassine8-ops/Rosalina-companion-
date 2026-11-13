#!/usr/bin/env python3
"""Windows Mode 7.1.13: preserve Win11 installation, repair portrait view and support boot diagnostics.

Runs after all 7.1.9, 7.1.10, 7.1.11 and 7.1.12 patches against pinned QubeVM 7.1.4.
Only edits Java source in the isolated Android app build. NEVER rewrites the guest
QCOW2, UEFI VARS, selected ISO, saved VM CPU configuration, or network settings.
"""
from pathlib import Path
import sys

base=Path(sys.argv[1] if len(sys.argv)>1 else "upstream")
src=base/"qube-android-lib/src/main/java/com/max2idea/android/qube"
def patch(name,old,new):
    file=src/name
    s=file.read_text()
    seen=s.count(old)
    if seen!=1:
        raise AssertionError(f"{name} unexpected anchor ({seen}): {old[:85]!r}")
    file.write_text(s.replace(old,new))
    print("SCOPED PATCH",name)

# Actual layout defect: the stock portrait mode deliberately displays a
# second, blank half-height "gap" as a legacy trackpad. On a Fold inner screen
# this wastes half the available area and pins the Win11 4:3 desktop high up.
# 7.1.10's real Flex pad makes that obsolete, but only for Windows Mode. Keep
# legacy behavior for all other QubeVM guests. Do not crop Windows taskbar.
patch("main/QubeQGEActivity.java",
'''        RelativeLayout mVncContainer = findViewById(R.id.qge);
        mVncContainer.addView(mSurface);''',
'''        RelativeLayout mVncContainer = findViewById(R.id.qge);
        // Explicitly size the QEMU guest surface to the entire available
        // pane; otherwise Android's default child params may constrain it.
        RelativeLayout.LayoutParams guestFrameParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        mVncContainer.addView(mSurface, guestFrameParams);''')
patch("main/QubeQGEActivity.java",
'''    public void updateLayout(int orientation) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT)
            mGap.setVisibility(View.VISIBLE);
        else
            mGap.setVisibility(View.GONE);
    }''',
'''    public void updateLayout(int orientation) {
        if (WindowsDisplayOptions.isWindowsGuest(this)) {
            // Windows Mode has its own real half-fold Flex touchpad. The old
            // legacy blank trackpad gap must NEVER consume half the portrait
            // screen on the Fold, including after fold/unfold transitions.
            mGap.setVisibility(View.GONE);
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            mGap.setVisibility(View.VISIBLE);
        } else {
            mGap.setVisibility(View.GONE);
        }
        if (mSurface != null) {
            mSurface.requestLayout();
            mSurface.invalidate();
        }
    }''')

# Optional, per-app, reversible boot policy. With installed Windows visible
# on NVMe, the 8.5 GB Windows installer USB CD is still attached every time.
# This adds firmware and device enumeration and can offer installer boot order
# before the Windows boot entry. Skip its attachment only when user enables
# this mode with the VM OFF. Its original selection remains persisted so a
# recovery boot is a one-tap rollback. No assertions of guaranteed speedups.
boot=src/"main/WindowsInstalledBoot.java"
if boot.exists():
    raise RuntimeError("Refusing to overwrite an existing WindowsInstalledBoot.java")
boot.write_text(r'''/*
 * Copyright (C) 2026 Windows Mode contributors
 * QubeVM GPL-3.0 derivative — reversible runtime boot policy.
 */
package com.max2idea.android.qube.main;

import android.content.Context;
import com.max2idea.android.qube.machine.Machine;
import java.util.Locale;

public final class WindowsInstalledBoot {
    private static final String PREF="windows_mode_installed_boot";
    private static final String KEY="skip_installer_usb_iso";
    private WindowsInstalledBoot() {}

    public static boolean supported(Context context, Machine vm) {
        if (!WindowsCpuMode.isTargetWindowsVM(context,vm))
            return false;
        String disk=vm.getHdaImagePath();
        String iso=vm.getCdImagePath();
        if (disk==null || iso==null)
            return false;
        String d=disk.toLowerCase(Locale.ROOT);
        String i=iso.toLowerCase(Locale.ROOT);
        return "nvme".equals(vm.getHdaInterface())
                && d.endsWith(".qcow2")
                && (d.contains("windows 11") || d.contains("windows11")
                     || d.contains("win11"))
                && i.endsWith(".iso")
                && (i.contains("windows11") || i.contains("win11"));
    }

    public static boolean enabled(Context context,Machine vm) {
        return supported(context,vm)
                && context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                    .getBoolean(KEY,false);
    }

    public static void setEnabled(Context context,Machine vm,boolean enabled) {
        if (!supported(context,vm))
            return;
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .edit().putBoolean(KEY,enabled).apply();
    }
}
''')

patch("jni/VMExecutor.java",
'''import com.max2idea.android.qube.main.WindowsCpuMode;''',
'''import com.max2idea.android.qube.main.WindowsCpuMode;
import com.max2idea.android.qube.main.WindowsInstalledBoot;''')
patch("jni/VMExecutor.java",
'''        String cdImagePath = getDriveFilePath(getMachine().getCdImagePath());
        if (cdImagePath != null) {''',
'''        boolean bootInstalledWindows = WindowsInstalledBoot.enabled(
                QubeApplication.getInstance(), getMachine());
        // Only the runtime QEMU argument list changes. The VM's saved CD
        // selection remains untouched, so recovery remains one toggle away.
        String cdImagePath = bootInstalledWindows ? null
                : getDriveFilePath(getMachine().getCdImagePath());
        if (bootInstalledWindows) {
            Log.i(TAG, "Windows installed-boot mode: skipping installer USB ISO "
                    + "attachment for this boot only; selected ISO unchanged");
        }
        if (cdImagePath != null) {''')

patch("main/QubeActivity.java",
'''    private static final int WINDOWS_CPU_MODE = 17;''',
'''    private static final int WINDOWS_CPU_MODE = 17;
    private static final int WINDOWS_INSTALLED_BOOT = 18;''')
patch("main/QubeActivity.java",
'''            menu.add(0, WINDOWS_CPU_MODE, 0, "Windows CPU Mode")
                    .setIcon(R.drawable.settings_24px);''',
'''            menu.add(0, WINDOWS_CPU_MODE, 0, "Windows CPU Mode")
                    .setIcon(R.drawable.settings_24px);
            if (WindowsInstalledBoot.supported(this, getMachine()))
                menu.add(0, WINDOWS_INSTALLED_BOOT, 0, "Windows installed boot")
                        .setIcon(R.drawable.settings_24px);''')
patch("main/QubeActivity.java",
'''        } else if (item.getItemId() == WINDOWS_CPU_MODE) {
            showWindowsCpuMode();
        } else if (item.getItemId() == TOOLS) {''',
'''        } else if (item.getItemId() == WINDOWS_CPU_MODE) {
            showWindowsCpuMode();
        } else if (item.getItemId() == WINDOWS_INSTALLED_BOOT) {
            showWindowsInstalledBoot();
        } else if (item.getItemId() == TOOLS) {''')
patch("main/QubeActivity.java",
'''    private void showWindowsCpuMode() {''',
'''    private void showWindowsInstalledBoot() {
        if (MachineController.getInstance().isRunning())
            return;
        final Machine vm=getMachine();
        if (!WindowsInstalledBoot.supported(this,vm))
            return;
        boolean enabled=WindowsInstalledBoot.enabled(this,vm);
        final String[] labels = {
                "Boot installed Windows — skip setup ISO (reversible)",
                "Recovery / install — keep original setup ISO"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Windows installed boot")
                .setMessage("Your existing Windows NVMe disk, selected ISO, UEFI, "
                        + "and machine settings remain untouched. When enabled, "
                        + "QEMU omits the Windows installer USB CD-ROM for its "
                        + "next boot, avoiding needless installer enumeration. "
                        + "If you need the Windows installer or recovery tools, "
                        + "switch back before starting the VM. This cannot "
                        + "guarantee a faster Windows desktop; CPU emulation "
                        + "and Windows first-time setup still take time.")
                .setSingleChoiceItems(labels,enabled?0:1,(dialog,choice)->{
                    WindowsInstalledBoot.setEnabled(this,vm,choice==0);
                    dialog.dismiss();
                    ToastUtils.toastShort(this,choice==0
                            ? "Installed-Windows boot enabled for next VM start"
                            : "Original Windows installer ISO restored for next start");
                })
                .setNegativeButton(android.R.string.cancel,null)
                .show();
    }

    private void showWindowsCpuMode() {''')

# Windows has shown its wallpaper but no Explorer taskbar. An Android
# bitmap/scaling filter cannot manufacture missing Windows shell UI. Expose
# direct and confirmed guest keyboard recovery controls to open Task Manager
# or prompt Windows Explorer to launch. No guest commands sent automatically.
patch("virtualkeys/VirtualKey.java",
'''    NetworkCheck(null, "Net Check", 0, "Read-only QEMU network adapter report", false),''',
'''    NetworkCheck(null, "Net Check", 0, "Read-only QEMU network adapter report", false),
    DesktopHelp(null, "Desktop Fix", 0, "Recover Windows Task Manager or Start/Explorer", false),''')
patch("virtualkeys/VirtualKeyLayoutConfig.java",
'''VirtualKey.SetupTools, VirtualKey.NetworkCheck, VirtualKey.LeftCtrl,''',
'''VirtualKey.SetupTools, VirtualKey.NetworkCheck, VirtualKey.DesktopHelp, VirtualKey.LeftCtrl,''')
patch("virtualkeys/VirtualKeyLayoutConfig.java",
'''VirtualKey.SetupTools, VirtualKey.NetworkCheck)''',
'''VirtualKey.SetupTools, VirtualKey.NetworkCheck, VirtualKey.DesktopHelp)''')
patch("virtualkeys/VirtualKeys.java",
'''            } else if (vk == VirtualKey.NetworkCheck) {
                view.setOnClickListener(v -> readWindowsNetworkReport());''',
'''            } else if (vk == VirtualKey.DesktopHelp) {
                view.setOnClickListener(v -> showWindowsDesktopRecovery());
            } else if (vk == VirtualKey.NetworkCheck) {
                view.setOnClickListener(v -> readWindowsNetworkReport());''')
patch("virtualkeys/VirtualKeys.java",
'''    private void initToggleKey(final ToggleButton key, final int keyCode) {''',
'''    private void showWindowsDesktopRecovery() {
        if (!isWindowsTarget()) {
            ToastUtils.toastShort(activity,"Windows 11 ARM64 VM only");
            return;
        }
        String[] choices={
                "Open Task Manager (Ctrl+Shift+Esc)",
                "Try Run > explorer (Windows+R)",
                "Windows security screen (Ctrl+Alt+Delete)"
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Windows desktop / Start repair")
                .setMessage("If wallpaper appears but Start and taskbar do not, "
                        + "Windows Explorer may still be starting, unresponsive, "
                        + "or missing. This is not a display-scaling problem. "
                        + "Choose a key sequence to send once; verify the "
                        + "Windows result before taking further action. "
                        + "Your installed Windows disk is not modified by the app.")
                .setItems(choices,(d,which)-> {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                            .setTitle("Confirm Windows desktop action")
                            .setMessage(which==0
                                    ? "Try Ctrl+Shift+Esc. Windows should open Task Manager. "
                                    + "You can use its Run new task action to open explorer.exe."
                                    : which==1
                                    ? "Try Windows+R and type explorer. This may open "
                                    + "the Windows shell if Run is working; Windows can ignore it "
                                    + "while setup is still finishing."
                                    : "Send Ctrl+Alt+Delete to open Windows security options. "
                                    + "No VM reset is performed.")
                            .setPositiveButton("Send", (a,b)->new Thread(()->{
                                boolean sent;
                                if (which==0) {
                                    sent=sendSetupHmpKey("ctrl-shift-esc");
                                } else if (which==1) {
                                    sent=sendSetupHmpKey("meta_l-r");
                                    if (sent) sent=waitSetupInput(1500);
                                    if (sent) sent=typeSetupCommand("explorer");
                                } else {
                                    sent=sendSetupHmpKey("ctrl-alt-delete");
                                }
                                final boolean ok=sent;
                                activity.runOnUiThread(()->new
                                        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                                .setTitle(ok ? "Keys delivered" : "Key delivery failed")
                                                .setMessage(ok
                                                        ? "Check the Windows screen. QEMU accepted "
                                                        + "the key sequence; this does NOT verify "
                                                        + "Windows executed it. Never reformat "
                                                        + "the existing Windows disk."
                                                        : "The emulator did not confirm all keys. "
                                                        + "No VM settings were altered.")
                                                .setPositiveButton("OK",null)
                                                .show());
                            },"WindowsDesktopRecovery").start())
                            .setNegativeButton(android.R.string.cancel,null)
                            .show();
                })
                .setNegativeButton("Close",null)
                .show();
    }

    private void initToggleKey(final ToggleButton key, final int keyCode) {''')

# Fail-closed guards; printed at build time and checked again by Gradle.
exe=(src/"jni/VMExecutor.java").read_text()
ui=(src/"main/QubeQGEActivity.java").read_text()
main=(src/"main/QubeActivity.java").read_text()
keys=(src/"virtualkeys/VirtualKeys.java").read_text()
assert 'String cdImagePath = bootInstalledWindows ? null' in exe
assert 'selected ISO unchanged' in exe
assert 'WindowsDisplayOptions.isWindowsGuest(this)' in ui
assert 'mGap.setVisibility(View.GONE)' in ui
assert 'mVncContainer.addView(mSurface, guestFrameParams)' in ui
assert 'WindowsInstalledBoot.setEnabled' in main
assert 'showWindowsDesktopRecovery()' in keys
assert 'ctrl-shift-esc' in keys and 'meta_l-r' in keys
assert 'typeSetupCommand("explorer")' in keys
assert 'setHdaImagePath' not in (src/"main/WindowsInstalledBoot.java").read_text()
assert 'setCdImagePath' not in (src/"main/WindowsInstalledBoot.java").read_text()
assert 'setEnableKVM' not in (src/"main/WindowsInstalledBoot.java").read_text()
print("7.1.13 WINDOWS LAYOUT + NONDESTRUCTIVE BOOT + RECOVERY SOURCE GATES PASS")

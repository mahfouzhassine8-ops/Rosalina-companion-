#!/usr/bin/env python3
"""Windows Mode 7.1.23: stopped-VM verified backup and read-only OOBE triage.

Apply after 7.1.22 and the protected setup/CPU/native staging. Only changes
generated Android Java from pinned QubeVM 7.1.4. No Windows QCOW2, EFI vars,
VM database, networking, CPU, GPU, USB, firmware or signing identity changes.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: stage_windows_setup_rescue.py UPSTREAM")
root = Path(sys.argv[1])
src = root/"qube-android-lib/src/main/java/com/max2idea/android/qube"
activity = src/"main/QubeActivity.java"
model = src/"main/WindowsSetupRecovery.java"
vk = src/"virtualkeys/VirtualKey.java"
layout = src/"virtualkeys/VirtualKeyLayoutConfig.java"
keys = src/"virtualkeys/VirtualKeys.java"
for f in (activity, vk, layout, keys):
    if not f.is_file(): raise RuntimeError(f"Pinned Java source missing {f}")
if model.exists(): raise RuntimeError("Recovery class already exists: refuse overwrite")

def patch(path, before, after):
    content = path.read_text()
    n = content.count(before)
    if n != 1:
        raise RuntimeError(f"{path.name} expected one anchor, found {n}: {before[:100]!r}")
    path.write_text(content.replace(before, after, 1))
    print("PASS setup recovery scoped patch", path.name)

model.write_text(r'''package com.max2idea.android.qube.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.max2idea.android.qube.files.FileUtils;
import com.max2idea.android.qube.machine.Machine;
import com.max2idea.android.qube.machine.MachineController;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-approved, stopped-VM backup; no guest repair automatically executed. */
public final class WindowsSetupRecovery {
    private WindowsSetupRecovery() {}
    private static final AtomicBoolean COPYING = new AtomicBoolean(false);
    private static final String PREFS = "windows_setup_recovery_70123";

    private static boolean target(Context c, Machine vm) {
        return vm != null && WindowsCpuMode.isTargetWindowsVM(c, vm);
    }

    private static String pathKey(Machine vm) {
        String path = vm.getHdaImagePath();
        return "backup_" + (path == null ? "none" : Integer.toHexString(path.hashCode()));
    }

    private static File recoveryRoot(Context ctx) throws IOException {
        File external = ctx.getExternalFilesDir(null);
        if (external == null) throw new IOException("Android app storage unavailable");
        File root = new File(external, "WindowsRecovery");
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("Cannot create WindowsRecovery storage");
        return root.getCanonicalFile();
    }

    private static File localFile(Context ctx, String config) throws IOException {
        if (config == null || config.trim().isEmpty() || "None".equals(config))
            throw new IOException("VM file path is not configured");
        String s = config.startsWith("/content//")
                ? FileUtils.decodeDocumentFilePath(config) : config;
        String resolved = FileUtils.getRealPathForFile(ctx, s);
        if (resolved == null) {
            if (s.startsWith("content://"))
                throw new IOException("Android document URI cannot be backed up safely by path; "
                        + "export the VM disk manually before repair");
            resolved = s;
        }
        File f = new File(resolved).getCanonicalFile();
        if (!f.isFile() || !f.canRead() || Files.isSymbolicLink(f.toPath()))
            throw new IOException("VM source file unavailable or unsafe: " + f.getName());
        return f;
    }

    private static File varsFile(Context ctx, Machine vm) throws IOException {
        String selected = vm.getBiosVars();
        if (selected == null || selected.trim().isEmpty()
                || "Default".equalsIgnoreCase(selected.trim())) {
            return localFile(ctx, new File(QubeApplication.getBasefileDir(),
                    Config.UEFI_ARM_VARS).getAbsolutePath());
        }
        return localFile(ctx, selected);
    }

    /** This checks a prior completed manifest and file sizes; it is NOT a new SHA audit. */
    public static boolean hasBackup(Context ctx, Machine vm) {
        if (!target(ctx, vm)) return false;
        String path = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(pathKey(vm), null);
        if (path == null) return false;
        try {
            File root = recoveryRoot(ctx), dir = new File(path).getCanonicalFile();
            if (!dir.getParentFile().equals(root)) return false;
            File manifest = new File(dir, "VERIFIED-BACKUP.txt");
            File disk = new File(dir, "windows-before-repair.qcow2");
            File vars = new File(dir, "uefi-vars-before-repair.fd");
            return manifest.isFile() && manifest.length() > 100
                    && disk.isFile() && disk.length() > 1048576
                    && vars.isFile() && vars.length() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String hex(byte[] data) {
        StringBuilder b = new StringBuilder();
        for (byte v : data) b.append(String.format(Locale.US, "%02x", v & 0xff));
        return b.toString();
    }

    private static String verifyDigest(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[1024*1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf,0,n);
        }
        return hex(md.digest());
    }

    private static String safeCopy(File source, File target, AtomicBoolean canceled,
                                   Context ctx, TextView status, String label)
            throws Exception {
        if (target.exists()) throw new IOException("Refusing to overwrite backup");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long expected = source.length(), beforeModified = source.lastModified();
        long copied = 0, reported = -1;
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[1024*1024];
            int count;
            while ((count = in.read(buf)) != -1) {
                if (canceled.get() || MachineController.getInstance().isRunning())
                    throw new IOException("Backup canceled or VM became active");
                out.write(buf,0,count);
                md.update(buf,0,count);
                copied += count;
                long tick = copied / (64L*1024*1024);
                if (tick != reported) {
                    reported = tick;
                    final long done = copied / (1024*1024);
                    final long total = expected / (1024*1024);
                    ((android.app.Activity)ctx).runOnUiThread(() ->
                        status.setText(label + ": " + done + " / " + total + " MiB\n"
                            + "Do not start Windows or uninstall the app."));
                }
            }
            out.getFD().sync();
        }
        if (copied != expected || source.length() != expected
                || source.lastModified() != beforeModified)
            throw new IOException("Source changed during backup; result rejected");
        String digest = hex(md.digest());
        if (target.length() != expected || !digest.equals(verifyDigest(target)))
            throw new IOException("Backup SHA-256 mismatch: result rejected");
        return digest;
    }

    private static void deletePartialDir(File dir) {
        File[] children = dir.listFiles();
        if (children != null) for (File f : children) {
            if (f.isFile()) f.delete();
        }
        dir.delete();
    }

    private static String backupNow(QubeActivity activity, Machine vm,
                                    AtomicBoolean canceled, TextView text) throws Exception {
        if (!target(activity, vm) || MachineController.getInstance().isRunning())
            throw new IOException("VM must be Windows ARM64 and fully stopped");
        File root = recoveryRoot(activity);
        File disk = localFile(activity, vm.getHdaImagePath());
        if (!disk.getName().toLowerCase(Locale.US).endsWith(".qcow2"))
            throw new IOException("Expected existing Windows QCOW2 disk; refusing other formats");
        File vars = varsFile(activity, vm);
        if (disk.equals(vars))
            throw new IOException("Guest disk and UEFI VARS paths overlap");
        long needed = disk.length() + vars.length() + (512L*1024*1024);
        if (root.getUsableSpace() < needed)
            throw new IOException("Not enough free Android storage for full disk+UEFI backup; "
                    + "requires roughly " + (needed/(1024*1024)) + " MiB");
        if (disk.length() < 1048576 || vars.length() == 0)
            throw new IOException("Missing or unusually small Windows disk/UEFI vars");
        if (disk.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Selected VM disk is already within backup area");
        File dir = new File(root, "backup-" + System.currentTimeMillis());
        if (!dir.mkdir()) throw new IOException("Cannot make unique backup directory");
        try {
            String diskDigest = safeCopy(disk,
                    new File(dir, "windows-before-repair.qcow2"),canceled,activity,text,"Windows QCOW2");
            String varsDigest = safeCopy(vars,
                    new File(dir, "uefi-vars-before-repair.fd"),canceled,activity,text,"UEFI variables");
            if (MachineController.getInstance().isRunning() || canceled.get())
                throw new IOException("VM started or canceled during verification");
            // The completion marker is written LAST, only after both SHA checks.
            File manifest = new File(dir,"VERIFIED-BACKUP.txt");
            try (FileWriter writer = new FileWriter(manifest)) {
                writer.write("Windows Mode 7.1.23 -- VERIFIED, READ-ONLY COPY\n");
                writer.write("Created: " + new Date() + "\n");
                writer.write("Disk source: " + disk.getCanonicalPath() + "\n");
                writer.write("QCOW2 bytes: " + disk.length() + "\n");
                writer.write("QCOW2 SHA-256: " + diskDigest + "\n");
                writer.write("UEFI source: " + vars.getCanonicalPath() + "\n");
                writer.write("UEFI bytes: " + vars.length() + "\n");
                writer.write("UEFI SHA-256: " + varsDigest + "\n");
                writer.write("No Windows changes, no automatic restore, no OOBE bypass.\n");
            }
            activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                    .putString(pathKey(vm), dir.getCanonicalPath()).apply();
            return dir.getCanonicalPath();
        } catch (Exception e) {
            deletePartialDir(dir);
            throw e;
        }
    }

    public static void show(QubeActivity activity, Machine vm) {
        if (!target(activity,vm)) return;
        if (MachineController.getInstance().isRunning()) {
            new MaterialAlertDialogBuilder(activity)
                .setTitle("Windows recovery backup")
                .setMessage("Shut Windows down safely first. Backup cannot run on a live QCOW2.")
                .setPositiveButton("OK",null).show();
            return;
        }
        boolean present = hasBackup(activity,vm);
        new MaterialAlertDialogBuilder(activity)
            .setTitle("Windows 11 setup recovery - BACKUP FIRST")
            .setMessage("Copy and SHA-256 verify your actual Windows QCOW2 and UEFI VARS "
                + "into separate app storage while the VM is OFF. This can require "
                + "many gigabytes and considerable time. It does NOT repair or alter "
                + "Windows or the VM hardware. A completed backup remains available "
                + "under Android/data/com.fold.windowsmode.research/files/WindowsRecovery. "
                + "Do NOT uninstall or clear app data. Current backup: "
                + (present ? "AVAILABLE." : "NOT VERIFIED."))
            .setPositiveButton("Back up disk + UEFI", (d,w)->confirmBackup(activity,vm))
            .setNegativeButton("Cancel",null)
            .show();
    }

    private static void confirmBackup(QubeActivity activity, Machine vm) {
        if (!COPYING.compareAndSet(false,true)) {
            new MaterialAlertDialogBuilder(activity).setMessage("Backup already running")
                .setPositiveButton("OK",null).show();
            return;
        }
        AtomicBoolean canceled = new AtomicBoolean(false);
        TextView status = new TextView(activity);
        int padding = Math.round(16*activity.getResources().getDisplayMetrics().density);
        status.setPadding(padding,padding,padding,padding);
        status.setText("Checking available space; Windows must remain stopped.");
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
            .setTitle("Verified Windows VM backup")
            .setView(status)
            .setCancelable(false)
            .setNegativeButton("Cancel backup", (d,w)->canceled.set(true))
            .create();
        dialog.show();
        new Thread(() -> {
            String result;
            boolean ok = false;
            try {
                result = backupNow(activity,vm,canceled,status);
                ok = true;
            } catch (Exception ex) {
                result = "Backup NOT completed: " + ex.getMessage()
                    + ". The original Windows disk and UEFI were not changed.";
            } finally {
                COPYING.set(false);
            }
            final boolean success = ok;
            final String message = result;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    dialog.dismiss();
                    new MaterialAlertDialogBuilder(activity)
                        .setTitle(success ? "Windows+UEFI backup VERIFIED" : "Backup incomplete")
                        .setMessage(success
                            ? "SHA-256 checks passed. Stored at:\n" + message
                              + "\n\nThis is an app-local copy; keep the app installed. "
                              + "The guest has NOT been repaired, and restoring is not automatic."
                            : message)
                        .setPositiveButton("OK",null).show();
                }
            });
        },"WindowsSetupRecovery-Backup").start();
    }
}
''')

patch(activity,
      '    private static final int WINDOWS_DISPOSABLE_GPU_LAB = 23;',
      '    private static final int WINDOWS_DISPOSABLE_GPU_LAB = 23;\n'
      '    private static final int WINDOWS_SETUP_RECOVERY = 24;')
patch(activity,
      '''            if (WindowsDisposableGpuLab.eligible(this, getMachine()))
                menu.add(0, WINDOWS_DISPOSABLE_GPU_LAB, 0,
                        "Windows GPU (test disk ONLY)")
                    .setIcon(R.drawable.settings_24px);''',
      '''            if (WindowsDisposableGpuLab.eligible(this, getMachine()))
                menu.add(0, WINDOWS_DISPOSABLE_GPU_LAB, 0,
                        "Windows GPU (test disk ONLY)")
                    .setIcon(R.drawable.settings_24px);
            menu.add(0, WINDOWS_SETUP_RECOVERY, 0,
                    "Windows setup recovery backup")
                    .setIcon(R.drawable.settings_24px);''')
patch(activity,
      '''        } else if (item.getItemId() == WINDOWS_DISPOSABLE_GPU_LAB) {
            if (!MachineController.getInstance().isRunning())
                showWindowsDisposableGpuLab();''',
      '''        } else if (item.getItemId() == WINDOWS_DISPOSABLE_GPU_LAB) {
            if (!MachineController.getInstance().isRunning())
                showWindowsDisposableGpuLab();
        } else if (item.getItemId() == WINDOWS_SETUP_RECOVERY) {
            if (!MachineController.getInstance().isRunning())
                WindowsSetupRecovery.show(this,getMachine());''')
patch(vk,
      '''    DesktopHelp(null, "Desktop Fix", 0, "Recover Windows Task Manager or Start/Explorer", false),''',
      '''    DesktopHelp(null, "Desktop Fix", 0, "Recover Windows Task Manager or Start/Explorer", false),
    SetupRescue(null, "Setup Rescue", 0, "Read Windows OOBE status; optional Audit Mode", false),''')
patch(layout,
      '''VirtualKey.SetupTools, VirtualKey.NetworkCheck, VirtualKey.DesktopHelp, VirtualKey.LeftCtrl,''',
      '''VirtualKey.SetupTools, VirtualKey.NetworkCheck, VirtualKey.DesktopHelp, VirtualKey.SetupRescue, VirtualKey.LeftCtrl,''')
patch(layout,
      '''VirtualKey.SetupTools, VirtualKey.NetworkCheck, VirtualKey.DesktopHelp)''',
      '''VirtualKey.SetupTools, VirtualKey.NetworkCheck, VirtualKey.DesktopHelp, VirtualKey.SetupRescue)''')
patch(keys,
      '''            } else if (vk == VirtualKey.DesktopHelp) {
                view.setOnClickListener(v -> showWindowsDesktopRecovery());''',
      '''            } else if (vk == VirtualKey.DesktopHelp) {
                view.setOnClickListener(v -> showWindowsDesktopRecovery());
            } else if (vk == VirtualKey.SetupRescue) {
                view.setOnClickListener(v -> showWindowsSetupRescue());''')
patch(keys,
      '''    private void showWindowsDesktopRecovery() {''',
      r'''    private void showWindowsSetupRescue() {
        if (!isWindowsTarget()) {
            ToastUtils.toastShort(activity,"Windows 11 ARM64 only");
            return;
        }
        String[] actions = {
            "Read Windows Setup state (registry, NO changes)",
            "Read ChildCompletion status (registry, NO changes)",
            "Windows Audit Mode (TEMP desktop, REBOOTS, backup REQUIRED)"
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Windows 11 OOBE recovery")
            .setMessage("The Windows restart loop is NOT yet diagnosed. "
                + "The first two options only READ registry status from an already "
                + "visible guest Command Prompt; they never change Windows. "
                + "Audit Mode is a MICROSOFT troubleshooting path that can reboot "
                + "into a TEMPORARY desktop; it is NOT a completed Windows installation.")
            .setItems(actions,(d,which)->confirmSetupRescue(which))
            .setNegativeButton("Close",null).show();
    }

    private void confirmSetupRescue(int which) {
        if (!isWindowsTarget() || which<0 || which>2) return;
        if (which==2) {
            com.max2idea.android.qube.machine.Machine vm =
                com.max2idea.android.qube.machine.MachineController.getInstance().getMachine();
            if (!com.max2idea.android.qube.main.WindowsSetupRecovery.hasBackup(activity,vm)) {
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle("Backup required before Audit Mode")
                    .setMessage("Shut down the VM safely, use Windows setup recovery backup "
                        + "on the Windows Mode main screen, and return only after the "
                        + "disk AND UEFI copies are verified. No guest command was sent.")
                    .setPositiveButton("OK",null).show();
                return;
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Audit Mode - TEMPORARY DESKTOP")
                .setMessage("Send Ctrl+Shift+F3 to Windows OOBE. Windows can REBOOT "
                    + "and attempt to enter built-in Administrator Audit Mode. "
                    + "That is NOT your completed desktop or a permanent user login. "
                    + "Only a backup is prepared; no guarantee it will work. "
                    + "Do NOT run Sysprep/generalize if it appears.")
                .setPositiveButton("Request Audit Mode", (d,w)->new Thread(()->{
                    boolean sent=sendSetupHmpKey("ctrl-shift-f3");
                    final boolean ok=sent;
                    activity.runOnUiThread(()->new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle(ok?"Keys delivered - result UNVERIFIED":"Audit Mode request FAILED")
                        .setMessage(ok
                            ?"QEMU accepted the key sequence. It does NOT prove Windows entered Audit Mode. "
                             +"Windows may reboot. Observe the guest screen, and do NOT reset the disk."
                            :"QEMU did not acknowledge the key sequence; nothing else was changed.")
                        .setPositiveButton("OK",null).show());
                },"WindowsSetupRecovery-AuditMode").start())
                .setNegativeButton("Cancel",null).show();
            return;
        }
        final String command=which==0
            ?"reg query hklm\\system\\setup"
            :"reg query hklm\\system\\setup\\status\\childcompletion";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Read-only Windows Setup status")
            .setMessage("Leave an existing BLACK Windows Command Prompt open and focused. "
                + "This will type a short REG QUERY into Windows automatically. "
                + "It only READS setup state, without altering registry, user accounts, "
                + "disk, EFI, drivers or machine hardware. "
                + "If CMD is NOT visible, cancel and open it with Shift+F10 first. "
                + "Take a picture of the result.")
            .setPositiveButton("CMD visible - read status", (d,w)->new Thread(()->{
                boolean delivered=typeSetupCommand(command);
                final boolean ok=delivered;
                activity.runOnUiThread(()->new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle(ok?"Read-only keys delivered":"Command delivery FAILED")
                    .setMessage(ok
                        ?"QEMU delivered REG QUERY keys, but the app cannot read the result. "
                         +"Take a screenshot of the Command Prompt for analysis; "
                         +"do not change registry values based only on one error log."
                        :"The command was not fully delivered; no repairs attempted.")
                    .setPositiveButton("OK",null).show());
            },"WindowsSetupRecovery-ReadState").start())
            .setNegativeButton("Cancel",null).show();
    }

    private void showWindowsDesktopRecovery() {''')

act=activity.read_text();key=keys.read_text();modelSrc=model.read_text()
for token in ("WINDOWS_SETUP_RECOVERY = 24","WindowsSetupRecovery.show(this,getMachine())"):
    assert token in act,token
for token in ("SetupRescue","showWindowsSetupRescue()",
              "reg query hklm","ctrl-shift-f3","WindowsSetupRecovery.hasBackup"):
    assert token in key,token
for token in ("MessageDigest.getInstance(\"SHA-256\")","windows-before-repair.qcow2",
              "uefi-vars-before-repair.fd","VM source file unavailable",
              "VERIFIED-BACKUP.txt","getBiosVars()","getHdaImagePath()"):
    assert token in modelSrc,token
assert "setHdaImagePath(" not in modelSrc and "setBiosVars(" not in modelSrc
assert "setCpuNum(" not in modelSrc and "setMemory(" not in modelSrc
print("PASS: safe stopped-VM SHA-verified QCOW2+UEFI backup, read-only Setup status and opt-in Audit Mode")
print("NOT PROVEN: user guest recoverable, Microsoft Audit Mode acceptance or Windows desktop")

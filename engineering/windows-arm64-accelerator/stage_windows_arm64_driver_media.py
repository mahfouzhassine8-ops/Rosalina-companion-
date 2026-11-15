#!/usr/bin/env python3
"""Add a checksummed Windows ARM64 VirtIO disc without changing saved VM media.

Applies to a disposable pinned QubeVM 7.1.4 checkout AFTER the protected
7.1.14, WinJIT 7.1.15, and fullscreen/shutdown 7.1.16 stages.
No guest filesystem, UEFI VARS, boot disk/controller, installed driver or
other QubeVM machine settings are modified.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
java = root / "qube-android-lib/src/main/java/com/max2idea/android/qube"
activity = java / "main/QubeActivity.java"
executor = java / "jni/VMExecutor.java"
media = java / "main/WindowsArm64DriverMedia.java"
ui = java / "main/WindowsArm64DriverSetup.java"

def patch(path: Path, before: str, after: str) -> None:
    old = path.read_text()
    n = old.count(before)
    if n != 1:
        raise RuntimeError(f"{path.name}: expected ONE pinned anchor, got {n} for {before[:110]!r}")
    path.write_text(old.replace(before, after, 1))
    print(f"PASS: {path.name} bounded patch")

if not activity.is_file() or not executor.is_file() or media.exists() or ui.exists():
    raise RuntimeError("Missing pinned source or Windows ARM64 driver classes already exist")

media.write_text(r'''package com.max2idea.android.qube.main;

import android.content.Context;
import android.content.SharedPreferences;
import com.max2idea.android.qube.machine.Machine;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WindowsArm64DriverMedia {
    // Vectras VM's pinned Fedora virtio-win 0.1.285 ISO.
    // SHA256 is independently pinned in microsoft/openvmm-deps Dockerfile.virtio-win.
    // This does not make the package a Windows driver for the current NVMe/ramfb.
    public static final String VERSION = "0.1.285";
    public static final String URL_ISO =
        "https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/"
        + "archive-virtio/virtio-win-0.1.285-1/virtio-win-0.1.285.iso";
    public static final String SHA256 =
        "e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331";
    private static final long MIN_SIZE = 300000000L;
    private static final long MAX_SIZE = 1100000000L;
    private static final String PREF = "windows_arm64_driver_media";
    private static final String KEY_ATTACH = "attach";
    private static final String KEY_LENGTH = "length";
    private static final String KEY_MODIFIED = "modified";
    private static final String KEY_DIGEST = "sha256";

    private WindowsArm64DriverMedia() {}

    public interface Progress {
        void update(long bytes, long totalBytes);
    }

    private static SharedPreferences preferences(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static File isoFile(Context c) {
        File parent = c.getExternalFilesDir(null);
        if (parent == null) return null;
        return new File(new File(parent, "WindowsArm64Drivers"), "virtio-win-0.1.285.iso");
    }

    public static boolean supported(Context c, Machine vm) {
        return WindowsCpuMode.isTargetWindowsVM(c, vm);
    }

    public static boolean isVerified(Context c) {
        File f = isoFile(c);
        if (f == null || !f.isFile()) return false;
        SharedPreferences p = preferences(c);
        return f.length() >= MIN_SIZE && f.length() <= MAX_SIZE
            && f.length() == p.getLong(KEY_LENGTH, -1)
            && f.lastModified() == p.getLong(KEY_MODIFIED, -1)
            && SHA256.equals(p.getString(KEY_DIGEST, ""));
    }

    public static boolean attachEnabled(Context c) {
        return preferences(c).getBoolean(KEY_ATTACH, false);
    }

    public static void setAttachEnabled(Context c, boolean enabled) {
        preferences(c).edit().putBoolean(KEY_ATTACH, enabled).apply();
    }

    public static String attachedPath(Context c, Machine m) {
        if (!supported(c, m) || !attachEnabled(c) || !isVerified(c)
                || WindowsWinjitMode.shouldUse(c, m))
            return null;
        File f = isoFile(c);
        if (f == null) return null;
        try {
            File root = new File(c.getExternalFilesDir(null), "WindowsArm64Drivers");
            if (!f.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))
                return null;
            return f.getCanonicalPath();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    // Called off the UI thread only. Download is explicit, never triggered by boot.
    // A wrong checksum is NEVER allowed to become an attachable guest disc.
    public static void download(Context context, AtomicBoolean canceled, Progress progress)
            throws Exception {
        File target = isoFile(context);
        if (target == null) throw new IllegalStateException("App-scoped storage unavailable");
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IllegalStateException("Cannot create app driver directory");
        File partial = new File(dir, "virtio-win.partial");
        HttpURLConnection conn = null;
        long written = 0L;
        try {
            conn = (HttpURLConnection) new URL(URL_ISO).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(45000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            if (!"https".equalsIgnoreCase(conn.getURL().getProtocol()))
                throw new IllegalStateException("Driver download redirected outside HTTPS");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("Driver download HTTP " + conn.getResponseCode());
            long total = conn.getContentLengthLong();
            if (total >= 0 && (total < MIN_SIZE || total > MAX_SIZE))
                throw new IllegalStateException("Unexpected VirtIO ISO content length");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                int n;
                long reportAt = 0;
                while ((n = in.read(buffer)) >= 0) {
                    if (canceled.get()) throw new InterruptedException("Download cancelled");
                    if (n == 0) continue;
                    written += n;
                    if (written > MAX_SIZE) throw new IllegalStateException("Driver download too large");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    if (written - reportAt >= 8 * 1024 * 1024) {
                        progress.update(written, total);
                        reportAt = written;
                    }
                }
                out.getFD().sync();
            }
            if (written < MIN_SIZE || (total >= 0 && written != total))
                throw new IllegalStateException("Incomplete VirtIO ISO");
            if (!SHA256.equals(toHex(digest.digest())))
                throw new IllegalStateException("VirtIO ISO SHA-256 mismatch; discarded");
            // Validate ISO9660 PVD; never trust an HTML error downloaded as ISO.
            try (FileInputStream check = new FileInputStream(partial)) {
                long skipped = 0;
                while (skipped < 32769) {
                    long n = check.skip(32769 - skipped);
                    if (n <= 0) throw new IllegalStateException("Invalid ISO image");
                    skipped += n;
                }
                byte[] magic = new byte[5];
                if (check.read(magic) != 5 || !java.util.Arrays.equals(
                        magic, new byte[]{'C','D','0','0','1'}))
                    throw new IllegalStateException("Invalid ISO9660 volume");
            }
            // Invalidate the previous verification before swapping the file.
            preferences(context).edit().remove(KEY_DIGEST).apply();
            if (target.exists() && !target.delete())
                throw new IllegalStateException("Cannot replace old ISO");
            if (!partial.renameTo(target))
                throw new IllegalStateException("Cannot finalize verified ISO");
            preferences(context).edit().putString(KEY_DIGEST, SHA256)
                    .putLong(KEY_LENGTH, target.length())
                    .putLong(KEY_MODIFIED, target.lastModified()).apply();
            progress.update(written, written);
        } finally {
            if (conn != null) conn.disconnect();
            if (partial.exists()) partial.delete();
        }
    }
}
''')

ui.write_text(r'''package com.max2idea.android.qube.main;

import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.max2idea.android.qube.machine.Machine;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WindowsArm64DriverSetup {
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private WindowsArm64DriverSetup() {}

    public static void show(QubeActivity activity, Machine vm) {
        if (!WindowsArm64DriverMedia.supported(activity, vm)) return;
        boolean ready = WindowsArm64DriverMedia.isVerified(activity);
        boolean attached = WindowsArm64DriverMedia.attachEnabled(activity);
        String[] choices = ready ? new String[]{
                attached ? "Disable extra driver disc on next boot" :
                           "Attach verified driver disc on next boot",
                "Download fresh verified driver disc again (~753 MB)",
                "Windows ARM64 driver installation guidance"
        } : new String[]{
                "Download Vectras/Fedora Windows VirtIO ISO (~753 MB)",
                "Windows ARM64 driver installation guidance"
        };
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Windows 11 ARM64 drivers")
                .setMessage("VirtIO 0.1.285 (Vectras Fedora source), SHA-256 pinned. "
                    + "Download once into this app's storage. The ISO is attached "
                    + "as an EXTRA READ-ONLY USB optical disc at the NEXT boot. "
                    + "The original NVMe Windows disk, UEFI variables, selected "
                    + "Windows installer ISO and hardware remain unchanged. "
                    + "Windows does not auto-install drivers. Only signed ARM64 "
                    + "drivers matching exposed devices may be installed in Windows. "
                    + "Currently " + (ready ? "verified" : "not downloaded")
                    + "; optional attachment " + (attached ? "enabled." : "disabled."))
                .setItems(choices, (dialog, index) -> {
                    if (ready && index == 0) {
                        WindowsArm64DriverMedia.setAttachEnabled(activity, !attached);
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle("Driver disc preference saved")
                                .setMessage("This only changes the optional read-only "
                                        + "media on the NEXT VM boot; Windows storage "
                                        + "and original installer remain unchanged.")
                                .setPositiveButton(android.R.string.ok, null).show();
                    } else if ((ready && index == 1) || (!ready && index == 0)) {
                        confirmDownload(activity);
                    } else {
                        showInstructions(activity);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void showInstructions(QubeActivity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Windows ARM64 signed driver guidance")
                .setMessage("The disc contains signed Windows VirtIO drivers. "
                    + "It is not a driver for the current NVMe controller or RAMFB. "
                    + "Inside Windows Device Manager, install only matching "
                    + "Windows 11 ARM64 drivers using Update Driver > Browse "
                    + "my computer, or inspect packages with pnputil. Never "
                    + "run x86/x64-only guest-tools installers on ARM64. "
                    + "A native no-distortion Fold resolution requires a "
                    + "separately verified display driver and guest mode-switch "
                    + "support. Do not switch the existing boot-disk controller.")
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private static void confirmDownload(QubeActivity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Download Windows ARM64 driver ISO?")
                .setMessage("About 753 MB from Fedora's published VirtIO archive. "
                    + "Use Wi-Fi and sufficient storage. SHA-256 will be checked "
                    + "against an independently pinned checksum; failed or "
                    + "partial downloads are rejected. Download does not "
                    + "modify your Windows VM or install drivers.")
                .setPositiveButton("Download", (dialog, which) -> download(activity))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void download(QubeActivity activity) {
        if (!BUSY.compareAndSet(false, true)) {
            new MaterialAlertDialogBuilder(activity)
                    .setMessage("Driver ISO download already running")
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        final AtomicBoolean canceled = new AtomicBoolean(false);
        TextView text = new TextView(activity);
        int pad = (int)(activity.getResources().getDisplayMetrics().density * 18);
        text.setPadding(pad, pad, pad, pad);
        text.setText("Downloading signed-driver ISO over HTTPS; verifying SHA-256...");
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Windows ARM64 driver download")
                .setView(text)
                .setCancelable(false)
                .setNegativeButton("Cancel download", (d, b) -> canceled.set(true))
                .create();
        dialog.show();
        new Thread(() -> {
            String result;
            boolean success = false;
            try {
                WindowsArm64DriverMedia.download(activity, canceled, (done, total) -> {
                    int pct = total > 0 ? (int)(done * 100 / total) : 0;
                    activity.runOnUiThread(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed())
                            text.setText("Downloading VirtIO ISO: " + pct + "%"
                                    + " (" + (done / 1048576) + " MiB)");
                    });
                });
                success = true;
                result = "Verified ISO downloaded. It is not attached until "
                    + "you enable attachment in the Windows Drivers menu while VM is off.";
            } catch (Exception ex) {
                result = "Driver download not completed: " + ex.getMessage()
                    + ". Existing Windows storage unchanged.";
            } finally {
                BUSY.set(false);
            }
            final String message = result;
            final boolean ok = success;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (dialog.isShowing()) dialog.dismiss();
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(ok ? "Driver ISO ready" : "Driver ISO unavailable")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null).show();
            });
        }, "WindowsARM64-VirtIO-Download").start();
    }
}
''')

patch(executor,
      "import com.max2idea.android.qube.main.WindowsCpuMode;",
      "import com.max2idea.android.qube.main.WindowsCpuMode;\n"
      "import com.max2idea.android.qube.main.WindowsArm64DriverMedia;")
patch(executor,
      """        addRemovableDrives(paramsList);
        addBootOptions(paramsList);""",
      """        addRemovableDrives(paramsList);
        addWindowsArm64DriverDisc(context, paramsList);
        addBootOptions(paramsList);""")
patch(executor,
      "    private void addAdvancedOptions(ArrayList<String> paramsList) {",
      """    private void addWindowsArm64DriverDisc(Context context, ArrayList<String> paramsList) {
        String isoPath = WindowsArm64DriverMedia.attachedPath(context, getMachine());
        if (isoPath == null) return;
        // Existing arm64 guest already uses qemu-xhci for usb-kbd/tablet.
        // Separate read-only USB optical disc; NEVER replace the saved installer
        // drive or the NVMe Windows boot disk. Low priority UEFI bootindex 99.
        paramsList.add("-drive");
        paramsList.add("if=none,id=wmarm64virtio,media=cdrom,format=raw,readonly=on,file="
                + qemuOpt(isoPath));
        paramsList.add("-device");
        paramsList.add("usb-storage,drive=wmarm64virtio,bootindex=99");
    }

    private void addAdvancedOptions(ArrayList<String> paramsList) {""")

# 7.1.14 assigned installed boot 18. The 7.1.15 WinJIT stage incorrectly
# reused 18; repair that actual collision without changing either behavior.
patch(activity,
      "    private static final int WINDOWS_WINJIT_MODE = 18;",
      "    private static final int WINDOWS_WINJIT_MODE = 21;\n"
      "    private static final int WINDOWS_ARM64_DRIVERS = 22;")
patch(activity,
      """            menu.add(0, WINDOWS_WINJIT_MODE, 0, "WinJIT (experimental)")
                    .setIcon(R.drawable.settings_24px);""",
      """            menu.add(0, WINDOWS_WINJIT_MODE, 0, "WinJIT (experimental)")
                    .setIcon(R.drawable.settings_24px);
            menu.add(0, WINDOWS_ARM64_DRIVERS, 0, "Windows ARM64 drivers")
                    .setIcon(R.drawable.settings_24px);""")
patch(activity,
      """        } else if (item.getItemId() == WINDOWS_WINJIT_MODE) {
            showWindowsWinjitMode();""",
      """        } else if (item.getItemId() == WINDOWS_WINJIT_MODE) {
            showWindowsWinjitMode();
        } else if (item.getItemId() == WINDOWS_ARM64_DRIVERS) {
            if (!MachineController.getInstance().isRunning())
                WindowsArm64DriverSetup.show(this, getMachine());""")

for file in [media, ui, executor, activity]:
    assert file.is_file()
exe = executor.read_text()
act = activity.read_text()
assert exe.count("addWindowsArm64DriverDisc(context, paramsList);") == 1
assert "usb-storage,drive=wmarm64virtio,bootindex=99" in exe
assert "readonly=on,file=" in exe
assert 'WINDOWS_INSTALLED_BOOT = 18;' in act
assert 'WINDOWS_WINJIT_MODE = 21;' in act
assert 'WINDOWS_ARM64_DRIVERS = 22;' in act
assert "WindowsArm64DriverSetup.show(this, getMachine());" in act
assert "e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331" in media.read_text()
assert "setHdaImagePath(" not in media.read_text()
print("PASS: signed Vectras/Fedora ARM64 driver disc, separate optical mount, preserved NVMe, unique menus")

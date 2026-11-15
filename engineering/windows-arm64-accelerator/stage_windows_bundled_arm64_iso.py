#!/usr/bin/env python3
"""Stage optional OFFLINE Windows 11 ARM64 driver media after 70117.

Only edits two generated Windows Mode guest-tool classes. No VM disk, UEFI,
QEMU engine, saved machine setting, unrelated app feature or driver binary
changes. The compact ISO is produced from checksum-verified Fedora 0.1.285.
"""
from __future__ import annotations
from pathlib import Path
import argparse
import hashlib

def digest(path:Path)->str:
    d=hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda:f.read(1024*1024),b""):
            d.update(b)
    return d.hexdigest()

def patch(path:Path,before:str,after:str)->None:
    old=path.read_text()
    n=old.count(before)
    if n!=1: raise RuntimeError(f"{path.name}: expected 1 pinned anchor, found {n}: {before[:70]!r}")
    path.write_text(old.replace(before,after,1))
    print(f"PASS: Windows-only offline driver patch {path.name}")

p=argparse.ArgumentParser()
p.add_argument("--upstream",type=Path,required=True)
p.add_argument("--compact-iso",type=Path,required=True)
args=p.parse_args()

root=args.upstream/"qube-android-lib/src/main/java/com/max2idea/android/qube/main"
media=root/"WindowsArm64DriverMedia.java"
ui=root/"WindowsArm64DriverSetup.java"
assets=args.upstream/"qube-android-arm/src/main/assets/WindowsArm64Drivers"
iso=args.compact_iso.resolve()
if not media.is_file() or not ui.is_file() or not iso.is_file():
    raise RuntimeError("Need complete prior Windows driver-stage classes and audited ISO")
if not 500000<iso.stat().st_size<100_000_000:
    raise RuntimeError("Invalid compact ISO size")
with iso.open("rb") as f:
    f.seek(32769)
    if f.read(5)!=b"CD001":
        raise RuntimeError("Compact driver ISO lacks ISO9660 signature")
assets.mkdir(parents=True,exist_ok=True)
target=assets/"arm64-driver-disc.iso"
if target.exists():
    raise RuntimeError("Refusing to overwrite an existing ARM64 driver asset")
target.write_bytes(iso.read_bytes())
sha=digest(target)
if sha!=digest(iso):
    raise RuntimeError("Bundled ISO staged bytes differ")

# Built-in ARM64 driver files are immediately available offline as an app
# asset. Copy it only after explicit user opt-in, and verify whole ISO SHA256.
# A metadata-cached downloadable full ISO remains available as a fallback.
patch(media,
'''    private static final String KEY_DIGEST = "sha256";''',
'''    private static final String KEY_DIGEST = "sha256";
    private static final String COMPACT_ASSET =
            "WindowsArm64Drivers/arm64-driver-disc.iso";
    private static final String COMPACT_SHA256 = "'''+sha+'''";
    private static final long COMPACT_MAX_BYTES = 100000000L;''')
patch(media,
'''    public static boolean supported(Context c, Machine vm) {''',
'''    public static File compactFile(Context c) {
        File parent = c.getExternalFilesDir(null);
        if (parent == null) return null;
        return new File(new File(parent, "WindowsArm64Drivers"), "virtio-win11-arm64-offline.iso");
    }

    private static String hashFile(File file) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] block=new byte[131072];
            int n;
            while ((n=in.read(block))!=-1) if (n>0) hash.update(block,0,n);
        }
        return toHex(hash.digest());
    }

    public static boolean isCompactVerified(Context c) {
        File f=compactFile(c);
        if (f==null || !f.isFile() || f.length()<500000
                || f.length()>COMPACT_MAX_BYTES) return false;
        try {
            File root=new File(c.getExternalFilesDir(null),"WindowsArm64Drivers");
            return f.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())
                    && COMPACT_SHA256.equals(hashFile(f));
        } catch (Exception ex) {
            return false;
        }
    }

    public static void prepareCompact(Context c) throws Exception {
        File file=compactFile(c);
        if (file==null) throw new IllegalStateException("Android app storage unavailable");
        if (isCompactVerified(c)) return;
        File root=file.getParentFile();
        if (!root.isDirectory() && !root.mkdirs())
            throw new IllegalStateException("Cannot create app driver folder");
        File tmp=new File(root,"virtio-win11-arm64-offline.partial");
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            long written=0;
            try (InputStream in=c.getAssets().open(COMPACT_ASSET);
                 FileOutputStream out=new FileOutputStream(tmp)) {
                byte[] block=new byte[131072];
                int n;
                while ((n=in.read(block))!=-1) {
                    if (n==0) continue;
                    written+=n;
                    if (written>COMPACT_MAX_BYTES)
                        throw new IllegalStateException("Bundled ISO exceeded size limit");
                    md.update(block,0,n);
                    out.write(block,0,n);
                }
                out.getFD().sync();
            }
            if (written<500000 || !COMPACT_SHA256.equals(toHex(md.digest())))
                throw new IllegalStateException("Bundled ISO verification failed");
            try (FileInputStream check=new FileInputStream(tmp)) {
                long skipped=0;
                while (skipped<32769) {
                    long n=check.skip(32769-skipped);
                    if (n<=0) throw new IllegalStateException("Invalid ISO header");
                    skipped+=n;
                }
                byte[] marker=new byte[5];
                if (check.read(marker)!=5 || !java.util.Arrays.equals(marker,
                        new byte[]{'C','D','0','0','1'}))
                    throw new IllegalStateException("Invalid ISO9660 file");
            }
            if (file.exists() && !file.delete())
                throw new IllegalStateException("Old compact ISO could not be replaced");
            if (!tmp.renameTo(file))
                throw new IllegalStateException("Verified compact ISO could not be finalized");
        } finally {
            if (tmp.exists()) tmp.delete();
        }
        if (!isCompactVerified(c))
            throw new IllegalStateException("Staged compact ISO did not pass final verification");
    }

    public static boolean supported(Context c, Machine vm) {''')
patch(media,
'''        if (!supported(c, m) || !attachEnabled(c) || !isVerified(c)
                || WindowsWinjitMode.shouldUse(c, m))
            return null;
        File f = isoFile(c);''',
'''        if (!supported(c, m) || !attachEnabled(c)
                || WindowsWinjitMode.shouldUse(c, m))
            return null;
        // Prefer the independently verified ARM64-only bundled ISO. Keep the
        // previously downloaded full ISO as an unchanged backward-compatible fallback.
        File f = isCompactVerified(c) ? compactFile(c)
                : isVerified(c) ? isoFile(c) : null;''')

patch(ui,
'''    private static final AtomicBoolean BUSY = new AtomicBoolean(false);''',
'''    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final AtomicBoolean PREPARING_COMPACT = new AtomicBoolean(false);''')
patch(ui,
'''        boolean ready = WindowsArm64DriverMedia.isVerified(activity);
        boolean attached = WindowsArm64DriverMedia.attachEnabled(activity);
        String[] choices = ready ? new String[]{
                attached ? "Disable extra driver disc on next boot" :
                           "Attach verified driver disc on next boot",
                "Download fresh verified driver disc again (~753 MB)",
                "Windows ARM64 driver installation guidance"
        } : new String[]{
                "Download Vectras/Fedora Windows VirtIO ISO (~753 MB)",
                "Windows ARM64 driver installation guidance"
        };''',
'''        boolean compactReady = WindowsArm64DriverMedia.isCompactVerified(activity);
        boolean ready = compactReady || WindowsArm64DriverMedia.isVerified(activity);
        boolean attached = WindowsArm64DriverMedia.attachEnabled(activity);
        String[] choices = ready ? new String[]{
                attached ? "Disable extra driver disc on next boot" :
                           "Attach verified driver disc on next boot",
                "Prepare included offline ARM64 driver disc (no download)",
                "Download full Fedora VirtIO ISO (~753 MB, optional)",
                "Windows ARM64 driver installation guidance"
        } : new String[]{
                "Prepare included offline ARM64 driver disc (no download)",
                "Download full Fedora VirtIO ISO (~753 MB, optional)",
                "Windows ARM64 driver installation guidance"
        };''')
patch(ui,
'''                .setMessage("VirtIO 0.1.285 (Vectras Fedora source), SHA-256 pinned. "
                    + "Download once into this app's storage. The ISO is attached "
                    + "as an EXTRA READ-ONLY USB optical disc at the NEXT boot. "
                    + "The original NVMe Windows disk, UEFI variables, selected "
                    + "Windows installer ISO and hardware remain unchanged. "
                    + "Windows does not auto-install drivers. Only signed ARM64 "
                    + "drivers matching exposed devices may be installed in Windows. "
                    + "Currently " + (ready ? "verified" : "not downloaded")
                    + "; optional attachment " + (attached ? "enabled." : "disabled."))''',
'''                .setMessage("Windows 11 ARM64 VirtIO drivers from Vectras/Fedora "
                    + "are already included OFFLINE in this APK. Preparing the "
                    + "1.8 MB compact ISO requires no data download. The larger "
                    + "753 MB original ISO remains optional. The disc is attached "
                    + "as a second READ-ONLY USB optical drive on NEXT boot. "
                    + "The NVMe boot disk, UEFI and selected installer stay intact. "
                    + "Windows does not auto-install drivers: only packages matching "
                    + "virtual PCI/USB hardware should be selected in Windows. "
                    + "Compact ISO: " + (compactReady ? "verified" : "not prepared")
                    + "; optional attachment " + (attached ? "enabled." : "disabled."))''')
patch(ui,
'''                    } else if ((ready && index == 1) || (!ready && index == 0)) {
                        confirmDownload(activity);
                    } else {
                        showInstructions(activity);
                    }''',
'''                    } else if ((ready && index == 1) || (!ready && index == 0)) {
                        prepareIncludedDisc(activity);
                    } else if ((ready && index == 2) || (!ready && index == 1)) {
                        confirmDownload(activity);
                    } else {
                        showInstructions(activity);
                    }''')
patch(ui,
'''    private static void showInstructions(QubeActivity activity) {''',
'''    private static void prepareIncludedDisc(QubeActivity activity) {
        if (!PREPARING_COMPACT.compareAndSet(false,true)) {
            new MaterialAlertDialogBuilder(activity)
                    .setMessage("Bundled driver disc is being prepared")
                    .setPositiveButton(android.R.string.ok,null).show();
            return;
        }
        new Thread(() -> {
            boolean completed=false;
            String message;
            try {
                WindowsArm64DriverMedia.prepareCompact(activity);
                WindowsArm64DriverMedia.setAttachEnabled(activity,true);
                completed=true;
                message="Verified offline ARM64 driver disc is prepared and enabled "
                        + "for the NEXT Windows boot. No drivers are installed automatically. "
                        + "The existing Windows disk, installer ISO and UEFI are unchanged.";
            } catch (Exception ex) {
                message="Could not prepare the offline driver disc: "+ex.getMessage()
                        + ". No VM disk or settings were changed.";
            } finally {
                PREPARING_COMPACT.set(false);
            }
            final boolean ok=completed;
            final String result=message;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(ok ? "ARM64 offline drivers ready" : "Driver disc unavailable")
                        .setMessage(result)
                        .setPositiveButton(android.R.string.ok,null).show();
            });
        },"WindowsARM64-PrepareOfflineDrivers").start();
    }

    private static void showInstructions(QubeActivity activity) {''')
# Make the existing download path explicit as optional, not the primary workflow.
patch(ui,
'''                .setTitle("Download Windows ARM64 driver ISO?")''',
'''                .setTitle("Download FULL Windows ARM64 driver ISO? (optional)")''')

for phrase in ["isCompactVerified","prepareCompact","COMPACT_ASSET","COMPACT_SHA256",
               "WindowsARM64-PrepareOfflineDrivers","WindowsWinjitMode.shouldUse"]:
    assert phrase in (media.read_text()+ui.read_text()),phrase
assert target.stat().st_size==iso.stat().st_size
print(f"PASS: offline ARM64 disc bundled in app asset: {target.stat().st_size} bytes SHA256 {sha}")

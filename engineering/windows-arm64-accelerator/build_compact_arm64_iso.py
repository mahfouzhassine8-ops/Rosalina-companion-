#!/usr/bin/env python3
"""Extract unchanged Windows 11 ARM64 VirtIO driver files into a compact ISO.

Input: externally downloaded Fedora virtio-win-0.1.285.iso verified against
SHA-256 e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331.
This tool neither installs drivers nor edits the installed Windows VM.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

PINNED_ORIGINAL_SHA256="e14cf2b94492c3e925f0070ba7fdfedeb2048c91eea9c5a5afb30232a3976331"
REQUIRED=("NetKVM", "vioscsi", "viostor", "vioinput", "viorng")
MIN_SOURCE_SIZE=300_000_000
MAX_SOURCE_SIZE=1_100_000_000

def sha256(path:Path)->str:
    h=hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda:f.read(1024*1024),b""):
            h.update(chunk)
    return h.hexdigest()

def run(*args:str, capture:bool=False)->str:
    p=subprocess.run(args,check=True,text=True,stdout=subprocess.PIPE if capture else None)
    return p.stdout or ""

def parse_list(iso:Path)->list[tuple[str,int]]:
    listing=run("7z","l","-slt",str(iso),capture=True)
    records=[]
    for block in re.split(r"\n\s*\n",listing):
        pathmatch=re.search(r"^Path = (.+)$",block,re.MULTILINE)
        sizematch=re.search(r"^Size = (\d+)$",block,re.MULTILINE)
        if pathmatch and sizematch:
            path=pathmatch.group(1).strip()
            # Do not interpret the source ISO itself as a file entry.
            if path!=str(iso) and path!="/"+iso.name:
                records.append((path,int(sizematch.group(1))))
    return records

def main()->None:
    p=argparse.ArgumentParser()
    p.add_argument("--source-iso",type=Path,required=True)
    p.add_argument("--compact-iso",type=Path,required=True)
    p.add_argument("--manifest",type=Path,required=True)
    a=p.parse_args()
    iso=a.source_iso.resolve()
    if not iso.is_file() or not MIN_SOURCE_SIZE<iso.stat().st_size<MAX_SOURCE_SIZE:
        raise RuntimeError("Source ISO missing or outside pinned size bounds")
    if sha256(iso)!=PINNED_ORIGINAL_SHA256:
        raise RuntimeError("STOP: Fedora VirtIO source ISO SHA256 differs from independently pinned original")
    with iso.open("rb") as f:
        f.seek(32769)
        if f.read(5)!=b"CD001":
            raise RuntimeError("Not an ISO9660 disc")
    entries=parse_list(iso)
    by_path={p.replace("\\","/"):size for p,size in entries}
    matched=[]
    # Drivers are stored under family/w11/ARM64 or family/Win11/ARM64.
    for path,size in by_path.items():
        n=path.replace("\\","/")
        if re.search(r"(^|/)(?:w11|win11)/arm64/",n,re.IGNORECASE):
            if size and n.lower().endswith((".inf",".sys",".cat",".dll",".bin")):
                if n.startswith("/") or ".." in Path(n).parts:
                    raise RuntimeError("Unexpected ISO traversal path")
                matched.append(n)
    found={}
    for family in REQUIRED:
        found[family]=any(p.lower().startswith(family.lower()+"/")
                          and p.lower().endswith(".inf") for p in matched)
        if not found[family]:
            raise RuntimeError(f"Missing required Windows ARM64 .inf family {family}: {found}")
    if not 8<=len(matched)<=800:
        raise RuntimeError(f"Unexpected driver subset cardinality {len(matched)}")
    # Include text licenses/notices from the source disc where present.
    notices=[]
    for path,size in by_path.items():
        n=path.replace("\\","/")
        if "/" not in n and (re.match(r"(?i)^(?:license|copying|readme|notice)(?:[._-].*)?$",n)
                              or n.lower().endswith(".txt")) and size<=2_000_000:
            notices.append(n)
    names=sorted(set(matched+notices),key=str.lower)
    a.compact_iso.parent.mkdir(parents=True,exist_ok=True)
    a.manifest.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="virtio-arm64-subset-") as d:
        stage=Path(d)/"disc"
        stage.mkdir()
        response=Path(d)/"files.txt"
        response.write_text("\n".join(names)+"\n")
        run("7z","x","-y",f"-o{stage}",str(iso),f"-i@{response}")
        for name in names:
            file=stage/name
            if not file.is_file():
                raise RuntimeError(f"ISO selected file failed to extract: {name}")
            if file.stat().st_size!=by_path[name]:
                raise RuntimeError(f"ISO source size differs after extraction: {name}")
        driverfile=stage/"README-WINDOWS-ARM64-DRIVERS.txt"
        driverfile.write_text(
            "Windows Mode compact Windows 11 ARM64 VirtIO driver media.\n"
            "This disc contains unmodified INF/SYS/CAT/DLL/BIN files taken from\n"
            "Fedora virtio-win-0.1.285.iso, whose SHA-256 was verified first.\n"
            "Source: https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/"
            "archive-virtio/virtio-win-0.1.285-1/virtio-win-0.1.285.iso\n"
            "Source SHA-256: "+PINNED_ORIGINAL_SHA256+"\n"
            "Use only signed ARM64 drivers matching virtual hardware in Device Manager.\n"
            "This media does not install anything automatically. Existing NVMe,\n"
            "E1000, RAMFB and USB hardware are unchanged by attaching the disc.\n"
            "Win11 ARM64 VirtIO GPU display compatibility and native Fold\n"
            "resolution switching are NOT established by this disc.\n")
        # Optional user-initiated Windows guest installer. Does NOT run at boot.
        # Microsoft pnputil /install selects matching present PnP devices; it
        # does not force a lower-ranked or incompatible driver on other hardware.
        installer=stage/"INSTALL-MATCHING-ARM64-DRIVERS.cmd"
        installer.write_bytes((
            "@echo off\r\n"
            "setlocal EnableExtensions\r\n"
            "title Windows Mode - Signed ARM64 VirtIO Drivers\r\n"
            "echo Windows Mode offline Windows 11 ARM64 VirtIO driver installer\r\n"
            "echo Only matching installed virtual devices receive a driver.\r\n"
            "echo No boot disk controller or UEFI settings are changed by this script.\r\n"
            "echo.\r\n"
            "if /I not \"%PROCESSOR_ARCHITECTURE%\"==\"ARM64\" goto :wrong_arch\r\n"
            "fltmc.exe >nul 2>&1\r\n"
            "if errorlevel 1 goto :needs_admin\r\n"
            "echo Checking Windows ARM64 driver signature and matching devices...\r\n"
            "echo A log is saved to %%TEMP%%\\WindowsMode-ARM64-Drivers.log\r\n"
            "pnputil.exe /add-driver \"%~dp0*.inf\" /subdirs /install > \"%TEMP%\\WindowsMode-ARM64-Drivers.log\" 2>&1\r\n"
            "set \"RESULT=%ERRORLEVEL%\"\r\n"
            "type \"%TEMP%\\WindowsMode-ARM64-Drivers.log\"\r\n"
            "echo.\r\n"
            "echo Finished. No force, uninstall, reboot or storage switch was requested.\r\n"
            "echo A reboot might be requested by Windows after driver installation.\r\n"
            "pause\r\n"
            "exit /b %RESULT%\r\n"
            ":wrong_arch\r\n"
            "echo This helper is only for native Windows ARM64. No drivers installed.\r\n"
            "pause\r\n"
            "exit /b 2\r\n"
            ":needs_admin\r\n"
            "echo Right-click and choose Run as administrator, then accept Windows UAC.\r\n"
            "echo Nothing was installed.\r\n"
            "pause\r\n"
            "exit /b 3\r\n"
        ).encode("ascii"))
        # A fixed timestamp lets the resulting ISO be reproducible across CI builds.
        for file in stage.rglob("*"):
            if file.is_file():
                import os
                os.utime(file,(1609459200,1609459200))
        run("genisoimage","-quiet","-J","-r","-iso-level","3",
            "-V","WIN11_ARM64_DRIVERS","-o",str(a.compact_iso),str(stage))
        with a.compact_iso.open("rb") as f:
            f.seek(32769)
            if f.read(5)!=b"CD001":
                raise RuntimeError("Compact driver output is not valid ISO9660")
        included=[{"path":n,"size":(stage/n).stat().st_size,
                   "sha256":sha256(stage/n)} for n in names]
    report={
      "source":"Vectras VM Fedora Windows VirtIO driver ISO",
      "upstream_iso_sha256":PINNED_ORIGINAL_SHA256,
      "upstream_url":"https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/archive-virtio/virtio-win-0.1.285-1/virtio-win-0.1.285.iso",
      "drivers_scope":"Windows 11 ARM64 only",
      "family_present":found,
      "driver_files":len(matched),
      "guest_installer":"INSTALL-MATCHING-ARM64-DRIVERS.cmd",
      "total_selected_files":len(included),
      "driver_disc_sha256":sha256(a.compact_iso),
      "driver_disc_bytes":a.compact_iso.stat().st_size,
      "files":included,
      "automatic_driver_install":False,
      "native_gpu_driver_verified":False,
      "physical_fold_verified":False,
    }
    a.manifest.write_text(json.dumps(report,indent=2,sort_keys=True)+"\n")
    print(json.dumps({k:v for k,v in report.items() if k!="files"},indent=2))
    print("PASS: pinned source, unmodified ARM64 driver files, compact ISO output")

if __name__=="__main__":
    main()

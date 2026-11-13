#!/usr/bin/env python3
"""Source-only pinned Android QEMU/WindowsiOS accelerator audit, NO VM execution.

Never imports, reads, creates, writes, or modifies guest QCOW2/UEFI files.
Never builds an APK. Fails closed if release or pinned source anchors changed.
"""
import argparse
from pathlib import Path
import subprocess
import sys

RELEASE_SHA = "68c0b948b89ab03c5f5b010a2e35b99e69339e1d"
WINDOWSIOS_SHA = "0071d6975287fd720b9046dfd3bd80b6f2aa765e"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def verify_git(path, expect):
    observed = subprocess.check_output(
        ["git", "-C", str(path), "rev-parse", "HEAD"], text=True
    ).strip()
    require(observed == expect, f"Pinned commit mismatch: {path} expected {expect}, got {observed}")
    return observed


def inspect(path, anchors, heading, report):
    require(path.is_file(), f"Required pinned source missing: {path}")
    text = path.read_text(errors="replace")
    for needle in anchors:
        require(needle in text, f"{heading}: missing anchor {needle!r} in {path}")
    report.append(f"- {heading}: source present, {len(text):,} characters; pinned anchors verified")
    return text


def run(repo, qemu, ios, output):
    release = repo / ".github/workflows/windows-mode-build.yml"
    perf = repo / "windows-mode-boot-benchmark-repairs.py"
    layout = repo / "windows-mode-desktop-boot-repairs.py"
    report = [
        "# Stage 0 — source-only accelerator integration audit",
        "",
        f"Protected Windows Mode 7.1.14 SHA: {RELEASE_SHA}",
        f"QEMU source tag: v11.1.3",
        f"WindowsiOS source pin: {WINDOWSIOS_SHA}",
        "",
        "## Current packaging and profiling",
    ]
    ci = inspect(
        release,
        ['android:versionCode="70114"',
         "qube-arm-q11.1.3-android-arm64-v8a-release.apk",
         "cp /tmp/qube-extracted/lib/arm64-v8a/libqemu-system-aarch64.so",
         ":qube-android-arm:assembleRelease"],
        "Windows Mode release CI", report,
    )
    require("make qube" not in ci, "Release CI unexpectedly rebuilds native QEMU; re-audit.")
    report.append("- BLOCKER: QEMU 11.1.3 is rehydrated as a PRECOMPILED shared library. No native accelerator can be inserted into this existing binary.")
    inspect(
        perf, ["query-blockstats", "info jit", "query-cpus-fast",
               "WindowsBootMetrics.markWallpaper", "WindowsBootMetrics.markTaskbar",
               "WindowsCpuTrial.runtimeCores"],
        "Windows 7.1.14 boot diagnostics", report,
    )
    inspect(layout, ["WindowsInstalledBoot", "showWindowsDesktopRecovery", "mGap.setVisibility(View.GONE)"],
            "Fold portrait + Windows desktop recovery", report)
    report += [
        "- Evidence gap: No real-device 20–30 minute boot report, Windows shell readiness verification, or QMP statistics was provided here.",
        "",
        "## QEMU accelerator interface at pinned v11.1.3",
    ]
    inspect(qemu/"accel/tcg/tcg-accel-ops.c", ["AccelOpsClass", "cpu_exec"],
            "TCG vCPU execution lifecycle", report)
    inspect(qemu/"accel/hvf/hvf-accel-ops.c", ["AccelOpsClass", "synchronize"],
            "HVF accelerator lifecycle and CPU state", report)
    inspect(qemu/"target/arm/hvf/hvf.c", ["CPUARMState", "hvf_arch_vcpu_exec"],
            "AArch64 HVF CPU state/interrupt adapter", report)
    inspect(qemu/"accel/meson.build", ["subdir('tcg')", "subdir('hvf')"],
            "QEMU accelerator build registration", report)
    report += [
        "- New Windows ARM64 direct translator requires new native QEMU accelerator implementation; HVF source is a lifecycle *example* and not a drop-in backend.",
        "",
        "## WindowsiOS portable and Apple-specific code",
    ]
    verify_git(ios, WINDOWSIOS_SHA)
    core = inspect(ios/"core/translate.c", ["translate_excl", "#include"],
                   "Direct AArch64 instruction translator", report)
    mmu = inspect(ios/"core/mmu.c", ["hv_mmu_ukey", "hv_tlb_switch_user"],
                  "Windows NT software-MMU/ASID logic", report)
    enter = inspect(ios/"host/enter.S", ["hv_enter", "hv_exit"],
                    "Mach-O host entry/exit assembly", report)
    jit = inspect(ios/"host/jit_region.c", ["vm_remap", "sys_icache_invalidate"],
                  "Mach virtual executable-memory mapping", report)
    host = inspect(ios/"host/winvm.c", ["CoreFoundation", "winvm_start"],
                   "Apple runtime/guest-machine host", report)
    cpu = inspect(ios/"core/cpu.h", ["HvCpu", "guest x18", "host x18"],
                  "Host ABI/guest register state", report)
    dependencies = {
        "Darwin/Mach JIT memory": sum(jit.count(t) for t in ("vm_remap", "MAP_JIT", "pthread_jit_write_protect_np")),
        "Mach-O ARM64 assembly": enter.count("_hv_"),
        "Apple native host references": sum(host.count(t) for t in ("CoreFoundation", "os_unfair_lock", "TargetConditionals")),
        "ARM64 guest/MMU specialization": mmu.count("hv_tlb_switch_user")+mmu.count("hv_mmu_ukey"),
    }
    report.extend(f"- {name}: {count} source references" for name,count in dependencies.items())
    require(dependencies["Darwin/Mach JIT memory"] > 0, "Expected Mach dependency absent; source evolved.")
    require(dependencies["ARM64 guest/MMU specialization"] > 0, "Expected Windows memory functionality absent.")
    report += [
        "",
        "## Stage 0 result",
        "PASS — pinned source/layout/package gates only.",
        "",
        "**NOT VERIFIED**: Android Fold executable-memory policy; complete direct translator port; QEMU accelerator; UEFI or Linux boot under it; Windows boot; taskbar readiness; battery/thermal improvement; any percentage speedup.",
        "",
        "NEXT: obtain 7.1.14 on-device boot metrics and prove reproducible Android QEMU native *source* build before touching the signed release app.",
        "PROTECTED: original QCOW2, UEFI VARS, network, display, input, package identity, signing key and unrelated projects.",
    ]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(report)+"\n")
    print(output.read_text())


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--qemu", type=Path, required=True)
    parser.add_argument("--windowsios", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    p=parser.parse_args()
    try:
        run(p.repo, p.qemu, p.windowsios, p.output)
    except Exception as exc:
        sys.exit(f"STAGE-0 SOURCE GATE FAILED: {exc}")

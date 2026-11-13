# Windows Mode: ARM64 accelerator research (source-only)

**Stage 0: native packaging audit and real-device boot profiling. NO NEW APK.**

Protected Windows Mode 7.1.14 release branch: windows-mode-android-research, SHA 68c0b948b89ab03c5f5b010a2e35b99e69339e1d. Research branch: windows-arm64-qemu-accel-research. No changes are allowed outside engineering/windows-arm64-accelerator/ and the single dedicated research workflow.

## Decision: execution accelerator, not TCG backend

Investigate an alternative direct AArch64 translator as a separate QEMU accelerator which uses the QEMU virtual hardware and block storage stack. Do not transplant a self-contained WindowsiOS ARM64 translator into QEMU's TCG code generator. This is NOT a ready-to-use option. QEMU's vCPU lifecycle, CPUARMState synchronization, sysregs, interrupts, RAMBlocks, AddressSpace MMIO, reset and migration semantics require a new accelerator implementation and correctness tests.

**Critical build blocker:** The current Android release workflow copies precompiled libqemu-system-aarch64.so from the QubeVM 7.1.4 release APK. It does not recompile QEMU 11.1.3. A new native accelerator cannot ship as a Java patch or through the current packaged binary; a reproducible Android native QEMU build must be established before proceeding.

**Performance caveat:** QEMU ARM64 TCG already performs JIT code generation. A claimed 2-5x speedup from the WindowsiOS translator is an unverified estimate. WindowsiOS has Apple Mach/JIT dependencies; Android x18/ABI, Linux signals, executable memory restrictions, system-register, and software-MMU fidelity must be addressed. Never use RWX as a shortcut or bypass Android protections.

## Stage 0: evidence first

1. Collect one real 7.1.14 Fold boot report with QEMU start, first framebuffer, manually marked Windows wallpaper and usable Start/taskbar times. The existing build includes read-only QMP query-blockstats, info jit and query-cpus-fast. First framebuffer may be UEFI. No on-device data has yet proven which boot phase is slow.
2. Distinguish CPU/TB-cache pressure, shared-storage I/O stalls, Windows setup/services/indexing and thermal throttling. Do not assume the taskbar is permanently absent or that CPU is the only bottleneck.
3. Audit reproducibility of QEMU 11.1.3 native C build for Android and review the actual accelerator API at pinned source. Verify all WindowsiOS portability claims against source, not only README.
4. Establish a source-only, headless compilation plan without modifying Windows disk or UEFI. Do not distribute a new app until an experimental engine successfully boots Windows.

## Mandatory implementation gates

- Reproduce upstream WindowsiOS ARM64 instruction/MMU tests on ARM64 Linux, then on Android hardware where safe. The separate old decoder/JIT-stub APK does NOT count as a complete engine.
- Port the full translator's machine state, JIT memory, assembly calling convention, software MMU and native thread/event handling; differential test against native AArch64 reference.
- Rebuild QEMU 11.1.3 for Android reproducibly from pinned C sources and ship a verified native accelerator in an isolated CI artifact, not an installable Android app.
- Boot UEFI and Linux AArch64 via retained QEMU virt machine, RAM, GIC, ACPI, NVMe, USB and QEMU block driver.
- Run a licensed Windows ARM64 guest on a QCOW2 overlay with read-only original base and a COPY of UEFI VARS. Require logon and responsive Explorer/Start/taskbar, not a screenshot-only pixel guess.
- Compare engines on the SAME Fold, same guest disk/UEFI configuration, same power and thermal conditions. A claimed improvement must include measured boot-to-usable-desktop timings.
- Only after the Windows success gate, add opt-in engine selection to the existing Windows Mode Android app, same package/signing identity, QEMU default/fallback and reversible user setting. Do not silently switch or alter the saved disk.

## Existing 7.1.14 evidence

- windows-mode-boot-benchmark-repairs.py: four-vs-two vCPU trial, host process time, manually marked wallpaper/taskbar milestones, QMP CPU/JIT/disk counters. Built by CI; no successful real-device measurements yet provided.
- windows-mode-desktop-boot-repairs.py: QubeVM portrait-gap change, opt-in Windows installed boot, user-confirmed Task Manager/Explorer key sequences.
- .github/workflows/windows-mode-build.yml: downloads QubeVM release APK and copies its precompiled libqemu-system-aarch64.so. Only Java is rebuilt.
- WindowsiOS pinned to 0071d6975287fd720b9046dfd3bd80b6f2aa765e; portable classifier/operand tests and a fixed JIT stub passed in Linux AArch64 CI in the separate windows-arm64-android-jit-poc branch. Those tests do not demonstrate an Android Windows boot.

## Pinned research sources

- QEMU v11.1.3: https://github.com/qemu/qemu/tree/v11.1.3
- QEMU accelerator internals: accel/tcg/tcg-accel-ops.c, accel/hvf/hvf-accel-ops.c, target/arm/hvf/hvf.c, accel/meson.build
- QubeVM v7.1.4: https://github.com/rhn-1k/QubeVM/tree/7.1.4
- WindowsiOS source: https://github.com/ammaarreshi/WindowsiOS ; review core/translate.c, core/mmu.c, core/machine.c, core/cpu.h, host/enter.S, host/jit_region.c and host/winvm.c.

## Strict protection

No installed-Windows disk or firmware access, no disabling Defender, no root, no KVM, no SELinux bypass, no edits to release scripts, packaged APK, signing, app settings or unrelated projects. CI stage is read-only and emits only a source-audit report, NOT an APK.

# Current owner directive and signed TEST deliverable (14 November 2026)

The governing instruction is [issue #2](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/issues/2). It authorizes an immediate signed **TEST** APK after native compile/link/load, dependency checks and basic real QEMU guest execution. Full Linux/Windows/Fold acceptance gates apply to a validated final release. Older “NO APK until Windows boots” directions in the historical notes below are superseded by this explicit owner override.

A real same-app signed candidate was published from packaging commit `5e77b651c18bf750c9d0523c92ac1c59752251d9`: [run 43567015803, artifact 14102773528](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43567015803/artifacts/14102773528). Package `com.fold.windowsmode.research`, versionCode `70115`, versionName `7.1.15-winjit-candidate`; original signer SHA-256 `a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`. APK SHA-256 `54ac406d5b8b07a7404004f978efb87dd87e63273ec62f18d7e21cd9cb3244a7`.

This candidate contains Android QEMU built at `1b3274f` and translator built at `7da8aa3`. Fixes preserve x16/x17 across the ELF helper branch, forward the QEMU-selected CPU IDs and generic timers, allow QEMU timer/device scheduling and WFI wakeup, and bound single-block execution so native self-chaining cannot starve shutdown. [Real guest suite 43566851599](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43566851599) passed ROM/EL0/MMU/TCR/MAIR, Cortex-A53 MIDR/PFR0/CNTFRQ parity, timer IRQ30/GICv2/EOI/WFI and normal timeout termination. A separate synthetic ROM passed high-address load/store and low/high non-alias checks with `-cpu max -smp 4 -m 6144`. [Linux userspace run 43566851666](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43566851666) passed with Cortex-A53/1 vCPU/512 MiB.

**Final release remains blocked:** WinJIT's `max` initializes a conservative A57 baseline and does not match TCG's full `max` feature set; a synthetic 4-vCPU memory test does not establish complete SMP/production-device compatibility. Licensed Windows UEFI/NVMe/desktop/application acceptance and physical Galaxy Fold upgrade/runtime are unverified. The owner confirmed no disposable Windows test image is available. No Windows boot result or performance speedup is claimed.

The earlier [first TEST artifact](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43565659417/artifacts/14101774379) remains historical; use the corrected artifact above.

Default TCG and the protected app identity/settings remain intact. WinJIT requires explicit opt-in and a separate test VM with disk/media and explicit UEFI VARS inside the app's `WinJIT-disposable` directory. Canonical-path checks reject escapes and the original Windows disk; shared folders and extra QEMU arguments are disallowed for WinJIT. Test writes use `-snapshot`. See [TEST-CANDIDATE-README.txt](TEST-CANDIDATE-README.txt).

The candidate workflow verifies source provenance, native dependencies, exact packaged ELF bytes, actual Android package/version/certificate, and publishes the APK plus proof. Java guard tests execute the staged code against disposable files. No installed Windows disk or live firmware variables were accessed. The protected 7.1.14 branch remains `68c0b948b89ab03c5f5b010a2e35b99e69339e1d`; Infinity and Cobra are outside scope.

## Historical research notes (not current release instructions)

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


## Verified progress on the research branch (2026-11-13)

- QEMU 11.1.3 Android ARM64 native **source build PASSED**: Actions 43520669820.
- **Actual ARM64 guest microprogram execution PASSED** on ARM64 Linux: native WinJIT translated MOV/ADD/LDR/branch instructions, accessed a QEMU-style MMIO callback via the new W^X code cache, and stopped at the expected PC with verified registers. Actions 43551607658. This is a real translator test, **not QEMU or Windows boot**.
- **QEMU native bridge source-build PASSED** (Actions 43551988314): rebuilt QEMU 11.1.3 Android AArch64 `libqemu-system-aarch64.so` from source with the WinJIT ARM64 EL1 CPU snapshot and QEMU AddressSpace read/write callbacks. Inspecting the resulting ELF confirmed all three symbols (`winjit_qemu_capture_arm64`, `winjit_qemu_mmio_read`, `winjit_qemu_mmio_write`) present. ELF SHA256 `9267e785ea3db89380ff573e6b04c4d2b63a1ea4b9fe0a63eb9477b91d380db8`; artifact 14097155283. Native WinJIT ARM64 execution is separately verified by Actions 43551607658. **This is not a QEMU `-accel winjit` execution backend and is not Windows boot proof.**
- WindowsiOS translated ARM64/MMU core and entry assembly **COMPILED** on Android NDK, and an isolated fully linked ARM64 **libwinjit-core-android-aarch64.so** was produced with no undefined native symbols: Actions 43548034462.
- Linux smoke test for the new Android-style **dual RW/RX memfd code cache PASSED**. This is W^X, not a root/SELinux bypass; actual Fold executable-memory policy and native guest execution remain unverified.
- An in-place upgrade **package identity gate PASSED** (same `com.fold.windowsmode.research`, required protected signer fingerprint, candidate versionCode `70115`, and rejection of the mistaken `com.fold.windowsmode.sourcenative` duplicate). This is SOURCE-PREPARATION TESTING ONLY, not a signed upgrade APK.
- The previously built `sourcenative` test APK (Actions 43531348059) used stock QEMU and did **not** contain active WinJIT acceleration. Do not treat that duplicate app as the direct upgrade.
- **Critical missing integration:** no QEMU `-accel winjit` implementation, lifecycle/CPUARMState synchronization, mapped QEMU RAM/MMIO and DMA invalidation, GIC/timers/interrupts, system register fidelity, or UEFI/Linux/Windows boot verification under WinJIT. The linked core library cannot replace QEMU's native accelerator by being placed in APK jniLibs.
- **Release gate:** do not produce or publish an in-place accelerated APK until WinJIT actually runs a guest under QEMU, passes UEFI and licensed Windows ARM64 desktop/compatibility checks on a safe disk overlay with copied UEFI VARS, and the signed APK passes package/signer/version checks. Preserve the original Windows Mode 7.1.14, signed identity, installed Windows and user data.

## Latest real QEMU WinJIT native guest acceptance (2026-11-14)

- **Passed** run [43557386466](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43557386466), evidence artifact 14098414521: QEMU 11.1.3 with the actual `-accel winjit` native ARM64 execution path printed the microguest's `OK` on emulated UART and remained alive until the deliberately imposed timeout (`GUEST_EXIT=124`), with no crash captured.
- This fixed a real core abort seen in run [43557029014](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43557029014): WinJIT's pinned translator can issue valid zero-byte code-pool writes; the original Android W^X cache treated them as fatal. Fix `e3241885`, regression `f3ef2377`.
- **Passed** Android WinJIT core compilation and regression tests, run 43557455819, and native translator regression run 43557458948. Direct-upgrade source checks passed in run 43556353079; no APK signed.
- **NOT VERIFIED:** UEFI/Linux/Windows ARM64 boot, Windows desktop/application compatibility, GIC interrupts and timers, DMA invalidation, transition to EL0, Android Fold runtime and actual APK in-place install. Successful microguest execution is not Windows compatibility evidence. Release remains blocked, and the original Windows Mode 7.1.14/disk/signing identity stays protected.

## Further integration gates (AArch64 EL0 and software GICv2)

- EL0t CPU-state import/export and QEMU state snapshot now support both EL0 and EL1; the ABI was bumped from v1 to v2 so older native libraries cannot silently be mistaken for the new contract. EL2/EL3 remain intentionally rejected.
- Real QEMU 11.1.3 \`-accel winjit\` on ARM64 Linux PASSED a user-mode exception-return microguest (EL1 \`ERET\` → EL0, UART \`USER\`, no crash, intentional timeout) in Actions run **43558338602**. This is an actual guest-execution result, not Windows boot.
- A narrow experimental QEMU GICv2 physical IRQ/FIQ level bridge was added. Its Android ARM64 source build PASSED in run **43558439732**; actual GIC/timer interrupt behavior under Linux or Windows is NOT verified.
- An actual Linux ARM64 kernel plus initramfs \`LINUX-READY\` boot acceptance workflow was added (\`.github/workflows/windows-arm64-qemu-winjit-linux-boot.yml\`). A test only passes if Linux userspace starts, QEMU does not abort, and the run stays stable. This never accesses any Windows disk.
- **Never issue a direct-upgrade APK on the strength of EL0 or Linux alone.** The final acceptance still requires a verified licensed Windows ARM64 boot and compatible desktop/apps, a separate disk overlay/copy of UEFI VARS, Android/Fold runtime checks, and the original package/signature update identity.

## Linux boot differential and APK package preflight (2026-11-14)

- Actions run [43559420516](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43559420516): the same Ubuntu AArch64 Linux kernel and initramfs reached `LINUX-READY` under stock QEMU TCG, establishing valid media. WinJIT did **not** boot it: `HV_EXIT_FAULT_LOOP=9`, `hv_reason=3`, exception-vector PC `0x200`, `ESR_EL1=0x86000004`, `FAR_EL1=0x200`, at EL1 with the MMU enabled and no pending IRQ. This is a real instruction-translation fault; do not mislabel it a compiler, ISO, or APK-signing issue.
- Scoped, nonbehavioral fault tracing in the QEMU bridge now records TTBR0/TTBR1/TCR/MAIR/VBAR, mapped RAM count, and the last 16 translated blocks/exception transitions on exit. Run [43560713886](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43560713886) verifies this new diagnostic.
- A deterministic 4 KiB stage-1 identity-mapping microguest now compares stock QEMU TCG against actual QEMU `-accel winjit` with both running identical instructions before attempting a full Linux boot. The test is in `.github/workflows/windows-arm64-qemu-winjit-boot-smoke.yml` and emits actual UART and crash evidence.
- Actions [43560296567](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43560296567) and [43560499524](https://github.com/mahfouzhassine8-ops/Rosalina-companion-/actions/runs/43560499524) **passed**: two native ARM64 libraries staged in the disposable Android source with the original `com.fold.windowsmode.research` package ID, higher `70115` version code, and WinJIT opt-in/TCG default. Comparing actual protected 7.1.14 APK QEMU native dependencies with the new engine showed only one added dependency, Android platform `libdl.so`, and all other required shared libs remain bundled. No APK was created by these tests.
- **Release blocker remains:** Linux via WinJIT and licensed Windows ARM64 desktop/compatibility have not passed; Android Fold runtime is unverified. Never substitute a synthetic UART test or source-package preflight for the user's protected Windows installation test. Do not produce a signed APK before the explicitly locked acceptance criteria.

## Strict protection

No installed-Windows disk or firmware access, no disabling Defender, no root, no KVM, no SELinux bypass, no edits to release scripts, packaged APK, signing, app settings or unrelated projects. CI stage is read-only and emits only a source-audit report, NOT an APK.

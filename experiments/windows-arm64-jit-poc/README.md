# Windows ARM64 — Android Native JIT Proof of Concept (Stage 0)

**This is an isolated engine experiment, not a Windows emulator or Windows Mode update.** The separate test app package ID is com.fold.windowsjit.probe. It does not read/write QubeVM or Windows Mode databases, QCOW2 files, installers, UEFI firmware, user storage or network. It requests **zero Android permissions**.

## Goal

[WindowsiOS](https://github.com/ammaarreshi/WindowsiOS) has a custom ARM64-to-ARM64 translator. Its decoder/operand logic is portable C, while its actual JIT, MMU, dispatcher, and guest hardware runtime depend on Apple's Mach, JIT-page entitlement/debugger, clocks, and ABI. Copying the iOS engine into Android without porting these is not viable.

**Stage 0 tests real prerequisites**:

- Cross-compiles the pinned WindowsiOS ARM64 instruction classifier and GPR operand decoder using the Android NDK.
- Executes upstream instruction/operand test cases on an ARM64 Linux CI runner. Only the Mach-style assembly symbol names and page relocation are adapted to ELF for those host tests.
- Builds a separate Android test app with a bounded decoder benchmark (150,000 iterations).
- Tries executing a **fixed** two-instruction ARM64 function that returns 42. It uses memfd RX/RW aliases or an anonymous RW-to-RX W^X fallback, without mapping a RWX region.
- Reports failure reasons if SELinux, kernel policy or application sandbox limits executable allocations.
- Produces a separately packaged, test-signed Android APK (not replacing Windows Mode).

**Stage 0 does NOT yet port the complete WindowsiOS translator, Windows NT memory manager, virtual NVMe, firmware, display, or Windows operating system.** Compilation or a 42-returning JIT stub does not prove Windows will boot or that the engine is faster than QEMU.

## Provenance and license

Copied unmodified source files from [ammaarreshi/WindowsiOS](https://github.com/ammaarreshi/WindowsiOS) at pinned commit **0071d6975287fd720b9046dfd3bd80b6f2aa765e**:

- vendor/windowsios/core/classify.c, classify.h, operands.c, operands.h
- vendor/windowsios/tests/test_classify.c, test_operands.c, classify_cases.s, operand_cases.s

Upstream attribution and license: GPL-2.0-or-later. Original test harness and Android port code in this isolated directory are likewise GPL-2.0-or-later. Any future integration with QubeVM's GPLv3 code must comply with both licenses and preserve attribution. Windows media and proprietary Windows binaries are not included.

## How to use

Install the separate **Windows JIT Engine Probe** APK. Tap **Run native engine checks**, then **Copy result**. Share the report to establish whether the execution mechanism actually works on your Galaxy Fold. It contains no Windows disk picker, no VM, and no root/network permission requests.

## Build and verification

Android SDK 36, NDK 27.2.12479018, CMake 3.22.1, Java 17, Gradle 9.6.0 and Android Gradle Plugin 9.4.1. GitHub Actions build logs verify compilation, package ID, signing, artifact checksums and source-scope containment. ARM Linux runner smoke tests are distinct from physical Android Fold acceptance.

The release APK uses QubeVM's publicly available test signing identity for the **different** experimental package. It is not production signing. No user-installed Windows Mode APK or its certificate is touched.

## After Stage 0 passes on actual hardware

1. Port the genuine WindowsiOS ARM64 translator and software MMU, replacing Darwin Mach and Mach-O specifics with Linux/Android equivalents, without connecting to the installed Windows disk.
2. Test translated, non-privileged ARM64 guest instruction sequences and compare registers/memory/flags with native references.
3. Measure performance against QEMU's own JIT on comparable workloads.
4. Only if correctness, sandbox permissions, and performance justify it, build an isolated Windows-compatible device platform and use a **separate** guest disk copy.

This is a significant research implementation, not a hidden setting in QubeVM.

## Absolute preservation

Do not modify the windows-mode-android-research branch, installed Windows Mode 7.1.14, its QCOW2, UEFI variables, settings, network, or unrelated project code. No permission bypass, KVM, root, debugger hack, or automatic Windows disk modification is authorized.

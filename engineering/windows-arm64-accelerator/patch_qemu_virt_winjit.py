#!/usr/bin/env python3
"""Allow research WinJIT to use QEMU's software GICv2 on virt (QEMU 11.1.3).

Applies ONLY to a disposable pinned QEMU checkout. Does not claim that a
GICv3/ITS or Windows guest works. Fail closed outside the exact source anchor.
"""
from pathlib import Path
import sys

qemu = Path(sys.argv[1])
p = qemu / "hw/arm/virt.c"
body = p.read_text()

gic_anchor = "} else if (tcg_enabled() || qtest_enabled())  {"
if body.count(gic_anchor) != 1:
    raise RuntimeError("Unexpected QEMU 11.1.3 GIC selection; release stays blocked")
# Wording and behavior deliberately restrict the experimental accelerator to
# GICv2, until GICv3 ICC system-register routing and interrupts are verified.
body = body.replace(gic_anchor, """} else if (winjit_accel_enabled()) {
        /* Research-only: WinJIT currently supports only QEMU's MMIO GICv2.
         * GICv3 sysreg CPU interfaces and ITS are not validated yet. */
        gics_supported |= VIRT_GIC_VERSION_2_MASK;
    } else if (tcg_enabled() || qtest_enabled())  {""")

# Add one forward declaration before the first QEMU source include without
# modifying any production headers or already existing accelerators.
header = '#include "qemu/osdep.h"'
if body.count(header) != 1:
    raise RuntimeError("Unexpected QEMU virt.c header")
body = body.replace(header,
                    header + '\n\nextern bool winjit_accel_enabled(void); /* research accelerator only */',
                    1)
p.write_text(body)
print("PASS: QEMU virt uses software GICv2 for experimental WinJIT only")
print("NOT VERIFIED: GICv3, CPU interrupt injection, DMA, Windows boot")


# QEMU's -cpu max treats a custom accelerator differently from TCG:
#   hwaccel_enabled(): false for the experimental backend
#   tcg_enabled(): false
# Consequently neither a CPU model nor AArch64 PA range is initialized.
# QEMU then measures PA width as 32 bits, making >4GiB guests unbootable.
# Use a *truthful, conservative* existing A57 feature baseline for WinJIT
# until its guest-visible ID/sysreg forwarding and feature parity are proven.
# Do not claim this makes -cpu max identical to TCG, or that the translator
# implements TCG's very large -cpu max feature set. This resolves only the
# erroneous 32-bit PA-width at QEMU board construction.
maxcpu = qemu / "target/arm/cpu-max.c"
body = maxcpu.read_text()
anchor = """    if (tcg_enabled() || qtest_enabled()) {
        aarch64_aa32_a57_init(obj, !aarch64_enabled);
    }

    if (!aarch64_enabled) {"""
replacement = """    if (winjit_accel_enabled()) {
        /* Research-only: WinJIT is neither QEMU TCG nor a HW accel.
         * Its -cpu max would otherwise leave all CPU features uninitialized,
         * returning a spurious 32-bit PAMax from arm_pamax() and rejecting
         * the real Windows Mode 6 GiB machine at board construction.
         * A57 has an architecturally defined 44-bit PA range here; do NOT
         * advertise unsupported 'max' TCG ISA features without proof. */
        g_assert(aarch64_enabled);
        aarch64_aa32_a57_init(obj, false);
        return;
    }
    if (tcg_enabled() || qtest_enabled()) {
        aarch64_aa32_a57_init(obj, !aarch64_enabled);
    }

    if (!aarch64_enabled) {"""
if body.count(anchor) != 1:
    raise RuntimeError("Unexpected pinned QEMU cpu-max.c initialization; no patch")
body = body.replace(anchor, replacement, 1)
header = '#include "qemu/osdep.h"'
if body.count(header) != 1:
    raise RuntimeError("Unexpected QEMU cpu-max.c include; no patch")
body = body.replace(header, header + '\n\nextern bool winjit_accel_enabled(void); /* research accelerator only */', 1)
maxcpu.write_text(body)
print("PASS: WinJIT -cpu max initializes a conservative QEMU A57 AArch64 PA range")
print("NOT VERIFIED: identical CPU max features vs TCG; Windows boot, interrupts, devices")

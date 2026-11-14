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

/* SPDX-License-Identifier: GPL-2.0-or-later
 * QEMU AArch64 / WinJIT CPU state boundary: explicitly versioned.
 * This is not a QEMU accelerator and does not run a guest.
 */
#ifndef WINJIT_CPU_BRIDGE_H
#define WINJIT_CPU_BRIDGE_H
#include <stdint.h>

#define WINJIT_CPU_ABI_VERSION 3U
#define WINJIT_CPU_OK 0
#define WINJIT_CPU_UNSUPPORTED (-1)
#define WINJIT_CPU_INVALID (-2)

/* Called on the owning QEMU vCPU thread while the BQL is held. Return 0 for
 * translator-owned registers, 1 on success, -1 for an unsupported access.
 * Hooks keep CPU identification and generic timer state in QEMU, without
 * changing the pinned HvCpu layout used by the entry assembly. */
typedef struct WinjitQemuSysregs {
    void *opaque;
    int (*read)(void *, uint32_t, uint64_t *);
    int (*write)(void *, uint32_t, uint64_t);
} WinjitQemuSysregs;

/* EL0t, EL1t and EL1h AArch64 states are supported; reject EL2/EL3.
 * QEMU's MTE/SVE/SME, EL2 traps, debug state and GIC remain separate gates. */
typedef struct WinjitA64State {
    uint32_t abi_version;
    uint32_t aarch64;
    uint64_t x[31];
    uint64_t sp;
    uint64_t pc;
    uint64_t pstate;
    uint64_t v_lo[32], v_hi[32];
    uint64_t fpcr, fpsr;
    uint64_t tpidr_el0, tpidrro_el0, tpidr_el1;
    uint64_t sctlr_el1, ttbr0_el1, ttbr1_el1, tcr_el1, mair_el1;
    uint64_t vbar_el1, contextidr_el1, cpacr_el1;
    uint64_t elr_el1, spsr_el1, sp_el0, sp_el1;
    uint64_t esr_el1, far_el1;
    uint64_t cntkctl_el1;
    WinjitQemuSysregs sysregs;
} WinjitA64State;

/* WinJIT HvCpu comes from pinned WindowsiOS core/cpu.h. No QEMU internals
 * are included here so the boundary can be unit tested in isolation. */
struct HvCpu;
int winjit_cpu_from_qemu_state(struct HvCpu *dst, const WinjitA64State *src);
int winjit_cpu_to_qemu_state(WinjitA64State *dst, const struct HvCpu *src);
int winjit_qemu_sysreg_read(struct HvCpu *, uint32_t, uint64_t *);
int winjit_qemu_sysreg_write(struct HvCpu *, uint32_t, uint64_t);

#endif

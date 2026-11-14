/* SPDX-License-Identifier: GPL-2.0-or-later
 * Selected AArch64 EL0/EL1 CPU state conversion between QEMU snapshot and HvCpu.
 * Incomplete accelerator states fail closed. No guest execution is enabled.
 */
#include "winjit_cpu_bridge.h"
#include "cpu.h"

#include <stddef.h>

/* Each QEMU vCPU imports its snapshot on the same thread that enters the
 * translator and its helpers. No process-global CPU model or host timer leaks
 * into another vCPU; standalone native tests have no installed hooks. */
static _Thread_local struct HvCpu *sysreg_cpu;
static _Thread_local WinjitQemuSysregs sysregs;

int winjit_qemu_sysreg_read(struct HvCpu *cpu, uint32_t reg, uint64_t *value)
{
    return cpu == sysreg_cpu && sysregs.read
        ? sysregs.read(sysregs.opaque, reg, value) : 0;
}

int winjit_qemu_sysreg_write(struct HvCpu *cpu, uint32_t reg, uint64_t value)
{
    return cpu == sysreg_cpu && sysregs.write
        ? sysregs.write(sysregs.opaque, reg, value) : 0;
}

#define NZCV 0xf0000000ULL
#define DAIF 0x3c0ULL
#define PSTATE_EXTRA ((1ULL << 12) | (1ULL << 22) | (1ULL << 23) | \
                      (1ULL << 24) | (1ULL << 25))
#define PSTATE_MODE 0xfULL
#define SUPPORTED_PSTATE (NZCV | DAIF | PSTATE_EXTRA | PSTATE_MODE)

static int state_supported(uint32_t is_aarch64, uint64_t pstate)
{
    if (is_aarch64 != 1 || (pstate & ~SUPPORTED_PSTATE) != 0) {
        return WINJIT_CPU_UNSUPPORTED;
    }
    /* EL0t, EL1t, EL1h only. EL2/EL3 and invalid EL0h reject safely. */
    uint64_t mode = pstate & PSTATE_MODE;
    if (mode != 0 && mode != 4 && mode != 5) {
        return WINJIT_CPU_UNSUPPORTED;
    }
    return WINJIT_CPU_OK;
}

int winjit_cpu_from_qemu_state(struct HvCpu *dst, const WinjitA64State *src)
{
    if (!dst || !src || src->abi_version != WINJIT_CPU_ABI_VERSION) {
        return WINJIT_CPU_INVALID;
    }
    int result = state_supported(src->aarch64, src->pstate);
    if (result != WINJIT_CPU_OK) {
        return result;
    }
    sysreg_cpu = dst;
    sysregs = src->sysregs;

    /* Do not clear HvCpu: machine pointers, cache generations, TLB and IRQ
     * wires are initialized by the WinJIT machine and are not CPU registers. */
    for (unsigned i = 0; i < 31; ++i) {
        dst->x[i] = src->x[i];
    }
    for (unsigned i = 0; i < 32; ++i) {
        dst->v[i] = ((__uint128_t)src->v_hi[i] << 64) | src->v_lo[i];
    }
    dst->sp = src->sp;
    dst->pc = src->pc;
    dst->el = (uint32_t)((src->pstate >> 2) & 3);
    dst->spsel = (uint32_t)(src->pstate & 1);
    dst->nzcv = src->pstate & NZCV;
    dst->daif = src->pstate & DAIF;
    dst->pstate_extra = src->pstate & PSTATE_EXTRA;
    dst->fpcr = src->fpcr;
    dst->fpsr = src->fpsr;
    dst->tpidr_el0 = src->tpidr_el0;
    dst->tpidrro_el0 = src->tpidrro_el0;
    dst->tpidr_el1 = src->tpidr_el1;
    dst->sctlr_el1 = src->sctlr_el1;
    dst->ttbr0_el1 = src->ttbr0_el1;
    dst->ttbr1_el1 = src->ttbr1_el1;
    dst->tcr_el1 = src->tcr_el1;
    dst->mair_el1 = src->mair_el1;
    dst->vbar_el1 = src->vbar_el1;
    dst->contextidr_el1 = src->contextidr_el1;
    dst->cpacr_el1 = src->cpacr_el1;
    dst->elr_el1 = src->elr_el1;
    dst->spsr_el1 = src->spsr_el1;
    dst->sp_el0 = src->sp_el0;
    dst->sp_el1 = src->sp_el1;
    dst->esr_el1 = src->esr_el1;
    dst->far_el1 = src->far_el1;
    dst->cntkctl_el1 = src->cntkctl_el1;
    return WINJIT_CPU_OK;
}

int winjit_cpu_to_qemu_state(WinjitA64State *dst, const struct HvCpu *src)
{
    if (!dst || !src) {
        return WINJIT_CPU_INVALID;
    }
    if ((src->el != 0 && src->el != 1) || src->spsel > 1 ||
        (src->el == 0 && src->spsel != 0) ||
        (src->nzcv & ~NZCV) || (src->daif & ~DAIF) ||
        (src->pstate_extra & ~PSTATE_EXTRA)) {
        return WINJIT_CPU_UNSUPPORTED;
    }

    dst->abi_version = WINJIT_CPU_ABI_VERSION;
    dst->aarch64 = 1;
    dst->sysregs = src == sysreg_cpu ? sysregs : (WinjitQemuSysregs){0};
    for (unsigned i = 0; i < 31; ++i) {
        dst->x[i] = src->x[i];
    }
    for (unsigned i = 0; i < 32; ++i) {
        dst->v_lo[i] = (uint64_t)src->v[i];
        dst->v_hi[i] = (uint64_t)(src->v[i] >> 64);
    }
    dst->sp = src->sp;
    dst->pc = src->pc;
    dst->pstate = ((uint64_t)src->el << 2) | (uint64_t)src->spsel |
                  src->nzcv | src->daif | src->pstate_extra;
    dst->fpcr = src->fpcr;
    dst->fpsr = src->fpsr;
    dst->tpidr_el0 = src->tpidr_el0;
    dst->tpidrro_el0 = src->tpidrro_el0;
    dst->tpidr_el1 = src->tpidr_el1;
    dst->sctlr_el1 = src->sctlr_el1;
    dst->ttbr0_el1 = src->ttbr0_el1;
    dst->ttbr1_el1 = src->ttbr1_el1;
    dst->tcr_el1 = src->tcr_el1;
    dst->mair_el1 = src->mair_el1;
    dst->vbar_el1 = src->vbar_el1;
    dst->contextidr_el1 = src->contextidr_el1;
    dst->cpacr_el1 = src->cpacr_el1;
    dst->elr_el1 = src->elr_el1;
    dst->spsr_el1 = src->spsr_el1;
    dst->sp_el0 = src->sp_el0;
    dst->sp_el1 = src->sp_el1;
    dst->esr_el1 = src->esr_el1;
    dst->far_el1 = src->far_el1;
    dst->cntkctl_el1 = src->cntkctl_el1;
    return WINJIT_CPU_OK;
}

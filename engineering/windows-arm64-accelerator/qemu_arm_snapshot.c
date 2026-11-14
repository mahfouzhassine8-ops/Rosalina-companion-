/* SPDX-License-Identifier: GPL-2.0-or-later
 * QEMU 11.1.3 AArch64 EL0/EL1 system CPU -> WinJIT ABI v1 snapshot.
 * Executes NO guest instructions and changes NO QEMU guest register.
 * This must be called only with a quiescent vCPU under appropriate locks.
 * Missing IRQ/GIC/timers, device and migration fidelity still block release.
 */
#include "qemu/osdep.h"
#include "cpu.h"
#include "internals.h"
#include "cpregs.h"
#include "winjit_cpu_bridge.h"

static bool winjit_qemu_owned_sysreg(uint32_t reg)
{
    unsigned op0 = reg >> 14, op1 = (reg >> 11) & 7;
    unsigned crn = (reg >> 7) & 15, crm = (reg >> 3) & 15;
    /* ID/cache registers, CSSELR, and the EL0 generic timer views. Do not
     * forward translator MMU state or unrelated QEMU/TCG helpers. */
    return op0 == 3 && (crn == 0 ||
        (op1 == 3 && crn == 14 && (crm == 0 || crm == 2 || crm == 3)));
}

static const ARMCPRegInfo *winjit_qemu_reg(ARMCPU *cpu, uint32_t reg)
{
    return get_arm_cp_reginfo(cpu->cp_regs, ENCODE_AA64_CP_REG(
        reg >> 14, (reg >> 11) & 7, (reg >> 7) & 15, (reg >> 3) & 15, reg & 7));
}

static int winjit_qemu_sysreg_get(void *opaque, uint32_t reg, uint64_t *value)
{
    if (!winjit_qemu_owned_sysreg(reg)) {
        return 0;
    }
    ARMCPU *cpu = opaque;
    const ARMCPRegInfo *ri = winjit_qemu_reg(cpu, reg);
    if (!ri || (ri->type & ARM_CP_SPECIAL_MASK)) {
        return -1;
    }
    /* Use guest accessors, not raw migration accessors: timer CTL needs
     * synthesized ISTATUS and counter reads use QEMU's virtual timebase. */
    if (ri->type & ARM_CP_CONST) {
        *value = ri->resetvalue;
    } else if (ri->readfn) {
        *value = ri->readfn(&cpu->env, ri);
    } else if (ri->fieldoffset) {
        *value = raw_read(&cpu->env, ri);
    } else {
        return -1;
    }
    return 1;
}

static int winjit_qemu_sysreg_set(void *opaque, uint32_t reg, uint64_t value)
{
    if (!winjit_qemu_owned_sysreg(reg)) {
        return 0;
    }
    ARMCPU *cpu = opaque;
    const ARMCPRegInfo *ri = winjit_qemu_reg(cpu, reg);
    if (!ri || (ri->type & ARM_CP_SPECIAL_MASK) || !(ri->access & PL1_W)) {
        return -1;
    }
    if (ri->type & ARM_CP_CONST) {
        return 1;
    } else if (ri->writefn) {
        ri->writefn(&cpu->env, ri, value);
    } else if (ri->fieldoffset) {
        raw_write(&cpu->env, ri, value);
    } else {
        return -1;
    }
    return 1;
}

int winjit_qemu_capture_arm64(ARMCPU *cpu, WinjitA64State *dest)
{
    if (!cpu || !dest) {
        return WINJIT_CPU_INVALID;
    }
    CPUARMState *env = &cpu->env;
    if (!env->aarch64) {
        return WINJIT_CPU_UNSUPPORTED;
    }
    uint64_t pstate = pstate_read(env);
    /* EL0t, EL1t and EL1h only. EL2/EL3 fail closed. */
    if (((pstate & 0xf) != 0 && (pstate & 0xf) != 4 &&
         (pstate & 0xf) != 5) ||
        (pstate & ~(0xf0000000ULL | 0x3c0ULL |
                    (1ULL << 12) | (1ULL << 22) |
                    (1ULL << 23) | (1ULL << 24) |
                    (1ULL << 25) | 0xfULL)) != 0) {
        return WINJIT_CPU_UNSUPPORTED;
    }

    WinjitA64State tmp = {0};
    tmp.abi_version = WINJIT_CPU_ABI_VERSION;
    tmp.aarch64 = 1;
    for (unsigned i = 0; i < 31; ++i) {
        tmp.x[i] = env->xregs[i];
    }
    tmp.sp = env->xregs[31];
    tmp.pc = env->pc;
    tmp.pstate = pstate;
    for (unsigned i = 0; i < 32; ++i) {
        /* QEMU SVE zregs hold V0..V31 in their first 128 bits. */
        tmp.v_lo[i] = env->vfp.zregs[i].d[0];
        tmp.v_hi[i] = env->vfp.zregs[i].d[1];
    }
    tmp.fpcr = vfp_get_fpcr(env);
    tmp.fpsr = vfp_get_fpsr(env);
    tmp.tpidr_el0 = env->cp15.tpidr_el[0];
    tmp.tpidrro_el0 = env->cp15.tpidrro_el[0];
    tmp.tpidr_el1 = env->cp15.tpidr_el[1];
    tmp.sctlr_el1 = env->cp15.sctlr_el[1];
    tmp.ttbr0_el1 = env->cp15.ttbr0_el[1];
    tmp.ttbr1_el1 = env->cp15.ttbr1_el[1];
    tmp.tcr_el1 = env->cp15.tcr_el[1];
    tmp.mair_el1 = env->cp15.mair_el[1];
    tmp.vbar_el1 = env->cp15.vbar_el[1];
    tmp.contextidr_el1 = env->cp15.contextidr_el[1];
    tmp.cpacr_el1 = env->cp15.cpacr_el1;
    tmp.elr_el1 = env->elr_el[1];
    tmp.spsr_el1 = env->banked_spsr[BANK_SVC];
    tmp.sp_el0 = env->sp_el[0];
    tmp.sp_el1 = env->sp_el[1];
    tmp.esr_el1 = env->cp15.esr_el[1];
    tmp.far_el1 = env->cp15.far_el[1];
    tmp.cntkctl_el1 = env->cp15.c14_cntkctl;
    tmp.sysregs = (WinjitQemuSysregs) {
        .opaque = cpu,
        .read = winjit_qemu_sysreg_get,
        .write = winjit_qemu_sysreg_set,
    };
    *dest = tmp;
    return WINJIT_CPU_OK;
}


/*
 * Import only state that is fully modeled in WinjitA64State. This is a
 * research-only mechanism; no QEMU guest execution or -accel backend is
 * enabled by this function.
 */
int winjit_qemu_restore_arm64(ARMCPU *cpu, const WinjitA64State *src)
{
    if (!cpu || !src || src->abi_version != WINJIT_CPU_ABI_VERSION ||
        src->aarch64 != 1) {
        return WINJIT_CPU_INVALID;
    }
    const uint64_t flags = 0xf0000000ULL | 0x3c0ULL |
        (1ULL << 12) | (1ULL << 22) | (1ULL << 23) |
        (1ULL << 24) | (1ULL << 25) | 0xfULL;
    CPUARMState *env = &cpu->env;
    uint64_t old = pstate_read(env);
    const uint64_t mode = src->pstate & 0xf;
    const uint64_t prior_mode = old & 0xf;
    if (!env->aarch64 ||
        (prior_mode != 0 && prior_mode != 4 && prior_mode != 5) ||
        (mode != 0 && mode != 4 && mode != 5) ||
        (src->pstate & ~flags) != 0) {
        return WINJIT_CPU_UNSUPPORTED;
    }
    for (unsigned i = 0; i < 31; i++) {
        env->xregs[i] = src->x[i];
    }
    for (unsigned i = 0; i < 32; i++) {
        env->vfp.zregs[i].d[0] = src->v_lo[i];
        env->vfp.zregs[i].d[1] = src->v_hi[i];
    }
    env->xregs[31] = src->sp;
    env->pc = src->pc;
    pstate_write(env, src->pstate);
    vfp_set_fpcr(env, src->fpcr);
    vfp_set_fpsr(env, src->fpsr);
    env->cp15.tpidr_el[0] = src->tpidr_el0;
    env->cp15.tpidrro_el[0] = src->tpidrro_el0;
    env->cp15.tpidr_el[1] = src->tpidr_el1;
    env->cp15.sctlr_el[1] = src->sctlr_el1;
    env->cp15.ttbr0_el[1] = src->ttbr0_el1;
    env->cp15.ttbr1_el[1] = src->ttbr1_el1;
    env->cp15.tcr_el[1] = src->tcr_el1;
    env->cp15.mair_el[1] = src->mair_el1;
    env->cp15.vbar_el[1] = src->vbar_el1;
    env->cp15.contextidr_el[1] = src->contextidr_el1;
    env->cp15.cpacr_el1 = src->cpacr_el1;
    env->elr_el[1] = src->elr_el1;
    env->banked_spsr[BANK_SVC] = src->spsr_el1;
    env->sp_el[0] = src->sp_el0;
    env->sp_el[1] = src->sp_el1;
    env->cp15.esr_el[1] = src->esr_el1;
    env->cp15.far_el[1] = src->far_el1;
    env->cp15.c14_cntkctl = src->cntkctl_el1;
    arm_rebuild_hflags(env);
    return WINJIT_CPU_OK;
}

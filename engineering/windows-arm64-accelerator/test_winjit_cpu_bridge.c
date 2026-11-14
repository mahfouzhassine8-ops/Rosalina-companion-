/* SPDX-License-Identifier: GPL-2.0-or-later
 * Independent boundary tests using pinned WindowsiOS cpu.h.
 * A passing result is NOT evidence of QEMU/WinJIT guest execution.
 */
#include "winjit_cpu_bridge.h"
#include "cpu.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int model_read(void *opaque, uint32_t reg, uint64_t *value)
{
    if (reg == 0xc000) { /* MIDR */
        *value = *(uint64_t *)opaque;
        return 1;
    }
    return reg == 0xffff ? -1 : 0;
}

static int model_write(void *opaque, uint32_t reg, uint64_t value)
{
    if (reg != 0xdf10) { /* CNTP_TVAL */
        return -1;
    }
    *(uint64_t *)opaque = value;
    return 1;
}

static void assert_equivalent(const WinjitA64State *a, const WinjitA64State *b)
{
    assert(a->abi_version == b->abi_version);
    assert(a->aarch64 == b->aarch64);
    assert(memcmp(a->x, b->x, sizeof a->x) == 0);
    assert(memcmp(a->v_lo, b->v_lo, sizeof a->v_lo) == 0);
    assert(memcmp(a->v_hi, b->v_hi, sizeof a->v_hi) == 0);
#define CHECK(field) assert(a->field == b->field)
    CHECK(sp); CHECK(pc); CHECK(pstate); CHECK(fpcr); CHECK(fpsr);
    CHECK(tpidr_el0); CHECK(tpidrro_el0); CHECK(tpidr_el1);
    CHECK(sctlr_el1); CHECK(ttbr0_el1); CHECK(ttbr1_el1);
    CHECK(tcr_el1); CHECK(mair_el1); CHECK(vbar_el1);
    CHECK(contextidr_el1); CHECK(cpacr_el1); CHECK(elr_el1);
    CHECK(spsr_el1); CHECK(sp_el0); CHECK(sp_el1);
    CHECK(esr_el1); CHECK(far_el1); CHECK(cntkctl_el1);
#undef CHECK
}

int main(void)
{
    HvCpu *hv = calloc(1, sizeof(*hv));
    assert(hv != NULL);
    WinjitA64State a = {0}, b = {0};
    a.abi_version = WINJIT_CPU_ABI_VERSION;
    a.aarch64 = 1;
    a.pstate = 0xa00003c5ULL | (1ULL << 22);
    a.pc = 0x1234567890ULL;
    a.sp = 0x7ffffe00ULL;
    for (unsigned i = 0; i < 31; i++) a.x[i] = 0x1100000000000000ULL + i * 7;
    for (unsigned i = 0; i < 32; i++) {
        a.v_lo[i] = 0xf00d000000000000ULL + i;
        a.v_hi[i] = 0xcafe000000000000ULL + i * 3;
    }
    a.fpcr = 0x08000000ULL;
    a.fpsr = 0x10ULL;
    a.tpidr_el0 = 0x1070;
    a.tpidrro_el0 = 0x1234;
    a.tpidr_el1 = 0x3010;
    a.sctlr_el1 = 0x30d00800ULL;
    a.ttbr0_el1 = 0x451000ULL;
    a.ttbr1_el1 = 0xffff0000452000ULL;
    a.tcr_el1 = 0x80803520ULL;
    a.mair_el1 = 0xff4400;
    a.vbar_el1 = 0x701000;
    a.contextidr_el1 = 0xbeef;
    a.cpacr_el1 = 0x300000;
    a.elr_el1 = 0x80001000;
    a.spsr_el1 = 0x3c5;
    a.sp_el0 = 0x9000;
    a.sp_el1 = 0x200000;
    a.esr_el1 = 0xdead;
    a.far_el1 = 0x100000;
    a.cntkctl_el1 = 0x3;
    uint64_t model_value = 0x410fd034;
    a.sysregs = (WinjitQemuSysregs) {
        .opaque = &model_value, .read = model_read, .write = model_write,
    };
    hv->m = (struct HvMachine *)(uintptr_t)0x1234; /* preserve non-reg pointers */
    hv->pc_state = (struct HvPerCpu *)(uintptr_t)0x5678;
    assert(winjit_cpu_from_qemu_state(hv, &a) == WINJIT_CPU_OK);
    assert(hv->el == 1 && hv->spsel == 1);
    assert(hv->m == (struct HvMachine *)(uintptr_t)0x1234);
    assert(hv->pc_state == (struct HvPerCpu *)(uintptr_t)0x5678);
    assert(winjit_cpu_to_qemu_state(&b, hv) == WINJIT_CPU_OK);
    assert_equivalent(&a, &b);
    uint64_t value = 0;
    assert(winjit_qemu_sysreg_read(hv, 0xc000, &value) == 1);
    assert(value == 0x410fd034);
    assert(winjit_qemu_sysreg_read(hv, 0xc100, &value) == 0);
    assert(winjit_qemu_sysreg_read(hv, 0xffff, &value) == -1);
    assert(winjit_qemu_sysreg_write(hv, 0xdf10, 1234) == 1);
    assert(model_value == 1234);
    assert(winjit_qemu_sysreg_write(hv, 0xc000, 0) == -1);
    assert(b.sysregs.opaque == &model_value && b.sysregs.read == model_read);
    HvCpu *other = calloc(1, sizeof(*other));
    assert(other);
    assert(winjit_qemu_sysreg_read(other, 0xc000, &value) == 0);
    free(other);

    /* Both stack-select forms must round-trip exactly. */
    a.pstate = 0x300003c4ULL;
    assert(winjit_cpu_from_qemu_state(hv, &a) == WINJIT_CPU_OK);
    assert(winjit_cpu_to_qemu_state(&b, hv) == WINJIT_CPU_OK);
    assert_equivalent(&a, &b);

    /* A real Windows user-mode process requires EL0t. Verify roundtrip
     * with DAIF, vector registers and active user SP preserved. */
    a.pstate = 0x900003c0ULL;
    a.sp = 0x00007ffdead000ULL;
    a.sp_el0 = a.sp;
    assert(winjit_cpu_from_qemu_state(hv, &a) == WINJIT_CPU_OK);
    assert(hv->el == 0 && hv->spsel == 0);
    assert(hv->sp == a.sp && hv->sp_el0 == a.sp_el0);
    assert(winjit_cpu_to_qemu_state(&b, hv) == WINJIT_CPU_OK);
    assert_equivalent(&a, &b);

    /* Reject EL0h, EL2 and EL3, non-AArch64, unmodeled flags and
     * ABI mismatches rather than corrupting caller's CPU state. */
    const uint64_t bad_modes[] = {0x1ULL, 0x8ULL, 0x9ULL, 0xcULL, 0xdULL};
    for (size_t i = 0; i < sizeof bad_modes / sizeof bad_modes[0]; i++) {
        a.pstate = 0x3c0ULL | bad_modes[i];
        assert(winjit_cpu_from_qemu_state(hv, &a) == WINJIT_CPU_UNSUPPORTED);
    }
    a.pstate = 0x3c5ULL | (1ULL << 63);
    assert(winjit_cpu_from_qemu_state(hv, &a) == WINJIT_CPU_UNSUPPORTED);
    a.pstate = 0x3c5ULL;
    a.aarch64 = 0;
    assert(winjit_cpu_from_qemu_state(hv, &a) == WINJIT_CPU_UNSUPPORTED);
    a.aarch64 = 1;
    a.abi_version = 99;
    assert(winjit_cpu_from_qemu_state(hv, &a) == WINJIT_CPU_INVALID);
    assert(winjit_cpu_from_qemu_state(NULL, &a) == WINJIT_CPU_INVALID);
    assert(winjit_cpu_to_qemu_state(NULL, hv) == WINJIT_CPU_INVALID);
    hv->el = 2;
    assert(winjit_cpu_to_qemu_state(&b, hv) == WINJIT_CPU_UNSUPPORTED);
    hv->el = 0;
    hv->spsel = 1;
    assert(winjit_cpu_to_qemu_state(&b, hv) == WINJIT_CPU_UNSUPPORTED);
    free(hv);
    puts("PASS: WinJIT AArch64 EL0/EL1 CPU/vector roundtrip and invalid mode rejection");
    return 0;
}

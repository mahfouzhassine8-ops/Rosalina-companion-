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
    hv->m = (struct HvMachine *)(uintptr_t)0x1234; /* preserve non-reg pointers */
    hv->pc_state = (struct HvPerCpu *)(uintptr_t)0x5678;
    assert(winjit_cpu_from_qemu_state(hv, &a) == WINJIT_CPU_OK);
    assert(hv->el == 1 && hv->spsel == 1);
    assert(hv->m == (struct HvMachine *)(uintptr_t)0x1234);
    assert(hv->pc_state == (struct HvPerCpu *)(uintptr_t)0x5678);
    assert(winjit_cpu_to_qemu_state(&b, hv) == WINJIT_CPU_OK);
    assert_equivalent(&a, &b);

    /* Both stack-select forms must round-trip exactly. */
    a.pstate = 0x300003c4ULL;
    assert(winjit_cpu_from_qemu_state(hv, &a) == WINJIT_CPU_OK);
    assert(winjit_cpu_to_qemu_state(&b, hv) == WINJIT_CPU_OK);
    assert_equivalent(&a, &b);

    /* Reject EL0, EL2 and EL3, non-AArch64 state, unmodeled flags and
     * ABI mismatches rather than corrupting the caller's CPU state. */
    const uint64_t bad_modes[] = {0x0ULL, 0x8ULL, 0x9ULL, 0xcULL, 0xdULL};
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
    free(hv);
    puts("PASS: WinJIT EL1 CPU register/vector state roundtrip and rejected unsafe modes");
    return 0;
}

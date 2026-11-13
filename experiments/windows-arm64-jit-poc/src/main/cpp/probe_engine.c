/* SPDX-License-Identifier: GPL-2.0-or-later
 * Source-level ARM64 decoder and Android executable-memory probe.
 * Vendors the exact WindowsiOS classifier/operand logic, NOT an interpreter.
 */
#include "probe_engine.h"
#include "android_jit_region.h"
#include "classify.h"
#include "operands.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/utsname.h>
#include <time.h>

typedef struct { uint32_t instruction; HvInsnKind kind; } DecoderCase;
static const DecoderCase cases[] = {
    {0xd503201fU, HV_INSN_COPY},       /* nop */
    {0xd2800540U, HV_INSN_COPY},       /* mov x0,#42 */
    {0x14000000U, HV_INSN_BRANCH},     /* b . */
    {0xd65f03c0U, HV_INSN_BRANCH_REG}, /* ret */
    {0xd4000001U, HV_INSN_EXCEPTION},  /* svc #0 */
    {0xf9400020U, HV_INSN_MEMORY},     /* ldr x0,[x1] */
    {0x91000420U, HV_INSN_COPY},       /* add x0,x1,#1 */
};

static double milliseconds(void) {
    struct timespec ts={0};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec*1000.0 + (double)ts.tv_nsec*1.0e-6;
}

int probe_engine_run(char *out, size_t cap) {
    if (!out || cap < 100) return -1;
    int failed=0;
    for (unsigned i=0;i<sizeof(cases)/sizeof(cases[0]);i++) {
        if (hv_classify(cases[i].instruction) != cases[i].kind) failed++;
    }
    /* Upstream's operand logic must identify Rn and Rd on ADD. */
    uint32_t fields = hv_gpr_fields(0x91000420U);
    if (fields != (HV_FIELD_RD | HV_FIELD_RN | HV_FIELD_RN_SP | HV_FIELD_RD_SP)) failed++;

    volatile unsigned checksum=0;
    double t0=milliseconds();
    for (unsigned i=0;i<150000;i++) {
        checksum+= (unsigned)hv_classify(cases[i%7].instruction);
        checksum+= hv_gpr_fields(0x91000420U);
    }
    double elapsed=milliseconds()-t0;
    JitProbe jit=android_jit_probe();
    struct utsname uts={0};
    if (uname(&uts)) strncpy(uts.machine,"unknown",sizeof uts.machine-1);
    snprintf(out,cap,
        "Windows ARM64 JIT — isolated Android proof\n"
        "Architecture: %s\n"
        "WindowsiOS decoder/operand checks: %s (%d failures, %zu cases)\n"
        "150,000 decode loops: %.2f ms (checksum %u)\n"
        "AArch64 JIT stub returned: %d (expected 42)\n"
        "Executable memory permitted: %s\n"
        "Memory mode: %s\n"
        "Memory errno: %d (%s)\n"
        "Scope: no Windows VM, disk, UEFI, KVM, graphics, or emulator\n"
        "LIMIT: does not prove the complete WindowsiOS translator or Windows boot works.\n",
        uts.machine,failed==0?"PASS":"FAIL",failed,
        sizeof(cases)/sizeof(cases[0])+1,elapsed,checksum,
        jit.code_return,jit.permitted?"YES":"NO",jit.mode,
        jit.errnum, jit.errnum?strerror(jit.errnum):"none");
    /* A failed JIT capability test is returned as a failure but never crashes
       the user-visible app; output states the blocker and errno. */
    return failed==0 && jit.permitted && jit.code_return==42 ? 0 : 1;
}

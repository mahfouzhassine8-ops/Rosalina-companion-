/* SPDX-License-Identifier: GPL-2.0-or-later
 * ARM64 native WinJIT execution test. Runs a tiny synthetic AArch64 program
 * through the ACTUAL translator, assembler entrypoint and W^X code cache.
 * No QEMU, Windows guest image, installation, UEFI VARS or APK involved.
 */
#include "cpu.h"
#include "dispatch.h"
#include "machine.h"
#include "winjit_phys_bus.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int read_mmio(void *opaque, uint64_t phys, uint8_t *dest, unsigned size)
{
    uint64_t *count = opaque;
    if (phys != 0x30000000ULL || size != 8) return -1;
    ++*count;
    const uint64_t value = 0x1122334455667788ULL;
    memcpy(dest, &value, size);
    return 0;
}

static int write_mmio(void *opaque, uint64_t phys, const uint8_t *src, unsigned size)
{
    (void)opaque; (void)phys; (void)src; (void)size;
    return -1; /* Test has no write. Fail closed if one occurs. */
}

int main(void)
{
#ifndef __aarch64__
#error "This test requires native ARM64 guest execution"
#endif
    /* 16 KB code/data; 4 MB executable cache. */
    HvMachine *m = calloc(1, sizeof(*m));
    HvCpu *cpu = calloc(1, sizeof(*cpu));
    uint8_t *ram = calloc(1, 16384);
    if (!m || !cpu || !ram) return 10;
    if (hv_machine_init(m, 4 * 1024 * 1024) != 0) {
        fprintf(stderr, "FAIL: Android-style Linux W^X JIT region unavailable\n");
        return 11;
    }
    if (hv_machine_add_ram(m, 0, 16384, ram, 0) != 0) return 12;

    uint64_t reads = 0;
    WinjitPhysBus bus = {
        .opaque = &reads,
        .read = read_mmio,
        .write = write_mmio,
    };
    if (winjit_bus_install(m, &bus, 0x30000000ULL, 4096) != 0) return 13;

    /* AArch64:
     *   movz x0, #42
     *   add  x0, x0, #1
     *   ldr  x2, [x1]      // physical MMIO, MMU disabled
     *   b    .+4          // return to host stop_pc after the branch
     */
    const uint32_t prog[] = {
        0xd2800540U, 0x91000400U, 0xf9400022U, 0x14000001U,
    };
    memcpy(ram + 0x1000, prog, sizeof prog);
    hv_cpu_reset(cpu, m);
    cpu->pc = 0x1000;
    cpu->x[1] = 0x30000000ULL;
    m->stop_pc = 0x1010;
    int code = hv_run(cpu);
    if (code != HV_EXIT_HALT || cpu->pc != 0x1010 ||
        cpu->x[0] != 43 ||
        cpu->x[2] != 0x1122334455667788ULL ||
        reads != 1 ||
        winjit_bus_failures(&bus) != 0) {
        fprintf(stderr, "FAIL: translated guest code=%d pc=%llx x0=%llx x2=%llx mmio=%llu failures=%u\n",
                code, (unsigned long long)cpu->pc, (unsigned long long)cpu->x[0],
                (unsigned long long)cpu->x[2], (unsigned long long)reads,
                winjit_bus_failures(&bus));
        return 14;
    }

    puts("PASS: REAL WinJIT AArch64 guest instructions executed through W^X translation and MMIO callback");
    hv_machine_free(m);
    free(ram);
    free(cpu);
    free(m);
    return 0;
}

/* SPDX-License-Identifier: GPL-2.0-or-later
 * Unit-only physical bus callback tests. No guest or QEMU vCPU execution.
 */
#include "winjit_phys_bus.h"
#include "machine.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct FakeBus {
    uint64_t last_address;
    uint8_t last_data[8];
    unsigned last_len, nread, nwrite;
    int reject;
} FakeBus;

/* Minimal substitute for the WinJIT machine registration function. The NDK
 * shared library links the real core/machine.c implementation instead. */
int hv_machine_add_mmio(HvMachine *m, uint64_t pa, uint64_t size, void *opaque,
                        uint64_t (*read)(void *, uint64_t, unsigned),
                        void (*write)(void *, uint64_t, uint64_t, unsigned),
                        const char *name)
{
    if (m->nmmio == HV_MAX_MMIO) return -1;
    m->mmio[m->nmmio++] = (HvMmio){pa, size, opaque, read, write, name, 0};
    return 0;
}

static int fake_read(void *ctx, uint64_t pa, uint8_t *dst, unsigned len)
{
    FakeBus *s = ctx;
    s->nread++;
    s->last_address = pa;
    s->last_len = len;
    if (s->reject) return -1;
    for (unsigned i = 0; i < len; ++i) dst[i] = (uint8_t)(pa + i);
    return 0;
}

static int fake_write(void *ctx, uint64_t pa, const uint8_t *src, unsigned len)
{
    FakeBus *s = ctx;
    s->nwrite++;
    s->last_address = pa;
    s->last_len = len;
    if (s->reject) return -1;
    memcpy(s->last_data, src, len);
    return 0;
}

int main(void)
{
    HvMachine *machine = calloc(1, sizeof(*machine));
    assert(machine);
    FakeBus io = {0};
    WinjitPhysBus bus = {.opaque=&io, .read=fake_read, .write=fake_write};
    const uint64_t base = 0x10000000ULL;
    assert(winjit_bus_install(machine, &bus, base, 0x1000) == 0);
    assert(machine->nmmio == 1);
    assert(machine->mmio[0].pa == base);
    assert(machine->mmio[0].size == 0x1000);
    assert(machine->mmio[0].opaque == &bus);
    assert(winjit_bus_failures(&bus) == 0);
    uint64_t readback = machine->mmio[0].read(&bus, 0x34, 4);
    assert(readback == 0x37363534ULL);
    assert(io.nread == 1 && io.last_address == base + 0x34);
    machine->mmio[0].write(&bus, 0x88, 0x1122334455667788ULL, 8);
    assert(io.nwrite == 1 && io.last_len == 8);
    const uint8_t expected[8] = {0x88,0x77,0x66,0x55,0x44,0x33,0x22,0x11};
    assert(memcmp(io.last_data, expected, 8) == 0);
    assert(io.last_address == base + 0x88);
    assert(winjit_bus_mmio_read(&bus, 0xfff, 2) == UINT64_MAX);
    assert(winjit_bus_failures(&bus) == 1);
    winjit_bus_mmio_write(&bus, 0x10, 0x11, 3);
    assert(winjit_bus_failures(&bus) == 2);
    io.reject = 1;
    assert(winjit_bus_mmio_read(&bus, 0x40, 1) == UINT64_MAX);
    assert(winjit_bus_failures(&bus) == 3);
    winjit_bus_mmio_write(&bus, 0x20, 0x77, 1);
    assert(winjit_bus_failures(&bus) == 4);
    assert(winjit_bus_install(machine, &bus, UINT64_MAX-4, 8) != 0);
    assert(winjit_bus_install(NULL, &bus, 0, 0x1000) != 0);
    free(machine);
    puts("PASS: WinJIT QEMU MMIO reads/writes, endian, bounds and sticky errors");
    return 0;
}

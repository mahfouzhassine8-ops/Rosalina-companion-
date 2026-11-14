/* SPDX-License-Identifier: GPL-2.0-or-later
 * Physical MMIO callback boundary between WinJIT and QEMU AddressSpace.
 * This interface is used by WinJIT's slow-path physical access helpers.
 * QEMU address_space_read/write callbacks are injected by the future adapter.
 */
#ifndef WINJIT_PHYS_BUS_H
#define WINJIT_PHYS_BUS_H
#include <stdint.h>
#include <stdatomic.h>

struct HvMachine;

typedef int (*WinjitBusRead)(void *opaque, uint64_t physical_address,
                             uint8_t *dst, unsigned bytes);
typedef int (*WinjitBusWrite)(void *opaque, uint64_t physical_address,
                              const uint8_t *src, unsigned bytes);

typedef struct WinjitPhysBus {
    void *opaque;
    WinjitBusRead read;
    WinjitBusWrite write;
    uint64_t base;
    uint64_t size;
    atomic_uint failure_count; /* Sticky until caller inspects: fail closed. */
} WinjitPhysBus;

/* Preinitialized with callbacks; one catch-all MMIO range outside RAM.
 * Does not modify the VM's RAM, disk or UEFI firmware and cannot start vCPUs. */
int winjit_bus_install(struct HvMachine *m, WinjitPhysBus *bus,
                       uint64_t physical_base, uint64_t length);
uint64_t winjit_bus_mmio_read(void *opaque, uint64_t offset, unsigned bytes);
void winjit_bus_mmio_write(void *opaque, uint64_t offset,
                           uint64_t value, unsigned bytes);
unsigned winjit_bus_failures(const WinjitPhysBus *bus);

#endif

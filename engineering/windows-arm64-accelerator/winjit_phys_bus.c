/* SPDX-License-Identifier: GPL-2.0-or-later
 * QEMU bus I/O callbacks for WinJIT software-MMU physical access slow path.
 * Errors are recorded for the owning vCPU to convert into a fatal exit.
 */
#include "winjit_phys_bus.h"
#include "machine.h"
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "AArch64 little-endian WinJIT MMIO bridge only"
#endif

static int bus_range_ok(const WinjitPhysBus *bus, uint64_t off, unsigned size)
{
    if (!bus || (size != 1 && size != 2 && size != 4 && size != 8) ||
        !bus->read || !bus->write || off >= bus->size ||
        size > bus->size - off || bus->base > UINT64_MAX - off) {
        return 0;
    }
    return 1;
}

static void bus_failure(WinjitPhysBus *bus)
{
    if (bus) {
        atomic_fetch_add_explicit(&bus->failure_count, 1, memory_order_relaxed);
    }
}

int winjit_bus_install(struct HvMachine *machine, WinjitPhysBus *bus,
                       uint64_t physical_base, uint64_t length)
{
    if (!machine || !bus || !bus->read || !bus->write || !length ||
        physical_base > UINT64_MAX - length) {
        return -1;
    }
    /* Call only before guest vCPUs run. RAM ranges take precedence in
     * hv_phys_read/write; the catch-all MMIO region delegates to QEMU. */
    bus->base = physical_base;
    bus->size = length;
    atomic_store_explicit(&bus->failure_count, 0, memory_order_relaxed);
    if (hv_machine_add_mmio(machine, physical_base, length, bus,
                            winjit_bus_mmio_read, winjit_bus_mmio_write,
                            "qemu-address-space") != 0) {
        bus->base = bus->size = 0;
        return -1;
    }
    return 0;
}

uint64_t winjit_bus_mmio_read(void *opaque, uint64_t off, unsigned size)
{
    WinjitPhysBus *bus = opaque;
    if (!bus_range_ok(bus, off, size)) {
        bus_failure(bus);
        return UINT64_MAX;
    }
    uint8_t data[8] = {0};
    if (bus->read(bus->opaque, bus->base + off, data, size) != 0) {
        bus_failure(bus);
        return UINT64_MAX;
    }
    uint64_t value = 0;
    memcpy(&value, data, size);
    return value;
}

void winjit_bus_mmio_write(void *opaque, uint64_t off,
                           uint64_t value, unsigned size)
{
    WinjitPhysBus *bus = opaque;
    if (!bus_range_ok(bus, off, size)) {
        bus_failure(bus);
        return;
    }
    uint8_t data[8];
    memcpy(data, &value, size);
    if (bus->write(bus->opaque, bus->base + off, data, size) != 0) {
        bus_failure(bus);
    }
}

unsigned winjit_bus_failures(const WinjitPhysBus *bus)
{
    return bus ? atomic_load_explicit(&bus->failure_count,
                                       memory_order_relaxed) : 1;
}

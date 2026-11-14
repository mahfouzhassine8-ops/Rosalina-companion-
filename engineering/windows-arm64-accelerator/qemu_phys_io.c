/* SPDX-License-Identifier: GPL-2.0-or-later
 * QEMU 11.1.3 AddressSpace callback bindings for WinJIT's MMIO bus.
 * The bus itself (winjit_phys_bus.c) tracks sticky I/O failures.
 * This source is compiled into QEMU only on research builds; no accelerator
 * is registered, no vCPU is started, and Windows disks are untouched.
 */
#include "qemu/osdep.h"
#include "system/address-spaces.h"
#include "system/memory.h"
#include "exec/memattrs.h"

int winjit_qemu_mmio_read(void *opaque, uint64_t physical_address,
                          uint8_t *dst, unsigned bytes)
{
    AddressSpace *as = opaque;
    if (!as || !dst || (bytes != 1 && bytes != 2 &&
                       bytes != 4 && bytes != 8)) {
        return -1;
    }
    MemTxResult status = address_space_read(as, (hwaddr)physical_address,
                                            MEMTXATTRS_UNSPECIFIED,
                                            dst, bytes);
    return status == MEMTX_OK ? 0 : -1;
}

int winjit_qemu_mmio_write(void *opaque, uint64_t physical_address,
                           const uint8_t *src, unsigned bytes)
{
    AddressSpace *as = opaque;
    if (!as || !src || (bytes != 1 && bytes != 2 &&
                       bytes != 4 && bytes != 8)) {
        return -1;
    }
    MemTxResult status = address_space_write(as, (hwaddr)physical_address,
                                             MEMTXATTRS_UNSPECIFIED,
                                             src, bytes);
    return status == MEMTX_OK ? 0 : -1;
}

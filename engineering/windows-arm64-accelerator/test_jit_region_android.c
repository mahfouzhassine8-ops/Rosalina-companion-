/* SPDX-License-Identifier: GPL-2.0-or-later
 * Host-only deterministic W^X JIT-cache smoke test; not guest boot proof.
 */
#include "jit_region.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    HvJitRegion region = {0};
    assert(hv_jit_region_open(&region, 4096) == 0);
    assert(region.base && region.rw_diff && region.capacity == 1024);
    uint32_t *code = hv_jit_reserve(&region, 3);
    assert(code == region.base && region.used == 4);
    const uint32_t op[] = {0xd503201f, 0xd65f03c0, 0x14000000};
    hv_jit_write(&region, code, op, sizeof(op));
    hv_jit_finish(&region, code, 3);
    /* Upstream translate.c legitimately calls hv_jit_write with zero
     * pool size. This must not abort or modify existing guest code. */
    hv_jit_write(&region, code, NULL, 0);
    hv_jit_write(&region, code + region.capacity, NULL, 0);
    assert(memcmp(code, op, sizeof(op)) == 0);
    uint32_t patch = 0xd503201f;
    hv_jit_write(&region, code + 1, &patch, sizeof(patch));
    hv_jit_finish(&region, code + 1, 1);
    assert(code[1] == patch);
    region.limit = 8;
    assert(hv_jit_reserve(&region, 5) == NULL);
    assert(region.used == 4);
    assert(hv_jit_reserve(&region, 4) == code + 4);
    assert(hv_jit_reserve(&region, 1) == NULL);
    hv_jit_reset(&region);
    assert(hv_jit_reserve(&region, 1) == region.base);
    hv_jit_region_close(&region);
    assert(region.base == NULL && region.capacity == 0);
    puts("PASS: split RW/RX JIT, cache flush, patch, bounded reserve, and reset");
    return 0;
}

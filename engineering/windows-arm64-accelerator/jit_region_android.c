/* SPDX-License-Identifier: GPL-2.0-or-later
 * Android/Linux W^X host for pinned WindowsiOS ARM64 translator.
 * Research component only: not yet integrated into QEMU, no Windows boot claim.
 * Never requests a writable+executable page, root, KVM or SELinux bypass.
 * Android device/SELinux executable-memfd policy is an UNVERIFIED runtime gate.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "jit_region.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

/* QEMU and the translator need two distinct aliases of one physical object.
 * No temporary PROT_WRITE|PROT_EXEC mapping is ever used. */
int hv_jit_region_open(HvJitRegion *region, size_t bytes) {
    if (!region) return -1;
    memset(region, 0, sizeof(*region));
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0 || bytes < 4096 || bytes % (size_t)page != 0 ||
        bytes > (size_t)INTPTR_MAX || bytes > (size_t)INT64_MAX) return -1;
#ifndef __NR_memfd_create
    return -1; /* Fail closed on a platform without memfd, never RWX. */
#else
    int fd = (int)syscall(__NR_memfd_create, "winjit-guest-code", MFD_CLOEXEC);
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)bytes) != 0) {
        close(fd);
        return -1;
    }
    void *rw = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (rw == MAP_FAILED) {
        close(fd);
        return -1;
    }
    void *rx = mmap(NULL, bytes, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0);
    close(fd);
    if (rx == MAP_FAILED) {
        munmap(rw, bytes);
        return -1;
    }
    if (hv_jit_region_attach(region, rx, rw, bytes) != 0) {
        munmap(rx, bytes);
        munmap(rw, bytes);
        return -1;
    }
    region->owned = 1;
    return 0;
#endif
}

int hv_jit_region_attach(HvJitRegion *region, void *rx, void *rw, size_t bytes) {
    if (!region || !rx || !rw || rx == rw || bytes < 4096 ||
        bytes % sizeof(uint32_t) != 0) return -1;
    memset(region, 0, sizeof(*region));
    region->base = (uint32_t *)rx;
    region->rw_diff = (intptr_t)rw - (intptr_t)rx;
    region->capacity = bytes / sizeof(uint32_t);
    return 0;
}

void hv_jit_region_close(HvJitRegion *region) {
    if (!region) return;
    if (region->owned && region->base) {
        size_t bytes = region->capacity * sizeof(uint32_t);
        munmap((void *)((uintptr_t)region->base + region->rw_diff), bytes);
        munmap(region->base, bytes);
    }
    memset(region, 0, sizeof(*region));
}

static void require_span(const HvJitRegion *region, uint32_t *start, size_t bytes) {
    if (!region || !region->base || !region->rw_diff || !start ||
        bytes > region->capacity * sizeof(uint32_t)) abort();
    uintptr_t begin = (uintptr_t)region->base, pos = (uintptr_t)start;
    size_t size = region->capacity * sizeof(uint32_t);
    if (pos < begin || pos - begin > size || bytes > size - (pos - begin)) abort();
}

void hv_jit_write(HvJitRegion *region, uint32_t *start, const void *src, size_t bytes) {
    require_span(region, start, bytes);
    /* The upstream translator may emit no literal-pool or cold words.
     * Zero-byte writes are valid no-ops, not a fatal JIT violation.
     * Preserve strict bounds and non-null source checks for real writes. */
    if (bytes == 0) return;
    if (!src) abort();
    uint8_t *rw = (uint8_t *)((uintptr_t)start + region->rw_diff);
    if (bytes == sizeof(uint32_t)) {
        if ((uintptr_t)start % sizeof(uint32_t)) abort();
        uint32_t word;
        memcpy(&word, src, sizeof(word));
        __atomic_store_n((uint32_t *)rw, word, __ATOMIC_RELEASE);
    } else {
        /* Caller guarantees code is not running until finish and publication. */
        memcpy(rw, src, bytes);
    }
}

void hv_jit_finish(HvJitRegion *region, uint32_t *start, size_t words) {
    if (!words) return;
    if (words > SIZE_MAX / sizeof(uint32_t)) abort();
    size_t bytes = words * sizeof(uint32_t);
    require_span(region, start, bytes);
    char *rx = (char *)start;
    char *rw = (char *)((uintptr_t)start + region->rw_diff);
    /* Flush the writable alias, then invalidate instruction cache at RX alias. */
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    __builtin___clear_cache(rw, rw + bytes);
    __builtin___clear_cache(rx, rx + bytes);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
}

uint32_t *hv_jit_reserve(HvJitRegion *region, size_t words) {
    if (!region || !region->base || !words || words > SIZE_MAX - 1) return NULL;
    words = (words + 1) & ~(size_t)1;
    size_t cap = region->limit && region->limit < region->capacity ?
                 region->limit : region->capacity;
    size_t at = __atomic_load_n(&region->used, __ATOMIC_RELAXED);
    for (;;) {
        if (at > cap || words > cap - at) return NULL;
        if (__atomic_compare_exchange_n(&region->used, &at, at + words, 0,
                                        __ATOMIC_ACQ_REL, __ATOMIC_RELAXED))
            return region->base + at;
    }
}

void hv_jit_reset(HvJitRegion *region) {
    if (!region) return;
    /* Only when every translation vCPU is quiescent (enforced by future bridge). */
    __atomic_store_n(&region->used, 0, __ATOMIC_RELEASE);
}

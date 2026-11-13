/* SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Minimal Android/Linux host replacement for the Mach vm_remap/MAP_JIT step.
 * Execute ONLY a fixed, checked two-instruction sequence: mov x0,#42; ret.
 * No Windows disk access, device emulation, arbitrary guest code, or RWX VMA.
 * Returned errno and mode make SELinux / seccomp limitations visible.
 */
#define _GNU_SOURCE
#include "android_jit_region.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_EXEC
#define MFD_EXEC 0x0010U
#endif
#ifndef __NR_memfd_create
#if defined(__aarch64__)
#define __NR_memfd_create 279
#elif defined(__x86_64__)
#define __NR_memfd_create 319
#endif
#endif

static void write_guest_stub(void *writable, void *executable) {
    /* mov x0,#42; ret (AArch64, aligned and deterministic). */
    static const uint32_t code[] = {0xd2800540U, 0xd65f03c0U};
    memcpy(writable, code, sizeof code);
    /* First clean D-cache through the RW mapping, then invalidate the
     * instruction-side mapping. This is necessary with a dual alias. */
    __builtin___clear_cache((char *)writable, (char *)writable + sizeof code);
    __sync_synchronize();
    __builtin___clear_cache((char *)executable, (char *)executable + sizeof code);
}

static int call_guest_stub(void *rx) {
#if defined(__aarch64__)
    long (*stub)(void) = (long (*)(void))rx;
    return (int)stub();
#else
    (void)rx;
    return -1;
#endif
}

JitProbe android_jit_probe(void) {
    JitProbe out = {0, -1, 0, "none"};
#if !defined(__aarch64__)
    out.errnum = ENOTSUP;
    strcpy(out.mode, "AArch64 only");
    return out;
#else
    const size_t bytes = (size_t)sysconf(_SC_PAGESIZE);
    int fd = -1;
#if defined(__NR_memfd_create)
    /* Some recent kernels require MFD_EXEC for executable memfd mappings;
       older kernels reject it with EINVAL, so retry without that flag. */
    fd = (int)syscall(__NR_memfd_create, "windows_android_jit_poc",
                      MFD_CLOEXEC | MFD_EXEC);
    if (fd < 0 && errno == EINVAL) {
        fd = (int)syscall(__NR_memfd_create, "windows_android_jit_poc",
                          MFD_CLOEXEC);
    }
#endif
    int memfd_failure = fd < 0 ? errno : 0;
    if (fd >= 0 && ftruncate(fd, (off_t)bytes) == 0) {
        /* Per-VMA W^X: never request a RWX mapping. RW and RX aliases
           reference the same underlying fd, as on the iOS port. */
        void *rw = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (rw != MAP_FAILED) {
            void *rx = mmap(NULL, bytes, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0);
            if (rx != MAP_FAILED) {
                write_guest_stub(rw, rx);
                out.code_return = call_guest_stub(rx);
                out.permitted = out.code_return == 42;
                strcpy(out.mode, "memfd: RX+RW alias");
                munmap(rx, bytes);
            } else {
                out.errnum = errno;
            }
            munmap(rw, bytes);
        } else {
            out.errnum = errno;
        }
    } else if (fd >= 0) {
        out.errnum = errno;
    }
    if (fd >= 0) close(fd);
    if (out.permitted) return out;

    /* Compatibility probe: RW anonymous allocation -> RX using mprotect,
       with NO simultaneously writable/executable page. */
    int previous_error = out.errnum ? out.errnum : memfd_failure;
    void *buf = mmap(NULL, bytes, PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (buf == MAP_FAILED) {
        out.errnum = errno;
        strcpy(out.mode, "anonymous mmap blocked");
        return out;
    }
    static const uint32_t code[] = {0xd2800540U, 0xd65f03c0U};
    memcpy(buf, code, sizeof code);
    __builtin___clear_cache((char *)buf, (char *)buf + sizeof code);
    if (mprotect(buf, bytes, PROT_READ | PROT_EXEC) == 0) {
        __builtin___clear_cache((char *)buf, (char *)buf + sizeof code);
        out.code_return = call_guest_stub(buf);
        out.permitted = out.code_return == 42;
        out.errnum = 0;
        strcpy(out.mode, "anonymous RW -> RX (W^X)");
    } else {
        out.errnum = errno;
        snprintf(out.mode, sizeof out.mode, "mprotect(RX) blocked; memfd errno=%d",
                 previous_error);
    }
    munmap(buf, bytes);
    return out;
#endif
}

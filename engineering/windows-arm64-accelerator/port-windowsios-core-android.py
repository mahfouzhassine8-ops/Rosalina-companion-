#!/usr/bin/env python3
"""Port pinned WindowsiOS ARM64 core to Android ABI for native compile gating.

Only edits a disposable checkout passed on the command line.
Does NOT implement a QEMU accelerator, start any guest, or touch an APK/disk.
Source origin: ammaarreshi/WindowsiOS pinned 0071d6975287fd720b9046dfd3bd80b6f2aa765e.
"""
from pathlib import Path
import re
import sys

def patch(f, old, new, count=1):
    p=Path(f)
    text=p.read_text()
    found=text.count(old)
    if found!=count:
        raise RuntimeError(f"Unexpected pinned source in {p}: {old[:90]!r} found {found}; expected {count}")
    p.write_text(text.replace(old,new))
    print(f"PORT {p.name}: {found} precise source anchor(s)")

root=Path(sys.argv[1] if len(sys.argv)>1 else "/tmp/windowsios-android-src")
core=root/"core"
entry=root/"host/enter.S"
assert (core/"cpu.h").is_file() and entry.is_file(), "Pinned WindowsiOS source checkout missing"

clock=core/"android_hv_clock.h"
assert not clock.exists()
clock.write_text(r'''/* SPDX-License-Identifier: GPL-2.0-or-later
 * Android/Linux adaptation of WindowsiOS's Apple 24 MHz guest timebase.
 * CLOCK_MONOTONIC is used only for a provisional portable 24 MHz count.
 * Correctness versus Windows timer hardware has NOT yet been verified.
 * This is a build/port gate, not an approved runtime implementation.
 */
#ifndef ANDROID_HV_CLOCK_H
#define ANDROID_HV_CLOCK_H
#define _POSIX_C_SOURCE 200809L
#include <stdint.h>
#include <time.h>
static inline uint64_t android_hv_counter_ticks(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t) != 0) return 0;
    return (uint64_t)t.tv_sec * 24000000ULL
         + (uint64_t)t.tv_nsec * 24ULL / 1000ULL;
}
#endif
''')
for name in ("dispatch.c","machine.c","sysreg.c"):
    patch(core/name, "#include <mach/mach_time.h>", '#include "android_hv_clock.h"')
for name in ("dispatch.c","machine.c","sysreg.c"):
    f=core/name
    body=f.read_text()
    count=body.count("mach_absolute_time()")
    if count <= 0: raise RuntimeError(f"Missing timebase references in {f}")
    f.write_text(body.replace("mach_absolute_time()", "android_hv_counter_ticks()"))
    print(f"PORT {name}: Android monotonic->24MHz conversion at {count} call sites")

patch(core/"dispatch.c", "#include <mach/mach.h>", "#include <time.h>")
patch(core/"machine.h", "#include <TargetConditionals.h>", "#define TARGET_OS_IPHONE 0")

stat_old='''        thread_basic_info_data_t ti;
        mach_msg_type_number_t cnt = THREAD_BASIC_INFO_COUNT;
        mach_port_t self = mach_thread_self();
        if (thread_info(self, THREAD_BASIC_INFO, (thread_info_t)&ti, &cnt) == KERN_SUCCESS) {
            double busy = ti.user_time.seconds + ti.user_time.microseconds / 1e6 + ti.system_time.seconds +
                          ti.system_time.microseconds / 1e6;'''
stat_new='''        struct timespec ti;
        if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ti) == 0) {
            double busy = (double)ti.tv_sec + (double)ti.tv_nsec / 1e9;'''
patch(core/"dispatch.c",stat_old,stat_new)
patch(core/"dispatch.c", "        mach_port_deallocate(mach_task_self(), self);\n", "")
patch(core/"dispatch.c",
      '''        struct timespec ts = {(time_t)(wait_ticks / 24000000), (long)(wait_ticks % 24000000) * 1000 / 24};
        pthread_cond_timedwait_relative_np(&p->wake_cv, &p->wake_mu, &ts);''',
      '''        /* Bionic/POSIX condvars use absolute CLOCK_REALTIME by default.
           Preserve the guest's relative 24 MHz wait without Apple's _np API. */
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        uint64_t ns = wait_ticks * 1000ULL / 24ULL;
        ts.tv_sec += (time_t)(ns / 1000000000ULL);
        ts.tv_nsec += (long)(ns % 1000000000ULL);
        if (ts.tv_nsec >= 1000000000L) {
            ts.tv_sec++;
            ts.tv_nsec -= 1000000000L;
        }
        pthread_cond_timedwait(&p->wake_cv, &p->wake_mu, &ts);''')

# CI-only diagnostic of the very first Linux MMU control-register writes.
# The Linux boot gate enables WINJIT_TRACE_SYSREG; ordinary execution does not
# emit guest register values. The bounded trace is inserted only into the pinned,
# disposable upstream checkout. Do not reinterpret bad writes as successful boot.
sysreg_msr_anchor = """    uint32_t reg = (insn >> 5) & 0xffff;
    int rt = insn & 0x1f;
    if (cpu->m->log >= 3)
        fprintf(stderr, "msr S%u_%u_C%u_C%u_%u = %llx pc %llx el%u\\n", """
sysreg_msr_probe = r"""    uint32_t reg = (insn >> 5) & 0xffff;
    int rt = insn & 0x1f;
    /* A kernel MMU write currently receives host-stack-like values.
     * Capture the source *before* the helper writes it to identify
     * register corruption, not merely the later translation fault.
     * This is bounded and opt-in; no guest RAM or disk bytes are logged. */
    static __thread unsigned winjit_trace_mmu_writes;
    if (winjit_trace_mmu_writes < 12 &&
        getenv("WINJIT_TRACE_SYSREG") &&
        (reg == R(3, 0, 1, 0, 0) || reg == R(3, 0, 2, 0, 2) ||
         reg == R(3, 0, 10, 2, 0))) {
        ++winjit_trace_mmu_writes;
        fprintf(stderr,
                "winjit-msr-source[%u] pc=0x%llx insn=0x%08x rt=x%d "
                "src=0x%llx el=%u\\n",
                winjit_trace_mmu_writes, (unsigned long long)pc,
                insn, rt,
                (unsigned long long)(rt == 31 ? 0 : cpu->x[rt]),
                cpu->el);
    }
    if (cpu->m->log >= 3)
        fprintf(stderr, "msr S%u_%u_C%u_C%u_%u = %llx pc %llx el%u\\n", """
patch(core/"sysreg.c", sysreg_msr_anchor, sysreg_msr_probe)

# The entry assembly uses Mach-O leading underscores for symbols.
# Rename ONLY symbol identifiers in an isolated checkout; do not rewrite
# instructions, hard-coded CPU register offsets, or guest ABI.
s=entry.read_text()
n=s.count("_hv_")
if n < 10: raise RuntimeError(f"Unexpected upstream assembly: only {n} _hv_ symbols")
s=re.sub(r"\b_hv_([A-Za-z0-9_]+)\b",r"hv_\1",s)
entry.write_text(s)
print(f"PORT host/enter.S: {n} original Darwin symbol references renamed for ELF")

# Ensure every known core-level Mach header is removed; other Darwin-only
# references elsewhere should make the compile gate fail rather than bypass.
for f in core.glob("*.c"):
    body=f.read_text()
    assert "#include <mach/" not in body, f"Unported Mach header: {f}"
assert "mach_port_deallocate(" not in (core/"dispatch.c").read_text()
assert "mach_thread_self(" not in (core/"dispatch.c").read_text()
assert not re.search(r"\b_hv_",entry.read_text())
print("ANDROID SOURCE PORT PREPARED; compile and runtime gates remain mandatory")

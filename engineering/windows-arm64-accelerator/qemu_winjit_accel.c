/*
 * Experimental QEMU 11.1.3 Android ARM64 -accel winjit vCPU bridge.
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Research ONLY. Isolated QEMU accelerator; defaults remain TCG.
 * Boots with no guest disk changes. Fails closed on unsupported ARM state,
 * an unavailable W^X JIT or MMIO errors. IRQ/GIC, DMA invalidation, timers,
 * EL0/EL2/EL3 and migration are NOT validated. DO NOT PACKAGE AS A RELEASE.
 *
 * QEMU source build includes this file only for aarch64-softmmu and links
 * the separately built native WinJIT .so at runtime (dlopen).
 */

#include "qemu/osdep.h"
#include "qemu/accel.h"
#include "qemu/error-report.h"
#include "qemu/main-loop.h"
#include "qemu/guest-random.h"
#include "qemu/module.h"
#include "accel/accel-ops.h"
#include "accel/accel-cpu-ops.h"
#include "hw/core/boards.h"
#include "hw/core/cpu.h"
#include "exec/cpu-common.h"
#include "system/cpus.h"
#include "system/runstate.h"
#include "system/address-spaces.h"
#include "system/memory.h"
#include "qom/object.h"
#include "cpu.h"
#include "internals.h"
#include "winjit_cpu_bridge.h"
#include "winjit_phys_bus.h"
#include "winjit_pinned/machine.h"
#include "winjit_pinned/dispatch.h"

#include <dlfcn.h>

#define TYPE_WINJIT_ACCEL ACCEL_CLASS_NAME("winjit")
#define WINJIT_MAX_CPUS 4
#define WINJIT_CACHE_BYTES (4 * 1024 * 1024)

extern int winjit_qemu_capture_arm64(ARMCPU *, WinjitA64State *);
extern int winjit_qemu_restore_arm64(ARMCPU *, const WinjitA64State *);
extern int winjit_qemu_mmio_read(void *, uint64_t, uint8_t *, unsigned);
extern int winjit_qemu_mmio_write(void *, uint64_t, const uint8_t *, unsigned);

typedef struct WinjitRuntimeFns {
    int (*machine_init)(HvMachine *, size_t);
    void (*machine_free)(HvMachine *);
    int (*add_ram)(HvMachine *, uint64_t, uint64_t, void *, int);
    int (*bus_install)(HvMachine *, WinjitPhysBus *, uint64_t, uint64_t);
    unsigned (*bus_failures)(const WinjitPhysBus *);
    void (*cpu_reset)(HvCpu *, HvMachine *, uint32_t);
    int (*cpu_run_one)(HvCpu *);
    int (*cpu_import)(HvCpu *, const WinjitA64State *);
    int (*cpu_export)(WinjitA64State *, const HvCpu *);
    void (*cpu_kick)(HvMachine *);
} WinjitRuntimeFns;

typedef struct WinjitState {
    AccelState parent_obj;
    WinjitRuntimeFns fn;
    void *library;
    HvMachine *machine;
    HvCpu *vcpu[WINJIT_MAX_CPUS];
    WinjitPhysBus bus;
    MemoryListener listener;
    bool failed, started;
} WinjitState;

static bool winjit_allowed;
static WinjitState *runtime;

static bool winjit_load(WinjitState *s)
{
    s->library = dlopen("libwinjit-core-android-aarch64.so",
                       RTLD_NOW | RTLD_LOCAL);
    if (!s->library) {
        error_report("winjit: native translator not available: %s", dlerror());
        return false;
    }
#define REQUIRE_FN(field, name)                                             \
    do {                                                                    \
        void *ptr = dlsym(s->library, name);                                 \
        if (!ptr) {                                                         \
            error_report("winjit: required symbol %s missing", name);       \
            return false;                                                   \
        }                                                                   \
        memcpy(&s->fn.field, &ptr, sizeof ptr);                              \
    } while (0)
    REQUIRE_FN(machine_init, "hv_machine_init");
    REQUIRE_FN(machine_free, "hv_machine_free");
    REQUIRE_FN(add_ram, "hv_machine_add_ram");
    REQUIRE_FN(bus_install, "winjit_bus_install");
    REQUIRE_FN(bus_failures, "winjit_bus_failures");
    REQUIRE_FN(cpu_reset, "hv_cpu_reset_index");
    REQUIRE_FN(cpu_run_one, "hv_run_one");
    REQUIRE_FN(cpu_import, "winjit_cpu_from_qemu_state");
    REQUIRE_FN(cpu_export, "winjit_cpu_to_qemu_state");
    REQUIRE_FN(cpu_kick, "hv_machine_kick_all");
#undef REQUIRE_FN
    return true;
}

static void winjit_memory_add(MemoryListener *listener,
                              MemoryRegionSection *section)
{
    WinjitState *s = container_of(listener, WinjitState, listener);
    MemoryRegion *region = section->mr;
    if (!memory_region_is_ram(region)) {
        return;
    }
    if (s->started) {
        /* An unmodeled hotplug/alias change must never reuse a stale map. */
        s->failed = true;
        error_report("winjit: RAM changed after vCPU start; accelerator disabled");
        return;
    }
    if (section->size.hi != 0 || !int128_get64(section->size) ||
        s->fn.add_ram(s->machine, section->offset_within_address_space,
                      int128_get64(section->size),
                      (uint8_t *)memory_region_get_ram_ptr(region) +
                          section->offset_within_region,
                      region->readonly) != 0) {
        s->failed = true;
        error_report("winjit: RAM mapping unsupported; no guest execution");
    }
}

static void winjit_memory_del(MemoryListener *listener,
                              MemoryRegionSection *section)
{
    WinjitState *s = container_of(listener, WinjitState, listener);
    if (memory_region_is_ram(section->mr)) {
        s->failed = true;
        error_report("winjit: RAM removal requires translation invalidation");
    }
}

static int winjit_machine_init(AccelState *as, MachineState *ms)
{
    WinjitState *s = (WinjitState *)as;
    (void)ms;
#ifndef __aarch64__
    error_report("winjit: requires ARM64 host");
    return -ENOTSUP;
#else
    if (runtime || !winjit_load(s)) {
        return -ENOTSUP;
    }
    s->machine = g_malloc0(sizeof *s->machine);
    if (s->fn.machine_init(s->machine, WINJIT_CACHE_BYTES) != 0) {
        error_report("winjit: W^X executable memory allocation failed");
        return -ENOTSUP;
    }
    s->bus.opaque = &address_space_memory;
    s->bus.read = winjit_qemu_mmio_read;
    s->bus.write = winjit_qemu_mmio_write;
    if (s->fn.bus_install(s->machine, &s->bus, 0, UINT64_MAX) != 0) {
        error_report("winjit: cannot install QEMU physical-MMIO callbacks");
        return -ENOTSUP;
    }
    s->listener = (MemoryListener) {
        .name = "winjit",
        .priority = MEMORY_LISTENER_PRIORITY_ACCEL,
        .region_add = winjit_memory_add,
        .region_del = winjit_memory_del,
    };
    runtime = s;
    memory_listener_register(&s->listener, &address_space_memory);
    if (s->failed) {
        error_report("winjit: initial QEMU memory layout unsupported");
        return -ENOTSUP;
    }
    error_report("winjit: RESEARCH accelerator loaded; Windows not validated");
    return 0;
#endif
}

static void winjit_stop_unsafe(CPUState *cpu, const char *why)
{
    error_report("winjit: vCPU %d stopped safely: %s", cpu->cpu_index, why);
    cpu->halted = true;
    vm_stop(RUN_STATE_INTERNAL_ERROR);
}

static void *winjit_cpu_thread(void *opaque)
{
    CPUState *cpu = opaque;
    WinjitState *s = runtime;
    int index = cpu->cpu_index;
    if (!s || index < 0 || index >= WINJIT_MAX_CPUS) {
        return NULL;
    }
    rcu_register_thread();
    bql_lock();
    qemu_thread_get_self(cpu->thread);
    cpu->thread_id = qemu_get_thread_id();
    cpu->neg.can_do_io = true;
    current_cpu = cpu;

    HvCpu *hv = g_malloc0(sizeof *hv);
    s->vcpu[index] = hv;
    s->fn.cpu_reset(hv, s->machine, index);
    cpu_thread_signal_created(cpu);
    qemu_guest_random_seed_thread_part2(cpu->random_seed);

    do {
        qemu_process_cpu_events(cpu);
        if (!cpu_can_run(cpu)) {
            continue;
        }
        s->started = true;
        if (s->failed || s->fn.bus_failures(&s->bus)) {
            winjit_stop_unsafe(cpu, "memory map or MMIO error");
            continue;
        }
        if (cpu->interrupt_request) {
            /* IRQ/GIC signal delivery has not yet been verified. */
            winjit_stop_unsafe(cpu, "unverified interrupt routing");
            continue;
        }

        WinjitA64State snap = {0};
        if (winjit_qemu_capture_arm64(ARM_CPU(cpu), &snap) != WINJIT_CPU_OK ||
            s->fn.cpu_import(hv, &snap) != WINJIT_CPU_OK) {
            winjit_stop_unsafe(cpu, "unsupported CPU mode or state");
            continue;
        }
        /*
         * One actual translated block per BQL cycle. Holding the BQL
         * serializes the experimental memory callbacks and avoids hidden
         * races until DMA invalidation and interrupt delivery are ready.
         */
        int result = s->fn.cpu_run_one(hv);
        if (s->fn.bus_failures(&s->bus)) {
            winjit_stop_unsafe(cpu, "QEMU physical MMIO transaction failure");
            continue;
        }
        if (result != HV_EXIT_BRANCH) {
            winjit_stop_unsafe(cpu, "unhandled WinJIT exception or halt");
            continue;
        }
        if (s->fn.cpu_export(&snap, hv) != WINJIT_CPU_OK ||
            winjit_qemu_restore_arm64(ARM_CPU(cpu), &snap) != WINJIT_CPU_OK) {
            winjit_stop_unsafe(cpu, "unsupported translated state");
            continue;
        }
    } while (!cpu->unplug || cpu_can_run(cpu));

    s->vcpu[index] = NULL;
    g_free(hv);
    cpu_thread_signal_destroyed(cpu);
    bql_unlock();
    rcu_unregister_thread();
    return NULL;
}

static void winjit_start_vcpu(CPUState *cpu)
{
    char name[VCPU_THREAD_NAME_SIZE];
    snprintf(name, sizeof(name), "CPU %d/WJ", cpu->cpu_index);
    qemu_thread_create(cpu->thread, name, winjit_cpu_thread,
                       cpu, QEMU_THREAD_JOINABLE);
}

static void winjit_kick_vcpu(CPUState *cpu)
{
    if (runtime && runtime->machine) {
        runtime->fn.cpu_kick(runtime->machine);
    }
}

static void winjit_cpu_sync_state(CPUState *cpu)
{
    /* Each translated block synchronizes to QEMU. No guest state retained
       across QEMU resets or migrations until those semantics are implemented. */
    (void)cpu;
}

static void winjit_ops_init(ObjectClass *oc, const void *data)
{
    AccelOpsClass *ops = ACCEL_OPS_CLASS(oc);
    ops->create_vcpu_thread = winjit_start_vcpu;
    ops->kick_vcpu_thread = winjit_kick_vcpu;
    ops->handle_interrupt = generic_handle_interrupt;
    ops->synchronize_state = winjit_cpu_sync_state;
    ops->synchronize_post_reset = winjit_cpu_sync_state;
    ops->synchronize_post_init = winjit_cpu_sync_state;
    ops->synchronize_pre_loadvm = winjit_cpu_sync_state;
}

static void winjit_accel_class_init(ObjectClass *oc, const void *data)
{
    AccelClass *ac = ACCEL_CLASS(oc);
    ac->name = "WinJIT (unverified research)";
    ac->init_machine = winjit_machine_init;
    ac->allowed = &winjit_allowed;
}

static const TypeInfo winjit_accel_type = {
    .name = TYPE_WINJIT_ACCEL,
    .parent = TYPE_ACCEL,
    .instance_size = sizeof(WinjitState),
    .class_init = winjit_accel_class_init,
};
static const TypeInfo winjit_ops_type = {
    .name = ACCEL_OPS_NAME("winjit"),
    .parent = TYPE_ACCEL_OPS,
    .class_init = winjit_ops_init,
    .abstract = true,
};
static void winjit_register_types(void)
{
    type_register_static(&winjit_accel_type);
    type_register_static(&winjit_ops_type);
}
type_init(winjit_register_types);

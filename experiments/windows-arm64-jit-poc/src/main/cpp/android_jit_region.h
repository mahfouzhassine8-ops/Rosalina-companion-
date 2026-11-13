/* SPDX-License-Identifier: GPL-2.0-or-later
 * Isolated Android/Linux AArch64 W^X executable-memory feasibility probe.
 * Not an installed-Windows, hypervisor, or full JIT translator.
 */
#ifndef ANDROID_JIT_REGION_H
#define ANDROID_JIT_REGION_H
#include <stddef.h>
typedef struct {
    int permitted;
    int code_return;
    int errnum;
    char mode[72];
} JitProbe;
JitProbe android_jit_probe(void);
#endif

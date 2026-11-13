/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <jni.h>
#include "probe_engine.h"
JNIEXPORT jstring JNICALL
Java_com_fold_windowsjit_probe_ProbeActivity_runNativeProbe(
        JNIEnv *env, jclass ignored) {
    (void)ignored;
    char report[2048];
    probe_engine_run(report,sizeof report);
    return (*env)->NewStringUTF(env,report);
}

/* SPDX-License-Identifier: GPL-2.0-or-later
 * Linux ARM64 CI harness for the SAME native files built in the Android APK.
 */
#include "probe_engine.h"
#include <stdio.h>
int main(void) {
    char result[2048];
    int status = probe_engine_run(result, sizeof result);
    fputs(result, stdout);
    return status;
}

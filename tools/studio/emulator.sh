#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa
trap 'adb logcat -d > qa/studio-emulator-logcat.txt 2>/dev/null || true; adb shell run-as com.rosalina.unified tar -cf - files/audit-qa > qa/studio-android-evidence.tar 2>/dev/null || true' EXIT
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1
# Dedicated additive gate: the original full-companion workflow remains unchanged.
# The known failing real Chatterbox model acceptance is NOT claimed as cleared.
gradle :unified:connectedDebugAndroidTest -PunifiedEmulatorQa=true -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.notClass=com.rosalina.unified.ChatterboxAuditTest --max-workers=2 --stacktrace
printf 'Studio adapter and existing non-Chatterbox Android regressions only. No full 10018 acceptance; inherited Chatterbox timeout remains open. Synthetic PCM is not human-voice quality.\n' > qa/ACCEPTANCE-BOUNDARY.txt

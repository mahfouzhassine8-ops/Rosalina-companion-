#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa
trap 'adb logcat -d > qa/emulator-logcat.txt 2>/dev/null || true; adb shell run-as com.rosalina.unified tar -cf - files/audit-qa > qa/avatar-pixel-evidence.tar 2>qa/avatar-export-errors.txt || true' EXIT
# Exercise the real avatar frame clock, not just an animation-disabled screenshot.
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1
gradle :unified:connectedDebugAndroidTest -PunifiedEmulatorQa=true --max-workers=2 --stacktrace
adb shell run-as com.rosalina.unified tar -cf - files/audit-qa > qa/avatar-pixel-evidence.tar
adb install -r unified/build/outputs/apk/debug/unified-debug.apk
adb shell am start -W -n com.rosalina.unified/.MainActivity
sleep 1
adb exec-out screencap -p > qa/unified-emulator.png
adb shell "run-as com.rosalina.unified sh -c 'echo preserved > files/update-marker.txt'"
gradle :unified:assembleDebug -PunifiedEmulatorQa=true -PunifiedVersionCode=10012 --max-workers=2
adb install -r unified/build/outputs/apk/debug/unified-debug.apk
adb shell run-as com.rosalina.unified cat files/update-marker.txt | grep preserved
echo 'QA-signer x86_64 update 10011 -> 10012 and data continuity passed; permanent-signer Samsung update NOT tested' > qa/update-test.txt

#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa
trap 'adb logcat -d > qa/emulator-logcat.txt 2>/dev/null || true; adb shell run-as com.rosalina.unified tar -cf - files/audit-qa > qa/avatar-pixel-evidence.tar 2>qa/avatar-export-errors.txt || true' EXIT
# Exercise the real avatar frame clock, not just an animation-disabled screenshot.
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1
# Keep the disposable emulator test install until screenshots and update evidence are copied.
# Same flag used by the official android/nowinandroid project (AGP post-test cleanup).
gradle :unified:connectedDebugAndroidTest -PunifiedEmulatorQa=true -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --max-workers=2 --stacktrace
adb shell run-as com.rosalina.unified tar -cf - files/audit-qa > qa/avatar-pixel-evidence.tar
adb install -r unified/build/outputs/apk/debug/unified-debug.apk
adb shell am start -W -n com.rosalina.unified/.MainActivity
sleep 1
adb exec-out screencap -p > qa/unified-emulator.png
# Run a clean QA-signer update simulation from installed 10013 to 10014.
# The connected test package above is already 10014, so remove that disposable install first.
adb uninstall com.rosalina.unified || true
gradle :unified:assembleDebug -PunifiedEmulatorQa=true -PunifiedVersionCode=10013 --max-workers=2
adb install unified/build/outputs/apk/debug/unified-debug.apk
adb shell "run-as com.rosalina.unified sh -c 'mkdir -p files && echo preserved > files/update-marker.txt'"
gradle :unified:assembleDebug -PunifiedEmulatorQa=true -PunifiedVersionCode=10014 --max-workers=2
adb install -r unified/build/outputs/apk/debug/unified-debug.apk
adb shell run-as com.rosalina.unified cat files/update-marker.txt | grep preserved
echo 'QA-signer x86_64 update 10013 -> 10014 and data continuity passed; permanent-signer Samsung update still requires signed APK verification' > qa/update-test.txt

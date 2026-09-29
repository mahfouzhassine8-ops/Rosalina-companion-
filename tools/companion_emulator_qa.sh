#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa
trap 'adb logcat -d > qa/emulator-logcat.txt 2>/dev/null || true' EXIT
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1
gradle :unified:connectedDebugAndroidTest -PunifiedEmulatorQa=true -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --max-workers=2 --stacktrace
adb shell run-as com.rosalina.unified tar -cf - files/audit-qa > qa/avatar-and-playback-evidence.tar
adb shell am start -W -n com.rosalina.unified/.MainActivity
sleep 1
adb exec-out screencap -p > qa/chat-screen.png
# Baseline is built from its actual protected source, not merely a relabeled candidate version.
candidate="$PWD/unified/build/outputs/apk/debug/unified-debug.apk"
base="$RUNNER_TEMP/rosalina-baseline-10015"
git worktree add --detach "$base" e212d17bb1904de55fe7b6cbea40443548cc73dc
mkdir -p "$base/unified/libs"
cp unified/libs/sherpa-onnx-1.13.8.aar "$base/unified/libs/"
(cd "$base" && gradle :unified:assembleDebug -PunifiedEmulatorQa=true --max-workers=2)
adb uninstall com.rosalina.unified || true
adb install "$base/unified/build/outputs/apk/debug/unified-debug.apk"
adb shell "run-as com.rosalina.unified sh -c 'mkdir -p files && echo preserved > files/update-marker.txt'"
adb install -r "$candidate"
adb shell run-as com.rosalina.unified cat files/update-marker.txt | grep preserved
printf 'Actual source 10015 -> 10017; same disposable debug signer, x86_64 emulator; private marker preserved. Physical Samsung/permanent-signer installation not tested here.\n' > qa/update-test.txt

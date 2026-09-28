#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa
trap 'adb logcat -d > qa/emulator-logcat.txt 2>/dev/null || true' EXIT
gradle :unified:connectedDebugAndroidTest -PunifiedEmulatorQa=true --max-workers=2 --stacktrace
adb install -r unified/build/outputs/apk/debug/unified-debug.apk
adb shell am start -W -n com.rosalina.unified/.MainActivity
sleep 1
adb exec-out screencap -p > qa/unified-emulator.png
adb shell "run-as com.rosalina.unified sh -c 'echo preserved > files/update-marker.txt'"
gradle :unified:assembleDebug -PunifiedEmulatorQa=true -PunifiedVersionCode=10011 --max-workers=2
adb install -r unified/build/outputs/apk/debug/unified-debug.apk
adb shell run-as com.rosalina.unified cat files/update-marker.txt | grep preserved
echo 'QA-signer x86_64 update 10010 -> 10011 and data continuity passed; permanent-signer Samsung update NOT tested' > qa/update-test.txt

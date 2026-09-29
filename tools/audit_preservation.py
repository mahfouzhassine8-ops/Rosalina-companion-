#!/usr/bin/env python3
"""Audited Android candidate keeps locked native engines, models, identity, and thermal policy."""
from pathlib import Path
import subprocess

BASE = "c51780f4762bf88165adfe5440c4c4247697589a"
UNCHANGED = [
    "app", "lib", "studio", "image-engine", "motion", "motion-engine", "unified-native",
    "unified/src/main/assets/avatar", "unified/src/main/AndroidManifest.xml",
    "unified/src/main/java/com/rosalina/unified/Policy.kt",
    "unified/src/main/java/com/rosalina/unified/RuntimePolicy.kt",
    "unified/src/main/java/com/rosalina/unified/NativeWorker.kt",
    "unified/src/main/java/com/rosalina/unified/LiveVoice.kt",
    "unified/src/main/java/com/rosalina/unified/VoiceExpression.kt",
    "unified/src/main/java/com/rosalina/unified/VoiceDsp.kt",
    "unified/src/main/java/com/rosalina/unified/Models.kt",
]
subprocess.run(["git", "diff", "--exit-code", BASE, "HEAD", "--", *UNCHANGED], check=True)
gradle = Path("unified/build.gradle.kts").read_text()
assert 'applicationId = "com.rosalina.unified"' in gradle
assert '?: 10012' in gradle
print("Locked 10011 preservation: native sources, emergency avatar bytes, thermal policy, package, and model layout PASS")

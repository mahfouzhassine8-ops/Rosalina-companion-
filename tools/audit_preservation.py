#!/usr/bin/env python3
"""Chat + Live focus candidate preserves the locked 10012 media/native/model baseline."""
from pathlib import Path
import subprocess

BASE = "e7b7bba776be1c31c443424b627ff831f3a08373"
UNCHANGED = [
    "app", "lib", "studio", "image-engine", "motion", "motion-engine", "unified-native",
    "unified/src/main/assets/avatar",
    "unified/src/main/java/com/rosalina/unified/Policy.kt",
    "unified/src/main/java/com/rosalina/unified/RuntimePolicy.kt",
    "unified/src/main/java/com/rosalina/unified/NativeWorker.kt",
    "unified/src/main/java/com/rosalina/unified/Models.kt",
]
subprocess.run(["git", "diff", "--exit-code", BASE, "HEAD", "--", *UNCHANGED], check=True)
gradle = Path("unified/build.gradle.kts").read_text()
assert 'applicationId = "com.rosalina.unified"' in gradle
assert '?: 10014' in gradle
print("Locked 10012 preservation: media/native engines, model storage, avatar assets, package identity PASS")

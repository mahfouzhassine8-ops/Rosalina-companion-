#!/usr/bin/env python3
"""Audited Android candidate keeps locked native engines, models, identity, and thermal policy."""
from pathlib import Path
import subprocess

BASE = "c3c4d58550edc3eee40487c68d08c29612a33584"
UNCHANGED = [
    "app", "lib", "studio", "image-engine", "motion", "motion-engine", "unified-native",
    "unified/src/main/assets/avatar", "unified/src/main/AndroidManifest.xml",
    "unified/src/main/java/com/rosalina/unified/Policy.kt",
    "unified/src/main/java/com/rosalina/unified/RuntimePolicy.kt",
    "unified/src/main/java/com/rosalina/unified/NativeWorker.kt",
    "unified/src/main/java/com/rosalina/unified/LiveVoice.kt",
    "unified/src/main/java/com/rosalina/unified/VoiceExpression.kt",
    "unified/src/main/java/com/rosalina/unified/VoiceDsp.kt",
]
subprocess.run(["git", "diff", "--exit-code", BASE, "HEAD", "--", *UNCHANGED], check=True)
model_path = "unified/src/main/java/com/rosalina/unified/Models.kt"
old = subprocess.check_output(["git", "show", f"{BASE}:{model_path}"], text=True)
new = Path(model_path).read_text()
# Only registry publication order changes. No model pins, file layout, imports, or receipts migrate.
old = old.replace("registry.put(key.name,value);atomicText(registryFile,registry.toString(2))",
                  "val next=JSONObject(registry.toString()).put(key.name,value);atomicText(registryFile,next.toString(2));registry=next")
assert old == new, "Unexpected model-store change"
gradle = Path("unified/build.gradle.kts").read_text()
assert 'applicationId = "com.rosalina.unified"' in gradle
assert '?: 10011' in gradle
print("Locked 10010 preservation: native sources, avatar bytes, thermal policy, package, and model layout PASS")

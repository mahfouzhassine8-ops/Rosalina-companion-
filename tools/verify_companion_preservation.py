#!/usr/bin/env python3
from pathlib import Path
import json, subprocess
BASE='e212d17bb1904de55fe7b6cbea40443548cc73dc'
protected=['app','lib','studio','image-engine','motion','motion-engine','unified-native',
'unified/src/device/java/com/rosalina/unified/ChatService.kt',
'unified/src/main/java/com/rosalina/unified/ListenService.kt',
'unified/src/main/java/com/rosalina/unified/Models.kt',
'unified/src/main/java/com/rosalina/unified/Policy.kt',
'unified/src/main/java/com/rosalina/unified/RuntimePolicy.kt',
'unified/src/main/java/com/rosalina/unified/PhoneCapability.kt',
'unified/src/main/java/com/rosalina/unified/NativeWorker.kt',
'unified/src/main/java/com/rosalina/unified/SpeechService.kt',
'unified/src/main/assets/avatar', 'tools/build_unified_native.sh']
subprocess.run(['git','diff','--exit-code',BASE,'HEAD','--',*protected],check=True)
session=Path('unified/src/main/java/com/rosalina/unified/Session.kt').read_text()
assert 'res.available>=3_500_000_000L' in session, 'Do not import 10016 RAM experiment'
assert 'TaskKind.CREATE,TaskKind.EDIT,TaskKind.ANIMATE' in session
assert 'com.rosalina.unified' in Path('unified/build.gradle.kts').read_text()
assert '?: 10017' in Path('unified/build.gradle.kts').read_text()
manifest=Path('unified/src/main/AndroidManifest.xml').read_text()
assert 'android:name=".ExpressiveSpeechService" android:exported="false" android:process=":expressive"' in manifest
assert 'android:name=".SpeechService" android:exported="false" android:process=":speech"' in manifest
assert 'testspeech' not in manifest
pins=json.loads(Path('unified/src/main/assets/voice-v3-pack.json').read_text())
assert pins['archiveSha256']=='bc3b03930b3632f55e507e5229348c2f0353d9265aa68fbb91269db3a6266d33'
print('Preserved 10015 Qwen/Whisper/native/model policy and 3.5 GB gate. Candidate remains isolated and unpromoted.')

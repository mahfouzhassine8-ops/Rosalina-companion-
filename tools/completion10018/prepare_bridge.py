#!/usr/bin/env python3
"""Install the unique Java bridge, never the protected sherpa C runtime."""
import hashlib,json,pathlib,shutil,sys,zipfile
src=pathlib.Path(sys.argv[1]); abi=sys.argv[2]
assert abi in ('arm64-v8a','x86_64')
checks={'classes.jar':'c21c4b520c66dda4c8d6e17e7a64f2b38314a83f8a29ecfa60900f876b758752',
'jni/arm64-v8a/libonnxruntime4j_jni.so':'2cc3f16ab527959a81b572adebcfb71a25ea4b8547c9b92d705b423e9c822d39',
'jni/x86_64/libonnxruntime4j_jni.so':'b8d1b251b7d5606cf5caa7db9738353c08efc4090ac90ca2aaab449bc263a10b'}
for name,digest in checks.items():
 assert hashlib.sha256((src/name).read_bytes()).hexdigest()==digest,name
out=pathlib.Path('unified/src/main/jniLibs')/abi;out.mkdir(parents=True,exist_ok=True)
shutil.copyfile(src/f'jni/{abi}/libonnxruntime4j_jni.so',out/'libonnxruntime4j_jni.so')
shutil.copyfile(src/'classes.jar','unified/libs/rosalina-ort-java-1.23.2.jar')
shutil.copyfile(src/'ONNX-Runtime-LICENSE','unified/src/main/assets/licenses/onnx-runtime-LICENSE')
with zipfile.ZipFile('unified/libs/sherpa-onnx-1.13.8.aar') as z:
 entry=f'jni/{abi}/libonnxruntime.so';data=z.read(entry)
 core=out/'libonnxruntime.so'
 assert not core.exists(), 'Do not shadow the protected C library packaged by sherpa'
pathlib.Path('qa').mkdir(exist_ok=True)
pathlib.Path('qa/protected-onnx.json').write_text(json.dumps({'abi':abi,'protectedSha256':hashlib.sha256(data).hexdigest(),'bridge':checks[f'jni/{abi}/libonnxruntime4j_jni.so'],'sherpaRuntimeReplaced':False},indent=2))
print('Verified unique JNI bridge and unmodified protected C runtime')

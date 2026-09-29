#!/usr/bin/env python3
"""Keep sherpa's C runtime byte-identical; give the new Java voice bridge its own SONAME.

The original Android test reproduced OrtGetApiBase@VERS_1.23.2 resolution failure
against sherpa's differently-versioned runtime. Merely shipping a unique JNI file
was insufficient. Retarget only DT_NEEDED/SONAME string bytes, without moving ELF
segments, changing operators, or touching the protected library.
"""
import hashlib,json,pathlib,shutil,struct,sys,urllib.request,zipfile
src=pathlib.Path(sys.argv[1]);abi=sys.argv[2]
assert abi in ('arm64-v8a','x86_64')
checks={'classes.jar':'c21c4b520c66dda4c8d6e17e7a64f2b38314a83f8a29ecfa60900f876b758752',
'jni/arm64-v8a/libonnxruntime4j_jni.so':'2cc3f16ab527959a81b572adebcfb71a25ea4b8547c9b92d705b423e9c822d39',
'jni/x86_64/libonnxruntime4j_jni.so':'b8d1b251b7d5606cf5caa7db9738353c08efc4090ac90ca2aaab449bc263a10b'}
def sha(data):return hashlib.sha256(data).hexdigest()
for name,digest in checks.items():assert sha((src/name).read_bytes())==digest,name

def retarget(data,tag):
    assert data[:6]==b'\x7fELF\x02\x01','Expected little-endian ELF64'
    phoff=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
    segments=[struct.unpack_from('<IIQQQQQQ',data,phoff+i*size) for i in range(count)]
    dynamic=next(s for s in segments if s[0]==2)
    entries=[struct.unpack_from('<QQ',data,o) for o in range(dynamic[2],dynamic[2]+dynamic[5],16)]
    address=next(v for t,v in entries if t==5);length=next(v for t,v in entries if t==10)
    load=next(s for s in segments if s[0]==1 and s[3]<=address<s[3]+s[5])
    start=load[2]+address-load[3]
    old=b'libonnxruntime.so';new=b'libvoiceort.so'
    table=data[start:start+length];indices=[v for t,v in entries if t==tag]
    matches=[v for v in indices if table[v:v+len(old)+1]==old+b'\0']
    assert len(matches)==1,'Expected one original SONAME/dependency'
    at=start+matches[0];replacement=new+b'\0'*(len(old)+1-len(new))
    result=data[:at]+replacement+data[at+len(old)+1:]
    assert len(result)==len(data)
    assert result[:64]==data[:64]
    assert result[phoff:phoff+size*count]==data[phoff:phoff+size*count]
    assert min(s[7] for s in segments if s[0]==1)>=16384
    return result

out=pathlib.Path('unified/src/main/jniLibs')/abi;out.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile('unified/libs/sherpa-onnx-1.13.8.aar') as z:
    protected=z.read(f'jni/{abi}/libonnxruntime.so')
assert not (out/'libonnxruntime.so').exists(),'Do not shadow the protected C library packaged by sherpa'
archive=src/'official-onnxruntime-android-1.23.2.aar'
if not archive.is_file():
    url='https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.23.2/onnxruntime-android-1.23.2.aar'
    with urllib.request.urlopen(url,timeout=90) as response,archive.open('wb') as f:shutil.copyfileobj(response,f)
assert sha(archive.read_bytes())=='82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827'
with zipfile.ZipFile(archive) as z:
    core=z.read(f'jni/{abi}/libonnxruntime.so')
    jni=z.read(f'jni/{abi}/libonnxruntime4j_jni.so')
    assert sha(jni)==checks[f'jni/{abi}/libonnxruntime4j_jni.so']
voicecore=retarget(core,14)
voicejni=retarget(jni,1)
(out/'libvoiceort.so').write_bytes(voicecore)
(out/'libonnxruntime4j_jni.so').write_bytes(voicejni)
shutil.copyfile(src/'classes.jar','unified/libs/rosalina-ort-java-1.23.2.jar')
shutil.copyfile(src/'ONNX-Runtime-LICENSE','unified/src/main/assets/licenses/onnx-runtime-LICENSE')
pathlib.Path('qa').mkdir(exist_ok=True)
pathlib.Path('qa/protected-onnx.json').write_text(json.dumps({'abi':abi,'protectedSha256':sha(protected),
    'candidateRuntimeVersion':'1.23.2','candidateSoname':'libvoiceort.so','candidateOriginalSha256':sha(core),
    'candidatePackagedSha256':sha(voicecore),'bridgeOriginalSha256':sha(jni),'bridge':sha(voicejni),
    'sherpaRuntimeReplaced':False,'elfSegmentLayoutChanged':False,
    'repair':'Retarget only candidate SONAME/dependency to resolve reproduced versioned-symbol failure'},indent=2))
print('Candidate C/JNI linked by unique SONAME; protected C runtime and ELF LOAD layout unchanged')

import sys, zipfile, struct
p=sys.argv[1]
with zipfile.ZipFile(p) as z:
    libs=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert libs and all(n.startswith('lib/arm64-v8a/') for n in libs), libs
    for name in ['librosalina-image.so','libllama.so','libai-chat.so']:
        assert 'lib/arm64-v8a/'+name in libs, name
    cpu=[n for n in libs if 'libggml-cpu-' in n]
    assert len(cpu)==7, cpu
    data=z.read('lib/arm64-v8a/librosalina-image.so')
    assert data[:4]==b'\x7fELF' and data[4]==2
    assert struct.unpack_from('<H',data,18)[0]==183, 'Expected AArch64'
    assert any(n.startswith('assets/licenses/') for n in z.namelist())
    print('APK structure verified:', len(libs),'native files, seven original CPU backends, isolated ARM64 worker')

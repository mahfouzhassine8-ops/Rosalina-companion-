#!/usr/bin/env python3
import json,pathlib,struct,sys,zipfile
apk=pathlib.Path(sys.argv[1]);rows=[]
with zipfile.ZipFile(apk) as z:
    libs=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert libs and all('/arm64-v8a/' in n for n in libs)
    for name in libs:
        b=z.read(name);assert b[:4]==b'\x7fELF' and b[4]==2
        assert struct.unpack_from('<H',b,18)[0]==183,name
        phoff=struct.unpack_from('<Q',b,32)[0];size,count=struct.unpack_from('<HH',b,54)
        align=[]
        for i in range(count):
            p=phoff+i*size
            if struct.unpack_from('<I',b,p)[0]==1:align.append(struct.unpack_from('<Q',b,p+48)[0])
        assert align and min(align)>=16384,(name,align)
        rows.append({'library':name,'bytes':len(b),'load_alignment':align})
    for n in ('image','motion','image-vulkan','motion-vulkan'):assert f'lib/arm64-v8a/librosalina-{n}.so' in libs
    assert any('ai-chat' in n for n in libs)
    assert any('sherpa-onnx-jni' in n for n in libs)
pathlib.Path('qa').mkdir(exist_ok=True)
pathlib.Path('qa/native-packaging.json').write_text(json.dumps(rows,indent=2));print(f'ARM64 and 16-KiB ELF alignment checked: {len(libs)} libraries')

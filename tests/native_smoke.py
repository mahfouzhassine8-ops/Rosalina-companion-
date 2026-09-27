import json, pathlib, struct, subprocess, sys, zlib
worker,model,out=[pathlib.Path(p).resolve() for p in sys.argv[1:]]
out.mkdir(exist_ok=True)
subprocess.run([str(worker),'--self-test'],check=True,timeout=30)
(out/'prompt.txt').write_text('a small red ceramic vase on a wooden table, still life, soft light')
(out/'negative.txt').write_text('blurry, distorted')

def inspect(path, png):
    data=path.read_bytes()
    assert data[:4]==b'RIMG'
    w,h,ch=struct.unpack_from('<III',data,4)
    assert (w,h,ch)==(128,128,3)
    rgb=data[16:]
    assert len(rgb)==w*h*ch and len(set(rgb))>8, 'Missing or blank image'
    def chunk(t,b): return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
    rows=b''.join(b'\0'+rgb[y*w*3:(y+1)*w*3] for y in range(h))
    png.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b''))
    return rgb

report={}
for edit in [False,True]:
    name='img2img' if edit else 'txt2img'
    target=out/(name+'.rimg')
    cmd=[str(worker),str(model),str(out/'prompt.txt'),str(out/'negative.txt'),str(out/'reference.rgb') if edit else '-',str(target),'128','128','2' if edit else '1','42','0.55','2']
    with (out/(name+'.txt')).open('w') as log:
        result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=600)
    assert result.returncode==0, (name,result.returncode,(out/(name+'.txt')).read_text()[-10000:])
    rgb=inspect(target,out/(name+'.png'))
    if not edit: (out/'reference.rgb').write_bytes(rgb)
    report[name]={'exit_code':result.returncode,'shape':[128,128,3],'nonblank':True}
report['scope']='Linux CPU smoke test of pinned runtime and exact model; not an Android device/performance test'
(out/'report.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))

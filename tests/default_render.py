"""Exercise the actual UI default image shape and sampler settings, not phone speed."""
import json, pathlib, struct, subprocess, sys, time, zlib
worker,model,out=[pathlib.Path(p).resolve() for p in sys.argv[1:]]
out.mkdir(exist_ok=True)
prompt='a red ceramic vase with a single white flower on a wooden table, soft window light, still life photography, sharp focus'
(out/'prompt.txt').write_text(prompt)
(out/'negative.txt').write_text('blurry, low quality, distorted')
output=out/'default.rimg'
command=[str(worker),str(model),str(out/'prompt.txt'),str(out/'negative.txt'),'-',str(output),'512','512','12','42','0.55','4']
t=time.monotonic()
with (out/'render-log.txt').open('w') as log:
    result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=1200)
seconds=time.monotonic()-t
assert result.returncode==0, (result.returncode,(out/'render-log.txt').read_text()[-10000:])
data=output.read_bytes()
assert data[:4]==b'RIMG'
w,h,ch=struct.unpack_from('<III',data,4)
assert (w,h,ch)==(512,512,3)
rgb=data[16:]
assert len(rgb)==w*h*ch and len(set(rgb))>32

def chunk(t,b):
    return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
rows=b''.join(b'\0'+rgb[y*w*3:(y+1)*w*3] for y in range(h))
(out/'default-512-12steps.png').write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b''))
report={'shape':[w,h,ch],'steps':12,'seed':42,'cfg':7.0,'sampler':'Euler A','backend':'Linux CPU','threads':4,'elapsed_seconds':round(seconds,2),'output_valid':True,'scope':'Default settings validation on GitHub Linux runner; NOT Android phone timing or on-device validation.'}
(out/'report.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))

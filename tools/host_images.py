#!/usr/bin/env python3
import hashlib,json,pathlib,struct,subprocess,sys,time
from PIL import Image,ImageStat
worker,model=sys.argv[1:3]
root=pathlib.Path('qa/images');root.mkdir(parents=True,exist_ok=True)
report=[]
for name,size,steps,edit in [('create-draft',384,8,False),('edit-draft',384,8,True),('create-standard',512,12,False)]:
    folder=root/name;folder.mkdir(exist_ok=True)
    (folder/'prompt.txt').write_text('A watercolor painting of a red vase with white flowers on a wooden table' if not edit else 'A blue ceramic vase with yellow flowers, watercolor painting')
    (folder/'negative.txt').write_text('')
    ref='-'
    if edit:
        source=Image.open(root/'create-draft.png').convert('RGB');(folder/'input.rgb').write_bytes(source.tobytes());ref=str(folder/'input.rgb')
    output=folder/'output.rimg';log=folder/'native.log'
    command=[worker,model,str(folder/'prompt.txt'),str(folder/'negative.txt'),ref,str(output),str(size),str(size),str(steps),'42','0.65','2']
    started=time.monotonic()
    with log.open('w') as stream:subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=1800)
    elapsed=time.monotonic()-started;data=output.read_bytes()
    assert len(data)==16+size*size*3 and struct.unpack('<4sIII',data[:16])==(b'RIMG',size,size,3)
    image=Image.frombytes('RGB',(size,size),data[16:]);assert max(ImageStat.Stat(image).var)>10,'Image is effectively blank'
    image.save(root/f'{name}.png')
    lines=log.read_text(errors='replace').splitlines();samples=[line.split() for line in lines if line.startswith('@@SAMPLE ')]
    assert samples,'No phase-qualified sampling progress was emitted'
    assert all(1<=int(row[1])<=int(row[2])<=steps for row in samples)
    assert any('@@STAGE Decoding' in line for line in lines)
    if edit:assert hashlib.sha256(data[16:]).digest()!=hashlib.sha256((folder/'input.rgb').read_bytes()).digest(),'Edit returned unchanged pixels'
    report.append({'test':name,'width':size,'height':size,'steps':steps,'elapsed_seconds':elapsed,'samples':len(samples),'sha256':hashlib.sha256((root/f'{name}.png').read_bytes()).hexdigest(),'scope':'Linux CPU host; not phone verified'})
    output.unlink()
(root/'host-image-report.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))

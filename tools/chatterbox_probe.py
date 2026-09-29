#!/usr/bin/env python3
"""Reproducible evaluation, not a declaration of phone acceptance or primary promotion."""
import hashlib,json,pathlib,time,zipfile,shutil
import requests,numpy as np,onnxruntime as ort,soundfile as sf
from transformers import AutoTokenizer
REV='d21799bd0354adb85e348b8a0442a8405110a2cf'
REPO='ResembleAI/chatterbox-turbo-ONNX'
root=pathlib.Path('chatterbox-pack');root.mkdir(exist_ok=True)
qa=pathlib.Path('engine-evaluation');qa.mkdir(exist_ok=True)
def get(url):
    r=requests.get(url,timeout=120);r.raise_for_status();return r
meta=get(f'https://huggingface.co/api/models/{REPO}/revision/{REV}?blobs=true').json()
(qa/'upstream-metadata.json').write_text(json.dumps(meta,indent=2))
files={x['rfilename']:x for x in meta['siblings']}
models=['conditional_decoder_quantized','speech_encoder_quantized','embed_tokens_q4','language_model_q4']
for model in models:
    for ending in ('.onnx','.onnx_data'):
        name='onnx/'+model+ending
        if name not in files: raise RuntimeError('Pinned graph not found: '+name)
names=['config.json','generation_config.json','tokenizer.json','tokenizer_config.json','README.md']+[f'onnx/{m}{ext}' for m in models for ext in ('.onnx','.onnx_data')]
checks={}
for name in names:
    target=root/name;target.parent.mkdir(parents=True,exist_ok=True)
    with requests.get(f'https://huggingface.co/{REPO}/resolve/{REV}/{name}',stream=True,timeout=120) as r:
        r.raise_for_status()
        with target.open('wb') as f:
            for block in r.iter_content(1024*1024): f.write(block)
    data=target.read_bytes();digest=hashlib.sha256(data).hexdigest()
    info=files[name]
    if info.get('lfs'):assert digest==info['lfs']['sha256'],name
    elif info.get('blobId'):assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==info['blobId'],name
    checks[name]=digest
    print(name,len(data),digest,flush=True)
# Reuse only the already licensed voice reference, not the rejected Pocket model.
reference=next(pathlib.Path('reference-source').glob('*.zip'))
assert hashlib.sha256(reference.read_bytes()).hexdigest()=='bc3b03930b3632f55e507e5229348c2f0353d9265aa68fbb91269db3a6266d33'
with zipfile.ZipFile(reference) as z:
    for name in ('reference.wav','VOICE-ATTRIBUTION.md','PROVENANCE.json'):
        matches=[x for x in z.namelist() if x==name or x.endswith('/'+name)];assert len(matches)==1
        data=z.read(matches[0]);(root/name).write_bytes(data);checks[name]=hashlib.sha256(data).hexdigest()
assert checks['reference.wav']=='d4329a97a70c46fd377524513d42238c03e784b65c51bdd64094e975ffb26903'
opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
opts.enable_mem_pattern=False
sessions={name:ort.InferenceSession(str(root/'onnx'/f'{model}.onnx'),sess_options=opts,providers=['CPUExecutionProvider']) for name,model in zip(('decoder','encoder','embed','lm'),models)}
info={k:{'inputs':[{'name':v.name,'shape':v.shape,'type':v.type} for v in s.get_inputs()],'outputs':[{'name':v.name,'shape':v.shape,'type':v.type} for v in s.get_outputs()]} for k,s in sessions.items()}
(qa/'graph-interfaces.json').write_text(json.dumps(info,indent=2))
for name in ('config.json','tokenizer_config.json'):shutil.copyfile(root/name,qa/name)
tokjson=json.loads((root/'tokenizer.json').read_text())
(qa/'tokenizer-structure.json').write_text(json.dumps({k:v for k,v in tokjson.items() if k!='model'}|{'model':{k:v for k,v in tokjson['model'].items() if k not in ('vocab','merges')}},indent=2))
tokenizer=AutoTokenizer.from_pretrained(str(root),local_files_only=True)
phrases=["Hi. I'm Rosalina.","That's funny! [chuckle]",'We can slow down. [sigh]','Café — hello, 世界!']
(qa/'tokenizer-cases.json').write_text(json.dumps([{'text':p,'ids':tokenizer(p)['input_ids']} for p in phrases],indent=2))
audio,sr=sf.read(root/'reference.wav',dtype='float32');assert sr==24000
if audio.ndim>1:audio=audio.mean(axis=1)
t0=time.monotonic();cond,prompt,speaker,features=sessions['encoder'].run(None,{'audio_values':audio[None,:]})
(qa/'conditioning.json').write_text(json.dumps({'ms':int((time.monotonic()-t0)*1000),'cond':list(cond.shape),'prompt':list(prompt.shape),'speaker':list(speaker.shape),'features':list(features.shape)}))
# Precomputed licensed reference conditioning removes the audio encoder from Android startup.
for name,value in [('cond',cond),('prompt',prompt),('speaker',speaker),('features',features)]:
    value=np.ascontiguousarray(value);name='conditioning/'+name
    (root/'conditioning').mkdir(exist_ok=True)
    path=root/(name+'.bin');path.write_bytes(value.tobytes());checks[name+'.bin']=hashlib.sha256(path.read_bytes()).hexdigest()
    path=root/(name+'.json');path.write_text(json.dumps({'shape':list(value.shape),'dtype':str(value.dtype)}));checks[name+'.json']=hashlib.sha256(path.read_bytes()).hexdigest()
text=phrases[1];ids=np.array([tokenizer(text)['input_ids']],dtype=np.int64)
emb=sessions['embed'].run(None,{'input_ids':ids})[0]
emb=np.concatenate((cond,emb),axis=1);seq=emb.shape[1]
past={i.name:np.zeros((1,16,0,64),dtype=np.float32) for i in sessions['lm'].get_inputs() if 'past_key_values' in i.name}
mask=np.ones((1,seq),dtype=np.int64);pos=np.arange(seq,dtype=np.int64)[None,:]
generated=[];t0=time.monotonic();steps=[]
for index in range(100):
    result=sessions['lm'].run(None,dict(inputs_embeds=emb,attention_mask=mask,position_ids=pos,**past))
    scores=result[0][0,-1,:].copy()
    for old in set([6561]+generated):scores[old]=scores[old]*1.2 if scores[old]<0 else scores[old]/1.2
    nxt=int(scores.argmax());generated.append(nxt)
    if nxt==6562:break
    emb=sessions['embed'].run(None,{'input_ids':np.array([[nxt]],dtype=np.int64)})[0]
    mask=np.concatenate((mask,np.ones((1,1),dtype=np.int64)),axis=1);pos=pos[:,-1:]+1
    for j,key in enumerate(past):past[key]=result[j+1]
    steps.append(time.monotonic()-t0)
    if time.monotonic()-t0>150:raise RuntimeError('Host generation exceeded evaluation limit')
tokens=np.array([generated[:-1] if generated[-1]==6562 else generated],dtype=np.int64)
tokens=np.concatenate((prompt,tokens,np.full((1,3),4299,dtype=np.int64)),axis=1)
wav=sessions['decoder'].run(None,dict(speech_tokens=tokens,speaker_embeddings=speaker,speaker_features=features))[0].reshape(-1)
assert len(wav)>2400 and np.isfinite(wav).all() and float(np.max(np.abs(wav)))>.001
sf.write(qa/'host-chuckle.wav',wav,24000)
(qa/'host-result.json').write_text(json.dumps({'runtime':ort.__version__,'tokens':generated,'elapsedMs':int((time.monotonic()-t0)*1000),'audioMs':len(wav)*1000//24000,'rms':float(np.sqrt(np.mean(wav*wav))),'phoneAccepted':False,'audibleQualityJudgment':None,'watermark':'Official ONNX graph output; optional external PerTh postprocessor not applied or verified'},indent=2))
manifest={'engine':'chatterbox-turbo-onnx-q4','revision':REV,'sampleRate':24000,'files':checks,'phoneAccepted':False,'conditioning':'Exact licensed Alba reference; no microphone upload; generated locally in this reproducible evaluation','nativeCapabilities':['chuckle','laugh','sigh','cough'],'unverifiedCapabilities':['controlled whisper','all requested emotional styles','Android first-audio latency','phone coexistence with Qwen and Whisper']}
(root/'model-manifest.json').write_text(json.dumps(manifest,sort_keys=True,indent=2))
shutil.copyfile(root/'model-manifest.json',qa/'model-manifest.json')
delivery=pathlib.Path('model-delivery');delivery.mkdir(exist_ok=True)
with zipfile.ZipFile(delivery/'Rosalina-Chatterbox-Turbo-Q4-Candidate.zip','w',compression=zipfile.ZIP_STORED) as z:
    for p in sorted(root.rglob('*')):
        if p.is_file():
            zi=zipfile.ZipInfo(str(p.relative_to(root)),date_time=(2026,9,29,0,0,0));zi.external_attr=0o100644<<16
            z.writestr(zi,p.read_bytes())
shutil.copyfile(root/'model-manifest.json',delivery/'model-manifest.json')
print('Host synthesis completed; no Android or phone voice acceptance claimed.',flush=True)

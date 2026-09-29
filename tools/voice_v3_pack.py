#!/usr/bin/env python3
"""Prepare a private-import candidate pack and honest Linux-only speech measurements.
No phone performance, whisper, laughter, or emotional-control acceptance is implied.
"""
from pathlib import Path
import hashlib, json, math, os, platform, resource, shutil, tarfile, time, urllib.request, zipfile

ROOT = Path('voice-v3-work')
OUT = Path('voice-v3-output')
URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2'
NAMES = ['lm_flow.int8.onnx','lm_main.int8.onnx','encoder.onnx','decoder.int8.onnx','text_conditioner.onnx','vocab.json','token_scores.json']

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def download(url, dest):
    print('Downloading',url,flush=True)
    req=urllib.request.Request(url,headers={'User-Agent':'Rosalina-Voice-Candidate/1'})
    with urllib.request.urlopen(req,timeout=120) as src, dest.open('wb') as target:
        shutil.copyfileobj(src,target,1024*1024)
    return sha(dest)

def main():
    import numpy as np
    import sherpa_onnx
    import soundfile as sf
    from scipy.signal import resample_poly
    ROOT.mkdir(exist_ok=True);OUT.mkdir(exist_ok=True)
    packdir=ROOT/'pack';packdir.mkdir(exist_ok=True)
    archive=ROOT/'pocket.tar.bz2';source_sha=download(URL,archive)
    # Extract named regular files only; no archive paths, symlinks or bundled unlicensed voice samples.
    found=set()
    with tarfile.open(archive,'r:bz2') as source:
        for member in source:
            name=Path(member.name).name
            if name not in NAMES:continue
            if not member.isfile() or name in found:raise ValueError('Unexpected model member: '+member.name)
            found.add(name)
            with source.extractfile(member) as src,(packdir/name).open('wb') as target:shutil.copyfileobj(src,target)
    if found!=set(NAMES):raise ValueError('Model archive is incomplete')
    # Pin the voice repository revision before downloading the reference and its attribution.
    req=urllib.request.Request('https://huggingface.co/api/models/kyutai/tts-voices',headers={'User-Agent':'Rosalina-Voice-Candidate/1'})
    with urllib.request.urlopen(req,timeout=60) as reply:revision=json.load(reply)['sha']
    base='https://huggingface.co/kyutai/tts-voices/resolve/'+revision+'/'
    reference=ROOT/'reference-source.wav';reference_sha=download(base+'alba-mackenna/casual.wav',reference)
    license_path=packdir/'VOICE-ATTRIBUTION.md';download(base+'README.md',license_path)
    recording,rate=sf.read(reference,dtype='float32',always_2d=True)
    mono=recording.mean(axis=1)
    if not np.isfinite(mono).all():raise ValueError('Reference contains non-finite samples')
    mono=mono[:int(rate*10)]
    g=math.gcd(int(rate),24000)
    mono=resample_poly(mono,24000//g,int(rate)//g)
    sf.write(packdir/'reference.wav',mono,24000,subtype='PCM_16',format='WAV')
    download('https://raw.githubusercontent.com/kyutai-labs/pocket-tts/main/LICENSE',packdir/'MODEL-LICENSE.txt')
    provenance={'model_url':URL,'model_archive_sha256':source_sha,'voice_repository':'kyutai/tts-voices','voice_revision':revision,'voice_path':'alba-mackenna/casual.wav','voice_source_sha256':reference_sha,'reference_processing':'First 10 seconds; mono mixdown; resampled to 24000 Hz; PCM16 WAV','voice_attribution':'Alba MacKenna, published by Kyutai, CC BY 4.0. Modified reference as described. No endorsement implied.','runtime':'sherpa-onnx 1.13.8','phone_validated':False,'unsupported_claims':['phoneme-accurate lips','controlled whispers','explicit emotion controls','sigh/laugh generation tags']}
    (packdir/'PROVENANCE.json').write_text(json.dumps(provenance,indent=2)+'\n')
    packed=OUT/'Rosalina-VoiceV3-Pocket-INT8-Candidate.zip'
    with zipfile.ZipFile(packed,'w',compression=zipfile.ZIP_STORED) as z:
        for path in sorted(packdir.iterdir()):
            info=zipfile.ZipInfo(path.name,date_time=(2026,1,26,0,0,0));info.external_attr=0o100644<<16
            z.writestr(info,path.read_bytes())
    pins={'archiveSha256':sha(packed),'files':{p.name:sha(p) for p in sorted(packdir.iterdir())},'provenance':provenance}
    (OUT/'voice-v3-pack.json').write_text(json.dumps(pins,indent=2)+'\n')
    print('Pack prepared',packed.stat().st_size,pins['archiveSha256'],flush=True)
    def p(name):return str(packdir/name)
    start=time.monotonic()
    config=sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(pocket=sherpa_onnx.OfflineTtsPocketModelConfig(lm_flow=p('lm_flow.int8.onnx'),lm_main=p('lm_main.int8.onnx'),encoder=p('encoder.onnx'),decoder=p('decoder.int8.onnx'),text_conditioner=p('text_conditioner.onnx'),vocab_json=p('vocab.json'),token_scores_json=p('token_scores.json')),num_threads=2,provider='cpu',debug=False))
    if not config.validate():raise ValueError('Pocket config rejected')
    tts=sherpa_onnx.OfflineTts(config);setup=time.monotonic()-start
    ref,rate=sf.read(packdir/'reference.wav',dtype='float32')
    gen=sherpa_onnx.GenerationConfig();gen.reference_audio=ref;gen.reference_sample_rate=rate;gen.num_steps=5
    phrases=[('neutral','Hello. I am Rosalina. Let us take this one step at a time.'),('warm','You have had a long day. Take a breath; we can work through this together.'),('playful','Well, that was unexpected! You certainly know how to keep a conversation interesting.'),('question','Hmm, let me think. Would you prefer the shorter explanation, or a little more detail?')]
    results=[];auditions=OUT/'auditions';auditions.mkdir(exist_ok=True)
    for name,text in phrases:
        print('Synthesizing candidate audition:',name,flush=True);start=time.monotonic();audio=tts.generate(text,gen);elapsed=time.monotonic()-start
        samples=np.asarray(audio.samples,dtype=np.float32)
        if not samples.size or not np.isfinite(samples).all():raise ValueError('Invalid candidate output '+name)
        duration=len(samples)/audio.sample_rate;rms=float(np.sqrt(np.mean(samples.astype(np.float64)**2)))
        if duration<=0 or rms<1e-5:raise ValueError('Silent candidate output '+name)
        sf.write(auditions/(name+'.wav'),samples,audio.sample_rate,subtype='PCM_16')
        results.append({'name':name,'text':text,'synthesis_seconds':elapsed,'audio_seconds':duration,'real_time_factor':elapsed/duration,'rms':rms,'sample_rate':audio.sample_rate})
        print(results[-1],flush=True)
    report={'host':platform.platform(),'runtime':'sherpa-onnx 1.13.8','cpu_threads':2,'setup_seconds':setup,'max_rss_kib':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,'auditions':results,'acceptance':'Linux synthesis and finite/non-silent output only. Not Android playback, Samsung latency, sustained thermal, whisper, nonverbal-reaction or humanization acceptance.'}
    (OUT/'host-voice-v3.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Host candidate synthesis complete; phone acceptance remains untested.',flush=True)

if __name__=='__main__':main()

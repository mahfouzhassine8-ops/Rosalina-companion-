#!/usr/bin/env python3
"""Build-time only. Download official offline packs and pin their import digests in this APK."""
import argparse, hashlib, json, pathlib, tarfile, time, urllib.request
p=argparse.ArgumentParser();p.add_argument('--benchmark',action='store_true');a=p.parse_args()
root=pathlib.Path('voice-cache');root.mkdir(exist_ok=True)
qa=pathlib.Path('qa');qa.mkdir(exist_ok=True)
assets=pathlib.Path('unified/src/main/assets');assets.mkdir(parents=True,exist_ok=True)
names={'TTS':('tts-models','kokoro-multi-lang-v1_0.tar.bz2'),'STT':('asr-models','sherpa-onnx-whisper-tiny.en.tar.bz2')}
pins=json.loads(pathlib.Path('tools/voice-pins.json').read_text()) if pathlib.Path('tools/voice-pins.json').exists() else {}
hashes={};provenance={}
for key,(tag,name) in names.items():
    url=f'https://github.com/k2-fsa/sherpa-onnx/releases/download/{tag}/{name}'
    target=root/name
    if not target.exists():
        part=target.with_suffix('.part');urllib.request.urlretrieve(url,part);part.rename(target)
    h=hashlib.sha256()
    with target.open('rb') as f:
        while b:=f.read(1024*1024):h.update(b)
    digest=h.hexdigest()
    if pins.get(key) and pins[key]!=digest:raise RuntimeError(f'{key} archive no longer matches the source pin')
    hashes[key]=digest;provenance[key]={'url':url,'sha256':digest,'archive_bytes':target.stat().st_size,'verification':'source-pinned' if pins.get(key) else 'first-build audit digest; pin in source before repeated release'}
(assets/'voice-model-checksums.json').write_text(json.dumps(hashes,indent=2))
(qa/'voice-model-provenance.json').write_text(json.dumps(provenance,indent=2))
print(json.dumps(hashes,indent=2))
if not a.benchmark:raise SystemExit(0)
import numpy as np
import soundfile as sf
import sherpa_onnx
for key,(_,name) in names.items():
    folder=root/name.removesuffix('.tar.bz2')
    if not folder.exists():
        with tarfile.open(root/name) as archive:archive.extractall(root,filter='data')
tts_root=root/'kokoro-multi-lang-v1_0'
start=time.monotonic()
config=sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=str(tts_root/'model.onnx'),voices=str(tts_root/'voices.bin'),tokens=str(tts_root/'tokens.txt'),data_dir=str(tts_root/'espeak-ng-data'),lexicon=str(tts_root/'lexicon-us-en.txt'),lang='en-us'),num_threads=2,provider='cpu'),max_num_sentences=1)
tts=sherpa_onnx.OfflineTts(config);load=time.monotonic()-start
first=[];start=time.monotonic()
def callback(samples,*_):
    if not first:first.append(time.monotonic()-start)
    assert np.isfinite(samples).all()
    return 1
text="Hello. I am Rosalina, your private on device assistant. Your conversation stays with you."
audio=tts.generate(text,sid=3,speed=1.0,callback=callback)
elapsed=time.monotonic()-start
assert np.isfinite(audio.samples).all() and len(audio.samples)>audio.sample_rate
sf.write(qa/'kokoro-host-sample.wav',audio.samples,audio.sample_rate)
report={'scope':'Linux x86_64 host only; NOT Android or Samsung benchmark','tts':{'model':'Kokoro82M FP32','load_seconds':load,'first_chunk_seconds':first[0] if first else None,'generation_seconds':elapsed,'audio_seconds':len(audio.samples)/audio.sample_rate,'rtf':elapsed/(len(audio.samples)/audio.sample_rate)}}
del tts
stt_root=root/'sherpa-onnx-whisper-tiny.en'
recognizer=sherpa_onnx.OfflineRecognizer.from_whisper(encoder=str(stt_root/'tiny.en-encoder.int8.onnx'),decoder=str(stt_root/'tiny.en-decoder.int8.onnx'),tokens=str(stt_root/'tiny.en-tokens.txt'),num_threads=2,language='en',task='transcribe')
samples,rate=sf.read(qa/'kokoro-host-sample.wav',dtype='float32')
stream=recognizer.create_stream();stream.accept_waveform(rate,samples)
start=time.monotonic();recognizer.decode_stream(stream);elapsed=time.monotonic()-start
transcript=stream.result.text
assert transcript.strip(),'Whisper returned no transcription'
report['stt']={'model':'Whisper tiny.en int8','input':'Host Kokoro sample, NOT actual microphone input','transcript':transcript,'decode_seconds':elapsed,'audio_seconds':len(samples)/rate}
(qa/'voice-host-benchmark.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))

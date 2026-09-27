import hashlib,json,pathlib,subprocess,sys,struct,time
from PIL import Image,ImageDraw
out=pathlib.Path('motion-full-clip');out.mkdir(exist_ok=True)
models=[('wan.gguf','https://huggingface.co/QuantStack/Wan2.2-TI2V-5B-GGUF/resolve/main/Wan2.2-TI2V-5B-Q4_K_S.gguf','ab4195ecd022e57455672771d8ec14c2589efc9ddd6b96c3578fbb326797bdbb'),('umt5.gguf','https://huggingface.co/city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q4_K_S.gguf','4a3176f32fd70c0a335b4419fcbf8c86cc875e23498c0fc06f5b4aa0930889e0'),('taew2_2.safetensors','https://raw.githubusercontent.com/madebyollin/taehv/011dfc2112197741c540e0bdd5b7b67bcc930771/safetensors/taew2_2.safetensors','b84609b2a133d48434bd9636bfcb44bf05168dc436e2d3cecf26256faa1f5325')]
for name,url,sha in models:
 p=out/name
 subprocess.run(['curl','-fL','--retry','4','--retry-all-errors',url,'-o',str(p)],check=True)
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(4*1024*1024),b''):h.update(b)
 assert h.hexdigest()==sha,(name,h.hexdigest())
w=h=256;n=49;steps=12;fps=8
im=Image.new('RGB',(w,h),(35,62,88));d=ImageDraw.Draw(im)
d.ellipse((64,64,192,192),fill=(225,80,55));d.rectangle((50,192,210,216),fill=(95,145,75))
im.save(out/'reference.png');(out/'reference.rgb').write_bytes(im.tobytes())
(out/'prompt.txt').write_text('A red ball slowly bounces above a green platform, static camera.')
(out/'negative.txt').write_text('blurry, distortion, text, watermark, flicker, still frame')
args=[sys.argv[1],str(out/'wan.gguf'),str(out/'umt5.gguf'),str(out/'taew2_2.safetensors'),str(out/'prompt.txt'),str(out/'negative.txt'),str(out/'reference.rgb'),str(out/'frames.rvf'),str(w),str(h),str(n),str(steps),'42','4']
start=time.monotonic();report={'phone_tested':False,'profile':'default-6s-256-12steps','generated_frames':49,'export_frames':48,'fps':8,'width':w,'height':h,'steps':steps,'completed':False}
try:
 with (out/'native.log').open('w') as log:
  result=subprocess.run(['/usr/bin/time','-v',*args],stdout=log,stderr=subprocess.STDOUT,timeout=6600)
 if result.returncode:raise RuntimeError(f'Video worker exited {result.returncode}')
 raw=(out/'frames.rvf').read_bytes();magic,ow,oh,on,ofps=struct.unpack('<4sIIII',raw[:20]);assert (magic,ow,oh,on,ofps)==(b'RVF1',w,h,n,fps);assert len(raw)==20+w*h*n*3
 for i in [0,12,24,36,47]:Image.frombytes('RGB',(w,h),raw[20+i*w*h*3:20+(i+1)*w*h*3]).save(out/f'frame-{i:02}.png')
 subprocess.run(['ffmpeg','-y','-v','error','-f','rawvideo','-pixel_format','rgb24','-video_size',f'{w}x{h}','-framerate','8','-i','pipe:0','-frames:v','48','-c:v','libx264','-pix_fmt','yuv420p',str(out/'six-second-draft.mp4')],input=raw[20:],check=True)
 metadata=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration:stream=nb_frames,width,height,r_frame_rate','-of','json',str(out/'six-second-draft.mp4')]))
 assert abs(float(metadata['format']['duration'])-6.0)<.05;assert int(metadata['streams'][0]['nb_frames'])==48
 report.update(completed=True,mp4_metadata=metadata,first_equals_last=raw[20:20+w*h*3]==raw[20+47*w*h*3:20+48*w*h*3])
except Exception as e:
 report['error']=str(e);raise
finally:
 report['elapsed_seconds']=round(time.monotonic()-start,2)
 (out/'report.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
 print((out/'native.log').read_text()[-12000:] if (out/'native.log').exists() else '')

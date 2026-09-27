import hashlib,json,pathlib,subprocess,sys,urllib.request,struct,time,os
from PIL import Image
out=pathlib.Path('motion-probe');out.mkdir(exist_ok=True)
models=[('wan.gguf','https://huggingface.co/QuantStack/Wan2.2-TI2V-5B-GGUF/resolve/main/Wan2.2-TI2V-5B-Q4_K_S.gguf','ab4195ecd022e57455672771d8ec14c2589efc9ddd6b96c3578fbb326797bdbb'),('umt5.gguf','https://huggingface.co/city96/umt5-xxl-encoder-gguf/resolve/main/umt5-xxl-encoder-Q4_K_S.gguf','4a3176f32fd70c0a335b4419fcbf8c86cc875e23498c0fc06f5b4aa0930889e0')]
for name,url,expected in models:
 p=out/name
 if not p.exists():
  subprocess.run(['curl','-fL','--retry','4','--retry-all-errors',url,'-o',str(p)],check=True)
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(4*1024*1024),b''):h.update(b)
 assert h.hexdigest()==expected,(name,h.hexdigest())
tae=out/'taew2_2.safetensors'
subprocess.run(['curl','-fL','--retry','4','https://raw.githubusercontent.com/madebyollin/taehv/011dfc2112197741c540e0bdd5b7b67bcc930771/safetensors/taew2_2.safetensors','-o',str(tae)],check=True)
assert tae.stat().st_size>1000000
# A synthetic reference is only test input. The output must be real generated frames.
im=Image.new('RGB',(128,128),(35,62,88))
from PIL import ImageDraw
p=ImageDraw.Draw(im);p.ellipse((32,32,96,96),fill=(225,80,55));p.rectangle((25,96,105,108),fill=(95,145,75))
im.save(out/'reference.png');(out/'reference.rgb').write_bytes(im.tobytes())
(out/'prompt.txt').write_text('A red ball slowly bounces above a green platform, static camera.')
(out/'negative.txt').write_text('blurry, text, watermark, distortion')
exe=sys.argv[1];frames=int(os.environ.get('PROBE_FRAMES','5'));steps=int(os.environ.get('PROBE_STEPS','2'))
args=[exe,str(out/'wan.gguf'),str(out/'umt5.gguf'),str(tae),str(out/'prompt.txt'),str(out/'negative.txt'),str(out/'reference.rgb'),str(out/'test.rvf'),'128','128',str(frames),str(steps),'42','4']
start=time.time()
with (out/'native.log').open('w') as log:
 p=subprocess.run(args,stdout=log,stderr=subprocess.STDOUT,timeout=2700)
if p.returncode:
 print((out/'native.log').read_text()[-14000:]);raise SystemExit(p.returncode)
raw=(out/'test.rvf').read_bytes();magic,w,h,n,fps=struct.unpack('<4sIIII',raw[:20]);assert magic==b'RVF1' and n==frames and len(raw)==20+w*h*3*n
for i in [0,n//2,n-1]:Image.frombytes('RGB',(w,h),raw[20+i*w*h*3:20+(i+1)*w*h*3]).save(out/f'frame-{i}.png')
subprocess.run(['ffmpeg','-y','-v','error','-f','rawvideo','-pixel_format','rgb24','-video_size',f'{w}x{h}','-framerate',str(fps),'-i','pipe:0','-c:v','libx264','-pix_fmt','yuv420p',str(out/'probe.mp4')],input=raw[20:],check=True)
report={'real_diffusion_probe':True,'phone_tested':False,'width':w,'height':h,'generated_frames':n,'steps':steps,'elapsed_seconds':round(time.time()-start,1),'frame0_equals_last':raw[20:20+w*h*3]==raw[-w*h*3:],'tae_sha256':hashlib.sha256(tae.read_bytes()).hexdigest()}
(out/'report.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))

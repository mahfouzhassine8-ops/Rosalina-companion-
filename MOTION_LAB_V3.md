# Rosalina Motion Lab V3 RC1 — experimental local photo-to-video

Base: Image Lab V2 RC1 commit 37bb610977054fe20626acbdc3ba14483354d578.
Preserved snapshots: locked-image-lab-v2-rc1 and locked-local-chat-v1.2-device-passed.
The existing app/, lib/, studio/, and image-engine/ trees are unchanged and checked in CI.
This is a separate package, com.rosalina.motionlab. Keep the original apps installed.

## Actual flow
Models > download and import both video files. Choose gallery photo > describe motion > select 6, 8 or 10 seconds > Generate video > Play > Save or Share MP4.
No Qwen import is required. The source photo is copied privately, orientation-normalized, and fitted without cropping into the selected output shape. Prompts and images are not sent to a cloud service. There is no INTERNET permission, and Android backup is disabled.

## Honest first-candidate limits
- Real Wan2.2 TI2V-5B diffusion, not a static pan/zoom, crossfade, or frame loop.
- CPU-only experiment; GPU/NPU acceleration is NOT included or claimed.
- Draft output: 8 fps, 256x256 / 320x192 / 192x320. Not HD, not native-model 24 fps playback, not smooth interpolated video. Using 8 fps also slows the generated motion relative to 24 fps playback.
- 6s exports 48 generated frames, 8s exports 64, 10s exports 80. Wan generates one extra alignment frame; only that last frame is discarded. No generated frames are repeated to inflate duration.
- Start at 6s and 12 steps. Longer clips need more compute. Generation time on the phone is unmeasured and may be very long. A two-hour watchdog prevents unbounded runs.
- This is not an exact-edit or face-preservation guarantee. The model can change faces, details, and framing, or fail to follow the requested motion. No claim of Grok-quality parity.
- No audio, video extension, upscaling, interpolation, selective image editing, or cloud fallback.
- TAE reduces decoder memory but can reduce quality compared with the full Wan VAE.
- Both large model files are checksum-verified when imported. Browser originals may be deleted only after successful import. Do not clear app storage or uninstall unless ready to import again.
- Close the original Qwen and Image Lab processes before rendering so they do not hold additional models in RAM.
- UI rotation/folding uses application-scoped state. Rendering has a foreground notification, Stop control, severe-thermal abort, and a bounded wake lock.
- Native probe checks actual small-image/prompt inference on Linux; codec instrumentation checks MP4 encoding on Android with synthetic frames. Neither substitutes for a full phone render or establishes image quality.

## Models
Video: QuantStack/Wan2.2-TI2V-5B-GGUF, Wan2.2-TI2V-5B-Q4_K_S.gguf (~3.12 GB)
SHA256 ab4195ecd022e57455672771d8ec14c2589efc9ddd6b96c3578fbb326797bdbb
Prompt encoder: city96/umt5-xxl-encoder-gguf, umt5-xxl-encoder-Q4_K_S.gguf (~3.50 GB)
SHA256 4a3176f32fd70c0a335b4419fcbf8c86cc875e23498c0fc06f5b4aa0930889e0
Decoder: madebyollin/taehv commit 011dfc2112197741c540e0bdd5b7b67bcc930771, safetensors/taew2_2.safetensors, bundled with its license and a build-recorded SHA256.
Runtime: stable-diffusion.cpp 3f8527a46c54ecf4cb4ed6003da8e8982283c73c, pinned GGML submodule.

## Phone acceptance
Install alongside existing apps. Verify model import, one 6s render, preview, duration, gallery save, sharing, stop during model load and sampling, rotation/folding, background/return, heat handling, and a second render. Test 10s only after 6s succeeds. Do not call the candidate device-passed from CI success alone.

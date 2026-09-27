# Rosalina Image Lab V2 — first test candidate

## Preserved baseline

Working text build: 2b4dbd048484e67fcc745c30ce1622cb095d3519.
Snapshot branch: locked-local-chat-v1.2-device-passed.
Image branch: image-lab-v2. No changes to original app/ or lib/ source; CI enforces this.

This APK installs alongside the original as com.rosalina.imagelab. It does not replace, uninstall, change, or access the original app's data. The test has its own private model files. Import the existing Qwen download only when using Chat in this test app. The original remains available for rollback. Close the original app before an image render so it does not hold another Qwen model in RAM.

## First image

1. Install Rosalina Image Lab.
2. Models > Get image model; download the SD 1.5 Q4 file linked there (about 1.57 GB).
3. Models > Import image model; select that file, NOT Qwen.
4. Create > enter a scene > Generate image. Defaults: 512 square, Euler A, 12 steps, CFG 7.
5. Save to gallery writes Pictures/Rosalina. Share exports only the selected PNG.

Image model: https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/blob/main/stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf
SHA-256: b8944e9fe0b69b36ae1b5bb0185b3a7b8ef14347fe0fa9af6c64c4829022261f
Model license: CreativeML Open RAIL-M. Model weights are not redistributed in this APK.

## Implemented

- Text-to-image and whole-photo image-to-image with one reference, using real local diffusion.
- Prompt, negative prompt, 3 aspect ratios, seed, 4–30 steps, edit strength 0.1–0.9.
- Reference input is decoded with orientation handling and center-cropped to output aspect ratio.
- Native progress callbacks, cancel, preserved diagnostic log, model SHA-256 verification, atomic imports.
- Private result history, local PNG/parameter storage, gallery save and Android share sheet.
- Dark/purple UI polish, touch-friendly controls, insets, folding/rotation state retention.
- Chat using the original engine; optional Qwen prompt refinement; explicit image requests route to Create for review before rendering.
- Qwen is unloaded before image generation; image worker exits after each render.
- Foreground rendering notification, bounded wake lock, 30-minute candidate timeout and Android severe-thermal abort.

## Limitations (not hidden)

- CPU-only first image candidate. No claim of GPU/NPU acceleration or measured phone generation speed.
- This is not a Grok-quality/image-editing parity claim. SD 1.5 is the initial lightweight compatibility target.
- Edit is whole-image diffusion, not mask-based selective editing, reference-image understanding, or guaranteed identity/face preservation.
- Saved chat transcript is visible, but unloading Qwen starts a fresh inference context. Transcript replay is not implemented.
- No video generation, voice, vision-chat, upscaling, inpainting, or cloud inference.
- This APK has no INTERNET permission. Model downloads use an explicit browser action; user-initiated sharing uses Android.
- Both native render smoke paths are exercised on a Linux CPU in CI. Android installation, runtime launch, UI, heat and actual image quality still require on-device testing.
- Source snapshot is a preservation branch, not a claim that GitHub administrative branch-protection rules were installed.

## Reproducible engine versions

llama.cpp a97cce86a8addeb9f40cba7a261c94b1f0c576cb (unchanged).
stable-diffusion.cpp 3f8527a46c54ecf4cb4ed6003da8e8982283c73c with its pinned submodules.

The image worker is a separately executed, statically linked GGML process installed in nativeLibraryDir. It is packaged under a lib*.so filename for Android extraction but is a PIE executable, not a JNI library. Do not System.loadLibrary it. The worker only receives validated file paths and bounded parameters through ProcessBuilder, never a shell command.

## On-device acceptance

Keep the original working Rosalina installed. In Image Lab: import image model; generate square image; save; share; regenerate; edit reference; cancel while loading and while sampling; fold/unfold during generation; background and reopen; import Qwen and verify chat; render after chat and return to chat. A successful CI compile is not this acceptance test.

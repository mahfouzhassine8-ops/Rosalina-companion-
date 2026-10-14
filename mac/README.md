# Rosalina — Photo to Video (Mac Test v0.1)

This is a **separate experimental macOS app**. It does not change or replace Rosalina Android or the existing Mac voice app. Requires Apple Silicon with **at least 24 GB unified memory** and macOS 14 or newer.

## Download and open

1. Open the successful **Rosalina Mac Photo-to-Video Test** workflow run for branch work/rosalina-mac-image-to-video-prototype on GitHub.
2. Under Artifacts, download **Rosalina-Mac-Photo-to-Video-Test**. Unzip it, then unzip **Rosalina-Photo-to-Video-Mac-Test.zip**.
3. Move **Rosalina Video.app** to Applications (optional). This is an ad-hoc signed test app, NOT Apple notarized. On first launch, Control-click the app and choose Open; if necessary, use System Settings > Privacy & Security > Open Anyway after you verify it came from your own GitHub build. Never disable Gatekeeper globally.
4. Open Rosalina Video.app.

## First-time engine setup

1. Press **1 · Install Engine**. It opens a Terminal setup script. If this button fails, open Contents/Resources/InstallEngine.command inside the app via Finder.
2. The installer requires Homebrew from https://brew.sh. If Homebrew is missing, install it from its official site and repeat step 1.
3. The installer uses Homebrew to install Python 3.12 and ffmpeg. It creates an isolated Python virtual environment at ~/Library/Application Support/Rosalina Video/venv and installs rapid-mlx[video]==0.16.1.
4. Return to the app and press **2 · Start Local Engine**. On the first start, Rapid-MLX downloads the **LTX-2.3 MLX Q4 model (~22.8 GB)**. Leave at least 35 GB free for download and cache. The model is NOT bundled in the app.
5. The app says **Engine ready** once the locally served video API responds. If launch fails, click Engine Log. First model download may take time.

## Your first picture-to-video test

1. Choose a JPG or PNG photograph (HEIC also accepted).
2. Describe one subtle movement: "The person blinks twice and smiles naturally. The camera stays still."
3. Keep **Preserve the original appearance** checked, and reference strength near **0.85**.
4. Choose **1 second**, **512×512** for the first test.
5. Click **Generate Video**. Rosalina sends the photo ONLY to the engine on your own Mac, polls the render, previews the local MP4 and offers Save MP4.
6. Compare face, body, clothing and background to the original photo. Once verified, increase to 2 or 4 seconds.

## Honest limitations

- Offline rendering after installing dependencies and downloading model weights. No API key or cloud inference is required.
- The photo provides a conditioning reference, not a pixel-perfect lock. Faces, hands, clothing and backgrounds can drift.
- LTX-2.3 returns silent video-only MP4, not synchronized audio.
- The first frame and video content cannot be guaranteed to match the original image exactly.
- Local rendering can take minutes for a single second of generated video. M5 speed is NOT yet measured.
- 24 GB is the documented minimum; close other memory-heavy apps for first tests.
- One video job at a time.
- Stop Render terminates the engine when Rosalina started it; if another application launched the server, Rosalina only stops monitoring the job.
- The macOS GitHub compile is not equivalent to a real rendering test on your MacBook.
- Another process listening on port 8000 can prevent engine startup.

## Diagnostics

- Use the **Engine Log** button or open ~/Library/Application Support/Rosalina Video/engine.log.
- If a model download fails, check network and disk, then restart the local engine; the model cache should reuse existing downloaded files.
- If out of memory, restart with 1 second at 512×512 and quit other GPU apps.
- If the photo changes significantly, try simpler motion and reference strength 0.9–1.0.

## Source and engine

- Native macOS app implemented with SwiftUI, AppKit, AVKit and Foundation.
- Engine: Rapid-MLX using model ltx-2.3-mlx-q4.
- Engine documentation: https://rapidmlx.com/docs/models/families/ltx
- Source: mac/RosalinaVideo.swift, mac/Info.plist, mac/InstallEngine.command.
- Build workflow: .github/workflows/rosalina-mac-video.yml.
- All changes are isolated on feature branch work/rosalina-mac-image-to-video-prototype. Android files are untouched.

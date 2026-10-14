# Rosalina — Mac Photo-to-Video Recovery RC1 (v0.1.1)

**This is an isolated replacement for the experimental Mac test app.** It does not change Rosalina Android or other projects. The previous Apple Silicon Mac v0.1 is preserved on branch work/rosalina-mac-image-to-video-prototype.

## Why this candidate exists

On the user's 24 GB M5 Mac, both Rapid-MLX jobs generated an MP4 but showed black frames. The first 768×512/49-frame render took 445.5s, the second 512×768/97-frame render took 232.1s. Peak engine memory reported 28.94 GB and the macOS app crashed around job completion. Uploaded reference images were good. The *cause of the black frames remains unproven*: reduced video workload and memory controls are protective experiments, not a confirmed model fix.

## Install this Mac RC1

1. Download the latest successful GitHub Actions artifact, **Rosalina-Mac-Recovery-RC1**. Unzip the artifact and then unzip **Rosalina-Mac-Recovery-RC1.zip**.
2. **Quit the old Rosalina Video.app UI** (but preserve its engine temporary files and model cache). Move the extracted **Rosalina Video.app** into Applications, replacing the previous experimental Mac test app if you want. You may also keep it in a separate directory.
3. This native Apple Silicon test app is **ad-hoc signed, not notarized**. Control-click → Open after checking the origin. If needed, use macOS Settings → Privacy & Security → Open Anyway. Do not globally disable Gatekeeper.
4. Open Rosalina. It immediately attempts to copy existing finished engine videos into **~/Movies/Rosalina Videos/** without restarting AI or modifying the original files. Click **Recover Videos** to retry recovery; click **Saved Videos Folder** to open the results.

**Important:** Do NOT delete or reinstall the existing 22.8 GB model. Do NOT click Install Engine unless Rapid-MLX is genuinely missing. All previous Hugging Face cache data, local models and videos remain untouched.

## Recover first, render second

1. Press **Recover Videos** and then **Saved Videos Folder**. Existing completed files at *~/.../T/rapid-mlx-videos-*/video_*/output.mp4* are copied into a stable location, named after each video ID. If the temporary files have been removed by a restart/cleanup, they may no longer be recoverable.
2. For any new render, Rosalina writes a persistent **video-jobs.json** to ~/Library/Application Support/Rosalina Video. That file includes the job ID and known output path. The app can retry completed jobs after UI crashes, provided the engine still retains the job and the original exists.
3. Render output is **copied and verified on disk BEFORE any video playback**. A black or invalid result is not silently thrown away. Preview is manual in the Mac's external player via **Open MP4** (no embedded AVPlayer during heavy GPU work).
4. The app checks small samples of saved MP4 frames using the already-installed FFmpeg. If nearly all sampled pixels are black, Rosalina shows a prominent warning. This is an output diagnostic; it does not repair a broken model.

## Memory-safer first test

1. Recover your old videos before starting or stopping an engine.
2. If the app says Engine offline, click **2 · Start Local Engine**. This launch uses **--gpu-memory-utilization 0.75 --cache-memory-percent 0.05** to limit new allocations. The flags may cause an honest out-of-memory/HTTP 503 instead of a dangerous workload on a 24 GB Mac. There is no guarantee of a successful render. If you connect to an engine already running, these startup flags cannot be retroactively applied.
3. Choose a photo. Rosalina defaults to portrait **320×512**, landscape **512×320**, or square **384×384**, **1 second (25 frames at 24 fps)**. Your original test used higher resolutions and 49/97 frames.
4. Use a small, simple motion description, such as "Blink once and gently smile; keep the camera still." Keep appearance preservation checked.
5. Click **Generate Video** once; wait for the status. The first viable clip may take minutes. Do not start a second render concurrently.
6. If the result is black again, do not repeatedly rerun with the same settings. Save the engine log and one recovered MP4. A different inference engine or upstream numeric/render bug investigation may be required.

Selecting **2 or 4 seconds** or an HD size on Macs with under 32 GB RAM is gated behind an explicit higher-memory override. It remains experimental.

## Known constraints

- Tested in GitHub Actions for native arm64 **compilation and isolated fixture tests**, not a real-model run on the user's M5. Device verification is still necessary.
- The existing LTX-2.3 checkpoint is ~22.8 GB on disk, and its first-run Gemma 3 encoder cache may occupy additional storage.
- Video-only silent MP4, no synchronized audio.
- Preserve original photo as a conditioning reference as closely as possible; identity consistency is not pixel-perfect.
- 24 GB RAM is the published minimum for this checkpoint, and even a small render may exceed the configured Metal budget, fail, or generate black. This release aims to avoid losing successful outputs and expose failures clearly.
- The local server is only on 127.0.0.1:8000; no cloud inference or API key.
- The job journal survives **application UI crashes** but cannot recover an unsaved render when the engine's job data and temporary MP4 have both disappeared.
- If the engine server is already running, Restarting Rosalina.app alone does not change its memory flags. Recover everything first; then stop the old engine if necessary before starting the RC1-managed engine.
- **Never delete ~/Library/Application Support/Rosalina Video or ~/.cache/huggingface** to troubleshoot black output: that would remove the installed runtime, logs, job journal, or model weights.

## Files

- New persistent exports: **~/Movies/Rosalina Videos/**
- Existing legacy exports (if any): **~/Library/Application Support/Rosalina Video/Exports/**
- Recoverable Rapid-MLX originals: **$TMPDIR/rapid-mlx-videos-*/video_*/output.mp4**
- Durable journal: **~/Library/Application Support/Rosalina Video/video-jobs.json**
- Engine log (now appended rather than truncated): **~/Library/Application Support/Rosalina Video/engine.log**
- Native app: mac/RosalinaVideo.swift
- Resilience code: mac/RosalinaStorage.swift
- Tests: mac/Tests/RosalinaRecoveryTests.swift
- Mac CI: .github/workflows/rosalina-mac-video.yml

## If recovery returns no files

The engine may have already cleared its temporary folder. Manually check ~/Movies/Rosalina Recovered from your previous terminal recovery attempt. If the video exists there, RC1 does not delete it. Only use a new render if you have verified the previous output cannot be recovered.

**Test status:** a green GitHub build means the code compiled and fixture tests passed. It is not proof that LTX generates visible frames on this hardware. Report the result of one *low-memory* real-device test before making further inference changes.

# Rosalina Unified Candidate — preservation and acceptance ledger

## Protected rollback points

- Chat device-passed V1.2: `2b4dbd048484e67fcc745c30ce1622cb095d3519`, branch `rollback-unified-20260928-local-chat-v1.2`.
- Image V2 RC1: `37bb610977054fe20626acbdc3ba14483354d578`, branch `rollback-unified-20260928-image-v2-rc1`.
- Motion RC1: `1ecff42595d980c7644d1eaa45a9ca7fd4000804`, branch `rollback-unified-20260928-motion-rc1`.
- Motion RC5 source: `95e44a09c7aa2ccb2ad18fa7e62b746a318fb7f3`, branch `rollback-unified-20260928-motion-rc5`.

Integration branch: `rosalina-unified-v1-candidate`. No locked branch is changed. The new `com.rosalina.unified` package coexists with all old apps. Do not uninstall any old app. Existing private models cannot be read across Android app sandboxes: re-import downloaded model files into the new package. Downloads and old private copies are not deleted.

## Audit findings

1. Original `app/` and `lib/` remain byte-identical to device-passed Chat. The Qwen native library, backend extraction configuration and 8K native context are not rewritten. A bound `:chat` adapter isolates it from the UI and ONNX/diffusion engines. Bounded transcript context is replayed after an engine release; the entire saved transcript remains on disk.
2. Image V2 reads Process.inputStream while multiple cancellation paths destroy the process. Both Create and Edit now use a common file-redirected worker lifecycle. Only its owner tails logs and performs bounded destruction/reap. No screen closes native stdout.
3. Existing image thermal entry gate was already Severe, not Moderate. Physical touch cannot establish Android's reported thermal level. Fresh platform readings and separately timestamped thermal diagnostics replace ambiguous/stale UI state; Moderate does not block. Native and shared ticker paths stop at Severe.
4. Old arbitrary 2.5 GB and multi-engine allocation gates are not copied. The coordinator releases chat/speech before image/video work, reports actual platform RAM, and uses Android's low-memory signal instead of inventing a model-specific memory benchmark.
5. RC5 debug artifact certificate is `372061e007eb00919d475b6486ec4482a46ae336edbec1b29d1db3df1e79b362`. The prior RC1 reported signer was different. This is an install-lineage risk, not proof of the exact Samsung installer error. The old permanent-signing workflow generated a fresh key per execution and requires a separately recoverable private wrapping key. No old signer is assumed recoverable. Unified uses a new permanent, privately retained signer; CI artifacts remain unsigned until deliberately signed. Never generate a replacement release key in CI.
6. Source-derived worker patches preserve the existing diffusion algorithm and Wan TI2V path. Image sampling is gated by the pinned backend's `generating image:` log immediately before `sd->sample`. Native tensor counters are not overall generation progress. Percentages are stage-specific, and ETA remains calibrating until device measurements justify it.
7. The existing MP4 encoder/spec are copied byte-for-byte into the unified module, then wrapped. Output is independently validated for exact sample count, monotonic timestamps and expected duration. Codec test frames are synthetic test fixtures, NOT claimed Wan outputs.

## Voice and backend evaluation

- Kokoro 82M FP32 through pinned sherpa-onnx 1.13.8 is the mobile baseline. Speech is sentence-chunked while Qwen streams, in `:speech`; waveform finiteness/clipping is checked before playback. Whisper tiny.en int8 encoder/decoder provide local STT. No Android cloud recognition or system TTS fallback is hidden.
- Microphone input is explicit and foreground. Tapping Mic interrupts current work, silences speech, waits for cleanup and starts another utterance. Automatic acoustic/hands-free barge-in is not implemented or claimed; Samsung echo/AEC validation remains necessary.
- Chatterbox Turbo upstream is 350M parameters, distributed as Python/PyTorch, developed/tested on Debian/Python 3.11. Desktop quality is not an Android benchmark. No supported, validated ARM64 Android execution path was established here; it is not bundled or falsely presented as working. Upstream now also offers a 110M Nano option; it is an evaluation lead, not an extra shipping feature.
- CPU workers remain the default. Vulkan candidates are separately linked and choose a registered Vulkan device at runtime. Diagnostics distinguish requested selection from successful context initialization. Per-operation CPU fallbacks and true Samsung speed/memory remain unverified. Pre-sampling setup failures may fall back to CPU; cancellation, Severe thermal, or quarantined workers do not retry.
- Phone Safe 384x384 / 8 steps / 2 threads is a lighter candidate profile; Standard stays 512x512 / 12 steps. Host validation is not a phone-safety benchmark. Motion remains 6/8/10 seconds, 8 fps, 4n+1 model frames, original draft aspects. No smaller fast-video model has been validated; Wan is preserved.

## Verification vocabulary

Source/build, packaging, x86_64 emulator, host inference, and real Samsung acceptance are independent. All build reports must explicitly preserve those distinctions. A green build is not a local model run. A host image is not a Samsung image. Codec fixtures are not a Wan render. A QA-signer emulator update is not a permanent-signer Samsung update.

## Samsung acceptance still required

Actual Qwen response; Create result/preview/save/share; actual Edit transformation; a generated playable Wan MP4; microphone-to-Whisper-to-Qwen-to-Kokoro voice turn; tap interrupt; background/screen-off work; Stop/restart; fold/recreate without duplicate workers; Severe thermal cleanup; sustained memory/thermal measurements; CPU-versus-Vulkan comparison; permanent-signer update-over-update with preserved chat/models.

The package is **Unified Candidate**, never device-passed until these are observed. All old installations remain the rollback path.

Primary implementation references: Android foreground service types (developer.android.com/develop/background-work/services/fgs/service-types); pinned stable-diffusion.cpp image.cpp and video.cpp at 3f8527a; sherpa-onnx Kotlin APIs at v1.13.8; official Kokoro and Whisper model documentation; resemble-ai/chatterbox README (reviewed 2026-09-28).

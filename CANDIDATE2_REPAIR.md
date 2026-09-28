# Unified Candidate 2 — chat responsiveness and thermal-aware native work

## Input evidence and scope

The Samsung diagnostic supplied by the owner records Android 37, SM-F976U1, QTI SM8850, ARM64, roughly 15.8 GB physical RAM, CPU backend, a native worker PID, successful Wan/UMT5/decoder loading, about 6326.71 MB of parameter memory, and termination at Severe (3) while encoding the motion description after 59.114 seconds. Sampling remained 0/0. This is a real platform thermal stop before diffusion sampling, not evidence of a bad model import, out-of-memory exception, completed video, or a GPU attempt.

Rollback: `rollback-unified-candidate1-04de997` at `04de997b2ff3b3184d75e7600a5d1f529f552e27`. All original Chat/Image/Motion directories remain unchanged. Package remains `com.rosalina.unified`, version code 10002, for update-over-candidate-1 signing with the existing permanent certificate. No model format/path/receipt migration or deletion is introduced.

## Implemented changes

- Original Qwen native library, context, sampler, backend extraction and threads are retained. Bound engine importance is inherited explicitly. Text IPC is batched at 50 ms with immediate first output and a final flush. The UI samples state, appends changed text, preserves scroll position when browsing earlier text, windows long histories, and does not rewrite the full response or resubmit foreground notifications for each token. Transcript disk parsing is off Main; read snapshots are reused.
- The four-item blocking speech queue is replaced by a nonblocking bounded queue. Slow speech cannot hold up text generation. An extremely long queued spoken response is explicitly marked shortened rather than silently stopping text.
- Chat diagnostics measure model setup, first text, response wall time, emitted text pieces, characters, warm reuse and process PSS. Text pieces are not falsely called native tokens.
- Automatic image/video backend selection performs a small numerical matrix computation on the registered GPU before trying the unchanged model path. A compile flag or capability string is not treated as runtime proof. Model initialization can still fail; pre-sampling failures fall back to CPU. Timeout/cancellation, Severe heat and unresolved cleanup are not hidden as success.
- Owned image/video workers receive wall-time pacing before Severe: CPU 75/55/30 percent budget for Normal/Light/Moderate; GPU 100/85/65 percent. A high forecast headroom reading adopts the Moderate budget early. These are conservative candidate scheduling settings, not measured optimal phone benchmarks. Resolution, steps and output duration stay selected by the owner. A validated executable/parent/start-time identity gates pause/resume signals. Cancellation resumes a paused process before termination/reaping. Parent-death protection is retained.
- Thermal headroom is queried at most every 10 seconds, thermal state is refreshed while idle, and the Severe cutoff stays in place. No Thermal Guardian setting is manipulated.
- Per-job private diagnostics retain actual backend, stage wall times, minimum available RAM, peak worker RSS, initial/peak thermal state and outcome. GPU memory not exposed as process RSS is not misrepresented as measured RAM. Matching successful records are available for CPU/GPU comparisons; no actual Samsung comparison is claimed from CI.
- User-started Voice gains a local continuous conversation loop and acoustic interruption. Microphone pre-roll retains the start of an utterance; VAD detects speech onset, immediately requests speech termination, and waits for owned engine cleanup. Platform echo cancellation and actual output-route gating are used; unsafe/unknown routes fall back to tap-Mic interruption. A transcript self-echo check prevents echo from becoming a new command. Stop ends the session. This is candidate behavior requiring real acoustic testing, not a guarantee of speaker echo cancellation.

## Acceptance boundaries

New tests cover UTF-8 batching/no loss, duty-cycle policy, unchanged quality, speech onset/silence, unsafe echo routes, polite command routing, incremental UI view reuse, and verified child pause/resume. Existing preservation, IPC, Stop, import, codec, host-image, host-voice and update tests remain.

No claimed Samsung latency improvement, successful Wan MP4, reliable acoustic barge-in, GPU model compatibility, or permanent-signer update acceptance until the owner tests the APK. Chatterbox Turbo and an optional smaller video engine are not bundled by this repair. Their feasibility evaluation and real-device benchmarking remain separate from the fixes above; the UI contains no fake model choices. Kokoro/Whisper remain the actual speech models.

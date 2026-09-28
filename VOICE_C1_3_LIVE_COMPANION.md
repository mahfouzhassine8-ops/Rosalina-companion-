# Rosalina Unified — C1.3 Live Companion Polish

Protected base: `b453d9777cd88408fc3982882578cb11f5d49fd8` / C1.2 (10007).

C1.2 remains unchanged and is preserved as the rollback baseline. C1.3 is a refinement pass for the next live female-companion build.

## Device evidence

C1.2 proved the repaired voice path on the Samsung target:

- Kokoro synthesis: 2308 ms
- first audio: 2510 ms
- audio duration: 1943 ms
- Android route type: 2
- speech-process restarts: 0
- speech exit: none
- thermal state: Light

The remaining user-visible issue is perceived latency: visible text can arrive well before the safe full-chunk TTS synthesis has completed.

## C1.3 changes

- Version 10008 / `1.0-unified-adaptive-hybrid-c1.3`.
- Keeps the proven C1.2 PCM16 playback path and process-exit diagnostics.
- Lets Kokoro use a bounded 2–4 CPU threads based on the device's reported core count, improving short-burst synthesis without an unbounded thermal jump.
- Adds an eager first natural phrase target around 44 characters so Rosalina can begin preparing speech before the entire answer is complete.
- Uses a larger follow-on phrase target so replies do not fragment into tiny, unnatural pieces.
- Splits the final remainder at word boundaries rather than sending one oversized catch-all speech chunk.
- Records first visible text → first actual audio latency and spoken chunk count in diagnostics.
- Shows truthful preparation states such as preparing/starting voice before the first audio write.
- Keeps “Rosalina is speaking” tied to actual audio reaching Android.
- Replaces stale hard-coded build labeling with the real BuildConfig version.
- Renames the warm local conversation mode to Live Companion in user-facing copy.
- Disables the pitch slider while the protected PCM16 path intentionally holds pitch neutral, rather than leaving a control that appears active but is ignored.
- Updates settings/help copy to match the actual safe playback behavior.

## Audit corrections

This pass also corrects small product-state mismatches found during the audit:

- stale C1.1 version text in the main status panel;
- outdated Live Voice wording after the companion-mode naming change;
- misleading pitch-control wording while safe playback intentionally bypasses pitch manipulation;
- missing text-to-audio timing visibility in diagnostics.

No unrelated screen redesign is included.

## Preservation

Chat/Qwen native inference, Whisper, model imports, image generation, video generation, signing identity, render profiles, and C1.2 crash diagnostics are preserved.

Rollback:
`rollback-rosalina-c1.2-10007-locked`

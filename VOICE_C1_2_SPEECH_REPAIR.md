# Rosalina Unified — Voice C1.2 Speech Crash Isolation & Repair

Base preserved: `7add972976c0490820662b0b5350cf88987978ba` (adaptive-hybrid C1.1 / version 10006).

This branch is intentionally narrow. It does not redesign Chat, Whisper, Adaptive Live, image generation, video generation, model storage, or the approved UI.

## Device evidence that motivated this pass

The Samsung diagnostic showed approximately 8.1 GB available RAM, Light thermal state, verified Kokoro and Whisper imports, successful Live Voice warmup, successful transcription and Qwen response generation, followed by:

`Speech process exited · restarting once`

That makes the speech process itself the repair target rather than RAM pressure, model import, or general chat inference.

## C1.2 changes

- Version 10007 / `1.0-unified-adaptive-hybrid-c1.2`.
- Adds Android `ApplicationExitInfo` details when an isolated engine process exits unexpectedly.
- Replaces the fragile streaming float AudioTrack path with a conservative two-stage voice path:
  1. Kokoro synthesizes a short response chunk completely in the isolated speech process.
  2. Rosalina validates/DSP-processes the returned samples and plays them through PCM16 mono AudioTrack.
- Uses ordinary media playback attributes for the audible reply path.
- Defers Android PlaybackParams pitch manipulation in this repair candidate; voice profile pitch is reported as safe neutral playback until device output is proven.
- Emits speech diagnostics at synthesis start/completion, PCM16 conversion, AudioTrack initialization, play(), and first successful audio write.
- The UI may report “Rosalina is speaking” only after the first successful AudioTrack write.
- Existing one-time speech-process restart remains as a recovery path. A second failure reports the last speech stage and Android process-exit details.

## Acceptance on Samsung

A CI pass is not voice acceptance. On the target Samsung phone verify:

1. Speak a short phrase in Live Voice.
2. Confirm Whisper transcribes it and Qwen answers.
3. Confirm Rosalina is audibly heard through the expected device.
4. Copy diagnostics and verify a successful path includes `tts-synthesis-complete`, `pcm16-ready`, `audio-track-ready`, and `first-audio-written`.
5. Repeat with phone speaker and, separately, Bluetooth if desired.
6. Confirm Stop still stops playback; Live interruption may wait for the current short Kokoro synthesis chunk because C1.2 intentionally uses non-streaming synthesis to isolate the previous crash.

Rollback remains the exact 10006 source at `rollback-adaptive-hybrid-10006-7add972`.

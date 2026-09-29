# Rosalina Companion / Voice V3 — 10017 implementation candidate

Base: phone-proven 10015 `e212d17bb1904de55fe7b6cbea40443548cc73dc`. Checkpoint and plan branches remain unchanged.

## Implemented for validation
- Exactly Chat, Live Voice, Settings as primary destinations. Media model files are not deleted or activated.
- Explicit task/utterance-generation guarded presentation state driven by microphone, inference and playback events.
- Real AudioRecord mute/resume; buffered audio spanning mute is discarded.
- Speech Stop/cancellation covers both platform and isolated candidate; late callbacks cannot revive cancelled lips.
- Preserve the direct Android MEDIA TextToSpeech path while adding offline-voice selection and playback observations.
- Platform lips: bounded synthesis-PCM energy aligned to playback callbacks/clock. This is an estimate, NOT phoneme-accurate lip sync. No fabricated energy when callbacks are unavailable.
- Pocket TTS INT8 isolated local candidate, checksum-pinned private import, same-phrase A/B audition, explicit per-pack phone acceptance before promotion, thermal/memory/first-audio guards and compatibility fallback.
- No repeated current clause after playback already started. Text remains available if speech fails.
- Motion tiers reduce decoration before conversation; 20/15/10/6/0 target FPS, immediate reduction and delayed recovery.
- Original 10015 Qwen, Whisper, native engines, Models, PhoneCapability and 3.5 GB Live guard retained. No 10016 merge.
- Optional affectionate/playful non-explicit companion style; base prompt is unchanged when disabled.

## Incomplete — must not be advertised as delivered
- A flat reference sheet is not a layered body/face rig. Full walking, dimensional turning, authored talking gestures, and the complete exact visual expression library are NOT implemented by localized mesh deformation.
- Natural Pocket output is a candidate, not acceptance of controlled whispers, sighs, laughter, breath styles, emotional control, or the user's desired humanization quality.
- Host WAV synthesis, a new enum, or generic pitch/speed settings do not satisfy those missing requirements.
- Actual Samsung speaker sound, sustained 10–15 minute RAM/heat behavior, route changes and physical folding remain device acceptance.
- If the new voice cannot meet those requirements, keep the working fallback and report Voice V3 incomplete.

## Artwork transport and release provenance
The exact supplied flat reference has SHA-256 `3fd9526712304ab6c9411a4be90dbb7ed7e548d485215944b811694b2c272f40`. Runtime uses a 594x1244 hero crop with original pixels and covers obsolete baked-in navigation. It does not invent a new portrait or call upscaled 180x261 pixels HD.
The conversation image is not in the Git text tree. A release may add its exact bytes at `assets/avatar-v3/approved-reference` before signing, with a separate recorded asset-packaging step and hash. The complete downloadable source must include it. CI without this asset exercises the clean emergency fallback, not high-resolution visual acceptance. Never describe an asset-augmented APK as bit-identical to the raw CI APK.

## Tests
Keep existing application lifecycle, Live warmup rejection, model validation, codec, worker cleanup and avatar pixel regressions. Add pure event-generation, RMS-envelope, promotion/fallback and motion-budget tests. Add a DEBUG-ONLY TextToSpeechService fixture emitting synthetic PCM through the real Android framework to exercise playback events, cancellation and recovery; it is not a real human voice and is not packaged in release. Exercise actual AudioRecord mute/resume. Build the real 10015 source with the same disposable debug signer and test update to 10017 with private-data retention. These do not establish permanent-signer physical-phone installation.

## Phone acceptance order
1. Update over installed Rosalina; preserve user data and models. Verify Chat/read-aloud and Live using the protected compatibility voice first.
2. Verify real Mute, End, interruption, late-callback prevention and background/return.
3. Import the supplied exact Pocket candidate pack. Compare identical phrases with baseline; test sustained sessions and heat. Promote only after user confirmation for this exact pack.
4. Verify graceful candidate failure/latency/thermal fallback without duplicated speech.
5. Review artwork quality and incomplete animation/action inventory. Do not lock or call the full Pro scope finished while these remain missing.

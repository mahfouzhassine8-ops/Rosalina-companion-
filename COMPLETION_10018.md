# Rosalina 10018 — original-brief implementation candidate

Baseline: e212d17bb1904de55fe7b6cbea40443548cc73dc (phone-proven 10015).
Locked branch is not modified. No 10016 RAM-gate change, no new parent-death guard.

## Implemented source
Hidden three-destination drawer; full-usable-display Live scene and floating session controls;
text-first Chat without avatar initialization; shared event-owned Performance State;
actual capture Mute/Unmute; immediate interruption invalidation; protected direct Android TTS;
PCM-derived mouth articulation estimates; bounded thermal motion tiers; isolated Chatterbox
candidate; explicit phone-gated A/B approval; optional, default-off HTTPS online speech with
Android Keystore credential encryption and current-clause-only disclosure/fallback.

The character renderer addresses 25 separate original-reference cutout layers and twelve
visual expression profiles. Short walking/turning actions use the supplied front/back reference
views at scene distance. It is NOT a volumetric 3-D character, and those small source views are
NOT HD full-body assets. Physical-phone visual quality remains an acceptance requirement.
The small arm region covered by the reference-sheet speech bubble is reconstructed from
adjoining source skin tones. This is recorded in the layer manifest; no opaque cover is used.

## Reuse rather than regenerate
10017 selected microphone, event ownership, cancellation, PCM and fixture infrastructure was
compared to 10015. Its permanent rail, card renderer and Pocket voice were replaced.
Chatterbox host evaluation: run 36539019432, commit 54216502fdef64c078f9756e3fa677f4e3372ec5.
Verified unchanged phone pack: run 36544053168, SHA-256
4dbeaa27de61f943edd1df97bc9d4eec9b92c5a65054eb0a263f25f4d469bc64.
Existing compiler/render kits are reused. The only new dependency preparation is the unique
Java bridge from run 36548601606; it does not replace sherpa's protected libonnxruntime.so.

## Artwork provenance and reproducibility
Build the rig with tools/build_avatar_layers.py using the exact approved reference SHA-256
3fd9526712304ab6c9411a4be90dbb7ed7e548d485215944b811694b2c272f40.
Original artwork is a conversation-supplied asset, not a public download. As in the verified
10017 transport, production art can be appended to the unsigned artifact before signing,
with all original entry bytes checked unchanged. Such an APK is not byte-identical to raw CI.
The complete source deliverable includes the original, built assets, manifest and recipe.
Native Android Canvas/Robolectric checks use the real production renderer and original layers;
CI without those conversation assets tests the explicit recovery path instead.

## Gates still required
Compilation or a generated waveform never establishes voice naturalness, whispers, emotional
control quality, acceptable Fold latency, 10–15 minute thermal stability, Bluetooth/call behavior,
or physical update-over-install acceptance. Do not mark those passed without evidence.
Online provider failure paths and configured live-provider quality also require validation;
no external provider request is made without explicit user configuration/consent.

# Rosalina Live Avatar C1

Base: Adaptive/Hybrid Live + Bluetooth commit 7add972976c0490820662b0b5350cf88987978ba, CI run #22 fully green.

## Locked visual
The avatar uses the user's approved brunette anime companion reference in the red-and-gold dress. The bundled phone asset is a resized copy for UI efficiency; the design is not replaced or reinterpreted.

## C1 runtime
- Chat/Voice surface shows the live avatar by default and can be disabled in Settings.
- State machine: Idle, Listening, Thinking, Speaking, Interrupted.
- Idle/listening/thinking/interruption have lightweight breathing, sway and reaction motion.
- Speaking motion is driven by real Voice V2 PCM energy emitted by SpeechService at a bounded cadence.
- The avatar renderer does not change Qwen, Whisper, Kokoro, Bluetooth routing, Hybrid Live, model imports, generation engines, or protected apps.
- Rendering is lightweight Canvas animation (20 fps idle / about 30 fps active) and requires no camera permission.
- The interface is intentionally rig-ready: a future layered/3D skeletal renderer can replace the single-image adapter without changing Live Voice state semantics.

## Truthful limitation
C1 does not claim a single JPEG has independent skeletal joints or perfect viseme lip shapes. It establishes the device-safe live avatar runtime and real audio-reactive motion on top of the newest passed voice/Bluetooth baseline.

# Rosalina Live Voice — Candidate 1

Base: Voice V2.1 version 10004 / commit 25a0334fcb08b4b45b062dc3df33ccf0001c7705.

## Goal
Make Rosalina feel like a live conversation rather than a text chatbot that happens to read replies aloud, while remaining fully local on the Samsung ARM64 target.

## Candidate 1 architecture
Live Voice uses three isolated warm local processes during an active session:

- :chat — preserved Qwen local chat engine
- :listen — dedicated warm Whisper tiny.en recognizer
- :speech — dedicated warm Kokoro / Voice V2 renderer

The microphone stays active while Rosalina speaks. Acoustic echo cancellation / safe-route gating controls whether speech can trigger a barge-in. A barge-in sends a non-destructive speech interrupt, cancels the current Qwen response, and immediately returns focus to listening. The TTS process itself is not killed merely to interrupt one utterance.

Whisper and TTS no longer unload each other every turn in Live mode. Classic Voice V2 remains available in Settings and preserves the older sequential speech-process behavior.

## Latency changes
- First user audio can be captured while the three local engines warm.
- Live endpoint timing defaults to 820 ms of quiet and is user-adjustable from 550–1400 ms.
- Streaming Qwen text can be spoken at natural clause boundaries instead of waiting for a full long sentence / paragraph.
- Qwen, Whisper, and TTS stay warm for the live session when memory allows.
- Live mode requires at least 3.5 GB Android-reported available RAM before warming all three engines; otherwise the app asks the user to use Classic Voice rather than risking memory pressure.

## Truthful scope
This is a local cascaded full-duplex coordinator. It is NOT an audio-native speech-to-speech foundation model and must never be reported as one.

Moshi is a genuine full-duplex speech model, but its supported on-device accelerated implementation is MLX on Apple hardware. It is not being bundled or falsely represented as Android-ready.

Future backend work can replace the current listening/speaking engines behind this coordinator if an Android ARM64 speech-to-speech runtime proves viable. Candidate 1 deliberately avoids cloud inference, INTERNET permission, remote TTS, and API keys.

## Acceptance
CI can verify lifecycle, update behavior, tests, packaging, and preservation. Only the Samsung device can establish:
- actual first-response latency,
- interruption quality,
- acoustic echo behavior,
- natural turn-taking,
- whether three warm engines fit comfortably in sustained use,
- whether Voice V2 expression still sounds natural during Live mode.

# Rosalina Adaptive / Hybrid Live — Candidate 1.1

Base: Live Voice Candidate 1 commit afe50db07dad424d19f7ff9e74cc208a1ccdd8af.

## Immediate adaptive behavior
- Adaptive Live learning is local and enabled by default.
- Live spoken reply length starts at a conversational cap and changes gradually from observed barge-in behavior.
- Repeated interruptions shorten later Live replies; completed turns gradually allow longer replies again.
- Learned state is stored in Rosalina preferences, survives normal upgrades, and can be reset in Settings.
- Text Chat keeps its configured normal response limit.

## Internet / hybrid readiness
- INTERNET and ACCESS_NETWORK_STATE are now declared.
- Online enhancements are user-switchable and can be disabled at any time.
- Local Qwen + Whisper + Voice V2 remains the fallback and does not depend on internet.
- No OpenAI or other provider API key is embedded in the APK.
- This candidate does not silently upload conversations merely because internet permission exists.
- A production remote speech-to-speech provider should use short-lived client credentials from a trusted broker/server; a permanent project key must not be stored in the APK.

## Speech lifecycle repair
- Intentional Stop/shutdown is distinguished from an unexpected service death.
- An intentional SpeechService disconnect closes pending RPC work as cancellation rather than recording a false process-crash error.
- Speech cancellation while the task is stopping no longer writes a misleading Voice unavailable stack trace.

## Truthful scope
Internet permission by itself does not make local inference faster. Immediate improvement in this build comes from local adaptation and lifecycle repair. Remote offload requires a configured trusted provider/session broker and is not falsely represented as active until that exists.

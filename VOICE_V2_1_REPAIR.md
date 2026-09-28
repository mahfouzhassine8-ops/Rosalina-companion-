# Voice V2.1 — Samsung diagnostic repair

Device evidence from SM-F976U1 / Android 37 showed three separate problems in version 10003:

1. Qwen was successfully preloaded but a subsequent prompt returned no text while the engine reported ModelReady.
2. Wan Vulkan reached model context initialization and motion-description encoding, then Android reported Severe thermal state; cancellation could leave the native process unreaped within the old 1.8-second deadline.
3. CPU Edit could complete, but the coarse 30% duty budget stretched a 384x384 / 8-step edit to roughly twelve minutes.

Repairs:
- Chat now uses the proven Local AI V1.2 lifecycle: load model -> set the configured system prompt -> user prompts. Persisted transcript remains visible/on disk but is no longer inserted into the native system prompt during prewarm/cold restoration.
- A zero-text warm response is not accepted. Chat performs one clean unload/reload/system-prompt setup and retries the exact prompt once, then reports failure if the retry is still empty.
- Native workers claim the ProcessBuilder PID immediately, verify ownership, resume before termination, send verified SIGTERM/SIGKILL, and wait up to eight seconds for driver/kernel unwind before quarantine. Parent-death protection remains unchanged.
- Thermal work budgets are gradual instead of collapsing every >=0.85 headroom reading to 30% CPU. Severe remains a hard stop. Quality, resolution, steps and duration are unchanged.
- Voice V2 expressive controls are untouched by this repair.

Wan2.2 5B is still not declared phone-viable: the real Samsung traces show T5 prompt encoding around 29–30 seconds and repeated Severe thermal stops before sampling. A smaller Fast Video engine remains a separate compatibility/benchmark task rather than being falsely claimed by this patch.

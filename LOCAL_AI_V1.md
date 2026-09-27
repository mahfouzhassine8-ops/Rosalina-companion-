# Rosalina Local AI V1

Experimental Android branch for fully on-device GGUF text inference.

- arm64-v8a
- 8K context
- llama.cpp JNI backend
- model import into app-private storage
- streaming chat
- editable system prompt
- adjustable max output tokens
- local transcript persistence
- no cloud inference/API key

First model target: `Qwen3.5-9B-abliterated-Q4_K_M.gguf`.

The model is not bundled into the APK. Import it on first launch.

Pinned llama.cpp: `a97cce86a8addeb9f40cba7a261c94b1f0c576cb`.

Vision, image generation, and photo-to-video remain follow-on stages after text inference passes on-device.

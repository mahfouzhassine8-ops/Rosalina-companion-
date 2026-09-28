# Rosalina Device Acceleration & Thermal Truth C1

Base: locked Companion Shell C1 10009 commit a988169f4bc7e9537ab72dce81636f55e9978a0b.

- Thermal source is Android PowerManager.currentThermalStatus + getThermalHeadroom(10), not Samsung Good Guardians / Thermal Guardian.
- Severe (3) throttles; Critical (4), Emergency (5), and Shutdown (6) stop native rendering.
- Cool workers may use full duty. Animate uses up to four CPU threads when cool, three at Light, two at Moderate/Severe.
- Diagnostics attempt worker CPU share, CPU frequency, Qualcomm KGSL GPU busy/frequency, RSS and exposed thermal zones.
- CPU retry after Vulkan failure is restricted to backend/device/allocation failures; thermal/cancel/stop failures do not repeat the full pipeline.
- The unified Vulkan build already enables SD_VULKAN/GGML_VULKAN. A probe is capability evidence only, not Wan performance proof.

Chat, Hybrid Live, Bluetooth, navigation, models and protected older apps remain preservation scope.

Avatar repair:
- The prior drawable JPEG was observed blank in emulator/device UI despite successful packaging.
- C1 stores a verified compact baseline JPEG as two text-safe base64 assets and decodes those exact bytes at runtime.
- Android instrumentation now asserts the bundled avatar actually decodes before the candidate can pass.

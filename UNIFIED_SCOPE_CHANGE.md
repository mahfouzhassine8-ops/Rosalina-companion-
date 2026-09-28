# Scope confirmation — 2026-09-28

The user's final instruction is to **keep parent-death protection**. This supersedes the temporary removal request.

Both unified CPU and Vulkan workers link the original, unchanged `motion-engine/android_lifecycle.cpp`. It installs `PR_SET_PDEATHSIG` before native generation and checks the parent identity across installation. Explicit Stop, cancellation, bounded termination/reap, foreground-service cleanup and wake-lock release remain in scope.

The legacy locked sources are not modified. Host and emulator lifecycle tests do not establish Samsung Force Stop behavior; real-device acceptance is still required.

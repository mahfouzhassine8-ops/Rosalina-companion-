# User scope correction — 2026-09-28

> Do not add parent death protection.

This overrides the corresponding requirement in the original consolidation brief.

The unified CPU and Vulkan workers do **not** link `motion-engine/android_lifecycle.cpp`, do not install `PR_SET_PDEATHSIG`, and do not introduce an equivalent parent-death watchdog. The old locked source file remains unmodified for rollback/preservation only.

Explicit Stop, cancellation, process termination/reap, service cleanup, and wake-lock release remain in scope. The new package does not promise parent-death-triggered child cleanup. Force-stop/background lifecycle behavior still requires real-device validation; it is not inferred from an omitted guard.

`python3 tools/test_no_parent_guard.py` checks the generated unified worker sources and preserves the explicit cleanup path.

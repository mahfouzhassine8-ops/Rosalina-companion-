# Candidate 2 reconciliation and validation scope

The branch received concurrent work. This revision retains c3f643d55b6c8ef3e6d072e31945a9f20412c08a's coordinated Chat preparation, incremental/windowed Chat UI, atomic voice request handling, microphone cleanup, actual GPU compute probe, render measurements and permanent update lineage. No force push or locked-branch modification was used.

The additional changes move IPC reply handling off Main; require known output routes and an enabled echo canceller for acoustic speaker interruption; preserve manual-interruption silence completion; and cap microphone input including pre-roll at Whisper's exact 480000-sample limit. Unknown routes use manual interruption. A 350 ms speaker-tail guard and transcript echo check are mitigations, not proven Samsung echo rejection.

CPU video launches with one thread. CPU workers retain conservative Normal/Light/Moderate work budgets and the Severe cutoff. GPU workers are NOT paused to simulate a GPU duty cycle: stopping a host process cannot establish control over already-submitted GPU commands. GPU compatibility, memory consumption and temperature still need phone measurement. Shared thermal readings are cached for at most 500 ms; headroom requests remain at least ten seconds apart. Selected frames, dimensions and sampling steps are unchanged.

The supplied Animate diagnostic proves a CPU thermal stop at 59.114 seconds during prompt encoding, before sampling. It does not show a missing model, an out-of-memory exception or a completed video. These changes address that workload path without disabling thermal safety or parent-death protection.

Package com.rosalina.unified and permanent signing identity are retained; code 10002 updates code 10001. Do not uninstall, clear app data or re-import models for this update. Model registry and private paths are unchanged. Actual update acceptance remains a device test, separate from signature verification and the QA-signer emulator update.

Chatterbox Turbo, a smaller optional Fast Video model, and a complete Samsung CPU/GPU/voice benchmark campaign are not completed in this repair. No placeholder engine, synthetic video substitute or device-passed claim is introduced. Validate the APK, test XML and actual phone outputs separately. Tests added in source are not test results until executed.

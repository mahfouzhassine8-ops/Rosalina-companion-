# Rosalina Companion Shell C1

Base: green Live Avatar C1 10008 commit f565eb7d31465e78e02a8619e4d1faf9f4667a4b.

Approved information architecture:
1. Companion — full-screen Rosalina with Chat and Live modes only.
2. Photo — Create and Edit in one workspace.
3. Animate — dedicated motion workspace.
4. Settings & Models — one consolidated destination.

Navigation:
- persistent compact left rail
- expandable left menu with labels
- selecting a destination collapses the expanded menu
- active engine work must be stopped before changing sections

Repairs included:
- LiveAvatar rendering uses direct decoded drawable + canvas destination drawing instead of the prior transform path that rendered blank on Samsung.
- visible chat strips <think>...</think> blocks while preserving the assistant answer.
- existing Hybrid Live, Bluetooth routing, Adaptive Live learning, speech recovery, imports, native image/video engines and protected projects are preserved.
- versionCode 10009 provides a true forward install over device-tested 10008.

C1 keeps the current image-based real-time avatar adapter. Full independent skeletal joints/visemes remain a later rig layer and are not falsely claimed here.

# Rosalina Private Mac Accelerator — v0.1

Branch: `rosalina-mac-accelerator-v0.1`

This is a separate macOS project. It does **not** modify the locked Rosalina Android C1.3 source.

## Purpose

The M5 MacBook Pro becomes an optional private accelerator on the user's local network while Rosalina remains the primary phone app.

v0.1 deliberately builds the secure transport and hardware proof **before** any personal photo or model inference is enabled.

## v0.1 features

- Native SwiftUI macOS application: `Rosalina Accelerator.app`.
- Apple Silicon / arm64 build.
- Bonjour local-network discovery using `_rosalina-accel._tcp`.
- Persistent server identity stored in macOS Keychain.
- One-time 10-digit pairing code with Curve25519 key agreement.
- After pairing, request bodies use ChaChaPoly authenticated encryption.
- Paired client secrets are stored in Keychain.
- Replay protection for recent request IDs.
- Real Metal compute probe on the Mac GPU.
- Hardware profile: Mac model, macOS version, CPU count, physical memory, Metal GPU, recommended GPU working set, unified-memory capability.
- Private model workspace under the user's Application Support directory.
- Create/Edit/Animate are explicitly rejected in v0.1; no personal photo is accepted yet.
- App UI truthfully shows which capabilities are ready vs waiting for the model backend.

## Why inference is disabled in v0.1

The earlier phone Edit pipeline did not preserve the source photo reliably. Moving that same pipeline to the M5 would only make a bad result faster.

The Mac inference phase will be accepted only after a reference-photo test proves:

1. the original subject remains recognizable;
2. composition and untouched regions are preserved;
3. only the requested edit changes;
4. output quality is materially better than the phone candidate.

After image fidelity passes, add Animate/Wan separately.

## Build

GitHub Actions workflow: `.github/workflows/build-mac-accelerator.yml`

The CI artifact is an **ad-hoc signed** arm64 app bundle for development/testing. It is not Apple-notarized yet. A normal public-distribution build would later require an Apple Developer certificate and notarization.

## Planned Android integration

A later Rosalina Android branch will add:

- Bonjour discovery;
- pairing client matching the Mac protocol;
- Curve25519/ChaChaPoly encrypted requests;
- accelerator availability state;
- explicit routing for Create/Edit/Animate;
- automatic local-phone fallback when the Mac is unavailable;
- no change to local Chat, Whisper, Kokoro, companion memory, or conversation history.

C1.3 remains the protected phone baseline while the Mac project is proven independently.

# Rosalina signing identity

The existing Rosalina permanent signing key was recovered from the user's private Library backup `Rosalina-Motion-Signing-Key-KEEP-PRIVATE.zip` on 2026-09-28. It was successfully opened with its stored credentials and its certificate exported for verification. No new disposable release key was generated.

Permanent certificate SHA-256:
`1cf0e96fa437475fb7aa97453eb958819214afebbec887e299016a642eea8d9b`

Unified uses the separate package `com.rosalina.unified`, so it does not replace, uninstall, or migrate any existing Chat/Image/Motion installation. Imported models in other app sandboxes require explicit re-import from the user's downloaded files. Old private copies and downloads are untouched.

The key and passwords must never be added to source or public workflow artifacts. CI produces an explicitly UNSIGNED ARM64 package. A release custodian signs it with the recovered permanent key and verifies the exact certificate above. This repository does not claim Actions secrets are configured. Subsequent release signing must fail rather than fall back to a new/debug key.

Package/signature verification and a QA-signed emulator update are separate from an actual permanent-signer Samsung update. Real-device update-over-update acceptance remains required.

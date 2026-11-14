#!/usr/bin/env python3
"""In-place Windows Mode upgrade identity gate for disposable QubeVM checkout.

Source preparation is not proof of WinJIT execution or permission to ship an APK.
The final release workflow must still require WinJIT/QEMU guest boot verification.
No Windows disk, UEFI VARS, Android data, or existing installed package is touched.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re

PACKAGE = "com.fold.windowsmode.research"
CERT_SHA256 = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
PROTECTED_VERSION_CODE = 70114
CANDIDATE_VERSION_CODE = 70115
CANDIDATE_VERSION_NAME = "7.1.15-winjit-candidate"


def replace_one(text: str, before: str, after: str, desc: str) -> str:
    count = text.count(before)
    if count != 1:
        raise ValueError(f"{desc}: expected exactly one known anchor, got {count}")
    return text.replace(before, after, 1)


def stage_disposable_source(source: Path) -> dict[str, str]:
    """Build-identity edits only; source is a disposable upstream checkout."""
    app = source / "qube-android-arm"
    gradle = app / "build.gradle"
    manifest = app / "src/main/AndroidManifest.xml"
    if not (gradle.is_file() and manifest.is_file()):
        raise ValueError("Not the expected pinned QubeVM source tree")
    gradle_new = replace_one(
        gradle.read_text(),
        'applicationId = "com.qubevm.arm"',
        f'applicationId = "{PACKAGE}"',
        "application ID",
    )
    manifest_new = replace_one(
        manifest.read_text(),
        'android:versionCode="70104"',
        f'android:versionCode="{CANDIDATE_VERSION_CODE}"',
        "version code",
    )
    manifest_new = replace_one(
        manifest_new,
        'android:versionName="7.1.4-arm"',
        f'android:versionName="{CANDIDATE_VERSION_NAME}"',
        "version name",
    )
    # Both source anchors verified before any file is modified.
    gradle.write_text(gradle_new)
    manifest.write_text(manifest_new)
    return {"package": PACKAGE, "version_code": str(CANDIDATE_VERSION_CODE)}


def check_signed_apk_report(badging: str, signatures: str) -> None:
    """Fail closed on incorrect Android package identity, version or signer."""
    match = re.search(
        r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",
        badging,
    )
    if not match:
        raise ValueError("Missing actual aapt APK package/version output")
    package, code, name = match.groups()
    if (package != PACKAGE or int(code) != CANDIDATE_VERSION_CODE or
            name != CANDIDATE_VERSION_NAME):
        raise ValueError(
            f"Not an in-place {CANDIDATE_VERSION_CODE} Windows Mode candidate: "
            f"package={package!r}, version={code}, label={name!r}"
        )
    digest = re.search(
        r"(?:V3\.0 Signer: )?certificate SHA-256 digest:\s*([a-fA-F0-9]{64})",
        signatures,
        flags=re.IGNORECASE,
    )
    if not digest or digest.group(1).lower() != CERT_SHA256:
        raise ValueError("Candidate signer differs from protected Windows Mode 7.1.14")
    if not re.search(r"Verified using v3 scheme .*:\s*true", signatures):
        raise ValueError("Android v3 APK signature verification did not pass")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="action", required=True)
    stage = sub.add_parser("stage")
    stage.add_argument("--disposable-upstream", type=Path, required=True)
    verify = sub.add_parser("verify")
    verify.add_argument("--aapt-output", type=Path, required=True)
    verify.add_argument("--apksigner-output", type=Path, required=True)
    args = p.parse_args()
    if args.action == "stage":
        print(stage_disposable_source(args.disposable_upstream))
    else:
        check_signed_apk_report(
            args.aapt_output.read_text(), args.apksigner_output.read_text()
        )
        print("PASS: exact Android in-place update package, version and signer")

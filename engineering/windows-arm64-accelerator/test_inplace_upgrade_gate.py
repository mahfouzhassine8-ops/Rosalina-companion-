#!/usr/bin/env python3
"""Identity regression tests; deliberately creates no APK or Windows disk."""
import tempfile
from pathlib import Path
from inplace_upgrade_gate import (
    PACKAGE, CERT_SHA256, CANDIDATE_VERSION_CODE, CANDIDATE_VERSION_NAME,
    stage_disposable_source, check_signed_apk_report,
)

with tempfile.TemporaryDirectory() as t:
    app = Path(t) / "qube-android-arm"
    manifest = app / "src/main/AndroidManifest.xml"
    manifest.parent.mkdir(parents=True)
    gradle = app / "build.gradle"
    gradle.write_text('applicationId = "com.qubevm.arm"\nDO_NOT_TOUCH=1\n')
    manifest.write_text(
        '<manifest android:versionCode="70104" android:versionName="7.1.4-arm"/>'
    )
    data_before = "DO_NOT_TOUCH=1"
    stage_disposable_source(Path(t))
    assert f'applicationId = "{PACKAGE}"' in gradle.read_text()
    assert data_before in gradle.read_text()
    assert f'android:versionCode="{CANDIDATE_VERSION_CODE}"' in manifest.read_text()
    assert f'android:versionName="{CANDIDATE_VERSION_NAME}"' in manifest.read_text()

base_badging = (
    f"package: name='{PACKAGE}' versionCode='{CANDIDATE_VERSION_CODE}' "
    f"versionName='{CANDIDATE_VERSION_NAME}'"
)
signed = (
    "Verified using v3 scheme (APK Signature Scheme v3): true\n"
    f"V3.0 Signer: certificate SHA-256 digest: {CERT_SHA256}\n"
)
check_signed_apk_report(base_badging, signed)

def must_reject(badging, signatures):
    try:
        check_signed_apk_report(badging, signatures)
    except ValueError:
        return
    raise AssertionError("Gate accepted wrong APK identity or signer")

# Regression from accidentally shipping a second Windows Mode app.
must_reject(base_badging.replace(PACKAGE, "com.fold.windowsmode.sourcenative"), signed)
must_reject(base_badging.replace("70115", "70114"), signed)
must_reject(base_badging, signed.replace(CERT_SHA256, "0" * 64))
must_reject(base_badging, signed.replace("v3): true", "v3): false"))
print("PASS: upgrade ID matches, version increments, signer matches; duplicate app rejected")

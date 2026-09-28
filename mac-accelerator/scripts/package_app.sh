#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
APP="$DIST/Rosalina Accelerator.app"
BIN_PATH="$(swift build --package-path "$ROOT" -c release --arch arm64 --show-bin-path)/RosalinaAccelerator"

rm -rf "$DIST"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_PATH" "$APP/Contents/MacOS/RosalinaAccelerator"
cp "$ROOT/Resources/Info.plist" "$APP/Contents/Info.plist"

printf 'APPL????' > "$APP/Contents/PkgInfo"
chmod +x "$APP/Contents/MacOS/RosalinaAccelerator"

codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict "$APP"

file "$APP/Contents/MacOS/RosalinaAccelerator"
lipo -info "$APP/Contents/MacOS/RosalinaAccelerator"

(
  cd "$DIST"
  ditto -c -k --sequesterRsrc --keepParent "Rosalina Accelerator.app" "Rosalina-Accelerator-v0.1.2-arm64.zip"
  shasum -a 256 "Rosalina-Accelerator-v0.1.2-arm64.zip" > SHA256SUMS.txt
)

#!/bin/zsh
# Rosalina Mac Image-to-Video: user-visible local AI setup.
# Installs dependencies only into Homebrew and a Rosalina-specific Python venv.
set -euo pipefail

echo ""
echo "======================================================"
echo " Rosalina Photo-to-Video — Local Mac Engine Setup"
echo "======================================================"
echo ""
echo "This installs Python 3.12, ffmpeg, and Rapid-MLX in:"
echo "  ~/Library/Application Support/Rosalina Video/venv"
echo ""
echo "The first engine start will download the LTX-2.3 model"
echo "(approximately 22.8 GB). Keep at least 35 GB free."
echo "No photos or generated videos will be uploaded."
echo ""

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "ERROR: This preview requires an Apple Silicon Mac."
  read -k 1 "?Press a key to close. " || true
  exit 1
fi

if [[ -x /opt/homebrew/bin/brew ]]; then
  BREW=/opt/homebrew/bin/brew
elif command -v brew >/dev/null 2>&1; then
  BREW="$(command -v brew)"
else
  echo "Homebrew is not installed yet."
  echo "Install Homebrew from https://brew.sh/"
  echo "Then click Install Engine in Rosalina again."
  echo ""
  read -k 1 "?Press a key to close. " || true
  exit 1
fi

APP_HOME="$HOME/Library/Application Support/Rosalina Video"
VENV="$APP_HOME/venv"
mkdir -p "$APP_HOME"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

echo "[1/4] Installing Python 3.12 and ffmpeg (may request your Mac password)..."
"$BREW" install python@3.12 ffmpeg

PYTHON="$("$BREW" --prefix python@3.12)/bin/python3.12"
if [[ ! -x "$PYTHON" ]]; then
  echo "ERROR: Python 3.12 was not found after Homebrew installation."
  read -k 1 "?Press a key to close. " || true
  exit 1
fi

echo "[2/4] Creating isolated Rosalina environment..."
"$PYTHON" -m venv "$VENV"

echo "[3/4] Installing the MLX video engine..."
"$VENV/bin/python" -m pip install --upgrade pip
"$VENV/bin/python" -m pip install "rapid-mlx[video]==0.16.1"

echo "[4/4] Verifying the engine..."
"$VENV/bin/rapid-mlx" --help > /dev/null
"$BREW" list ffmpeg > /dev/null

echo ""
echo "SUCCESS: Rosalina Mac video engine installed."
echo "Return to Rosalina and click '2 · Start Local Engine'."
echo "First launch downloads the image-to-video model (~22.8 GB)."
echo "Do not close Rosalina during the first model download."
echo ""
read -k 1 "?Press a key to close this Terminal window. " || true

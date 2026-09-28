#!/usr/bin/env bash
# Downloads the sherpa-onnx Android library and a Whisper model into the project.
# Usage: ./scripts/setup.sh [tiny|base|small|tiny.en|base.en|small.en]   (default: base)
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION="1.13.8"
MODEL="${1:-base}"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download"

mkdir -p app/libs app/src/main/assets/whisper

AAR="app/libs/sherpa-onnx-${SHERPA_VERSION}.aar"
if [ ! -f "$AAR" ]; then
  echo "Downloading sherpa-onnx ${SHERPA_VERSION} AAR (~50 MB)..."
  curl -L --fail -o "$AAR" "$BASE/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
fi

echo "Downloading Whisper model '${MODEL}'..."
TMP="$(mktemp -d)"
curl -L --fail -o "$TMP/m.tar.bz2" "$BASE/asr-models/sherpa-onnx-whisper-${MODEL}.tar.bz2"
tar -xjf "$TMP/m.tar.bz2" -C "$TMP"
D="$TMP/sherpa-onnx-whisper-${MODEL}"

# The app always loads these three fixed names (int8 = smaller and faster).
cp "$D/${MODEL}-encoder.int8.onnx" app/src/main/assets/whisper/encoder.onnx
cp "$D/${MODEL}-decoder.int8.onnx" app/src/main/assets/whisper/decoder.onnx
cp "$D/${MODEL}-tokens.txt"        app/src/main/assets/whisper/tokens.txt
rm -rf "$TMP"
echo "Done. Open the project in Android Studio and run it."

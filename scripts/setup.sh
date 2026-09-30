#!/usr/bin/env bash
# Downloads the sherpa-onnx Android library (.aar) needed to build the app.
# Whisper models are no longer bundled at build time -- download them from
# inside the app itself (Manage model button), since they're too large to
# ship in every build and the app can now fetch/switch/remove them on demand.
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION="1.13.8"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download"

mkdir -p app/libs

AAR="app/libs/sherpa-onnx-${SHERPA_VERSION}.aar"
if [ ! -f "$AAR" ]; then
  echo "Downloading sherpa-onnx ${SHERPA_VERSION} AAR (~50 MB)..."
  curl -L --fail -o "$AAR" "$BASE/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
fi
echo "Done. Open the project in Android Studio, run it, then use 'Manage model' in the app to download a Whisper model."

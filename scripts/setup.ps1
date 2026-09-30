# Windows version of setup.sh -- downloads only the sherpa-onnx .aar needed to build.
# Whisper models are downloaded from inside the app (Manage model button) instead.
param()
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$ver  = "1.13.8"
$base = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
New-Item -ItemType Directory -Force app/libs | Out-Null

$aar = "app/libs/sherpa-onnx-$ver.aar"
if (-not (Test-Path $aar)) {
  Write-Host "Downloading sherpa-onnx $ver AAR (~50 MB)..."
  Invoke-WebRequest "$base/v$ver/sherpa-onnx-$ver.aar" -OutFile $aar
}
Write-Host "Done. Open the project in Android Studio, run it, then use 'Manage model' in the app to download a Whisper model."

# Windows version of setup.sh.  Usage: .\scripts\setup.ps1 -Model base
param([string]$Model = "base")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$ver  = "1.13.8"
$base = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
New-Item -ItemType Directory -Force app/libs, app/src/main/assets/whisper | Out-Null

$aar = "app/libs/sherpa-onnx-$ver.aar"
if (-not (Test-Path $aar)) {
  Write-Host "Downloading sherpa-onnx $ver AAR (~50 MB)..."
  Invoke-WebRequest "$base/v$ver/sherpa-onnx-$ver.aar" -OutFile $aar
}

Write-Host "Downloading Whisper model '$Model'..."
$tmp = Join-Path $env:TEMP "whisper-$Model"
New-Item -ItemType Directory -Force $tmp | Out-Null
Invoke-WebRequest "$base/asr-models/sherpa-onnx-whisper-$Model.tar.bz2" -OutFile "$tmp/m.tar.bz2"
tar -xjf "$tmp/m.tar.bz2" -C $tmp
$d = "$tmp/sherpa-onnx-whisper-$Model"

Copy-Item "$d/$Model-encoder.int8.onnx" app/src/main/assets/whisper/encoder.onnx -Force
Copy-Item "$d/$Model-decoder.int8.onnx" app/src/main/assets/whisper/decoder.onnx -Force
Copy-Item "$d/$Model-tokens.txt"        app/src/main/assets/whisper/tokens.txt  -Force
Remove-Item $tmp -Recurse -Force
Write-Host "Done. Open the project in Android Studio and run it."

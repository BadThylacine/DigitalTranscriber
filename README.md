# Offline Transcriber (Android)

Share or pick a short video -> audio is extracted on-device -> Whisper (via sherpa-onnx)
transcribes it -> copy / share / save as .txt. There is **no INTERNET permission**, so the
app cannot send anything off the phone.

## Setup
1. Download the library and a model (needs internet once, on your computer):
   - macOS/Linux: `./scripts/setup.sh base`
   - Windows (PowerShell): `.\scripts\setup.ps1 -Model base`

   Models: `tiny` (~115 MB download, fastest), `base` (~200 MB, good default),
   `small` (~640 MB, best accuracy, wants 6 GB+ RAM). English-only variants
   (`base.en`, `small.en`) are a bit more accurate for English. The script keeps only the
   int8 files, so the APK is smaller than the download.
2. Open the folder in Android Studio (it creates the Gradle wrapper and syncs).
3. Run on a physical arm64 phone (Android 8.0+).

## Use
- Save the video from Instagram/gallery, then Share -> "Offline Transcriber",
  or open the app and tap "Pick video".
- Choose the spoken language or leave Auto-detect (English-only models ignore this).

## How it works
- `AudioDecoder.kt`: MediaExtractor + MediaCodec -> mono float PCM at native sample rate.
- `Transcriber.kt`: splits audio into <=28 s chunks at quiet points (Whisper's window is 30 s),
  runs sherpa-onnx, joins the text. sherpa-onnx resamples to 16 kHz internally.
- `MainActivity.kt`: share-intent handling, progress UI, copy/share/save.

## Notes
- To shrink the APK, drop `x86_64` from `abiFilters` in `app/build.gradle.kts`.
- Only process content you have the right to use.

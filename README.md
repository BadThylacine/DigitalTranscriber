# Offline Transcriber (Android)

Share or pick a short video -> audio is extracted on-device -> Whisper (via sherpa-onnx)
transcribes or translates it -> copy / share / save as .txt.

Transcription and translation run **fully on-device** and never touch the network.
The app does hold the `INTERNET` permission, but only to let you *download a
Whisper model* from inside the app -- nothing else ever goes over the network.

## Setup
1. Download the sherpa-onnx library needed to build the app:
   - macOS/Linux: `./scripts/setup.sh`
   - Windows (PowerShell): `.\scripts\setup.ps1`
2. Open the folder in Android Studio (it creates the Gradle wrapper and syncs).
3. Run on a physical arm64 phone (Android 8.0+).
4. On first launch, tap **Manage model** and download one. `base` is a good default.

## Choosing a model
Available from the in-app picker:

| Model | Download size | Notes |
|---|---|---|
| tiny / tiny.en | ~115 MB | Fastest, weakest accuracy |
| base / base.en | ~200 MB | Good default |
| small / small.en | ~640 MB | Best accuracy, wants 6 GB+ RAM |

`.en` variants are English-only (transcription only -- no translation, since
there's nothing to translate to). Multilingual models (`tiny`/`base`/`small`)
support translating any source language to English.

Switching models re-downloads and swaps automatically: the new model is
downloaded and verified first, then the old one is deleted, so a failed or
cancelled download never leaves you without a working model. **Manage model ->
Remove current model** frees the space without downloading anything else.

## Use
- Save the video from Instagram/gallery, then Share -> "Offline Transcriber",
  or open the app and tap "Pick video".
- Choose the spoken language or leave Auto-detect.
- Tap **Translate to English** to translate the same audio without re-decoding
  it; tap it again to switch back to the original-language transcript.
- Tap **Export subtitles (.srt)** for an external subtitle file matching the
  source video's filename, with a suffix showing what it contains:
  - `_org` — original-language subtitles
  - `_eng` — English translation subtitles (needs a multilingual model)
  - `_bi` — bilingual: English on top, original language below each cue
    (needs a multilingual model)

  All three use the same ~10s chunk boundaries (cut at quiet points) as the
  cue timings, so the English and original lines in a bilingual file always
  line up in time -- they're two passes over the exact same audio segments,
  not matched up afterwards. Results are cached per video, so exporting a
  second variant of the same clip doesn't redo work already done for the
  first.

## How it works
- `AudioDecoder.kt`: MediaExtractor + MediaCodec -> mono float PCM at native sample rate.
- `ModelManager.kt`: downloads a model's `.tar.bz2` from sherpa-onnx's GitHub
  releases, stream-extracting only the three files actually needed (int8
  encoder/decoder + tokens) straight out of the archive. Stores one model at a
  time under the app's private files directory and tracks which one is active.
- `Transcriber.kt`: loads the active model from disk by file path (sherpa-onnx's
  non-asset constructor), splits audio into <=28 s chunks at quiet points
  (Whisper's window is 30 s), runs sherpa-onnx, joins the text. sherpa-onnx
  resamples to 16 kHz internally.
- `MainActivity.kt`: share-intent handling, model picker/download/remove UI,
  progress UI, copy/share/save.

## Notes
- If speech is hard to transcribe, or output degenerates into one word
  repeated many times, that's a known Whisper failure mode (often triggered by
  music/noise/silence, or a language switch mid-clip). A bigger model (`small`)
  usually helps a lot; switch anytime via **Manage model**.
- To shrink the APK, drop `x86_64` from `abiFilters` in `app/build.gradle.kts`.
- Only process content you have the right to use.

## Credits
Built with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0) and
OpenAI's [Whisper](https://github.com/openai/whisper) (MIT) models. Not affiliated
with Instagram or Meta.

## License
MIT — see [LICENSE](LICENSE).

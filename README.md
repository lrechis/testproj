# Kitten TTS for Android

A small Android app that runs [KittenTTS](https://github.com/KittenML/KittenTTS), the
15M–80M parameter open-source text-to-speech model, entirely on the phone. Type some
text, pick a voice and a speed, tap **Speak**, and the audio is synthesised and played
locally. No server, no account, and after the one-time model download no network at all.

## How it works

| Piece | What it does |
|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (`sherpa-onnx-1.13.8.aar`) | Runs the KittenTTS ONNX model with ONNX Runtime and turns text into phonemes with its bundled espeak-ng. This is the same runtime the sherpa-onnx project uses for its own Android TTS demos, and it already contains the KittenTTS front end. |
| KittenTTS model bundles | Published by the sherpa-onnx project on its `tts-models` GitHub release as `.tar.bz2` archives containing the ONNX model, `voices.bin`, `tokens.txt` and `espeak-ng-data`. The app downloads the chosen bundle on first use into the app's private storage. |
| Apache Commons Compress | Unpacks the `.tar.bz2` bundle on the device. |
| `AudioTrack` | Plays the 24 kHz mono float audio, streaming sentence by sentence while the rest of the text is still being synthesised; transient audio focus ducks other apps meanwhile. |

The app itself is plain Kotlin on the Android framework (no Jetpack/AndroidX), so the APK
stays small: the only sizeable dependency is the sherpa-onnx native library.

```
app/src/main/java/com/lrechis/kittentts/
├── MainActivity.kt      UI: model and voice pickers, speed slider, text box, Speak/Stop
├── TtsController.kt     Background worker: download → load → speak; survives rotation
├── TtsEngine.kt         sherpa-onnx OfflineTts configured for a KittenTTS bundle
├── PcmPlayer.kt         AudioTrack streaming playback with stop support
├── TextChunker.kt       Splits long text into sentence-aligned chunks for synthesis
├── ModelDownloader.kt   HTTP download with progress, then unpack and install
├── TarBz2Extractor.kt   Safe .tar.bz2 extraction (no path traversal, no links)
├── ModelStore.kt        Where models live on disk and whether they are complete
└── KittenModels.kt      Catalogue of models and voices
```

## Models

| Picker name | Bundle | Model size (from the KittenTTS README) |
|---|---|---|
| Nano 0.8 int8 (default) | `kitten-nano-en-v0_8-int8` | 15M params, ~25 MB |
| Nano 0.8 fp32 | `kitten-nano-en-v0_8-fp32` | 15M params, ~56 MB |
| Micro 0.8 | `kitten-micro-en-v0_8` | 40M params, ~41 MB |
| Mini 0.8 | `kitten-mini-en-v0_8` | 80M params, ~80 MB |
| Nano 0.1 fp16 | `kitten-nano-en-v0_1-fp16` | original release, 15M params |
| Nano 0.2 fp16 | `kitten-nano-en-v0_2-fp16` | 15M params |

Each bundle is downloaded from
`https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<bundle>.tar.bz2`
and adds roughly 10 MB of espeak-ng data to the model size. All models are English only
and offer the same eight voices (`expr-voice-2-m` … `expr-voice-5-f`; `-m` male, `-f`
female). Downloaded models can be deleted from the app.

## Building

Requirements: Android Studio Narwhal 3 Feature Drop (2025.1.3) or newer, or a command line
with JDK 17+ and the Android SDK (platform 36). The project uses Gradle 8.14.3 (via the
wrapper), Android Gradle Plugin 8.13.2 and Kotlin 2.2.21.

```bash
./gradlew assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # installs on a connected device or emulator
./gradlew testDebugUnitTest      # runs the JVM unit tests
```

The first build (or the first Gradle sync in Android Studio) fetches
`sherpa-onnx-1.13.8.aar` from the sherpa-onnx GitHub release into `app/libs/`; the file is
ignored by git. If the build machine is offline, download that asset manually and put it
there. `app/build.gradle.kts` has an optional SHA-256 pin for the file.

A GitHub Actions workflow (`.github/workflows/android.yml`) builds the debug and release
APKs and runs the unit tests on every push, and uploads the debug APK as an artifact.

The APK includes native code for `arm64-v8a`, `armeabi-v7a` and `x86_64` (change
`abiFilters` in `app/build.gradle.kts` to adjust). Minimum Android version is 8.0 (API 26).

## Using the app

1. Choose a model (the default, Nano 0.8 int8, is the smallest and fastest).
2. Tap **Download**, or just tap **Speak**: the model is downloaded and unpacked on demand,
   with progress shown. This needs internet once per model. Keep the app in the foreground
   while it downloads (the screen stays on); there is no background service.
3. Pick a voice and a speed (0.5× to 2.0×), enter text and tap **Speak**. **Stop**
   interrupts playback immediately.

Synthesis runs on the CPU with a thread count derived from the device's core count. The
nano models speak faster than real time on recent phones; Mini is noticeably slower.

## Notes and limitations

- English only (KittenTTS 0.x models are English models).
- Long texts are cut into chunks of up to 300 characters at sentence boundaries and
  synthesised one chunk at a time; the first sentence starts playing almost immediately and
  the rest follows as it is generated. Stop takes effect after the sentence in progress.
- The newer KittenTTS 2 (a 1.7B parameter speech language model) is not an ONNX model and
  is far too large for a phone; this app uses the ONNX "legacy" family (0.1/0.2/0.8).
- Licences: KittenTTS models and sherpa-onnx are Apache-2.0; the espeak-ng phonemizer
  inside sherpa-onnx is GPL-3.0, which matters if you distribute the app.

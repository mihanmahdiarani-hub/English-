# English AI Tutor V2 — offline actor-speech enhancement (v47)

This applies **only** to the Android package `com.mihan.englishaitutor.v2`.

## Real processing contract

* `audio.wav`: untouched PTS-corrected, 16 kHz mono original movie mix.
  **Movie Media3 playback and per-word PCM always use this file / original video.**
* `speech.wav`: output of the **real GTCRN streaming ONNX speech enhancement
  model** (not a volume gate, time shift, silent track or mock).
* `background.wav`: sample-aligned **residual** `audio - speech`.
  This is NOT independently classified music versus sound effects. Voice can
  remain in it, and the speech stem can contain music bleed.
* Whisper transcription, waveform sentence refinement and Wav2Vec2 CTC word
  alignment use `speech.wav` when it is valid. If enhancement fails, they
  use `audio.wav` and report this in `SPEECH_STEMS` diagnostics.
* A focused CTC attempt that fails on `speech.wav` retries the untouched
  original actor waveform once; no fabricated times or artificial voice.
* Stem outputs contain **exactly the same number of 16 kHz PCM samples**
  as `audio.wav`. STFT overlap-add windows are centered and reconstructed
  on the original sample index. The movie's video / soundtrack stay unchanged.
* Old movie archives are upgraded once. The audio/transcript marker is written
  only after an enhanced (or explicitly logged fallback) transcript is saved;
  old CTC pass completion state is invalidated and recalculated.

## Neural model, licensing and reproducibility

* Model: **GTCRN** (Xiaobin-Rong/gtcrn) `gtcrn_simple.onnx`, 16 kHz
  512-point sqrt-Hann STFT, 256-sample hop, cache-preserving causal inference.
* Original: https://github.com/Xiaobin-Rong/gtcrn
* Source revision: `502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73`.
* Git blob SHA-1: `015454eef64bd4328e6ff4946c231d4a4a632e93`.
* Size: 535,190 bytes. CI downloads and verifies the exact upstream blob
  before packaging it in APK assets. The app requires no network for
  separation after installation, and sends no movie audio to a backend.
* Upstream license: **MIT** (check upstream repository for full attribution).
* The network is trained for noise suppression, not perfect speech/music/SFX
  three-stem separation. In particular, songs with vocals or simultaneous
  speakers may defeat it.

## Validation

* Pure Java STFT roundtrip sample conservation and identity waveform tests.
* Real ONNX CPU graph execution and exact streaming-state shape checks on CI.
* Android SDK 36 compile and signed APK verification.
* Listening comparisons and elapsed time on the user's Samsung handset are
  still required to establish perceptual improvement. **No improved CTC
  percentage is promised without measuring the same film before/after.**

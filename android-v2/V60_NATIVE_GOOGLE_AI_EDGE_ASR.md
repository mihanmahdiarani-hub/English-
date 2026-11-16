# V60 Preview — REAL Google AI Edge / MediaPipe speech recognition within English AI Tutor

**Scope:** English AI Tutor Preview `com.mihan.englishaitutor.v2.preview` only, based on V59. The official `MediaPipe AudioTranscriber` SDK `com.google.mediapipe:tasks-audio:1.2.2` is now integrated directly into the Android app. This is **not** a method to remotely control the installed Google AI Edge Gallery. Gallery does not publish an external inter-app transcription-result API; its private model cannot be silently accessed by another app.

## Direct API behavior

1. Open a movie, select an English dialogue and go to Advanced Tools > **🔬 Whisper / AI Edge**. The default new button **🎧 تبدیل مستقیم همین دیالوگ با Google AI Edge** calls `EdgeNativeTranscriber.transcribe`. There is **no app switch, manual audio export, clipboard paste, Firebase, Gemini key or remote ASR**.
2. On **first use only**, tap **⬇ دانلود مدل Google AI Edge** to explicitly approve a ~615 MB download over **unmetered Wi-Fi**. The Android DownloadManager downloads NVIDIA Parakeet TDT 0.6B v3 INT8 in the documented `.litertlm` format directly from its official Hugging Face repository into English AI Tutor app-specific local storage. It is **not** bundled into the APK and is not shared with the Gallery app. It may take a while and requires sufficient storage (~800MB+ headroom). Tap the Direct Conversion button when the model has finished downloading. No silent download and no use of metered/mobile network by default.
3. The exact original movie 16kHz mono PCM audio interval, with up to 200ms padding and 30 seconds maximum, is fed as normalized PCM floats into `AudioTranscriber.createFromFile(context, model).transcribe(pcm, true)` on a background worker. It returns the transcribed text plus finalized segment timestamps, which are mapped back to the movie's clock for **comparison display only**.
4. The resulting text is stored in the same per-archive/per-original-row comparison preferences as V59. The choice between **Whisper** and **Google AI Edge** for the selected dialogue is explicit and reversible. The unmodified Whisper transcript and raw WAV archive remain the canonical original; selecting AI Edge changes the teaching and visible text only. Old Whisper per-word timestamps are cleared because they cannot be assumed correct for an unrelated ASR transcript. Segment times from MediaPipe are shown as reference; they do **not** silently rewrite original playback boundaries. **The existence of segment timestamps does not prove word-level accuracy.**
5. V59's assisted/manual Gallery Audio Scribe workflow remains behind the optional **روش دستی قدیمی** button. It is no longer the main flow.

## Model and license

- Official Google docs: https://developers.google.com/edge/mediapipe/solutions/audio/audio_transcriber/index
- Model: https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3
- Model creator: **NVIDIA**, Parakeet TDT 0.6B v3. Weights/tokenizer are **Creative Commons Attribution 4.0** (CC BY 4.0). Attribution: NVIDIA NeMo team and the upstream model card. Google provides the MediaPipe Tasks runtime under Apache 2.0.
- English is supported; this model currently targets 25 European languages and cannot be presumed to accurately transcribe all other languages.
- First run uses significant memory (model ~615MB, roughly 800MB RAM reported on flagship Galaxy S24 Ultra). Whether the owner's Samsung SM-S721B can load and infer must be verified on-device. If it cannot, the app shows a nonfatal error and Whisper is still available.
- MediaPipe Tasks may transmit anonymized performance/usage metrics under its SDK privacy notice, though audio transcription computation remains on-device. Read: https://developers.google.com/edge/mediapipe/solutions/setup_android . No claim of absolute zero networking other than the offline inference path.

## Validation and release boundary

- V60 code builds a signed experimental APK with previous Preview package + signing certificate. GitHub Actions performs byte-level WAV tests, static API call and optional download checks, compiles Android, validates assets/identity/signature and uploads **only a temporary test artifact**.
- The full **model file is not present in CI** (615MB), so CI cannot genuinely run MediaPipe ASR on a real movie. This must be verified on the user's physical phone, and there is no guarantee it will beat Whisper.
- No GitHub Release, production APK replacement, backend deployment, Firebase key, paid services or billing activation is authorized.

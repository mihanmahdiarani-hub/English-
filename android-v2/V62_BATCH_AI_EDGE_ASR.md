# V62 Preview — one-tap whole-film Google AI Edge transcription

Only **English AI Tutor Preview** (package `com.mihan.englishaitutor.v2.preview`) is changed. Based on V61; existing app signing, archive, encrypted Gemini key, Live voice, and original Whisper/CTC code stay untouched.

## Owner request

V61 requires manually selecting and converting each movie dialogue. V62 adds a genuine background-worker **🎞 تبدیل همه دیالوگ‌های فیلم با AI Edge** button, reachable from the existing **🔬 Whisper / AI Edge** in the Settings / Advanced tools drawer. Opening it for a prepared film no longer requires tapping an individual subtitle row first.

## Processing

- One explicit **شروع همه** confirmation starts processing every eligible as-yet-unconverted line from the prepared **current movie only**, using original 16 kHz mono PCM clips of up to 30 seconds per dialogue. It never asks the user to hit Run for each line. This is **not** an online API: the NVIDIA Parakeet TDT 0.6B v3 model runs inside the app via Google's MediaPipe SDK, after the owner's prior explicit optional download.
- **Load the ~615 MB native model ONCE** into a long-lived `EdgeNativeTranscriber.Session` and reuse for all rows, rather than loading model separately for each line. Release the model on completion/stop.
- Existing cached results for an unchanged movie/row/Whisper text/bounds are skipped. Store each successful result immediately in the private per-row comparison preferences. A cancelled session can restart and skip previously finished rows.
- Display progress (completed/total, successful/failed/skipped), an explicit **■ توقف** button, and a summary. Stopping can take until the current short speech segment finishes. The movie screen does not automatically navigate to Lesson. The screen is kept awake while running to avoid stopping due to sleep; leaving the app or changing the movie cancels the batch (no secret/background processing). Long movies may take considerable battery/time and require the app to stay open.
- Segments longer than 30 seconds or invalid are **clearly counted and skipped**, not silently truncated or misaligned. Multiple repeated failures terminate early. Original WAV and Whisper transcript remain untouched.
- Do **not** automatically replace Whisper outputs. The owner can inspect the results per dialogue and optionally press **✓ استفاده از نتایج AI Edge برای همه دیالوگ‌های آماده**. This changes text used for display/teaching only, preserving original Whisper dialogue start/end and clearing incompatible Whisper per-word timestamps on selected AI Edge rows. Individual Whisper/Gallery choice is still reversible.
- No Firebase/Render server, no GitHub compute for model inference and no Gemini API usage for this processing. The original optional ~615 MB model download can use Wi-Fi or SIM data after size confirmation, roaming disabled.

## Validation

The V62 PR-only workflow verifies batch-session reuse, stop, onPause/onDestroy cancellation, saved/resumable rows, opt-in mass selection and WAV clip tests; compiles and signs Android with the previous Preview keystore and verifies Whisper/Silero/GTCRN offline assets. It generates **only a temporary GitHub Actions APK**, not a public release, merge, paid service or production update.

**Limit:** CI does not run the 615 MB model or verify real recognition quality on the owner's device. MediaPipe's ability to reuse a session for hundreds of sequential final clips and inference speed/memory must be verified on the phone. V62 does not claim more accurate speech or word-level timing without a real sample.

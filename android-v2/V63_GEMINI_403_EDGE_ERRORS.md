# V63 Preview — Gemini HTTP 403 guard and AI Edge failure reporting

Scope: **English AI Tutor Preview only** (`com.mihan.englishaitutor.v2.preview`), built on V62 with the original signing certificate, local movie archives and encrypted personal API key. No other app, backend, Render or production version is affected.

## Confirmed by 2026-11-13 user diagnostics

- Local archive restores 131 timed dialogue rows on Samsung SM-S721B.
- Google AI Edge MediaPipe Parakeet succeeded for 123 newly converted dialogues and reused two cached results (125/131). Six rows (zero-based indices 71, 78, 82, 83, 84, 105) failed with `IOException`, with the old log not recording whether this meant no speech or an input/model I/O error. A later run used the 125 cached results and failed five remaining rows before its stop-on-repeated-failure safety guard.
- Direct Gemini key is successfully *decrypted* but does not demonstrate Google project access. The valid model `gemini-3.5-flash-lite` consistently returns HTTP 403 from both lesson analysis and manual Gemini chat. Google documents HTTP 403 as `PERMISSION_DENIED`: key/project access/permissions must be checked at Google AI Studio. Do not confuse with HTTP 429 quotas or assert the key is invalid without the provider error details.
- Repeated movie dialogue taps generated many callbacks and repeated forbidden API calls. Some AI Edge text rows cannot be acoustically aligned word-by-word via the separate Wav2Vec2/CTC component. Do not treat those timestamps as exact words.

## Changes

1. After a real HTTP 403, `GeminiDirectClient` pauses further direct API traffic for **five minutes**, persisted across app restarts; it never discards/exposes the existing encrypted API key. After the short cooldown, a new user request can retry. Re-saving the key explicitly clears the cooldown. This does not pretend to repair Google's project permission.
2. `GeminiLessonService` stops and fails already queued automatic teaching requests without sending them to the rejected API after the first 403. During the pause, movie teaching uses on-device translation, even when the user had enabled automatic Gemini explanations. Manual chat/teacher triggers get a clear error rather than secretly retrying. No Gemini usage while paused.
3. AI Edge batch logs categorized failures: `NO_SPEECH_DETECTED` when Parakeet returned no transcribed words, `AUDIO_OR_MODEL_IO` for other IO errors, and named classes for other failures. The visible summary lists failed **one-based dialogue numbers** and reason categories, so the user can target the six rows. Results successfully converted stay cached; neither the underlying Whisper transcript nor the movie's timeline is overwritten.

## User verification

- Install V63 Preview over V62 **without uninstalling**. Movie archive, model download and key remain.
- Tap a dialogue; if Gemini is still forbidden, Google will return at most one 403 within the cooldown interval, and subsequent taps will use local translation. The separate Tutor Chat will explain permission access rather than sending another unauthorized request. Check the key's owning project in https://aistudio.google.com/api-keys and its permissions and any visible project restrictions. No need to paste the key into ChatGPT or enable billing.
- Run **🔬 Whisper / AI Edge → 🎞 تبدیل همه دیالوگ‌ها**. The five/six remaining short or hard rows will report numbers and a reason; this still does not guarantee accurate individual word timestamps.
- CI tests Java/Riff WAV behavior, checks new permission suppression and failure categorization, compiles and signs with the unchanged Preview certificate, and verifies the existing offline ASR models. Final API permissions and real device transcription still require user testing.

No release, merge, production change, auto-billing or paid provider connection.

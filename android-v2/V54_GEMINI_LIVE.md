# English AI Tutor V54 Preview: Live voice with the existing encrypted Gemini key

Scope: only `com.mihan.englishaitutor.v2.preview`. Upgrade from installed V53 Preview without uninstalling. Production V50 and other apps are untouched.

## Added

- A dedicated **🎙 Live** button next to text Gemini and also in the chat header. It opens `GeminiLiveActivity`. If entered from the movie, video playback is paused; only the **highlighted dialogue text** (at most 800 characters) is supplied as optional lesson context. No video, file, movie audio, or full transcript is sent.
- An explicitly user-started full-duplex Google Gemini Live WebSocket, implemented in Java/OkHttp. Input: `AudioRecord` VOICE_COMMUNICATION source at 16 kHz mono 16-bit PCM, 50 ms microphone chunks. Output: `AudioTrack` 24 kHz mono PCM16 streamed on a worker thread. Android echo cancellation is enabled when available; headphones are recommended.
- Real-time user/teacher text transcriptions on screen (not persisted or logged), model choice, voice choice (Kore, Aoede, Puck, Charon), mute/unmute (stops local microphone capture), stop and server-side interruption (flush buffered speaker audio).
- By default model `gemini-3.8-live` via the `v1alpha` WS endpoint; fallback model `gemini-3.1-flash-live-preview` via `v1beta`. The model cannot be an arbitrary URL or unapproved name. Official docs: [Live API](https://ai.google.dev/gemini-api/docs/live-api), [WebSocket setup](https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket).
- A **ten-minute upper session bound** and no automatic reconnect, hidden microphone use or background recording; onStop/onDestroy and Stop close the network session and mic. Android's RECORD_AUDIO permission is requested only after Start.
- Reuses the **same V53 personal Google AI Studio API key** stored as AndroidKeyStore AES-GCM ciphertext in app-private SharedPreferences. No new key, Firebase, backend, public token, secret build setting or billing activation. The raw WebSocket protocol accepts a `key` URL query parameter; that value is constructed only on the phone and **never logged or shown in a diagnostic message**. Client-to-server direct authentication is adequate only for the owner's personal-use device; Google recommends ephemeral session tokens for distributed apps.
- Free-tier access depends on the user's Google project, region, model and quota. If billing is enabled on the owner's project, audio usage **can incur cost**. The app cannot disable Google billing. Sessions are opt-in and limited to 10 minutes to reduce accidental usage.

## Setup/test

1. Install **V54 Preview** over V53 Preview; **do not uninstall** (same app package and signing certificate preserve local film archive, word-alignment progress and saved key).
2. Open **✨ کلید Gemini** and confirm the encrypted personal key is saved. Do not paste the key in a chat, screenshot or log.
3. Tap **🎙 Live** in the app. Choose the default 3.8 model and a preferred voice. Tap **🎙 شروع مکالمه**, grant the Android microphone permission, then speak English.
4. Gemini's native audio should play in realtime and the two speakers' transcriptions should appear. You can speak while Gemini answers, toggle **میکروفون** or tap **پایان**.
5. If the model is unavailable (HTTP 404 or WS close), choose **Gemini 3.1 Flash Live** and try again manually. Errors such as 401/403 may mean access or regional restrictions; 429 is quota. There is **no automatic paid-model fallback or retry**.
6. The Android app deliberately stops Live when backgrounded. A new session requires another tap on Start. For real-world speaker echo, use wired/Bluetooth headphones.

## Release/validation boundary

The V54 workflow restores the original Preview debug keystore only from the cache, verifies its exact SHA-256, downloads and hashes all three pinned offline models (Whisper base.en, Silero VAD, GTCRN), compiles Android, verifies package/version/asset presence/signature and uploads **only an Actions test artifact**. This is not a public Release, merge, production deployment or paid resource activation. Live network audio cannot be proven from CI without the owner's actual encrypted on-device key, granted microphone and country/model access; success must be confirmed on a physical phone.

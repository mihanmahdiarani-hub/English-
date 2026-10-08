# English AI Tutor — Liara primary / Render standby

This migration is **staged**, not yet live. Existing Render service remains untouched.

## Architecture and scope

- Source of truth: `mihanmahdiarani-hub/English-` (this repository).
- Intended primary: a Docker application on Liara, serving the PWA, Node HTTP APIs,
  WebSocket endpoint `/live`, audio extraction, media analysis and Android APK download.
- Existing backup: `https://english-ai-tutor-2vki.onrender.com` (Render).
- Backend uses Node >=18, package `ws`, `ffmpeg-static`, and Google Gemini HTTPS and WebSocket endpoints.
- `Dockerfile` starts all required Node preloads; do **not** use the `package.json`
  `npm start` script inside Docker because that reinstalls dependencies at runtime.
- Readiness endpoint: `GET /health` (does not prove Google Gemini connectivity).

## 1. Create a Liara application

1. Sign in at https://console.liara.ir/ and create a Docker app with a unique app ID.
2. Connect this GitHub repository and choose a branch to deploy. First test this migration
   branch; switch to `main` only after verification.
3. Set container port **8080**, matching `Dockerfile`, `liara.json` and the server's
   `PORT` environment variable. Set `PORT=8080` if the Liara environment does not inject it.
4. Use **Germany for BUILD location** for easier npm dependency downloads. This does NOT
   imply that the application RUNTIME IP address is in Germany.
5. Deploy once and record Liara's actual HTTPS hostname. Do not guess the hostname.

CLI alternative after logging into Liara: `liara deploy --app <LIARA_APP_ID> --platform docker --port 8080 --build-location germany`

## 2. Configure secrets on Liara (server environment, never in Git)

Copy required values using secure environment-variable screens, not repository commits:
- `GEMINI_API_KEY` (required by Gemini functionality)
- `GEMINI_API_KEY_2` (optional standby Gemini API key)
- `GEMINI_LIVE_MODEL`, `GEMINI_ANALYSIS_MODEL`, `GEMINI_MEDIA_MODEL`
  if deliberately overridden on Render
- `ANDROID_SIGNING_KEYSTORE_B64`, `ANDROID_SIGNING_STORE_PASSWORD`
  only if enabling Liara's `/android/latest.apk` signed-APK endpoint.
Preserve the **same Android signing key** across versions or upgrades will fail.
Never paste keys, keystores or token values into logs, issues or chat.

## 3. Critical Gemini regional validation gate

Google's Gemini API has country/region eligibility restrictions and Iran is not
listed among supported regions: https://ai.google.dev/gemini-api/docs/available-regions
A successful Liara deploy or `/health` is NOT proof that Gemini requests will work.
Build location does not change runtime egress geolocation.

Before migrating traffic, validate from the deployed Liara runtime:
- Gemini HTTPS `generateContent` and File API calls with a real test request.
- Live `/live` upstream WebSocket setup and `setupComplete`, audio and replies.
- Real Whisper/audio extraction and `/api/analyze-media` responses over multiple dialogs.
- Upload body size, duration, FFmpeg executable, cleanup, Liara request timeouts,
  memory limits and WebSocket upgrade support.
- Android `/android/latest.apk` if signing secrets and correct unsigned APK are present.
If Google returns regional restrictions, keep the supported-region Gemini backend as
the active route; do not announce Liara as an independently working replacement.

## 4. Android build and failover

`MainActivity.java` now takes the primary URL from `BuildConfig.PRIMARY_SERVER_URL`
and retains Render as `BuildConfig.BACKUP_SERVER_URL`.

For a **new Android build**, set Gradle project property:
`-PenglishTutorPrimaryUrl=https://<actual-liara-hostname>`

The default remains Render until Liara is verified. The Android WebView automatically
falls back to Render if loading the *primary app's main document* fails from a
network error or a 5xx response. It does not automatically switch for all runtime
API/WebSocket errors after a page was loaded. Cross-origin browser localStorage
is separate: lesson data/progress in the Render origin will not automatically appear
in Liara; back it up or implement a migration before end-user cutover.

The existing APKs still point to Render until a newly signed APK is released. A new
APK must use the original signing identity to be installable as an update.
`public/android/update.json` currently advertises a Render URL and should
only be updated when a **verified** new signed APK is actually hosted on Liara.
Never point an update feed to a nonexistent or unverified APK.

## 5. Cutover and rollback

1. Verify Liara `/health` and PWA landing page.
2. Pass end-to-end Gemini Live, media upload/extraction, dialog analysis and download tests.
3. Configure Android Liara primary URL and produce/install a correctly signed APK.
4. Test failure of Liara startup and confirm **main document** falls back to Render.
5. Switch web traffic/custom-domain DNS only after both services are confirmed; keep
   Render service, API keys and configuration available.
6. Keep Render backup updated deliberately: its auto-deploy is disabled and logs have
   reported `pipeline_minutes_exhausted`; do not assume new commits reach it.
7. To roll back a new Android release, build a newer versionCode signed with the
   same key pointing to Render, or use the standby on startup failure.
   
No deletion, billing change, DNS cutover or Render deployment is part of this staged PR.

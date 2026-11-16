# V53 Preview — Gemini Developer API directly from Android

**Target:** English AI Tutor V53 isolated Preview update `com.mihan.englishaitutor.v2.preview`, versionCode 53. Based on V52 Preview; not a replacement for production V50.

## What changed

- Firebase AI Logic, Firebase App Check, `google-services.json` import and the Firebase SDK dependency are removed from the **active Gemini runtime**. The bundled Firebase configuration file is removed from the APK.
- The existing `GeminiLessonService` still owns dialog-focused teaching, shared cache, four-item maximum batches, pacing, quota backoff, structured lexical categories, and the full chat prompt. Requests now go to the **Gemini Developer REST API** using `GeminiDirectClient`.
- Hardcoded endpoint: `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent`. Header `x-goog-api-key` carries the owner's personal key over strictly validated HTTPS. Redirects are not followed. Request/response bytes are bounded. No movie, audio or video is uploaded; only the same compact transcript text and chat prompts.
- The user enters the personal Google AI Studio **Gemini Developer API key** in the "✨ کلید Gemini" dialog. This is **NOT** the `current_key` from Firebase `google-services.json`. The app stores the key only as AES-256-GCM ciphertext protected by AndroidKeyStore in app-private SharedPreferences; the original key is never written to GitHub, compiled into the APK, displayed again, logged or sent to any backend.
- The dialog supports changing/removing a key and explicitly opting in to **automatic** Gemini analysis. Default OFF to avoid unsolicited quota and potential paid usage. Clicking the manual teacher button, tapping a transcript line or manually requesting vocabulary analysis is an intentional API action; chat sends on explicit user action.
- Existing V52 preview film archive, Whisper/GTCRN/Silero models, Media3 player, extracted transcripts, word alignment and UI navigation remain unchanged. V53 signs with the **exact original V51/V52 Preview certificate** so installing it over V52 should keep Preview's app-private data.

## First use

1. Install the signed **V53 Preview** APK over the installed V52 Preview. **Do not uninstall** if Android prompts you; check the signature first. Production V50 is a different package and is untouched.
2. Go to [Google AI Studio](https://aistudio.google.com/app/apikey) and create/copy a **Gemini Developer API key** for a project **without billing enabled** if you want no monetary charges. The app cannot switch off billing for a project remotely. No project creation, billing activation or external credential handling is performed by this code.
3. In English AI Tutor press **✨ کلید Gemini**. Paste the personal key and press **ذخیره**. "Key stored" confirms only local encryption, **not a successful network response**.
4. Open **💬 چت Gemini** and send a short message. A real successful response is required before claiming Gemini is connected.
5. Optionally enable automatic analyses in Gemini settings after confirming your provider usage tier. Quota or rate limits can cause HTTP 429; key/region/permission restrictions can cause HTTP 401/403; model/project restrictions can cause HTTP 404. No fallback to paid services.
6. Never send an API key to chat or in a diagnostic log.

## Validation / limitations

- Java compile, static privacy checks, APK package/version/signature checks and presence of **all three pinned offline models** run in a pull-request-only GitHub Actions workflow. The workflow does NOT create GitHub releases, deploy servers or modify production update metadata.
- The key is decrypted only on the phone. CI cannot perform an authenticated real Gemini request; country restrictions and service availability must be tested on the phone after key entry.
- Free-tier availability and rates are controlled by Google, may vary by account/region and may change. A billable API project can incur charges; this code cannot guarantee free usage from an arbitrary project.
- V53 is an experimental unmerged draft branch; no production merge or public APK release is approved.

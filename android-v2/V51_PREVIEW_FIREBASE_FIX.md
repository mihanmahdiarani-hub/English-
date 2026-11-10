# English AI Tutor V51 Preview — Firebase package mismatch fix

Scope: **V51 Preview only**. Do not merge this into production V50 or release an APK without explicit owner approval.

## Confirmed mismatch

- Preview Android application ID: `com.mihan.englishaitutor.v2.preview`.
- Existing bundled `android-v2/app/src/main/assets/google-services.json` contains only a Firebase Android client registered for `com.mihan.englishaitutor.v2`.
- Both MainActivity (movie lesson) and GeminiConnection (dialogue chat/vocabulary) previously required an **exact** package-name match. Therefore the Preview config was not found and Gemini appeared unconfigured even before any Google API request.

## Source fix

The new FirebaseClientPackageSelector selects an **exact package match first**, for either production or preview. For **only** `com.mihan.englishaitutor.v2.preview`, if the file has no preview client entry, it allows reusing the bundled V50 client configuration **temporarily**. Other app IDs never inherit any client.

This removes the immediate local configuration blocker in Preview but **does not register Preview in Firebase and does not guarantee a successful network API call**. Firebase API-key restrictions, App Check enforcement, and service/region availability still apply.

When the active Firebase client changes in the running process, the app no longer incorrectly reports success with stale FirebaseApp options. Completely close and reopen the app after importing another Firebase client JSON.

## Recommended permanent setup for Preview

1. In **Firebase Console > Project settings > General**, open the existing Firebase project and **Add app > Android**.
2. Register the preview package **`com.mihan.englishaitutor.v2.preview`** (not the production package). If using Android-app-restricted credentials, configure the exact SHA certificate fingerprint of the installed Preview APK in the appropriate Google Cloud/Firebase project settings.
3. Download a new **`google-services.json`** for this project that includes the preview Android client. Import it from the English AI Tutor Preview **✨ اتصال Gemini** screen. The matcher will select Preview's own entry instead of the compatibility fallback.
4. Fully close and reopen Preview to initialize the new Firebase app identity.
5. Inside Preview, use **🛡 App Check** to copy the debug token. Register it under Firebase Console > App Check > the *Preview* Android app > Manage debug tokens (where permitted). Treat the debug token as sensitive, and never put it in GitHub or public messages.
6. Test one small text explanation; if it returns HTTP 403, check App Check registration, Firebase API key permissions/restrictions, regional availability, and the selected Gemini model.

**Important:** The in-app configuration indicator confirms only local SDK initialization, **not** successful model access. The compatibility fallback may be rejected by Firebase/Google if production credentials are restricted to the production app. A separate registered Preview Firebase client is the correct long-term solution.

## No release here

The fix is proposed on an isolated feature branch and draft pull request targeting `preview/v51-navigation-isolated-20261110`. A PR check runs Java identity tests and compiles Android Java without packaging, uploading, publishing, or installing an APK. The installed V50 and V51 Preview remain untouched.

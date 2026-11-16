# V58 Preview: hidden Gemini teacher instructions and stable user-selected tab

**Scope:** English AI Tutor `com.mihan.englishaitutor.v2.preview` only. This upgrades V57/V56 without removing film archives or encrypted Gemini key. No change to production V50, Firebase, server, or any other app.

## Two reported problems

1. The user wants the long private teaching preset **separate from the visible chat**, not written out as a user bubble or re-injected in visible chat history. V57 hid the full text behind a short user bubble, but sent the preset as the ordinary user `question` and showed a placeholder message.
2. On **Video** (including while AUTO/SMART analysis runs), a call to `mediaControls.showLesson()` automatically switched from Video to **Lesson**. The user did not tap the Lesson tab and does not want this navigation.

## V58 corrections

- `GeminiDirectClient.generate(..., systemInstruction)` now passes the teaching preset in Google's **`systemInstruction`** JSON field, separate from the request's `contents`. Existing text chat, vocabulary analysis, model key, Gemini Live and archives keep their prior API paths.
- The new `GeminiLessonService.teachDialogue(currentLine, callback)` sends only the **currently selected film dialogue** in the user-content field; no previous/next film lines or media are uploaded, and the full internal preset never enters `TutorChatActivity` visible messages, history or saved state. It does not claim to have viewed or heard the movie.
- The dedicated **🎓 آموزش کامل با معلم** button begins the request and shows a temporary **status**, not a chat bubble. Only Gemini's actual lesson is added to the chat. The existing unsent draft is retained. If the user answers Gemini's exercise, the response can be discussed with the preceding teacher answer in conversation history.
- The old automatic `mediaControls.showLesson()` in `MainActivity.showTeachingUnit` is removed. AUTO/SMART may still pause at a dialogue and prepare its lesson according to the previously chosen mode, but **the currently selected Video tab remains selected**. The learner alone chooses **Lesson** in bottom navigation. Manual teacher selection also does not redirect.
- VersionCode is **58** and the Preview signing key/package stay unchanged so installing V58 over V56/V57 should keep all existing private data. Do not uninstall Preview.
- Dedicated PR-only Actions job runs the existing pure-Java prompt/Markdown tests, checks `systemInstruction` separation/no chat prompt and no automatic tab switching, then compiles Android and verifies original preview signer and the three offline speech models before uploading a temporary test artifact. It does **not** publish a GitHub Release or deploy/merge anything.

**Verification limitation:** A successful APK build proves code compilation and static rules; the actual on-device chat and tab behavior still needs testing on the owner's Samsung. The user alone supplies/keeps the existing encrypted Gemini key. Direct API calls may consume quota or incur cost if the user's Google project is billable.

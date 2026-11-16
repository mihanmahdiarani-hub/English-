# English AI Tutor V56 Preview — formatted Gemini bold text

## Screenshot defect

The Gemini tutor chat previously called `bubble.setText("✦ Gemini\\n" + clean)`. Google-generated Markdown such as `**معنی مفهومی:**`, `**اصطلاحات واقعی و واژگان:**`, `**barely**` or `**نکات کلیدی گرامر:**` was displayed with literal asterisks and no bold weight.

## V56 fix

- New pure Java `ChatMarkdownText.parse()` interprets matched `**strong**` and `__strong__` in mixed Persian/English Gemini responses; removes markup characters only from the **visible bubble**, converts Markdown `* item` prefixes to proper bullet `• item`, and provides exact UTF-16 character ranges for bold spans.
- `TutorChatActivity` builds an Android `SpannableStringBuilder` and applies `StyleSpan(Typeface.BOLD)` for every strong range. Gemini bubbles remain selectable and RTL/LTR text survives.
- Raw Gemini replies still populate existing `chatHistory` and saved-instance-state; no request prompts, API keys, provider models or history semantics change. Rendering also applies when the Activity is recreated.
- Unmatched Markdown markers remain literal; escaped Markdown marker is not incorrectly treated as bold. No HTML is interpreted.
- Pure Java unit test covers Persian headings, English vocabulary, lists, malformed delimiters, escaping, null/empty and multi-line mixed language.
- Preview `versionCode 56`, same `com.mihan.englishaitutor.v2.preview` package and exact V51–V55 Preview signing certificate; installation over V55 should retain local movie archive, Gemini encrypted key, movie transcription repair, and Live voice.

## Scope and verification

PR-only GitHub Actions first runs the pure Java formatting regression, then compiles Android, validates package/version, prior signing fingerprint and all three bundled offline speech models (Whisper, Silero, GTCRN), and uploads a test APK. The CI does not make any Google API requests or require a key. This change affects **English AI Tutor Preview only**; no production merge, backend, paid service or other app is changed.

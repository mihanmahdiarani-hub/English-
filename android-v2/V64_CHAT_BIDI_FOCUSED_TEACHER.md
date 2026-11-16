# V64 Preview — Persian/English chat direction and useful movie-dialogue teacher

Changes apply **only to English AI Tutor Preview**, `com.mihan.englishaitutor.v2.preview`. Based on V63, preserves app signing certificate, local movie archive and Parakeet model, Gemini encrypted key, Whisper/CTC, V62 automatic ASR, existing video tab/navigation. No edits to Google AI Edge Gallery, MoonStyle, Render, production or billing.

## Problem shown on 2026-11-16 Samsung screenshot

A lengthy Gemini teaching answer starts some lines with English terms (e.g. `Responsible:`, `Who's this guy?`, `Eternal:`) and switches between Persian and English ordering and left/right text alignment. Single Markdown italics such as `*responsive*`, `*love*` and `*damnation/wrath*` remain visible as literal asterisks. The teaching preset previously demanded nine named sections and 250–450 words, encouraging excessive peripheral definitions and low-value information for a short movie sentence.

## What V64 changes

- **Mixed script display:** all Persian-containing Gemini chat bubbles now use a consistent **RTL base and right-aligned text**. English phrases that occur in a Persian line receive Unicode LTR isolation (`LRI U+2066` ... `PDI U+2069`) so their natural order and punctuation stay intact, without shifting the entire paragraph to the left. Fully English assistant answers use LTR. This applies also to previously saved chat bubbles when Android restores them; raw Gemini text and chat history are never rewritten.
- Markdown: `**bold**` and `__bold__` retain bold; `*italic*` and `_italic_` render as italic without exposed stars; both `- item` and `* item` bullets render as `• item`. Formatting is visual only; the app keeps exact original raw model response in conversation history. HTML is not parsed.
- **Teaching preset:** `FilmDialogueTeacherPrompt.INSTRUCTION` now requests **a short natural Persian translation**, then **only one or two key terms / actual grammar structures present in the selected movie line**, **one optional context-safe usage example**, and **one specific practice question**. For a short dialogue, roughly 60–120 Persian words; for several complex sentences, no more than ~180 words. No forced nine headings, irrelevant dictionary entries, unnecessary similar-word contrasts, made-up context/characters, unrequested IPA or speculative grammar. If the line is ambiguous, acknowledge uncertainty. English phrases are quoted without changing their wording. The teaching preset remains a private Gemini `systemInstruction` and **is not shown as a fake user message**.
- **Original choices unchanged:** meaning-only quick button still requests only the meaning; split-translation quick button requests only segment translations. The full-teaching preset only affects **🎓 آموزش کامل با معلم**.

### Demonstration of intended approach (not actual Gemini output)

Input movie text: `Who's this guy?`

**معنی طبیعی:** «این یارو کیه؟» یا رسمی‌تر «این شخص کیست؟»

**نکتهٔ اصلی:** `Who's` شکل کوتاه‌شدهٔ `Who is` است. `this guy` در گفت‌وگوی روزمره به «این یارو / این شخص» اشاره می‌کند و با توجه به موقعیت می‌تواند خودمانی یا کمی بی‌ادبانه باشد.

**تمرین:** اگر بخواهی بپرسی «این خانم کیه؟» به انگلیسی چه می‌گویی؟

For a film line such as `my eternal wrath`, clarify the dramatic sense of `eternal` only if relevant; do not invent an adjacent meaning of `responsive` unless the selected dialogue truly includes that word.

## Validation and limitations

The isolated V64 workflow uses plain JVM regressions for mixed Persian/English isolation and Markdown italic/bold spans, tests the concise one-tap teacher instruction, runs the inherited original audio WAV, update signing and privacy regression checks, compiles and signs the same Preview app with the existing keystore and pinned Whisper/Silero/GTCRN models. A temporary GitHub Actions APK only; no release, merge or production deploy.

This fixes **display/formatting and instructional guidance**. It does not prove Google will obey every length constraint; model behavior must still be tested on the owner's phone with an actual movie dialogue. It also does **not** resolve external Gemini HTTP 403 project permission, improve word-level audio timing or modify AI Edge ASR output.

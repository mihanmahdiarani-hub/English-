# V57 Preview — one-tap film dialogue teaching prompt

Only the **English AI Tutor Preview** package `com.mihan.englishaitutor.v2.preview` is changed. This update is based on V56 and retains V55 archive repair, V54 Gemini Live, and the existing encrypted personal Gemini API key.

## Behavior

- From any selected movie dialogue, open **💬 چت Gemini**. The original movie line is passed in the Intent as `current_dialogue`; **no other dialogue, movie file, audio, or video is sent**.
- New primary **🎓 آموزش کامل با معلم** button is displayed directly above **معنی مفهومی** and **تقسیم و ترجمه**.
- On tap the button sends the reusable `FilmDialogueTeacherPrompt.INSTRUCTION` to `GeminiLessonService.askTutor()`, which supplies the selected line separately as `Current dialogue`. Prompts remain in the same direct Gemini Developer API connection and use the user's existing AndroidKeyStore-encrypted key.
- The AI is told to teach like a precise Persian-speaking private teacher, **not merely translate**. The fixed lesson covers exact dialogue text, natural Persian meaning, meaningful chunks, contextual vocabulary, *only genuinely present* collocations/idioms/phrasal verbs, grammar, plausible written pronunciation (without pretending to hear audio), two related examples, and one interactive practice question. It waits for the learner's answer for correction.
- Persian section headings ask for Markdown `**bold**` styling, supported by the V56 chat renderer. No HTML, guessed film context or invented dialogue.
- Opening the screen no longer automatically triggers an AI request. The teaching button explicitly starts one; this prevents repeated charges/quota use when the screen is opened or recreated.
- The verbose fixed prompt is **not rendered as a huge chat bubble nor repeated in history**; the visible/history user message is short. Existing unsent typed chat is kept when using the button.
- Short `معنی مفهومی`, `تقسیم و ترجمه`, manual text chat, Android mic input, Gemini Live, offline Whisper and CTC remain.

## First test

1. Install signed V57 Preview over V56, **without uninstalling**; keep the archive and saved Gemini key. No production V50 change.
2. Tap an English movie line, then **💬 چت Gemini**.
3. Tap **🎓 آموزش کامل با معلم**. For `It's barely, sir.`, the tutor should explicitly note that the utterance may be incomplete or context-dependent rather than inventing a preceding sentence; it can explain `barely` and respectful `sir`.
4. After the lesson's one exercise question, type a learner answer and send it for feedback.
5. Confirm other quick buttons continue to make narrower requests and no automatic request happens on just opening the page.

## Scope / test boundary

GitHub Actions runs a pure-Java regression checking prompt sections, no invented film context and waiting for learner input; verifies the UI route and absence of the previous auto-on-open call; compiles and signs the V57 test APK with the same original V51-V56 Preview certificate. Offline speech models are restored and verified. Only a **temporary pull request artifact** is built (no release, merge or paid service activation). A real lesson response still requires the owner's personal key, provider access, quota and on-device testing. Direct API requests may use free-tier quota or cause charges if project Billing is enabled.

# English AI Tutor v2 — Local-first architecture

## Product core

Video on device → transcript with reliable timestamps → teaching units → cached lessons → Media3 playback → pause at teaching boundary → ask questions about the current dialogue.

Gemini Live / permanent WebSocket is not part of the core architecture.

## MVP scope

1. Pick a local video.
2. Keep the video on-device.
3. Prefer embedded/external subtitles when available.
4. Otherwise transcribe audio locally where practical; cloud ASR remains a fallback, not a requirement for the core player.
5. Normalize transcript into Teaching Units with `startMs`, `speechEndMs`, `pauseAtMs`, `text` and optional `speakerId`.
6. Generate structured teaching JSON for units in batches.
7. Persist Movie, Dialogue, Lesson, ProcessingChunk and QuestionThread data locally.
8. Play with Media3 and pause on the armed teaching boundary.
9. Show cached Persian explanation immediately.
10. Allow text Q&A for the current dialogue using only small contextual windows.
11. Android TTS reads tutor answers.

## Non-goals for MVP

- Gemini Live / WebSocket proxy
- Full-video uploads
- Speaker diarization as a blocking dependency
- Cloud TTS by default
- Vector database
- Pronunciation scoring

## Modes

- Smart Teacher: default; pause only on high teaching-value units.
- Auto Teacher: pause on nearly every suitable Teaching Unit.
- Watch: no automatic pause; tap a subtitle/dialogue to open its lesson.

## Data ownership

Timestamp and transcript are pipeline-owned facts. The LLM must not rewrite them. Gemini only enriches an existing dialogue ID with teaching fields such as translation, natural meaning, grammar, idioms, connected speech, teaching value and uncertainty.

## Q&A context

Each question sends only:

- current dialogue
- several previous dialogues
- cached lesson
- recent turns for the same dialogue
- optional text-derived scene summary
- user's question

Future dialogue context is opt-in to avoid spoilers.

## Cost-first plan

Development should start on no-cost/on-device components wherever possible. Use Firebase AI Logic + Gemini Developer API free tier for MVP experimentation. Paid cloud ASR is a fallback only when local/subtitle transcription is insufficient.

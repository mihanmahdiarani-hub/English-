---
title: English AI Tutor
emoji: 🎧
colorFrom: purple
colorTo: blue
sdk: docker
app_port: 7860
fullWidth: true
short_description: Private live English tutor powered by Gemini Live
---

# English AI Tutor

A personal live English tutor with Teacher, Free Talk, and Shadowing modes.

## Hugging Face Space deployment

This branch is prepared for a **Hugging Face Docker Space**. The container listens on port `7860` and keeps the Gemini API key server-side.

### Required Space secrets
Add these in **Space → Settings → Variables and secrets**:

- `GEMINI_API_KEY` — required
- `GEMINI_API_KEY_2` — optional backup key

### Optional Space variables

- `GEMINI_LIVE_MODEL` — fallback model used only if automatic model discovery fails; default `gemini-3.8-live`
- `GEMINI_ANALYSIS_MODEL` — analysis model; default is defined by the server
- `GEMINI_LIVE_MODEL_CACHE_MS` — cache duration for automatic Live-model selection

The server calls Gemini's Models API, discovers available general Live models, selects the best candidate, and rewrites the Live setup frame on the server. The permanent Gemini API key is never sent to the browser.

## What works

- Teacher / Free Talk / Shadowing modes
- Live microphone streaming to Gemini Live API through the server WebSocket proxy
- Automatic Gemini Live model discovery and selection
- Server-side API-key failover
- Native audio playback from Gemini
- Input/output transcription in chat
- Source-lock instruction for lesson-based teaching
- PDF and media learning flows
- Local progress storage
- Installable PWA / Android shell support

## Local run

Install Node.js 18+ and run:

```bash
GEMINI_API_KEY="YOUR_KEY" npm start
```

Then open `http://localhost:3000`.

## Important

Do not commit Gemini API keys into this repository. On Hugging Face, store them only as **Space Secrets**.

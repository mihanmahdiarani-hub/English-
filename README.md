# English AI Tutor — MVP

A personal, book-locked live English tutor PWA.

## What works
- Teacher / Free Talk / Shadowing modes
- Live microphone streaming to Gemini Live API
- Native audio playback from Gemini (24 kHz PCM)
- Input/output transcription in the chat
- Source-lock system instruction
- PDF page extraction in the browser (PDF.js from jsDelivr)
- Progress saved locally in the browser
- Ephemeral-token backend so the permanent Gemini API key is not exposed to the browser
- Installable PWA shell

## Run locally
1. Install Node.js 18+.
2. Set your Gemini API key in the terminal:

Windows PowerShell:
```powershell
$env:GEMINI_API_KEY="YOUR_KEY"
node server.js
```

macOS/Linux:
```bash
GEMINI_API_KEY="YOUR_KEY" node server.js
```

3. Open `http://localhost:3000` in Chrome/Edge.
4. Choose a PDF or paste lesson text, then click **شروع کلاس زنده** and allow the microphone.

## Environment variables
- `GEMINI_API_KEY` (required for Live voice)
- `GEMINI_LIVE_MODEL` (optional, default `gemini-3.8-live`)
- `PORT` (optional, default 3000)

## Important MVP limitation
The app locks the live teacher to the **selected/extracted lesson pages**, not the entire textbook database. This is deliberate for the first test. A later version can add a proper indexed book library/RAG layer and server-side progress database.

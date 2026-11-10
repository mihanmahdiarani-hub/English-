# English AI Tutor — optional free-tier AI chat providers

**Scope:** English AI Tutor only. Gemini stays enabled and unchanged. No deployment, payment, or Android release is part of this PR.

## Configure in Liara, not in GitHub

In the existing English AI Tutor Liara Node.js app, set these runtime environment variables:

- **GROQ_API_KEY** — personal Groq API key, from a free-plan account only.
- **OPENROUTER_API_KEY** — personal OpenRouter API key.
- **AI_TUTOR_ACCESS_CODE** — long random app-to-backend access code; alternatively reuse the existing GEMINI_CHAT_ACCESS_CODE.

Never commit real keys or access codes to GitHub, APK, samples, chat messages, or issue comments. GitHub Actions Secrets do not automatically configure a deployed Liara server. Existing Gemini API/Firebase settings stay intact.

## Android chat setup

In the full-screen chat for a selected dialogue, choose Gemini, Groq, or OpenRouter. For Groq/OpenRouter, use the server settings button to enter the existing English AI Tutor Liara HTTPS URL and the access code. The URL is restricted to HTTPS on a Liara hostname.

Only the **current dialogue text, question and short chat history** are sent to the chosen provider. The movie video/audio never leaves the device for these text requests. No provider API key is compiled into the Android app.

## Model and cost safeguards

- **OpenRouter** uses only the free router ID **openrouter/free**. The server refuses user-supplied model IDs, base URLs or paid routing. Local cap: 40 requests/day/IP; upstream free limits apply and model selection can change.
- **Groq** uses **openai/gpt-oss-120b**. Only activate on a verified free Groq plan; Groq may charge on paid plans. A software request cap alone cannot guarantee no billing once a paid plan is enabled. The backend caps requests at 100/day/IP, but this resets when the app restarts.
- No automatic retry/fallback to a paid model, and no payment or credit purchase is implemented.
- Provider availability and VPN requirements vary by region and are not guaranteed.

## Endpoint

POST **/api/ai/chat** with JSON body containing provider (groq or openrouter), question, dialogue and optional history; send the **X-AI-Tutor-Code** header with the access code.

An invalid provider or custom model or URL is rejected. Responses return only safe text, provider and model details. Keys and raw upstream errors are not logged.

## Testing and release

Run **npm run test:multi-ai** for local mock-only tests, which use no real provider credentials, network API credits or payment. Actual network operation needs Liara secrets and a separately approved Liara deployment. GitHub commits alone do not update the running server or installed APK.

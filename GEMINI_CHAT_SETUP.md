# English AI Tutor — Gemini Manual Chat

This feature belongs **only** to English AI Tutor.

## Included

- Main-screen entry: `public/index.html` → `gemini-chat.html`
- Standalone, mobile-friendly page: `public/gemini-chat.html`, `gemini-chat.css`, `gemini-chat.js`
- Separate, server-side chat route: `gemini-chat-api.js`, preloaded by `npm start`
- Tests: `npm run test:gemini-chat` (mocks Google; consumes no Gemini quota)

Text chat never uses the video/lesson Gemini queue. Conversations are kept in localStorage on the user's device. Requests to Google go through the server. No Gemini API key is embedded into the page or APK.

## Required production secrets

On the **English AI Tutor** backend service, configure:

- `GEMINI_API_KEY` — valid Google Gemini API key.
- `GEMINI_CHAT_ACCESS_CODE` — private, long, random access code for this personal chat. **Required**; without it, chat requests fail closed with HTTP 503. Do not commit the value to GitHub.
- `GEMINI_CHAT_MODEL` — optional preferred default model. Available models are loaded from Google's `models.list`.

The chat screen has a "⚙ دسترسی" section where the user enters the chat access code (not the API key). The code is kept only in the WebView/browser session. The backend restricts origins and applies per-minute throttling.

## Publish

1. Run `npm run test:gemini-chat`.
2. Deploy the latest GitHub `main` to the **English AI Tutor app on Liara**, following `LIARA_DEPLOY.md` after reviewing plan and account access.
3. Build a new signed Android APK from the updated source. Include all three `public/gemini-chat.*` files in the APK's `assets/www/` directory and the modified `index.html`. Sign with the original signing identity and increment the Android version.
4. Install/update the new APK, open **✦ چت Gemini**, enter the Liara backend address (`https://<your-app>.liara.run`) and private chat access code, then try a short message.

The existing APK does not get these new assets just because the GitHub repository changed.

## Gemini 403

The previously reported **HTTP 403 / Google HTML error page** is independent of this UI feature. The chat server deliberately displays a concise diagnostic instead of rendering the HTML error or repeating blocked requests. Validate the key, model access, deployment region and connection before declaring real Gemini chat operational.

Liara setup is documented in `LIARA_DEPLOY.md`. A GitHub commit alone does not publish the backend. Render is not used for this deployment.

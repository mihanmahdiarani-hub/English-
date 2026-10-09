# English AI Tutor — Liara deployment

Scope: **English AI Tutor only**. Render and every other project are untouched.

## Repository

GitHub: https://github.com/mihanmahdiarani-hub/English-
Branch: `main`
Platform: Liara **Node.js 22**
HTTP port: `3000`
Public frontend: `/` and `/gemini-chat.html`
API: `/health`, `/api/gemini-chat/models`, `/api/gemini-chat` and existing English AI Tutor routes
Build settings: `liara.json`

Do **not** create an unrelated repository. Do **not** use Liara's Static platform: the project includes a Node.js server, WebSocket proxy, protected credentials, and audio-processing routes.

## Deployment through Liara console

1. Open https://console.liara.ir/apps .
2. Select an **existing English AI Tutor Node.js app** if one exists. Otherwise creation is required and may incur costs; do not create one without reviewing the plan.
3. Under the Liara account's GitHub integration (https://console.liara.ir/settings/github), grant access to the **English-** repository only.
4. In the app's **New Deployment** area, select GitHub repository `mihanmahdiarani-hub/English-`, branch `main`. Initially prefer a manual deployment to confirm the release works; do not enable automatic deploy without reviewing billing and testing.
5. Add these secrets in the app's environment settings (**never commit their values to GitHub**):
   - `GEMINI_API_KEY`: Gemini key with required permissions
   - `GEMINI_CHAT_ACCESS_CODE`: private random access code for the manual chat UI
   - optional `GEMINI_API_KEY_2` and `GEMINI_CHAT_MODEL`
6. Deploy from the Liara Console, then open `https://<your-app-id>.liara.run/health`, and `https://<your-app-id>.liara.run/gemini-chat.html`.
7. Enter the private chat access code under ⚙. For Android WebView builds, also enter the Liara backend URL there and install an APK whose bundled page contains the new chat files.

**Note:** A GitHub commit does not deploy to Liara by itself. An existing app, its account access and its environment secrets are required.

## CLI alternative

After authenticating on a machine with your Liara access:

```bash
npm install -g @liara/cli
liara login
liara deploy --app <existing-liara-app-id> --platform node --port 3000
```

Read the Liara app's pricing and resource choices before creating a new app.

## Health checks

- `GET /health` should return JSON and show at least one configured Gemini key.
- `GET /api/gemini-chat/models` with `X-Gemini-Chat-Code` should return a JSON list of available generation models.
- `POST /api/gemini-chat` requires the same private access code. A `GEMINI_ACCESS_DENIED` error does **not** mean Liara deployed incorrectly: test your Gemini key and Google regional eligibility.

**Regional caveat:** Google AI Studio and Gemini API are not listed as available in Iran in Google's documented supported regions. Running this backend in an Iran-based runtime may still return HTTP 403. Configuring a Germany **build** location in `liara.json` changes only where Node dependencies build; it does not change the app's runtime location or Google's usage rules.

## APK integration

The checked-in web files and server deploy to Liara, but the already installed Android APK is a distinct compiled artifact and is not automatically updated by deployment. Rebuild/re-sign Android from the correct source, preserving the original package identity and signing key. The manual chat screen now has a Liara URL field for embedded-WebView builds and no longer hardcodes a Render endpoint.

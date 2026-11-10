# English AI Tutor V2 — personal AI keys, no server

This feature is designed **only for the owner of English AI Tutor**. It does not use Liara, Render, a GitHub-hosted proxy or any other backend for Groq / OpenRouter.

## Configure on the phone

1. Install a future **separately approved** Android build that contains this feature.
2. Open any movie dialogue, then open the full-screen AI tutor chat.
3. Tap the provider selector and choose **Groq** or **OpenRouter**.
4. Tap **🔑 کلید API** and enter your **personal** API key for the selected provider. The key is encrypted on-device by **AndroidKeyStore AES-GCM**; only encrypted bytes are stored in private SharedPreferences.
5. Press **ذخیره امن**. The key is never embedded in source, the compiled APK, GitHub Secrets or a server.
6. Send a question or use one of the quick prompts. Groq / OpenRouter calls are **manual**, not automatic; switching back to **Gemini** uses the existing Firebase AI Logic path.

Keys can be replaced or deleted from the same settings dialog. Do not paste provider keys in GitHub issues, commits, screenshots or this chat. Android device backup may preserve encrypted data but cannot export the Keystore encryption key to another device.

## Fixed API endpoints and models

| Provider | API endpoint | Model |
| --- | --- | --- |
| Groq | https://api.groq.com/openai/v1/chat/completions | openai/gpt-oss-120b |
| OpenRouter | https://openrouter.ai/api/v1/chat/completions | openrouter/free |

The app **does not** support arbitrary model identifiers, custom URLs, or automatic paid-provider fallbacks. OpenRouter's free router can change the underlying model. Groq models are only free **when your own Groq plan provides that model in its free tier**. This app does not buy credits or enable paid plans; if you choose a paid provider account, the provider may still bill it, so verify both accounts remain on free plans before use.

## Privacy and connectivity

Only the currently selected English subtitle, the submitted question, and at most 12 short chat history messages go directly via HTTPS to the chosen provider. No movie video or audio file is uploaded through this client. API keys are transmitted in an Authorization header **only to the fixed API hostname**; HTTPS redirects are disabled. No raw upstream error response or secret is logged.

The phone needs network access to Groq and OpenRouter. **No guarantee of VPN-free connectivity** is possible; regional provider restrictions may still apply.

## GitHub and releases

GitHub is used for **source control and code-review tests**. It cannot act as the always-running API server for the phone, and **there is no reason to store your personal API keys in GitHub Secrets** for direct-to-provider Android requests.

This change stays in **draft PR #35** on the V2 branch pending user approval to merge and release. Android source compilation is tested on pull requests without publishing an APK. Gemini is preserved. The unrelated personal Gemini web backend is not modified.

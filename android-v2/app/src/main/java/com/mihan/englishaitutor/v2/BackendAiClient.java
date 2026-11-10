package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

/**
 * Text-only gateway for Groq/OpenRouter through the existing English AI Tutor
 * Liara backend. The APK never contains or receives provider API keys.
 * The user-entered access code is distinct from the provider's API key.
 */
final class BackendAiClient {
    private static final String PREFS = "english_tutor_ai_gateway_v1";
    private static final String URL_KEY = "liara_url";
    private static final String ACCESS_KEY = "access_code";
    private static final String PROVIDER_KEY = "selected_provider";
    static final String GEMINI = "gemini";
    static final String GROQ = "groq";
    static final String OPENROUTER = "openrouter";

    private BackendAiClient() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String provider(Context context) {
        String value = prefs(context).getString(PROVIDER_KEY, GEMINI);
        return GROQ.equals(value) || OPENROUTER.equals(value) ? value : GEMINI;
    }

    static void choose(Context context, String value) {
        if (!GEMINI.equals(value) && !GROQ.equals(value) && !OPENROUTER.equals(value))
            throw new IllegalArgumentException("Unknown AI provider");
        prefs(context).edit().putString(PROVIDER_KEY, value).apply();
    }

    static String name(String provider) {
        if (GROQ.equals(provider)) return "Groq";
        if (OPENROUTER.equals(provider)) return "OpenRouter";
        return "Gemini";
    }

    static String serverUrl(Context context) {
        return prefs(context).getString(URL_KEY, "");
    }

    static String accessCode(Context context) {
        return prefs(context).getString(ACCESS_KEY, "");
    }

    static boolean configured(Context context) {
        return validLiaraUrl(serverUrl(context)) && !accessCode(context).isEmpty();
    }

    static boolean validLiaraUrl(String input) {
        if (input == null || input.length() > 200) return false;
        try {
            URI uri = new URI(input.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !host.matches("[a-zA-Z0-9][a-zA-Z0-9-]*[.]liara[.]run")
                    || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) return false;
            String path = uri.getPath();
            return path == null || path.isEmpty() || "/".equals(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean save(Context context, String url, String access) {
        if (url == null || access == null) return false;
        String cleanUrl = url.trim();
        if (cleanUrl.endsWith("/")) cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
        String cleanAccess = access.trim();
        if (!validLiaraUrl(cleanUrl) || cleanAccess.length() < 8
                || cleanAccess.length() > 250) return false;
        prefs(context).edit()
                .putString(URL_KEY, cleanUrl)
                .putString(ACCESS_KEY, cleanAccess)
                .apply();
        return true;
    }

    static void ask(Context context, String provider, String question,
                    String dialogue, List<String> history,
                    GeminiLessonService.ChatCallback callback) {
        if (!GROQ.equals(provider) && !OPENROUTER.equals(provider)) {
            postError(callback, "سرویس انتخاب‌شده معتبر نیست.");
            return;
        }
        final String baseUrl = serverUrl(context);
        final String access = accessCode(context);
        if (!validLiaraUrl(baseUrl) || access.isEmpty()) {
            postError(callback, "ابتدا آدرس سرور Liara و کد دسترسی را در تنظیمات وارد کن.");
            return;
        }
        final JSONObject payload = new JSONObject();
        try {
            payload.put("provider", provider);
            payload.put("question", question == null ? "" : question);
            payload.put("dialogue", dialogue == null ? "" : dialogue);
            JSONArray turns = new JSONArray();
            if (history != null) {
                int start = Math.max(0, history.size() - 12);
                for (int i = start; i < history.size(); i++) {
                    String line = history.get(i);
                    if (line == null) continue;
                    String role;
                    if (line.startsWith("User: ")) role = "user";
                    else if (line.startsWith("Tutor: ")) role = "assistant";
                    else continue;
                    JSONObject turn = new JSONObject();
                    turn.put("role", role);
                    turn.put("content", line.substring(line.indexOf(':') + 1).trim());
                    turns.put(turn);
                }
            }
            payload.put("history", turns);
        } catch (Exception ignored) {
            postError(callback, "آماده‌سازی پیام ناموفق بود.");
            return;
        }
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(baseUrl + "/api/ai/chat");
                connection = (HttpsURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(36000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("X-AI-Tutor-Code", access);
                connection.setRequestProperty("Cache-Control", "no-store");
                byte[] request = payload.toString().getBytes(StandardCharsets.UTF_8);
                if (request.length > 30000) {
                    postError(callback, "پیام بیش از حد طولانی است.");
                    return;
                }
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(request);
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String result = readBounded(stream);
                JSONObject parsed = new JSONObject(result);
                if (status >= 200 && status < 300 && parsed.optBoolean("ok")) {
                    String text = parsed.optString("text", "").trim();
                    if (text.isEmpty()) postError(callback, "سرویس پاسخی برنگرداند.");
                    else postSuccess(callback, text);
                } else {
                    // Our backend returns sanitized errors; never expose raw provider data.
                    String message = parsed.optString("error", "").trim();
                    postError(callback, message.isEmpty()
                            ? "سرویس در دسترس نیست یا سهمیه رایگان آن تمام شده است."
                            : message);
                }
            } catch (Exception ignored) {
                postError(callback, "اتصال به سرور English AI Tutor برقرار نشد. آدرس Liara و اینترنت را بررسی کن.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "EnglishTutorAiGateway").start();
    }

    private static String readBounded(InputStream input) throws Exception {
        if (input == null) throw new IllegalStateException("No response");
        try (InputStream stream = input;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (out.size() + count > 100000)
                    throw new IllegalStateException("Response too long");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void postSuccess(GeminiLessonService.ChatCallback callback, String text) {
        if (callback != null) new Handler(Looper.getMainLooper())
                .post(() -> callback.onSuccess(text));
    }

    private static void postError(GeminiLessonService.ChatCallback callback, String message) {
        if (callback != null) new Handler(Looper.getMainLooper())
                .post(() -> callback.onError(message));
    }
}

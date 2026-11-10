package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * Direct personal Gemini Developer API client. No Firebase initialization, App Check,
 * Firebase config, proxy server, or embedded provider key is involved.
 *
 * Only text prompts are transmitted to Google's fixed HTTPS API endpoint. The user's
 * personal API key is encrypted with a non-exportable AndroidKeyStore AES-GCM key before
 * it reaches app-private preferences, and is never logged, rendered, or committed.
 */
final class GeminiDirectClient {
    static final String MODEL = "gemini-3.5-flash-lite";
    private static final String URL_STRING =
            "https://generativelanguage.googleapis.com/v1beta/models/"
                    + MODEL + ":generateContent";
    private static final String PREFS = "english_tutor_gemini_direct_v1";
    private static final String KEY_PREF = "gemini_api_key_encrypted";
    private static final String AUTO_PREF = "allow_automatic_gemini_lessons";
    private static final String KEY_ALIAS = "english_tutor_gemini_direct_aes_v1";
    private static final int MAX_PROMPT_CHARS = 28_000;
    private static final int MAX_REQUEST_BYTES = 110_000;
    private static final int MAX_RESPONSE_BYTES = 750_000;
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(2);
    private final Context context;

    GeminiDirectClient(Context context) {
        this.context = context.getApplicationContext();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean saveKey(Context context, String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        if (value.length() < 16 || value.length() > 512
                || value.contains(" ") || value.contains("\n") || value.contains("\r")
                || value.contains("\t")) return false;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != 12) return false;
            byte[] combined = new byte[12 + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, 12);
            System.arraycopy(encrypted, 0, combined, 12, encrypted.length);
            // Never store plaintext, including when encryption fails.
            return prefs(context).edit().putString(
                    KEY_PREF, Base64.encodeToString(combined, Base64.NO_WRAP)).commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    static void deleteKey(Context context) {
        prefs(context).edit().remove(KEY_PREF).putBoolean(AUTO_PREF, false).apply();
    }

    static void setAutomaticLessons(Context context, boolean allowed) {
        prefs(context).edit().putBoolean(AUTO_PREF, allowed).apply();
    }

    static boolean automaticLessonsEnabled(Context context) {
        // Default OFF: opening/playing a film must not silently incur API usage.
        return prefs(context).getBoolean(AUTO_PREF, false);
    }

    boolean hasKey() {
        return !readKey(context).isEmpty();
    }

    private static synchronized SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS))
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    // Only the in-process Live client may obtain the decrypted owner key.
    // It is transient: never put it in an Intent, logs, UI or preferences.
    static String readKeyForLiveSession(Context context) {
        return readKey(context);
    }

    private static String readKey(Context context) {
        String packed = prefs(context).getString(KEY_PREF, "");
        if (packed == null || packed.isEmpty()) return "";
        try {
            byte[] bytes = Base64.decode(packed, Base64.NO_WRAP);
            if (bytes.length < 29) return "";
            byte[] iv = Arrays.copyOfRange(bytes, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(bytes, 12, bytes.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // A restored encrypted preference is unusable without the same keystore key.
            return "";
        }
    }

    ListenableFuture<String> generate(String promptText, boolean lessonJson) {
        SettableFuture<String> future = SettableFuture.create();
        if (promptText == null || promptText.isEmpty()
                || promptText.length() > MAX_PROMPT_CHARS) {
            future.setException(new IOException("متن درخواست بیش از حد طولانی است."));
            return future;
        }

        NETWORK.execute(() -> {
            HttpsURLConnection connection = null;
            try {
                String apiKey = readKey(context);
                if (apiKey.isEmpty()) {
                    future.setException(new IOException(
                            "کلید Gemini روی گوشی ذخیره نشده است. از تنظیمات Gemini وارد کن."));
                    return;
                }

                JSONObject request = new JSONObject();
                request.put("contents", new JSONArray().put(
                        new JSONObject().put("role", "user").put("parts",
                                new JSONArray().put(new JSONObject().put("text", promptText)))));
                JSONObject generation = new JSONObject()
                        .put("temperature", 0.3)
                        .put("maxOutputTokens", lessonJson ? 8192 : 2048);
                if (lessonJson) generation.put("responseMimeType", "application/json");
                request.put("generationConfig", generation);
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                if (body.length > MAX_REQUEST_BYTES)
                    throw new IOException("متن درخواست بیش از حد طولانی است.");

                // No user-provided URL, redirect, local HTTP, or proxy.
                connection = (HttpsURLConnection) new URL(URL_STRING).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(48_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Cache-Control", "no-store");
                connection.setRequestProperty("x-goog-api-key", apiKey);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
                int status = connection.getResponseCode();
                if (status != 200)
                    throw new IOException(statusMessage(status));

                JSONObject result = new JSONObject(readBounded(connection.getInputStream()));
                JSONArray candidates = result.optJSONArray("candidates");
                JSONObject first = candidates == null ? null : candidates.optJSONObject(0);
                JSONObject responseContent = first == null
                        ? null : first.optJSONObject("content");
                JSONArray parts = responseContent == null
                        ? null : responseContent.optJSONArray("parts");
                StringBuilder response = new StringBuilder();
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;
                        String text = part.optString("text", "");
                        if (!text.isEmpty()) response.append(text);
                    }
                }
                if (response.length() == 0)
                    throw new IOException("پاسخ Gemini خالی یا توسط مدل مسدود شده است.");
                future.set(response.toString());
            } catch (IOException e) {
                // Only app-authored, sanitized messages reach diagnostics or UI.
                String message = e.getMessage();
                if (message != null && (message.startsWith("متن درخواست")
                        || message.startsWith("کلید Gemini")
                        || message.startsWith("Gemini:")
                        || message.startsWith("پاسخ Gemini"))) {
                    future.setException(e);
                } else {
                    future.setException(new IOException(
                            "شبکه یا ارتباط HTTPS با Gemini در دسترس نیست."));
                }
            } catch (Exception ignored) {
                future.setException(new IOException(
                        "ارتباط مستقیم Gemini ناموفق بود. اینترنت و کلید API را بررسی کن."));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
        return future;
    }

    private static String statusMessage(int status) {
        switch (status) {
            case 400: return "Gemini: مدل یا فرمت درخواست توسط Google رد شد (HTTP 400).";
            case 401: return "Gemini: کلید API معتبر نیست (HTTP 401).";
            case 403: return "Gemini: دسترسی API یا منطقه مجاز نیست (HTTP 403).";
            case 404: return "Gemini: مدل انتخاب‌شده در پروژه در دسترس نیست (HTTP 404).";
            case 429: return "Gemini: سهمیه یا محدودیت نرخ درخواست پر شده (HTTP 429).";
            case 500:
            case 502:
            case 503:
            case 504: return "Gemini: اختلال موقت سرویس (HTTP " + status + ").";
            default: return "Gemini: درخواست ناموفق بود (HTTP " + status + ").";
        }
    }

    private static String readBounded(InputStream stream) throws Exception {
        if (stream == null) throw new IOException("پاسخ Gemini خالی است.");
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n == 0) continue;
                if (out.size() + n > MAX_RESPONSE_BYTES)
                    throw new IOException("پاسخ Gemini بیش از حد بزرگ است.");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}

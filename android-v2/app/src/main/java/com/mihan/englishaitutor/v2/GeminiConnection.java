package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared Gemini connection for the full-screen chat and vocabulary activity.
 * MainActivity previously had an asset fallback; the standalone chat did
 * not, leaving Gemini unexpectedly disabled after opening a new screen.
 *
 * Never log, display, export or transmit Firebase configuration secrets.
 */
final class GeminiConnection {
    private GeminiConnection() {}

    static boolean configure(Context context, GeminiLessonService tutor) {
        if (context == null || tutor == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(
                "english_tutor_ai_config", Context.MODE_PRIVATE);
        if (tutor.configure(
                prefs.getString("firebase_api_key", ""),
                prefs.getString("firebase_app_id", ""),
                prefs.getString("firebase_project_id", ""))) return true;

        try (InputStream in = context.getAssets().open("google-services.json");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n == 0) continue;
                if (out.size() + n > 512 * 1024)
                    throw new IllegalArgumentException("Bundled Firebase config too large");
                out.write(buf, 0, n);
            }
            JSONObject root = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            String projectId = root.getJSONObject("project_info")
                    .optString("project_id", "");
            JSONArray clients = root.optJSONArray("client");
            if (clients == null) return false;
            for (int i = 0; i < clients.length(); i++) {
                JSONObject client = clients.optJSONObject(i);
                if (client == null) continue;
                JSONObject info = client.optJSONObject("client_info");
                if (info == null) continue;
                JSONObject android = info.optJSONObject("android_client_info");
                if (android == null || !context.getPackageName().equals(
                        android.optString("package_name", ""))) continue;
                JSONArray keys = client.optJSONArray("api_key");
                if (keys == null || keys.length() == 0) return false;
                JSONObject api = keys.optJSONObject(0);
                if (api == null) return false;
                return tutor.configure(api.optString("current_key", ""),
                        info.optString("mobilesdk_app_id", ""), projectId);
            }
        } catch (Exception error) {
            Diagnostics.log("GEMINI_CONFIG", "chat/vocab Firebase configuration unavailable: "
                    + error.getClass().getSimpleName());
        }
        return false;
    }
}

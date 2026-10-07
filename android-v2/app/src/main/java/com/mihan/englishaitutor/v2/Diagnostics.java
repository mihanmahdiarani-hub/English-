package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight private diagnostic log for English AI Tutor.
 * Never logs Firebase API keys, media bytes, or full dialogue text.
 */
public final class Diagnostics {
    private static final String TAG = "EnglishAITutor";
    private static final String FILE = "diagnostics.log";
    private static final long MAX_BYTES = 160L * 1024L;
    private static final Object LOCK = new Object();
    private static Context appContext;

    private Diagnostics() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        log("APP", "start v=" + BuildConfig.VERSION_NAME
                + " sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + "/" + Build.MODEL);
    }

    public static void log(String area, String message) {
        String clean = sanitize(message);
        String line = timestamp() + " [" + safe(area) + "] " + clean;
        Log.i(TAG, line);
        Context c = appContext;
        if (c == null) return;
        synchronized (LOCK) {
            try {
                File f = new File(c.getFilesDir(), FILE);
                if (f.exists() && f.length() > MAX_BYTES) rotate(f);
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void error(String area, Throwable t) {
        String msg = t == null ? "unknown" : t.getClass().getSimpleName() + ": " + t.getMessage();
        log(area, "ERROR " + msg);
    }

    public static String dump(Context context) {
        synchronized (LOCK) {
            File f = new File(context.getFilesDir(), FILE);
            if (!f.isFile()) return "هنوز لاگی ثبت نشده.";
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            } catch (Throwable t) {
                return "خواندن لاگ ناموفق بود: " + t.getClass().getSimpleName();
            }
            return out.toString();
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            try {
                File f = new File(context.getFilesDir(), FILE);
                if (f.exists()) f.delete();
            } catch (Throwable ignored) {}
        }
        log("APP", "diagnostics cleared");
    }

    private static void rotate(File f) {
        try {
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            int keep = (int) Math.min(all.length, MAX_BYTES / 2L);
            int start = all.length - keep;
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(all, start, keep);
            }
        } catch (Throwable ignored) {
            try { f.delete(); } catch (Throwable ignored2) {}
        }
    }

    private static String sanitize(String value) {
        String s = value == null ? "" : value;
        s = s.replaceAll("AIza[0-9A-Za-z_\\-]{20,}", "<redacted-api-key>");
        s = s.replaceAll("(?i)(api[_ -]?key\\s*[=:]\\s*)[^\\s,;]+", "$1<redacted>");
        if (s.length() > 1200) s = s.substring(0, 1200) + "…";
        return s;
    }

    private static String safe(String s) {
        return s == null ? "?" : s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}

package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * One-time, verified ~95 MB on-device English CTC acoustic model.
 *
 * NOT bundled in the APK. The model remains in English AI Tutor private
 * app storage and is used entirely offline once downloaded.
 *
 * Upstream: Xenova/wav2vec2-base-960h, model_quantized.onnx.
 * SHA-256 sourced from the Hugging Face immutable Xet file pointer.
 * MIT-licensed upstream model (facebook/wav2vec2-base-960h).
 */
final class AlignerModelManager {
    private static final String MODEL_NAME = "wav2vec2-english-ctc-int8-v1.onnx";
    private static final String MODEL_URL =
            "https://huggingface.co/Xenova/wav2vec2-base-960h/"
                    + "resolve/main/onnx/model_quantized.onnx?download=true";
    private static final String MODEL_SHA256 =
            "cd5040c147381580ed73258143dd8e0c28e800a09e74ee42ee2b3e8cb4d760a3";
    private static final long MIN_BYTES = 90_000_000L;
    private static final long MAX_BYTES = 110_000_000L;
    private static final String PREFS = "english_tutor_ctc_model";
    private static final String VERIFIED_LEN = "verified_length";
    private static final String VERIFIED_MODIFIED = "verified_modified";

    interface Progress {
        void update(String message);
    }

    private AlignerModelManager() {}

    private static File targetFile(Context context) {
        File directory = context.getExternalFilesDir("models");
        if (directory == null) directory = new File(context.getFilesDir(), "models");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create local speech models directory");
        }
        return new File(directory, MODEL_NAME);
    }

    private static boolean verified(Context context, File target) {
        if (!target.isFile() || target.length() < MIN_BYTES
                || target.length() > MAX_BYTES) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getLong(VERIFIED_LEN, -1L) == target.length()
                && prefs.getLong(VERIFIED_MODIFIED, -1L) == target.lastModified()) {
            return true;
        }
        if (!sha256Matches(target)) return false;
        prefs.edit().putLong(VERIFIED_LEN, target.length())
                .putLong(VERIFIED_MODIFIED, target.lastModified()).commit();
        return true;
    }

    static boolean isReady(Context context) {
        try { return verified(context, targetFile(context)); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean sha256Matches(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 262144)) {
            MessageDigest dig = MessageDigest.getInstance("SHA-256");
            byte[] data = new byte[262144];
            int n;
            while ((n = in.read(data)) >= 0) {
                if (n > 0) dig.update(data, 0, n);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : dig.digest()) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return MODEL_SHA256.equals(hex.toString());
        } catch (Exception failure) {
            Diagnostics.error("ALIGNER_SHA", failure);
            return false;
        }
    }

    /**
     * Must run on a background worker (never on Android's main thread).
     * A resumable HTTP Range request avoids redownloading 95MB after a
     * connection drop. Cancellation leaves a harmless .part file.
     */
    static synchronized File ensureReady(Context context, Progress progress)
            throws Exception {
        Context app = context.getApplicationContext();
        File target = targetFile(app);
        if (verified(app, target)) return target;

        // Invalid final model is never accepted. Delete it before retry.
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Corrupted alignment model cannot be replaced");
        }
        File part = new File(target.getParentFile(), MODEL_NAME + ".part");
        if (part.length() > MAX_BYTES) part.delete();
        long already = part.isFile() ? part.length() : 0L;
        if (progress != null) {
            progress.update("مدل تطبیق کلمه و صدا (~۹۵ مگابایت) یک‌بار دانلود می‌شود...");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "EnglishAITutor-CTC/1.0");
            if (already > 0L) connection.setRequestProperty("Range", "bytes=" + already + "-");
            int response = connection.getResponseCode();
            boolean append = response == HttpURLConnection.HTTP_PARTIAL && already > 0;
            if (response != HttpURLConnection.HTTP_OK
                    && response != HttpURLConnection.HTTP_PARTIAL) {
                throw new IllegalStateException("Alignment model server HTTP " + response);
            }
            if (!append) already = 0L;
            // Sanity-check Content-Range when continuing an interrupted download.
            if (append) {
                String contentRange = connection.getHeaderField("Content-Range");
                if (contentRange == null || !contentRange.startsWith("bytes " + already + "-")) {
                    throw new IllegalStateException("Model server returned wrong range");
                }
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 262144);
                 FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[262144];
                int previousPercent = -1;
                long written = already;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted())
                        throw new InterruptedException("Alignment download cancelled");
                    if (n == 0) continue;
                    written += n;
                    if (written > MAX_BYTES) throw new IllegalStateException("Oversized alignment model");
                    out.write(buf, 0, n);
                    int percent = (int) (written * 100L / 95_300_000L);
                    if (progress != null && percent / 5 != previousPercent / 5) {
                        previousPercent = percent;
                        progress.update("دانلود مدل تطبیق گفتار: " + Math.min(100, percent) + "٪");
                    }
                }
                out.flush();
            }
            if (part.length() < MIN_BYTES || !sha256Matches(part)) {
                part.delete();
                throw new IllegalStateException("SHA-256 مدل هم‌ترازی معتبر نیست");
            }
            if (!part.renameTo(target)) {
                throw new IllegalStateException("Saving verified alignment model failed");
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(VERIFIED_LEN, target.length())
                    .putLong(VERIFIED_MODIFIED, target.lastModified()).commit();
            Diagnostics.log("ALIGNER_MODEL", "CTC model verified bytes=" + target.length());
            if (progress != null) progress.update("مدل تطبیق گفتار آماده شد.");
            return target;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

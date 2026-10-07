package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads the free tiny.en whisper.cpp model once, then keeps it on-device. */
public final class ModelManager {
    private static final String MODEL_NAME = "ggml-tiny.en.bin";
    private static final String MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin?download=true";
    private static final long MIN_VALID_BYTES = 50L * 1024L * 1024L;
    private static final long MAX_IMPORT_BYTES = 120L * 1024L * 1024L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ModelManager() {}

    public interface Listener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onReady(File modelFile);
        void onError(String message);
    }

    public static File modelFile(Context context) {
        File dir = context.getExternalFilesDir("models");
        if (dir == null) dir = new File(context.getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MODEL_NAME);
    }

    public static boolean isReady(Context context) {
        File f = modelFile(context);
        return f.isFile() && f.length() >= MIN_VALID_BYTES;
    }


    public static void importModel(Context context, Uri uri, Listener listener) {
        File finalFile = modelFile(context);
        EXECUTOR.execute(() -> {
            File partial = new File(finalFile.getParentFile(), MODEL_NAME + ".import.part");
            try {
                if (partial.exists()) partial.delete();
                long done = 0L;
                try (InputStream in = new BufferedInputStream(context.getContentResolver().openInputStream(uri), 256 * 1024);
                     FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[256 * 1024];
                    int n;
                    int lastMb = -1;
                    while ((n = in.read(buffer)) >= 0) {
                        if (n == 0) continue;
                        out.write(buffer, 0, n);
                        done += n;
                        if (done > MAX_IMPORT_BYTES) {
                            throw new IllegalArgumentException("فایل انتخاب‌شده بزرگ‌تر از مدل tiny.en است");
                        }
                        int mb = (int) (done / (1024L * 1024L));
                        if (mb != lastMb) {
                            lastMb = mb;
                            long d = done;
                            MAIN.post(() -> listener.onProgress(-1, d, -1L));
                        }
                    }
                    out.flush();
                }

                if (partial.length() < MIN_VALID_BYTES) {
                    throw new IllegalArgumentException("فایل انتخاب‌شده مدل Whisper معتبر نیست یا ناقص است");
                }
                if (finalFile.exists() && !finalFile.delete()) {
                    throw new IllegalStateException("مدل قبلی قابل جایگزینی نیست");
                }
                if (!partial.renameTo(finalFile)) {
                    throw new IllegalStateException("ذخیره مدل کامل نشد");
                }
                Diagnostics.log("WHISPER_MODEL", "imported bytes=" + finalFile.length());
                MAIN.post(() -> listener.onReady(finalFile));
            } catch (Throwable t) {
                if (partial.exists()) partial.delete();
                Diagnostics.error("WHISPER_MODEL_IMPORT", t);
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                MAIN.post(() -> listener.onError(message));
            }
        });
    }

    private static boolean installBundledModel(Context context, File finalFile, File partial, Listener listener) throws Exception {
        InputStream raw;
        try {
            raw = context.getAssets().open(MODEL_NAME);
        } catch (IOException missing) {
            return false;
        }

        Diagnostics.log("WHISPER_MODEL", "bundled asset found; installing locally");
        if (partial.exists()) partial.delete();
        try (InputStream in = new BufferedInputStream(raw, 256 * 1024);
             FileOutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[256 * 1024];
            long done = 0L;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                out.write(buffer, 0, n);
                done += n;
                if ((done % (8L * 1024L * 1024L)) < n) {
                    long d = done;
                    MAIN.post(() -> listener.onProgress(-1, d, -1L));
                }
            }
            out.flush();
        }

        if (partial.length() < MIN_VALID_BYTES) {
            partial.delete();
            throw new IllegalStateException("مدل Whisper داخل APK ناقص است");
        }
        if (finalFile.exists() && !finalFile.delete()) {
            throw new IllegalStateException("مدل قبلی قابل جایگزینی نیست");
        }
        if (!partial.renameTo(finalFile)) {
            throw new IllegalStateException("ذخیره مدل داخلی کامل نشد");
        }
        Diagnostics.log("WHISPER_MODEL", "bundled install ready bytes=" + finalFile.length());
        MAIN.post(() -> listener.onReady(finalFile));
        return true;
    }

    public static void ensureModel(Context context, Listener listener) {
        File finalFile = modelFile(context);
        if (finalFile.isFile() && finalFile.length() >= MIN_VALID_BYTES) {
            MAIN.post(() -> listener.onReady(finalFile));
            return;
        }

        EXECUTOR.execute(() -> {
            File partial = new File(finalFile.getParentFile(), MODEL_NAME + ".part");
            HttpURLConnection conn = null;
            try {
                if (installBundledModel(context, finalFile, partial, listener)) return;
                if (partial.exists()) partial.delete();
                Diagnostics.log("WHISPER_MODEL", "bundled asset absent; trying network download");
                URL url = new URL(MODEL_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20_000);
                conn.setReadTimeout(60_000);
                conn.setRequestProperty("User-Agent", "EnglishAITutor/2.0");
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("دانلود مدل با خطای HTTP " + code + " متوقف شد");
                }
                long total = conn.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 256 * 1024);
                     FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[256 * 1024];
                    long done = 0L;
                    int lastPercent = -1;
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (n == 0) continue;
                        out.write(buffer, 0, n);
                        done += n;
                        int percent = total > 0 ? (int) Math.min(100, (done * 100L) / total) : -1;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            long d = done;
                            long t = total;
                            int p = percent;
                            MAIN.post(() -> listener.onProgress(p, d, t));
                        }
                    }
                    out.flush();
                }
                if (partial.length() < MIN_VALID_BYTES) {
                    throw new IllegalStateException("فایل مدل ناقص دانلود شد");
                }
                if (finalFile.exists() && !finalFile.delete()) {
                    throw new IllegalStateException("مدل قبلی قابل جایگزینی نیست");
                }
                if (!partial.renameTo(finalFile)) {
                    throw new IllegalStateException("ذخیره مدل کامل نشد");
                }
                MAIN.post(() -> listener.onReady(finalFile));
            } catch (Throwable t) {
                if (partial.exists()) partial.delete();
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                MAIN.post(() -> listener.onError(message));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}

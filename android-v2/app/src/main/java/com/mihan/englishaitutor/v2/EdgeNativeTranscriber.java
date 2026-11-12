package com.mihan.englishaitutor.v2;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriber;
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriberResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Real native on-device Google AI Edge MediaPipe Audio Transcriber API.
 * An installed Google AI Edge Gallery app does not expose an inter-app
 * transcriber; this uses Google's OFFICIAL in-app MediaPipe SDK instead.
 *
 * The 615 MB NVIDIA Parakeet .litertlm model is downloaded once, ONLY on
 * explicit user action and, by default, over unmetered Wi-Fi. It is never
 * embedded in the APK, silently downloaded or uploaded to a server.
 */
final class EdgeNativeTranscriber {
    static final String MODEL_NAME =
            "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm";
    private static final String MODEL_URL =
            "https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/resolve/main/"
                    + MODEL_NAME + "?download=true";
    private static final String DOWNLOAD_PREFS = "edge_native_asr_download_v1";
    // Model is officially about 615MB. Broad range checks completeness;
    // decoding and model validity are verified by AudioTranscriber itself.
    private static final long MIN_MODEL_BYTES = 550_000_000L;
    private static final long MAX_MODEL_BYTES = 800_000_000L;

    static final class Segment {
        final String text;
        final long startMs;
        final long endMs;
        Segment(String text, long startMs, long endMs) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    static final class Result {
        final String text;
        final List<Segment> segments;
        Result(String text, List<Segment> segments) {
            this.text = text;
            this.segments = segments;
        }
    }

    static final class DownloadState {
        final int status;
        final long downloadedBytes;
        final long totalBytes;

        DownloadState(int status, long downloadedBytes, long totalBytes) {
            this.status = status;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
        }
    }

    private EdgeNativeTranscriber() {}

    static File modelFile(Context context) {
        File external = context.getExternalFilesDir("native-speech-model");
        return external == null ? null : new File(external, MODEL_NAME);
    }

    static boolean isReady(Context context) {
        File model = modelFile(context);
        if (model == null || !model.isFile()
                || model.length() < MIN_MODEL_BYTES
                || model.length() > MAX_MODEL_BYTES) return false;
        // DownloadManager may expose its destination file while 550+ MB
        // are written but BEFORE all bytes finish. Avoid loading partial
        // native model files as if the download were successful.
        long trackedDownload = context.getSharedPreferences(DOWNLOAD_PREFS,
                Context.MODE_PRIVATE).getLong("downloadId", -1L);
        DownloadState state = downloadState(context);
        return trackedDownload < 0
                || (state != null && state.status == DownloadManager.STATUS_SUCCESSFUL);
    }

    static long enqueueDownload(Context context) {
        if (isReady(context)) return -1L;
        File model = modelFile(context);
        if (model == null) throw new IllegalStateException("فضای ذخیره‌سازی گوشی آماده نیست");
        DownloadState previous = downloadState(context);
        if (previous != null && (previous.status == DownloadManager.STATUS_RUNNING
                || previous.status == DownloadManager.STATUS_PENDING
                || previous.status == DownloadManager.STATUS_PAUSED))
            return context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
                    .getLong("downloadId", -1L);
        if (model.exists() && !model.delete())
            throw new IllegalStateException("فایل مدل ناقص قبلی پاک نشد");
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("Google AI Edge Parakeet ASR — English AI Tutor")
                .setDescription("مدل گفتار آفلاین حدود ۶۱۵ مگابایت")
                .setMimeType("application/octet-stream")
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, "native-speech-model", MODEL_NAME);
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IllegalStateException("مدیریت دانلود اندروید موجود نیست");
        long id = manager.enqueue(request);
        context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
                .edit().putLong("downloadId", id).apply();
        return id;
    }

    static DownloadState downloadState(Context context) {
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long id = context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
                .getLong("downloadId", -1L);
        if (manager == null || id < 0) return null;
        try (Cursor c = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (c == null || !c.moveToFirst()) return null;
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long current = c.getLong(c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = c.getLong(c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            return new DownloadState(status, current, total);
        }
    }

    static Result transcribe(Context context, File sourceWav,
                             GalleryComparisonWav.Bounds bounds) throws Exception {
        File model = modelFile(context);
        if (!isReady(context) || model == null)
            throw new IOException("مدل آفلاین Google AI Edge هنوز آماده نیست");
        // Byte-for-byte same original 16 kHz mono PCM sample window used for
        // local Whisper comparison; no dependency on external Gallery app.
        ByteArrayOutputStream clip = new ByteArrayOutputStream(960_044);
        GalleryComparisonWav.writeDialogueWav(sourceWav, clip, bounds);
        byte[] bytes = clip.toByteArray();
        float[] pcm = new float[(bytes.length - 44) / 2];
        for (int i = 0; i < pcm.length; i++) {
            int offset = 44 + i * 2;
            short sample = (short) ((bytes[offset] & 255)
                    | ((bytes[offset + 1] & 255) << 8));
            pcm[i] = sample / 32768f;
        }
        // A fresh stateless invocation for each line: no audio from previous
        // segments enters the ASR result. This must run off the main thread.
        AudioTranscriber recognizer = AudioTranscriber.createFromFile(context, model.getAbsolutePath());
        try {
            AudioTranscriberResult raw = recognizer.transcribe(pcm, true);
            String text = raw == null || raw.getText() == null
                    ? "" : raw.getText().replaceAll("\\s+", " ").trim();
            List<Segment> segments = new ArrayList<>();
            if (raw != null && raw.segments() != null) {
                for (AudioTranscriberResult.Segment item : raw.segments()) {
                    if (item == null || !item.isFinal() || item.transcript() == null
                            || item.transcript().trim().isEmpty()) continue;
                    long start = bounds.startMs + Math.max(0L, item.fromTimestampMs());
                    long end = bounds.startMs + Math.max(0L, item.toTimestampMs());
                    if (start >= bounds.startMs && end > start
                            && end <= bounds.endMs + 100L)
                        segments.add(new Segment(item.transcript().trim(), start, end));
                }
            }
            if (text.isEmpty() && !segments.isEmpty()) {
                StringBuilder merged = new StringBuilder();
                for (Segment item : segments) merged.append(item.text).append(' ');
                text = merged.toString().trim();
            }
            if (text.isEmpty())
                throw new IOException("موتور Google AI Edge در این کلیپ گفتاری تشخیص نداد");
            return new Result(text, segments);
        } finally {
            recognizer.close();
        }
    }
}

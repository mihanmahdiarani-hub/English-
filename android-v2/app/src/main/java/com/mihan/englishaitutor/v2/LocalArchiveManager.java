package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local persistent archive for one movie inside English AI Tutor's app storage.
 *
 * Layout:
 *   EnglishAITutorArchive/<sha256>/
 *     video.<ext>
 *     audio.wav
 *     transcript.json
 *     progress.json
 *     metadata.json
 *
 * The content SHA-256 is the archive ID, so giving the same movie again reuses
 * the saved video/audio/transcript instead of rebuilding Whisper output.
 */
public final class LocalArchiveManager {
    private static final String ROOT_NAME = "EnglishAITutorArchive";
    private static final int TRANSCRIPT_VERSION = 2;
    // Older archived WAVs omitted MediaCodec PTS gaps; older refinement
    // could borrow up to ~1 second from neighboring phrases. This marker is
    // written ONLY after corrected extraction + Whisper + refined transcript.
    private static final String PRECISE_TIMING_MARKER = "timeline-pts-v36.ready";

    public interface ProgressListener {
        void onProgress(int percent, String stage);
    }

    public static final class Archive {
        public final String id;
        public final String title;
        public final File dir;
        public final File videoFile;
        public final File audioFile;
        public final File transcriptFile;
        public final File progressFile;

        Archive(String id, String title, File dir, File videoFile) {
            this.id = id;
            this.title = title;
            this.dir = dir;
            this.videoFile = videoFile;
            this.audioFile = new File(dir, "audio.wav");
            this.transcriptFile = new File(dir, "transcript.json");
            this.progressFile = new File(dir, "progress.json");
        }

        public boolean hasTranscript() {
            return transcriptFile.isFile() && transcriptFile.length() > 8L;
        }

        public boolean hasAudio() {
            return audioFile.isFile() && audioFile.length() > 1024L;
        }
    }

    public static final class TranscriptRow {
        public final long startMs;
        public final long endMs;
        public final String text;
        // Empty when the speech model cannot confidently align this row.
        public final List<CtcWordAligner.Word> words;

        TranscriptRow(long startMs, long endMs, String text) {
            this(startMs, endMs, text, new ArrayList<>());
        }

        TranscriptRow(long startMs, long endMs, String text,
                      List<CtcWordAligner.Word> words) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
            this.words = words;
        }
    }

    public static final class Progress {
        public final long positionMs;
        public final int dialogueIndex;
        public final long updatedAtMs;

        Progress(long positionMs, int dialogueIndex, long updatedAtMs) {
            this.positionMs = positionMs;
            this.dialogueIndex = dialogueIndex;
            this.updatedAtMs = updatedAtMs;
        }
    }

    private LocalArchiveManager() {}

    public static Archive prepareArchive(Context context,
                                         Uri source,
                                         ProgressListener listener) throws Exception {
        if (context == null || source == null) {
            throw new IllegalArgumentException("ویدئو برای آرشیو مشخص نیست");
        }

        File root = archiveRoot(context);
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("ساخت پوشه آرشیو ناموفق بود");
        }

        SourceInfo info = querySourceInfo(context, source);
        String extension = safeExtension(info.displayName);
        File incoming = new File(root, ".incoming_" + System.nanoTime() + extension);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0L;
        byte[] buffer = new byte[1024 * 1024];

        if (listener != null) listener.onProgress(0, "در حال آرشیو فیلم");

        try (InputStream raw = context.getContentResolver().openInputStream(source);
             BufferedInputStream in = raw == null ? null : new BufferedInputStream(raw, buffer.length);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(incoming), buffer.length)) {
            if (in == null) throw new IllegalArgumentException("فایل فیلم قابل خواندن نیست");
            int n;
            int lastPercent = -1;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
                copied += n;

                if (listener != null && info.sizeBytes > 0L) {
                    int percent = (int) Math.min(100L, (copied * 100L) / info.sizeBytes);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        listener.onProgress(percent, "در حال آرشیو فیلم");
                    }
                }
            }
        } catch (Throwable t) {
            incoming.delete();
            throw t;
        }

        String id = hex(digest.digest());
        File dir = new File(root, id);
        if (!dir.exists() && !dir.mkdirs()) {
            incoming.delete();
            throw new IllegalStateException("ساخت پوشه فیلم در آرشیو ناموفق بود");
        }

        File video = findVideoFile(dir);
        if (video == null) {
            video = new File(dir, "video" + extension);
            if (!incoming.renameTo(video)) {
                copyFile(incoming, video);
                incoming.delete();
            }
        } else {
            incoming.delete();
        }

        String title = info.displayName == null || info.displayName.trim().isEmpty()
                ? "Movie"
                : info.displayName.trim();

        JSONObject meta = new JSONObject();
        meta.put("archiveId", id);
        meta.put("title", title);
        meta.put("videoFile", video.getName());
        meta.put("videoBytes", video.length());
        meta.put("updatedAtMs", System.currentTimeMillis());
        writeTextAtomic(new File(dir, "metadata.json"), meta.toString());

        if (listener != null) listener.onProgress(100, "آرشیو فیلم آماده شد");
        return new Archive(id, title, dir, video);
    }

    public static void saveTranscript(Archive archive,
                                      List<WhisperBridge.Segment> segments) throws Exception {
        saveTranscript(archive, segments, null);
    }

    public static void saveTranscript(Archive archive,
                                      List<WhisperBridge.Segment> segments,
                                      List<List<CtcWordAligner.Word>> wordTimings) throws Exception {
        if (archive == null || segments == null) return;

        JSONArray rows = new JSONArray();
        int alignedRows = 0;
        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            WhisperBridge.Segment segment = segments.get(segmentIndex);
            if (segment == null) continue;
            String text = segment.getText() == null
                    ? ""
                    : segment.getText().replaceAll("\\s+", " ").trim();
            if (text.isEmpty() || segment.getEndMs() <= segment.getStartMs()) continue;

            JSONObject row = new JSONObject();
            row.put("startMs", segment.getStartMs());
            row.put("endMs", segment.getEndMs());
            row.put("text", text);
            if (wordTimings != null && segmentIndex < wordTimings.size()) {
                List<CtcWordAligner.Word> words = wordTimings.get(segmentIndex);
                if (words != null && !words.isEmpty()) {
                    JSONArray acousticWords = new JSONArray();
                    for (CtcWordAligner.Word word : words) {
                        if (word == null || word.text.isEmpty()
                                || word.startMs < segment.getStartMs()
                                || word.endMs > segment.getEndMs()
                                || word.endMs <= word.startMs) continue;
                        JSONObject part = new JSONObject();
                        part.put("text", word.text);
                        part.put("startMs", word.startMs);
                        part.put("endMs", word.endMs);
                        part.put("confidence", word.confidence);
                        acousticWords.put(part);
                    }
                    if (acousticWords.length() > 0) {
                        row.put("words", acousticWords);
                        alignedRows++;
                    }
                }
            }
            rows.put(row);
        }

        JSONObject root = new JSONObject();
        if (alignedRows > 0) {
            root.put("alignmentEngine", "wav2vec2-ctc-v1");
            root.put("alignedRows", alignedRows);
        }
        root.put("version", TRANSCRIPT_VERSION);
        root.put("archiveId", archive.id);
        root.put("savedAtMs", System.currentTimeMillis());
        root.put("dialogues", rows);
        writeTextAtomic(archive.transcriptFile, root.toString());
    }

    public static List<TranscriptRow> loadTranscript(Archive archive) throws Exception {
        List<TranscriptRow> out = new ArrayList<>();
        if (archive == null || !archive.hasTranscript()) return out;

        JSONObject root = new JSONObject(readText(archive.transcriptFile));
        int version = root.optInt("version", 1);
        if (version != TRANSCRIPT_VERSION) {
            Diagnostics.log("ARCHIVE_TRANSCRIPT", "stale version=" + version
                    + " expected=" + TRANSCRIPT_VERSION + "; rebuilding");
            return out;
        }
        JSONArray rows = root.optJSONArray("dialogues");
        if (rows == null) return out;

        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            long start = row.optLong("startMs", -1L);
            long end = row.optLong("endMs", -1L);
            String text = row.optString("text", "").replaceAll("\\s+", " ").trim();
            if (start < 0L || end <= start || text.isEmpty()) continue;
            List<CtcWordAligner.Word> words = new ArrayList<>();
            JSONArray items = row.optJSONArray("words");
            if (items != null) {
                long previousEnd = start;
                for (int w = 0; w < items.length(); w++) {
                    JSONObject item = items.optJSONObject(w);
                    if (item == null) continue;
                    long wordStart = item.optLong("startMs", -1);
                    long wordEnd = item.optLong("endMs", -1);
                    double score = item.optDouble("confidence", 0.0);
                    String wordText = item.optString("text", "");
                    if (wordStart < previousEnd || wordEnd <= wordStart
                            || wordEnd > end || wordText.isEmpty()
                            || !Double.isFinite(score)) {
                        words.clear();
                        break;
                    }
                    words.add(new CtcWordAligner.Word(
                            wordText, wordStart, wordEnd, score));
                    previousEnd = wordEnd;
                }
            }
            out.add(new TranscriptRow(start, end, text, words));
        }
        return out;
    }

    /**
     * A saved movie contains real CTC acoustic-word timestamps. Missing or
     * failed alignments can retry next time the model becomes available.
     * v36 speech timing markers remain unchanged.
     */
    public static boolean hasWordAlignment(Archive archive) {
        if (archive == null || !archive.hasTranscript()) return false;
        try {
            JSONObject root = new JSONObject(readText(archive.transcriptFile));
            return "wav2vec2-ctc-v1".equals(root.optString("alignmentEngine"))
                    && root.optInt("alignedRows", 0) > 0;
        } catch (Exception e) {
            Diagnostics.error("CTC_ARCHIVE", e);
            return false;
        }
    }

    /** Legacy archives remain viewable while they are realigned once. */
    public static boolean hasPreciseTiming(Archive archive) {
        return archive != null && archive.hasAudio()
                && archive.hasTranscript()
                && new File(archive.dir, PRECISE_TIMING_MARKER).isFile();
    }

    /** Commit only AFTER the corrected WAV and accurate transcript are saved. */
    public static void markPreciseTiming(Archive archive) throws Exception {
        if (archive == null || !archive.hasAudio() || !archive.hasTranscript()) {
            throw new IllegalStateException("Precise timeline assets are incomplete");
        }
        writeTextAtomic(new File(archive.dir, PRECISE_TIMING_MARKER), "36\n");
    }

    public static void saveProgress(Archive archive, long positionMs, int dialogueIndex) {
        if (archive == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("positionMs", Math.max(0L, positionMs));
            root.put("dialogueIndex", dialogueIndex);
            root.put("updatedAtMs", System.currentTimeMillis());
            writeTextAtomic(archive.progressFile, root.toString());
        } catch (Throwable t) {
            Diagnostics.error("ARCHIVE_PROGRESS_SAVE", t);
        }
    }

    public static Progress loadProgress(Archive archive) {
        if (archive == null || !archive.progressFile.isFile()) {
            return new Progress(0L, -1, 0L);
        }
        try {
            JSONObject root = new JSONObject(readText(archive.progressFile));
            return new Progress(
                    Math.max(0L, root.optLong("positionMs", 0L)),
                    root.optInt("dialogueIndex", -1),
                    root.optLong("updatedAtMs", 0L)
            );
        } catch (Throwable t) {
            Diagnostics.error("ARCHIVE_PROGRESS_LOAD", t);
            return new Progress(0L, -1, 0L);
        }
    }

    public static File archiveRoot(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        return new File(base, ROOT_NAME);
    }

    /**
     * Locate a fully prepared movie from existing app-private archives.
     *
     * Called on a background worker when MainActivity is opened after an APK
     * update/restart. Prefer the last used archive saved in SharedPreferences;
     * older app versions never saved that preference, so also discover the
     * newest usable transcript/video pair without running Whisper again.
     *
     * This method never deletes files or rewrites archive metadata.
     */
    public static Archive findMostRecentPreparedArchive(Context context,
                                                          String preferredId) {
        File root = archiveRoot(context);
        File[] directories = root.listFiles();
        if (directories == null) return null;
        Archive newest = null;
        long newestTimestamp = -1L;
        for (File dir : directories) {
            if (!dir.isDirectory() || !dir.getName().matches("[0-9a-fA-F]{64}")) continue;
            File movie = findVideoFile(dir);
            if (movie == null || !movie.isFile() || movie.length() <= 0L) continue;
            String title = "Movie";
            File metadata = new File(dir, "metadata.json");
            if (metadata.isFile()) {
                try {
                    title = new JSONObject(readText(metadata)).optString("title", "Movie");
                } catch (Exception ignored) {
                    // Older archives can still be reused without metadata.
                }
            }
            Archive found = new Archive(dir.getName(), title, dir, movie);
            if (!found.hasTranscript()) continue;
            try {
                if (loadTranscript(found).isEmpty()) continue;
            } catch (Exception ignored) {
                continue;
            }
            if (preferredId != null && preferredId.equalsIgnoreCase(found.id)) {
                return found;
            }
            long lastUsed = Math.max(metadata.lastModified(),
                    Math.max(found.progressFile.lastModified(),
                            found.transcriptFile.lastModified()));
            if (lastUsed > newestTimestamp) {
                newestTimestamp = lastUsed;
                newest = found;
            }
        }
        return newest;
    }

    private static File findVideoFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("video.")) return file;
        }
        return null;
    }

    private static SourceInfo querySourceInfo(Context context, Uri source) {
        String name = "";
        long size = -1L;

        try (Cursor cursor = context.getContentResolver().query(
                source,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (Throwable ignored) {}

        return new SourceInfo(name == null ? "" : name, size);
    }

    private static String safeExtension(String name) {
        if (name == null) return ".mp4";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return ".mp4";
        String ext = name.substring(dot).toLowerCase(Locale.US);
        if (!ext.matches("\\.[a-z0-9]{1,8}")) return ".mp4";
        return ext;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(from));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(to))) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                out.write(buffer, 0, n);
            }
        }
    }

    private static void writeTextAtomic(File target, String text) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("ساخت پوشه ناموفق بود");
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        }
        if (target.exists() && !target.delete()) {
            tmp.delete();
            throw new IllegalStateException("جایگزینی فایل آرشیو ناموفق بود");
        }
        if (!tmp.renameTo(target)) {
            copyFile(tmp, target);
            tmp.delete();
        }
    }

    private static String readText(File file) throws Exception {
        long length = file.length();
        if (length > 16L * 1024L * 1024L) {
            throw new IllegalArgumentException("فایل آرشیو بیش از حد بزرگ است");
        }
        byte[] bytes = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int n = in.read(bytes, offset, bytes.length - offset);
                if (n < 0) break;
                offset += n;
            }
            return new String(bytes, 0, offset, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final class SourceInfo {
        final String displayName;
        final long sizeBytes;

        SourceInfo(String displayName, long sizeBytes) {
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
        }
    }
}

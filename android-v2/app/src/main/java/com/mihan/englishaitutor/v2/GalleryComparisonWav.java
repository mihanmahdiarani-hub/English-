package com.mihan.englishaitutor.v2;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Exports the selected movie dialogue's ORIGINAL 16kHz mono PCM WAV samples
 * without resampling/re-encoding. This yields exactly the same audio
 * for Whisper and AI Edge Gallery's Audio Scribe comparison.
 *
 * The Gallery app is an independent Android application without a public
 * automatic transcription-result API; this helper only prepares a WAV file
 * that the learner explicitly saves to a location they control.
 */
final class GalleryComparisonWav {
    static final long MAX_CLIP_MS = 30_000L;
    static final int SAMPLE_RATE = 16_000;
    static final int BYTES_PER_FRAME = 2;

    private GalleryComparisonWav() {}

    static final class Bounds {
        final long startMs;
        final long endMs;

        Bounds(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        long durationMs() { return endMs - startMs; }
    }

    static Bounds chooseBounds(long startMs, long endMs, long durationMs) {
        if (startMs < 0 || endMs <= startMs || durationMs <= 0L)
            throw new IllegalArgumentException("زمان دیالوگ معتبر نیست");
        long begin = Math.max(0L, startMs - 200L);
        long end = Math.min(durationMs, endMs + 200L);
        if (end - begin > MAX_CLIP_MS)
            throw new IllegalArgumentException(
                    "این دیالوگ بیش از ۳۰ ثانیه است؛ بخش کوتاه‌تری انتخاب کن");
        if (end - begin < 100L)
            throw new IllegalArgumentException("بخش صوتی خیلی کوتاه است");
        return new Bounds(begin, end);
    }

    static long durationMs(File wav) throws IOException {
        try (RandomAccessFile input = openPcmWav(wav)) {
            return Math.max(0, (input.length() - 44L)
                    / (SAMPLE_RATE * BYTES_PER_FRAME / 1000L));
        }
    }

    static long writeDialogueWav(File wav, OutputStream output,
                                 Bounds bounds) throws IOException {
        if (output == null || bounds == null || bounds.durationMs() <= 0
                || bounds.durationMs() > MAX_CLIP_MS)
            throw new IOException("کلیپ انتخابی معتبر نیست");
        try (RandomAccessFile input = openPcmWav(wav)) {
            long totalSamples = (input.length() - 44L) / BYTES_PER_FRAME;
            long startFrame = Math.min(totalSamples, bounds.startMs * SAMPLE_RATE / 1000);
            long endFrame = Math.min(totalSamples, bounds.endMs * SAMPLE_RATE / 1000);
            if (endFrame <= startFrame)
                throw new IOException("صدای کافی در محدوده دیالوگ وجود ندارد");
            long totalBytes = (endFrame - startFrame) * BYTES_PER_FRAME;
            if (totalBytes > 960_000L)
                throw new IOException("کلیپ از سقف ۳۰ ثانیه بیشتر است");
            writeHeader(output, (int) totalBytes);
            input.seek(44L + startFrame * BYTES_PER_FRAME);
            byte[] buffer = new byte[8192];
            long remaining = totalBytes;
            while (remaining > 0) {
                int n = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (n < 0) throw new IOException("فایل صوتی ناقص است");
                output.write(buffer, 0, n);
                remaining -= n;
            }
            output.flush();
            return totalBytes + 44L;
        }
    }

    private static RandomAccessFile openPcmWav(File wav) throws IOException {
        if (wav == null || !wav.isFile() || wav.length() < 44 + 3200)
            throw new IOException("فایل صوتی اصلی فیلم آماده نیست");
        RandomAccessFile input = new RandomAccessFile(wav, "r");
        try {
            byte[] header = new byte[44];
            input.readFully(header);
            if (!magic(header, 0, "RIFF") || !magic(header, 8, "WAVE")
                    || !magic(header, 12, "fmt ") || !magic(header, 36, "data")
                    || read16(header, 20) != 1 || read16(header, 22) != 1
                    || read32(header, 24) != SAMPLE_RATE
                    || read16(header, 34) != 16) {
                throw new IOException("فرمت WAV اصلی قابل پردازش نیست");
            }
            return input;
        } catch (IOException e) {
            input.close();
            throw e;
        }
    }

    private static boolean magic(byte[] buf, int offset, String value) {
        for (int i = 0; i < value.length(); i++)
            if (buf[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private static int read16(byte[] b, int offset) {
        return (b[offset] & 255) | ((b[offset + 1] & 255) << 8);
    }

    private static int read32(byte[] b, int offset) {
        return (b[offset] & 255) | ((b[offset + 1] & 255) << 8)
                | ((b[offset + 2] & 255) << 16)
                | ((b[offset + 3] & 255) << 24);
    }

    private static void writeHeader(OutputStream out, int dataBytes) throws IOException {
        ascii(out, "RIFF"); write32(out, dataBytes + 36); ascii(out, "WAVE");
        ascii(out, "fmt "); write32(out, 16); write16(out, 1); write16(out, 1);
        write32(out, SAMPLE_RATE); write32(out, SAMPLE_RATE * BYTES_PER_FRAME);
        write16(out, BYTES_PER_FRAME); write16(out, 16);
        ascii(out, "data"); write32(out, dataBytes);
    }

    private static void ascii(OutputStream out, String value) throws IOException {
        for (int i = 0; i < value.length(); i++) out.write(value.charAt(i));
    }

    private static void write32(OutputStream out, int value) throws IOException {
        for (int i = 0; i < 4; i++) out.write((value >>> (i * 8)) & 255);
    }

    private static void write16(OutputStream out, int value) throws IOException {
        out.write(value & 255);
        out.write((value >>> 8) & 255);
    }
}

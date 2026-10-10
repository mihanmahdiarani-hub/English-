package com.mihan.englishaitutor.v2;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Reads ONLY the requested actor-word samples from the movie's own cached
 * 16kHz mono PCM WAV. No speech synthesis, internet or Media3 short seek.
 * Movie PTS has already been preserved by VideoAudioExtractor.
 */
final class WordAudioSlice {
    static final int SAMPLE_RATE = 16000;
    static final int SAMPLE_BYTES = 2;
    static final int HEADER_BYTES = 44;
    static final long MAX_WORD_MS = 2500L;

    private WordAudioSlice() {}

    static byte[] read(File wav, long startMs, long endMs) throws Exception {
        if (wav == null || !wav.isFile() || startMs < 0L
                || endMs <= startMs || endMs - startMs > MAX_WORD_MS) {
            throw new IllegalArgumentException("Invalid original movie word audio range");
        }
        try (RandomAccessFile in = new RandomAccessFile(wav, "r")) {
            if (in.length() < HEADER_BYTES + 2L) {
                throw new IllegalArgumentException("Original movie WAV is missing or empty");
            }
            byte[] header = new byte[HEADER_BYTES];
            in.readFully(header);
            if (!ascii(header, 0, "RIFF") || !ascii(header, 8, "WAVE")
                    || !ascii(header, 12, "fmt ") || !ascii(header, 36, "data")
                    || little16(header, 20) != 1 // uncompressed PCM
                    || little16(header, 22) != 1 // mono
                    || little32(header, 24) != SAMPLE_RATE
                    || little16(header, 34) != 16 // bits per sample
                    || little16(header, 32) != SAMPLE_BYTES) {
                throw new IllegalArgumentException("Expected original 16kHz mono PCM16 WAV");
            }
            long pcmSize = little32(header, 40);
            if (pcmSize > in.length() - HEADER_BYTES || pcmSize < 0L)
                throw new IllegalArgumentException("Corrupt WAV data size");
            // Exactly 16 sample frames per ms, 2 bytes per sample frame.
            long firstFrame = startMs * SAMPLE_RATE / 1000L;
            long lastFrame = endMs * SAMPLE_RATE / 1000L;
            long firstByte = firstFrame * SAMPLE_BYTES;
            long lastByte = lastFrame * SAMPLE_BYTES;
            if (lastByte <= firstByte || lastByte > pcmSize
                    || firstByte > pcmSize || lastByte - firstByte > SAMPLE_RATE * 5L) {
                throw new IllegalArgumentException("Word range outside original movie audio");
            }
            int bytes = (int) (lastByte - firstByte);
            byte[] segment = new byte[bytes];
            in.seek(HEADER_BYTES + firstByte);
            in.readFully(segment);
            return segment;
        }
    }

    static void fadeEdges(byte[] pcm) {
        if (pcm == null || pcm.length < 32) return;
        // 2ms gentle fade to avoid a pop when slicing at phoneme boundaries.
        // This changes amplitude only, not the actor's pronunciation/content.
        int frames = pcm.length / 2;
        int fade = Math.min(32, frames / 4);
        for (int i = 0; i < fade; i++) {
            double gain = i / (double) fade;
            fadeSample(pcm, i, gain);
            fadeSample(pcm, frames - 1 - i, gain);
        }
    }

    private static void fadeSample(byte[] bytes, int sample, double gain) {
        int offset = sample * 2;
        int lo = bytes[offset] & 255;
        int hi = bytes[offset + 1] & 255;
        short old = (short) ((hi << 8) | lo);
        short adjusted = (short) Math.round(old * gain);
        bytes[offset] = (byte) (adjusted & 255);
        bytes[offset + 1] = (byte) ((adjusted >>> 8) & 255);
    }

    private static boolean ascii(byte[] bytes, int offset, String required) {
        return required.equals(new String(
                bytes, offset, required.length(), StandardCharsets.US_ASCII));
    }

    private static int little16(byte[] bytes, int at) {
        return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8;
    }

    private static long little32(byte[] bytes, int at) {
        return (bytes[at] & 255L) | ((bytes[at + 1] & 255L) << 8)
                | ((bytes[at + 2] & 255L) << 16)
                | ((bytes[at + 3] & 255L) << 24);
    }
}

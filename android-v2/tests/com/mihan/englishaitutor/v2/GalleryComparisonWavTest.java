package com.mihan.englishaitutor.v2;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;

/** Plain JVM regression for audio samples and original-time comparison. */
public final class GalleryComparisonWavTest {
    private GalleryComparisonWavTest() {}

    public static void main(String[] args) throws Exception {
        File wav = File.createTempFile("edge-compare-test-", ".wav");
        try {
            byte[] fourSeconds = new byte[4 * 16000 * 2];
            for (int i = 0; i < fourSeconds.length; i++)
                fourSeconds[i] = (byte) (i % 251);
            byte[] header = makeHeader(fourSeconds.length, 16000);
            try (FileOutputStream out = new FileOutputStream(wav)) {
                out.write(header);
                out.write(fourSeconds);
            }
            check(GalleryComparisonWav.durationMs(wav) == 4000L,
                    "16kHz mono length");
            GalleryComparisonWav.Bounds bounds =
                    GalleryComparisonWav.chooseBounds(1000L, 2000L, 4000L);
            check(bounds.startMs == 800L && bounds.endMs == 2200L,
                    "padding strictly within source");
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            long count = GalleryComparisonWav.writeDialogueWav(wav, copy, bounds);
            byte[] bytes = copy.toByteArray();
            check(count == 44L + 1400L * 32L && bytes.length == count,
                    "1.4 sec output at PCM16 mono 16kHz");
            check(ascii(bytes, 0, 4).equals("RIFF")
                    && ascii(bytes, 8, 4).equals("WAVE")
                    && ascii(bytes, 36, 4).equals("data"), "valid WAV headers");
            check(read32(bytes, 24) == 16000, "PCM rate must be 16k");
            check(read32(bytes, 40) == count - 44, "data chunk size");
            check(Arrays.equals(Arrays.copyOfRange(bytes, 44, bytes.length),
                    Arrays.copyOfRange(fourSeconds, 800 * 32, 2200 * 32)),
                    "byte-perfect original sound; never re-encode or misalign PTS");

            boolean rejected = false;
            try {
                GalleryComparisonWav.chooseBounds(0, 30100, 50000);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "Gallery 30-second clip limit enforced");
            rejected = false;
            try {
                GalleryComparisonWav.chooseBounds(1000, 1000, 4000);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "empty clip rejected");

            byte[] invalid = makeHeader(fourSeconds.length, 44100);
            try (FileOutputStream out = new FileOutputStream(wav)) {
                out.write(invalid);
                out.write(fourSeconds);
            }
            rejected = false;
            try {
                GalleryComparisonWav.durationMs(wav);
            } catch (Exception expected) {
                rejected = true;
            }
            check(rejected, "reject incompatible WAV header");
            System.out.println("GalleryComparisonWavTest: PASS");
        } finally {
            Files.deleteIfExists(wav.toPath());
        }
    }

    private static byte[] makeHeader(int data, int rate) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (char c : "RIFF".toCharArray()) b.write(c);
        put32(b, 36 + data);
        for (char c : "WAVEfmt ".toCharArray()) b.write(c);
        put32(b, 16);
        put16(b, 1); put16(b, 1);
        put32(b, rate); put32(b, rate * 2);
        put16(b, 2); put16(b, 16);
        for (char c : "data".toCharArray()) b.write(c);
        put32(b, data);
        return b.toByteArray();
    }

    private static String ascii(byte[] bytes, int start, int length) {
        return new String(bytes, start, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int read32(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8)
                | ((b[p + 2] & 255) << 16) | ((b[p + 3] & 255) << 24);
    }

    private static void put16(ByteArrayOutputStream b, int n) {
        b.write(n & 255); b.write((n >>> 8) & 255);
    }

    private static void put32(ByteArrayOutputStream b, int n) {
        for (int i = 0; i < 4; i++) b.write((n >>> (8 * i)) & 255);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}

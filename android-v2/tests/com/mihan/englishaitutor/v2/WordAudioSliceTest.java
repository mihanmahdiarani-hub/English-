package com.mihan.englishaitutor.v2;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * This test uses a real on-disk RIFF PCM waveform (synthetic test tone).
 * It checks byte-level source crop boundaries, not inferred speech timing.
 */
public final class WordAudioSliceTest {
    private static int checks;

    private static void ok(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    private static void shortLe(RandomAccessFile out, int value) throws Exception {
        out.write(value & 255);
        out.write(value >>> 8 & 255);
    }
    private static void intLe(RandomAccessFile out, long value) throws Exception {
        out.write((int) (value & 255));
        out.write((int) (value >>> 8 & 255));
        out.write((int) (value >>> 16 & 255));
        out.write((int) (value >>> 24 & 255));
    }
    private static int sampleAt(byte[] bytes, int position) {
        int offset = position * 2;
        return (short) (((bytes[offset + 1] & 255) << 8)
                | (bytes[offset] & 255));
    }

    public static void main(String[] args) throws Exception {
        File wav = File.createTempFile("movie-actor-word-clip-", ".wav");
        try {
            try (RandomAccessFile out = new RandomAccessFile(wav, "rw")) {
                final int frames = 16000; // one second
                out.writeBytes("RIFF"); intLe(out, 36 + frames * 2);
                out.writeBytes("WAVEfmt "); intLe(out, 16);
                shortLe(out, 1); shortLe(out, 1);
                intLe(out, 16000); intLe(out, 32000);
                shortLe(out, 2); shortLe(out, 16);
                out.writeBytes("data"); intLe(out, frames * 2);
                for (int i = 0; i < frames; i++) {
                    // Encode time into samples: can detect even a 1ms shift.
                    shortLe(out, i);
                }
            }
            ok(wav.length() == 32044, "correct 44 byte WAV header and 16k PCM");
            byte[] wordA = WordAudioSlice.read(wav, 125L, 210L);
            ok(wordA.length == 85 * 32, "85ms word extracts exactly 1360 source frames");
            ok(sampleAt(wordA, 0) == 2000, "first frame matches 125ms of original movie");
            ok(sampleAt(wordA, 1359) == 3359, "last frame precedes next word");
            byte[] wordB = WordAudioSlice.read(wav, 210L, 250L);
            ok(sampleAt(wordB, 0) == 3360,
                    "next word begins at its own exact sample, with no overlap");
            ok(sampleAt(wordA, 1359) + 1 == sampleAt(wordB, 0),
                    "adjacent word chunks never duplicate a sample");

            WordAudioSlice.fadeEdges(wordA);
            ok(sampleAt(wordA, 0) == 0, "2ms fade removes start click");
            ok(sampleAt(wordA, wordA.length / 2 - 1) == 0,
                    "2ms fade removes stop click");
            ok(sampleAt(wordA, 100) == 2100,
                    "central actor-voice samples remain unchanged");

            boolean outOfRangeRejected = false;
            try { WordAudioSlice.read(wav, 920, 1040); }
            catch (IllegalArgumentException expected) { outOfRangeRejected = true; }
            ok(outOfRangeRejected, "never read beyond original movie WAV");
            boolean negativeRejected = false;
            try { WordAudioSlice.read(wav, -10, 100); }
            catch (IllegalArgumentException expected) { negativeRejected = true; }
            ok(negativeRejected, "reject negative WAV position");
            boolean zeroRejected = false;
            try { WordAudioSlice.read(wav, 100, 100); }
            catch (IllegalArgumentException expected) { zeroRejected = true; }
            ok(zeroRejected, "reject zero-duration word");
            boolean tooLongRejected = false;
            try { WordAudioSlice.read(wav, 0, 2600); }
            catch (IllegalArgumentException expected) { tooLongRejected = true; }
            ok(tooLongRejected, "reject extreme nonsensical word durations");

            try (RandomAccessFile out = new RandomAccessFile(wav, "rw")) {
                out.seek(24);
                intLe(out, 8000); // NOT Whisper-compatible PCM rate
            }
            boolean malformedRejected = false;
            try { WordAudioSlice.read(wav, 125, 210); }
            catch (IllegalArgumentException expected) { malformedRejected = true; }
            ok(malformedRejected, "wrong-sample-rate WAV cannot be presented as 16k movie word");

            System.out.println("PASS " + checks
                    + " real original-WAV PCM sample extraction and safe word boundaries");
        } finally {
            if (!wav.delete()) wav.deleteOnExit();
        }
    }
}

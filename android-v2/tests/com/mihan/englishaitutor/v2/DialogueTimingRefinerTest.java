package com.mihan.englishaitutor.v2;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Real WAV integration checks of the production refiner. Catches the previous
 * up-to-one-second waveform search and naive word-count sentence split.
 */
public final class DialogueTimingRefinerTest {
    private static int checks = 0;
    private static void assertTrue(boolean b, String msg) {
        checks++;
        if (!b) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Exception {
        File wav = File.createTempFile("english-tutor-timing-", ".wav");
        try {
            // Two clearly distinct spoken phrases in a 2-second movie:
            // speech 400..650ms; then speech 880..1100ms.
            writeTestWave(wav);
            List<WhisperBridge.Segment> raw = Arrays.asList(
                    new WhisperBridge.Segment(420L, 680L, "Hold it."),
                    new WhisperBridge.Segment(900L, 1140L, "Don't move."));
            List<WhisperBridge.Segment> refined =
                    DialogueTimingRefiner.refine(wav, raw);
            assertTrue(refined.size() == 2, "must keep two independent phrases");
            long a0 = refined.get(0).getStartMs();
            long a1 = refined.get(0).getEndMs();
            long b0 = refined.get(1).getStartMs();
            long b1 = refined.get(1).getEndMs();
            assertTrue(a0 >= 385 && a0 <= 455,
                    "first phrase must start near 400ms, got " + a0);
            assertTrue(a1 >= 615 && a1 <= 715,
                    "first phrase must finish near 650ms, got " + a1);
            assertTrue(b0 >= 835 && b0 <= 935,
                    "second phrase must start near 880ms NOT within first, got " + b0);
            assertTrue(b1 >= 1070 && b1 <= 1180,
                    "second phrase must end near 1100ms, got " + b1);
            assertTrue(a1 < b0, "real silence gap must remain");

            // Two sentences from Whisper in one segment: 1:2 word ratio
            // predicts 633ms although actual second speech starts at 880ms.
            // Splitting is only acceptable if a real pause is found.
            List<WhisperBridge.Segment> combined = Arrays.asList(
                    new WhisperBridge.Segment(400L, 1100L, "Stop. Don't move."));
            List<WhisperBridge.Segment> parts =
                    DialogueTimingRefiner.refine(wav, combined);
            assertTrue(parts.size() == 2,
                    "clear 230ms speech pause should allow 2 teaching lines");
            assertTrue(parts.get(0).getEndMs() <= parts.get(1).getStartMs(),
                    "split lines may not overlap");
            assertTrue(parts.get(1).getStartMs() >= 820,
                    "second sentence must not begin at 633ms guessed by word count");

            System.out.println("PASS " + checks
                    + " real synthetic WAV / Whisper refiner regressions");
        } finally {
            wav.delete();
        }
    }

    private static void writeTestWave(File wav) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(wav, "rw")) {
            f.setLength(0L);
            // The production refiner reads 44 bytes of PCM WAV header.
            byte[] header = new byte[44];
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            f.write(header);
            for (int sample = 0; sample < 32000; sample++) {
                long ms = sample * 1000L / 16000L;
                boolean speaking = (ms >= 400 && ms < 650)
                        || (ms >= 880 && ms < 1100);
                short amp = (short) (speaking ? 4200 : 0);
                f.write(amp & 0xff);
                f.write((amp >> 8) & 0xff);
            }
        }
    }
}

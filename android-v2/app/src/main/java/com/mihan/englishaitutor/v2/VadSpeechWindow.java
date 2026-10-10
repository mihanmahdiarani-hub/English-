package com.mihan.englishaitutor.v2;

/**
 * Conservative VAD-only speech WINDOW proposal. A Silero probability does
 * not identify an individual word and must never be shown as one.
 * This is used only to retry a failed CTC forced-alignment window.
 */
final class VadSpeechWindow {
    static final long FRAME_MS = 32L;  // 512 samples at 16 kHz
    static final float SPEECH_THRESHOLD = 0.50f;
    static final long PAD_MS = 160L;

    static final class Bounds {
        final long startMs;
        final long endMs;
        Bounds(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private VadSpeechWindow() {}

    static Bounds choose(float[] probabilities, long windowStartMs,
                         long windowEndMs, long transcriptStartMs,
                         long transcriptEndMs) {
        if (probabilities == null || probabilities.length == 0
                || transcriptStartMs < 0 || transcriptEndMs <= transcriptStartMs
                || windowEndMs <= windowStartMs) return null;
        long left = Math.max(windowStartMs, transcriptStartMs - 650L);
        long right = Math.min(windowEndMs, transcriptEndMs + 650L);
        long first = Long.MAX_VALUE, last = -1L;
        int matches = 0;
        for (int i = 0; i < probabilities.length; i++) {
            float p = probabilities[i];
            if (!Float.isFinite(p) || p < SPEECH_THRESHOLD) continue;
            long begin = windowStartMs + i * FRAME_MS;
            long end = Math.min(windowEndMs, begin + FRAME_MS);
            if (end <= left || begin >= right) continue;
            if (begin < first) first = begin;
            if (end > last) last = end;
            matches++;
        }
        // At least three frames (~96ms) of actual detected speech; single
        // clicks/sound effects cannot arbitrarily move transcript boundaries.
        if (matches < 3 || last <= first) return null;
        long from = Math.max(windowStartMs, first - PAD_MS);
        long to = Math.min(windowEndMs, last + PAD_MS);
        // VAD is a suggestion only. Do not allow a crop that omits most of the
        // ASR line or creates an overly long/short forced-alignment segment.
        long intersection = Math.max(0L,
                Math.min(to, transcriptEndMs) - Math.max(from, transcriptStartMs));
        if (intersection * 2L < transcriptEndMs - transcriptStartMs
                || to - from < 200L || to - from > 11000L) return null;
        return new Bounds(from, to);
    }
}

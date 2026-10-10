package com.mihan.englishaitutor.v2;

import java.util.Arrays;

/**
 * Millisecond-based dialogue time rules and audio presentation-timestamp
 * reconciliation. Pure Java so CI runs executable regressions on every APK.
 *
 * IMPORTANT: 1 ms arithmetic is NOT a guarantee that Whisper can distinguish
 * adjacent spoken words to 1 ms, or that Android's audio renderer stops with
 * zero latency. It removes avoidable time padding, overlap and WAV drift.
 */
final class AudioTimeline {
    static final int OUTPUT_RATE = 16000;
    static final int ENERGY_FRAME_MS = 5;
    static final long EDGE_SEARCH_MS = 160L;
    static final long MIN_DIALOGUE_MS = 80L;

    private AudioTimeline() {}

    /**
     * The decoded PCM output buffer has presentationTimeUs matching the movie.
     * The number of samples already written may differ due to gaps / edits or
     * initial stream offset. Return sample count to insert (positive) or skip
     * (negative). Codec jitter under 10ms should not cause discontinuities.
     */
    static long reconcilePcmFrames(long framesWritten, long presentationTimeUs,
                                   int sampleRate) {
        if (framesWritten < 0 || presentationTimeUs < 0 || sampleRate <= 0) return 0L;
        long expected = Math.round(presentationTimeUs * (sampleRate / 1000000.0));
        long diff = expected - framesWritten;
        long jitterFrames = Math.max(1L, sampleRate / 100L); // 10 ms
        return Math.abs(diff) <= jitterFrames ? 0L : diff;
    }

    /** Selected audio ends BEFORE the next sentence's audio begins. */
    static long exclusiveEnd(long startMs, long endMs, long nextStartMs) {
        long min = Math.max(0L, startMs) + 1L;
        long last = Math.max(min, endMs);
        if (nextStartMs >= 0L) last = Math.min(last, Math.max(min, nextStartMs));
        return last;
    }

    /** Strict, non-overlapping [start,end) segment membership, no 550ms grace. */
    static boolean contains(long positionMs, long startMs, long endMs) {
        return startMs >= 0L && endMs > startMs
                && positionMs >= startMs && positionMs < endMs;
    }

    /**
     * Move the edge only toward the NEAREST sustained quiet-to-active (start)
     * or active-to-quiet (end) crossing; never pick the first/last loud
     * frame from a broad window (which grabs the previous/next actor).
     * If background music obscures a reliable transition, retain Whisper.
     */
    static long nearestSpeechEdge(double[] frameEnergy, int frameMs,
                                  long expectedMs, long lowMs, long highMs,
                                  boolean onset) {
        if (frameEnergy == null || frameEnergy.length < 5 || frameMs <= 0
                || highMs <= lowMs) return expectedMs;
        int last = frameEnergy.length - 1;
        int low = (int) Math.max(1L, Math.min(last - 2L, lowMs / frameMs));
        int high = (int) Math.max(low, Math.min(last - 2L, highMs / frameMs));
        if (high - low < 2) return expectedMs;

        double[] local = Arrays.copyOfRange(frameEnergy, low - 1, high + 3);
        Arrays.sort(local);
        double noise = local[(int) Math.floor((local.length - 1) * 0.18)];
        double speech = local[(int) Math.floor((local.length - 1) * 0.85)];
        // Insist on meaningful contrast. Constant soundtrack/music must not
        // move timestamps by hundreds of milliseconds.
        if (speech - noise < Math.max(120.0, noise * 0.4)) return expectedMs;
        double threshold = noise + (speech - noise) * 0.38;

        long best = expectedMs;
        long bestDistance = Long.MAX_VALUE;
        for (int i = low; i <= high; i++) {
            boolean edge;
            if (onset) {
                edge = frameEnergy[i - 1] < threshold
                        && frameEnergy[i] >= threshold
                        && frameEnergy[i + 1] >= threshold;
            } else {
                edge = frameEnergy[i - 1] >= threshold
                        && frameEnergy[i] < threshold
                        && frameEnergy[i + 1] < threshold;
            }
            if (!edge) continue;
            long candidate = i * (long) frameMs;
            long distance = Math.abs(candidate - expectedMs);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}

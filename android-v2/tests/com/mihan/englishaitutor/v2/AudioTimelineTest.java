package com.mihan.englishaitutor.v2;

import java.util.Arrays;

/**
 * Pure Java executable millisecond playback and PCM PTS regressions.
 * Run on every signed build (not a substitute for on-device listening).
 */
public final class AudioTimelineTest {
    private static int assertions;

    private static void check(boolean actual, String explanation) {
        assertions++;
        if (!actual) throw new AssertionError(explanation);
    }

    private static void eq(long actual, long expected, String explanation) {
        check(actual == expected, explanation + " expected=" + expected + " actual=" + actual);
    }

    public static void main(String[] args) {
        // WAV video-time integrity: old extractor lost this initial 1s offset.
        eq(AudioTimeline.reconcilePcmFrames(0, 1000000, 16000),
                16000, "1-second initial PTS gap must be preserved");
        eq(AudioTimeline.reconcilePcmFrames(16000, 1000000, 16000),
                0, "contiguous audio produces no inserted samples");
        eq(AudioTimeline.reconcilePcmFrames(16000, 1250000, 16000),
                4000, "250ms edit/gap yields 4000 samples of silence");
        eq(AudioTimeline.reconcilePcmFrames(16000, 950000, 16000),
                -800, "50ms PTS overlap trims duplicate samples");
        eq(AudioTimeline.reconcilePcmFrames(16000, 1005000, 16000),
                0, "5ms decoder jitter does not induce silence");
        eq(AudioTimeline.reconcilePcmFrames(16000, 0, 16000),
                -16000, "seek overlap is detected on absolute sample clock");
        eq(AudioTimeline.reconcilePcmFrames(0, -1, 16000),
                0, "invalid codec PTS must not corrupt waveform");
        eq(AudioTimeline.reconcilePcmFrames(0, 1000000, 48000),
                48000, "sample clock scales with output sample rate");

        // Exclusive end: no previous / next dialogue is deliberately added.
        eq(AudioTimeline.exclusiveEnd(1000, 2000, -1),
                2000, "last sentence stops at true end");
        eq(AudioTimeline.exclusiveEnd(1000, 2000, 1950),
                1950, "overlapping next sentence clips earlier one at 1950ms");
        eq(AudioTimeline.exclusiveEnd(1000, 2000, 2050),
                2000, "silence after line must not be appended to clip");
        check(AudioTimeline.contains(1000, 1000, 2000),
                "current sentence includes exact start");
        check(AudioTimeline.contains(1999, 1000, 2000),
                "current sentence includes last millisecond");
        check(!AudioTimeline.contains(2000, 1000, 2000),
                "at sentence end it must no longer be active");
        check(!AudioTimeline.contains(2499, 1000, 2000),
                "old 550ms allowance must not keep earlier line active");

        // Synthetic 5-ms waveform: previous phrase ends at 650ms, next
        // starts at 880ms. Approximate Whisper times: 420..680 / 900..1140.
        // Regression: broad first/last loud-frame search used to steal audio
        // from the previous/next phrase by up to ~1 second.
        double[] frames = new double[400];
        Arrays.fill(frames, 0.0);
        fill(frames, 400, 650, 1600.0);
        fill(frames, 880, 1100, 1600.0);

        eq(AudioTimeline.nearestSpeechEdge(frames, 5, 420, 260, 580, true),
                400, "first phrase onset at 400ms");
        eq(AudioTimeline.nearestSpeechEdge(frames, 5, 680, 520, 840, false),
                650, "first phrase offset at 650ms");
        eq(AudioTimeline.nearestSpeechEdge(frames, 5, 900, 740, 1060, true),
                880, "second phrase must start at 880ms, not inside first");
        eq(AudioTimeline.nearestSpeechEdge(frames, 5, 1140, 980, 1300, false),
                1100, "second phrase must end at 1100ms, not borrow third");

        double[] music = new double[200];
        Arrays.fill(music, 900.0);
        eq(AudioTimeline.nearestSpeechEdge(music, 5, 500, 350, 650, true),
                500, "flat music/no voice contrast cannot shift a boundary");

        System.out.println("PASS " + assertions
                + " audio PTS / strict dialogue / nearest-edge checks");
    }

    private static void fill(double[] frames, int startMs, int endMs, double energy) {
        for (int ms = startMs; ms < endMs; ms += 5) {
            frames[ms / 5] = energy;
        }
    }
}

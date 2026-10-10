package com.mihan.englishaitutor.v2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Executable acoustic-emission Viterbi alignment checks. */
public final class CtcWordAlignerTest {
    private static int checks;
    private static void require(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) {
        // Official ONNX-model vocabulary: blank=0, separator=4, G=21, O=8.
        // Emit real-shaped frame-level CTC LOGITS (32 labels) with blanks
        // between words. This tests the Viterbi path, not timestamp guessing.
        int[] phrase = {21, 8, 4, 21, 8};
        float[][] emissions = synthesized(phrase);
        CtcWordAligner.Alignment aligned = CtcWordAligner.align(
                emissions, "Go, go!", 1000L, emissions.length * 20L);
        require(aligned != null, "clear emissions must align real words");
        require(aligned.words.size() == 2, "two distinct words must be returned");
        require(aligned.words.get(0).text.equals("GO"), "first acoustic word");
        require(aligned.words.get(1).text.equals("GO"), "second acoustic word");
        require(aligned.words.get(0).startMs == 1020L,
                "word start anchored to first actual 20ms emission frame");
        require(aligned.words.get(0).endMs == 1080L,
                "word end anchored to final emitted token, not Whisper duration");
        require(aligned.words.get(1).startMs == 1140L,
                "next word must start from its own acoustic evidence");
        require(aligned.words.get(1).endMs == 1200L,
                "next word end must not borrow neighboring blank frame");
        require(aligned.words.get(0).endMs < aligned.words.get(1).startMs,
                "CTC words do not overlap");
        require(aligned.score > 0.8, "strong emission confidence");

        // "HELLO" contains two adjacent L tokens; blank between identical
        // phoneme/character labels is REQUIRED under CTC collapse semantics.
        int[] repeat = {11, 5, 15, 15, 8};
        float[][] repeated = synthesized(repeat);
        CtcWordAligner.Alignment doubleL = CtcWordAligner.align(
                repeated, "Hello", 0L, repeated.length * 20L);
        require(doubleL != null && doubleL.words.size() == 1,
                "CTC must preserve repeated letter separated by blank");
        require(doubleL.words.get(0).text.equals("HELLO"), "retain spelling");

        require(CtcWordAligner.align(emissions, "42 Go", 0L, 260L) == null,
                "digits not in 32-character vocabulary must fall back");
        require(CtcWordAligner.align(emissions, "سلام", 0L, 260L) == null,
                "non-English segment must not produce fictitious timings");
        require(CtcWordAligner.align(new float[2][32],
                "A very long transcript", 0L, 40L) == null,
                "never invent words without enough acoustic frames");
        require(CtcWordAligner.align(null, "Hello", 0L, 400L) == null,
                "never align without acoustic emissions");

        System.out.println("PASS " + checks
                + " true CTC Viterbi word emission / timestamp regressions");
    }

    private static float[][] synthesized(int[] tokens) {
        int nFrames = tokens.length * 2 + 3;
        float[][] logits = new float[nFrames][CtcWordAligner.VOCAB_SIZE];
        for (float[] f : logits) {
            Arrays.fill(f, -10f);
            f[0] = 12f;
        }
        for (int i = 0; i < tokens.length; i++) {
            int frame = 1 + i * 2;
            logits[frame][0] = -10f;
            logits[frame][tokens[i]] = 12f;
        }
        return logits;
    }
}

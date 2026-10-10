package com.mihan.englishaitutor.v2;

import java.util.Arrays;
import java.util.Collections;

/**
 * Covers real user-observed clip truncation in actor-dialogue tapping/replay.
 * Does not assert that a Whisper-only segment is acoustically verified.
 */
public final class DialogueClipPlanTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static CtcWordAligner.Word word(String text, long start, long end,
                                            double confidence) {
        return new CtcWordAligner.Word(text, start, end, confidence);
    }

    public static void main(String[] args) {
        // Device logs: previous code stopped dialogue 44 at next-row start
        // 226090, cutting away 190ms without examining the actor's words.
        DialogueClipPlan.Bounds old44 = DialogueClipPlan.resolve(
                "Hold it.", 223136L, 226280L, 226090L,
                Collections.emptyList());
        check(old44.startMs == 223136L && old44.endMs == 226280L,
                "Whisper overlap must not silently discard 190ms of selected line");
        check(old44.transcriptOverlap && !old44.acousticallyVerified,
                "an overlapping approximate segment is NOT claimed exact");

        // Another user-observed 275ms tail truncation.
        DialogueClipPlan.Bounds old55 = DialogueClipPlan.resolve(
                "Wait here.", 258386L, 259275L, 259000L, null);
        check(old55.endMs == 259275L && old55.transcriptOverlap,
                "dialogue 55 original 275ms tail is not forcibly discarded");

        // CTC covers the EXACT spoken words and can tighten the region
        // INSIDE the original ASR window with the original actor's voice.
        DialogueClipPlan.Bounds aligned = DialogueClipPlan.resolve(
                "Hold it.", 1000L, 2000L, 1900L,
                Arrays.asList(word("HOLD", 1060L, 1430L, .96),
                        word("IT", 1480L, 1880L, .99)));
        check(aligned.acousticallyVerified,
                "complete high-confidence CTC word sequence is accepted");
        check(aligned.startMs >= 1000 && aligned.startMs < 1080
                        && aligned.endMs >= 1880 && aligned.endMs <= 1905,
                "actor clip follows first/last acoustic words, not invented durations");

        // If the next Whisper row starts before an acoustically verified
        // final word ends, the previous code would have chopped that word.
        DialogueClipPlan.Bounds overlappingWord = DialogueClipPlan.resolve(
                "Go now.", 500L, 1250L, 1010L,
                Arrays.asList(word("GO", 540L, 710L, .99),
                        word("NOW", 780L, 1210L, .99)));
        check(overlappingWord.acousticallyVerified && overlappingWord.endMs > 1010,
                "cannot truncate verified final word at next subtitle's earlier start");

        // Wrong speech, incomplete word sequence, or weak first/last word
        // never passes as precise acoustic playback.
        DialogueClipPlan.Bounds missing = DialogueClipPlan.resolve(
                "Hold it.", 1000L, 2000L, 1900L,
                Arrays.asList(word("HOLD", 1100L, 1440L, .99)));
        check(!missing.acousticallyVerified && missing.endMs == 2000L,
                "incomplete CTC falls back to ASR without guessing");
        DialogueClipPlan.Bounds weak = DialogueClipPlan.resolve(
                "Hold it.", 1000L, 2000L, 1900L,
                Arrays.asList(word("HOLD", 1100L, 1440L, .40),
                        word("IT", 1490L, 1910L, .99)));
        check(!weak.acousticallyVerified,
                "low-confidence first spoken word cannot set clip boundary");

        DialogueClipPlan.Bounds ordinary = DialogueClipPlan.resolve(
                "Thanks.", 100L, 500L, 600L, null);
        check(ordinary.startMs == 100 && ordinary.endMs == 500
                        && !ordinary.transcriptOverlap,
                "non-overlapping fallback retains full segment");

        System.out.println("PASS " + checks
                + " actor clip bounds / overlapping Whisper / verified word tests");
    }
}

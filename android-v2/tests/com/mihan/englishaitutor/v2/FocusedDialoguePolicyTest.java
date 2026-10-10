package com.mihan.englishaitutor.v2;

import java.util.Arrays;
import java.util.Collections;

/** Regressions for exact-word touch vs sentence-card playback dispatch. */
public final class FocusedDialoguePolicyTest {
    private static int checks;
    private static void check(boolean pass, String name) {
        checks++;
        if (!pass) throw new AssertionError(name);
    }
    private static CtcWordAligner.Word w(String t, long start,
                                         long end, double confidence) {
        return new CtcWordAligner.Word(t, start, end, confidence);
    }
    public static void main(String[] args) {
        WordPlaybackPlan.Plan missing = WordPlaybackPlan.build(
                "That's when good things happen.", 1000, 3100,
                Collections.emptyList());
        DialogueClipPlan.Bounds approximate = DialogueClipPlan.resolve(
                missing.dialogue, 1000, 3100, 2850,
                Collections.emptyList());
        check(FocusedDialoguePolicy.needsAlignmentForWord(missing, 2),
                "tap an unaligned word requests that sentence's real actor timing");
        check(FocusedDialoguePolicy.needsAlignmentForSentence(approximate, missing),
                "tap on sentence card must prioritize sentence CTC");
        check(!FocusedDialoguePolicy.needsAlignmentForWord(missing, -1),
                "out-of-range character is never a spoken-word request");
        check(!FocusedDialoguePolicy.needsAlignmentForWord(missing, 100),
                "out-of-range word does not trigger model work");

        WordPlaybackPlan.Plan aligned = WordPlaybackPlan.build("Go now.", 0, 650,
                Arrays.asList(w("GO", 80, 240, .98), w("NOW", 260, 610, .94)));
        DialogueClipPlan.Bounds verified = DialogueClipPlan.resolve(
                aligned.dialogue, 0, 650, 540,
                Arrays.asList(w("GO", 80, 240, .98), w("NOW", 260, 610, .94)));
        check(aligned.playableCount == 2 && verified.acousticallyVerified,
                "genuine two-word acoustic alignment");
        check(!FocusedDialoguePolicy.needsAlignmentForWord(aligned, 0),
                "aligned word plays immediately as PCM, no second CTC request");
        check(!FocusedDialoguePolicy.needsAlignmentForSentence(verified, aligned),
                "verified sentence plays directly from its word boundaries");

        WordPlaybackPlan.Plan partlyWeak = WordPlaybackPlan.build("Go now.", 0, 650,
                Arrays.asList(w("GO", 80, 240, .06), w("NOW", 260, 610, .95)));
        check(partlyWeak.transcriptAligned && partlyWeak.playableCount == 1,
                "low-confidence word is disabled");
        check(!FocusedDialoguePolicy.needsAlignmentForWord(partlyWeak, 0),
                "do not invent speech or loop a failing alignment");
        System.out.println("PASS " + checks + " word/phrase touch dispatch checks");
    }
}

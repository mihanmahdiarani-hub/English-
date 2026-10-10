package com.mihan.englishaitutor.v2;

/**
 * Tap dispatch policy: a missing genuine acoustic word span asks the local
 * CTC model to align only the selected subtitle. A verified acoustic span
 * plays immediately, and a known low-confidence span is never guessed.
 *
 * Touching sentence BACKGROUND plays the complete movie line, while touching
 * an actual word attempts its original actor PCM on its own.
 */
final class FocusedDialoguePolicy {
    private FocusedDialoguePolicy() {}

    static boolean needsAlignmentForWord(WordPlaybackPlan.Plan plan, int wordIndex) {
        if (plan == null || plan.transcriptAligned) return false;
        WordPlaybackPlan.Token selected = plan.tokenAt(wordIndex);
        return selected != null && !selected.playable;
    }

    static boolean needsAlignmentForSentence(DialogueClipPlan.Bounds clip,
                                             WordPlaybackPlan.Plan plan) {
        return clip != null && plan != null
                && !clip.acousticallyVerified && !plan.transcriptAligned;
    }
}

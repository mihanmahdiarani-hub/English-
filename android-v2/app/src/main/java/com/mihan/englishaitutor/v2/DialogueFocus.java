package com.mihan.englishaitutor.v2;

/**
 * Single, testable rule for which Whisper dialogue the learner is viewing.
 *
 * A stopped video with an open teacher card MUST show the exact sentence the
 * teacher explains, even if the Media3 position listener still reports the
 * preceding sentence (e.g. a pause at an overlapping segment boundary).
 *
 * While normal video playback is running, the list follows the last line
 * actually heard. A user-tapped clip is the only playing mode which pins
 * its selected sentence until that short clip finishes.
 *
 * No Android or network dependency: the same rule is tested in CI using javac.
 */
final class DialogueFocus {
    private DialogueFocus() {}

    private static boolean valid(int index, int count) {
        return index >= 0 && index < count;
    }

    static int visibleIndex(int dialogueCount,
                            int lastHeardIndex,
                            int teacherIndex,
                            int tappedTeacherIndex,
                            int playingTappedIndex,
                            boolean videoIsPlaying) {
        if (dialogueCount <= 0) return -1;

        // A manually selected sentence is being replayed. Both the teacher
        // and the centered subtitle must remain on that exact sentence.
        if (valid(tappedTeacherIndex, dialogueCount)
                && teacherIndex == tappedTeacherIndex
                && playingTappedIndex == tappedTeacherIndex) {
            return tappedTeacherIndex;
        }

        // In paused AUTO/SMART/manual-tutor mode, the teacher card has
        // priority over a stale last-heard position reported by Media3.
        if (!videoIsPlaying && valid(teacherIndex, dialogueCount)) {
            return teacherIndex;
        }

        // During ordinary playback never freeze the transcript on an older
        // teacher card; follow the sentence that last played.
        return valid(lastHeardIndex, dialogueCount) ? lastHeardIndex : -1;
    }

    /**
     * Return the absolute Whisper dialogue index for one of the seven slots.
     * A focused line always occupies slot offset 0 ("جاری"), the previous
     * line offset -1 and the following line offset +1.
     */
    static int windowIndex(int focusedIndex, int offset, int dialogueCount) {
        if (offset < -3 || offset > 3) return -1;

        // Before the very first line has played there is no "current" line,
        // but the next three sentences should still be visible and tappable.
        if (focusedIndex == -1 && offset > 0) {
            int upcoming = offset - 1;
            return valid(upcoming, dialogueCount) ? upcoming : -1;
        }

        if (!valid(focusedIndex, dialogueCount)) return -1;
        int index = focusedIndex + offset;
        return valid(index, dialogueCount) ? index : -1;
    }

    /**
     * The "teach this dialogue" button must explain the purple/current row,
     * NEVER the raw Media3 playhead: the playhead can already be in the next
     * sentence while the highlighted last-heard row is still the previous one.
     */
    static int teacherButtonIndex(int visibleIndex, int dialogueCount) {
        return valid(visibleIndex, dialogueCount) ? visibleIndex : -1;
    }

    /**
     * The video may be resumed from ExoPlayer's built-in Play button, bypassing
     * MainActivity.continueMovie(). A lesson from an earlier sentence must not
     * remain visible as the highlighted current sentence advances.
     */
    static boolean shouldClearOldLesson(int visibleIndex,
                                        int teacherIndex,
                                        boolean ordinaryPlayback) {
        return ordinaryPlayback && teacherIndex >= 0 && visibleIndex != teacherIndex;
    }

    /**
     * When a new line is highlighted during playback, show its exact English
     * text in the tutor card immediately. Gemini is not automatically invoked
     * just by moving through a sentence in Watch mode.
     */
    static boolean needsTeacherPreview(int visibleIndex,
                                       int presentedCardIndex,
                                       int activeTeacherIndex,
                                       int dialogueCount) {
        return valid(visibleIndex, dialogueCount)
                && activeTeacherIndex != visibleIndex
                && presentedCardIndex != visibleIndex;
    }

    /**
     * Validate the ACTUAL teacher card text shown on screen, not merely the
     * Java selection index. This catches stale asynchronous text or a screen
     * refreshed in the wrong order (e.g. "next 1" explained by the teacher).
     * All real tutor/preview cards start with this exact numbered sentence.
     */
    static boolean teacherCardMatchesVisible(int visibleIndex,
                                             int dialogueCount,
                                             String sentence,
                                             String cardText) {
        if (!valid(visibleIndex, dialogueCount)) return true;
        if (sentence == null || cardText == null) return false;
        String expected = "دیالوگ " + (visibleIndex + 1) + ": " + sentence;
        int lineEnd = cardText.indexOf('\n');
        String firstLine = (lineEnd < 0 ? cardText : cardText.substring(0, lineEnd)).trim();
        return firstLine.equals("🎓 " + expected) || firstLine.equals("🎯 " + expected);
    }

    /**
     * Older asynchronous Gemini responses cannot replace a newer lesson,
     * including when the learner taps the SAME line twice in quick succession.
     */
    static boolean shouldAcceptLessonResponse(int responseIndex,
                                              long responseGeneration,
                                              int activeLessonIndex,
                                              long activeGeneration) {
        return responseIndex >= 0 && responseIndex == activeLessonIndex
                && responseGeneration == activeGeneration;
    }
}

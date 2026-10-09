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

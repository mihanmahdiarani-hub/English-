package com.mihan.englishaitutor.v2;

/**
 * Pure-Java executable regression tests.
 *
 * The exact bug seen on the user's v31 screenshot was:
 * teacher = "except a gun." (dialogue 26 / 0-based 25),
 * while the seven-line list showed it as "next 1" because last heard
 * remained dialogue 25 / 0-based 24.
 *
 * This test runs in GitHub Actions BEFORE the APK may be published.
 */
public final class DialogueFocusTest {
    private static int cases = 0;

    private static void expect(int actual, int expected, String why) {
        cases++;
        if (actual != expected) {
            throw new AssertionError(why + ": expected=" + expected + ", got=" + actual);
        }
    }

    private static void expect(boolean actual, String why) {
        cases++;
        if (!actual) throw new AssertionError(why);
    }

    public static void main(String[] args) {
        // v31 screenshot: paused teacher card says dialogue 26, timeline
        // was still one line behind. Both MUST now resolve to 0-based 25.
        expect(DialogueFocus.visibleIndex(117, 24, 25, -1, -1, false),
                25, "REGRESSION: paused teacher and highlighted line must match");

        // Clicking a future row immediately pins it even while actor speaks.
        expect(DialogueFocus.visibleIndex(117, 24, 25, 25, 25, true),
                25, "tap-selected sentence must stay current during its clip");
        expect(DialogueFocus.visibleIndex(117, 24, 25, 25, -1, false),
                25, "selected teacher stays current after actor segment stops");

        // AUTO teaching every line: older timestamps must not take precedence.
        for (int i = 0; i < 117; i++) {
            expect(DialogueFocus.visibleIndex(117, Math.max(-1, i - 1),
                    i, -1, -1, false), i,
                    "AUTO paused lesson at index " + i);
        }

        // Ordinary playback after Continue must follow real heard dialogue.
        expect(DialogueFocus.visibleIndex(117, 27, 25, -1, -1, true),
                27, "normal playback should not pin stale teacher lesson");
        expect(DialogueFocus.visibleIndex(117, 27, -1, -1, -1, false),
                27, "pause with no active lesson keeps last heard");

        // Manual seek away clears selected teacher; highlight is the
        // previous heard line, not the future one.
        expect(DialogueFocus.visibleIndex(117, 21, -1, -1, -1, false),
                21, "timeline scrub without active teacher");
        expect(DialogueFocus.visibleIndex(117, 21, 25, -1, -1, true),
                21, "playing video ignores old lesson");

        // Edge cases cannot produce a fake or out-of-range current row.
        expect(DialogueFocus.visibleIndex(0, 0, 0, 0, 0, false), -1, "empty video");
        expect(DialogueFocus.visibleIndex(1, -1, -1, -1, -1, false),
                -1, "nothing played yet");
        expect(DialogueFocus.visibleIndex(1, 3, 4, -1, -1, false),
                -1, "invalid UI state");
        expect(DialogueFocus.visibleIndex(1, -1, 0, -1, -1, false),
                0, "first line teaching");

        // Absolute seven-row mapping: "except a gun." (0-based 25) is
        // the CURRENT purple row, never the first upcoming row.
        expect(DialogueFocus.windowIndex(25, 0, 117), 25,
                "selected teacher line must be the center row");
        expect(DialogueFocus.windowIndex(25, -1, 117), 24,
                "previous row maps to its own preceding sentence");
        expect(DialogueFocus.windowIndex(25, 1, 117), 26,
                "next 1 MUST be the sentence AFTER the selected teacher");
        expect(DialogueFocus.windowIndex(0, -3, 117), -1,
                "missing early previous dialogue must be empty");
        expect(DialogueFocus.windowIndex(116, 3, 117), -1,
                "missing final next dialogue must be empty");
        expect(DialogueFocus.windowIndex(-1, 0, 117), -1,
                "empty focus has no clickable current sentence");
        expect(DialogueFocus.windowIndex(-1, 1, 117), 0,
                "before playback, first upcoming sentence stays tappable");
        expect(DialogueFocus.windowIndex(-1, 3, 117), 2,
                "before playback, third upcoming sentence stays tappable");

        // REPRO #1: Last-heard highlighted line 25, but raw player
        // position has already entered line 26. Teacher button MUST target
        // the visible purple line 25, never the raw playhead's 26.
        expect(DialogueFocus.teacherButtonIndex(24, 117), 24,
                "manual teacher button binds to highlight not playhead");
        expect(DialogueFocus.teacherButtonIndex(-1, 117), -1,
                "no highlighted line means no teacher selection");
        expect(DialogueFocus.teacherButtonIndex(117, 117), -1,
                "invalid highlighted index must not open wrong teacher");

        // REPRO #2: Native Media3 Play button bypasses continueMovie().
        // Video advances to line 26 while teacher card still explains 25.
        // The old lesson MUST be invalidated and card should preview 26.
        expect(DialogueFocus.shouldClearOldLesson(25, 24, true),
                "native player play + new line clears older Gemini lesson");
        expect(!DialogueFocus.shouldClearOldLesson(24, 24, true),
                "same line need not clear its active teacher lesson");
        expect(!DialogueFocus.shouldClearOldLesson(25, 24, false),
                "paused tutor keeps its own lesson until a real seek");
        expect(DialogueFocus.shouldClearOldLesson(-1, 24, true),
                "playing outside transcript cancels old spoken explanation");
        expect(!DialogueFocus.shouldClearOldLesson(25, -1, true),
                "no active lesson should not be invalidated");

        // The teacher card follows each newly highlighted sentence without
        // launching Gemini during ordinary playback.
        expect(DialogueFocus.needsTeacherPreview(25, 24, -1, 117),
                "newly highlighted row must update teacher card text");
        expect(!DialogueFocus.needsTeacherPreview(25, 25, -1, 117),
                "unchanged current row must not redraw same preview");
        expect(!DialogueFocus.needsTeacherPreview(25, 24, 25, 117),
                "actual Gemini lesson must not be overwritten by preview");
        expect(!DialogueFocus.needsTeacherPreview(-1, 24, -1, 117),
                "no selected dialogue cannot create phantom teacher card");

        // Late Gemini result for an earlier selection must not overwrite
        // current teacher; even repeated taps on the same line are distinct.
        expect(DialogueFocus.shouldAcceptLessonResponse(25, 12, 25, 12),
                "current request is accepted");
        expect(!DialogueFocus.shouldAcceptLessonResponse(24, 12, 25, 12),
                "previous line's response is stale");
        expect(!DialogueFocus.shouldAcceptLessonResponse(25, 11, 25, 12),
                "same-line older generation is stale");

        System.out.println("PASS " + cases
                + " deterministic English AI Tutor dialogue consistency checks");
    }
}

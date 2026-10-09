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

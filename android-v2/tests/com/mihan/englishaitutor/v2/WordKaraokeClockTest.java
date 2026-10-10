package com.mihan.englishaitutor.v2;

import java.util.Arrays;
import java.util.Collections;

/** Orange underline can move only with genuine timed actor word samples. */
public final class WordKaraokeClockTest {
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
    private static CtcWordAligner.Word word(String text, long a, long b, double c) {
        return new CtcWordAligner.Word(text, a, b, c);
    }
    public static void main(String[] args) {
        WordPlaybackPlan.Plan plan = WordPlaybackPlan.build("Go now.", 1000, 1900,
                Arrays.asList(word("Go", 1080, 1340, .98),
                        word("now", 1450, 1800, .96)));
        check(WordKaraokeClock.activeWord(plan, 1060)==-1,"no invented lead");
        check(WordKaraokeClock.activeWord(plan, 1200)==0,"first spoken word");
        check(WordKaraokeClock.activeWord(plan, 1420)==-1,"silence gap not word");
        check(WordKaraokeClock.activeWord(plan, 1580)==1,"second spoken word");
        check(WordKaraokeClock.activeWord(plan, 1880)==-1,"after final word");
        WordPlaybackPlan.Plan unavailable = WordPlaybackPlan.build(
                "Go now.",1000,1900,Collections.emptyList());
        check(WordKaraokeClock.activeWord(unavailable, 1200)==-1,
                "never animate orange on guessed/unavailable word timing");
        WordPlaybackPlan.Plan lowConfidence = WordPlaybackPlan.build(
                "Go now.",1000,1900,Arrays.asList(
                        word("Go",1080,1340,.05), word("now",1450,1800,.96)));
        check(WordKaraokeClock.activeWord(lowConfidence,1200)==-1,
                "low confidence word cannot be highlighted");
        check(WordKaraokeClock.activeWord(lowConfidence,1580)==1,
                "high confidence word may highlight even if another is weak");
        check(WordKaraokeClock.activeWord(plan,
                AudioTimeline.transcriptTimeForPlayer(1850, 250)) == 1,
                "player clock and transcript delay convert consistently");
        System.out.println("PASS live actor-word karaoke CTC and Media3 timeline tests");
    }
}

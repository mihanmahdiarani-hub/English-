package com.mihan.englishaitutor.v2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Regression gate: every clickable word points to the actor's own CTC frames. */
public final class WordPlaybackPlanTest {
    private static int checks;
    private static void require(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    private static CtcWordAligner.Word w(String name, long start, long end, double confidence) {
        return new CtcWordAligner.Word(name, start, end, confidence);
    }

    public static void main(String[] args) {
        String text = "I don't know. I don't know!";
        List<CtcWordAligner.Word> ctc = Arrays.asList(
                w("I", 100, 150, 0.95),
                w("DON'T", 165, 300, 0.90),
                w("KNOW", 310, 530, 0.92),
                w("I", 650, 690, 0.96),
                w("DON'T", 703, 827, 0.97),
                w("KNOW", 850, 1040, 0.95));
        WordPlaybackPlan.Plan plan = WordPlaybackPlan.build(text, 85, 1080, ctc);
        require(plan.transcriptAligned, "CTC word text must match exact dialogue");
        require(plan.tokens.size() == 6 && plan.playableCount == 6,
                "each repeated spoken word is separately tappable");
        require(plan.tokenAt(0).surface.equals("I"), "first I identity");
        require(plan.tokenAt(3).surface.equals("I"), "second I identity");
        require(plan.tokenAt(0).charStart == 0 && plan.tokenAt(0).charEnd == 1,
                "first word character span points to actual text");
        require(plan.tokenAt(3).charStart == 14 && plan.tokenAt(3).charEnd == 15,
                "same-spelling word occurrence keeps distinct character positions");
        require(plan.tokenAt(1).surface.equals("don't"), "apostrophe is one word");
        require(plan.tokenAt(0).audioStartMs >= 85, "never borrow preceding actor");
        require(plan.tokenAt(0).audioEndMs <= ctc.get(1).startMs,
                "never borrow next token's phoneme");
        require(plan.tokenAt(1).audioStartMs >= ctc.get(0).endMs,
                "don't begin before previous word ends");
        require(plan.tokenAt(2).audioEndMs <= ctc.get(3).startMs,
                "sentence punctuation does not merge next phrase");
        require(plan.tokenAt(5).audioEndMs <= 1080, "last word capped at dialogue end");
        require(plan.tokenAt(0).audioStartMs == 85,
                "small 18ms leading pad clamped to dialogue start");
        require(plan.tokenAt(0).audioEndMs == 165,
                "small 24ms trailing pad clamped exactly to the next word onset");
        require(plan.tokenAt(4).audioEndMs <= ctc.get(5).startMs,
                "tail never consumes following word");

        List<CtcWordAligner.Word> orderWrong = new ArrayList<>(ctc);
        orderWrong.set(1, w("KNOW", 165, 300, 0.90));
        WordPlaybackPlan.Plan wrong = WordPlaybackPlan.build(text, 85, 1080, orderWrong);
        require(!wrong.transcriptAligned && wrong.playableCount == 0,
                "wrong ASR/CTC word order cannot invent clip positions");
        require(WordPlaybackPlan.build(text, 85, 1080,
                Collections.emptyList()).playableCount == 0,
                "missing CTC must never silently use guessed Whisper word times");

        List<CtcWordAligner.Word> weak = new ArrayList<>(ctc);
        weak.set(1, w("DON'T", 165, 300, 0.08));
        WordPlaybackPlan.Plan partial = WordPlaybackPlan.build(text, 85, 1080, weak);
        require(partial.transcriptAligned && partial.playableCount == 5,
                "low-confidence single word is disabled, not synthesized");
        require(!partial.tokenAt(1).playable
                && partial.tokenAt(1).audioStartMs < 0,
                "no fake WAV seek range exposed for weak word");

        List<CtcWordAligner.Word> overlaps = new ArrayList<>(ctc);
        overlaps.set(1, w("DON'T", 145, 300, 0.99));
        require(!WordPlaybackPlan.build(text, 85, 1080, overlaps).transcriptAligned,
                "overlapping acoustic words invalidate whole alignment");

        List<CtcWordAligner.Word> outside = new ArrayList<>(ctc);
        outside.set(5, w("KNOW", 850, 1210, 0.95));
        require(!WordPlaybackPlan.build(text, 85, 1080, outside).transcriptAligned,
                "word crop cannot exceed movie dialogue boundaries");

        List<CtcWordAligner.Word> withQuote = Arrays.asList(
                w("I'M", 100, 225, 0.88), w("READY", 230, 510, 0.86));
        WordPlaybackPlan.Plan curly = WordPlaybackPlan.build(
                "I’m ready.", 80, 530, withQuote);
        require(curly.playableCount == 2 && curly.tokenAt(0).surface.equals("I’m"),
                "curly apostrophes map to English CTC apostrophe correctly");

        WordPlaybackPlan.Plan unsupported = WordPlaybackPlan.build(
                "Wait 42 seconds!", 0, 2500, Collections.emptyList());
        require(unsupported.tokens.size() == 2 && unsupported.playableCount == 0,
                "unsupported digits must NOT get fabricated acoustic word windows");
        require(plan.tokenAt(-1) == null && plan.tokenAt(99) == null,
                "out of bounds word taps safely ignored");
        require(WordPlaybackPlan.MIN_CONFIDENCE >= 0.30,
                "word playback requires stricter acoustic confidence than CTC average");

        System.out.println("PASS " + checks
                + " original-actor repeated-word mapping, CTC confidence and isolation checks");
    }
}

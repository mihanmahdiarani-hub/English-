package com.mihan.englishaitutor.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-of-truth for tapping the ORIGINAL actor's spoken WORD, not TTS.
 *
 * Text indices always refer to one *occurrence*, so repeated "I" / "no"
 * words are independently playable. Audio bounds exist only for actual
 * confidence-gated CTC word emissions; we NEVER partition Whisper sentence
 * duration into artificial equal-length word windows.
 *
 * Times are in the same PTS-aligned movie timeline as audio.wav. End is
 * EXCLUSIVE; padding is allowed only inside actual word gaps, never across
 * the next word or a neighboring dialogue.
 */
final class WordPlaybackPlan {
    private static final Pattern SPOKEN = Pattern.compile(
            "[A-Za-z]+(?:['\u2019][A-Za-z]+)*");
    private static final long LEAD_PAD_MS = 18L;
    private static final long TAIL_PAD_MS = 24L;
    private static final long MIN_PLAY_MS = 20L;
    private static final long MAX_PLAY_MS = 2500L;
    static final double MIN_CONFIDENCE = 0.35;

    static final class Token {
        final int wordIndex;
        final String surface;
        final int charStart;
        final int charEnd;
        final long audioStartMs;
        final long audioEndMs;
        final double confidence;
        final boolean playable;

        Token(int wordIndex, String surface, int charStart, int charEnd,
              long audioStartMs, long audioEndMs,
              double confidence, boolean playable) {
            this.wordIndex = wordIndex;
            this.surface = surface;
            this.charStart = charStart;
            this.charEnd = charEnd;
            this.audioStartMs = audioStartMs;
            this.audioEndMs = audioEndMs;
            this.confidence = confidence;
            this.playable = playable;
        }
    }

    static final class Plan {
        final String dialogue;
        final List<Token> tokens;
        final boolean transcriptAligned;
        final int playableCount;

        Plan(String dialogue, List<Token> tokens, boolean transcriptAligned,
             int playableCount) {
            this.dialogue = dialogue;
            this.tokens = Collections.unmodifiableList(tokens);
            this.transcriptAligned = transcriptAligned;
            this.playableCount = playableCount;
        }

        Token tokenAt(int index) {
            return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
        }
    }

    private WordPlaybackPlan() {}

    static Plan build(String dialogue, long sentenceStartMs, long sentenceEndMs,
                      List<CtcWordAligner.Word> ctc) {
        String source = dialogue == null ? "" : dialogue;
        List<Token> raw = new ArrayList<>();
        Matcher matcher = SPOKEN.matcher(source);
        while (matcher.find()) {
            raw.add(new Token(raw.size(), matcher.group(), matcher.start(),
                    matcher.end(), -1L, -1L, 0.0, false));
        }

        boolean complete = sentenceStartMs >= 0L && sentenceEndMs > sentenceStartMs
                && ctc != null && ctc.size() == raw.size() && !raw.isEmpty();
        if (complete) {
            long previousEnd = sentenceStartMs;
            for (int i = 0; i < raw.size(); i++) {
                CtcWordAligner.Word word = ctc.get(i);
                if (word == null || !normalize(raw.get(i).surface).equals(normalize(word.text))
                        || word.startMs < sentenceStartMs
                        || word.endMs > sentenceEndMs
                        || word.endMs <= word.startMs
                        || word.startMs < previousEnd
                        || !Double.isFinite(word.confidence)
                        || word.confidence < 0.0 || word.confidence > 1.0) {
                    complete = false;
                    break;
                }
                previousEnd = word.endMs;
            }
        }

        if (!complete) return new Plan(source, raw, false, 0);

        List<Token> timed = new ArrayList<>(raw.size());
        int playable = 0;
        for (int i = 0; i < raw.size(); i++) {
            CtcWordAligner.Word c = ctc.get(i);
            long previousEnd = i == 0 ? sentenceStartMs : ctc.get(i - 1).endMs;
            long nextStart = i == ctc.size() - 1
                    ? sentenceEndMs : ctc.get(i + 1).startMs;
            // Add a small natural onset/offset (up to 18/24 ms) ONLY from
            // observed acoustic gaps; never include any neighbor's voice.
            long leadGap = Math.max(0L, c.startMs - Math.max(sentenceStartMs, previousEnd));
            long tailGap = Math.max(0L, Math.min(sentenceEndMs, nextStart) - c.endMs);
            long begin = c.startMs - Math.min(LEAD_PAD_MS, leadGap);
            long end = c.endMs + Math.min(TAIL_PAD_MS, tailGap);
            boolean reliable = c.confidence >= MIN_CONFIDENCE
                    && end > begin
                    && end - begin >= MIN_PLAY_MS
                    && end - begin <= MAX_PLAY_MS
                    && begin >= sentenceStartMs && end <= sentenceEndMs
                    && (i == 0 || begin >= ctc.get(i - 1).endMs)
                    && (i == ctc.size() - 1 || end <= ctc.get(i + 1).startMs);
            Token displayed = raw.get(i);
            timed.add(new Token(i, displayed.surface,
                    displayed.charStart, displayed.charEnd,
                    reliable ? begin : -1L, reliable ? end : -1L,
                    c.confidence, reliable));
            if (reliable) playable++;
        }
        return new Plan(source, timed, true, playable);
    }

    static String normalize(String word) {
        return word == null ? "" : word.toLowerCase(Locale.US)
                .replace('\u2019', '\'').trim();
    }
}

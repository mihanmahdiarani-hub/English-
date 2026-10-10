package com.mihan.englishaitutor.v2;

import java.util.List;

/**
 * The video player's single-dialogue loop must honor the words spoken in that
 * particular transcript line, not arbitrarily stop at the next Whisper row.
 *
 * The ASR/Whisper row is a FALLBACK, not sample-accurate evidence. Only a
 * complete, confidence-gated CTC alignment of the exact spoken word sequence
 * may provide an acoustically supported start/end.
 *
 * All timestamps here are in the ORIGINAL WAV/transcript timebase. Callers
 * map to Media3 playback time with AudioTimeline.playerTimeForTranscript.
 */
final class DialogueClipPlan {
    private static final long MAX_ACOUSTIC_EDGE_SHIFT_MS = 800L;
    private static final long MIN_CLIP_MS = 40L;
    // Boundary words are held to a stronger threshold than individual taps.
    private static final double MIN_BOUNDARY_WORD_CONFIDENCE = 0.70;

    static final class Bounds {
        final long startMs;
        final long endMs;
        final boolean acousticallyVerified;
        final boolean transcriptOverlap;

        Bounds(long startMs, long endMs,
               boolean acousticallyVerified, boolean transcriptOverlap) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.acousticallyVerified = acousticallyVerified;
            this.transcriptOverlap = transcriptOverlap;
        }
    }

    private DialogueClipPlan() {}

    static Bounds resolve(String transcript, long startMs, long endMs,
                          long nextStartMs, List<CtcWordAligner.Word> acousticWords) {
        long from = Math.max(0L, startMs);
        long to = Math.max(from + 1L, endMs);
        boolean overlaps = nextStartMs >= 0L && nextStartMs < to;
        WordPlaybackPlan.Plan plan =
                WordPlaybackPlan.build(transcript, from, to, acousticWords);

        // Never trust a partial alignment for a whole sentence. It is worse
        // than the fallback if the FIRST or LAST spoken word is not aligned.
        if (plan.transcriptAligned
                && !plan.tokens.isEmpty()
                && plan.playableCount == plan.tokens.size()) {
            WordPlaybackPlan.Token first = plan.tokens.get(0);
            WordPlaybackPlan.Token last =
                    plan.tokens.get(plan.tokens.size() - 1);
            if (first.playable && last.playable
                    && first.confidence >= MIN_BOUNDARY_WORD_CONFIDENCE
                    && last.confidence >= MIN_BOUNDARY_WORD_CONFIDENCE
                    && last.audioEndMs > first.audioStartMs + MIN_CLIP_MS
                    && first.audioStartMs >= from && last.audioEndMs <= to
                    && Math.abs(first.audioStartMs - from)
                            <= MAX_ACOUSTIC_EDGE_SHIFT_MS
                    && Math.abs(last.audioEndMs - to)
                            <= MAX_ACOUSTIC_EDGE_SHIFT_MS) {
                // Do not clip the verified LAST WORD merely because Whisper
                // claims that the next subtitle already began.
                return new Bounds(first.audioStartMs, last.audioEndMs,
                        true, overlaps);
            }
        }

        // Historical bug: exclusiveEnd() truncated e.g. dialogue 44 at
        // 226090ms though its transcript ended at 226280ms. That 190ms
        // was thrown away WITHOUT acoustic evidence. Preserve the complete
        // ASR interval and explicitly mark it unverified, rather than
        // silently pretend a shorter interval matches the text.
        return new Bounds(from, to, false, overlaps);
    }
}

package com.mihan.englishaitutor.v2;

/**
 * One authoritative original-WAV word clock, driven by the actual Media3
 * playback position converted back via AudioTimeline.transcriptTimeForPlayer.
 * Never interpolate from characters or Whisper sentence length.
 */
final class WordKaraokeClock {
    private WordKaraokeClock() {}

    static int activeWord(WordPlaybackPlan.Plan plan, long transcriptMs) {
        if (plan == null || !plan.transcriptAligned || transcriptMs < 0) return -1;
        for (WordPlaybackPlan.Token word : plan.tokens) {
            if (word.playable && transcriptMs >= word.audioStartMs
                    && transcriptMs < word.audioEndMs) return word.wordIndex;
        }
        return -1;
    }
}

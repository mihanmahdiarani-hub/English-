package com.mihan.englishaitutor.v2;

/** VAD is a conservative second-pass WINDOW hint, not a word timestamp. */
public final class VadSpeechWindowTest {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        float[] probabilities = new float[110];
        // 2500ms of genuinely detectable actor speech inside a 3.5s clip.
        for (int i = 14; i < 91; i++) probabilities[i] = .95f;
        VadSpeechWindow.Bounds hit = VadSpeechWindow.choose(
                probabilities, 900L, 4420L, 1450L, 3550L);
        require(hit != null, "speech of the original line detected");
        require(hit.startMs < 1450L && hit.endMs > 3550L - 100L,
                "protect the original utterance with padding");
        require(hit.endMs - hit.startMs <= 11000L, "CTC safe maximum");
        float[] silent = new float[110];
        require(VadSpeechWindow.choose(silent,900,4420,1450,3550)==null,
                "quiet waveform must not invent acoustic evidence");
        silent[20] = 1f;
        require(VadSpeechWindow.choose(silent,900,4420,1450,3550)==null,
                "single transient is not speech");
        float[] distant = new float[110];
        for (int i = 0; i < 8; i++) distant[i] = .9f;
        require(VadSpeechWindow.choose(distant, 900, 4420, 2450, 3550)==null,
                "speech outside selected line must not substitute for its actor");
        System.out.println("PASS Silero VAD evidence window regression checks");
    }
}

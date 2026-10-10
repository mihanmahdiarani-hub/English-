package com.mihan.englishaitutor.v2;

public final class SpeechTranscriptGateTest {
    private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        check(!SpeechTranscriptGate.isSpokenText("[Music]"), "music isn't speech");
        check(!SpeechTranscriptGate.isSpokenText("[Applause]"), "clapping isn't a word");
        check(!SpeechTranscriptGate.isSpokenText("(Laughter)"), "laugh isn't spoken transcript");
        check(!SpeechTranscriptGate.isSpokenText("[sound effect]"), "sound effect not actor dialogue");
        check(SpeechTranscriptGate.isSpokenText("Paris suffers a bread shortage."),
                "genuine dialogue must be included");
        check(SpeechTranscriptGate.isSpokenText("She's playing music."),
                "spoken discussion of music must not be filtered");
        check(SpeechTranscriptGate.isSpokenText("[(music) I said no]"),
                "mixed utterance must remain eligible");
        check(!SpeechTranscriptGate.isSpokenText("   "), "empty text");
        System.out.println("PASS actual spoken-dialogue CTC denominator");
    }
}

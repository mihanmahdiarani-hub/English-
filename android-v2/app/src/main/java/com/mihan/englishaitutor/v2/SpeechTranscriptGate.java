package com.mihan.englishaitutor.v2;

import java.util.Locale;

/** Do not count a soundtrack label as a failed ACTOR utterance. */
final class SpeechTranscriptGate {
    private SpeechTranscriptGate() {}

    static boolean isSpokenText(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.length() > 2
                && ((s.charAt(0) == '[' && s.endsWith("]"))
                    || (s.charAt(0) == '(' && s.endsWith(")")))) {
            String label = s.substring(1, s.length() - 1).trim();
            return !(label.matches(".*\\b(music|applause|clapping|laughter|laughing|"
                    + "cheering|noise|silence|sound effect|instrumental|"
                    + "sighing|breathing|gunshot|footsteps)\\b.*"));
        }
        return true;
    }
}

package com.mihan.englishaitutor.v2;

final class WhisperBridge {
    static final class Segment {
        private final long startMs;
        private final long endMs;
        private final String text;
        Segment(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
        long getStartMs() { return startMs; }
        long getEndMs() { return endMs; }
        String getText() { return text; }
    }
}

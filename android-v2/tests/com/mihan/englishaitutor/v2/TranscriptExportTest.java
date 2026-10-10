package com.mihan.englishaitutor.v2;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure Java tests of exact-timestamp TXT and subtitle SRT exports. */
public final class TranscriptExportTest {
    private static int checks;
    private static void require(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        List<TranscriptExport.Row> movie = Arrays.asList(
                new TranscriptExport.Row(378, 1250, "Hello there!"),
                new TranscriptExport.Row(1250, 63456, "Where are the keys?"),
                new TranscriptExport.Row(3723456, 3724001, "I'm back."));
        String text = TranscriptExport.render(movie, TranscriptExport.Format.TXT);
        require(text.contains("Dialogue count: 3"), "entire movie count");
        require(text.contains("1. [00:00:00.378 → 00:00:01.250] Hello there!"),
                "milliseconds retained in human readable transcript");
        require(text.contains("2. [00:00:01.250 → 00:01:03.456] Where are the keys?"),
                "minutes and real timestamp");
        require(text.contains("3. [01:02:03.456 → 01:02:04.001] I'm back."),
                "hours for movie-length extraction");
        String srt = TranscriptExport.render(movie, TranscriptExport.Format.SRT);
        require(srt.startsWith("1\n00:00:00,378 --> 00:00:01,250\nHello there!"),
                "standard SRT timestamp format with commas");
        require(srt.contains("2\n00:00:01,250 --> 00:01:03,456"),
                "SRT punctuation exact, not offset");
        require(srt.contains("3\n01:02:03,456 --> 01:02:04,001"),
                "long timestamp for full movie");
        require(!srt.contains("Dialogue count:"), "SRT contains only subtitles");
        require(TranscriptExport.render(Collections.emptyList(),
                TranscriptExport.Format.SRT).isEmpty(), "empty export is empty");
        require("00:00:00,000".equals(TranscriptExport.timestamp(-100, true)),
                "never export negative timestamp");
        require(TranscriptExport.render(Collections.singletonList(
                new TranscriptExport.Row(1, 1, "A")),
                TranscriptExport.Format.SRT).contains("00:00:00,001 --> 00:00:00,002"),
                "SRT subtitle with zero-length source remains >0 ms");
        System.out.println("PASS " + checks
                + " full movie TXT/SRT user-selected timestamp export checks");
    }
}

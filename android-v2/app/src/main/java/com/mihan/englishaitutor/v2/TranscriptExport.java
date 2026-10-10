package com.mihan.englishaitutor.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text-only transcript exporter (never exports a movie or local AI credentials).
 * Android's ACTION_CREATE_DOCUMENT owns the user-selected destination.
 */
final class TranscriptExport {
    static final class Row {
        final long startMs;
        final long endMs;
        final String dialogue;
        Row(long startMs, long endMs, String dialogue) {
            this.startMs = Math.max(0L, startMs);
            this.endMs = Math.max(this.startMs, endMs);
            this.dialogue = dialogue == null ? "" : dialogue.trim();
        }
    }

    enum Format { TXT, SRT }

    private TranscriptExport() {}

    static String render(List<Row> lines, Format format) {
        StringBuilder out = new StringBuilder();
        List<Row> input = lines == null ? new ArrayList<>() : lines;
        if (format == Format.TXT) {
            out.append("English AI Tutor — Extracted movie dialogue transcript\n")
                    .append("Dialogue count: ").append(input.size()).append("\n\n");
        }
        int index = 0;
        for (Row line : input) {
            if (line == null || line.dialogue.isEmpty()) continue;
            index++;
            if (format == Format.SRT) {
                out.append(index).append('\n')
                        .append(timestamp(line.startMs, true))
                        .append(" --> ")
                        .append(timestamp(Math.max(line.startMs + 1L, line.endMs), true))
                        .append('\n').append(line.dialogue).append("\n\n");
            } else {
                out.append(index).append(". [")
                        .append(timestamp(line.startMs, false)).append(" → ")
                        .append(timestamp(line.endMs, false))
                        .append("] ").append(line.dialogue).append("\n");
            }
        }
        return out.toString();
    }

    static String timestamp(long millis, boolean subtitle) {
        long positive = Math.max(0L, millis);
        long hours = positive / 3600000L;
        long minutes = (positive / 60000L) % 60L;
        long seconds = (positive / 1000L) % 60L;
        long ms = positive % 1000L;
        return String.format(Locale.US,
                subtitle ? "%02d:%02d:%02d,%03d" : "%02d:%02d:%02d.%03d",
                hours, minutes, seconds, ms);
    }
}

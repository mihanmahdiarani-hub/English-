package com.mihan.englishaitutor.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small, Android-independent renderer model for Gemini's markdown emphasis.
 * The raw Gemini response stays unchanged in chat history; only visible text
 * has markdown emphasis markers replaced by actual styled character ranges.
 */
final class ChatMarkdownText {
    private ChatMarkdownText() {}

    static final class BoldRange {
        final int start;
        final int end;

        BoldRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    static final class Parsed {
        final String text;
        final List<BoldRange> boldRanges;

        Parsed(String text, List<BoldRange> boldRanges) {
            this.text = text;
            this.boldRanges = Collections.unmodifiableList(boldRanges);
        }
    }

    /**
     * Supports **strong** and __strong__ in mixed Persian/English paragraphs.
     * Markdown list stars ("* item") become bullets ("• item") rather than
     * being treated as emphasis. Unmatched delimiters stay literal.
     *
     * No HTML parsing is used, so generated text is never executable.
     */
    static Parsed parse(String source) {
        if (source == null || source.isEmpty())
            return new Parsed("", new ArrayList<>());

        StringBuilder visible = new StringBuilder(source.length());
        List<BoldRange> ranges = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            char ch = source.charAt(i);

            // Respect an escaped Markdown marker without interpreting it.
            if (ch == '\\' && i + 1 < source.length()
                    && (source.charAt(i + 1) == '*'
                    || source.charAt(i + 1) == '_')) {
                visible.append(source.charAt(i + 1));
                i += 2;
                continue;
            }

            // "* item" at the beginning of a line is a bullet, not bold.
            if (ch == '*' && i + 1 < source.length()
                    && source.charAt(i + 1) == ' '
                    && atLineIndent(source, i)) {
                visible.append('•').append(' ');
                i += 2;
                continue;
            }

            boolean boldOpening = (ch == '*' || ch == '_')
                    && i + 2 < source.length()
                    && source.charAt(i + 1) == ch
                    && !Character.isWhitespace(source.charAt(i + 2));
            if (boldOpening) {
                int close = closingDelimiter(source, i + 2, ch);
                if (close > i + 2) {
                    int begin = visible.length();
                    appendEscapes(visible, source, i + 2, close);
                    int end = visible.length();
                    if (end > begin) ranges.add(new BoldRange(begin, end));
                    i = close + 2;
                    continue;
                }
            }

            visible.append(ch);
            ++i;
        }
        return new Parsed(visible.toString(), ranges);
    }

    private static boolean atLineIndent(String source, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == '\n' || c == '\r') return true;
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    private static int closingDelimiter(String text, int start, char marker) {
        for (int i = start + 1; i + 1 < text.length(); i++) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (text.charAt(i) == marker && text.charAt(i + 1) == marker
                    && !Character.isWhitespace(text.charAt(i - 1))) return i;
        }
        return -1;
    }

    private static void appendEscapes(StringBuilder out, String source, int from, int to) {
        for (int i = from; i < to; i++) {
            char ch = source.charAt(i);
            if (ch == '\\' && i + 1 < to
                    && (source.charAt(i + 1) == '*'
                    || source.charAt(i + 1) == '_')) {
                out.append(source.charAt(++i));
            } else {
                out.append(ch);
            }
        }
    }
}

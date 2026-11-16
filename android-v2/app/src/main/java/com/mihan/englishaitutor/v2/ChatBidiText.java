package com.mihan.englishaitutor.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android-free bidirectional layout rules for mixed Persian/English lessons.
 *
 * Use an RTL paragraph base when Persian is present and surround Latin runs
 * inside that paragraph with directional isolates (LRI ... PDI). This keeps
 * "Who's this guy?" and English term order intact while the Persian sentence
 * itself stays right-aligned. Plain English paragraphs remain unmodified.
 */
final class ChatBidiText {
    static final String LRI = "\u2066";
    static final String PDI = "\u2069";

    private static final Pattern ENGLISH = Pattern.compile(
            "[A-Za-z][A-Za-z0-9'’/_-]*"
                    + "(?:[ \\t]+[A-Za-z0-9][A-Za-z0-9'’/_.-]*)*"
                    + "[?!:.,]*");

    static final class Range {
        final int start;
        final int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private ChatBidiText() {}

    static boolean hasRtlLanguage(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= '\u0590' && ch <= '\u08FF')
                    || (ch >= '\uFB1D' && ch <= '\uFDFF')
                    || (ch >= '\uFE70' && ch <= '\uFEFF')) return true;
        }
        return false;
    }

    static List<Range> englishRunsInMixedLines(String visible) {
        if (visible == null || visible.isEmpty()) return Collections.emptyList();
        List<Range> ranges = new ArrayList<>();
        int offset = 0;
        while (offset < visible.length()) {
            int nl = visible.indexOf('\n', offset);
            int end = nl < 0 ? visible.length() : nl;
            String line = visible.substring(offset, end);
            if (hasRtlLanguage(line)) {
                Matcher matches = ENGLISH.matcher(line);
                while (matches.find()) {
                    ranges.add(new Range(offset + matches.start(),
                            offset + matches.end()));
                }
            }
            if (nl < 0) break;
            offset = nl + 1;
        }
        return ranges;
    }

    /** Pure-Java deterministic preview; Android inserts into a Spannable to
     * preserve existing bold/italic styles as the indexes shift. */
    static String isolateEnglishInMixedLines(String visible) {
        if (visible == null || visible.isEmpty()) return "";
        StringBuilder out = new StringBuilder(visible);
        List<Range> ranges = englishRunsInMixedLines(visible);
        for (int i = ranges.size() - 1; i >= 0; i--) {
            Range r = ranges.get(i);
            out.insert(r.end, PDI);
            out.insert(r.start, LRI);
        }
        return out.toString();
    }
}

package com.mihan.englishaitutor.v2;

import java.util.List;

/** Pure Java checks for Persian paragraph direction and isolated Latin words. */
public final class ChatBidiTextTest {
    public static void main(String[] args) {
        String sample = "معنی: Who's this guy? یعنی این یارو کیه؟\n"
                + "• Responsible: «مسئول»؛ نه responsive.\n"
                + "Who is this guy?\n"
                + "گرامر: Who's = Who is.";
        check(ChatBidiText.hasRtlLanguage(sample), "has Persian");
        check(!ChatBidiText.hasRtlLanguage("Who's this guy?"),
                "English-only line must stay LTR");
        List<ChatBidiText.Range> ranges =
                ChatBidiText.englishRunsInMixedLines(sample);
        check(ranges.size() >= 5, "English phrases isolated independently");
        String rendered = ChatBidiText.isolateEnglishInMixedLines(sample);
        check(rendered.contains(ChatBidiText.LRI + "Who's this guy?"
                + ChatBidiText.PDI), "Question mark stays with LTR phrase");
        check(rendered.contains(ChatBidiText.LRI + "Responsible:"
                + ChatBidiText.PDI), "Colon stays attached to Latin term");
        check(rendered.contains(ChatBidiText.LRI + "responsive."
                + ChatBidiText.PDI), "Mixed phrase is isolated");
        check(rendered.contains("\nWho is this guy?\n"),
                "pure English paragraph does not receive isolation");
        check(rendered.contains("یعنی این یارو کیه؟"), "Persian is preserved");

        String withoutControls = rendered.replace(ChatBidiText.LRI, "")
                .replace(ChatBidiText.PDI, "");
        check(sample.equals(withoutControls), "No transcript content is edited");
        check("".equals(ChatBidiText.isolateEnglishInMixedLines(null)),
                "null safe");
        check(ChatBidiText.englishRunsInMixedLines("only English").isEmpty(),
                "skip pure English");
        System.out.println("ChatBidiTextTest: PASS");
    }

    private static void check(boolean ok, String detail) {
        if (!ok) throw new AssertionError(detail);
    }
}

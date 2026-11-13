package com.mihan.englishaitutor.v2;

/** Run with plain javac/java; the formatting rules don't need Android. */
public final class ChatMarkdownTextTest {
    public static void main(String[] args) {
        String sample = "**معنی مفهومی:**\n"
                + "به زور، خیلی کم، به سختی.\n\n"
                + "**اصطلاحات واقعی و واژگان:**\n"
                + "* **barely** (قید): تقریباً نه\n"
                + "* **sir** (اسم): آقا\n"
                + "**نکات کلیدی گرامر:**\n"
                + "مثال: **I can barely hear you**";
        ChatMarkdownText.Parsed parsed = ChatMarkdownText.parse(sample);
        check(!parsed.text.contains("**"), "bold markers must be removed");
        check(parsed.text.contains("• barely (قید)"), "bullet becomes •");
        check(parsed.text.contains("• sir (اسم)"), "second bullet becomes •");
        check(parsed.text.contains("به زور، خیلی کم، به سختی."), "Persian text preserved");
        check(parsed.boldRanges.size() == 6, "expected six strong spans");
        assertBold(parsed, "معنی مفهومی:");
        assertBold(parsed, "اصطلاحات واقعی و واژگان:");
        assertBold(parsed, "barely");
        assertBold(parsed, "sir");
        assertBold(parsed, "نکات کلیدی گرامر:");
        assertBold(parsed, "I can barely hear you");

        ChatMarkdownText.Parsed mixed =
                ChatMarkdownText.parse("It's **barely**, sir.\n__Translation__: به سختی.");
        check("It's barely, sir.\nTranslation: به سختی.".equals(mixed.text),
                "English/Persian strong formatting");
        check(mixed.boldRanges.size() == 2, "double underscore also bold");
        assertBold(mixed, "barely");
        assertBold(mixed, "Translation");

        ChatMarkdownText.Parsed malformed = ChatMarkdownText.parse(
                "Math 4 * 5. An unclosed **tag stays. Next * not a list.");
        check(malformed.text.contains("**tag stays."), "unmatched markers stay intact");
        check(malformed.boldRanges.isEmpty(), "no fake style for unmatched delimiters");

        ChatMarkdownText.Parsed escaped =
                ChatMarkdownText.parse("Literal \\**word** and **actual**.");
        check(escaped.text.contains("*word**"), "escaped leading star remains literal");
        check(escaped.boldRanges.size() == 1, "escaped opener must not form a span");
        assertBold(escaped, "actual");

        ChatMarkdownText.Parsed afterNewline =
                ChatMarkdownText.parse("Heading\n  * **example** item");
        check(afterNewline.text.contains("  • example item"), "indented bullet retained");
        check(afterNewline.boldRanges.size() == 1, "style inside indented list");

        check(ChatMarkdownText.parse(null).text.isEmpty(), "null safe");
        check(ChatMarkdownText.parse("").boldRanges.isEmpty(), "empty safe");
        System.out.println("ChatMarkdownTextTest: PASS");
    }

    private static void assertBold(ChatMarkdownText.Parsed parsed, String expected) {
        for (ChatMarkdownText.BoldRange span : parsed.boldRanges) {
            if (parsed.text.substring(span.start, span.end).equals(expected)) return;
        }
        throw new AssertionError("missing bold span: " + expected);
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}

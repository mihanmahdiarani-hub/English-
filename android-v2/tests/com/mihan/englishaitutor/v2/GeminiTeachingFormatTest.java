package com.mihan.englishaitutor.v2;

/**
 * Release-gating regression tests of actual text-only lesson card formatter.
 * Gemini JSON prompting/parsing and Android chat IME wiring are additionally
 * guarded by CI source checks and the normal Android compile.
 */
public final class GeminiTeachingFormatTest {
    private static int checks;

    private static void assertOk(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void includes(String text, String snippet, String label) {
        assertOk(text.contains(snippet), label + ": missing " + snippet);
    }

    private static void excludes(String text, String snippet, String label) {
        assertOk(!text.contains(snippet), label + ": unexpectedly contains " + snippet);
    }

    public static void main(String[] args) {
        String sentence = "Where did you put the keys?";
        String lesson = GeminiTeachingFormat.render(12, sentence,
                "put — گذاشتن، keys — کلیدها",
                "put something somewhere — گذاشتن چیزی در جایی",
                "Past Simple question: Where + did + subject + base verb?\n"
                        + "Example: Where did she go?",
                "Where did you put the keys?",
                "I put them on the table. — آن‌ها را روی میز گذاشتم.");

        assertOk(lesson.startsWith("🎯 دیالوگ 12: " + sentence),
                "first line must exactly match highlighted sentence");
        includes(lesson, "🧠 واژگان (Vocabulary)", "visible vocabulary section");
        includes(lesson, "📐 گرامر (Grammar)", "visible grammar section");
        includes(lesson, "put — گذاشتن", "vocabulary actually displayed");
        includes(lesson, "Past Simple question", "grammar actually displayed");
        includes(lesson, "اصطلاح:", "idiom retained within vocabulary section");
        includes(lesson, "❓ سؤال\nWhere did you put the keys?", "actual question visible");
        includes(lesson, "✅ پاسخ\nI put them on the table.", "answer paired with question");
        assertOk(GeminiTeachingFormat.includesAnsweredQuestion(lesson),
                "a raised question has a written answer");
        assertOk(lesson.indexOf("🧠 واژگان") < lesson.indexOf("📐 گرامر"),
                "vocabulary is followed by grammar, not mixed");
        excludes(lesson, "🔊", "Gemini lesson never prompts audio");
        excludes(lesson, "🎙", "Gemini text is not mixed with voice controls");

        String noQuestion = GeminiTeachingFormat.render(3, "Hello.",
                "Hello — سلام", "", "Greeting, no special grammar.", "", "");
        includes(noQuestion, "🧠 واژگان", "vocab for ordinary statements");
        includes(noQuestion, "📐 گرامر", "grammar for ordinary statements");
        excludes(noQuestion, "❓ سؤال", "do not invent a study question");
        excludes(noQuestion, "✅ پاسخ", "do not invent an answer");
        assertOk(!GeminiTeachingFormat.includesAnsweredQuestion(noQuestion),
                "statement does not fake a question");

        String partial = GeminiTeachingFormat.render(1, "Are you ready?",
                "ready — آماده", "", "be + adjective question",
                "Are you ready?", "");
        includes(partial, "❓ سؤال\nAre you ready?", "preserve real unanswered question");
        includes(partial, "پاسخی تولید نکرد", "honestly mark a missing answer");
        assertOk(!GeminiTeachingFormat.includesAnsweredQuestion(partial),
                "never claim AI answered when it did not");

        String empty = GeminiTeachingFormat.render(8, "Yes.", null, "", null, null, null);
        includes(empty, "واژه یا اصطلاح ویژه‌ای تشخیص داده نشد.",
                "honest vocabulary fallback");
        includes(empty, "نکته گرامری مشخصی ارائه نشد.",
                "honest grammar fallback");
        assertOk(empty.startsWith("🎯 دیالوگ 8: Yes."),
                "identical first line even with missing Gemini fields");
        System.out.println("PASS " + checks
                + " Gemini vocabulary/grammar and written Q&A card checks");
    }
}

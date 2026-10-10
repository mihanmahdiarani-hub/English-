package com.mihan.englishaitutor.v2;

/**
 * Deterministic text-only Gemini lesson presentation. Never triggers speech.
 * Every lesson visibly separates Vocabulary from Grammar. Questions are
 * displayed together with answers, never as unanswered homework.
 */
final class GeminiTeachingFormat {
    private GeminiTeachingFormat() {}

    static String render(int oneBasedDialogue, String dialogue,
                         String vocabulary, String idioms, String grammar,
                         String question, String answer) {
        StringBuilder out = new StringBuilder();
        // Must remain EXACTLY the first line understood by DialogueFocus's
        // highlighted-dialogue invariant, including the same sentence text.
        out.append("🎯 دیالوگ ").append(oneBasedDialogue).append(": ")
                .append(clean(dialogue));

        out.append("\n\n🧠 واژگان (Vocabulary)\n");
        String vocab = clean(vocabulary);
        String expressions = clean(idioms);
        if (vocab.isEmpty()) {
            out.append(expressions.isEmpty()
                    ? "واژه یا اصطلاح ویژه‌ای تشخیص داده نشد."
                    : expressions);
        } else {
            out.append(vocab);
            if (!expressions.isEmpty() && !vocab.contains(expressions)) {
                out.append("\nاصطلاح: ").append(expressions);
            }
        }

        out.append("\n\n📐 گرامر (Grammar)\n");
        String grammarText = clean(grammar);
        out.append(grammarText.isEmpty()
                ? "نکته گرامری مشخصی ارائه نشد."
                : grammarText);

        String q = clean(question);
        String a = clean(answer);
        if (!q.isEmpty()) {
            out.append("\n\n❓ سؤال\n").append(q);
            out.append("\n✅ پاسخ\n");
            out.append(a.isEmpty()
                    ? "Gemini برای این سؤال پاسخی تولید نکرد؛ می‌توانی آن را در چت بپرسی."
                    : a);
        }
        return out.toString();
    }

    static boolean includesAnsweredQuestion(String output) {
        return output != null && output.contains("❓ سؤال\n")
                && output.contains("✅ پاسخ\n")
                && !output.contains("پاسخی تولید نکرد");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

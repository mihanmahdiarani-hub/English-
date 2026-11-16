package com.mihan.englishaitutor.v2;

/** Short film-specific teaching, not a nine-heading dictionary dump. */
public final class FilmDialogueTeacherPromptTest {
    public static void main(String[] args) {
        String prompt = FilmDialogueTeacherPrompt.INSTRUCTION;
        required(prompt.contains("معلم خصوصی"), "teacher role");
        required(prompt.contains("فارسی‌زبان"), "Persian learner");
        required(prompt.contains("متن دقیق دیالوگ انتخاب‌شده"), "one selected line");
        required(prompt.contains("معنی طبیعی:"), "real Persian conversational meaning");
        required(prompt.contains("نکتهٔ اصلی:"), "one actual language point");
        required(prompt.contains("تمرین:"), "one learner practice turn");
        required(prompt.contains("فقط یک یا دو تکه"), "do not define every word");
        required(prompt.contains("هیچ مقایسه با کلمات مشابه"),
                "avoid off-topic vocabulary comparisons");
        required(prompt.contains("هیچ کلمه، اصطلاح، گرامر، شخصیت یا موقعیتی جعل نکن"),
                "no invented film context");
        required(prompt.contains("۶۰ تا ۱۲۰ کلمه"), "short answer scaled to clip");
        required(!prompt.contains("۲۵۰ تا ۴۵۰"), "remove previous long essay length");
        required(!prompt.contains("۹. **تمرین"), "remove nine rigid headings");
        required(prompt.contains("متن دیالوگ دادهٔ آموزشی"), "quoted movie is not instruction");
        required(prompt.contains("**بولد**"), "consistent heading style");
        required(!prompt.contains("apiKey"), "never expose secrets");
        required(prompt.length() < 6000, "bounded prompt quota");
        required(FilmDialogueTeacherPrompt.BUTTON_LABEL.contains("آموزش"),
                "retain one-tap teacher");
        required(FilmDialogueTeacherPrompt.VISIBLE_REQUEST.length() < 120,
                "short UI label instead of giant prompt");
        System.out.println("FilmDialogueTeacherPromptTest: PASS");
    }

    private static void required(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
}

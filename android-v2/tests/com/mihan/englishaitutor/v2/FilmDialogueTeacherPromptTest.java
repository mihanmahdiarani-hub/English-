package com.mihan.englishaitutor.v2;

/**
 * Plain-Java regressions: the teaching action is a reusable, explicit
 * fixed prompt for the currently selected dialogue only.
 */
public final class FilmDialogueTeacherPromptTest {
    public static void main(String[] args) {
        String prompt = FilmDialogueTeacherPrompt.INSTRUCTION;
        required(prompt.contains("معلم خصوصی حرفه‌ای"), "teacher role");
        required(prompt.contains("فارسی‌زبان"), "Persian learner");
        required(prompt.contains("فقط «دیالوگ جاری»"), "only current dialogue");
        required(prompt.contains("مرحله") || prompt.contains("به ترتیب"), "structured explanation");
        for (String topic : new String[]{
                "دیالوگ انگلیسی", "معنی طبیعی فارسی", "تجزیه جمله",
                "واژگان مهم", "اصطلاحات و ترکیب‌ها", "گرامر",
                "تلفظ و شنیدن", "استفاده در زندگی واقعی",
                "تمرین با معلم"}) {
            required(prompt.contains(topic), "missing teaching topic: " + topic);
        }
        required(prompt.contains("منتظر جواب من بمان"), "interactive practice waits");
        required(prompt.contains("چون فقط متن در اختیارت است"), "no fake listening");
        required(prompt.contains("به فیلم نسبت نده"), "no invented movie dialogue");
        required(prompt.contains("اضافه نکن"), "no invented transcript");
        required(prompt.contains("**بولد**"), "formatting rule");
        required(prompt.length() < 8000, "bounded, quota-conscious prompt");
        required(!prompt.contains("apiKey"), "never mention secrets");
        required(FilmDialogueTeacherPrompt.BUTTON_LABEL.contains("آموزش"),
                "button has visible teaching label");
        required(FilmDialogueTeacherPrompt.VISIBLE_REQUEST.length() < 120,
                "UI/history use short readable label, not long prompt");
        System.out.println("FilmDialogueTeacherPromptTest: PASS");
    }

    private static void required(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
}

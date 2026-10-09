package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Modern Gemini conversation layout using existing chat widgets and handlers.
 * No change to the Gemini request/response, voice input or TTS flow.
 */
final class AuroraChatScreen {
    private AuroraChatScreen() {}

    private static int dp(Activity a, float n) {
        return Math.round(n * a.getResources().getDisplayMetrics().density);
    }

    private static void detach(View child) {
        if (child.getParent() instanceof ViewGroup) {
            ((ViewGroup)child.getParent()).removeView(child);
        }
    }

    private static TextView label(Activity a, String text, String role, float size) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(size);
        t.setTag(role);
        return t;
    }

    static void mount(Activity a,
                      LinearLayout root,
                      LinearLayout header,
                      TextView privacy,
                      LinearLayout dialogueContext,
                      ScrollView chatScroll,
                      TextView statusView,
                      EditText questionInput,
                      LinearLayout actions,
                      Button micButton,
                      Button sendButton,
                      boolean hasDialogue) {
        root.removeAllViews();
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        detach(header);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout hero = new LinearLayout(a);
        hero.setTag("aurora-hero");
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(a, 11), dp(a, 7), dp(a, 12), dp(a, 7));
        AuroraDashboard.BotView robot = new AuroraDashboard.BotView(a);
        hero.addView(robot, new LinearLayout.LayoutParams(dp(a, 78), dp(a, 82)));

        LinearLayout copy = new LinearLayout(a);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView hello = label(a, "سلام! من معلم زبان تو هستم ✨", "aurora-hero-title", 17);
        hello.setMaxLines(2);
        copy.addView(hello);
        TextView subtitle = label(a, "بپرس، تمرین کن و طبیعی انگلیسی صحبت کن",
                "aurora-hero-subtitle", 11);
        subtitle.setPadding(0, dp(a, 4), 0, 0);
        copy.addView(subtitle);
        hero.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, -2);
        heroParams.topMargin = dp(a, 9);
        root.addView(hero, heroParams);

        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(0, dp(a, 5), 0, dp(a, 5));
        String[] prompts = {"مکالمه روزانه", "توضیح گرامر", "تمرین مصاحبه"};
        String[] text = {
                "Let's practice a short everyday conversation in English.",
                "لطفاً یک نکته گرامری انگلیسی را با مثال ساده توضیح بده.",
                "Please ask me an English interview question and correct my answer."
        };
        for (int i = 0; i < prompts.length; i++) {
            final String prompt = text[i];
            Button chip = new Button(a);
            chip.setTag("aurora-chip");
            chip.setText(prompts[i]);
            chip.setTextSize(10f);
            chip.setMinHeight(dp(a, 38));
            chip.setOnClickListener(v -> {
                questionInput.setText(prompt);
                questionInput.setSelection(questionInput.getText().length());
                questionInput.requestFocus();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(a, 40), 1f);
            lp.setMargins(dp(a, 2), 0, dp(a, 2), 0);
            chips.addView(chip, lp);
        }
        root.addView(chips, new LinearLayout.LayoutParams(-1, -2));

        Button contextToggle = new Button(a);
        contextToggle.setText("▾  متن دیالوگ مرتبط");
        contextToggle.setTag("aurora-tool");
        contextToggle.setContentDescription("نمایش یا بستن دیالوگ مرتبط با گفتگو");
        detach(dialogueContext);
        dialogueContext.setVisibility(hasDialogue ? View.VISIBLE : View.GONE);
        contextToggle.setOnClickListener(v -> {
            boolean visible = dialogueContext.getVisibility() == View.VISIBLE;
            dialogueContext.setVisibility(visible ? View.GONE : View.VISIBLE);
            contextToggle.setText(visible ? "▸  متن دیالوگ مرتبط" : "▾  متن دیالوگ مرتبط");
        });
        if (hasDialogue) {
            root.addView(contextToggle, new LinearLayout.LayoutParams(-1, dp(a, 38)));
            root.addView(dialogueContext, new LinearLayout.LayoutParams(-1, -2));
        }

        detach(chatScroll);
        chatScroll.setFillViewport(true);
        chatScroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams messages = new LinearLayout.LayoutParams(-1, 0, 1f);
        messages.topMargin = dp(a, 6);
        root.addView(chatScroll, messages);

        detach(statusView);
        statusView.setMaxLines(2);
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        // A true fixed composer: chat messages scroll, the input stays visible.
        LinearLayout composer = new LinearLayout(a);
        composer.setTag("aurora-card");
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(a, 5), dp(a, 5), dp(a, 5), dp(a, 5));

        detach(questionInput);
        questionInput.setMinLines(1);
        questionInput.setMaxLines(3);
        composer.addView(questionInput, new LinearLayout.LayoutParams(0, -2, 1f));

        detach(actions);
        actions.removeAllViews();
        actions.setOrientation(LinearLayout.HORIZONTAL);
        micButton.setText("🎙");
        micButton.setContentDescription("گفتن سؤال با میکروفن");
        sendButton.setText("➤");
        sendButton.setContentDescription("ارسال پیام");
        actions.addView(micButton, new LinearLayout.LayoutParams(dp(a, 51), dp(a, 49)));
        actions.addView(sendButton, new LinearLayout.LayoutParams(dp(a, 51), dp(a, 49)));
        composer.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));

        detach(privacy);
        privacy.setMaxLines(2);
        privacy.setTextSize(10f);
        privacy.setGravity(Gravity.CENTER);
        root.addView(privacy, new LinearLayout.LayoutParams(-1, -2));
    }
}

package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Minimal Gemini conversation surface.
 *
 * NO splash hero, marketing copy, preset prompt chips, privacy banner,
 * or narrator controls. The whole available height goes to dialogues/chat.
 * The composer is always outside the scrollable messages list, directly
 * above system navigation or the keyboard (IME insets belong to Activity).
 */
final class AuroraChatScreen {
    private AuroraChatScreen() {}

    private static int dp(Activity a, int px) {
        return Math.round(px * a.getResources().getDisplayMetrics().density);
    }

    private static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
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
        root.setFocusableInTouchMode(true);

        // Only a slim navigation bar is kept at the top.
        detach(header);
        header.setPadding(dp(a, 2), 0, dp(a, 2), 0);
        header.setMinimumHeight(dp(a, 39));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(a, 40)));

        if (hasDialogue) {
            // A two-line preview of the CURRENT sentence is pinned above
            // the messages. Previous/next lines are collapsed by default,
            // but remain available without taking permanent chat space.
            detach(dialogueContext);
            dialogueContext.setTag("aurora-strip");
            dialogueContext.setPadding(dp(a, 4), dp(a, 3), dp(a, 4), dp(a, 3));
            TextView previous = (TextView) dialogueContext.getChildAt(0);
            TextView current = (TextView) dialogueContext.getChildAt(1);
            TextView next = (TextView) dialogueContext.getChildAt(2);
            previous.setVisibility(View.GONE);
            next.setVisibility(View.GONE);
            current.setMaxLines(2);
            current.setEllipsize(TextUtils.TruncateAt.END);
            current.setTextSize(14f);
            current.setPadding(dp(a, 5), dp(a, 4), dp(a, 5), dp(a, 4));

            Button toggle = new Button(a);
            toggle.setText("⌄");
            toggle.setTextSize(18f);
            toggle.setContentDescription("نمایش دیالوگ قبلی و بعدی");
            toggle.setTag("aurora-tool");
            toggle.setMinWidth(0);
            toggle.setMinimumWidth(0);
            toggle.setPadding(0, 0, 0, 0);
            toggle.setOnClickListener(v -> {
                boolean expanded = previous.getVisibility() == View.VISIBLE;
                previous.setVisibility(expanded ? View.GONE : View.VISIBLE);
                next.setVisibility(expanded ? View.GONE : View.VISIBLE);
                toggle.setText(expanded ? "⌄" : "⌃");
                toggle.setContentDescription(expanded
                        ? "نمایش دیالوگ قبلی و بعدی"
                        : "بستن دیالوگ‌های قبلی و بعدی");
                current.setMaxLines(expanded ? 2 : 5);
            });
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(dialogueContext, new LinearLayout.LayoutParams(0, -2, 1f));
            line.addView(toggle, new LinearLayout.LayoutParams(dp(a, 36), dp(a, 38)));
            root.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }

        detach(chatScroll);
        chatScroll.setFillViewport(true);
        chatScroll.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams messages = new LinearLayout.LayoutParams(-1, 0, 1f);
        messages.topMargin = dp(a, 2);
        root.addView(chatScroll, messages);

        detach(statusView);
        statusView.setMaxLines(1);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setTextSize(11f);
        statusView.setPadding(dp(a, 5), dp(a, 1), dp(a, 5), dp(a, 1));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        // ONE compact fixed composer. It never scrolls away with messages.
        LinearLayout composer = new LinearLayout(a);
        composer.setTag("aurora-card");
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(a, 3), dp(a, 1), dp(a, 3), dp(a, 1));

        detach(questionInput);
        questionInput.setMinLines(1);
        questionInput.setMaxLines(3);
        questionInput.setTextSize(15f);
        questionInput.setPadding(dp(a, 8), dp(a, 5), dp(a, 8), dp(a, 5));
        composer.addView(questionInput, new LinearLayout.LayoutParams(0, -2, 1f));

        detach(actions);
        actions.removeAllViews();
        actions.setOrientation(LinearLayout.HORIZONTAL);

        micButton.setText("🎙");
        micButton.setTextSize(17f);
        micButton.setContentDescription("پرسیدن سؤال با میکروفن");
        micButton.setMinWidth(0);
        micButton.setMinimumWidth(0);
        micButton.setPadding(0, 0, 0, 0);

        sendButton.setText("➤");
        sendButton.setTextSize(18f);
        sendButton.setContentDescription("ارسال متن سؤال");
        sendButton.setMinWidth(0);
        sendButton.setMinimumWidth(0);
        sendButton.setPadding(0, 0, 0, 0);

        actions.addView(micButton, new LinearLayout.LayoutParams(dp(a, 42), dp(a, 43)));
        actions.addView(sendButton, new LinearLayout.LayoutParams(dp(a, 42), dp(a, 43)));
        composer.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));

        // The privacy information remains in app configuration/documentation;
        // it is not a large fixed banner blocking conversation space.
        detach(privacy);
    }
}

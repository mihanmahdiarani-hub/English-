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
 * Full-height ChatGPT-style Gemini conversation layout.
 *
 * No previous/next dialogue, hero, chips, permanent explanation banners,
 * expandable transcript panel or fixed context card. The ONLY movie context
 * is a small current-sentence message INSIDE the scrollable conversation.
 * Messages use the entire height between a 40dp header and a fixed composer.
 */
final class AuroraChatScreen {
    private AuroraChatScreen() {}

    private static int dp(Activity a, int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }

    private static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup)view.getParent()).removeView(view);
        }
    }

    static void mount(Activity activity,
                      LinearLayout root,
                      LinearLayout header,
                      ScrollView chatScroll,
                      TextView status,
                      EditText input,
                      Button micButton,
                      Button sendButton) {
        root.removeAllViews();
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);

        detach(header);
        header.setPadding(dp(activity, 3), 0, dp(activity, 3), 0);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(activity, 42)));

        detach(chatScroll);
        chatScroll.setFillViewport(true);
        chatScroll.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams messages = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(chatScroll, messages);

        detach(status);
        status.setTextSize(11f);
        status.setMaxLines(2);
        status.setTag("aurora-status");
        status.setVisibility(View.GONE);
        status.setPadding(dp(activity, 7), dp(activity, 2),
                dp(activity, 7), dp(activity, 2));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout composer = new LinearLayout(activity);
        composer.setTag("aurora-card");
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        composer.setPadding(dp(activity, 4), dp(activity, 2),
                dp(activity, 4), dp(activity, 2));

        detach(input);
        input.setHint("پیام...");
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setTextSize(16f);
        input.setPadding(dp(activity, 10), dp(activity, 7),
                dp(activity, 10), dp(activity, 7));
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));

        micButton.setText("🎙");
        micButton.setTextSize(17f);
        micButton.setContentDescription("پرسیدن سؤال صوتی");
        micButton.setMinWidth(0);
        micButton.setMinimumWidth(0);
        micButton.setPadding(0, 0, 0, 0);
        micButton.setTag("aurora-secondary");
        composer.addView(micButton, new LinearLayout.LayoutParams(
                dp(activity, 41), dp(activity, 45)));

        sendButton.setText("➤");
        sendButton.setTextSize(19f);
        sendButton.setContentDescription("ارسال پیام به Gemini");
        sendButton.setMinWidth(0);
        sendButton.setMinimumWidth(0);
        sendButton.setPadding(0, 0, 0, 0);
        sendButton.setTag("aurora-primary");
        composer.addView(sendButton, new LinearLayout.LayoutParams(
                dp(activity, 41), dp(activity, 45)));
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
    }
}

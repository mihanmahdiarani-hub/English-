package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.ui.PlayerView;

/**
 * v2's real, video-first native dashboard. Reparents existing controls,
 * preserving each control's original click listeners and application state.
 * This class intentionally has no references to Gemini/Whisper/Media internals.
 */
final class AuroraDashboard {
    private AuroraDashboard() {}

    private static int dp(Activity a, float n) {
        return Math.round(n * a.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout column(Activity a, String tag) {
        LinearLayout result = new LinearLayout(a);
        result.setOrientation(LinearLayout.VERTICAL);
        if (tag != null) result.setTag(tag);
        return result;
    }

    private static LinearLayout row(Activity a, String tag) {
        LinearLayout result = new LinearLayout(a);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        if (tag != null) result.setTag(tag);
        return result;
    }

    private static TextView label(Activity a, String value, String tag, int sp) {
        TextView t = new TextView(a);
        t.setText(value);
        if (tag != null) t.setTag(tag);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(true);
        return t;
    }

    private static void gap(Activity a, LinearLayout parent, float height) {
        View space = new View(a);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(a, height)));
    }

    private static void detach(View view) {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup)view.getParent()).removeView(view);
    }

    private static void anchorScroll(ScrollView scroll, View section) {
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, section.getTop() - 4)));
    }

    private static LinearLayout sectionTitle(Activity a, String eyebrow, String title,
                                             String hint) {
        LinearLayout column = column(a, null);
        column.addView(label(a, eyebrow, "aurora-kicker", 11));
        column.addView(label(a, title, "aurora-section-title", 20));
        if (hint != null && !hint.isEmpty()) {
            TextView description = label(a, hint, "aurora-muted", 12);
            description.setTextDirection(View.TEXT_DIRECTION_RTL);
            column.addView(description);
        }
        return column;
    }

    private static LinearLayout navItem(Activity a, String icon, String caption) {
        LinearLayout item = column(a, "aurora-nav-item");
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(caption);
        item.addView(label(a, icon, "aurora-nav-icon", 23),
                new LinearLayout.LayoutParams(-2, dp(a, 30)));
        item.addView(label(a, caption, "aurora-nav-label", 10),
                new LinearLayout.LayoutParams(-2, dp(a, 24)));
        return item;
    }

    static final class BotView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        BotView(Activity activity) {
            super(activity);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setContentDescription("دستیار هوشمند آموزش زبان");
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private void fill(Canvas canvas, int color, float l, float t, float r, float b,
                          float radius) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            bounds.set(l, t, r, b);
            canvas.drawRoundRect(bounds, radius, radius, paint);
        }

        private void ring(Canvas canvas, float x, float y, float radius, int color,
                          float width) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            canvas.drawCircle(x, y, radius, paint);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int save = canvas.save();
            float scale = Math.min(getWidth(), getHeight()) / 130f;
            canvas.translate((getWidth() - 130f * scale) / 2f,
                    (getHeight() - 130f * scale) / 2f);
            canvas.scale(scale, scale);

            ring(canvas, 64, 66, 58, 0x55FFFFFF, 1.4f);
            ring(canvas, 64, 66, 48, 0x33FFFFFF, 1.1f);
            fill(canvas, 50, 10, 112, 121, 126, 25);
            fill(canvas, 0xFFB6E5FF, 19, 11, 108, 112, 30);
            fill(canvas, 0xFFFFFFFF, 22, 12, 105, 107, 28);
            fill(canvas, 0xFF4F60C9, 26, 33, 102, 89, 23);
            fill(canvas, 0xFF101C4D, 29, 35, 99, 86, 21);
            fill(canvas, 0xFF9BEFFF, 18, 48, 30, 79, 9);
            fill(canvas, 0xFFB5C5FF, 101, 48, 111, 79, 9);
            ring(canvas, 47, 57, 7, 0xFF51E6FF, 6);
            ring(canvas, 81, 57, 7, 0xFF51E6FF, 6);
            paint.setColor(0xFF9FEFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.2f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            Path smile = new Path();
            smile.moveTo(57, 72);
            smile.quadTo(64, 79, 72, 72);
            canvas.drawPath(smile, paint);
            fill(canvas, 0xFFB2CFFF, 61, 4, 68, 20, 4);
            fill(canvas, 0xFF7FE6FF, 58, 2, 71, 10, 7);
            fill(canvas, 0xFFF6F1FF, 36, 94, 93, 116, 14);
            fill(canvas, 0xFF6D6CE7, 53, 94, 76, 111, 11);
            ring(canvas, 64, 104, 5, 0xFFC2EEFF, 2.4f);
            canvas.restoreToCount(save);
        }
    }

    static void mount(Activity activity,
                      LinearLayout root,
                      View header,
                      View privacy,
                      LinearLayout sourceRow,
                      LinearLayout serviceRow,
                      LinearLayout advancedRow,
                      LinearLayout tutorRow,
                      PlayerView playerView,
                      LinearLayout modes,
                      LinearLayout transcriptContext,
                      ScrollView lessonScroll,
                      TextView statusView,
                      Button directChatButton,
                      Button advancedToggle) {
        // The original Views and handlers are kept. Only their visual parents
        // change, which leaves video playback and teaching wiring untouched.
        root.removeAllViews();
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        detach(header);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout body = column(activity, null);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        body.setPadding(dp(activity, 1), dp(activity, 11), dp(activity, 1), dp(activity, 22));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        LinearLayout hero = row(activity, "aurora-hero");
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(activity, 21), dp(activity, 14), dp(activity, 11), dp(activity, 14));
        LinearLayout heroText = column(activity, null);
        heroText.addView(label(activity, "YOUR AI LANGUAGE COMPANION", "aurora-hero-eyebrow", 9));
        gap(activity, heroText, 9);
        heroText.addView(label(activity, "هر فیلم، یک درس تازه", "aurora-hero-title", 21));
        gap(activity, heroText, 7);
        TextView subtitle = label(activity, "تماشا کن، مکالمه یاد بگیر و با Gemini تمرین کن",
                "aurora-hero-subtitle", 12);
        subtitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        subtitle.setMaxLines(3);
        heroText.addView(subtitle);
        hero.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1f));
        BotView mascot = new BotView(activity);
        hero.addView(mascot, new LinearLayout.LayoutParams(dp(activity, 126), dp(activity, 130)));
        body.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        gap(activity, body, 22);
        LinearLayout watchAnchor = column(activity, null);
        watchAnchor.addView(sectionTitle(activity, "WATCH & LEARN", "یادگیری با ویدئو",
                "فیلم را از گوشی انتخاب کن و آموزش را شروع کن."));
        gap(activity, watchAnchor, 10);

        LinearLayout playerCard = column(activity, "aurora-card");
        playerCard.setPadding(dp(activity, 9), dp(activity, 10), dp(activity, 9), dp(activity, 12));
        LinearLayout playerCaption = row(activity, null);
        playerCaption.setPadding(dp(activity, 8), dp(activity, 2), dp(activity, 8), dp(activity, 9));
        playerCaption.addView(label(activity, "◉  VIDEO LESSON", "aurora-kicker", 10),
                new LinearLayout.LayoutParams(0, -2, 1f));
        playerCaption.addView(label(activity, "ویدئو محلی می‌ماند", "aurora-muted", 11));
        playerCard.addView(playerCaption);

        FrameLayout videoFrame = new FrameLayout(activity);
        videoFrame.setTag("aurora-video-frame");
        videoFrame.setClipToOutline(true);
        videoFrame.setBackgroundColor(Color.rgb(5, 9, 26));
        detach(playerView);
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(-1, -1);
        videoFrame.addView(playerView, videoParams);
        playerCard.addView(videoFrame, new LinearLayout.LayoutParams(-1, dp(activity, 207)));
        gap(activity, playerCard, 11);
        detach(sourceRow);
        playerCard.addView(sourceRow, new LinearLayout.LayoutParams(-1, -2));
        detach(statusView);
        statusView.setMaxLines(3);
        playerCard.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        watchAnchor.addView(playerCard);
        body.addView(watchAnchor);

        gap(activity, body, 22);
        LinearLayout aiAnchor = column(activity, null);
        aiAnchor.addView(sectionTitle(activity, "AI TUTOR", "معلم خصوصی تو",
                "برای مکالمه آزاد وارد چت شو یا اتصال Gemini را مدیریت کن."));
        gap(activity, aiAnchor, 10);
        LinearLayout aiCard = row(activity, "aurora-card");
        aiCard.setPadding(dp(activity, 11), dp(activity, 11),
                dp(activity, 11), dp(activity, 11));
        BotView mini = new BotView(activity);
        aiCard.addView(mini, new LinearLayout.LayoutParams(dp(activity, 68), dp(activity, 75)));
        detach(serviceRow);
        serviceRow.setOrientation(LinearLayout.VERTICAL);
        // Existing buttons use weight-based horizontal params. Once stacked,
        // replace only LayoutParams, keeping the Button instances intact.
        for (int i = 0; i < serviceRow.getChildCount(); i++) {
            View child = serviceRow.getChildAt(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(activity, 48));
            if (i > 0) lp.topMargin = dp(activity, 5);
            child.setLayoutParams(lp);
        }
        aiCard.addView(serviceRow, new LinearLayout.LayoutParams(0, -2, 1f));
        aiAnchor.addView(aiCard);
        body.addView(aiAnchor);

        gap(activity, body, 22);
        LinearLayout practiceAnchor = column(activity, null);
        practiceAnchor.addView(sectionTitle(activity, "SMART TOOLS", "کنترل هوشمند درس",
                "حالت تدریس را انتخاب کن؛ تنظیمات فعلی همچنان حفظ می‌شوند."));
        gap(activity, practiceAnchor, 10);
        LinearLayout modesCard = column(activity, "aurora-card");
        modesCard.setPadding(dp(activity, 8), dp(activity, 9),
                dp(activity, 8), dp(activity, 9));
        detach(modes);
        modesCard.addView(modes, new LinearLayout.LayoutParams(-1, -2));
        gap(activity, modesCard, 7);
        detach(tutorRow);
        modesCard.addView(tutorRow, new LinearLayout.LayoutParams(-1, -2));
        practiceAnchor.addView(modesCard);
        body.addView(practiceAnchor);

        gap(activity, body, 22);
        LinearLayout lessonAnchor = column(activity, null);
        lessonAnchor.addView(sectionTitle(activity, "LEARN EVERY LINE", "دیالوگ و توضیح",
                "جمله، ترجمه و پرسش از Gemini در همین بخش نمایش داده می‌شود."));
        gap(activity, lessonAnchor, 10);
        detach(transcriptContext);
        lessonAnchor.addView(transcriptContext, new LinearLayout.LayoutParams(-1, -2));
        gap(activity, lessonAnchor, 10);
        detach(lessonScroll);
        // Unwrap the original teaching card from its legacy fixed-height nested
        // ScrollView. All message and button Views are kept as-is; the complete
        // lesson becomes accessible in one continuous page scroll.
        View lessonContent = lessonScroll.getChildCount() > 0
                ? lessonScroll.getChildAt(0) : null;
        if (lessonContent != null) {
            detach(lessonContent);
            lessonAnchor.addView(lessonContent, new LinearLayout.LayoutParams(-1, -2));
        } else {
            lessonAnchor.addView(lessonScroll, new LinearLayout.LayoutParams(-1, -2));
        }
        body.addView(lessonAnchor);

        gap(activity, body, 22);
        LinearLayout settingsAnchor = column(activity, null);
        settingsAnchor.addView(sectionTitle(activity, "SETTINGS", "تنظیمات و عیب‌یابی",
                "تم برنامه از بالای صفحه قابل تغییر است. ابزارهای فنی در این بخش قرار دارند."));
        gap(activity, settingsAnchor, 9);
        detach(advancedRow);
        advancedRow.setVisibility(View.GONE);
        settingsAnchor.addView(advancedRow, new LinearLayout.LayoutParams(-1, -2));
        body.addView(settingsAnchor);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = row(activity, "aurora-nav");
        nav.setPadding(dp(activity, 5), dp(activity, 4),
                dp(activity, 5), dp(activity, 2));
        String[] icons = {"⌂", "▶", "✦", "▤", "⚙"};
        String[] names = {"خانه", "ویدئو", "Gemini", "درس", "تنظیمات"};
        LinearLayout[] navItems = new LinearLayout[names.length];
        for (int i = 0; i < names.length; i++) {
            navItems[i] = navItem(activity, icons[i], names[i]);
            LinearLayout.LayoutParams navParams =
                    new LinearLayout.LayoutParams(0, dp(activity, 61), 1f);
            nav.addView(navItems[i], navParams);
        }
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        Runnable selectHome = () -> setSelected(navItems, 0, activity, root);
        navItems[0].setOnClickListener(v -> {
            selectHome.run();
            scroll.smoothScrollTo(0, 0);
        });
        navItems[1].setOnClickListener(v -> {
            setSelected(navItems, 1, activity, root);
            anchorScroll(scroll, watchAnchor);
        });
        navItems[2].setOnClickListener(v -> directChatButton.performClick());
        navItems[3].setOnClickListener(v -> {
            setSelected(navItems, 3, activity, root);
            anchorScroll(scroll, lessonAnchor);
        });
        navItems[4].setOnClickListener(v -> {
            setSelected(navItems, 4, activity, root);
            advancedRow.setVisibility(View.VISIBLE);
            anchorScroll(scroll, settingsAnchor);
        });
        advancedToggle.setOnClickListener(v -> {
            advancedRow.setVisibility(advancedRow.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
            setSelected(navItems, 4, activity, root);
            anchorScroll(scroll, settingsAnchor);
        });
        setSelected(navItems, 0, activity, root);
    }

    private static void setSelected(LinearLayout[] items, int index, Activity a, View root) {
        for (int i = 0; i < items.length; i++) {
            boolean active = i == index;
            items[i].setTag(active ? "aurora-nav-selected" : "aurora-nav-item");
            for (int j = 0; j < items[i].getChildCount(); j++) {
                View child = items[i].getChildAt(j);
                child.setTag(j == 0
                        ? (active ? "aurora-nav-icon-selected" : "aurora-nav-icon")
                        : (active ? "aurora-nav-label-selected" : "aurora-nav-label"));
            }
        }
        AuroraUi.apply(a, root);
    }
}

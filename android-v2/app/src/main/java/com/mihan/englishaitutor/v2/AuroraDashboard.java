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

    static final class ViewControls {
        private final LinearLayout sourceRow;
        private final Button tinyChoose;
        private final Button tinyPrepare;
        private final LinearLayout floatingRail;
        private final Button replay;
        private final Button slow;
        private final Button next;

        ViewControls(LinearLayout sourceRow, Button tinyChoose, Button tinyPrepare,
                     LinearLayout floatingRail, Button replay, Button slow, Button next) {
            this.sourceRow = sourceRow;
            this.tinyChoose = tinyChoose;
            this.tinyPrepare = tinyPrepare;
            this.floatingRail = floatingRail;
            this.replay = replay;
            this.slow = slow;
            this.next = next;
            setReady(false);
        }

        void setReady(boolean ready) {
            sourceRow.setVisibility(ready ? View.GONE : View.VISIBLE);
            tinyChoose.setVisibility(ready ? View.VISIBLE : View.GONE);
            tinyPrepare.setVisibility(ready ? View.VISIBLE : View.GONE);
            floatingRail.setVisibility(ready ? View.VISIBLE : View.GONE);
            replay.setVisibility(ready ? View.VISIBLE : View.GONE);
            slow.setVisibility(ready ? View.VISIBLE : View.GONE);
            next.setVisibility(ready ? View.VISIBLE : View.GONE);
        }

        int sourceVisibility() {
            return sourceRow.getVisibility();
        }

        int railVisibility() {
            return floatingRail.getVisibility();
        }
    }

    static ViewControls mount(Activity activity,
                      LinearLayout root,
                      View header,
                      TextView privacy,
                      LinearLayout sourceRow,
                      LinearLayout serviceRow,
                      LinearLayout advancedRow,
                      LinearLayout tutorRow,
                      PlayerView playerView,
                      LinearLayout modes,
                      LinearLayout transcriptContext,
                      AuroraDialogueWindow dialogueWindow,
                      ScrollView lessonScroll,
                      TextView statusView,
                      Button directChatButton,
                      Button advancedToggle,
                      Button videoButton,
                      Button prepareButton,
                      Button replayButton,
                      Button slowReplayButton,
                      Button continueButton) {
        // Keep the original media player, lesson controls and click handlers.
        // Reparent views to make the media surface permanently visible.
        root.removeAllViews();
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // The mascot lives ONLY in the small top bar. Remove the old large hero.
        if (header instanceof LinearLayout) {
            LinearLayout bar = (LinearLayout) header;
            if (bar.getChildCount() > 0) bar.getChildAt(0).setVisibility(View.GONE);
            BotView topRobot = new BotView(activity);
            bar.addView(topRobot, 0, new LinearLayout.LayoutParams(
                    dp(activity, 43), dp(activity, 43)));
            bar.setPadding(dp(activity, 4), dp(activity, 2),
                    dp(activity, 4), dp(activity, 2));
        }
        detach(header);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // Sticky video: a sibling ABOVE the ScrollView, never a child of it.
        LinearLayout pinnedVideo = column(activity, "aurora-card");
        pinnedVideo.setPadding(dp(activity, 3), dp(activity, 2),
                dp(activity, 3), dp(activity, 3));
        LinearLayout playerCaption = row(activity, null);
        playerCaption.setPadding(dp(activity, 8), 0, dp(activity, 8), dp(activity, 3));
        playerCaption.addView(label(activity, "◉ VIDEO", "aurora-kicker", 9),
                new LinearLayout.LayoutParams(0, -2, 1f));
        // Once preparation is complete, the bulky source row is replaced by
        // two icon-only shortcuts ABOVE the pinned movie.
        Button tinyChoose = new Button(activity);
        tinyChoose.setTag("aurora-mini-media");
        tinyChoose.setText("🎬");
        tinyChoose.setContentDescription("انتخاب فیلم جدید");
        tinyChoose.setMinWidth(0);
        tinyChoose.setPadding(0, 0, 0, 0);
        tinyChoose.setOnClickListener(v -> videoButton.callOnClick());

        Button tinyPrepare = new Button(activity);
        tinyPrepare.setTag("aurora-mini-media");
        tinyPrepare.setText("↻");
        tinyPrepare.setContentDescription("آماده‌سازی مجدد فیلم");
        tinyPrepare.setMinWidth(0);
        tinyPrepare.setPadding(0, 0, 0, 0);
        tinyPrepare.setOnClickListener(v -> {
            if (prepareButton.isEnabled()) prepareButton.callOnClick();
        });
        LinearLayout.LayoutParams miniParamsA =
                new LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 34));
        miniParamsA.leftMargin = dp(activity, 5);
        playerCaption.addView(tinyChoose, miniParamsA);
        playerCaption.addView(tinyPrepare,
                new LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 34)));
        pinnedVideo.addView(playerCaption, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout videoFrame = new FrameLayout(activity);
        videoFrame.setTag("aurora-video-frame");
        videoFrame.setBackgroundColor(Color.rgb(5, 9, 26));
        videoFrame.setClipToOutline(true);
        detach(playerView);
        videoFrame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        pinnedVideo.addView(videoFrame, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(pinnedVideo, new LinearLayout.LayoutParams(
                -1, dp(activity, 199)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setSmoothScrollingEnabled(true);
        LinearLayout body = column(activity, null);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        body.setPadding(dp(activity, 1), dp(activity, 8),
                dp(activity, 1), dp(activity, 155));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        // Film source and preparation controls remain functional.
        LinearLayout watchAnchor = column(activity, null);
        detach(sourceRow);
        watchAnchor.addView(sourceRow, new LinearLayout.LayoutParams(-1, -2));
        detach(statusView);
        statusView.setMaxLines(3);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        watchAnchor.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        body.addView(watchAnchor);

        // Seven synced lines immediately BELOW the persistent video.
        gap(activity, body, 6);
        LinearLayout dialogueAnchor = column(activity, null);
        LinearLayout dialogueTitle = row(activity, null);
        dialogueTitle.setPadding(dp(activity, 4), 0,
                dp(activity, 4), dp(activity, 6));
        dialogueTitle.addView(label(activity, "دیالوگ‌های فیلم", "aurora-section-title", 16),
                new LinearLayout.LayoutParams(0, -2, 1f));
        dialogueTitle.addView(label(activity, "۳ قبل  •  جاری  •  ۳ بعد",
                "aurora-muted", 10));
        dialogueAnchor.addView(dialogueTitle);
        detach(dialogueWindow);
        dialogueAnchor.addView(dialogueWindow, new LinearLayout.LayoutParams(-1, -2));
        body.addView(dialogueAnchor);

        // Original SMART/AUTO/WATCH and lesson buttons, no altered behavior.
        gap(activity, body, 14);
        LinearLayout modesCard = column(activity, "aurora-card");
        modesCard.setPadding(dp(activity, 6), dp(activity, 7),
                dp(activity, 6), dp(activity, 7));
        detach(modes);
        modesCard.addView(modes, new LinearLayout.LayoutParams(-1, -2));
        gap(activity, modesCard, 5);
        detach(tutorRow);
        modesCard.addView(tutorRow, new LinearLayout.LayoutParams(-1, -2));
        body.addView(modesCard);

        gap(activity, body, 14);
        LinearLayout lessonAnchor = column(activity, null);
        lessonAnchor.addView(sectionTitle(activity, "LEARN", "آموزش همین دیالوگ",
                "معنی، ترجمه، توضیح و سؤال درباره دیالوگ جاری."));
        gap(activity, lessonAnchor, 8);
        detach(lessonScroll);
        // Expand the complete lesson into this ONE vertical scroll region.
        View lessonContent = lessonScroll.getChildCount() > 0
                ? lessonScroll.getChildAt(0) : null;
        if (lessonContent != null) {
            detach(lessonContent);
            lessonAnchor.addView(lessonContent, new LinearLayout.LayoutParams(-1, -2));
        } else {
            lessonAnchor.addView(lessonScroll, new LinearLayout.LayoutParams(-1, -2));
        }
        body.addView(lessonAnchor);

        gap(activity, body, 17);
        LinearLayout aiAnchor = column(activity, null);
        aiAnchor.addView(sectionTitle(activity, "GEMINI", "مکالمه با معلم",
                "چت مستقیم یا تنظیم اتصال Gemini"));
        gap(activity, aiAnchor, 7);
        detach(serviceRow);
        serviceRow.setOrientation(LinearLayout.HORIZONTAL);
        aiAnchor.addView(serviceRow, new LinearLayout.LayoutParams(-1, -2));
        body.addView(aiAnchor);

        gap(activity, body, 17);
        LinearLayout settingsAnchor = column(activity, null);
        settingsAnchor.addView(sectionTitle(activity, "SETTINGS", "تنظیمات و عیب‌یابی",
                "مدیریت اتصال و بررسی لاگ‌های فنی"));
        gap(activity, settingsAnchor, 7);
        detach(advancedRow);
        advancedRow.setVisibility(View.GONE);
        settingsAnchor.addView(advancedRow, new LinearLayout.LayoutParams(-1, -2));
        detach(privacy);
        privacy.setMaxLines(2);
        settingsAnchor.addView(privacy, new LinearLayout.LayoutParams(-1, -2));
        body.addView(settingsAnchor);

        // The rail uses the ORIGINAL working teaching buttons. It is attached
        // as an overlay sibling of the ScrollView, never in scrolling content.
        LinearLayout floatingRail = column(activity, "aurora-floating-rail");
        floatingRail.setGravity(Gravity.CENTER);
        floatingRail.setPadding(dp(activity, 2), dp(activity, 3),
                dp(activity, 2), dp(activity, 3));
        Button[] playbackActions = {replayButton, slowReplayButton, continueButton};
        String[] symbols = {"↻", "½×", "▶"};
        String[] descriptions = {"تکرار آخرین دیالوگ", "پخش آهسته آخرین دیالوگ", "ادامه پخش فیلم"};
        for (int i = 0; i < playbackActions.length; i++) {
            Button button = playbackActions[i];
            detach(button);
            button.setText(symbols[i]);
            button.setContentDescription(descriptions[i]);
            button.setTag("aurora-floating-action");
            button.setMinimumWidth(0);
            button.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(dp(activity, 46), dp(activity, 46));
            if (i > 0) lp.topMargin = dp(activity, 6);
            floatingRail.addView(button, lp);
        }

        FrameLayout viewport = new FrameLayout(activity);
        viewport.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams floatingParams = new FrameLayout.LayoutParams(
                dp(activity, 52), -2, Gravity.RIGHT | Gravity.BOTTOM);
        floatingParams.rightMargin = dp(activity, 6);
        floatingParams.bottomMargin = dp(activity, 10);
        viewport.addView(floatingRail, floatingParams);
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = row(activity, "aurora-nav");
        nav.setPadding(dp(activity, 4), dp(activity, 2),
                dp(activity, 4), dp(activity, 1));
        String[] icons = {"⌂", "▶", "✦", "▤", "⚙"};
        String[] names = {"خانه", "ویدئو", "Gemini", "درس", "تنظیمات"};
        LinearLayout[] navItems = new LinearLayout[names.length];
        for (int i = 0; i < names.length; i++) {
            navItems[i] = navItem(activity, icons[i], names[i]);
            nav.addView(navItems[i], new LinearLayout.LayoutParams(
                    0, dp(activity, 55), 1f));
        }
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        navItems[0].setOnClickListener(v -> {
            setSelected(navItems, 0, activity, root);
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
        return new ViewControls(sourceRow, tinyChoose, tinyPrepare, floatingRail,
                replayButton, slowReplayButton, continueButton);
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

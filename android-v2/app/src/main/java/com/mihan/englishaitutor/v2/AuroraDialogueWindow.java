package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Seven visible transcript slots: three previous lines, the currently spoken
 * line and three upcoming lines. Timing/recognition belongs exclusively to
 * MainActivity; this view only reflects the supplied current dialogue index.
 */
final class AuroraDialogueWindow extends LinearLayout {
    private static final int RADIUS = 12;
    private final LinearLayout[] slots = new LinearLayout[7];
    private final TextView[] captions = new TextView[7];
    private final TextView[] texts = new TextView[7];
    private boolean dark = true;
    private boolean hasSpokenLine = false;
    private OnDialogueTapListener tapListener;
    private final int[] visibleDialogueIndices = new int[7];

    interface OnDialogueTapListener {
        void onDialogueTap(int dialogueIndex);
    }

    void setOnDialogueTapListener(OnDialogueTapListener listener) {
        tapListener = listener;
    }

    AuroraDialogueWindow(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);
        setTag("aurora-seven-dialogues");
        setPadding(dp(7), dp(7), dp(7), dp(7));

        for (int i = 0; i < 7; i++) {
            final LinearLayout row = new LinearLayout(activity);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(5), dp(9), dp(5));
            row.setMinimumHeight(dp(43));
            // A native ripple makes it clear each real transcript line can
            // be tapped, without obscuring the active purple highlight.
            android.util.TypedValue ripple = new android.util.TypedValue();
            if (activity.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true)
                    && ripple.resourceId != 0) {
                row.setForeground(activity.getDrawable(ripple.resourceId));
            }
            row.setOnClickListener(v -> {
                int rowIndex = indexOfChild(v);
                if (rowIndex < 0 || rowIndex >= visibleDialogueIndices.length) return;
                int selected = visibleDialogueIndices[rowIndex];
                if (selected >= 0 && tapListener != null) tapListener.onDialogueTap(selected);
            });

            TextView badge = new TextView(activity);
            badge.setGravity(Gravity.CENTER);
            badge.setTextSize(10f);
            badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            badge.setSingleLine(true);

            TextView line = new TextView(activity);
            line.setTextSize(i == 3 ? 14f : 12f);
            line.setMaxLines(2);
            line.setEllipsize(TextUtils.TruncateAt.END);
            line.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            line.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);

            row.addView(badge, new LayoutParams(dp(57), -2));
            row.addView(line, new LayoutParams(0, -2, 1f));
            LayoutParams rowParams = new LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(i == 6 ? 0 : 3);
            addView(row, rowParams);

            slots[i] = row;
            captions[i] = badge;
            texts[i] = line;
        }
        setActive(-1, java.util.Collections.emptyList());
        applyTheme(AuroraUi.isDark(activity));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int fill, int edge, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), edge);
        return background;
    }

    void setActive(int index, List<String> dialogueTexts) {
        List<String> all = dialogueTexts == null
                ? java.util.Collections.emptyList() : dialogueTexts;
        int active = index >= 0 && index < all.size() ? index : -1;
        hasSpokenLine = active >= 0;
        for (int position = 0; position < 7; position++) {
            int offset = position - 3;
            // Exactly the same tested absolute mapping used by the teacher
            // selection; the focused line can never display as "بعد ۱".
            int lineIndex = DialogueFocus.windowIndex(active, offset, all.size());
            boolean valid = lineIndex >= 0;
            visibleDialogueIndices[position] = valid ? lineIndex : -1;
            slots[position].setEnabled(valid);
            slots[position].setClickable(valid);
            slots[position].setFocusable(valid);
            String label;
            if (offset == 0) label = "● جاری";
            else if (offset < 0) label = Math.abs(offset) + " قبل";
            else label = offset + " بعد";
            captions[position].setText(label);

            String content = valid ? all.get(lineIndex) : "—";
            texts[position].setText(content == null || content.trim().isEmpty() ? "—" : content);
            if (valid) {
                slots[position].setContentDescription(label + ": " + content
                        + "؛ برای پخش همین دیالوگ لمس کن");
            } else {
                slots[position].setContentDescription(label + ": بدون دیالوگ");
            }
        }
        // Only the central line is active. The seven slots stay in fixed
        // positions, with their contents advancing as the video plays.
        applyTheme(dark);
    }

    void applyTheme(boolean darkMode) {
        dark = darkMode;
        int surface = Color.parseColor(dark ? "#211B3A" : "#FFFFFF");
        int border = Color.parseColor(dark ? "#4F466D" : "#DFD8F1");
        int faint = Color.parseColor(dark ? "#2C2546" : "#F7F5FF");
        int highlight = Color.parseColor(dark ? "#59439B" : "#DED4FF");
        int highlightBorder = Color.parseColor(dark ? "#AA96FF" : "#8569E6");
        int primary = Color.parseColor(dark ? "#FFFFFF" : "#251A4A");
        int secondary = Color.parseColor(dark ? "#ABA6C6" : "#77708F");
        int violet = Color.parseColor(dark ? "#CBBEFF" : "#5F43BA");

        setBackground(rounded(surface, border, 19));
        for (int i = 0; i < 7; i++) {
            boolean current = i == 3 && hasSpokenLine;
            slots[i].setBackground(rounded(current ? highlight : faint,
                    current ? highlightBorder : border, RADIUS));
            captions[i].setTextColor(current ? primary : violet);
            texts[i].setTextColor(current ? primary : secondary);
            texts[i].setTypeface(Typeface.DEFAULT,
                    current ? Typeface.BOLD : Typeface.NORMAL);
            slots[i].setAlpha(!slots[i].isEnabled() ? 0.55f : current ? 1f : 0.96f);
        }
    }
}

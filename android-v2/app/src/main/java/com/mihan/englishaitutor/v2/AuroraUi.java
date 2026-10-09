package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.ui.PlayerView;

/**
 * Presentation-only Aurora skin for the existing native screens.
 * No playback, Gemini, lesson, network, updater or processing logic belongs here.
 */
final class AuroraUi {
    private static final String PREFS = "english_tutor_appearance";
    private static final String KEY = "theme_mode";
    private static final String[] MODES = {"system", "light", "dark"};
    private static final String[] LABELS = {"هماهنگ با گوشی", "روشن", "تاریک"};

    private AuroraUi() {}

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String selectedMode(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY, "system");
        for (String mode : MODES) if (mode.equals(saved)) return mode;
        return "system";
    }

    static boolean isDark(Activity activity) {
        String mode = selectedMode(activity);
        if ("dark".equals(mode)) return true;
        if ("light".equals(mode)) return false;
        return (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static final class Palette {
        final boolean dark;
        final int surface;
        final int lifted;
        final int text;
        final int muted;
        final int accent;
        final int edge;
        final int field;
        final int bgOne;
        final int bgTwo;
        final int accentSurface;
        Palette(boolean dark) {
            this.dark = dark;
            surface = Color.parseColor(dark ? "#252043" : "#FFFFFF");
            lifted = Color.parseColor(dark ? "#302A51" : "#F3F0FF");
            text = Color.parseColor(dark ? "#F6F5FF" : "#282243");
            muted = Color.parseColor(dark ? "#B7B4CF" : "#666481");
            accent = Color.parseColor(dark ? "#BBABFF" : "#7150D8");
            edge = Color.parseColor(dark ? "#52496E" : "#DCD6F1");
            field = Color.parseColor(dark ? "#292441" : "#F9F8FF");
            bgOne = Color.parseColor(dark ? "#110F2C" : "#F9F8FF");
            bgTwo = Color.parseColor(dark ? "#211A48" : "#EFF0FF");
            accentSurface = Color.parseColor(dark ? "#483B7A" : "#EDE6FF");
        }
    }

    private static GradientDrawable shape(Context context, int color, int stroke, float radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(context, radius));
        result.setStroke(dp(context, 1), stroke);
        return result;
    }

    private static GradientDrawable gradient(Context context, int colorA, int colorB,
                                              int stroke, float radius) {
        GradientDrawable result = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[] { colorA, colorB });
        result.setCornerRadius(dp(context, radius));
        if (stroke != Color.TRANSPARENT) result.setStroke(dp(context, 1), stroke);
        return result;
    }

    static Button appearanceButton(Activity activity, View root) {
        Button button = new Button(activity);
        button.setText("◐");
        button.setContentDescription("انتخاب تم: خودکار، روشن یا تاریک");
        button.setTag("aurora-appearance");
        button.setOnClickListener(v -> {
            String current = selectedMode(activity);
            int checked = 0;
            for (int i = 0; i < MODES.length; i++) {
                if (MODES[i].equals(current)) checked = i;
            }
            new AlertDialog.Builder(activity, isDark(activity)
                    ? android.R.style.Theme_Material_Dialog_Alert
                    : android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("ظاهر English AI Tutor")
                    .setSingleChoiceItems(LABELS, checked, (dialog, which) -> {
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putString(KEY, MODES[which]).apply();
                        apply(activity, root);
                        dialog.dismiss();
                    })
                    .setNegativeButton("بستن", null)
                    .show();
        });
        return button;
    }

    static void apply(Activity activity, View root) {
        if (root == null) return;
        Palette palette = new Palette(isDark(activity));

        root.setBackground(gradient(activity, palette.bgOne, palette.bgTwo,
                Color.TRANSPARENT, 0));
        activity.getWindow().setStatusBarColor(palette.bgOne);
        activity.getWindow().setNavigationBarColor(palette.bgOne);
        View decor = activity.getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (palette.dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
        decorate(activity, root, palette);
    }

    private static void decorate(Activity activity, View view, Palette palette) {
        // Media3 controls are owned by Media3. Do not style or traverse them.
        if (view instanceof PlayerView) return;

        // Dialogue cards manage their own current/previous/next palette.
        // Do not recolor their child TextViews after highlighting them.
        if (view instanceof AuroraDialogueWindow) {
            ((AuroraDialogueWindow) view).applyTheme(palette.dark);
            return;
        }
        Object marker = view.getTag();
        String role = marker instanceof String ? (String) marker : "";
        if (view instanceof Button) {
            Button button = (Button) view;
            button.setAllCaps(false);
            button.setGravity(Gravity.CENTER);
            button.setTextSize(12f);
            button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            button.setMinHeight(dp(activity, 43));

            if ("aurora-primary".equals(role)) {
                button.setBackground(gradient(activity, Color.parseColor("#6957E6"),
                        Color.parseColor("#A177EE"), palette.edge, 16));
                button.setTextColor(Color.WHITE);
            } else if ("aurora-appearance".equals(role)) {
                button.setBackground(shape(activity, palette.accentSurface, palette.edge, 14));
                button.setTextColor(palette.accent);
                button.setTextSize(20f);
            } else if ("aurora-mini-media".equals(role)) {
                button.setBackground(shape(activity, palette.accentSurface, palette.edge, 12));
                button.setTextColor(palette.accent);
                button.setTextSize(19f);
                button.setMinWidth(0);
                button.setPadding(0, 0, 0, 0);
            } else if ("aurora-floating-action".equals(role)) {
                button.setBackground(shape(activity, palette.accentSurface, palette.edge, 17));
                button.setTextColor(palette.accent);
                button.setTextSize(19f);
                button.setMinWidth(0);
                button.setPadding(0, 0, 0, 0);
                button.setElevation(dp(activity, 3));
            } else if ("aurora-tool".equals(role)) {
                button.setBackground(shape(activity, palette.field, palette.edge, 14));
                button.setTextColor(palette.muted);
                button.setTextSize(11f);
            } else if ("aurora-chip".equals(role)) {
                // The selected teaching mode is disabled by the existing mode logic.
                // Distinguish that state visually instead of showing a grey button.
                boolean selected = !button.isEnabled();
                button.setBackground(shape(activity, selected ? palette.accentSurface
                        : palette.lifted, selected ? palette.accent : palette.edge, 14));
                button.setTextColor(selected ? palette.accent : palette.text);
                button.setAlpha(1f);
            } else {
                button.setBackground(shape(activity, palette.lifted, palette.edge, 15));
                button.setTextColor(palette.text);
            }
        } else if (view instanceof EditText) {
            EditText field = (EditText) view;
            field.setTextColor(palette.text);
            field.setHintTextColor(palette.muted);
            field.setBackground(shape(activity, palette.field, palette.edge, 14));
            field.setPadding(dp(activity, 12), dp(activity, 10),
                    dp(activity, 12), dp(activity, 10));
        } else if (view instanceof TextView) {
            TextView label = (TextView) view;
            label.setTextColor(palette.text);
            if ("aurora-title".equals(role)) {
                label.setTextSize(17f);
                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            } else if ("aurora-logo".equals(role)) {
                label.setTextColor(palette.accent);
                label.setTextSize(29f);
                label.setGravity(Gravity.CENTER);
            } else if ("aurora-muted".equals(role) || "aurora-status".equals(role)) {
                label.setTextColor(palette.muted);
                label.setTextSize(12f);
            } else if ("aurora-heading".equals(role)) {
                label.setTextColor(palette.accent);
                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            } else if ("aurora-highlight".equals(role)) {
                label.setTextColor(palette.text);
                label.setBackground(shape(activity, palette.accentSurface, palette.edge, 14));
            } else if ("aurora-hero-eyebrow".equals(role)) {
                label.setTextColor(0xFFD9D9FF);
                label.setLetterSpacing(0.11f);
                label.setTextSize(9f);
            } else if ("aurora-hero-title".equals(role)) {
                label.setTextColor(Color.WHITE);
                label.setTextSize(20f);
                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            } else if ("aurora-hero-subtitle".equals(role)) {
                label.setTextColor(0xFFEDF0FF);
                label.setTextSize(12f);
                label.setLineSpacing(dp(activity, 2), 1.04f);
            } else if ("aurora-kicker".equals(role)) {
                label.setTextColor(palette.accent);
                label.setLetterSpacing(0.07f);
                label.setTextSize(10f);
                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            } else if ("aurora-section-title".equals(role)) {
                label.setTextColor(palette.text);
                label.setTextSize(20f);
                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            } else if ("aurora-nav-icon".equals(role)
                    || "aurora-nav-icon-selected".equals(role)) {
                label.setTextColor("aurora-nav-icon-selected".equals(role)
                        ? palette.accent : palette.muted);
                label.setTextSize(23f);
                label.setGravity(Gravity.CENTER);
            } else if ("aurora-nav-label".equals(role)
                    || "aurora-nav-label-selected".equals(role)) {
                boolean selected = "aurora-nav-label-selected".equals(role);
                label.setTextColor(selected ? palette.accent : palette.muted);
                label.setTextSize(10f);
                label.setTypeface(Typeface.DEFAULT, selected
                        ? Typeface.BOLD : Typeface.NORMAL);
                label.setGravity(Gravity.CENTER);
            }
        }

        if (view instanceof LinearLayout) {
            if ("aurora-card".equals(role)) {
                view.setBackground(shape(activity, palette.surface, palette.edge, 21));
            } else if ("aurora-strip".equals(role)) {
                view.setBackground(shape(activity, palette.lifted, palette.edge, 17));
            } else if ("aurora-hero".equals(role)) {
                view.setBackground(gradient(activity, Color.parseColor("#5944CD"),
                        Color.parseColor("#6D82E6"), Color.parseColor("#8E8FF3"), 26));
            } else if ("aurora-nav".equals(role)) {
                view.setBackground(shape(activity, palette.surface, palette.edge, 21));
                view.setElevation(dp(activity, 12));
            } else if ("aurora-floating-rail".equals(role)) {
                view.setBackground(shape(activity, palette.surface, palette.edge, 20));
                view.setElevation(dp(activity, 8));
            } else if ("aurora-nav-selected".equals(role)) {
                view.setBackground(shape(activity, palette.accentSurface,
                        palette.edge, 16));
            } else if ("aurora-nav-item".equals(role)) {
                view.setBackground(shape(activity, Color.TRANSPARENT,
                        Color.TRANSPARENT, 16));
            }
        }
        if ("aurora-video-frame".equals(role)) {
            view.setBackground(shape(activity, Color.parseColor("#080D20"),
                    palette.edge, 17));
            view.setClipToOutline(true);
        }

        if ("aurora-chat".equals(role)) {
            view.setBackground(shape(activity, palette.surface, palette.edge, 20));
            view.setPadding(dp(activity, 8), dp(activity, 8),
                    dp(activity, 8), dp(activity, 8));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                decorate(activity, group.getChildAt(i), palette);
            }
        }
    }
}

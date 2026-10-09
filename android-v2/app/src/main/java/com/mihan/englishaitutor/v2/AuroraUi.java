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

    private static boolean isDark(Activity activity) {
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
            new AlertDialog.Builder(activity)
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
            } else if ("aurora-tool".equals(role)) {
                button.setBackground(shape(activity, palette.field, palette.edge, 14));
                button.setTextColor(palette.muted);
                button.setTextSize(11f);
            } else if ("aurora-chip".equals(role)) {
                button.setBackground(shape(activity, palette.lifted, palette.edge, 14));
                button.setTextColor(palette.text);
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
                label.setTextSize(21f);
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
            }
        }

        if (view instanceof LinearLayout) {
            if ("aurora-card".equals(role)) {
                view.setBackground(shape(activity, palette.surface, palette.edge, 21));
            } else if ("aurora-strip".equals(role)) {
                view.setBackground(shape(activity, palette.lifted, palette.edge, 17));
            }
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

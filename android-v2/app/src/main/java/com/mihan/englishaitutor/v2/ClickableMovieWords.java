package com.mihan.englishaitutor.v2;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

/**
 * One tap target for EACH specific spoken word occurrence in the visible
 * movie subtitles or the teacher's large English sentence.
 *
 * ClickableSpan also exposes individual words to Android accessibility
 * services. Purple underlines mean a real reliable CTC acoustic interval
 * exists. Unaligned words remain readable but do not fake a timestamp.
 */
final class ClickableMovieWords {
    interface Listener {
        void onTap(int tokenIndex);
    }

    private ClickableMovieWords() {}

    static void bind(TextView view, WordPlaybackPlan.Plan plan, Listener listener) {
        if (view == null || plan == null) return;
        SpannableString displayed = new SpannableString(plan.dialogue);
        // TextView context can be a ContextThemeWrapper (not Activity).
        // Infer surface contrast from the actual resolved caption text color.
        final int plainColor = view.getCurrentTextColor();
        boolean dark = Color.luminance(plainColor) > 0.45;
        final int enabledColor = Color.parseColor(dark ? "#D1BEFF" : "#5738B7");

        for (WordPlaybackPlan.Token token : plan.tokens) {
            final int index = token.wordIndex;
            displayed.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    if (listener != null) listener.onTap(index);
                }

                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(token.playable ? enabledColor : plainColor);
                    paint.setUnderlineText(token.playable);
                    paint.setFakeBoldText(token.playable);
                }
            }, token.charStart, token.charEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(displayed);
        // LinkMovementMethod dispatches exact-character clicks to spans
        // rather than triggering the parent row's whole-sentence button.
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setLinksClickable(true);
        view.setHighlightColor(Color.parseColor(dark ? "#59439B" : "#DED4FF"));
    }
}

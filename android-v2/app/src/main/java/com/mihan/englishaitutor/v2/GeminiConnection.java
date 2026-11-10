package com.mihan.englishaitutor.v2;

import android.content.Context;

/**
 * Both the movie and standalone chat load the same encrypted personal Gemini
 * Developer API key. Firebase project configuration is no longer used.
 */
final class GeminiConnection {
    private GeminiConnection() {}

    static boolean configure(Context context, GeminiLessonService tutor) {
        if (context == null || tutor == null) return false;
        return tutor.configure();
    }
}

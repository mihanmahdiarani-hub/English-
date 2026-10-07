package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerativeBackend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Gemini lesson generator for the personal English AI Tutor app.
 *
 * The movie/audio never goes to Gemini. Only the current transcript line and a few
 * previous transcript lines are sent. Firebase AI Logic is used so no Gemini provider
 * secret is compiled into the APK.
 */
public final class GeminiLessonService {
    private static final String PREFS = "gemini_lessons_v1";
    private static final String MODEL = "gemini-3.8-flash";

    public interface Callback {
        void onSuccess(Lesson lesson);
        void onError(String message);
    }

    public static final class Lesson {
        public final String translationFa;
        public final String naturalMeaningFa;
        public final String grammar;
        public final String idioms;
        public final String pronunciation;
        public final String connectedSpeech;
        public final String contextNote;
        public final boolean shouldPause;
        public final double teachingScore;

        Lesson(String translationFa,
               String naturalMeaningFa,
               String grammar,
               String idioms,
               String pronunciation,
               String connectedSpeech,
               String contextNote,
               boolean shouldPause,
               double teachingScore) {
            this.translationFa = translationFa;
            this.naturalMeaningFa = naturalMeaningFa;
            this.grammar = grammar;
            this.idioms = idioms;
            this.pronunciation = pronunciation;
            this.connectedSpeech = connectedSpeech;
            this.contextNote = contextNote;
            this.shouldPause = shouldPause;
            this.teachingScore = teachingScore;
        }

        String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("translationFa", translationFa);
                o.put("naturalMeaningFa", naturalMeaningFa);
                o.put("grammar", grammar);
                o.put("idioms", idioms);
                o.put("pronunciation", pronunciation);
                o.put("connectedSpeech", connectedSpeech);
                o.put("contextNote", contextNote);
                o.put("shouldPause", shouldPause);
                o.put("teachingScore", teachingScore);
                return o.toString();
            } catch (Throwable ignored) {
                return "";
            }
        }

        static Lesson fromJson(String raw) throws Exception {
            JSONObject o = new JSONObject(raw);
            return new Lesson(
                    o.optString("translationFa", ""),
                    o.optString("naturalMeaningFa", ""),
                    o.optString("grammar", ""),
                    o.optString("idioms", ""),
                    o.optString("pronunciation", ""),
                    o.optString("connectedSpeech", ""),
                    o.optString("contextNote", ""),
                    o.optBoolean("shouldPause", true),
                    o.optDouble("teachingScore", 0.5)
            );
        }
    }

    private final Context appContext;
    private final SharedPreferences cache;
    private final Executor callbackExecutor = Executors.newSingleThreadExecutor();
    private GenerativeModelFutures model;
    private String configSignature = "";

    public GeminiLessonService(Context context) {
        appContext = context.getApplicationContext();
        cache = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized boolean configure(String apiKey, String applicationId, String projectId) {
        apiKey = safe(apiKey);
        applicationId = safe(applicationId);
        projectId = safe(projectId);
        if (apiKey.isEmpty() || applicationId.isEmpty() || projectId.isEmpty()) {
            model = null;
            Diagnostics.log("GEMINI", "configure skipped: Firebase config incomplete");
            return false;
        }

        String signature = applicationId + "|" + projectId + "|" + apiKey.hashCode();
        if (model != null && signature.equals(configSignature)) {
            Diagnostics.log("GEMINI", "configure reused existing model=" + MODEL);
            return true;
        }

        try {
            FirebaseApp firebaseApp;
            List<FirebaseApp> apps = FirebaseApp.getApps(appContext);
            if (apps.isEmpty()) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setApiKey(apiKey)
                        .setApplicationId(applicationId)
                        .setProjectId(projectId)
                        .build();
                firebaseApp = FirebaseApp.initializeApp(appContext, options);
                if (firebaseApp == null) return false;
            } else {
                firebaseApp = FirebaseApp.getInstance();
            }

            GenerativeModel ai = FirebaseAI.getInstance(GenerativeBackend.googleAI())
                    .generativeModel(MODEL);
            model = GenerativeModelFutures.from(ai);
            configSignature = signature;
            Diagnostics.log("GEMINI", "configured model=" + MODEL + " backend=googleAI");
            return true;
        } catch (Throwable t) {
            model = null;
            Diagnostics.error("GEMINI_CONFIG", t);
            return false;
        }
    }

    public boolean isConfigured() {
        return model != null;
    }

    public Lesson getCached(String text) {
        String raw = cache.getString(cacheKey(text), null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            Lesson lesson = Lesson.fromJson(raw);
            Diagnostics.log("GEMINI_CACHE", "hit lineHash=" + cacheKey(text));
            return lesson;
        } catch (Throwable t) {
            Diagnostics.error("GEMINI_CACHE", t);
            return null;
        }
    }

    public void analyze(String currentLine, List<String> previousLines, Callback callback) {
        GenerativeModelFutures localModel = model;
        if (localModel == null) {
            Diagnostics.log("GEMINI", "analyze blocked: model not configured");
            callback.onError("Gemini هنوز تنظیم نشده است.");
            return;
        }

        Lesson cached = getCached(currentLine);
        if (cached != null) {
            callback.onSuccess(cached);
            return;
        }

        Diagnostics.log("GEMINI_REQ", "begin currentChars=" + safe(currentLine).length()
                + " previousLines=" + (previousLines == null ? 0 : previousLines.size()));
        String promptText = buildPrompt(currentLine, previousLines);
        Content prompt = new Content.Builder().addText(promptText).build();
        ListenableFuture<GenerateContentResponse> future = localModel.generateContent(prompt);

        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                try {
                    String text = result == null ? null : result.getText();
                    Lesson lesson = parseLesson(text);
                    String encoded = lesson.toJson();
                    if (!encoded.isEmpty()) {
                        cache.edit().putString(cacheKey(currentLine), encoded).apply();
                    }
                    Diagnostics.log("GEMINI_RES", "success score="
                            + String.format(java.util.Locale.US, "%.2f", lesson.teachingScore)
                            + " shouldPause=" + lesson.shouldPause
                            + " responseChars=" + (text == null ? 0 : text.length()));
                    callback.onSuccess(lesson);
                } catch (Throwable t) {
                    Diagnostics.error("GEMINI_PARSE", t);
                    callback.onError("پاسخ Gemini قابل خواندن نبود: " + safeMessage(t));
                }
            }

            @Override
            public void onFailure(Throwable t) {
                Diagnostics.error("GEMINI_CALL", t);
                callback.onError("Gemini: " + safeMessage(t));
            }
        }, callbackExecutor);
    }

    private String buildPrompt(String currentLine, List<String> previousLines) {
        StringBuilder context = new StringBuilder();
        if (previousLines != null) {
            for (String s : previousLines) {
                if (s == null || s.trim().isEmpty()) continue;
                context.append("- ").append(s.trim()).append("\n");
            }
        }

        return "You are the teaching engine inside a personal English-learning movie player.\n"
                + "The learner is Persian-speaking. Analyze ONLY the current English dialogue using the nearby previous dialogue as context.\n"
                + "Do not invent visual events. If context is insufficient, say so briefly in contextNote.\n"
                + "Return ONLY one valid JSON object, no markdown and no code fences.\n"
                + "All explanations except pronunciation examples should be in Persian.\n"
                + "Schema:\n"
                + "{"
                + "\"translationFa\":\"natural Persian translation\","
                + "\"naturalMeaningFa\":\"what it really means in this context\","
                + "\"grammar\":\"short useful grammar note or empty string\","
                + "\"idioms\":\"idiom/phrasal-verb/slang explanation or empty string\","
                + "\"pronunciation\":\"practical pronunciation hint using English examples\","
                + "\"connectedSpeech\":\"connected speech/reduction note or empty string\","
                + "\"contextNote\":\"why this wording fits the dialogue context; no visual guesses\","
                + "\"shouldPause\":true,"
                + "\"teachingScore\":0.0"
                + "}\n"
                + "teachingScore must be between 0 and 1.\n\n"
                + "Previous dialogue (oldest to newest):\n"
                + (context.length() == 0 ? "(none)\n" : context)
                + "\nCURRENT DIALOGUE:\n" + safe(currentLine);
    }

    private Lesson parseLesson(String raw) throws Exception {
        if (raw == null) throw new IllegalStateException("پاسخ خالی بود");
        String clean = raw.trim();
        if (clean.startsWith("```")) {
            int firstNewline = clean.indexOf('\n');
            if (firstNewline >= 0) clean = clean.substring(firstNewline + 1);
            int fence = clean.lastIndexOf("```");
            if (fence >= 0) clean = clean.substring(0, fence).trim();
        }
        int a = clean.indexOf('{');
        int b = clean.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("JSON پیدا نشد");
        JSONObject o = new JSONObject(clean.substring(a, b + 1));

        String idioms = valueAsText(o.opt("idioms"));
        return new Lesson(
                o.optString("translationFa", ""),
                o.optString("naturalMeaningFa", ""),
                o.optString("grammar", ""),
                idioms,
                o.optString("pronunciation", ""),
                o.optString("connectedSpeech", ""),
                o.optString("contextNote", ""),
                o.optBoolean("shouldPause", true),
                clamp(o.optDouble("teachingScore", 0.5))
        );
    }

    private String valueAsText(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (s.isEmpty()) continue;
                if (out.length() > 0) out.append(" • ");
                out.append(s);
            }
            return out.toString();
        }
        return String.valueOf(value);
    }

    private String cacheKey(String text) {
        return "lesson_" + safe(text).toLowerCase().trim().hashCode();
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "خطای نامشخص";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}

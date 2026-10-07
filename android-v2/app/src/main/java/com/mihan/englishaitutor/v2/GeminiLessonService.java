package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.debug.internal.StorageHelper;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerativeBackend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // The current free-tier quota observed by the app is small. Keep requests serialized
    // and spaced out so normal movie playback cannot burst multiple Gemini calls at once.
    private static final long MIN_REQUEST_GAP_MS = 15_000L;
    private static final long DEFAULT_QUOTA_COOLDOWN_MS = 45_000L;
    private static final long HIGH_DEMAND_COOLDOWN_MS = 35_000L;
    private static final long UNKNOWN_TRANSIENT_COOLDOWN_MS = 20_000L;
    private static final int MAX_TRANSIENT_RETRIES = 1;
    private static final Pattern RETRY_SECONDS =
            Pattern.compile("retry\\s+in\\s+([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);

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
    private final ScheduledExecutorService queueExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final ArrayDeque<PendingRequest> requestQueue = new ArrayDeque<>();
    private final Map<String, PendingRequest> pendingByKey = new HashMap<>();

    private GenerativeModelFutures model;
    private String configSignature = "";
    private String appCheckDebugSecret = "";

    private boolean requestInFlight = false;
    private boolean pumpScheduled = false;
    private long nextRequestAtMs = 0L;

    private static final class PendingRequest {
        final String currentLine;
        final List<String> previousLines;
        final String cacheKey;
        final List<Callback> callbacks = new ArrayList<>();
        int retryCount = 0;

        PendingRequest(String currentLine, List<String> previousLines, String cacheKey, Callback callback) {
            this.currentLine = currentLine;
            this.previousLines = previousLines;
            this.cacheKey = cacheKey;
            this.callbacks.add(callback);
        }
    }

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

            StorageHelper debugStorage =
                    new StorageHelper(appContext, firebaseApp.getPersistenceKey());
            String debugSecret = debugStorage.retrieveDebugSecret();
            if (debugSecret == null || debugSecret.trim().isEmpty()) {
                debugSecret = UUID.randomUUID().toString();
                debugStorage.saveDebugSecret(debugSecret);
            }
            appCheckDebugSecret = debugSecret;

            FirebaseAppCheck firebaseAppCheck = FirebaseAppCheck.getInstance(firebaseApp);
            firebaseAppCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance());
            Diagnostics.log("APP_CHECK", "debug provider installed; secretReady=true");

            GenerativeModel ai = FirebaseAI
                    .getInstance(firebaseApp, GenerativeBackend.googleAI())
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

    public String getAppCheckDebugSecret() {
        return appCheckDebugSecret;
    }

    public Lesson getCached(String text) {
        return getCached(text, null);
    }

    public Lesson getCached(String currentLine, List<String> previousLines) {
        String key = cacheKey(currentLine, previousLines);
        String raw = cache.getString(key, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            Lesson lesson = Lesson.fromJson(raw);
            Diagnostics.log("GEMINI_CACHE", "hit key=" + key);
            return lesson;
        } catch (Throwable t) {
            Diagnostics.error("GEMINI_CACHE", t);
            return null;
        }
    }

    public void analyze(String currentLine, List<String> previousLines, Callback callback) {
        if (callback == null) return;

        GenerativeModelFutures localModel = model;
        if (localModel == null) {
            Diagnostics.log("GEMINI", "analyze blocked: model not configured");
            callback.onError("Gemini هنوز تنظیم نشده است.");
            return;
        }

        List<String> contextCopy = previousLines == null
                ? new ArrayList<>()
                : new ArrayList<>(previousLines);
        String key = cacheKey(currentLine, contextCopy);

        Lesson cached = getCached(currentLine, contextCopy);
        if (cached != null) {
            callback.onSuccess(cached);
            return;
        }

        synchronized (this) {
            PendingRequest existing = pendingByKey.get(key);
            if (existing != null) {
                existing.callbacks.add(callback);
                Diagnostics.log("GEMINI_QUEUE", "dedupe key=" + key
                        + " callbacks=" + existing.callbacks.size());
                return;
            }

            PendingRequest request =
                    new PendingRequest(safe(currentLine), contextCopy, key, callback);
            requestQueue.addLast(request);
            pendingByKey.put(key, request);
            Diagnostics.log("GEMINI_QUEUE", "queued size=" + requestQueue.size()
                    + " currentChars=" + request.currentLine.length());
            schedulePumpLocked(0L);
        }
    }

    private void schedulePumpLocked(long delayMs) {
        if (pumpScheduled) return;
        pumpScheduled = true;
        queueExecutor.schedule(() -> {
            synchronized (GeminiLessonService.this) {
                pumpScheduled = false;
            }
            pumpQueue();
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private void pumpQueue() {
        PendingRequest request;
        GenerativeModelFutures localModel;

        synchronized (this) {
            if (requestInFlight || requestQueue.isEmpty()) return;

            long now = System.currentTimeMillis();
            long waitMs = nextRequestAtMs - now;
            if (waitMs > 0L) {
                Diagnostics.log("GEMINI_QUEUE", "cooldown waitMs=" + waitMs
                        + " queued=" + requestQueue.size());
                schedulePumpLocked(waitMs);
                return;
            }

            localModel = model;
            if (localModel == null) {
                request = requestQueue.pollFirst();
                if (request != null) {
                    pendingByKey.remove(request.cacheKey);
                }
            } else {
                request = requestQueue.pollFirst();
                requestInFlight = request != null;
            }
        }

        if (request == null) return;
        if (localModel == null) {
            deliverError(request, "Gemini هنوز تنظیم نشده است.");
            synchronized (this) {
                schedulePumpLocked(0L);
            }
            return;
        }

        executeRequest(localModel, request);
    }

    private void executeRequest(GenerativeModelFutures localModel, PendingRequest request) {
        Diagnostics.log("GEMINI_REQ", "begin currentChars=" + request.currentLine.length()
                + " previousLines=" + request.previousLines.size()
                + " retry=" + request.retryCount
                + " queuedRemaining=" + queuedCount());

        String promptText = buildPrompt(request.currentLine, request.previousLines);
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
                        cache.edit().putString(request.cacheKey, encoded).apply();
                    }
                    Diagnostics.log("GEMINI_RES", "success score="
                            + String.format(Locale.US, "%.2f", lesson.teachingScore)
                            + " shouldPause=" + lesson.shouldPause
                            + " responseChars=" + (text == null ? 0 : text.length()));
                    finishSuccess(request, lesson);
                } catch (Throwable t) {
                    Diagnostics.error("GEMINI_PARSE", t);
                    finishError(request, "پاسخ Gemini قابل خواندن نبود: " + safeMessage(t));
                }
            }

            @Override
            public void onFailure(Throwable t) {
                Diagnostics.error("GEMINI_CALL", t);
                String message = safeMessage(t);
                long backoffMs = transientBackoffMs(t, message);

                if (backoffMs > 0L && request.retryCount < MAX_TRANSIENT_RETRIES) {
                    request.retryCount++;
                    synchronized (GeminiLessonService.this) {
                        requestInFlight = false;
                        nextRequestAtMs = Math.max(
                                nextRequestAtMs,
                                System.currentTimeMillis() + backoffMs);
                        requestQueue.addFirst(request);
                        Diagnostics.log("GEMINI_BACKOFF", "retry=" + request.retryCount
                                + " waitMs=" + backoffMs
                                + " reason=" + compactReason(message)
                                + " queued=" + requestQueue.size());
                        schedulePumpLocked(backoffMs);
                    }
                    return;
                }

                finishError(request, "Gemini: " + message);
            }
        }, queueExecutor);
    }

    private int queuedCount() {
        synchronized (this) {
            return requestQueue.size();
        }
    }

    private void finishSuccess(PendingRequest request, Lesson lesson) {
        List<Callback> callbacks;
        synchronized (this) {
            requestInFlight = false;
            pendingByKey.remove(request.cacheKey);
            nextRequestAtMs = Math.max(
                    nextRequestAtMs,
                    System.currentTimeMillis() + MIN_REQUEST_GAP_MS);
            callbacks = new ArrayList<>(request.callbacks);
            schedulePumpLocked(MIN_REQUEST_GAP_MS);
        }
        for (Callback callback : callbacks) {
            try {
                callback.onSuccess(lesson);
            } catch (Throwable ignored) {}
        }
    }

    private void finishError(PendingRequest request, String message) {
        List<Callback> callbacks;
        synchronized (this) {
            requestInFlight = false;
            pendingByKey.remove(request.cacheKey);
            nextRequestAtMs = Math.max(
                    nextRequestAtMs,
                    System.currentTimeMillis() + MIN_REQUEST_GAP_MS);
            callbacks = new ArrayList<>(request.callbacks);
            schedulePumpLocked(MIN_REQUEST_GAP_MS);
        }
        deliverError(callbacks, message);
    }

    private void deliverError(PendingRequest request, String message) {
        deliverError(new ArrayList<>(request.callbacks), message);
    }

    private void deliverError(List<Callback> callbacks, String message) {
        for (Callback callback : callbacks) {
            try {
                callback.onError(message);
            } catch (Throwable ignored) {}
        }
    }

    private long transientBackoffMs(Throwable t, String message) {
        String className = t == null ? "" : t.getClass().getSimpleName();
        String lower = (className + " " + safe(message)).toLowerCase(Locale.US);

        if (lower.contains("quota") || lower.contains("rate limit")
                || lower.contains("too many requests") || lower.contains("429")) {
            return parseRetryMs(message);
        }
        if (lower.contains("high demand") || lower.contains("temporar")
                || lower.contains("unavailable") || lower.contains("503")) {
            return HIGH_DEMAND_COOLDOWN_MS;
        }
        if (lower.contains("unknownexception") || lower.contains("unexpected happened")) {
            return UNKNOWN_TRANSIENT_COOLDOWN_MS;
        }
        return 0L;
    }

    private long parseRetryMs(String message) {
        Matcher matcher = RETRY_SECONDS.matcher(safe(message));
        if (matcher.find()) {
            try {
                double seconds = Double.parseDouble(matcher.group(1));
                long parsed = (long) Math.ceil(seconds * 1000.0) + 5_000L;
                return Math.max(DEFAULT_QUOTA_COOLDOWN_MS, Math.min(parsed, 120_000L));
            } catch (Throwable ignored) {}
        }
        return DEFAULT_QUOTA_COOLDOWN_MS;
    }

    private String compactReason(String message) {
        String clean = safe(message).replaceAll("\\s+", " ");
        return clean.length() <= 100 ? clean : clean.substring(0, 100) + "…";
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

    private String cacheKey(String currentLine, List<String> previousLines) {
        StringBuilder raw = new StringBuilder();
        raw.append("v2|").append(MODEL).append('|').append(safe(currentLine).toLowerCase(Locale.US));
        if (previousLines != null) {
            for (String line : previousLines) {
                raw.append('|').append(safe(line).toLowerCase(Locale.US));
            }
        }
        return "lesson_v2_" + Integer.toHexString(raw.toString().hashCode());
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

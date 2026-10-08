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
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final int MAX_BATCH_ITEMS = 4;

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

    public static final class LessonInput {
        public final String currentLine;
        public final List<String> previousLines;

        public LessonInput(String currentLine, List<String> previousLines) {
            this.currentLine = safe(currentLine);
            this.previousLines = previousLines == null
                    ? new ArrayList<>()
                    : new ArrayList<>(previousLines);
        }
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
        final List<LessonInput> inputs;
        final List<String> itemCacheKeys;
        final String requestKey;
        final List<Callback> callbacks = new ArrayList<>();
        int retryCount = 0;

        PendingRequest(List<LessonInput> inputs,
                       List<String> itemCacheKeys,
                       String requestKey,
                       Callback callback) {
            this.inputs = inputs;
            this.itemCacheKeys = itemCacheKeys;
            this.requestKey = requestKey;
            if (callback != null) this.callbacks.add(callback);
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
        List<LessonInput> single = new ArrayList<>();
        single.add(new LessonInput(currentLine, previousLines));
        analyzeBatch(single, callback);
    }

    public void analyzeBatch(List<LessonInput> requestedInputs, Callback callback) {
        if (requestedInputs == null || requestedInputs.isEmpty()) {
            if (callback != null) callback.onError("هیچ دیالوگی برای Gemini آماده نشده است.");
            return;
        }
        if (model == null) {
            Diagnostics.log("GEMINI", "analyze blocked: model not configured");
            if (callback != null) callback.onError("Gemini هنوز تنظیم نشده است.");
            return;
        }

        List<LessonInput> limited = new ArrayList<>();
        for (LessonInput input : requestedInputs) {
            if (input == null || safe(input.currentLine).isEmpty()) continue;
            limited.add(new LessonInput(input.currentLine, input.previousLines));
            if (limited.size() >= MAX_BATCH_ITEMS) break;
        }
        if (limited.isEmpty()) {
            if (callback != null) callback.onError("دیالوگ خالی است.");
            return;
        }

        LessonInput primary = limited.get(0);
        Lesson primaryCached = getCached(primary.currentLine, primary.previousLines);

        List<LessonInput> missing = new ArrayList<>();
        List<String> missingKeys = new ArrayList<>();
        for (LessonInput input : limited) {
            String key = cacheKey(input.currentLine, input.previousLines);
            if (cache.contains(key)) continue;
            missing.add(input);
            missingKeys.add(key);
        }

        if (primaryCached != null && callback != null) {
            callback.onSuccess(primaryCached);
            callback = null; // remaining missing items are only prefetched.
        }
        if (missing.isEmpty()) return;

        String requestKey = batchRequestKey(missingKeys);
        synchronized (this) {
            PendingRequest existing = pendingByKey.get(requestKey);
            if (existing != null) {
                if (callback != null) existing.callbacks.add(callback);
                Diagnostics.log("GEMINI_QUEUE", "batch dedupe key=" + requestKey
                        + " callbacks=" + existing.callbacks.size());
                return;
            }

            PendingRequest request =
                    new PendingRequest(missing, missingKeys, requestKey, callback);
            requestQueue.addLast(request);
            pendingByKey.put(requestKey, request);
            Diagnostics.log("GEMINI_BATCH", "queued items=" + missing.size()
                    + " queueSize=" + requestQueue.size()
                    + " model=" + MODEL);
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
                    pendingByKey.remove(request.requestKey);
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
        int totalChars = 0;
        for (LessonInput input : request.inputs) totalChars += input.currentLine.length();
        Diagnostics.log("GEMINI_REQ", "begin batchItems=" + request.inputs.size()
                + " totalChars=" + totalChars
                + " retry=" + request.retryCount
                + " queuedRemaining=" + queuedCount());

        String promptText = buildBatchPrompt(request.inputs);
        Content prompt = new Content.Builder().addText(promptText).build();
        ListenableFuture<GenerateContentResponse> future = localModel.generateContent(prompt);

        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse result) {
                try {
                    String text = result == null ? null : result.getText();
                    List<Lesson> lessons = parseBatchLessons(text, request.inputs.size());
                    SharedPreferences.Editor editor = cache.edit();
                    for (int i = 0; i < lessons.size() && i < request.itemCacheKeys.size(); i++) {
                        String encoded = lessons.get(i).toJson();
                        if (!encoded.isEmpty()) {
                            editor.putString(request.itemCacheKeys.get(i), encoded);
                        }
                    }
                    editor.apply();

                    Lesson primaryLesson = lessons.isEmpty() ? null : lessons.get(0);
                    Diagnostics.log("GEMINI_RES", "success batchItems=" + lessons.size()
                            + " responseChars=" + (text == null ? 0 : text.length())
                            + " model=" + MODEL);
                    finishSuccess(request, primaryLesson);
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
            pendingByKey.remove(request.requestKey);
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
            pendingByKey.remove(request.requestKey);
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

    private String buildBatchPrompt(List<LessonInput> inputs) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            LessonInput input = inputs.get(i);
            items.append("\nITEM ").append(i).append("\nPrevious dialogue:\n");
            if (input.previousLines.isEmpty()) {
                items.append("(none)\n");
            } else {
                for (String line : input.previousLines) {
                    if (!safe(line).isEmpty()) items.append("- ").append(safe(line)).append("\n");
                }
            }
            items.append("CURRENT DIALOGUE:\n").append(input.currentLine).append("\n");
        }

        return "You are the teaching engine inside a personal English-learning movie player.\n"
                + "The learner is Persian-speaking. Analyze EACH numbered item independently, using only its previous dialogue as context.\n"
                + "Do not invent visual events. Keep each explanation concise and useful.\n"
                + "Return ONLY one valid JSON object, no markdown and no code fences.\n"
                + "All explanations except pronunciation examples should be in Persian.\n"
                + "Return exactly one lesson for every input item, in the SAME ORDER.\n"
                + "Schema:\n"
                + "{\"lessons\":[{"
                + "\"id\":0,"
                + "\"translationFa\":\"natural Persian translation\","
                + "\"naturalMeaningFa\":\"contextual meaning\","
                + "\"grammar\":\"short grammar note or empty string\","
                + "\"idioms\":\"idiom/phrasal verb/slang note or empty string\","
                + "\"pronunciation\":\"practical pronunciation hint using English examples\","
                + "\"connectedSpeech\":\"connected speech/reduction note or empty string\","
                + "\"contextNote\":\"brief context note without visual guesses\","
                + "\"shouldPause\":true,"
                + "\"teachingScore\":0.0"
                + "}]}\n"
                + "teachingScore must be between 0 and 1.\n"
                + items;
    }

    private List<Lesson> parseBatchLessons(String raw, int expectedCount) throws Exception {
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

        JSONObject root = new JSONObject(clean.substring(a, b + 1));
        JSONArray lessonsArray = root.optJSONArray("lessons");
        if (lessonsArray == null) throw new IllegalArgumentException("آرایه lessons پیدا نشد");
        if (lessonsArray.length() < expectedCount) {
            throw new IllegalArgumentException("تعداد درس‌های Gemini کمتر از Batch بود");
        }

        Lesson[] ordered = new Lesson[expectedCount];
        for (int i = 0; i < lessonsArray.length(); i++) {
            JSONObject o = lessonsArray.optJSONObject(i);
            if (o == null) continue;
            int id = o.optInt("id", i);
            if (id < 0 || id >= expectedCount) continue;
            ordered[id] = parseLessonObject(o);
        }

        List<Lesson> out = new ArrayList<>();
        for (int i = 0; i < expectedCount; i++) {
            if (ordered[i] == null) throw new IllegalArgumentException("درس شماره " + i + " در پاسخ نبود");
            out.add(ordered[i]);
        }
        return out;
    }

    private Lesson parseLessonObject(JSONObject o) {
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

    private String batchRequestKey(List<String> itemKeys) {
        StringBuilder raw = new StringBuilder("batch|").append(MODEL);
        for (String key : itemKeys) raw.append('|').append(key);
        return "batch_" + Integer.toHexString(raw.toString().hashCode());
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

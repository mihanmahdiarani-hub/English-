package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * English AI Tutor v2 alpha-10.
 * Local video -> local audio decode -> local whisper.cpp -> timestamped dialogues ->
 * Firebase AI Logic / Gemini Flash-Lite batched lesson -> cached teaching card -> Media3 pause/learn loop.
 * Video/audio stay local. Only compact transcript context is sent to Gemini when enabled.
 */
public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 2001;
    private static final int PICK_FIREBASE_CONFIG = 2002;
    private static final int PICK_WHISPER_MODEL = 2003;
    private static final int MAX_FIREBASE_CONFIG_BYTES = 512 * 1024;
    private static final long TICK_MS = 50L;

    private enum Mode { SMART, AUTO, WATCH }

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView statusView;
    private TextView dialogueView;
    private TextView translationView;
    private TextView lessonView;
    private Button prepareButton;
    private Button geminiButton;
    private Button diagnosticsButton;
    private Button appCheckButton;
    private Button continueButton;
    private Button replayButton;
    private Button slowReplayButton;
    private Button smartButton;
    private Button autoButton;
    private Button watchButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Dialogue> dialogues = new ArrayList<>();

    private Uri selectedVideoUri;
    private Mode mode = Mode.SMART;
    private int activeDialogueIndex = -1;
    private int lessonDialogueIndex = -1;
    private int lastPausedIndex = -1;
    private long replayStopAtMs = -1L;
    private boolean replaySlow = false;
    private boolean preparing = false;

    private Translator translator;
    private boolean translatorReady = false;
    private GeminiLessonService geminiLessonService;
    private static final String AI_PREFS = "english_tutor_ai_config";
    private static final String AI_API_KEY = "firebase_api_key";
    private static final String AI_APP_ID = "firebase_app_id";
    private static final String AI_PROJECT_ID = "firebase_project_id";

    private final Runnable playbackTick = new Runnable() {
        @Override public void run() {
            try { syncDialogueWithPlayer(); }
            finally { handler.postDelayed(this, TICK_MS); }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.init(this);
        buildUi();
        initTranslator();
        initGemini();

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                          Player.PositionInfo newPosition,
                                                          int reason) {
                activeDialogueIndex = findDialogueForPosition(newPosition.positionMs);
                if (lastPausedIndex >= 0 && lastPausedIndex < dialogues.size()
                        && newPosition.positionMs < dialogues.get(lastPausedIndex).startMs) {
                    lastPausedIndex = -1;
                }
            }
        });
        selectMode(Mode.SMART);
        handler.post(playbackTick);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView title = new TextView(this);
        title.setText("English AI Tutor v2 — Local + Gemini");
        title.setTextSize(19f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView privacy = new TextView(this);
        privacy.setText("ویدئو و صدا محلی می‌مانند • فقط متن کوتاه دیالوگ برای درس به Gemini می‌رود");
        privacy.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(privacy, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        Button videoButton = new Button(this);
        videoButton.setText("🎬 انتخاب کلیپ");
        prepareButton = new Button(this);
        prepareButton.setText("⚙ آماده‌سازی خودکار");
        prepareButton.setEnabled(false);
        sourceRow.addView(videoButton, new LinearLayout.LayoutParams(0, -2, 1f));
        sourceRow.addView(prepareButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(sourceRow);

        LinearLayout serviceRow = new LinearLayout(this);
        serviceRow.setOrientation(LinearLayout.HORIZONTAL);
        geminiButton = new Button(this);
        geminiButton.setText("✨ اتصال Gemini");
        diagnosticsButton = new Button(this);
        diagnosticsButton.setText("🧾 لاگ");
        appCheckButton = new Button(this);
        appCheckButton.setText("🛡 App Check");
        serviceRow.addView(geminiButton, new LinearLayout.LayoutParams(0, -2, 1f));
        serviceRow.addView(appCheckButton, new LinearLayout.LayoutParams(0, -2, 0.75f));
        serviceRow.addView(diagnosticsButton, new LinearLayout.LayoutParams(0, -2, 0.45f));
        root.addView(serviceRow);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        root.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        smartButton = new Button(this);
        autoButton = new Button(this);
        watchButton = new Button(this);
        smartButton.setText("Smart");
        autoButton.setText("Auto");
        watchButton.setText("Watch");
        modes.addView(smartButton, new LinearLayout.LayoutParams(0, -2, 1f));
        modes.addView(autoButton, new LinearLayout.LayoutParams(0, -2, 1f));
        modes.addView(watchButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(modes);

        ScrollView lessonScroll = new ScrollView(this);
        LinearLayout lessonBox = new LinearLayout(this);
        lessonBox.setOrientation(LinearLayout.VERTICAL);
        lessonBox.setPadding(dp(12), dp(8), dp(12), dp(8));

        dialogueView = new TextView(this);
        dialogueView.setTextSize(18f);
        dialogueView.setText("بعد از آماده‌سازی، متن انگلیسی اینجا ظاهر می‌شود.");
        lessonBox.addView(dialogueView);

        translationView = new TextView(this);
        translationView.setTextSize(17f);
        translationView.setTextDirection(View.TEXT_DIRECTION_RTL);
        translationView.setText("ترجمه فارسی روی خود گوشی انجام می‌شود.");
        lessonBox.addView(translationView);

        lessonView = new TextView(this);
        lessonView.setTextSize(14f);
        lessonView.setText("Whisper محلی Timestamp را می‌سازد؛ Gemini می‌تواند ترجمه طبیعی، اصطلاح، گرامر و تلفظ را درس بدهد.");
        lessonBox.addView(lessonView);

        LinearLayout lessonActions = new LinearLayout(this);
        lessonActions.setOrientation(LinearLayout.HORIZONTAL);
        replayButton = new Button(this);
        replayButton.setText("🔁 دوباره");
        slowReplayButton = new Button(this);
        slowReplayButton.setText("🐢 آهسته");
        continueButton = new Button(this);
        continueButton.setText("▶ ادامه");
        replayButton.setVisibility(View.GONE);
        slowReplayButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);
        lessonActions.addView(replayButton, new LinearLayout.LayoutParams(0, -2, 1f));
        lessonActions.addView(slowReplayButton, new LinearLayout.LayoutParams(0, -2, 1f));
        lessonActions.addView(continueButton, new LinearLayout.LayoutParams(0, -2, 1f));
        lessonBox.addView(lessonActions);

        lessonScroll.addView(lessonBox);
        root.addView(lessonScroll, new LinearLayout.LayoutParams(-1, dp(225)));

        statusView = new TextView(this);
        statusView.setText("یک کلیپ یا فیلم از حافظه گوشی انتخاب کن.");
        statusView.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(statusView);

        setContentView(root);

        videoButton.setOnClickListener(v -> pickVideo());
        prepareButton.setOnClickListener(v -> prepareSelectedVideo());
        geminiButton.setOnClickListener(v -> {
            if (geminiLessonService != null && geminiLessonService.isConfigured()) {
                showGeminiSettings();
            } else {
                pickFirebaseConfig();
            }
        });
        diagnosticsButton.setOnClickListener(v -> showDiagnostics());
        appCheckButton.setOnClickListener(v -> showAppCheckDebugToken());
        smartButton.setOnClickListener(v -> selectMode(Mode.SMART));
        autoButton.setOnClickListener(v -> selectMode(Mode.AUTO));
        watchButton.setOnClickListener(v -> selectMode(Mode.WATCH));
        continueButton.setOnClickListener(v -> continueMovie());
        replayButton.setOnClickListener(v -> replayCurrent(false));
        slowReplayButton.setOnClickListener(v -> replayCurrent(true));
    }

    private void initGemini() {
        geminiLessonService = new GeminiLessonService(this);
        SharedPreferences prefs = getSharedPreferences(AI_PREFS, MODE_PRIVATE);
        boolean ok = geminiLessonService.configure(
                prefs.getString(AI_API_KEY, ""),
                prefs.getString(AI_APP_ID, ""),
                prefs.getString(AI_PROJECT_ID, ""));
        Diagnostics.log("FIREBASE", "startup configured=" + ok);
        updateGeminiButton(ok);
    }

    private void updateGeminiButton(boolean configured) {
        if (geminiButton != null) {
            geminiButton.setText(configured ? "✨ Gemini وصل است" : "✨ اتصال Gemini");
        }
    }

    private void pickFirebaseConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/json", "text/plain"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_FIREBASE_CONFIG);
        statusView.setText("فایل google-services.json همین Firebase را انتخاب کن.");
    }

    private void importFirebaseConfig(Uri uri) {
        try {
            String raw = readSmallTextFile(uri, MAX_FIREBASE_CONFIG_BYTES);
            String trimmed = raw.trim();
            if (!trimmed.startsWith("{")) {
                throw new IllegalArgumentException("فایل انتخاب‌شده JSON نیست. فقط google-services.json را انتخاب کن");
            }

            JSONObject root = new JSONObject(trimmed);
            String projectId = root.getJSONObject("project_info").optString("project_id", "").trim();
            JSONArray clients = root.optJSONArray("client");
            if (clients == null || clients.length() == 0) throw new IllegalArgumentException("client پیدا نشد");

            String appId = "";
            String apiKey = "";
            boolean packageFound = false;
            for (int i = 0; i < clients.length(); i++) {
                JSONObject client = clients.optJSONObject(i);
                if (client == null) continue;
                JSONObject info = client.optJSONObject("client_info");
                if (info == null) continue;
                JSONObject android = info.optJSONObject("android_client_info");
                String packageName = android == null ? "" : android.optString("package_name", "");
                if (!getPackageName().equals(packageName)) continue;

                packageFound = true;
                appId = info.optString("mobilesdk_app_id", "").trim();
                JSONArray keys = client.optJSONArray("api_key");
                if (keys != null && keys.length() > 0 && keys.optJSONObject(0) != null) {
                    apiKey = keys.optJSONObject(0).optString("current_key", "").trim();
                }
                break;
            }

            if (!packageFound) {
                throw new IllegalArgumentException("این فایل برای package دیگری است، نه " + getPackageName());
            }
            if (projectId.isEmpty() || appId.isEmpty() || apiKey.isEmpty()) {
                throw new IllegalArgumentException("Project ID / App ID / API Key کامل نیست");
            }

            SharedPreferences prefs = getSharedPreferences(AI_PREFS, MODE_PRIVATE);
            prefs.edit()
                    .putString(AI_API_KEY, apiKey)
                    .putString(AI_APP_ID, appId)
                    .putString(AI_PROJECT_ID, projectId)
                    .apply();

            boolean ok = geminiLessonService.configure(apiKey, appId, projectId);
            Diagnostics.log("FIREBASE", "google-services import configured=" + ok + " packageOk=true");
            updateGeminiButton(ok);
            statusView.setText(ok
                    ? "✅ Firebase خوانده شد و Gemini آماده است. حالا کلیپ را آماده کن."
                    : "فایل خوانده شد، ولی اتصال Gemini هنوز آماده نشد. اپ را یک بار ببند و باز کن.");
        } catch (Throwable t) {
            Diagnostics.error("FIREBASE_IMPORT", t);
            statusView.setText("خواندن google-services.json ناموفق بود: " + safeMessage(t));
        }
    }

    private String readSmallTextFile(Uri uri, int maxBytes) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalArgumentException("فایل قابل خواندن نیست");
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                total += n;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("فایل انتخاب‌شده خیلی بزرگ است؛ google-services.json باید فایل کوچک JSON باشد");
                }
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void pickWhisperModel() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_WHISPER_MODEL);
        statusView.setText("فایل ggml-tiny.en.bin را انتخاب کن.");
    }

    private void importWhisperModel(Uri uri) {
        preparing = true;
        prepareButton.setEnabled(false);
        statusView.setText("در حال وارد کردن مدل Whisper از حافظه گوشی...");
        Diagnostics.log("WHISPER_MODEL", "manual import start");

        ModelManager.importModel(this, uri, new ModelManager.Listener() {
            @Override public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                statusView.setText(String.format(Locale.US,
                        "در حال وارد کردن مدل Whisper: %.1f MB",
                        downloadedBytes / (1024.0 * 1024.0)));
            }

            @Override public void onReady(File modelFile) {
                Diagnostics.log("WHISPER_MODEL", "manual import ready bytes=" + modelFile.length());
                if (selectedVideoUri != null) {
                    extractAndTranscribe(modelFile);
                } else {
                    preparing = false;
                    prepareButton.setEnabled(false);
                    statusView.setText("✅ مدل Whisper آماده است. حالا یک کلیپ انتخاب کن.");
                }
            }

            @Override public void onError(String message) {
                preparing = false;
                prepareButton.setEnabled(selectedVideoUri != null);
                statusView.setText("وارد کردن مدل Whisper ناموفق بود: " + message);
            }
        });
    }

    private void showWhisperDownloadError(String message) {
        new AlertDialog.Builder(this)
                .setTitle("مدل Whisper دانلود نشد")
                .setMessage(message + "\n\nاگر اینترنت یا DNS به Hugging Face دسترسی ندارد، می‌توانی فایل ggml-tiny.en.bin را دستی انتخاب کنی.")
                .setNegativeButton("بعداً", null)
                .setNeutralButton("انتخاب فایل مدل", (dialog, which) -> pickWhisperModel())
                .setPositiveButton("تلاش دوباره", (dialog, which) -> prepareSelectedVideo())
                .show();
    }

    private void showAppCheckDebugToken() {
        String secret = geminiLessonService == null ? "" : geminiLessonService.getAppCheckDebugSecret();
        if (secret == null || secret.trim().isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("App Check")
                    .setMessage("اول Firebase/Gemini را با google-services.json وصل کن؛ بعد توکن App Check ساخته می‌شود.")
                    .setPositiveButton("باشه", null)
                    .show();
            return;
        }

        EditText token = new EditText(this);
        token.setText(secret);
        token.setSingleLine(true);
        token.setTextIsSelectable(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        token.setPadding(dp(16), dp(8), dp(16), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("App Check Debug Token")
                .setMessage("این توکن را در Firebase Console > Security > App Check > Apps > english > Manage debug tokens ثبت کن. توکن را اینجا در چت نفرست.")
                .setView(token)
                .setNegativeButton("بستن", null)
                .setPositiveButton("کپی توکن", (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("App Check debug token", secret));
                        Toast.makeText(this, "توکن App Check کپی شد", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showDiagnostics() {
        String logs = Diagnostics.dump(this);
        TextView text = new TextView(this);
        text.setText(logs);
        text.setTextSize(12f);
        text.setTextIsSelectable(true);
        text.setPadding(dp(12), dp(8), dp(12), dp(8));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("لاگ English AI Tutor")
                .setView(scroll)
                .setNegativeButton("بستن", null)
                .setNeutralButton("پاک کردن", null)
                .setPositiveButton("اشتراک", null)
                .create();

        dialog.setOnShowListener(unused -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                Diagnostics.clear(this);
                text.setText(Diagnostics.dump(this));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String dump = Diagnostics.dump(this);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "English AI Tutor diagnostics");
                send.putExtra(Intent.EXTRA_TEXT, dump);
                startActivity(Intent.createChooser(send, "ارسال لاگ"));
            });
        });
        dialog.show();
    }

    private void showGeminiSettings() {
        SharedPreferences prefs = getSharedPreferences(AI_PREFS, MODE_PRIVATE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView help = new TextView(this);
        help.setText("Gemini وصل شده. برای تغییر پروژه می‌توانی مقادیر را دستی عوض کنی یا Cancel کرده و از صفحه اصلی دوباره فایل google-services.json را انتخاب کنی.");
        box.addView(help);

        EditText apiKey = new EditText(this);
        apiKey.setHint("Firebase Web API Key");
        apiKey.setSingleLine(true);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        apiKey.setText(prefs.getString(AI_API_KEY, ""));
        box.addView(apiKey);

        EditText appId = new EditText(this);
        appId.setHint("Firebase App ID  (1:...:android:...)");
        appId.setSingleLine(true);
        appId.setText(prefs.getString(AI_APP_ID, ""));
        box.addView(appId);

        EditText projectId = new EditText(this);
        projectId.setHint("Firebase Project ID");
        projectId.setSingleLine(true);
        projectId.setText(prefs.getString(AI_PROJECT_ID, ""));
        box.addView(projectId);

        new AlertDialog.Builder(this)
                .setTitle("اتصال Gemini با Firebase AI Logic")
                .setView(box)
                .setNegativeButton("لغو", null)
                .setPositiveButton("ذخیره و اتصال", (dialog, which) -> {
                    String k = apiKey.getText().toString().trim();
                    String a = appId.getText().toString().trim();
                    String p = projectId.getText().toString().trim();
                    prefs.edit()
                            .putString(AI_API_KEY, k)
                            .putString(AI_APP_ID, a)
                            .putString(AI_PROJECT_ID, p)
                            .apply();
                    boolean ok = geminiLessonService.configure(k, a, p);
                    updateGeminiButton(ok);
                    statusView.setText(ok
                            ? "Gemini آماده است. فقط متن دیالوگ و چند خط قبل برای ساخت درس ارسال می‌شود."
                            : "تنظیم Firebase کامل نیست یا نیاز به یک بار بستن/باز کردن اپ دارد.");
                })
                .show();
    }

    private void initTranslator() {
        String en = TranslateLanguage.fromLanguageTag("en");
        String fa = TranslateLanguage.fromLanguageTag("fa");
        if (en == null || fa == null) return;
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(en)
                .setTargetLanguage(fa)
                .build();
        translator = Translation.getClient(options);
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> translatorReady = true)
                .addOnFailureListener(e -> translatorReady = false);
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        if (requestCode == PICK_FIREBASE_CONFIG) {
            importFirebaseConfig(uri);
            return;
        }
        if (requestCode == PICK_WHISPER_MODEL) {
            importWhisperModel(uri);
            return;
        }
        if (requestCode != PICK_VIDEO) return;

        try {
            int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Throwable ignored) {}

        selectedVideoUri = uri;
        Diagnostics.log("MEDIA", "video selected uriScheme=" + (uri.getScheme() == null ? "?" : uri.getScheme()));
        dialogues.clear();
        activeDialogueIndex = -1;
        lessonDialogueIndex = -1;
        lastPausedIndex = -1;
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.pause();
        dialogueView.setText("کلیپ انتخاب شد.");
        translationView.setText("برای ساخت خودکار دیالوگ‌ها «آماده‌سازی خودکار» را بزن.");
        lessonView.setText("اولین بار مدل رایگان Whisper حدود 75MB دانلود می‌شود؛ بعد از آن محلی می‌ماند. اگر Gemini تنظیم باشد، بعد از Transcript درس هوشمند هم ساخته می‌شود.");
        prepareButton.setEnabled(true);
        statusView.setText("آماده برای پردازش محلی؛ هیچ فایل ویدئو یا صوتی آپلود نمی‌شود.");
    }

    private void prepareSelectedVideo() {
        if (selectedVideoUri == null || preparing) return;
        preparing = true;
        Diagnostics.log("PIPELINE", "prepare start modelReady=" + ModelManager.isReady(this));
        prepareButton.setEnabled(false);
        player.pause();
        statusView.setText(ModelManager.isReady(this)
                ? "مدل Whisper موجود است. در حال آماده‌سازی..."
                : "آماده‌سازی یک‌باره مدل Whisper داخلی (~75MB)...");

        ModelManager.ensureModel(this, new ModelManager.Listener() {
            @Override public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                if (percent >= 0) statusView.setText("دانلود مدل محلی: " + percent + "%");
                else statusView.setText("در حال آماده‌سازی مدل Whisper...");
            }

            @Override public void onReady(File modelFile) {
                Diagnostics.log("WHISPER_MODEL", "ready bytes=" + modelFile.length());
                extractAndTranscribe(modelFile);
            }

            @Override public void onError(String message) {
                Diagnostics.log("WHISPER_MODEL", "ERROR " + message);
                preparing = false;
                prepareButton.setEnabled(true);
                statusView.setText("دانلود مدل شکست خورد: " + message);
                showWhisperDownloadError(message);
            }
        });
    }

    private void extractAndTranscribe(File modelFile) {
        Uri uri = selectedVideoUri;
        if (uri == null) return;
        statusView.setText("در حال استخراج صدای کلیپ روی گوشی...");
        worker.execute(() -> {
            File wav = new File(getCacheDir(), "english_tutor_whisper_input.wav");
            try {
                VideoAudioExtractor.extract(this, uri, wav, (percent, stage) ->
                        runOnUiThread(() -> statusView.setText(stage + ": " + percent + "%")));
                Diagnostics.log("AUDIO", "wav ready bytes=" + wav.length());
                runOnUiThread(() -> statusView.setText("Whisper روی گوشی در حال ساخت دیالوگ‌هاست..."));
                WhisperBridge.transcribe(this, modelFile.getAbsolutePath(), wav.getAbsolutePath(),
                        new WhisperBridge.Callback() {
                            @Override public void onSuccess(List<WhisperBridge.Segment> segments, long processingTimeMs) {
                                Diagnostics.log("WHISPER", "success segments=" + segments.size()
                                        + " processingMs=" + processingTimeMs);
                                wav.delete();
                                applyTranscript(segments, processingTimeMs);
                            }

                            @Override public void onError(String message) {
                                Diagnostics.log("WHISPER", "ERROR " + message);
                                wav.delete();
                                preparing = false;
                                prepareButton.setEnabled(true);
                                statusView.setText("تبدیل صدا به متن شکست خورد: " + message);
                            }
                        });
            } catch (Throwable t) {
                Diagnostics.error("PIPELINE", t);
                wav.delete();
                runOnUiThread(() -> {
                    preparing = false;
                    prepareButton.setEnabled(true);
                    statusView.setText("آماده‌سازی شکست خورد: " + safeMessage(t));
                });
            }
        });
    }

    private void applyTranscript(List<WhisperBridge.Segment> segments, long processingTimeMs) {
        dialogues.clear();
        for (WhisperBridge.Segment s : segments) {
            String text = s.getText() == null ? "" : s.getText().replaceAll("\\s+", " ").trim();
            if (text.isEmpty() || s.getEndMs() <= s.getStartMs()) continue;
            dialogues.add(new Dialogue(s.getStartMs(), s.getEndMs(), text));
        }
        preparing = false;
        prepareButton.setEnabled(true);
        lastPausedIndex = -1;
        activeDialogueIndex = -1;
        if (dialogues.isEmpty()) {
            Diagnostics.log("TRANSCRIPT", "empty after filtering");
            statusView.setText("Whisper دیالوگ قابل استفاده پیدا نکرد. کلیپ دیگری را امتحان کن.");
            return;
        }
        Diagnostics.log("TRANSCRIPT", "ready dialogues=" + dialogues.size());
        statusView.setText(String.format(Locale.US,
                "%d دیالوگ آماده شد • پردازش Whisper: %.1f ثانیه%s",
                dialogues.size(), processingTimeMs / 1000.0,
                geminiLessonService != null && geminiLessonService.isConfigured()
                        ? " • Gemini آماده درس دادن است"
                        : " • Gemini هنوز تنظیم نشده"));
        dialogueView.setText(dialogues.get(0).text);
        translationView.setText("▶ فیلم را پخش کن؛ در توقف ترجمه فارسی نمایش داده می‌شود.");
        lessonView.setText("Mode فعلی: " + mode + " • Timestampها مستقیماً از صدای همین کلیپ ساخته شده‌اند.");
        player.seekTo(0L);
        player.play();
    }

    private void selectMode(Mode newMode) {
        mode = newMode;
        smartButton.setEnabled(newMode != Mode.SMART);
        autoButton.setEnabled(newMode != Mode.AUTO);
        watchButton.setEnabled(newMode != Mode.WATCH);
        if (!preparing) {
            statusView.setText(newMode == Mode.SMART
                    ? "Smart: روی دیالوگ‌های آموزشی‌تر توقف می‌کند."
                    : newMode == Mode.AUTO
                    ? "Auto: تقریباً بعد از هر دیالوگ توقف می‌کند."
                    : "Watch: فیلم بدون توقف خودکار پخش می‌شود.");
        }
    }

    private void syncDialogueWithPlayer() {
        if (player == null || dialogues.isEmpty()) return;
        long position = player.getCurrentPosition();

        if (replayStopAtMs >= 0L && player.isPlaying() && position >= replayStopAtMs) {
            player.pause();
            replayStopAtMs = -1L;
            if (replaySlow) player.setPlaybackSpeed(1.0f);
            replaySlow = false;
            return;
        }

        int index = findDialogueForPosition(position);
        if (index >= 0 && index != activeDialogueIndex) {
            activeDialogueIndex = index;
            dialogueView.setText(dialogues.get(index).text);
        }

        if (replayStopAtMs >= 0L) return;
        if (!player.isPlaying() || mode == Mode.WATCH || index < 0 || index == lastPausedIndex) return;
        Dialogue d = dialogues.get(index);
        long pauseAt = d.endMs + 100L;
        if (position >= pauseAt && position <= pauseAt + 450L && shouldTeach(d)) {
            lastPausedIndex = index;
            Diagnostics.log("PAUSE", "dialogue=" + index + " startMs=" + d.startMs + " endMs=" + d.endMs);
            player.pause();
            showTeachingUnit(d, index);
        }
    }

    private boolean shouldTeach(Dialogue d) {
        if (mode == Mode.AUTO) return true;
        if (mode == Mode.WATCH) return false;
        String text = d.text == null ? "" : d.text.trim().toLowerCase(Locale.US);
        if (text.isEmpty()) return false;
        int words = text.split("\\s+").length;
        if (words >= 5 || text.contains("?") || text.contains("!")) return true;
        String[] useful = {"got to", "have to", "used to", "supposed to", "might as well",
                "gonna", "wanna", "kind of", "sort of", "figure out", "come on", "you know", "i mean"};
        for (String p : useful) if (text.contains(p)) return true;
        return false;
    }

    private void showTeachingUnit(Dialogue d, int index) {
        lessonDialogueIndex = index;
        dialogueView.setText(d.text);
        replayButton.setVisibility(View.VISIBLE);
        slowReplayButton.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);
        continueButton.setEnabled(true);

        if (geminiLessonService != null && geminiLessonService.isConfigured()) {
            List<GeminiLessonService.LessonInput> batch = buildGeminiBatch(index, 4);
            Diagnostics.log("LESSON", "Gemini batch path dialogue=" + index
                    + " items=" + batch.size() + " chars=" + d.text.length());

            continueButton.setEnabled(false);
            translationView.setText("✨ Gemini Flash-Lite در حال آماده‌سازی درس...");
            lessonView.setText("⏱ " + formatMs(d.startMs) + " → " + formatMs(d.endMs)
                    + "\nتا ۴ دیالوگ در یک درخواست تحلیل و Cache می‌شوند تا مصرف سهمیه کمتر شود."
                    + "\nتا آماده شدن درس، ادامه موقتاً غیرفعال است تا درخواست تکراری ساخته نشود."
                    + "\nفقط متن دیالوگ‌ها و زمینه کوتاه متنی ارسال می‌شود؛ فیلم و صوت ارسال نمی‌شود.");

            geminiLessonService.analyzeBatch(batch,
                    new GeminiLessonService.Callback() {
                        @Override public void onSuccess(GeminiLessonService.Lesson lesson) {
                            runOnUiThread(() -> {
                                if (lessonDialogueIndex != index) return;
                                continueButton.setEnabled(true);
                                showGeminiLesson(lesson, index);
                            });
                        }

                        @Override public void onError(String message) {
                            Diagnostics.log("LESSON", "Gemini ERROR dialogue=" + index + " " + message);
                            runOnUiThread(() -> {
                                if (lessonDialogueIndex != index) return;
                                continueButton.setEnabled(true);
                                lessonView.setText("Gemini در دسترس نبود: " + message
                                        + "\nترجمه محلی به‌عنوان جایگزین استفاده می‌شود.");
                                translateDialogue(d.text, index);
                            });
                        }
                    });
            return;
        }

        Diagnostics.log("LESSON", "local translation fallback dialogue=" + index);
        translationView.setText("در حال ترجمه فارسی روی گوشی...");
        lessonView.setText("⏱ " + formatMs(d.startMs) + " → " + formatMs(d.endMs)
                + "\nGemini تنظیم نشده؛ فعلاً ترجمه محلی نمایش داده می‌شود."
                + "\n🎧 برای شنیدن تلفظ واقعی بازیگر «دوباره» یا «آهسته» را بزن.");
        translateDialogue(d.text, index);
    }

    private List<GeminiLessonService.LessonInput> buildGeminiBatch(int startIndex, int maxItems) {
        List<GeminiLessonService.LessonInput> out = new ArrayList<>();
        if (startIndex < 0 || startIndex >= dialogues.size()) return out;

        int limit = Math.max(1, Math.min(4, maxItems));
        for (int i = startIndex; i < dialogues.size() && out.size() < limit; i++) {
            Dialogue candidate = dialogues.get(i);
            if (i != startIndex && mode == Mode.SMART && !shouldTeach(candidate)) continue;
            out.add(new GeminiLessonService.LessonInput(
                    candidate.text,
                    previousDialogueText(i, 3)
            ));
        }
        return out;
    }

    private List<String> previousDialogueText(int index, int count) {
        List<String> out = new ArrayList<>();
        int start = Math.max(0, index - Math.max(0, count));
        for (int i = start; i < index; i++) out.add(dialogues.get(i).text);
        return out;
    }

    private void showGeminiLesson(GeminiLessonService.Lesson lesson, int dialogueIndex) {
        if (lessonDialogueIndex != dialogueIndex) return;

        String translation = lesson.translationFa == null ? "" : lesson.translationFa.trim();
        String natural = lesson.naturalMeaningFa == null ? "" : lesson.naturalMeaningFa.trim();
        translationView.setText("🇮🇷 " + (translation.isEmpty() ? natural : translation));

        StringBuilder card = new StringBuilder();
        if (!natural.isEmpty() && !natural.equals(translation)) {
            card.append("💬 معنی طبیعی: ").append(natural).append('\n');
        }
        appendLessonLine(card, "🧩 اصطلاح", lesson.idioms);
        appendLessonLine(card, "📚 گرامر", lesson.grammar);
        appendLessonLine(card, "🗣 تلفظ", lesson.pronunciation);
        appendLessonLine(card, "🔗 Connected speech", lesson.connectedSpeech);
        appendLessonLine(card, "🎬 کاربرد در متن", lesson.contextNote);
        card.append("⭐ ارزش آموزشی: ")
                .append(String.format(Locale.US, "%.0f%%", lesson.teachingScore * 100.0));
        card.append("\n🎧 «دوباره» صدای واقعی همان بازیگر را پخش می‌کند.");
        lessonView.setText(card.toString().trim());
    }

    private void appendLessonLine(StringBuilder out, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append('\n');
        out.append(label).append(": ").append(value.trim());
    }

    private void translateDialogue(String text, int dialogueIndex) {
        if (translator == null) {
            translationView.setText("مدل ترجمه فارسی روی این دستگاه در دسترس نیست.");
            return;
        }
        if (!translatorReady) {
            DownloadConditions conditions = new DownloadConditions.Builder().build();
            translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener(unused -> {
                        translatorReady = true;
                        translateDialogue(text, dialogueIndex);
                    })
                    .addOnFailureListener(e -> translationView.setText("دانلود مدل ترجمه شکست خورد: " + safeMessage(e)));
            return;
        }
        translator.translate(text)
                .addOnSuccessListener(result -> {
                    if (lessonDialogueIndex == dialogueIndex) translationView.setText("🇮🇷 " + result);
                })
                .addOnFailureListener(e -> {
                    if (lessonDialogueIndex == dialogueIndex) translationView.setText("ترجمه ناموفق بود: " + safeMessage(e));
                });
    }

    private void replayCurrent(boolean slow) {
        if (lessonDialogueIndex < 0 || lessonDialogueIndex >= dialogues.size()) return;
        Dialogue d = dialogues.get(lessonDialogueIndex);
        player.setPlaybackSpeed(slow ? 0.72f : 1.0f);
        replaySlow = slow;
        replayStopAtMs = d.endMs + 80L;
        player.seekTo(Math.max(0L, d.startMs - 80L));
        player.play();
    }

    private void continueMovie() {
        replayStopAtMs = -1L;
        replaySlow = false;
        player.setPlaybackSpeed(1.0f);
        replayButton.setVisibility(View.GONE);
        slowReplayButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);
        player.play();
    }

    private int findDialogueForPosition(long positionMs) {
        int lo = 0, hi = dialogues.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Dialogue d = dialogues.get(mid);
            if (positionMs < d.startMs) hi = mid - 1;
            else if (positionMs > d.endMs + 550L) lo = mid + 1;
            else return mid;
        }
        return -1;
    }

    private String formatMs(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        return String.format(Locale.US, "%02d:%02d", total / 60L, total % 60L);
    }

    private String safeMessage(Throwable t) {
        return t == null ? "خطای نامشخص" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(playbackTick);
        worker.shutdownNow();
        if (translator != null) translator.close();
        if (player != null) player.release();
        super.onDestroy();
    }

    private static final class Dialogue {
        final long startMs;
        final long endMs;
        final String text;
        Dialogue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }
}

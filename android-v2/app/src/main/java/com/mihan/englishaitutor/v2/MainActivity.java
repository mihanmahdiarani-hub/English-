package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
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
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.PlayerMessage;
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
 * English AI Tutor v2 alpha-22.
 * Local video -> local audio decode -> local whisper.cpp -> timestamped dialogues ->
 * Firebase AI Logic / Gemini Flash-Lite batched lesson -> cached teaching card -> Media3 pause/learn loop.
 * Video/audio stay local. Only compact transcript context is sent to Gemini when enabled.
 */
public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 2001;
    private static final int PICK_FIREBASE_CONFIG = 2002;
    private static final int PICK_WHISPER_MODEL = 2003;
    private static final int RECOGNIZE_INLINE_QUESTION = 2004;
    private static final int SAVE_EXTRACTED_TRANSCRIPT = 2005;
    private static final int MAX_FIREBASE_CONFIG_BYTES = 512 * 1024;
    // A faster main-thread tick reduces overshoot at sentence boundaries,
    // though actual renderer/Whisper precision is device/content dependent.
    private static final long TICK_MS = 10L;
    private static final String SESSION_PREFS = "english_tutor_last_movie";
    private static final String SESSION_ARCHIVE_ID = "prepared_archive_id";

    private enum Mode { SMART, AUTO, WATCH }

    private ExoPlayer player;
    private AutoUpdater updater;
    private PlayerView playerView;
    private LinearLayout auroraRoot;
    private AuroraDashboard.ViewControls mediaControls;
    private TextView statusView;
    private TextView dialogueView;
    private TextView translationView;
    private TextView lessonView;
    private TextView previousDialogueView;
    private TextView currentDialogueView;
    private TextView nextDialogueView;
    private AuroraDialogueWindow dialogueWindow;
    private Button prepareButton;
    private Button geminiButton;
    private Button diagnosticsButton;
    private Button appCheckButton;
    private Button chatButton;
    private Button vocabularyButton;
    private Button exportTextButton;
    // Snapshot taken when the Android document picker opens. Export will
    // never inadvertently save dialogues from a newly chosen different film.
    private List<TranscriptExport.Row> pendingExportRows = new ArrayList<>();
    private TranscriptExport.Format pendingExportFormat = TranscriptExport.Format.TXT;
    private Button speakLessonButton;
    private EditText inlineQuestionInput;
    private Button inlineAskButton;
    private Button inlineMicButton;
    private Button inlineSpeakAnswerButton;
    private TextView inlineAnswerView;
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
    private Mode mode = Mode.AUTO;
    private int activeDialogueIndex = -1;
    private int lastPlayedDialogueIndex = -1;
    // A tapped transcript sentence plays as an isolated segment; it must not
    // trigger AUTO/SMART lesson pauses while that segment is playing.
    private int tappedDialoguePlaybackIndex = -1;
    // The visible lesson belongs to the exact row the learner tapped, even
    // after its video segment finishes or the learner continues playback.
    private int tappedTeacherDialogueIndex = -1;
    // Gemini's spoken explanation must wait until the actor's own clip ends.
    private int pendingTapNarrationIndex = -1;
    private boolean tappedClipFinished = false;
    private String deferredTapNarration = "";
    private int lessonDialogueIndex = -1;
    // Tracks the index ACTUALLY displayed in the tutor card, independently
    // from the active Gemini lesson. Preview cards follow playback without
    // unnecessarily requesting Gemini for each line.
    private int presentedTeacherDialogueIndex = -1;
    // Incremented for EVERY lesson request, even when the same line is tapped
    // twice; stale Gemini/translation callbacks must never overwrite the UI.
    private long lessonRequestGeneration = 0L;
    private int lastPausedIndex = -1;
    private int nextAutoPauseIndex = 0;
    private long replayStopAtMs = -1L;
    private PlayerMessage preciseDialogueStopMessage;
    private long dialogueStopGeneration = 0L;
    private boolean replaySlow = false;
    private boolean preparing = false;
    private LocalArchiveManager.Archive currentArchive;
    // A one-time archive timing migration must preserve the saved position.
    private long precisionMigrationResumeMs = -1L;
    private String aligningArchiveId = "";
    private long lastArchiveProgressSaveAtMs = 0L;

    private Translator translator;
    private boolean translatorReady = false;
    // Gemini explanations and chat replies are text-only; only movie audio plays.
    private String lastSpokenLesson = "";
    private String lastInlineAnswer = "";
    private int inlineChatDialogueIndex = -1;
    private final ArrayList<String> inlineChatHistory = new ArrayList<>();
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
        updater = new AutoUpdater(this);
        buildUi();
        initTranslator();
        initGemini();

        player = new ExoPlayer.Builder(this)
                .setSeekParameters(SeekParameters.EXACT)
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                          Player.PositionInfo newPosition,
                                                          int reason) {
                // A tap triggers its own seek. Preserve the tapped sentence as
                // highlighted instead of temporarily highlighting the previous
                // sentence, as regular manual scrubbing would do.
                if (tappedDialoguePlaybackIndex >= 0
                        && tappedDialoguePlaybackIndex < dialogues.size()) {
                    Dialogue tapped = dialogues.get(tappedDialoguePlaybackIndex);
                    long seekPosition = newPosition.positionMs;
                    if (seekPosition >= Math.max(0L, tapped.startMs - 150L)
                            && seekPosition <= tapped.endMs + 150L) {
                        activeDialogueIndex = tappedDialoguePlaybackIndex;
                        lastPlayedDialogueIndex = tappedDialoguePlaybackIndex;
                        updateLiveTranscriptContext(tappedDialoguePlaybackIndex);
                        return;
                    }
                    // User scrubbed outside the selected sentence: cancel
                    // isolated playback before resuming normal timeline mode.
                    tappedDialoguePlaybackIndex = -1;
                    replayStopAtMs = -1L;
                    replaySlow = false;
                    player.setPlaybackSpeed(1.0f);
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK
                        && lessonDialogueIndex >= 0) {
                    // A seek away from ANY lesson (tapped or AUTO) invalidates
                    // its old teacher card; selected-clip seek has returned
                    // above and will not reach this branch.
                    clearOutdatedTeacherContext("seek");
                }
                activeDialogueIndex = findDialogueForPosition(newPosition.positionMs);
                // Regular timeline seeks show only a previously heard sentence.
                lastPlayedDialogueIndex = lastCompletedDialogueAt(newPosition.positionMs);
                updateLiveTranscriptContext(lastPlayedDialogueIndex);
                if (lastPausedIndex >= 0 && lastPausedIndex < dialogues.size()
                        && newPosition.positionMs < dialogues.get(lastPausedIndex).startMs) {
                    lastPausedIndex = -1;
                }
                if (!dialogues.isEmpty()) {
                    nextAutoPauseIndex = firstDialogueEndingAfter(newPosition.positionMs);
                }
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                // Covers Media3's own Play/Pause button (which doesn't call
                // continueMovie). An old Gemini explanation cannot be kept
                // on-screen or spoken once normal video playback resumes.
                if (isPlaying && tappedDialoguePlaybackIndex < 0
                        && lessonDialogueIndex >= 0) {
                    clearOutdatedTeacherContext("player resumed");
                }
                updateLiveTranscriptContext(-1);
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && tappedDialoguePlaybackIndex >= 0) {
                    int finishedIndex = tappedDialoguePlaybackIndex;
                    replayStopAtMs = -1L;
                    replaySlow = false;
                    player.setPlaybackSpeed(1.0f);
                    finishTappedDialoguePlayback(finishedIndex);
                }
            }
        });
        selectMode(Mode.AUTO);
        handler.post(playbackTick);
        // After an app update, bring back the prepared local movie automatically;
        // otherwise the UI incorrectly returns to the pre-preparation state.
        restorePreparedSessionIfAvailable();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        auroraRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Respect Android 15+ status/navigation insets so the native header and
        // bottom teaching controls never overlap the system UI.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars());
                v.setPadding(dp(12) + bars.left, dp(12) + bars.top,
                        dp(12) + bars.right, dp(12) + bars.bottom);
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
        }

        // Aurora header: presentation controls only, no lesson or playback changes.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setTag("aurora-strip");
        header.setPadding(dp(8), dp(4), dp(6), dp(4));
        TextView mark = new TextView(this);
        mark.setText("✦");
        mark.setTag("aurora-logo");
        header.addView(mark, new LinearLayout.LayoutParams(dp(46), dp(48)));

        TextView title = new TextView(this);
        title.setText("English AI Tutor");
        title.setTag("aurora-title");
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView versionBadge = new TextView(this);
        int installedCode = 0;
        try {
            installedCode = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignored) {}
        versionBadge.setText("v" + installedCode);
        versionBadge.setTag("aurora-version");
        versionBadge.setGravity(Gravity.CENTER);
        versionBadge.setContentDescription("نسخه نصب‌شده: " + installedCode
                + "؛ برای بررسی آپدیت لمس کن");
        versionBadge.setOnClickListener(v -> {
            if (updater != null) updater.checkNow();
        });
        header.addView(versionBadge,
                new LinearLayout.LayoutParams(dp(38), dp(32)));

        Button appearanceButton = AuroraUi.appearanceButton(this, root);
        header.addView(appearanceButton, new LinearLayout.LayoutParams(dp(43), dp(46)));

        Button advancedToggle = new Button(this);
        advancedToggle.setText("⚙");
        advancedToggle.setContentDescription("تنظیمات فنی و عیب‌یابی");
        advancedToggle.setTag("aurora-appearance");
        header.addView(advancedToggle, new LinearLayout.LayoutParams(dp(43), dp(46)));
        root.addView(header);

        TextView privacy = new TextView(this);
        privacy.setText("ویدئو و صدا محلی می‌مانند • فقط متن کوتاه دیالوگ برای درس به Gemini می‌رود");
        privacy.setTextDirection(View.TEXT_DIRECTION_RTL);
        privacy.setTag("aurora-muted");
        privacy.setPadding(dp(5), dp(7), dp(5), dp(8));
        root.addView(privacy);

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceRow.setTag("aurora-card");
        sourceRow.setPadding(dp(5), dp(4), dp(5), dp(4));
        Button videoButton = new Button(this);
        videoButton.setText("🎬 انتخاب فیلم");
        videoButton.setTag("aurora-primary");
        prepareButton = new Button(this);
        prepareButton.setText("آماده‌سازی");
        prepareButton.setTag("aurora-secondary");
        prepareButton.setEnabled(false);
        sourceRow.addView(videoButton, new LinearLayout.LayoutParams(0, -2, 1f));
        sourceRow.addView(prepareButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(sourceRow);

        LinearLayout serviceRow = new LinearLayout(this);
        serviceRow.setOrientation(LinearLayout.HORIZONTAL);
        geminiButton = new Button(this);
        geminiButton.setText("✨ اتصال Gemini");
        geminiButton.setTag("aurora-secondary");
        Button directChatButton = new Button(this);
        directChatButton.setText("💬 چت Gemini");
        directChatButton.setTag("aurora-primary");
        serviceRow.addView(geminiButton, new LinearLayout.LayoutParams(0, -2, 1f));
        serviceRow.addView(directChatButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(serviceRow);

        // Existing App Check and diagnostic actions remain available, but do not
        // crowd the learning screen. The tools drawer is closed by default.
        LinearLayout advancedRow = new LinearLayout(this);
        advancedRow.setOrientation(LinearLayout.HORIZONTAL);
        advancedRow.setTag("aurora-strip");
        diagnosticsButton = new Button(this);
        diagnosticsButton.setText("🧾 لاگ");
        diagnosticsButton.setTag("aurora-tool");
        appCheckButton = new Button(this);
        appCheckButton.setText("🛡 App Check");
        appCheckButton.setTag("aurora-tool");
        advancedRow.addView(appCheckButton, new LinearLayout.LayoutParams(0, -2, 1f));
        advancedRow.addView(diagnosticsButton, new LinearLayout.LayoutParams(0, -2, 1f));
        Button checkUpdateButton = new Button(this);
        checkUpdateButton.setText("⟳ آپدیت");
        checkUpdateButton.setTag("aurora-tool");
        advancedRow.addView(checkUpdateButton, new LinearLayout.LayoutParams(0, -2, 1f));
        checkUpdateButton.setOnClickListener(v -> {
            if (updater != null) updater.checkNow();
        });
        advancedRow.setVisibility(View.GONE);
        root.addView(advancedRow);
        advancedToggle.setOnClickListener(v -> advancedRow.setVisibility(
                advancedRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        directChatButton.setOnClickListener(v -> openTutorChat());

        LinearLayout tutorRow = new LinearLayout(this);
        tutorRow.setOrientation(LinearLayout.HORIZONTAL);
        chatButton = new Button(this);
        chatButton.setText("🎓 معلم همین دیالوگ");
        chatButton.setTag("aurora-secondary");
        speakLessonButton = new Button(this);
        speakLessonButton.setText("🔊 توضیح صوتی");
        speakLessonButton.setTag("aurora-secondary");
        speakLessonButton.setEnabled(false);
        vocabularyButton = new Button(this);
        vocabularyButton.setText("📚 واژگان");
        vocabularyButton.setTag("aurora-secondary");
        vocabularyButton.setEnabled(false);
        vocabularyButton.setContentDescription("واژگان کامل دیالوگ و دسته‌بندی‌های تخصصی فیلم");
        exportTextButton = new Button(this);
        exportTextButton.setText("💾 متن");
        exportTextButton.setTag("aurora-secondary");
        exportTextButton.setEnabled(false);
        exportTextButton.setContentDescription("ذخیره متن استخراج‌شده فیلم در محل انتخابی");
        tutorRow.addView(chatButton, new LinearLayout.LayoutParams(0, -2, 1.3f));
        tutorRow.addView(vocabularyButton, new LinearLayout.LayoutParams(0, -2, 1.0f));
        tutorRow.addView(exportTextButton, new LinearLayout.LayoutParams(0, -2, 0.85f));
        playerView = new PlayerView(this);
        // No overlay play/pause, seek bar or tap-to-show controls on the video:
        // English AI Tutor's fixed bottom-right dialogue controls remain.
        playerView.setUseController(false);
        playerView.setMinimumHeight(dp(170));
        root.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setTag("aurora-strip");
        modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        smartButton = new Button(this);
        autoButton = new Button(this);
        watchButton = new Button(this);
        smartButton.setText("Smart");
        autoButton.setText("Auto");
        watchButton.setText("Watch");
        smartButton.setTag("aurora-chip");
        autoButton.setTag("aurora-chip");
        watchButton.setTag("aurora-chip");
        modes.addView(smartButton, new LinearLayout.LayoutParams(0, -2, 1f));
        modes.addView(autoButton, new LinearLayout.LayoutParams(0, -2, 1f));
        modes.addView(watchButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(modes);
        root.addView(tutorRow);

        LinearLayout transcriptContext = new LinearLayout(this);
        transcriptContext.setOrientation(LinearLayout.VERTICAL);
        transcriptContext.setTag("aurora-strip");
        transcriptContext.setPadding(dp(11), dp(8), dp(11), dp(8));

        previousDialogueView = new TextView(this);
        previousDialogueView.setTextSize(14f);
        previousDialogueView.setText("قبلی: —");
        previousDialogueView.setPadding(dp(8), dp(4), dp(8), dp(4));

        currentDialogueView = new TextView(this);
        currentDialogueView.setTextSize(17f);
        currentDialogueView.setTypeface(Typeface.DEFAULT_BOLD);
        currentDialogueView.setText("▶ در حال پخش: —");
        currentDialogueView.setTag("aurora-highlight");
        currentDialogueView.setPadding(dp(10), dp(7), dp(10), dp(7));

        nextDialogueView = new TextView(this);
        nextDialogueView.setTextSize(14f);
        nextDialogueView.setText("بعدی: —");
        nextDialogueView.setPadding(dp(8), dp(4), dp(8), dp(4));

        transcriptContext.addView(previousDialogueView);
        transcriptContext.addView(currentDialogueView);
        transcriptContext.addView(nextDialogueView);
        root.addView(transcriptContext);
        dialogueWindow = new AuroraDialogueWindow(this);
        dialogueWindow.setOnDialogueTapListener(this::playTappedDialogue);

        ScrollView lessonScroll = new ScrollView(this);
        LinearLayout lessonBox = new LinearLayout(this);
        lessonBox.setOrientation(LinearLayout.VERTICAL);
        lessonBox.setTag("aurora-card");
        lessonBox.setPadding(dp(13), dp(12), dp(13), dp(12));

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

        TextView askTitle = new TextView(this);
        askTitle.setText("💬 درباره همین دیالوگ سؤال کن");
        askTitle.setTextSize(15f);
        askTitle.setTag("aurora-heading");
        askTitle.setPadding(0, dp(8), 0, dp(4));
        lessonBox.addView(askTitle);

        inlineAnswerView = new TextView(this);
        inlineAnswerView.setTextDirection(View.TEXT_DIRECTION_RTL);
        inlineAnswerView.setText("بعد از توضیح Gemini، می‌تونی همین‌جا سؤال بپرسی.");
        inlineAnswerView.setTextSize(14f);
        lessonBox.addView(inlineAnswerView);

        inlineQuestionInput = new EditText(this);
        inlineQuestionInput.setHint("مثلاً: چرا اینجا used to گفته؟");
        inlineQuestionInput.setMinLines(1);
        inlineQuestionInput.setMaxLines(3);
        lessonBox.addView(inlineQuestionInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout inlineActions = new LinearLayout(this);
        inlineActions.setOrientation(LinearLayout.HORIZONTAL);
        inlineMicButton = new Button(this);
        inlineMicButton.setText("🎙 بپرس");
        inlineAskButton = new Button(this);
        inlineAskButton.setText("ارسال ➤");
        inlineAskButton.setTag("aurora-primary");
        inlineSpeakAnswerButton = new Button(this);
        inlineSpeakAnswerButton.setText("🔊 جواب");
        inlineSpeakAnswerButton.setEnabled(false);
        inlineActions.addView(inlineMicButton, new LinearLayout.LayoutParams(0, -2, 1f));
        inlineActions.addView(inlineAskButton, new LinearLayout.LayoutParams(0, -2, 1f));
        lessonBox.addView(inlineActions);

        lessonScroll.addView(lessonBox);
        root.addView(lessonScroll, new LinearLayout.LayoutParams(-1, dp(270)));

        statusView = new TextView(this);
        statusView.setText("یک کلیپ یا فیلم از حافظه گوشی انتخاب کن.");
        statusView.setTag("aurora-status");
        statusView.setPadding(dp(6), dp(8), dp(6), dp(6));
        root.addView(statusView);

        // Real dashboard hierarchy: controls and their click listeners remain the
        // same instances; only presentation and scroll navigation are rebuilt.
        mediaControls = AuroraDashboard.mount(this, root, header, privacy, sourceRow, serviceRow,
                advancedRow, tutorRow, playerView, modes, transcriptContext,
                dialogueWindow, lessonScroll, statusView, directChatButton, advancedToggle,
                videoButton, prepareButton, replayButton, slowReplayButton, continueButton);
        AuroraUi.apply(this, root);
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
        chatButton.setOnClickListener(v -> teachCurrentDialogue());
        vocabularyButton.setOnClickListener(v -> openVocabulary());
        exportTextButton.setOnClickListener(v -> askTranscriptExportFormat());
        inlineAskButton.setOnClickListener(v -> sendInlineTutorQuestion());
        inlineMicButton.setOnClickListener(v -> startInlineSpeechQuestion());
        smartButton.setOnClickListener(v -> selectMode(Mode.SMART));
        autoButton.setOnClickListener(v -> selectMode(Mode.AUTO));
        watchButton.setOnClickListener(v -> selectMode(Mode.WATCH));
        continueButton.setOnClickListener(v -> continueMovie());
        replayButton.setOnClickListener(v -> replayCurrent(false));
        slowReplayButton.setOnClickListener(v -> replayCurrent(true));
    }

    private void teachCurrentDialogue() {
        if (dialogues.isEmpty()) {
            Toast.makeText(this, "اول فیلم را آماده کن تا دیالوگ‌ها ساخته شوند.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Take the index from the SAME centered/purple row the learner sees.
        // Media3's raw position can already be in the NEXT sentence while the
        // most recently heard/highlighted line is the previous one.
        int index = DialogueFocus.teacherButtonIndex(
                displayedDialogueIndex(), dialogues.size());
        if (index < 0 || index >= dialogues.size()) {
            Toast.makeText(this, "الان دیالوگ فعالی پیدا نشد.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (player != null) player.pause();
        
        // Explicit teacher action interrupts single-line audio playback:
        // Gemini is now free to narrate the paused lesson immediately.
        tappedDialoguePlaybackIndex = -1;
        tappedTeacherDialogueIndex = -1;
        replayStopAtMs = -1L;
        cancelPendingTapNarration();

        // Mark the current dialogue as handled so AUTO does not immediately teach it again.
        lastPausedIndex = index;
        if (mode == Mode.AUTO) {
            nextAutoPauseIndex = Math.max(nextAutoPauseIndex, index + 1);
        }

        Dialogue d = dialogues.get(index);
        activeDialogueIndex = index;
        Diagnostics.log("TEACHER", "manual dialogue=" + index
                + " startMs=" + d.startMs + " endMs=" + d.endMs);
        statusView.setText("🎓 معلم دارد همین دیالوگ را توضیح می‌دهد.");
        showTeachingUnit(d, index);
    }

    private void openTutorChat() {
        if (player != null) player.pause();
        // Gemini Chat must see ONLY the same selected/visible sentence.
        // No previous/next line appears in UI OR request context.
        int index = displayedDialogueIndex();
        if (index < 0 && player != null) {
            index = findDialogueForPosition(Math.max(0L, player.getCurrentPosition()));
        }
        String current = index >= 0 && index < dialogues.size()
                ? dialogues.get(index).text : "";

        Intent intent = new Intent(this, TutorChatActivity.class);
        intent.putExtra("current_dialogue", current);
        Diagnostics.log("TUTOR_CHAT", "open ONLY current dialogue=" + index
                + " chars=" + current.length());
        startActivity(intent);
    }

    private void openVocabulary() {
        if (currentArchive == null || !currentArchive.hasTranscript()
                || dialogues.isEmpty()) {
            Toast.makeText(this, "اول فیلم را آماده کن تا متن آن ساخته شود.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (player != null) player.pause();
        int index = displayedDialogueIndex();
        if (index < 0 && player != null)
            index = findDialogueForPosition(Math.max(0L, player.getCurrentPosition()));
        if (index < 0 || index >= dialogues.size()) index = 0;
        Intent intent = new Intent(this, VocabularyActivity.class);
        intent.putExtra("archive_id", currentArchive.id);
        intent.putExtra("dialogue_index", index);
        Diagnostics.log("VOCABULARY", "open archive idPrefix="
                + currentArchive.id.substring(0, 10)
                + " currentDialogue=" + index + " total=" + dialogues.size());
        startActivity(intent);
    }

    private void askTranscriptExportFormat() {
        if (dialogues.isEmpty()) {
            Toast.makeText(this, "متن دیالوگ‌ها هنوز آماده نیست.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("ذخیره متن استخراج‌شده فیلم")
                .setItems(new String[]{
                        "متن کامل همراه زمان دیالوگ‌ها (.txt)",
                        "زیرنویس با زمان میلی‌ثانیه (.srt)"
                }, (dialog, choice) -> pickTranscriptDestination(
                        choice == 0 ? TranscriptExport.Format.TXT : TranscriptExport.Format.SRT))
                .setNegativeButton("انصراف", (d, w) -> {})
                .show();
    }

    private void pickTranscriptDestination(TranscriptExport.Format format) {
        pendingExportRows = new ArrayList<>();
        for (Dialogue row : dialogues) {
            pendingExportRows.add(new TranscriptExport.Row(
                    row.startMs, row.endMs, row.text));
        }
        if (pendingExportRows.isEmpty()) return;
        pendingExportFormat = format;
        // Storage Access Framework: learner CHOOSES directory/provider and
        // filename, including Documents, Downloads or a connected Drive.
        // Never write an arbitrary unchecked path or require storage access.
        String base = currentArchive == null ? "EnglishAITutor-Transcript"
                : currentArchive.title.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.trim().isEmpty()) base = "EnglishAITutor-Transcript";
        if (base.length() > 64) base = base.substring(0, 64);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE,
                base + "-transcript." + (format == TranscriptExport.Format.TXT ? "txt" : "srt"));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, SAVE_EXTRACTED_TRANSCRIPT);
    }

    private void saveExtractedTranscript(Uri destination) {
        final List<TranscriptExport.Row> snapshot = new ArrayList<>(pendingExportRows);
        final TranscriptExport.Format format = pendingExportFormat;
        pendingExportRows.clear();
        if (snapshot.isEmpty()) {
            statusView.setText("برای ذخیره، دوباره دکمه متن را بزن.");
            return;
        }
        statusView.setText("در حال ذخیره " + snapshot.size()
                + " دیالوگ در آدرس انتخاب‌شده...");
        worker.execute(() -> {
            try {
                String transcript = TranscriptExport.render(snapshot, format);
                try (java.io.OutputStream out =
                             getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null)
                        throw new IllegalStateException("آدرس ذخیره انتخاب‌شده قابل نوشتن نیست");
                    out.write(transcript.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    statusView.setText("✅ متن کامل فیلم (" + snapshot.size()
                            + " دیالوگ) در محل انتخاب‌شده ذخیره شد.");
                    Toast.makeText(this, "فایل ذخیره شد", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                Diagnostics.error("TRANSCRIPT_EXPORT", error);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed())
                        statusView.setText("ذخیره متن ناموفق بود: " + safeMessage(error));
                });
            }
        });
    }

    private void initGemini() {
        geminiLessonService = new GeminiLessonService(this);
        SharedPreferences prefs = getSharedPreferences(AI_PREFS, MODE_PRIVATE);
        boolean ok = geminiLessonService.configure(
                prefs.getString(AI_API_KEY, ""),
                prefs.getString(AI_APP_ID, ""),
                prefs.getString(AI_PROJECT_ID, ""));

        if (!ok) {
            try {
                String bundled = readBundledFirebaseConfig();
                ok = configureFirebaseFromJson(bundled, "bundled", false);
            } catch (Throwable t) {
                Diagnostics.error("FIREBASE_BUNDLED", t);
            }
        }

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
            boolean ok = configureFirebaseFromJson(raw, "manual", true);
            statusView.setText(ok
                    ? "✅ Firebase خوانده شد و Gemini آماده است. حالا کلیپ را آماده کن."
                    : "فایل خوانده شد، ولی اتصال Gemini هنوز آماده نشد. اپ را یک بار ببند و باز کن.");
        } catch (Throwable t) {
            Diagnostics.error("FIREBASE_IMPORT", t);
            statusView.setText("خواندن google-services.json ناموفق بود: " + safeMessage(t));
        }
    }

    private boolean configureFirebaseFromJson(String raw, String source, boolean persist) throws Exception {
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.startsWith("{")) {
            throw new IllegalArgumentException("فایل Firebase JSON معتبر نیست");
        }

        JSONObject root = new JSONObject(trimmed);
        String projectId = root.getJSONObject("project_info").optString("project_id", "").trim();
        JSONArray clients = root.optJSONArray("client");
        if (clients == null || clients.length() == 0) {
            throw new IllegalArgumentException("client پیدا نشد");
        }

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
            throw new IllegalArgumentException("این Firebase config برای package دیگری است، نه " + getPackageName());
        }
        if (projectId.isEmpty() || appId.isEmpty() || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Project ID / App ID / API Key کامل نیست");
        }

        if (persist) {
            SharedPreferences prefs = getSharedPreferences(AI_PREFS, MODE_PRIVATE);
            prefs.edit()
                    .putString(AI_API_KEY, apiKey)
                    .putString(AI_APP_ID, appId)
                    .putString(AI_PROJECT_ID, projectId)
                    .apply();
        }

        boolean ok = geminiLessonService.configure(apiKey, appId, projectId);
        Diagnostics.log("FIREBASE", source + " config configured=" + ok + " packageOk=true");
        updateGeminiButton(ok);
        return ok;
    }

    private String readBundledFirebaseConfig() throws Exception {
        try (InputStream in = getAssets().open("google-services.json");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                total += n;
                if (total > MAX_FIREBASE_CONFIG_BYTES) {
                    throw new IllegalArgumentException("Bundled Firebase config is too large");
                }
                out.write(buffer, 0, n);
            }
            Diagnostics.log("FIREBASE_BUNDLED", "loaded bytes=" + total);
            return out.toString(StandardCharsets.UTF_8.name());
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

        if (requestCode == SAVE_EXTRACTED_TRANSCRIPT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                saveExtractedTranscript(data.getData());
            } else {
                pendingExportRows.clear();
            }
            return;
        }

        if (requestCode == RECOGNIZE_INLINE_QUESTION) {
            if (resultCode != RESULT_OK || data == null) return;
            ArrayList<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results == null || results.isEmpty()) return;
            inlineQuestionInput.setText(results.get(0));
            inlineQuestionInput.setSelection(inlineQuestionInput.getText().length());
            sendInlineTutorQuestion();
            return;
        }

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
        currentArchive = null;
        lastArchiveProgressSaveAtMs = 0L;
        Diagnostics.log("MEDIA", "video selected uriScheme=" + (uri.getScheme() == null ? "?" : uri.getScheme()));
        dialogues.clear();
        activeDialogueIndex = -1;
        lastPlayedDialogueIndex = -1;
        tappedDialoguePlaybackIndex = -1;
        tappedTeacherDialogueIndex = -1;
        cancelPendingTapNarration();
        lastSpokenLesson = "";
        speakLessonButton.setEnabled(false);
        chatButton.setText("🎓 معلم همین دیالوگ");
        replayStopAtMs = -1L;
        lessonDialogueIndex = -1;
        presentedTeacherDialogueIndex = -1;
        lessonRequestGeneration++;
        setMediaControlsReady(false);
        lastPausedIndex = -1;
        nextAutoPauseIndex = 0;
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.pause();
        dialogueView.setText("کلیپ انتخاب شد.");
        updateLiveTranscriptContext(-1);
        translationView.setText("«آماده‌سازی خودکار» را بزن. فیلم اول داخل آرشیو محلی اپ ذخیره می‌شود.");
        lessonView.setText("اگر همین فیلم قبلاً آرشیو شده باشد، صدا و دیالوگ‌های ذخیره‌شده دوباره استفاده می‌شوند و Whisper از نو اجرا نمی‌شود.");
        prepareButton.setEnabled(true);
        statusView.setText("آماده آرشیو محلی؛ فیلم، صدا و دیالوگ‌ها فقط روی همین گوشی نگه‌داری می‌شوند.");
    }

    private void prepareSelectedVideo() {
        if (selectedVideoUri == null || preparing) return;
        preparing = true;
        setMediaControlsReady(false);
        Diagnostics.log("PIPELINE", "prepare start archiveFirst=true modelReady=" + ModelManager.isReady(this));
        prepareButton.setEnabled(false);
        player.pause();
        statusView.setText("در حال بررسی و آرشیو فیلم روی گوشی...");

        final Uri sourceUri = selectedVideoUri;
        worker.execute(() -> {
            try {
                LocalArchiveManager.Archive archive =
                        LocalArchiveManager.prepareArchive(this, sourceUri, (percent, stage) ->
                                runOnUiThread(() -> statusView.setText(
                                        stage + (percent >= 0 ? ": " + percent + "%" : ""))));

                currentArchive = archive;
                Diagnostics.log("ARCHIVE", "ready id=" + archive.id.substring(0, 12)
                        + " videoBytes=" + archive.videoFile.length()
                        + " hasAudio=" + archive.hasAudio()
                        + " hasTranscript=" + archive.hasTranscript());

                List<LocalArchiveManager.TranscriptRow> saved =
                        LocalArchiveManager.loadTranscript(archive);
                if (!saved.isEmpty()) {
                    LocalArchiveManager.Progress progress =
                            LocalArchiveManager.loadProgress(archive);
                    runOnUiThread(() -> loadArchivedTranscript(archive, saved, progress));
                    return;
                }

                runOnUiThread(this::ensureWhisperAndContinue);
            } catch (Throwable t) {
                Diagnostics.error("ARCHIVE", t);
                runOnUiThread(() -> {
                    preparing = false;
                    prepareButton.setEnabled(true);
                    statusView.setText("آرشیو فیلم ناموفق بود: " + safeMessage(t));
                });
            }
        });
    }

    private void ensureWhisperAndContinue() {
        statusView.setText(ModelManager.isReady(this)
                ? "آرشیو ساخته شد؛ در حال آماده‌سازی دیالوگ‌ها..."
                : "آرشیو ساخته شد؛ مدل Whisper داخلی در حال آماده‌سازی است...");

        ModelManager.ensureModel(this, new ModelManager.Listener() {
            @Override public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                if (percent >= 0) statusView.setText("مدل Whisper: " + percent + "%");
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
                statusView.setText("آماده‌سازی مدل شکست خورد: " + message);
                showWhisperDownloadError(message);
            }
        });
    }

    private void extractAndTranscribe(File modelFile) {
        LocalArchiveManager.Archive archive = currentArchive;
        Uri uri = archive != null ? Uri.fromFile(archive.videoFile) : selectedVideoUri;
        if (uri == null) return;

        statusView.setText(archive != null && archive.hasAudio()
                ? "صدای آرشیوشده پیدا شد؛ Whisper در حال ساخت دیالوگ‌هاست..."
                : "در حال استخراج و ذخیره صدای فیلم در آرشیو...");

        worker.execute(() -> {
            File wav = archive != null
                    ? archive.audioFile
                    : new File(getCacheDir(), "english_tutor_whisper_input.wav");
            try {
                if (archive == null || !archive.hasAudio()
                        || !LocalArchiveManager.hasPreciseTiming(archive)) {
                    // Legacy WAV ignored original video PTS: re-extract once.
                    VideoAudioExtractor.extract(this, uri, wav, (percent, stage) ->
                            runOnUiThread(() -> statusView.setText(stage + ": " + percent + "%")));
                } else {
                    Diagnostics.log("AUDIO", "reused archived wav bytes=" + wav.length());
                }

                Diagnostics.log("AUDIO", "wav ready bytes=" + wav.length()
                        + " archived=" + (archive != null));
                runOnUiThread(() -> statusView.setText("Whisper روی گوشی در حال ساخت دیالوگ‌هاست..."));

                WhisperBridge.transcribe(this, modelFile.getAbsolutePath(), wav.getAbsolutePath(),
                        new WhisperBridge.Callback() {
                            @Override public void onSuccess(List<WhisperBridge.Segment> segments, long processingTimeMs) {
                                Diagnostics.log("WHISPER", "success segments=" + segments.size()
                                        + " processingMs=" + processingTimeMs);

                                // Whisper callbacks run on Android's main thread. Timing
                                // refinement scans the WAV and transcript archiving writes
                                // to disk; doing either here freezes the UI (ANR).
                                statusView.setText("در حال تنظیم زمان‌بندی دیالوگ‌ها در پس‌زمینه...");
                                worker.execute(() -> {
                                    final List<WhisperBridge.Segment> refined;
                                    try {
                                        refined = DialogueTimingRefiner.refine(wav, segments);
                                        Diagnostics.log("WHISPER", "refined dialogues=" + refined.size());
                                        // Real alignment: Wav2Vec2 ONNX emits CTC acoustic
                                        // frames, then Viterbi finds per-word start/end.
                                        // Old Whisper times remain ONLY on failed/weak rows.
                                        Wav2VecAlignmentEngine.Result aligned = null;
                                        try {
                                            runOnUiThread(() -> statusView.setText(
                                                    "در حال هم‌ترازی واقعی کلمات با صوت فیلم..."));
                                            aligned = Wav2VecAlignmentEngine.align(
                                                    MainActivity.this, wav, refined,
                                                    msg -> runOnUiThread(() -> statusView.setText(msg)));
                                            Diagnostics.log("CTC", "word-aligned rows="
                                                    + aligned.aligned + "/" + refined.size());
                                        } catch (Throwable modelFailure) {
                                            Diagnostics.error("CTC_UNAVAILABLE", modelFailure);
                                            runOnUiThread(() -> statusView.setText(
                                                    "مدل هم‌ترازی آماده نشد؛ فعلاً زمان‌بندی Whisper استفاده می‌شود."));
                                        }
                                        List<WhisperBridge.Segment> savedSegments =
                                                aligned != null ? aligned.segments : refined;
                                        List<List<CtcWordAligner.Word>> acousticWords =
                                                aligned != null ? aligned.wordTimings : null;
                                        if (archive != null) {
                                            try {
                                                LocalArchiveManager.saveTranscript(
                                                        archive, savedSegments, acousticWords);
                                                LocalArchiveManager.markPreciseTiming(archive);
                                                Diagnostics.log("ARCHIVE", "saved CTC words="
                                                        + (aligned == null ? 0 : aligned.aligned)
                                                        + "/" + savedSegments.size());
                                            } catch (Throwable t) {
                                                Diagnostics.error("ARCHIVE_TRANSCRIPT", t);
                                            }
                                        }
                                        final List<WhisperBridge.Segment> completed = savedSegments;
                                        final List<List<CtcWordAligner.Word>> completedWords = acousticWords;
                                        runOnUiThread(() -> applyTranscript(
                                                completed, processingTimeMs, completedWords));
                                    } catch (Throwable t) {
                                        Diagnostics.error("TIMING_REFINE", t);
                                        runOnUiThread(() -> {
                                            preparing = false;
                                            prepareButton.setEnabled(true);
                                            statusView.setText("پردازش زمان‌بندی ناموفق بود: " + safeMessage(t));
                                        });
                                        return;
                                    } finally {
                                        if (archive == null) wav.delete();
                                    }
                                });
                            }

                            @Override public void onError(String message) {
                                Diagnostics.log("WHISPER", "ERROR " + message);
                                if (archive == null) wav.delete();
                                runOnUiThread(() -> {
                                    preparing = false;
                                    prepareButton.setEnabled(true);
                                    statusView.setText("تبدیل صدا به متن شکست خورد: " + message);
                                });
                            }
                        });
            } catch (Throwable t) {
                Diagnostics.error("PIPELINE", t);
                if (archive == null) wav.delete();
                runOnUiThread(() -> {
                    preparing = false;
                    prepareButton.setEnabled(true);
                    statusView.setText("آماده‌سازی شکست خورد: " + safeMessage(t));
                });
            }
        });
    }

    private void applyTranscript(List<WhisperBridge.Segment> segments,
                                 long processingTimeMs,
                                 List<List<CtcWordAligner.Word>> wordTimings) {
        dialogues.clear();
        for (int i = 0; i < segments.size(); i++) {
            WhisperBridge.Segment s = segments.get(i);
            String text = s.getText() == null ? "" : s.getText().replaceAll("\\s+", " ").trim();
            if (text.isEmpty() || s.getEndMs() <= s.getStartMs()) continue;
            List<CtcWordAligner.Word> words =
                    wordTimings != null && i < wordTimings.size()
                            ? wordTimings.get(i) : new ArrayList<>();
            dialogues.add(new Dialogue(s.getStartMs(), s.getEndMs(), text, words));
        }
        preparing = false;
        prepareButton.setEnabled(true);
        lastPausedIndex = -1;
        activeDialogueIndex = -1;
        lastPlayedDialogueIndex = -1;
        tappedDialoguePlaybackIndex = -1;
        tappedTeacherDialogueIndex = -1;
        presentedTeacherDialogueIndex = -1;
        lessonDialogueIndex = -1;
        lessonRequestGeneration++;
        cancelPendingTapNarration();
        nextAutoPauseIndex = 0;
        if (dialogues.isEmpty()) {
            Diagnostics.log("TRANSCRIPT", "empty after filtering");
            statusView.setText("Whisper دیالوگ قابل استفاده پیدا نکرد. کلیپ دیگری را امتحان کن.");
            return;
        }
        int wordAlignedRows = 0;
        for (Dialogue item : dialogues) if (!item.words.isEmpty()) wordAlignedRows++;
        Diagnostics.log("TRANSCRIPT", "ready dialogues=" + dialogues.size()
                + " CTC-aligned=" + wordAlignedRows);
        statusView.setText(String.format(Locale.US,
                "%d دیالوگ • Whisper: %.1f ثانیه • Wav2Vec2 CTC: %d/%d%s",
                dialogues.size(), processingTimeMs / 1000.0,
                wordAlignedRows, dialogues.size(),
                wordAlignedRows == 0 ? " (فقط زمان تقریبی Whisper)" : ""));
        dialogueView.setText(dialogues.get(0).text);
        rememberPreparedArchive(currentArchive);
        setMediaControlsReady(true);
        translationView.setText("▶ فیلم را پخش کن؛ در توقف ترجمه فارسی نمایش داده می‌شود.");
        lessonView.setText("Mode فعلی: " + mode
                + " • فیلم، صدا و دیالوگ‌ها در آرشیو محلی ذخیره شدند.");
        // Bind teacher and seven-line list AFTER the status card has been
        // initialized, so it cannot overwrite the synchronized sentence.
        updateLiveTranscriptContext(-1);

        if (currentArchive != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(currentArchive.videoFile)));
            player.prepare();
            LocalArchiveManager.saveProgress(currentArchive, 0L, 0);
        }
        boolean migrated = precisionMigrationResumeMs >= 0L;
        long resumeAt = migrated ? precisionMigrationResumeMs : 0L;
        precisionMigrationResumeMs = -1L;
        player.seekTo(resumeAt);
        if (migrated) {
            player.pause();
            // After the new transcript has replaced the old timestamps, the
            // restored purple row must correspond to the same saved position.
            lastPlayedDialogueIndex = lastStartedDialogueAt(resumeAt);
            activeDialogueIndex = findDialogueForPosition(resumeAt);
            nextAutoPauseIndex = firstDialogueEndingAfter(resumeAt);
            updateLiveTranscriptContext(lastPlayedDialogueIndex);
            Diagnostics.log("AUDIO_TIMELINE", "legacy resync completed resumeMs=" + resumeAt);
            statusView.setText("✅ همگام‌سازی دوباره انجام شد؛ ادامه فیلم از جای قبلی آماده است.");
        } else {
            player.play();
        }
    }

    /**
     * Upgrade a v36 (PTS-corrected but NOT word-aligned) movie in-place.
     * No Whisper rerun or user video re-selection: its locally cached WAV
     * and text are enough for actual Wav2Vec2 CTC forced alignment.
     */
    private void scheduleArchivedWordAlignment(LocalArchiveManager.Archive archive) {
        if (archive == null || !LocalArchiveManager.hasPreciseTiming(archive)
                || LocalArchiveManager.hasWordAlignment(archive)
                || archive.id.equals(aligningArchiveId)) return;
        aligningArchiveId = archive.id;
        Diagnostics.log("CTC_ARCHIVE", "word alignment requested archive=" + archive.id.substring(0, 12));
        statusView.setText("در حال ساخت زمان شروع و پایان کلمات روی همین گوشی...");
        worker.execute(() -> {
            try {
                List<LocalArchiveManager.TranscriptRow> saved =
                        LocalArchiveManager.loadTranscript(archive);
                if (saved.isEmpty()) return;
                List<WhisperBridge.Segment> source = new ArrayList<>();
                for (LocalArchiveManager.TranscriptRow row : saved) {
                    source.add(new WhisperBridge.Segment(
                            row.startMs, row.endMs, row.text));
                }
                Wav2VecAlignmentEngine.Result result =
                        Wav2VecAlignmentEngine.align(
                                MainActivity.this, archive.audioFile, source,
                                msg -> runOnUiThread(() -> {
                                    if (!isFinishing() && currentArchive == archive)
                                        statusView.setText(msg);
                                }));
                if (result.aligned <= 0) {
                    Diagnostics.log("CTC_ARCHIVE", "no trustworthy word boundaries");
                    runOnUiThread(() -> {
                        if (currentArchive == archive) statusView.setText(
                                "CTC نتوانست این گفتار را مطمئن جدا کند؛ زمان‌بندی Whisper حفظ شد.");
                    });
                    return;
                }
                LocalArchiveManager.saveTranscript(
                        archive, result.segments, result.wordTimings);
                Diagnostics.log("CTC_ARCHIVE", "saved aligned rows="
                        + result.aligned + "/" + result.segments.size());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || currentArchive != archive) return;
                    if (player != null && player.isPlaying()) {
                        // Do not change indices/timing underneath an active
                        // movie. Newly aligned archive is loaded on next open.
                        statusView.setText("✅ زمان‌بندی کلمات ذخیره شد؛ "
                                + "برای استفاده فیلم را دوباره باز کن.");
                        return;
                    }
                    long currentMs = player == null ? 0L : player.getCurrentPosition();
                    dialogues.clear();
                    for (int i = 0; i < result.segments.size(); i++) {
                        WhisperBridge.Segment seg = result.segments.get(i);
                        List<CtcWordAligner.Word> words = result.wordTimings.get(i);
                        dialogues.add(new Dialogue(
                                seg.getStartMs(), seg.getEndMs(), seg.getText(), words));
                    }
                    activeDialogueIndex = findDialogueForPosition(currentMs);
                    lastPlayedDialogueIndex = lastStartedDialogueAt(currentMs);
                    nextAutoPauseIndex = firstDialogueEndingAfter(currentMs);
                    updateLiveTranscriptContext(lastPlayedDialogueIndex);
                    statusView.setText("✅ تطبیق صوت و متن با Wav2Vec2: "
                            + result.aligned + " از " + result.segments.size()
                            + " دیالوگ؛ بقیه با زمان Whisper باقی ماندند.");
                });
            } catch (Throwable failure) {
                Diagnostics.error("CTC_ARCHIVE", failure);
                runOnUiThread(() -> {
                    if (currentArchive == archive) statusView.setText(
                            "مدل هم‌ترازی در دسترس نبود؛ فیلم با زمان‌های قبلی قابل پخش است.");
                });
            } finally {
                runOnUiThread(() -> {
                    if (archive.id.equals(aligningArchiveId)) aligningArchiveId = "";
                });
            }
        });
    }

    private void loadArchivedTranscript(LocalArchiveManager.Archive archive,
                                        List<LocalArchiveManager.TranscriptRow> saved,
                                        LocalArchiveManager.Progress progress) {
        dialogues.clear();
        for (LocalArchiveManager.TranscriptRow row : saved) {
            dialogues.add(new Dialogue(row.startMs, row.endMs, row.text, row.words));
        }

        preparing = false;
        prepareButton.setEnabled(true);
        lastPausedIndex = -1;
        replayStopAtMs = -1L;
        replaySlow = false;
        tappedDialoguePlaybackIndex = -1;
        tappedTeacherDialogueIndex = -1;
        presentedTeacherDialogueIndex = -1;
        lessonRequestGeneration++;
        cancelPendingTapNarration();

        long resumeMs = Math.max(0L, progress == null ? 0L : progress.positionMs);
        int resumeIndex = progress == null ? -1 : progress.dialogueIndex;
        if (resumeIndex < 0 || resumeIndex >= dialogues.size()) {
            resumeIndex = findDialogueForPosition(resumeMs);
        }

        activeDialogueIndex = resumeIndex;
        lessonDialogueIndex = -1;
        nextAutoPauseIndex = firstDialogueEndingAfter(resumeMs);

        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(archive.videoFile)));
        player.prepare();
        player.seekTo(resumeMs);
        player.pause();
        // During a resume the audio before positionMs really was played.
        // A mid-sentence pause should show that sentence, not only the
        // previous fully completed sentence. Set this AFTER seekTo because
        // its discontinuity listener intentionally handles manual scrubbing.
        lastPlayedDialogueIndex = lastStartedDialogueAt(resumeMs);

        int shownIndex = resumeIndex >= 0 && resumeIndex < dialogues.size() ? resumeIndex : 0;
        dialogueView.setText(dialogues.get(shownIndex).text);
        rememberPreparedArchive(archive);
        setMediaControlsReady(true);
        translationView.setText("♻️ این فیلم از آرشیو محلی بازیابی شد؛ صدا و دیالوگ‌ها دوباره ساخته نشدند.");
        lessonView.setText("📚 ادامه از " + formatMs(resumeMs)
                + "\nدیالوگ‌های ذخیره‌شده: " + dialogues.size()
                + "\nبرای ادامه ▶ را بزن.");
        // The saved position and highlighted row must agree with the visible
        // teacher card as soon as the archived movie reappears.
        updateLiveTranscriptContext(lastPlayedDialogueIndex);
        continueButton.setEnabled(true);

        Diagnostics.log("ARCHIVE", "restored dialogues=" + dialogues.size()
                + " resumeMs=" + resumeMs
                + " dialogue=" + shownIndex);
        int alignedRows = 0;
        for (Dialogue item : dialogues) if (!item.words.isEmpty()) alignedRows++;
        statusView.setText("✅ آرشیو بازیابی شد • زمان کلمات CTC: "
                + alignedRows + "/" + dialogues.size());
        if (!LocalArchiveManager.hasPreciseTiming(archive)) {
            // Keep the old film visible; re-extract WAV and run Whisper once
            // with corrected PTS. No deletion or manual video re-selection.
            precisionMigrationResumeMs = resumeMs;
            preparing = true;
            prepareButton.setEnabled(false);
            player.pause();
            statusView.setText("⏱ آرشیو قدیمی: همگام‌سازی دوباره فیلم و دیالوگ‌ها "
                    + "یک‌بار روی گوشی انجام می‌شود...");
            Diagnostics.log("AUDIO_TIMELINE", "upgrading existing archive rows="
                    + dialogues.size() + " atMs=" + resumeMs);
            handler.post(this::ensureWhisperAndContinue);
        } else if (!LocalArchiveManager.hasWordAlignment(archive)) {
            // An existing film is PTS-corrected but has only Whisper segment
            // timestamps. Run the new model directly over its saved audio.
            scheduleArchivedWordAlignment(archive);
        }
    }

    private void maybeSaveArchiveProgress(long positionMs, int dialogueIndex) {
        if (currentArchive == null) return;
        long now = System.currentTimeMillis();
        if (now - lastArchiveProgressSaveAtMs < 5000L) return;
        lastArchiveProgressSaveAtMs = now;
        LocalArchiveManager.saveProgress(currentArchive, positionMs, dialogueIndex);
    }

    private void saveArchiveProgressNow() {
        if (currentArchive == null || player == null) return;
        long position = Math.max(0L, player.getCurrentPosition());
        int index = findDialogueForPosition(position);
        if (lessonDialogueIndex >= 0) index = lessonDialogueIndex;
        LocalArchiveManager.saveProgress(currentArchive, position, index);
        lastArchiveProgressSaveAtMs = System.currentTimeMillis();
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
                    ? "Auto: بعد از تک‌تک دیالوگ‌ها توقف می‌کند و هر کدام را جدا توضیح می‌دهد."
                    : "Watch: فیلم بدون توقف خودکار پخش می‌شود.");
        }
        // Refresh only the visual selection state of the existing mode buttons.
        AuroraUi.apply(this, auroraRoot);
    }

    private void cancelPreciseDialogueStop() {
        dialogueStopGeneration++;
        if (preciseDialogueStopMessage != null) {
            try { preciseDialogueStopMessage.cancel(); } catch (Exception ignored) {}
            preciseDialogueStopMessage = null;
        }
    }

    /**
     * Media3 delivers PlayerMessage at the requested playback timeline
     * position. The 10ms polling tick remains only as a safety fallback.
     * This cannot force a codec to emit PCM with sub-millisecond latency,
     * but avoids an extra 10-30ms Java timer overshoot.
     */
    private void schedulePreciseDialogueStop(long endMs) {
        cancelPreciseDialogueStop();
        if (player == null || endMs <= 0L) return;
        final long generation = dialogueStopGeneration;
        try {
            preciseDialogueStopMessage = player.createMessage((type, payload) -> {
                if (generation != dialogueStopGeneration
                        || replayStopAtMs != endMs || player == null) return;
                preciseDialogueStopMessage = null;
                if (player.isPlaying()) player.pause();
                replayStopAtMs = -1L;
                if (replaySlow) player.setPlaybackSpeed(1.0f);
                replaySlow = false;
                if (tappedDialoguePlaybackIndex >= 0) {
                    finishTappedDialoguePlayback(tappedDialoguePlaybackIndex);
                }
                Diagnostics.log("DIALOGUE_STOP", "Media3 reached endMs=" + endMs
                        + " currentPosition=" + player.getCurrentPosition());
            })
                    .setLooper(Looper.getMainLooper())
                    .setPosition(endMs)
                    .setDeleteAfterDelivery(true)
                    .send();
        } catch (Exception issue) {
            // The 10ms playbackTick still handles this exact bound if Media3
            // cannot schedule a message for an unusual media source.
            Diagnostics.error("DIALOGUE_STOP_SCHEDULE", issue);
        }
    }

    private void syncDialogueWithPlayer() {
        if (player == null || dialogues.isEmpty()) return;
        long position = player.getCurrentPosition();

        if (replayStopAtMs >= 0L && player.isPlaying() && position >= replayStopAtMs) {
            // Fallback if a platform/media source could not deliver the
            // scheduled timeline callback.
            cancelPreciseDialogueStop();
            player.pause();
            replayStopAtMs = -1L;
            if (replaySlow) player.setPlaybackSpeed(1.0f);
            replaySlow = false;
            if (tappedDialoguePlaybackIndex >= 0) {
                finishTappedDialoguePlayback(tappedDialoguePlaybackIndex);
            }
            return;
        }

        // A sentence the user tapped has its own start/end playback window.
        // Do not let a neighboring timestamp or AUTO/SMART teaching logic
        // advance the highlight or interrupt this chosen sentence.
        if (tappedDialoguePlaybackIndex >= 0) return;

        int index = findDialogueForPosition(position);
        maybeSaveArchiveProgress(position, index);
        if (index >= 0 && index != activeDialogueIndex) {
            activeDialogueIndex = index;
            dialogueView.setText(dialogues.get(index).text);
        }
        // The current card must be the last line whose audio PLAYED.
        // Keep it during silence, buffering and pauses, not the next line.
        if (player.isPlaying() && index >= 0
                && position >= dialogues.get(index).startMs
                && index != lastPlayedDialogueIndex) {
            lastPlayedDialogueIndex = index;
            updateLiveTranscriptContext(index);
        }

        if (replayStopAtMs >= 0L) return;
        if (!player.isPlaying() || mode == Mode.WATCH) return;

        // AUTO must explain every dialogue. Use a sequential cursor instead of a
        // narrow timing window so a busy frame/tick cannot silently skip a line.
        if (mode == Mode.AUTO) {
            while (nextAutoPauseIndex < dialogues.size()) {
                Dialogue autoDialogue = dialogues.get(nextAutoPauseIndex);
                long pauseAt = autoDialogue.endMs;
                if (position < pauseAt) break;

                int autoIndex = nextAutoPauseIndex++;
                lastPausedIndex = autoIndex;
                Diagnostics.log("PAUSE", "AUTO dialogue=" + autoIndex
                        + " startMs=" + autoDialogue.startMs
                        + " endMs=" + autoDialogue.endMs);
                player.pause();
                showTeachingUnit(autoDialogue, autoIndex);
                return;
            }
            return;
        }

        if (index < 0 || index == lastPausedIndex) return;
        Dialogue d = dialogues.get(index);
        long pauseAt = d.endMs;
        if (position >= pauseAt && position <= pauseAt + 450L && shouldTeach(d)) {
            lastPausedIndex = index;
            Diagnostics.log("PAUSE", "dialogue=" + index + " startMs=" + d.startMs + " endMs=" + d.endMs);
            player.pause();
            showTeachingUnit(d, index);
        }
    }

    /**
     * One visible selection drives the seven-line window and the teacher card.
     * While a tapped lesson is playing or paused, the explicitly selected
     * sentence stays in the center even if a Media3 seek callback reports
     * a neighboring Whisper boundary. Outside selection mode, the last
     * genuinely played sentence remains authoritative.
     */
    private int displayedDialogueIndex() {
        // Single rule for both AUTO/SMART teacher pauses and manual taps.
        // In particular: teacher = dialogue 26 must NEVER appear as "next 1"
        // in the seven-line window just because the last playback tick says 25.
        return DialogueFocus.visibleIndex(
                dialogues.size(), lastPlayedDialogueIndex, lessonDialogueIndex,
                tappedTeacherDialogueIndex, tappedDialoguePlaybackIndex,
                player != null && player.isPlaying());
    }

    private void clearOutdatedTeacherContext(String reason) {
        Diagnostics.log("DIALOGUE_SYNC", reason + " cleared teacher="
                + lessonDialogueIndex);
        tappedTeacherDialogueIndex = -1;
        lessonDialogueIndex = -1;
        presentedTeacherDialogueIndex = -1;
        lessonRequestGeneration++;
        cancelPendingTapNarration();
        lastSpokenLesson = "";
        
        speakLessonButton.setEnabled(false);
        chatButton.setText("🎓 معلم همین دیالوگ");
        lessonView.setText("برای توضیح دیالوگ جدید، روی یکی از جمله‌ها بزن "
                + "یا «معلم همین دیالوگ» را انتخاب کن.");
        translationView.setText("دیالوگ تازه هنوز برای معلم انتخاب نشده است.");
        resetInlineTutorForDialogue(-1);
        if (inlineAskButton != null) inlineAskButton.setEnabled(false);
        if (inlineMicButton != null) inlineMicButton.setEnabled(false);
    }

    private void showTeacherPreviewForVisibleDialogue(int index) {
        if (index < 0 || index >= dialogues.size()) return;
        // This is a preview of the EXACT highlighted line, not a new Gemini
        // teaching request. The full explanation loads only when tapping
        // a sentence or pressing the teacher button / during AUTO pause.
        Dialogue visible = dialogues.get(index);
        presentedTeacherDialogueIndex = index;
        dialogueView.setText(visible.text);
        chatButton.setText("🎓 معلم: دیالوگ " + (index + 1));
        lastSpokenLesson = "";
        speakLessonButton.setEnabled(false);
        translationView.setText("برای معنی و توضیح این جمله، روی «معلم همین دیالوگ» بزن.");
        lessonView.setText("🎓 دیالوگ " + (index + 1) + ": " + visible.text
                + "\n⏱ " + formatMs(visible.startMs) + " → " + formatMs(visible.endMs)
                + "\n▶ این جمله اکنون هایلایت شده؛ برای توضیح فارسی، معلم را بزن.");
        resetInlineTutorForDialogue(-1);
        if (inlineAskButton != null) inlineAskButton.setEnabled(false);
        if (inlineMicButton != null) inlineMicButton.setEnabled(false);
        Diagnostics.log("DIALOGUE_SYNC", "teacher preview=" + index);
    }

    private void ensureTeacherCardMatchesHighlight(int index) {
        if (index < 0 || index >= dialogues.size() || lessonView == null) return;
        String text = dialogues.get(index).text;
        String card = lessonView.getText() == null
                ? "" : lessonView.getText().toString();
        if (DialogueFocus.teacherCardMatchesVisible(
                index, dialogues.size(), text, card)) return;
        // Fail safe: never explain the wrong sentence. Discard a mismatched
        // lesson (and late Gemini callbacks) and show the correct line.
        Diagnostics.log("DIALOGUE_REPAIR", "mismatched card index="
                + presentedTeacherDialogueIndex + " highlight=" + index
                + " activeTeacher=" + lessonDialogueIndex);
        if (lessonDialogueIndex >= 0) {
            clearOutdatedTeacherContext("incorrect tutor card for " + index);
        }
        showTeacherPreviewForVisibleDialogue(index);
    }

    private void updateLiveTranscriptContext(int ignoredIndex) {
        final int index = displayedDialogueIndex();
        // Keep the on-screen teacher and the purple subtitle synchronized
        // within ONE main-thread update. In particular, ExoPlayer's own play
        // button must not leave the old teacher text visible as time advances.
        boolean ordinaryPlayback = player != null && player.isPlaying()
                && tappedDialoguePlaybackIndex < 0;
        if (DialogueFocus.shouldClearOldLesson(
                index, lessonDialogueIndex, ordinaryPlayback)) {
            clearOutdatedTeacherContext("playback advanced to " + index);
        }
        if (DialogueFocus.needsTeacherPreview(
                index, presentedTeacherDialogueIndex,
                lessonDialogueIndex, dialogues.size())) {
            showTeacherPreviewForVisibleDialogue(index);
        }
        ensureTeacherCardMatchesHighlight(index);
        if (replayButton != null && slowReplayButton != null) {
            boolean canReplay = index >= 0 && index < dialogues.size();
            replayButton.setEnabled(canReplay);
            slowReplayButton.setEnabled(canReplay);
        }
        // The seven-line view reads the same indexed local Whisper transcript
        // as the existing player; no new ASR, Gemini or timeline logic.
        if (dialogueWindow != null) {
            List<String> texts = new ArrayList<>(dialogues.size());
            for (Dialogue item : dialogues) texts.add(item.text);
            dialogueWindow.setActive(index, texts);
        }
        Diagnostics.log("DIALOGUE_SYNC", "highlight=" + index
                + " played=" + lastPlayedDialogueIndex
                + " teacher=" + lessonDialogueIndex
                + " tapped=" + tappedTeacherDialogueIndex
                + " clip=" + tappedDialoguePlaybackIndex);

        // Preserve legacy fields for existing tutor interactions and debugging.
        if (previousDialogueView == null || currentDialogueView == null || nextDialogueView == null) return;
        if (index < 0 || index >= dialogues.size()) {
            previousDialogueView.setText("قبلی: —");
            currentDialogueView.setText("▶ در حال پخش: —");
            nextDialogueView.setText("بعدی: —");
            return;
        }

        String previous = index > 0 ? dialogues.get(index - 1).text : "—";
        String current = dialogues.get(index).text;
        String next = index + 1 < dialogues.size() ? dialogues.get(index + 1).text : "—";

        previousDialogueView.setText("قبلی: " + previous);
        currentDialogueView.setText("▶ در حال پخش: " + current);
        nextDialogueView.setText("بعدی: " + next);
    }

    // For a restored progress position, a partially spoken sentence has
    // already been heard. For position zero no dialogue has played yet.
    private int lastStartedDialogueAt(long positionMs) {
        if (positionMs <= 0L) return -1;
        for (int i = dialogues.size() - 1; i >= 0; i--) {
            if (dialogues.get(i).startMs < positionMs) return i;
        }
        return -1;
    }

    // On a manual seek, select only sentences COMPLETED at or before the cursor.
    // This prevents an unheard upcoming sentence from being highlighted.
    private int lastCompletedDialogueAt(long positionMs) {
        for (int i = dialogues.size() - 1; i >= 0; i--) {
            if (dialogues.get(i).endMs <= positionMs) return i;
        }
        return -1;
    }

    private void setMediaControlsReady(boolean ready) {
        if (mediaControls == null) return;
        mediaControls.setReady(ready);
        int visibleLine = displayedDialogueIndex();
        boolean canReplay = ready && visibleLine >= 0
                && visibleLine < dialogues.size();
        replayButton.setEnabled(canReplay);
        slowReplayButton.setEnabled(canReplay);
        if (vocabularyButton != null) vocabularyButton.setEnabled(
                ready && currentArchive != null && currentArchive.hasTranscript());
        if (exportTextButton != null) exportTextButton.setEnabled(
                ready && !dialogues.isEmpty());
        Diagnostics.log("UI_STATE", "ready=" + ready
                + " source=" + mediaControls.sourceVisibility()
                + " rail=" + mediaControls.railVisibility()
                + " dialogues=" + dialogues.size());
    }

    private void rememberPreparedArchive(LocalArchiveManager.Archive archive) {
        if (archive == null || dialogues.isEmpty()) return;
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit()
                .putString(SESSION_ARCHIVE_ID, archive.id)
                .apply();
    }

    private void restorePreparedSessionIfAvailable() {
        final String preferred = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
                .getString(SESSION_ARCHIVE_ID, "");
        worker.execute(() -> {
            try {
                LocalArchiveManager.Archive archive =
                        LocalArchiveManager.findMostRecentPreparedArchive(this, preferred);
                if (archive == null) {
                    Diagnostics.log("SESSION_RESTORE", "no usable saved movie");
                    return;
                }
                List<LocalArchiveManager.TranscriptRow> stored =
                        LocalArchiveManager.loadTranscript(archive);
                if (stored.isEmpty()) return;
                LocalArchiveManager.Progress progress =
                        LocalArchiveManager.loadProgress(archive);
                runOnUiThread(() -> {
                    // Never overwrite a new film selected while discovery runs.
                    if (isFinishing() || isDestroyed() || preparing
                            || selectedVideoUri != null || !dialogues.isEmpty()) return;
                    currentArchive = archive;
                    selectedVideoUri = Uri.fromFile(archive.videoFile);
                    Diagnostics.log("SESSION_RESTORE", "restoring existing movie"
                            + " savedRows=" + stored.size());
                    loadArchivedTranscript(archive, stored, progress);
                    statusView.setText("✅ فیلم و " + stored.size()
                            + " دیالوگ از آرشیو بازیابی شد؛ آماده پخش.");
                });
            } catch (Throwable error) {
                Diagnostics.error("SESSION_RESTORE", error);
            }
        });
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
        
        final long requestGeneration = ++lessonRequestGeneration;
        if (tappedDialoguePlaybackIndex != index) {
            // This is a regular pause/manual lesson, not an active row tap.
            tappedTeacherDialogueIndex = -1;
            cancelPendingTapNarration();
        }
        lessonDialogueIndex = index;
        presentedTeacherDialogueIndex = index;
        // Never let the previous sentence's audio/text carry into a new one.
        lastSpokenLesson = "";
        speakLessonButton.setEnabled(false);
        chatButton.setText("🎓 معلم: دیالوگ " + (index + 1));
        translationView.setText("در حال آماده‌سازی معنی همین جمله...");
        lessonView.setText("🎓 دیالوگ " + (index + 1) + ": " + d.text
                + "\n⏱ " + formatMs(d.startMs) + " → " + formatMs(d.endMs));
        dialogueView.setText(d.text);
        // Both automatic teaching and explicit tap are about this exact line.
        // The player's asynchronous seek position must not re-center the
        // seven-row window to its previous neighbor.
        lastPlayedDialogueIndex = index;
        updateLiveTranscriptContext(index);
        resetInlineTutorForDialogue(index);
        replayButton.setVisibility(View.VISIBLE);
        slowReplayButton.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);
        continueButton.setEnabled(true);

        if (geminiLessonService != null && geminiLessonService.isConfigured()) {
            int batchSize = tappedTeacherDialogueIndex == index ? 1 : 4;
            List<GeminiLessonService.LessonInput> batch = buildGeminiBatch(index, batchSize);
            Diagnostics.log("LESSON", "Gemini batch path dialogue=" + index
                    + " items=" + batch.size() + " chars=" + d.text.length());

            continueButton.setEnabled(false);
            translationView.setText("✨ Gemini Flash-Lite در حال آماده‌سازی درس...");
            lessonView.setText("🎓 دیالوگ " + (index + 1) + ": " + d.text
                    + "\n⏱ " + formatMs(d.startMs) + " → " + formatMs(d.endMs)
                    + "\nGemini معنی و نکته‌های همین جمله را آماده می‌کند."
                    + "\nدر حال بررسی واژگان و گرامرِ همین جمله..."
                    + "\nسؤال‌های مطرح‌شده همراه پاسخ، به‌صورت نوشتاری نمایش داده می‌شوند.");

            geminiLessonService.analyzeBatch(batch,
                    new GeminiLessonService.Callback() {
                        @Override public void onSuccess(GeminiLessonService.Lesson lesson) {
                            runOnUiThread(() -> {
                                if (!DialogueFocus.shouldAcceptLessonResponse(
                                        index, requestGeneration,
                                        lessonDialogueIndex, lessonRequestGeneration)) return;
                                continueButton.setEnabled(true);
                                showGeminiLesson(lesson, index);
                            });
                        }

                        @Override public void onError(String message) {
                            Diagnostics.log("LESSON", "Gemini ERROR dialogue=" + index + " " + message);
                            runOnUiThread(() -> {
                                if (!DialogueFocus.shouldAcceptLessonResponse(
                                        index, requestGeneration,
                                        lessonDialogueIndex, lessonRequestGeneration)) return;
                                continueButton.setEnabled(true);
                                lessonView.setText("🎓 دیالوگ " + (index + 1) + ": " + d.text
                                        + "\nGemini در دسترس نبود: " + message
                                        + "\nترجمه محلی به‌عنوان جایگزین استفاده می‌شود.");
                                translateDialogue(d.text, index, requestGeneration);
                            });
                        }
                    });
            return;
        }

        Diagnostics.log("LESSON", "local translation fallback dialogue=" + index);
        translationView.setText("در حال ترجمه فارسی روی گوشی...");
        lessonView.setText("🎓 دیالوگ " + (index + 1) + ": " + d.text
                + "\n⏱ " + formatMs(d.startMs) + " → " + formatMs(d.endMs)
                + "\nGemini تنظیم نشده؛ فعلاً ترجمه محلی همین جمله نمایش داده می‌شود."
                + "\nفقط صدای اصلی فیلم پخش می‌شود؛ تحلیل Gemini نوشتاری است.");
        translateDialogue(d.text, index, requestGeneration);
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
        // A late AI reply must also agree with the sentence the learner
        // actually sees, even if the video has moved through an unexpected
        // Media3 playback transition.
        if (displayedDialogueIndex() != dialogueIndex) {
            Diagnostics.log("DIALOGUE_REPAIR", "discard Gemini lesson=" + dialogueIndex
                    + " current=" + displayedDialogueIndex());
            clearOutdatedTeacherContext("Gemini returned for unhighlighted dialogue");
            updateLiveTranscriptContext(-1);
            return;
        }

        String translation = lesson.translationFa == null ? "" : lesson.translationFa.trim();
        String natural = lesson.naturalMeaningFa == null ? "" : lesson.naturalMeaningFa.trim();
        translationView.setText("🇮🇷 " + (translation.isEmpty() ? natural : translation));

        // Two explicit teaching perspectives plus a paired written Q&A.
        // No narration, TTS callbacks or overlay buttons are triggered.
        lessonView.setText(GeminiTeachingFormat.render(
                dialogueIndex + 1,
                dialogues.get(dialogueIndex).text,
                lesson.vocabulary, lesson.idioms, lesson.grammar,
                lesson.questionFa, lesson.answerFa));
        ensureTeacherCardMatchesHighlight(dialogueIndex);
        lastSpokenLesson = "";
    }

    private void resetInlineTutorForDialogue(int dialogueIndex) {
        if (inlineChatDialogueIndex != dialogueIndex) {
            inlineChatDialogueIndex = dialogueIndex;
            inlineChatHistory.clear();
            lastInlineAnswer = "";
            if (inlineQuestionInput != null) inlineQuestionInput.setText("");
            if (inlineAnswerView != null) {
                inlineAnswerView.setText("💬 سؤال درباره همین دیالوگ را بنویس یا با میکروفن بگو.");
            }
            if (inlineSpeakAnswerButton != null) inlineSpeakAnswerButton.setEnabled(false);
        }
        boolean ready = geminiLessonService != null && geminiLessonService.isConfigured();
        if (inlineAskButton != null) inlineAskButton.setEnabled(ready);
        if (inlineMicButton != null) inlineMicButton.setEnabled(ready);
    }

    private void startInlineSpeechQuestion() {
        if (lessonDialogueIndex < 0 || lessonDialogueIndex >= dialogues.size()) {
            Toast.makeText(this, "اول روی یک دیالوگ توقف کن.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                "سؤالت را درباره همین دیالوگ بگو");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try {
            startActivityForResult(intent, RECOGNIZE_INLINE_QUESTION);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                    "Speech Recognition روی این گوشی در دسترس نیست.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void sendInlineTutorQuestion() {
        if (geminiLessonService == null || !geminiLessonService.isConfigured()) return;
        if (lessonDialogueIndex < 0 || lessonDialogueIndex >= dialogues.size()) {
            Toast.makeText(this, "اول روی یک دیالوگ توقف کن.", Toast.LENGTH_SHORT).show();
            return;
        }

        String question = inlineQuestionInput.getText().toString().trim();
        if (question.isEmpty()) return;

        final int dialogueIndex = lessonDialogueIndex;
        Dialogue d = dialogues.get(dialogueIndex);
        List<String> previous = previousDialogueText(dialogueIndex, 3);
        List<String> history = new ArrayList<>(inlineChatHistory);

        inlineAskButton.setEnabled(false);
        inlineMicButton.setEnabled(false);
        inlineAnswerView.setText("✨ Gemini در حال جواب دادن درباره همین دیالوگ...");
        inlineQuestionInput.setText("");

        Diagnostics.log("INLINE_CHAT_REQ", "dialogue=" + dialogueIndex
                + " questionChars=" + question.length()
                + " historyItems=" + history.size());

        geminiLessonService.askTutor(question, d.text, previous, history,
                new GeminiLessonService.ChatCallback() {
                    @Override
                    public void onSuccess(String answer) {
                        runOnUiThread(() -> {
                            if (lessonDialogueIndex != dialogueIndex) return;
                            lastInlineAnswer = answer == null ? "" : answer.trim();
                            inlineChatHistory.add("User: " + question);
                            inlineChatHistory.add("Tutor: " + lastInlineAnswer);
                            while (inlineChatHistory.size() > 8) inlineChatHistory.remove(0);

                            // Keep both the written question and its written
                            // answer visible after the edit field is cleared.
                            inlineAnswerView.setText("❓ سؤال: " + question
                                    + "\n✅ پاسخ Gemini: " + lastInlineAnswer);
                            inlineAskButton.setEnabled(true);
                            inlineMicButton.setEnabled(true);
                            Diagnostics.log("INLINE_CHAT_RES", "dialogue=" + dialogueIndex
                                    + " responseChars=" + lastInlineAnswer.length());
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (lessonDialogueIndex != dialogueIndex) return;
                            inlineAnswerView.setText("❓ سؤال: " + question
                                    + "\nپاسخ دریافت نشد: " + message);
                            inlineAskButton.setEnabled(true);
                            inlineMicButton.setEnabled(true);
                            Diagnostics.log("INLINE_CHAT", "ERROR dialogue=" + dialogueIndex
                                    + " " + message);
                        });
                    }
                });
    }

    private void appendLessonLine(StringBuilder out, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append('\n');
        out.append(label).append(": ").append(value.trim());
    }

    private void translateDialogue(String text, int dialogueIndex, long requestGeneration) {
        if (!DialogueFocus.shouldAcceptLessonResponse(dialogueIndex, requestGeneration,
                lessonDialogueIndex, lessonRequestGeneration)) return;
        if (translator == null) {
            translationView.setText("مدل ترجمه فارسی روی این دستگاه در دسترس نیست.");
            return;
        }
        if (!translatorReady) {
            DownloadConditions conditions = new DownloadConditions.Builder().build();
            translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener(unused -> {
                        translatorReady = true;
                        translateDialogue(text, dialogueIndex, requestGeneration);
                    })
                    .addOnFailureListener(e -> {
                        if (DialogueFocus.shouldAcceptLessonResponse(dialogueIndex,
                                requestGeneration, lessonDialogueIndex, lessonRequestGeneration)) {
                            translationView.setText("دانلود مدل ترجمه شکست خورد: " + safeMessage(e));
                        }
                    });
            return;
        }
        translator.translate(text)
                .addOnSuccessListener(result -> {
                    if (DialogueFocus.shouldAcceptLessonResponse(dialogueIndex,
                            requestGeneration, lessonDialogueIndex, lessonRequestGeneration)) {
                        translationView.setText("🇮🇷 " + result);
                    }
                })
                .addOnFailureListener(e -> {
                    if (DialogueFocus.shouldAcceptLessonResponse(dialogueIndex,
                            requestGeneration, lessonDialogueIndex, lessonRequestGeneration)) {
                        translationView.setText("ترجمه ناموفق بود: " + safeMessage(e));
                    }
                });
    }

    /**
     * Play precisely the chosen line, independently of AUTO/SMART/Watch.
     * The seven-line list re-centers on the chosen sentence immediately;
     * the existing playback ticker stops the video at its end timestamp.
     */
    private void cancelPendingTapNarration() {
        pendingTapNarrationIndex = -1;
        deferredTapNarration = "";
        tappedClipFinished = false;
    }

    /**
     * The video line ends first. Its Gemini explanation may already be cached
     * or still queued; either way Android TTS never talks over the actor.
     */
    private void finishTappedDialoguePlayback(int index) {
        Diagnostics.log("DIALOGUE_TAP", "finished dialogue=" + index);
        tappedDialoguePlaybackIndex = -1;
        // Gemini output stays on screen; never speak over or after the actor.
        cancelPendingTapNarration();
    }

    private void playTappedDialogue(int index) {
        if (player == null || preparing || index < 0 || index >= dialogues.size()) return;
        Dialogue selected = dialogues.get(index);
        
        player.pause();
        player.setPlaybackSpeed(1.0f);
        replaySlow = false;
        replayStopAtMs = -1L;
        cancelPreciseDialogueStop();

        tappedDialoguePlaybackIndex = index;
        tappedTeacherDialogueIndex = index;
        pendingTapNarrationIndex = index;
        tappedClipFinished = false;
        deferredTapNarration = "";
        lastPausedIndex = index;
        lessonDialogueIndex = -1; // Ignore callbacks for an older lesson.
        nextAutoPauseIndex = Math.min(dialogues.size(), index + 1);
        player.seekTo(Math.max(0L, selected.startMs));
        activeDialogueIndex = index;
        lastPlayedDialogueIndex = index;
        dialogueView.setText(selected.text);
        updateLiveTranscriptContext(index);

        // Synchronize the teacher card immediately with the selected line.
        // Gemini/translation can work asynchronously while the actor speaks.
        // Spoken tutor narration is deferred until this line has ended.
        showTeachingUnit(selected, index);
        continueButton.setEnabled(true);

        // No 25-ms tail: it might contain the NEXT actor. Treat the right
        // boundary as EXCLUSIVE and clamp to the following sentence's start.
        long nextStart = index + 1 < dialogues.size()
                ? dialogues.get(index + 1).startMs : -1L;
        long stopAt = AudioTimeline.exclusiveEnd(
                selected.startMs, selected.endMs, nextStart);
        replayStopAtMs = stopAt;
        schedulePreciseDialogueStop(stopAt);
        Diagnostics.log("DIALOGUE_TAP", "play index=" + index + " startMs="
                + selected.startMs + " endMs=" + selected.endMs
                + " stopMs=" + stopAt);
        statusView.setText("▶ پخش دیالوگ انتخاب‌شده (" + (index + 1)
                + " از " + dialogues.size() + ")");
        player.play();
    }

    private void replayCurrent(boolean slow) {
        
        // The replay/slow button must target the SAME line highlighted on
        // screen, not an older last-heard index from an asynchronous seek.
        int replayIndex = displayedDialogueIndex();
        if (replayIndex < 0 || replayIndex >= dialogues.size()) return;
        if (lessonDialogueIndex >= 0) clearOutdatedTeacherContext("replay");
        cancelPendingTapNarration();
        tappedDialoguePlaybackIndex = -1;
        lastPlayedDialogueIndex = replayIndex;
        Dialogue d = dialogues.get(replayIndex);
        player.setPlaybackSpeed(slow ? 0.72f : 1.0f);
        replaySlow = slow;
        long nextStart = replayIndex + 1 < dialogues.size()
                ? dialogues.get(replayIndex + 1).startMs : -1L;
        replayStopAtMs = AudioTimeline.exclusiveEnd(
                d.startMs, d.endMs, nextStart);
        // No 80-ms pre-roll: that was audibly borrowing the previous phrase.
        player.seekTo(Math.max(0L, d.startMs));
        schedulePreciseDialogueStop(replayStopAtMs);
        player.play();
    }

    private void continueMovie() {
        saveArchiveProgressNow();
        cancelPreciseDialogueStop();
        
        if (lessonDialogueIndex >= 0) clearOutdatedTeacherContext("continue");
        cancelPendingTapNarration();
        tappedDialoguePlaybackIndex = -1;
        replayStopAtMs = -1L;
        replaySlow = false;
        player.setPlaybackSpeed(1.0f);
        // Playback controls stay mounted on the lower-right floating rail.
        player.play();
    }

    private int firstDialogueEndingAfter(long positionMs) {
        for (int i = 0; i < dialogues.size(); i++) {
            if (positionMs < dialogues.get(i).endMs) return i;
        }
        return dialogues.size();
    }

    private int findDialogueForPosition(long positionMs) {
        // Strict [start,end) intervals. The old +550ms tolerance could keep
        // the PREVIOUS line active deep into the following spoken sentence.
        int lo = 0, hi = dialogues.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Dialogue d = dialogues.get(mid);
            if (positionMs < d.startMs) hi = mid - 1;
            else if (positionMs >= d.endMs) lo = mid + 1;
            else if (AudioTimeline.contains(positionMs, d.startMs, d.endMs)) return mid;
            else return -1;
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
    protected void onResume() {
        super.onResume();
        AuroraUi.apply(this, auroraRoot);
        // Keep button presentation derived from the actual loaded transcript,
        // including after returning from Android's installation/settings UI.
        setMediaControlsReady(!preparing && !dialogues.isEmpty());
        if (!dialogues.isEmpty()) updateLiveTranscriptContext(-1);
        if (updater != null) updater.onResume();
    }

    @Override
    protected void onPause() {
        if (updater != null) updater.onPause();
        saveArchiveProgressNow();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveArchiveProgressNow();
        cancelPreciseDialogueStop();
        handler.removeCallbacks(playbackTick);
        worker.shutdownNow();
        if (updater != null) updater.onDestroy();
        if (translator != null) translator.close();
        if (player != null) player.release();
        super.onDestroy();
    }

    private static final class Dialogue {
        final long startMs;
        final long endMs;
        final String text;
        final List<CtcWordAligner.Word> words;
        Dialogue(long startMs, long endMs, String text) {
            this(startMs, endMs, text, new ArrayList<>());
        }
        Dialogue(long startMs, long endMs, String text,
                 List<CtcWordAligner.Word> words) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
            this.words = words == null ? new ArrayList<>() : words;
        }
    }
}

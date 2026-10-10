package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import androidx.core.view.WindowCompat;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Separate conversational tutor screen for English AI Tutor.
 * Text questions or Android speech recognition -> Gemini -> spoken answer with Android TTS.
 * Only transcript text and chat text are sent to Gemini; movie/audio stay local.
 */
public class TutorChatActivity extends Activity {
    private static final int RECOGNIZE_QUESTION = 3101;

    private static final String AI_PREFS = "english_tutor_ai_config";
    private static final String AI_API_KEY = "firebase_api_key";
    private static final String AI_APP_ID = "firebase_app_id";
    private static final String AI_PROJECT_ID = "firebase_project_id";

    private GeminiLessonService gemini;
    private TextView chatView;
    private TextView statusView;
    private EditText questionInput;
    private Button sendButton;
    private Button micButton;
    private ScrollView chatScroll;
    private LinearLayout auroraRoot;

    private String lastAnswer = "";

    private String currentDialogue = "";
    private String nextDialogue = "";
    private ArrayList<String> previousDialogue = new ArrayList<>();
    private final ArrayList<String> chatHistory = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.init(this);

        Intent source = getIntent();
        if (source != null) {
            String current = source.getStringExtra("current_dialogue");
            currentDialogue = current == null ? "" : current.trim();
            String next = source.getStringExtra("next_dialogue");
            nextDialogue = next == null ? "" : next.trim();
            ArrayList<String> previous = source.getStringArrayListExtra("previous_dialogue");
            if (previous != null) previousDialogue.addAll(previous);
        }

        // Prevent Android 15+ edge-to-edge/IME behavior from placing the
        // fixed message composer behind the keyboard or navigation bar.
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        buildUi();
        initGemini();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        auroraRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        final int basePad = dp(12);
        root.setPadding(basePad, basePad, basePad, basePad);

        // On Android 15/16 adjustResize alone is insufficient when drawing
        // edge-to-edge. The IME bottom inset is independent of system bars.
        // Push the fixed composer completely ABOVE the visible keyboard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime =
                        insets.getInsets(WindowInsets.Type.ime());
                boolean keyboardVisible = insets.isVisible(WindowInsets.Type.ime());
                int bottomInset = Math.max(bars.bottom,
                        keyboardVisible ? ime.bottom : 0);
                view.setPadding(
                        basePad + bars.left,
                        Math.max(dp(3), bars.top + dp(3)),
                        basePad + bars.right,
                        dp(3) + bottomInset);
                if (keyboardVisible && chatScroll != null) {
                    chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
                }
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setTag("aurora-strip");
        header.setPadding(dp(5), dp(3), dp(5), dp(3));

        Button backButton = new Button(this);
        backButton.setText("‹");
        backButton.setContentDescription("بازگشت به فیلم");
        backButton.setMinWidth(0);
        backButton.setMinimumWidth(0);
        backButton.setPadding(0, 0, 0, 0);
        backButton.setTag("aurora-tool");
        TextView title = new TextView(this);
        title.setText("Gemini");
        title.setTextSize(16f);
        title.setTag("aurora-title");
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        header.addView(backButton, new LinearLayout.LayoutParams(dp(39), dp(38)));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1f));
        header.addView(AuroraUi.appearanceButton(this, root),
                new LinearLayout.LayoutParams(dp(39), dp(38)));
        root.addView(header);

        TextView privacy = new TextView(this);
        privacy.setText("فقط متن سؤال و زمینه کوتاه دیالوگ برای Gemini می‌رود؛ ویدئو و صدا ارسال نمی‌شوند.");
        privacy.setTextDirection(View.TEXT_DIRECTION_RTL);
        privacy.setTag("aurora-muted");
        privacy.setPadding(dp(5), dp(8), dp(5), dp(8));
        root.addView(privacy);

        LinearLayout dialogueContext = new LinearLayout(this);
        dialogueContext.setOrientation(LinearLayout.VERTICAL);
        dialogueContext.setTag("aurora-card");
        dialogueContext.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView previousView = new TextView(this);
        String previous = previousDialogue.isEmpty()
                ? "—"
                : previousDialogue.get(previousDialogue.size() - 1);
        previousView.setText("قبلی: " + previous);
        previousView.setTextSize(14f);
        previousView.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView currentView = new TextView(this);
        currentView.setText(currentDialogue.isEmpty() ? "—" : currentDialogue);
        currentView.setTextSize(17f);
        currentView.setTypeface(Typeface.DEFAULT_BOLD);
        currentView.setTag("aurora-highlight");
        currentView.setPadding(dp(10), dp(7), dp(10), dp(7));

        TextView nextView = new TextView(this);
        nextView.setText("بعدی: " + (nextDialogue.isEmpty() ? "—" : nextDialogue));
        nextView.setTextSize(14f);
        nextView.setPadding(dp(8), dp(4), dp(8), dp(4));

        dialogueContext.addView(previousView);
        dialogueContext.addView(currentView);
        dialogueContext.addView(nextView);
        root.addView(dialogueContext);

        chatScroll = new ScrollView(this);
        chatScroll.setTag("aurora-chat");
        chatView = new TextView(this);
        chatView.setTextSize(16f);
        chatView.setTextIsSelectable(true);
        chatView.setPadding(dp(8), dp(8), dp(8), dp(8));
        chatScroll.addView(chatView);
        root.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        statusView = new TextView(this);
        statusView.setText("در حال اتصال به Tutor...");
        statusView.setTextDirection(View.TEXT_DIRECTION_RTL);
        statusView.setTag("aurora-status");
        statusView.setPadding(dp(5), dp(5), dp(5), dp(5));
        root.addView(statusView);

        questionInput = new EditText(this);
        questionInput.setHint("سؤالت را بنویس یا با میکروفن بگو...");
        questionInput.setMinLines(1);
        questionInput.setMaxLines(4);
        root.addView(questionInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        micButton = new Button(this);
        micButton.setText("🎙 سؤال صوتی");
        micButton.setTag("aurora-secondary");
        sendButton = new Button(this);
        sendButton.setText("ارسال ➤");
        sendButton.setTag("aurora-primary");
        actions.addView(micButton, new LinearLayout.LayoutParams(0, -2, 1f));
        actions.addView(sendButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(actions);

        AuroraChatScreen.mount(this, root, header, privacy, dialogueContext,
                chatScroll, statusView, questionInput, actions, micButton, sendButton,
                !currentDialogue.isEmpty());
        AuroraUi.apply(this, root);
        setContentView(root);
        root.requestFocus();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) root.requestApplyInsets();

        questionInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        questionInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendQuestion();
                return true;
            }
            return false;
        });
        backButton.setOnClickListener(v -> finish());
        micButton.setOnClickListener(v -> startSpeechQuestion());
        sendButton.setOnClickListener(v -> sendQuestion());
    }

    @Override
    protected void onResume() {
        super.onResume();
        AuroraUi.apply(this, auroraRoot);
    }

    private void initGemini() {
        gemini = new GeminiLessonService(this);
        SharedPreferences prefs = getSharedPreferences(AI_PREFS, MODE_PRIVATE);
        boolean ok = gemini.configure(
                prefs.getString(AI_API_KEY, ""),
                prefs.getString(AI_APP_ID, ""),
                prefs.getString(AI_PROJECT_ID, ""));
        statusView.setText(ok
                ? "✅ Gemini آماده است؛ سؤال کن."
                : "Gemini تنظیم نشده؛ از صفحه فیلم اتصال Gemini را انجام بده.");
        sendButton.setEnabled(ok);
        micButton.setEnabled(ok);
        Diagnostics.log("TUTOR_CHAT", "startup configured=" + ok
                + " currentChars=" + currentDialogue.length());
    }

    private void sendQuestion() {
        if (gemini == null || !gemini.isConfigured()) return;
        String question = questionInput.getText().toString().trim();
        if (question.isEmpty()) return;

        appendUser(question);
        questionInput.setText("");
        // Keep the keyboard accessible for follow-up chat. The IME insets
        // keep the input bar visible while the conversation scrolls.

        sendButton.setEnabled(false);
        micButton.setEnabled(false);
        statusView.setText("✨ Tutor در حال فکر کردن...");

        List<String> historyForRequest = new ArrayList<>(chatHistory);
        gemini.askTutor(question, currentDialogue, previousDialogue, historyForRequest,
                new GeminiLessonService.ChatCallback() {
                    @Override
                    public void onSuccess(String answer) {
                        runOnUiThread(() -> {
                            lastAnswer = answer == null ? "" : answer.trim();
                            appendTutor(lastAnswer);
                            chatHistory.add("User: " + question);
                            chatHistory.add("Tutor: " + lastAnswer);
                            trimHistory();
                            statusView.setText("✅ آماده سؤال بعدی");
                            sendButton.setEnabled(true);
                            micButton.setEnabled(true);
                            // Text-only Gemini response. No TTS overlay.
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            appendSystem("خطای Gemini: " + message);
                            statusView.setText("ارسال ناموفق بود؛ دوباره امتحان کن.");
                            sendButton.setEnabled(true);
                            micButton.setEnabled(true);
                        });
                    }
                });
    }

    private void trimHistory() {
        while (chatHistory.size() > 8) chatHistory.remove(0);
    }

    private void startSpeechQuestion() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                "سؤالت را به فارسی یا انگلیسی بگو");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try {
            startActivityForResult(intent, RECOGNIZE_QUESTION);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                    "Speech Recognition روی این گوشی در دسترس نیست.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RECOGNIZE_QUESTION || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) return;
        questionInput.setText(results.get(0));
        questionInput.setSelection(questionInput.getText().length());
        statusView.setText("صدای سؤال گرفته شد؛ اگر درست است ارسال را بزن.");
    }

    private void appendUser(String text) {
        appendLine("👤 شما", text);
    }

    private void appendTutor(String text) {
        appendLine("✨ Tutor", text);
    }

    private void appendSystem(String text) {
        appendLine("ℹ", text);
    }

    private void appendLine(String who, String text) {
        String old = chatView.getText().toString();
        String next = old.isEmpty()
                ? who + ": " + text
                : old + "\n\n" + who + ": " + text;
        chatView.setText(next);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}

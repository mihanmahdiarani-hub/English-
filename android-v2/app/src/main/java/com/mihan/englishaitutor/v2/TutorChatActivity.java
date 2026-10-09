package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
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
import java.util.Locale;

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
    private Button speakButton;
    private ScrollView chatScroll;
    private LinearLayout auroraRoot;

    private TextToSpeech tts;
    private boolean ttsReady = false;
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

        buildUi();
        initTts();
        initGemini();

        if (!currentDialogue.isEmpty()) {
            appendSystem("همین دیالوگ مارک‌شده موضوع سؤال‌های این صفحه است.");
        } else {
            appendSystem("می‌توانی درباره انگلیسی هر سؤالی بپرسی.");
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        auroraRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        final int basePad = dp(12);
        root.setPadding(basePad, basePad, basePad, basePad);

        // Android 15/16 can draw app content behind the system navigation bar.
        // Keep the question field and action buttons safely above that area.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(
                        basePad + bars.left,
                        basePad + bars.top,
                        basePad + bars.right,
                        basePad + bars.bottom);
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
        backButton.setText("← فیلم");
        backButton.setTag("aurora-tool");
        TextView title = new TextView(this);
        title.setText("Gemini Tutor Chat");
        title.setTextSize(19f);
        title.setTag("aurora-title");
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        speakButton = new Button(this);
        speakButton.setText("🔊");
        speakButton.setTag("aurora-secondary");
        speakButton.setEnabled(false);

        header.addView(backButton, new LinearLayout.LayoutParams(0, -2, 0.8f));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 2f));
        header.addView(speakButton, new LinearLayout.LayoutParams(0, -2, 0.6f));
        header.addView(AuroraUi.appearanceButton(this, root),
                new LinearLayout.LayoutParams(dp(43), dp(46)));
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
        currentView.setText("▶ دیالوگ فعلی: " + (currentDialogue.isEmpty() ? "—" : currentDialogue));
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

        backButton.setOnClickListener(v -> finish());
        speakButton.setOnClickListener(v -> speak(lastAnswer));
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

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            Diagnostics.log("TTS", "chat init success=" + ttsReady);
            if (!ttsReady) {
                runOnUiThread(() -> Toast.makeText(
                        TutorChatActivity.this,
                        "موتور تبدیل متن به گفتار روی گوشی آماده نیست.",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void sendQuestion() {
        if (gemini == null || !gemini.isConfigured()) return;
        String question = questionInput.getText().toString().trim();
        if (question.isEmpty()) return;

        appendUser(question);
        questionInput.setText("");
        hideKeyboard();

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
                            speakButton.setEnabled(!lastAnswer.isEmpty());
                            speak(lastAnswer);
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

    private void speak(String text) {
        String clean = text == null ? "" : text.trim();
        if (!ttsReady || tts == null || clean.isEmpty()) return;

        Locale preferred = containsPersian(clean)
                ? new Locale("fa", "IR")
                : Locale.US;
        int result = tts.setLanguage(preferred);
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US);
            Diagnostics.log("TTS", "preferred language unavailable=" + preferred);
        }
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "tutor_chat_answer");
    }

    private boolean containsPersian(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u0600' && c <= '\u06FF')
                    || (c >= '\u0750' && c <= '\u077F')) {
                return true;
            }
        }
        return false;
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

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View focus = getCurrentFocus();
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}

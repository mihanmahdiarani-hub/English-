package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
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

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String lastAnswer = "";

    private String currentDialogue = "";
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
            ArrayList<String> previous = source.getStringArrayListExtra("previous_dialogue");
            if (previous != null) previousDialogue.addAll(previous);
        }

        buildUi();
        initTts();
        initGemini();

        if (!currentDialogue.isEmpty()) {
            appendSystem("دیالوگ فعلی: " + currentDialogue);
        } else {
            appendSystem("می‌توانی درباره انگلیسی هر سؤالی بپرسی.");
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        Button backButton = new Button(this);
        backButton.setText("← فیلم");
        TextView title = new TextView(this);
        title.setText("Gemini Tutor Chat");
        title.setTextSize(19f);
        title.setGravity(Gravity.CENTER);
        speakButton = new Button(this);
        speakButton.setText("🔊");
        speakButton.setEnabled(false);

        header.addView(backButton, new LinearLayout.LayoutParams(0, -2, 0.8f));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 2f));
        header.addView(speakButton, new LinearLayout.LayoutParams(0, -2, 0.6f));
        root.addView(header);

        TextView privacy = new TextView(this);
        privacy.setText("فقط متن سؤال و زمینه کوتاه دیالوگ برای Gemini می‌رود؛ ویدئو و صدا ارسال نمی‌شوند.");
        privacy.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.addView(privacy);

        chatScroll = new ScrollView(this);
        chatView = new TextView(this);
        chatView.setTextSize(16f);
        chatView.setTextIsSelectable(true);
        chatView.setPadding(dp(8), dp(8), dp(8), dp(8));
        chatScroll.addView(chatView);
        root.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        statusView = new TextView(this);
        statusView.setText("در حال اتصال به Tutor...");
        statusView.setTextDirection(View.TEXT_DIRECTION_RTL);
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
        sendButton = new Button(this);
        sendButton.setText("ارسال ➤");
        actions.addView(micButton, new LinearLayout.LayoutParams(0, -2, 1f));
        actions.addView(sendButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(actions);

        setContentView(root);

        backButton.setOnClickListener(v -> finish());
        speakButton.setOnClickListener(v -> speak(lastAnswer));
        micButton.setOnClickListener(v -> startSpeechQuestion());
        sendButton.setOnClickListener(v -> sendQuestion());
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

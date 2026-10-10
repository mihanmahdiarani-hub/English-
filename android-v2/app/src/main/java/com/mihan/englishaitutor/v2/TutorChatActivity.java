package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.WindowCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full-screen, written Gemini conversation bound to ONE current dialogue.
 *
 * No preceding/following movie dialogue is read, displayed or passed to AI.
 * Entire movie/video/audio remain on the device. Only current selected line,
 * learner's messages and short prior chat turns are sent to Gemini.
 */
public final class TutorChatActivity extends Activity {
    private static final int RECOGNIZE_QUESTION = 3101;
    private static final String STATE_HISTORY = "chat_history";
    private static final String STATE_VISIBLE_ROLE = "chat_visible_role";
    private static final String STATE_VISIBLE_TEXT = "chat_visible_text";

    private GeminiLessonService gemini;
    private TextView statusView;
    private EditText questionInput;
    private Button sendButton;
    private Button micButton;
    private ScrollView chatScroll;
    private LinearLayout messageList;
    private LinearLayout root;

    private String currentDialogue = "";
    private final ArrayList<String> chatHistory = new ArrayList<>();
    private final ArrayList<String> visibleRole = new ArrayList<>();
    private final ArrayList<String> visibleText = new ArrayList<>();
    private boolean requestInFlight = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.init(this);
        Intent source = getIntent();
        if (source != null) {
            String line = source.getStringExtra("current_dialogue");
            currentDialogue = line == null ? "" : line.trim();
        }

        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        buildUi();

        if (savedInstanceState != null) {
            ArrayList<String> history = savedInstanceState.getStringArrayList(STATE_HISTORY);
            ArrayList<String> roles = savedInstanceState.getStringArrayList(STATE_VISIBLE_ROLE);
            ArrayList<String> texts = savedInstanceState.getStringArrayList(STATE_VISIBLE_TEXT);
            if (history != null) chatHistory.addAll(history);
            if (roles != null && texts != null) {
                int count = Math.min(roles.size(), texts.size());
                for (int i = 0; i < count; i++) showMessage(roles.get(i), texts.get(i), false);
            }
        }
        if (visibleText.isEmpty() && !currentDialogue.isEmpty()) {
            showMessage("context", currentDialogue, true);
        }
        initGemini();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag("aurora-chat");
        final int side = dp(7);
        root.setPadding(side, dp(4), side, dp(4));
        // Both keyboard height and system nav are handled as window insets.
        // Fixed composer remains above the visible IME on Android 15/16.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard =
                        insets.getInsets(WindowInsets.Type.ime());
                int bottomInset = Math.max(bars.bottom,
                        insets.isVisible(WindowInsets.Type.ime()) ? keyboard.bottom : 0);
                view.setPadding(side + bars.left, bars.top + dp(3),
                        side + bars.right, bottomInset + dp(3));
                if (insets.isVisible(WindowInsets.Type.ime()) && chatScroll != null)
                    chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
                return insets;
            });
        } else root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setTag("aurora-strip");
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(19f);
        back.setContentDescription("بازگشت به فیلم");
        back.setTag("aurora-tool");
        back.setMinWidth(0);
        back.setMinimumWidth(0);
        back.setPadding(0, 0, 0, 0);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(39)));

        TextView title = new TextView(this);
        title.setText("Gemini");
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTag("aurora-title");
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(39), 1f));
        header.addView(AuroraUi.appearanceButton(this, root),
                new LinearLayout.LayoutParams(dp(40), dp(39)));

        chatScroll = new ScrollView(this);
        chatScroll.setTag("aurora-chat");
        chatScroll.setFillViewport(true);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setPadding(dp(3), dp(12), dp(3), dp(12));
        messageList.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        chatScroll.addView(messageList,
                new ScrollView.LayoutParams(-1, -2));

        statusView = new TextView(this);
        statusView.setTextDirection(View.TEXT_DIRECTION_RTL);

        questionInput = new EditText(this);
        questionInput.setSingleLine(false);
        questionInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        questionInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        questionInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendQuestion();
                return true;
            }
            return false;
        });
        micButton = new Button(this);
        sendButton = new Button(this);

        AuroraChatScreen.mount(this, root, header, chatScroll,
                statusView, questionInput, micButton, sendButton);
        AuroraUi.apply(this, root);
        setContentView(root);
        root.requestFocus();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            root.requestApplyInsets();

        micButton.setOnClickListener(v -> startSpeechQuestion());
        sendButton.setOnClickListener(v -> sendQuestion());
    }

    private void initGemini() {
        gemini = new GeminiLessonService(this);
        boolean ready = GeminiConnection.configure(this, gemini);
        sendButton.setEnabled(ready);
        micButton.setEnabled(ready);
        if (!ready) {
            statusView.setText("Gemini وصل نیست؛ اتصال را از صفحه فیلم تنظیم کن.");
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }
        Diagnostics.log("TUTOR_CHAT", "configured=" + ready
                + " currentChars=" + currentDialogue.length()
                + " adjacentDialoguePassed=false");
    }

    private void sendQuestion() {
        if (requestInFlight || gemini == null || !gemini.isConfigured()) return;
        String question = questionInput.getText().toString().trim();
        if (question.isEmpty()) return;
        showMessage("user", question, true);
        questionInput.setText("");
        requestInFlight = true;
        sendButton.setEnabled(false);
        micButton.setEnabled(false);
        statusView.setText("Gemini در حال پاسخ دادن...");
        statusView.setVisibility(View.VISIBLE);

        List<String> requestHistory = new ArrayList<>(chatHistory);
        // Explicitly empty prior-dialogue context: not even a hidden line
        // from the previous or following subtitle reaches Gemini Chat.
        gemini.askTutor(question, currentDialogue, Collections.emptyList(),
                requestHistory, new GeminiLessonService.ChatCallback() {
                    @Override public void onSuccess(String answer) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            String written = answer == null ? "" : answer.trim();
                            showMessage("gemini", written, true);
                            chatHistory.add("User: " + question);
                            chatHistory.add("Tutor: " + written);
                            while (chatHistory.size() > 12) chatHistory.remove(0);
                            finishRequest();
                        });
                    }
                    @Override public void onError(String error) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            showMessage("error", "خطای Gemini: " + error, true);
                            finishRequest();
                        });
                    }
                });
    }

    private void finishRequest() {
        requestInFlight = false;
        sendButton.setEnabled(gemini != null && gemini.isConfigured());
        micButton.setEnabled(gemini != null && gemini.isConfigured());
        statusView.setVisibility(View.GONE);
    }

    /**
     * Each turn is a separate selectable bubble, like a normal chat; there
     * is no giant joined text block that grows over the composer.
     */
    private void showMessage(String role, String message, boolean store) {
        String clean = message == null ? "" : message.trim();
        if (clean.isEmpty()) return;
        if (store) {
            // Bound saved instance state to avoid Android Binder size limits.
            if (visibleText.size() >= 90) {
                visibleText.remove(0);
                visibleRole.remove(0);
            }
            visibleRole.add(role);
            visibleText.add(clean);
        } else {
            visibleRole.add(role);
            visibleText.add(clean);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        boolean fromLearner = "user".equals(role);
        row.setGravity(fromLearner ? Gravity.RIGHT : Gravity.LEFT);
        row.setPadding(dp(6), dp(3), dp(6), dp(5));

        TextView bubble = new TextView(this);
        bubble.setTextIsSelectable(true);
        bubble.setTextSize(16f);
        bubble.setLineSpacing(dp(3), 1f);
        bubble.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(65));
        bubble.setPadding(dp(13), dp(10), dp(13), dp(10));
        bubble.setTag(fromLearner ? "aurora-highlight" : "aurora-card");
        if ("context".equals(role)) {
            bubble.setText("🎬 دیالوگ جاری\n" + clean);
        } else if ("gemini".equals(role)) {
            bubble.setText("✦ Gemini\n" + clean);
        } else {
            bubble.setText(clean);
        }
        row.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        messageList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        AuroraUi.apply(this, root);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
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
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "ورودی صوتی روی این گوشی در دسترس نیست.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int code, int status, Intent data) {
        super.onActivityResult(code, status, data);
        if (code != RECOGNIZE_QUESTION || status != RESULT_OK || data == null) return;
        ArrayList<String> spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (spoken == null || spoken.isEmpty()) return;
        questionInput.setText(spoken.get(0));
        questionInput.setSelection(questionInput.length());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putStringArrayList(STATE_HISTORY, new ArrayList<>(chatHistory));
        // Retain current scrollable conversation across rotation/config change.
        int start = Math.max(0, visibleRole.size() - 90);
        state.putStringArrayList(STATE_VISIBLE_ROLE,
                new ArrayList<>(visibleRole.subList(start, visibleRole.size())));
        state.putStringArrayList(STATE_VISIBLE_TEXT,
                new ArrayList<>(visibleText.subList(start, visibleText.size())));
    }

    @Override protected void onResume() {
        super.onResume();
        AuroraUi.apply(this, root);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}

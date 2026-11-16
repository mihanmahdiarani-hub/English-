package com.mihan.englishaitutor.v2;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * User-controlled, foreground-only English tutoring over Gemini Live.
 * One tap starts both the network session and (after runtime permission)
 * microphone capture; Stop, Back and leaving the screen shut them down.
 */
public final class GeminiLiveActivity extends Activity {
    private static final int MICROPHONE_PERMISSION_REQUEST = 3401;
    private static final long MAX_SESSION_MS = 10L * 60L * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder transcript = new StringBuilder();
    private GeminiLiveSession session;
    private TextView status;
    private TextView transcriptView;
    private Button startButton;
    private Button stopButton;
    private Button micButton;
    private Spinner modelPicker;
    private Spinner voicePicker;
    private ScrollView transcriptScroll;
    private String currentDialogue = "";
    private String lastSpeaker = "";
    private long sessionSerial;
    private final Runnable sessionTimeout = () -> {
        if (session != null && session.isActive()) {
            session.stop();
            status.setText("زمان ۱۰ دقیقه‌ای جلسه تمام شد. برای ادامه، دستی جلسه جدید شروع کن.");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Diagnostics.init(this);
        currentDialogue = getIntent().getStringExtra("current_dialogue");
        if (currentDialogue == null) currentDialogue = "";
        if (currentDialogue.length() > 800)
            currentDialogue = currentDialogue.substring(0, 800);
        setTitle("Gemini Live — English AI Tutor");
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        Button back = new Button(this);
        back.setText("بازگشت");
        back.setTag("aurora-tool");
        back.setOnClickListener(v -> finish());
        heading.addView(back, new LinearLayout.LayoutParams(-2, dp(44)));

        TextView title = new TextView(this);
        title.setText("🎙 مکالمه زنده Gemini");
        title.setTextSize(19);
        title.setGravity(Gravity.CENTER);
        title.setTag("aurora-title");
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(heading);

        TextView explanation = new TextView(this);
        explanation.setText("با صدای خودت انگلیسی صحبت کن و جواب را با صدای معلم بشنو. "
                + "می‌توانی وسط پاسخ صحبت کنی. فقط صدای میکروفون و متن دیالوگ انتخابی "
                + "به Google فرستاده می‌شوند؛ صدا روی گوشی ذخیره نمی‌شود.");
        explanation.setTextSize(13);
        explanation.setPadding(0, dp(8), 0, dp(8));
        root.addView(explanation);

        TextView bill = new TextView(this);
        bill.setText("⚠️ شروع جلسه از سهمیه Gemini Live استفاده می‌کند و اگر پروژه API "
                + "دارای Billing باشد، ممکن است هزینه داشته باشد. "
                + "هیچ جلسه‌ای خودکار شروع نمی‌شود؛ سقف هر جلسه ۱۰ دقیقه است.");
        bill.setTextSize(12);
        bill.setPadding(0, dp(4), 0, dp(8));
        root.addView(bill);

        LinearLayout selectors = new LinearLayout(this);
        selectors.setOrientation(LinearLayout.HORIZONTAL);
        selectors.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        modelPicker = new Spinner(this);
        modelPicker.setContentDescription("مدل Gemini Live");
        modelPicker.setAdapter(adapter(new String[]{
                "Gemini 3.8 Live", "Gemini 3.1 Flash Live"
        }));
        voicePicker = new Spinner(this);
        voicePicker.setContentDescription("صدای معلم");
        voicePicker.setAdapter(adapter(new String[]{
                "Kore", "Aoede", "Puck", "Charon"
        }));
        selectors.addView(modelPicker, new LinearLayout.LayoutParams(0, dp(52), 2f));
        selectors.addView(voicePicker, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(selectors);

        status = new TextView(this);
        status.setText("آماده. برای مکالمه روی «شروع» بزن.");
        status.setTextSize(14);
        status.setTag("aurora-body");
        status.setPadding(dp(8), dp(9), dp(8), dp(9));
        root.addView(status);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        startButton = new Button(this);
        startButton.setText("🎙 شروع مکالمه");
        startButton.setTag("aurora-primary");
        stopButton = new Button(this);
        stopButton.setText("■ پایان");
        stopButton.setTag("aurora-tool");
        stopButton.setEnabled(false);
        micButton = new Button(this);
        micButton.setText("🎤 میکروفون روشن");
        micButton.setTag("aurora-secondary");
        micButton.setEnabled(false);
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1.25f));
        actions.addView(stopButton, new LinearLayout.LayoutParams(0, dp(52), .8f));
        actions.addView(micButton, new LinearLayout.LayoutParams(0, dp(52), 1.2f));
        root.addView(actions);

        TextView transcriptLabel = new TextView(this);
        transcriptLabel.setText("متن هم‌زمان گفت‌وگو (فقط همین جلسه)");
        transcriptLabel.setTextSize(13);
        transcriptLabel.setPadding(0, dp(12), 0, dp(4));
        root.addView(transcriptLabel);

        transcriptScroll = new ScrollView(this);
        transcriptScroll.setFillViewport(true);
        transcriptScroll.setTag("aurora-chat");
        transcriptView = new TextView(this);
        transcriptView.setTextIsSelectable(true);
        transcriptView.setTextSize(16);
        transcriptView.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        transcriptView.setPadding(dp(12), dp(12), dp(12), dp(12));
        transcriptView.setText("هنوز مکالمه‌ای شروع نشده است.");
        transcriptScroll.addView(transcriptView);
        root.addView(transcriptScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        startButton.setOnClickListener(v -> requestStart());
        stopButton.setOnClickListener(v -> stopConversation());
        micButton.setOnClickListener(v -> {
            GeminiLiveSession current = session;
            if (current == null || !current.isActive()) return;
            boolean enabled = !current.isMicEnabled();
            current.setMicEnabled(enabled);
            micButton.setText(enabled ? "🎤 میکروفون روشن" : "🔇 میکروفون قطع");
        });

        AuroraUi.apply(this, root);
        setContentView(root);
    }

    private ArrayAdapter<String> adapter(String[] values) {
        ArrayAdapter<String> result =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        result.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return result;
    }

    private void requestStart() {
        if (session != null && session.isActive()) return;
        if (!new GeminiDirectClient(this).hasKey()) {
            status.setText("کلید Gemini پیدا نشد؛ به صفحه فیلم برگرد و «✨ کلید Gemini» را تنظیم کن.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    MICROPHONE_PERMISSION_REQUEST);
            return;
        }
        startConversation();
    }

    @Override public void onRequestPermissionsResult(int code,
                                                       String[] permissions,
                                                       int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != MICROPHONE_PERMISSION_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            startConversation();
        else status.setText("برای مکالمه Live باید مجوز میکروفون را بدهی.");
    }

    private void startConversation() {
        if (session != null && session.isActive()) return;
        final long serial = ++sessionSerial;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        micButton.setEnabled(false);
        modelPicker.setEnabled(false);
        voicePicker.setEnabled(false);
        lastSpeaker = "";
        transcript.setLength(0);
        transcriptView.setText("در حال شروع جلسه...");
        status.setText("در حال اتصال...");
        String model = modelPicker.getSelectedItemPosition() == 1
                ? GeminiLiveSession.MODEL_FALLBACK
                : GeminiLiveSession.MODEL_DEFAULT;
        String voice = (String) voicePicker.getSelectedItem();
        GeminiLiveSession newSession = new GeminiLiveSession(this,
                new GeminiLiveSession.Listener() {
                    @Override public void onReady() {
                        if (serial != sessionSerial) return;
                        micButton.setEnabled(true);
                        status.setText("اتصال برقرار شد؛ صحبت کن.");
                    }

                    @Override public void onStatus(String message) {
                        if (serial == sessionSerial) status.setText(message);
                    }

                    @Override public void onTranscript(String speaker, String text) {
                        if (serial == sessionSerial) appendTranscript(speaker, text);
                    }

                    @Override public void onStopped(String reason) {
                        if (serial != sessionSerial) return;
                        main.removeCallbacks(sessionTimeout);
                        startButton.setEnabled(true);
                        stopButton.setEnabled(false);
                        micButton.setEnabled(false);
                        micButton.setText("🎤 میکروفون روشن");
                        modelPicker.setEnabled(true);
                        voicePicker.setEnabled(true);
                        status.setText(reason);
                    }
                });
        session = newSession;
        main.postDelayed(sessionTimeout, MAX_SESSION_MS);
        newSession.start(model, voice, currentDialogue);
        Diagnostics.log("GEMINI_LIVE", "start model="
                + (model.equals(GeminiLiveSession.MODEL_DEFAULT) ? "3.8" : "3.1")
                + " voice preset selected; key never logged");
    }

    private void appendTranscript(String speaker, String text) {
        if (TextUtils.isEmpty(text)) return;
        if (!speaker.equals(lastSpeaker)) {
            transcript.append("\n\n").append(speaker).append(": ");
            lastSpeaker = speaker;
        }
        transcript.append(text.trim()).append(" ");
        // Avoid retaining a long-term speech history in memory.
        if (transcript.length() > 18000)
            transcript.delete(0, transcript.length() - 15000);
        transcriptView.setText(transcript.toString().trim());
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void stopConversation() {
        main.removeCallbacks(sessionTimeout);
        GeminiLiveSession current = session;
        if (current != null) current.stop();
        // The session listener resets controls; no background reconnect.
    }

    @Override protected void onStop() {
        stopConversation(); // no microphone access when the screen is hidden
        super.onStop();
    }

    @Override protected void onDestroy() {
        stopConversation();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

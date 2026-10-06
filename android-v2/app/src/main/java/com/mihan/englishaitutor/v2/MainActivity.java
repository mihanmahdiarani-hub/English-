package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 2001;
    private static final int PICK_SUBTITLE = 2002;
    private static final long TICK_MS = 60L;

    private enum Mode { SMART, AUTO, WATCH }

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView statusView;
    private TextView dialogueView;
    private TextView lessonView;
    private Button continueButton;
    private Button smartButton;
    private Button autoButton;
    private Button watchButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Dialogue> dialogues = new ArrayList<>();
    private Mode mode = Mode.SMART;
    private int activeDialogueIndex = -1;
    private int lastPausedIndex = -1;

    private final Runnable playbackTick = new Runnable() {
        @Override public void run() {
            try { syncDialogueWithPlayer(); }
            finally { handler.postDelayed(this, TICK_MS); }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                          Player.PositionInfo newPosition,
                                                          int reason) {
                activeDialogueIndex = findDialogueForPosition(newPosition.positionMs);
                if (lastPausedIndex >= 0 && newPosition.positionMs < dialogues.get(lastPausedIndex).startMs) {
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
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("English AI Tutor v2 — Local-first");
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        Button videoButton = new Button(this);
        videoButton.setText("انتخاب فیلم");
        Button subtitleButton = new Button(this);
        subtitleButton.setText("انتخاب SRT");
        sourceRow.addView(videoButton, new LinearLayout.LayoutParams(0, -2, 1f));
        sourceRow.addView(subtitleButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(sourceRow);

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
        lessonBox.setPadding(dp(12), dp(10), dp(12), dp(10));

        dialogueView = new TextView(this);
        dialogueView.setTextSize(18f);
        dialogueView.setText("دیالوگ فعلی اینجا نمایش داده می‌شود.");
        lessonBox.addView(dialogueView);

        lessonView = new TextView(this);
        lessonView.setTextSize(15f);
        lessonView.setText("در alpha-1 هسته پخش و Pause را تست می‌کنیم. تحلیل فارسی AI در مرحله بعد به همین Dialogue ID وصل می‌شود.");
        lessonBox.addView(lessonView);

        continueButton = new Button(this);
        continueButton.setText("▶ ادامه فیلم");
        continueButton.setVisibility(View.GONE);
        lessonBox.addView(continueButton);
        lessonScroll.addView(lessonBox);
        root.addView(lessonScroll, new LinearLayout.LayoutParams(-1, dp(190)));

        statusView = new TextView(this);
        statusView.setText("اول فیلم را انتخاب کن. برای تست دقیق Pause می‌توانی SRT همان فیلم را هم انتخاب کنی.");
        root.addView(statusView);

        setContentView(root);

        videoButton.setOnClickListener(v -> pickVideo());
        subtitleButton.setOnClickListener(v -> pickSubtitle());
        smartButton.setOnClickListener(v -> selectMode(Mode.SMART));
        autoButton.setOnClickListener(v -> selectMode(Mode.AUTO));
        watchButton.setOnClickListener(v -> selectMode(Mode.WATCH));
        continueButton.setOnClickListener(v -> {
            continueButton.setVisibility(View.GONE);
            if (player != null) player.play();
        });
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_VIDEO);
    }

    private void pickSubtitle() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-subrip", "text/plain", "text/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_SUBTITLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        persistReadPermission(uri, data);

        if (requestCode == PICK_VIDEO) {
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();
            statusView.setText("فیلم فقط از حافظه گوشی پخش می‌شود. حالا SRT را انتخاب کن یا بعداً Transcript محلی را فعال می‌کنیم.");
            return;
        }

        if (requestCode == PICK_SUBTITLE) {
            try {
                List<Dialogue> parsed = parseSrt(uri);
                dialogues.clear();
                dialogues.addAll(parsed);
                activeDialogueIndex = -1;
                lastPausedIndex = -1;
                statusView.setText(String.format(Locale.US, "%d دیالوگ زمان‌دار آماده شد. Mode=%s", dialogues.size(), mode));
                Toast.makeText(this, "Subtitle آماده شد", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                statusView.setText("خواندن SRT شکست خورد: " + e.getMessage());
            }
        }
    }

    private void persistReadPermission(Uri uri, Intent data) {
        try {
            int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {}
    }

    private void selectMode(Mode newMode) {
        mode = newMode;
        smartButton.setEnabled(newMode != Mode.SMART);
        autoButton.setEnabled(newMode != Mode.AUTO);
        watchButton.setEnabled(newMode != Mode.WATCH);
        statusView.setText("Mode: " + newMode + (newMode == Mode.SMART
                ? " — فعلاً با heuristic ساده؛ بعداً teachingScore از AI می‌آید."
                : ""));
    }

    private void syncDialogueWithPlayer() {
        if (player == null || dialogues.isEmpty()) return;
        long position = player.getCurrentPosition();
        int index = findDialogueForPosition(position);
        if (index >= 0 && index != activeDialogueIndex) {
            activeDialogueIndex = index;
            dialogueView.setText(dialogues.get(index).text);
        }

        if (!player.isPlaying() || mode == Mode.WATCH || index < 0 || index == lastPausedIndex) return;
        Dialogue d = dialogues.get(index);
        long pauseAt = d.endMs + 120L;
        if (position >= pauseAt) return;
        if (pauseAt - position <= 90L && shouldTeach(d)) {
            lastPausedIndex = index;
            player.pause();
            showTeachingUnit(d, index);
        }
    }

    private boolean shouldTeach(Dialogue d) {
        if (mode == Mode.AUTO) return true;
        if (mode == Mode.WATCH) return false;
        String normalized = d.text == null ? "" : d.text.trim();
        if (normalized.isEmpty()) return false;
        int words = normalized.split("\\s+").length;
        return words >= 4 || normalized.contains("?") || normalized.contains("!");
    }

    private void showTeachingUnit(Dialogue d, int index) {
        dialogueView.setText(d.text);
        lessonView.setText("Teaching Unit #" + (index + 1) + "\n\n"
                + "ترجمه، معنی طبیعی، اصطلاح، گرامر و تلفظ در مرحله AI به این رکورد اضافه می‌شود.\n\n"
                + "start=" + formatMs(d.startMs) + "  end=" + formatMs(d.endMs));
        continueButton.setVisibility(View.VISIBLE);
    }

    private int findDialogueForPosition(long positionMs) {
        for (int i = 0; i < dialogues.size(); i++) {
            Dialogue d = dialogues.get(i);
            if (positionMs >= d.startMs && positionMs <= d.endMs + 250L) return i;
            if (d.startMs > positionMs) break;
        }
        return -1;
    }

    private List<Dialogue> parseSrt(Uri uri) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri)))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }

        List<Dialogue> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) { i++; continue; }
            if (line.matches("\\d+")) { i++; if (i >= lines.size()) break; line = lines.get(i).trim(); }
            if (!line.contains("-->")) { i++; continue; }

            String[] times = line.split("-->");
            if (times.length != 2) { i++; continue; }
            long start = parseSrtTime(times[0].trim());
            long end = parseSrtTime(times[1].trim().split("\\s+")[0]);
            i++;

            StringBuilder text = new StringBuilder();
            while (i < lines.size() && !lines.get(i).trim().isEmpty()) {
                if (text.length() > 0) text.append(' ');
                text.append(lines.get(i).replaceAll("<[^>]+>", "").trim());
                i++;
            }
            if (end > start && text.length() > 0) out.add(new Dialogue(start, end, text.toString()));
        }
        return out;
    }

    private long parseSrtTime(String value) {
        String clean = value.replace('.', ',');
        String[] hm = clean.split(":");
        if (hm.length != 3) throw new IllegalArgumentException("زمان SRT نامعتبر: " + value);
        String[] sm = hm[2].split(",");
        long hours = Long.parseLong(hm[0]);
        long minutes = Long.parseLong(hm[1]);
        long seconds = Long.parseLong(sm[0]);
        long millis = sm.length > 1 ? Long.parseLong(padMillis(sm[1])) : 0L;
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis;
    }

    private String padMillis(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() >= 3) return digits.substring(0, 3);
        if (digits.length() == 2) return digits + "0";
        if (digits.length() == 1) return digits + "00";
        return "000";
    }

    private String formatMs(long ms) {
        long total = Math.max(0, ms) / 1000L;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(playbackTick);
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

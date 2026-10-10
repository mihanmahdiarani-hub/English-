package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.core.view.WindowCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * English AI Tutor movie vocabulary, NOT a separate app or server.
 *
 * Complete WORDS are built from the local Whisper transcript, even offline.
 * Advanced linguistic categories require Gemini analysis of THAT exact
 * sentence. The learner can independently analyze just the current line or
 * explicitly request a potentially long/quota-limited whole-movie pass.
 *
 * All data stays tied to one exact archived movie and dialogue index.
 */
public final class VocabularyActivity extends Activity {
    private static final int PAGE_SIZE = 120;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<String> lines = new ArrayList<>();
    private final Map<Integer, List<VocabularyIndex.Phrase>> enrichments = new HashMap<>();
    private List<VocabularyIndex.Entry> index = Collections.emptyList();

    private LocalArchiveManager.Archive archive;
    private GeminiLessonService gemini;
    private int selectedDialogue = 0;
    private boolean wholeMovie = false;
    private int visibleLimit = PAGE_SIZE;
    private boolean analysisRunning = false;
    private int analysisGeneration = 0;

    private LinearLayout root;
    private Button currentButton;
    private Button movieButton;
    private Button currentAnalyzeButton;
    private Button movieAnalyzeButton;
    private Button moreButton;
    private TextView status;
    private TextView summary;
    private TextView results;
    private EditText search;
    private Spinner categorySpinner;
    private VocabularyIndex.Category selectedCategory = VocabularyIndex.Category.WORD;
    private boolean updatingSpinner = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.init(this);
        selectedDialogue = Math.max(0, getIntent().getIntExtra("dialogue_index", 0));
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        gemini = new GeminiLessonService(this);
        boolean connected = GeminiConnection.configure(this, gemini);
        buildUi();
        status.setText(connected
                ? "در حال خواندن واژگان فیلم از آرشیو..."
                : "متن فیلم به‌صورت آفلاین در دسترس است؛ دسته‌بندی تخصصی نیاز به اتصال Gemini دارد.");
        final String archiveId = getIntent().getStringExtra("archive_id");
        io.execute(() -> {
            try {
                LocalArchiveManager.Archive found =
                        LocalArchiveManager.openPreparedArchive(this, archiveId);
                if (found == null) throw new IllegalStateException(
                        "آرشیو همین فیلم پیدا نشد؛ اول فیلم را آماده کن.");
                List<LocalArchiveManager.TranscriptRow> transcript =
                        LocalArchiveManager.loadTranscript(found);
                ArrayList<String> restored = new ArrayList<>();
                Map<Integer, List<VocabularyIndex.Phrase>> existing = new HashMap<>();
                for (LocalArchiveManager.TranscriptRow row : transcript) {
                    restored.add(row.text);
                }
                for (int i = 0; i < restored.size(); i++) {
                    GeminiLessonService.Lesson cached =
                            gemini.getCached(restored.get(i), previous(restored, i));
                    if (cached != null && !cached.lexicalItems.isEmpty()) {
                        existing.put(i, cached.lexicalItems);
                    }
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    archive = found;
                    lines.clear();
                    lines.addAll(restored);
                    selectedDialogue = Math.min(selectedDialogue, Math.max(0, lines.size() - 1));
                    enrichments.clear();
                    enrichments.putAll(existing);
                    currentButton.setEnabled(!lines.isEmpty());
                    movieButton.setEnabled(!lines.isEmpty());
                    currentAnalyzeButton.setEnabled(!lines.isEmpty() && gemini.isConfigured());
                    movieAnalyzeButton.setEnabled(!lines.isEmpty() && gemini.isConfigured());
                    status.setText("آرشیو: " + found.title + " • "
                            + restored.size() + " دیالوگ"
                            + (gemini.isConfigured()
                            ? " • Gemini برای دسته‌بندی آماده است"
                            : " • دسته‌بندی تخصصی هنوز در دسترس نیست"));
                    refreshIndex();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("خطا: " + error.getMessage()));
            }
        });
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setTag("aurora-chat");
        final int horizontal = dp(8);
        root.setPadding(horizontal, dp(6), horizontal, dp(6));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(horizontal + bars.left, bars.top + dp(4),
                        horizontal + bars.right,
                        Math.max(bars.bottom, insets.isVisible(WindowInsets.Type.ime())
                                ? ime.bottom : 0) + dp(4));
                return insets;
            });
        } else root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        Button back = iconButton("‹", "بازگشت به فیلم");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(39)));
        TextView title = new TextView(this);
        title.setText("📚 Vocabulary");
        title.setTag("aurora-title");
        title.setTextSize(18f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(39), 1f));
        header.addView(AuroraUi.appearanceButton(this, root),
                new LinearLayout.LayoutParams(dp(40), dp(39)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout scopes = new LinearLayout(this);
        scopes.setOrientation(LinearLayout.HORIZONTAL);
        currentButton = new Button(this);
        currentButton.setTag("aurora-chip");
        currentButton.setText("همین دیالوگ");
        currentButton.setEnabled(false);
        movieButton = new Button(this);
        movieButton.setTag("aurora-chip");
        movieButton.setText("کل فیلم");
        movieButton.setEnabled(false);
        scopes.addView(currentButton, new LinearLayout.LayoutParams(0, dp(43), 1f));
        scopes.addView(movieButton, new LinearLayout.LayoutParams(0, dp(43), 1f));
        root.addView(scopes);

        currentButton.setOnClickListener(v -> {
            wholeMovie = false;
            visibleLimit = PAGE_SIZE;
            refreshIndex();
        });
        movieButton.setOnClickListener(v -> {
            wholeMovie = true;
            visibleLimit = PAGE_SIZE;
            refreshIndex();
        });

        summary = new TextView(this);
        summary.setTextSize(12f);
        summary.setTag("aurora-muted");
        summary.setPadding(dp(5), dp(4), dp(5), dp(4));
        root.addView(summary);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        categorySpinner = new Spinner(this);
        ArrayList<String> options = new ArrayList<>();
        for (VocabularyIndex.Category category : VocabularyIndex.Category.values())
            options.add(category.title());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categorySpinner.setAdapter(adapter);
        filters.addView(categorySpinner, new LinearLayout.LayoutParams(0, dp(49), 1f));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14f);
        search.setHint("جستجو");
        search.setPadding(dp(8), 0, dp(8), 0);
        filters.addView(search, new LinearLayout.LayoutParams(dp(118), dp(45)));
        root.addView(filters);

        categorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingSpinner) return;
                selectedCategory = VocabularyIndex.Category.values()[position];
                visibleLimit = PAGE_SIZE;
                render();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                visibleLimit = PAGE_SIZE;
                render();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        results = new TextView(this);
        results.setTextSize(15f);
        results.setTextIsSelectable(true);
        results.setPadding(dp(10), dp(9), dp(10), dp(9));
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        moreButton = new Button(this);
        moreButton.setText("نمایش لغات بیشتر ↓");
        moreButton.setTag("aurora-secondary");
        moreButton.setVisibility(View.GONE);
        moreButton.setOnClickListener(v -> {
            visibleLimit += PAGE_SIZE;
            render();
        });
        root.addView(moreButton, new LinearLayout.LayoutParams(-1, dp(39)));

        status = new TextView(this);
        status.setTextSize(12f);
        status.setTag("aurora-status");
        status.setMaxLines(3);
        status.setPadding(dp(5), dp(5), dp(5), dp(5));
        root.addView(status);

        LinearLayout analysisButtons = new LinearLayout(this);
        analysisButtons.setOrientation(LinearLayout.HORIZONTAL);
        currentAnalyzeButton = new Button(this);
        currentAnalyzeButton.setText("✨ تحلیل این دیالوگ");
        currentAnalyzeButton.setTag("aurora-primary");
        currentAnalyzeButton.setEnabled(false);
        movieAnalyzeButton = new Button(this);
        movieAnalyzeButton.setText("✨ دسته‌بندی کل فیلم");
        movieAnalyzeButton.setTag("aurora-secondary");
        movieAnalyzeButton.setEnabled(false);
        analysisButtons.addView(currentAnalyzeButton,
                new LinearLayout.LayoutParams(0, dp(47), 1f));
        analysisButtons.addView(movieAnalyzeButton,
                new LinearLayout.LayoutParams(0, dp(47), 1f));
        root.addView(analysisButtons);

        currentAnalyzeButton.setOnClickListener(v -> startAnalysis(false));
        movieAnalyzeButton.setOnClickListener(v -> confirmWholeMovieAnalysis());
        AuroraUi.apply(this, root);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) root.requestApplyInsets();
    }

    private Button iconButton(String symbol, String description) {
        Button button = new Button(this);
        button.setText(symbol);
        button.setTextSize(19f);
        button.setTag("aurora-tool");
        button.setContentDescription(description);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private void refreshIndex() {
        if (lines.isEmpty()) {
            index = Collections.emptyList();
            if (results != null) results.setText("پس از آماده‌شدن فیلم، لغات اینجا نمایش داده می‌شوند.");
            return;
        }
        index = VocabularyIndex.build(lines, enrichments,
                wholeMovie ? -1 : selectedDialogue);
        Map<VocabularyIndex.Category, Integer> counts = VocabularyIndex.counts(index);
        updatingSpinner = true;
        ArrayList<String> items = new ArrayList<>();
        for (VocabularyIndex.Category category : VocabularyIndex.Category.values()) {
            items.add(category.title() + " (" + counts.get(category) + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categorySpinner.setAdapter(adapter);
        categorySpinner.setSelection(selectedCategory.ordinal());
        updatingSpinner = false;

        int specialized = index.size() - counts.get(VocabularyIndex.Category.WORD);
        summary.setText((wholeMovie
                ? "کل فیلم • " + lines.size() + " دیالوگ"
                : "دیالوگ " + (selectedDialogue + 1) + ": " + lines.get(selectedDialogue))
                + "\n" + counts.get(VocabularyIndex.Category.WORD)
                + " واژه متفاوت • " + specialized
                + " مدخل تخصصی تأییدشده"
                + (gemini != null && !gemini.isConfigured()
                ? " • بدون معنی Gemini" : ""));
        currentButton.setEnabled(wholeMovie);
        movieButton.setEnabled(!wholeMovie);
        render();
    }

    private void render() {
        if (results == null || index == null) return;
        List<VocabularyIndex.Entry> list = VocabularyIndex.filter(
                index, selectedCategory,
                search == null ? "" : search.getText().toString());
        StringBuilder output = new StringBuilder();
        int max = Math.min(visibleLimit, list.size());
        for (int i = 0; i < max; i++) {
            VocabularyIndex.Entry entry = list.get(i);
            if (i > 0) output.append("\n\n");
            output.append(i + 1).append(". ").append(entry.term);
            if (!entry.meaningFa.isEmpty())
                output.append(" — ").append(entry.meaningFa);
            if (!entry.note.isEmpty())
                output.append("\n   ").append(entry.note);
            if (wholeMovie)
                output.append("\n   دیالوگ ").append(entry.firstDialogue + 1)
                        .append(" • تکرار ").append(entry.occurrences);
        }
        if (list.isEmpty()) {
            output.append(selectedCategory == VocabularyIndex.Category.WORD
                    ? "لغتی مطابق جستجو پیدا نشد."
                    : "فعلاً مورد تأییدشده‌ای در این دسته نیست. "
                    + "برای بررسی دقیق‌تر، تحلیل Gemini را اجرا کن. "
                    + "هیچ اصطلاحی بدون وجود در متن فیلم ساخته نمی‌شود.");
        }
        results.setText(output);
        moreButton.setVisibility(max < list.size() ? View.VISIBLE : View.GONE);
        moreButton.setText("نمایش بیشتر (" + max + " از " + list.size() + ")");
    }

    private static List<String> previous(List<String> content, int index) {
        ArrayList<String> items = new ArrayList<>();
        for (int j = Math.max(0, index - 3); j < index; j++) items.add(content.get(j));
        return items;
    }

    private void confirmWholeMovieAnalysis() {
        if (lines.isEmpty() || analysisRunning) return;
        new AlertDialog.Builder(this)
                .setTitle("دسته‌بندی تخصصی کل فیلم")
                .setMessage("برای بررسی اصطلاحات تمام " + lines.size()
                        + " دیالوگ، Gemini باید بخش‌های بیشتری را تحلیل کند. "
                        + "این کار می‌تواند طول بکشد و از سهمیه درخواست Gemini "
                        + "استفاده کند؛ لغات خام فیلم از قبل در دسترس‌اند. ادامه بدهیم؟")
                .setNegativeButton("انصراف", (d, w) -> {})
                .setPositiveButton("شروع تحلیل", (d, w) -> startAnalysis(true))
                .show();
    }

    private void startAnalysis(boolean all) {
        if (lines.isEmpty() || analysisRunning) return;
        if (!gemini.isConfigured()) {
            Toast.makeText(this,
                    "Gemini متصل نیست؛ فهرست کامل کلمات فیلم بدون Gemini قابل مشاهده است.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        analysisRunning = true;
        int generation = ++analysisGeneration;
        currentAnalyzeButton.setEnabled(false);
        movieAnalyzeButton.setEnabled(false);
        int start = all ? 0 : selectedDialogue;
        int end = all ? lines.size() : selectedDialogue + 1;
        analyzeNext(generation, start, end);
    }

    private void analyzeNext(int generation, int cursor, int end) {
        if (generation != analysisGeneration || isFinishing() || isDestroyed()) return;
        int index = cursor;
        while (index < end
                && gemini.getCached(lines.get(index), previous(lines, index)) != null) {
            GeminiLessonService.Lesson item =
                    gemini.getCached(lines.get(index), previous(lines, index));
            if (item != null && !item.lexicalItems.isEmpty())
                enrichments.put(index, item.lexicalItems);
            index++;
        }
        if (index >= end) {
            analysisRunning = false;
            currentAnalyzeButton.setEnabled(true);
            movieAnalyzeButton.setEnabled(true);
            status.setText("✅ تحلیل تمام شد • " + end
                    + " / " + lines.size()
                    + " دیالوگ بررسی شد. نتیجه دسته‌بندی در کش فیلم می‌ماند.");
            refreshIndex();
            return;
        }

        // Never silently schedule requests for the entire movie at once.
        // A maximum of four uncached dialogue items per batch, with the next
        // request started only AFTER the current Gemini result arrives.
        final int batchStart = index;
        List<GeminiLessonService.LessonInput> batch = new ArrayList<>();
        int next = index;
        while (next < end && batch.size() < 4) {
            if (gemini.getCached(lines.get(next), previous(lines, next)) != null) break;
            batch.add(new GeminiLessonService.LessonInput(
                    lines.get(next), previous(lines, next)));
            next++;
        }
        if (batch.isEmpty()) {
            analyzeNext(generation, index + 1, end);
            return;
        }
        final int batchEnd = next;
        status.setText("✨ Gemini: دسته‌بندی دیالوگ "
                + (batchStart + 1) + " تا " + batchEnd + " از " + end
                + " • امکان محدودیت سهمیه وجود دارد");
        gemini.analyzeBatch(batch, new GeminiLessonService.Callback() {
            @Override public void onSuccess(GeminiLessonService.Lesson lesson) {
                runOnUiThread(() -> {
                    if (generation != analysisGeneration || isFinishing() || isDestroyed()) return;
                    for (int i = batchStart; i < batchEnd; i++) {
                        GeminiLessonService.Lesson cached =
                                gemini.getCached(lines.get(i), previous(lines, i));
                        if (cached != null && !cached.lexicalItems.isEmpty())
                            enrichments.put(i, cached.lexicalItems);
                    }
                    refreshIndex();
                    // Post to the UI loop to avoid recursive runs when many
                    // lines are already in cache.
                    root.post(() -> analyzeNext(generation, batchEnd, end));
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (generation != analysisGeneration || isFinishing() || isDestroyed()) return;
                    analysisRunning = false;
                    currentAnalyzeButton.setEnabled(true);
                    movieAnalyzeButton.setEnabled(true);
                    refreshIndex();
                    status.setText("تحلیل Gemini متوقف شد نزدیک دیالوگ "
                            + (batchStart + 1) + ": " + message
                            + " • لغات استخراج‌شده محفوظ‌اند؛ بعداً دوباره اجرا کن.");
                });
            }
        });
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override protected void onDestroy() {
        analysisGeneration++;
        io.shutdownNow();
        super.onDestroy();
    }
}

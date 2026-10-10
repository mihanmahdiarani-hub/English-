package com.mihan.englishaitutor.v2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Actual CTC Viterbi forced alignment from Wav2Vec2 acoustic emission logits.
 *
 * No generated/synthetic time boundaries: every accepted word is backed by
 * CTC acoustic frames. The caller retains Whisper timings when the acoustic
 * evidence is weak or text does not match the speech.
 *
 * Model: Xenova/wav2vec2-base-960h, onnx/model_quantized.onnx (English).
 * Vocabulary identifiers are verified against that model's vocab.json.
 */
final class CtcWordAligner {
    static final int BLANK = 0;
    static final int WORD_SEP = 4;
    static final int VOCAB_SIZE = 32;
    // ID -> token from Xenova/wav2vec2-base-960h/vocab.json
    private static final String[] VOCAB = {
            "<pad>", "<s>", "</s>", "<unk>", "|",
            "E", "T", "A", "O", "N", "I", "H", "S", "R",
            "D", "L", "U", "M", "W", "C", "F", "G", "Y",
            "P", "B", "V", "K", "'", "X", "J", "Q", "Z"
    };
    private static final int[] CHAR_TO_ID = new int[128];
    static {
        Arrays.fill(CHAR_TO_ID, -1);
        for (int i = 5; i < VOCAB.length; i++) {
            char c = VOCAB[i].charAt(0);
            CHAR_TO_ID[c] = i;
        }
        CHAR_TO_ID['\''] = 27;
    }

    static final class Word {
        final String text;
        final long startMs;
        final long endMs;
        final double confidence;

        Word(String text, long startMs, long endMs, double confidence) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.confidence = confidence;
        }
    }

    static final class Alignment {
        final List<Word> words;
        final double score;

        Alignment(List<Word> words, double score) {
            this.words = words;
            this.score = score;
        }

        boolean usable() {
            return !words.isEmpty() && score >= 0.10;
        }
    }

    private static final class Tokenized {
        final List<String> words = new ArrayList<>();
        final List<Integer> symbols = new ArrayList<>();
        final List<Integer> tokenToWord = new ArrayList<>();
    }

    private CtcWordAligner() {}

    /** Reject digits, foreign characters, or too-long text instead of guessing. */
    private static Tokenized tokenize(String text) {
        if (text == null || text.isEmpty()) return null;
        String upper = text.toUpperCase(Locale.US);
        // A Whisper text may contain curly quotes, punctuation and hyphens;
        // normalize apostrophes, and treat punctuation as word separators.
        upper = upper.replace('\u2019', '\'')
                .replace('\u2018', '\'');
        upper = upper.replaceAll("[^A-Z'0-9\\\\s]", " ");
        if (!upper.matches("[A-Z'\\s]+")) return null;
        upper = upper.trim().replaceAll("\\s+", " ");
        if (upper.isEmpty() || upper.length() > 160) return null;
        String[] parts = upper.split(" ");
        if (parts.length == 0 || parts.length > 26) return null;
        Tokenized result = new Tokenized();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int wi = result.words.size();
            if (wi > 0) {
                result.symbols.add(WORD_SEP);
                result.tokenToWord.add(-1);
            }
            result.words.add(part);
            for (int j = 0; j < part.length(); j++) {
                char ch = part.charAt(j);
                int token = ch < CHAR_TO_ID.length ? CHAR_TO_ID[ch] : -1;
                if (token < 0) return null;
                result.symbols.add(token);
                result.tokenToWord.add(wi);
            }
        }
        return result;
    }

    /**
     * @param logits acoustic emissions [frames][32], NOT guessed timestamps.
     * @param cropStartMs absolute beginning of source waveform crop.
     * @param cropDurationMs actual crop duration; frame spacing is derived
     *        from tensor output rather than hard-coding 20ms.
     * @return null on low-confidence/incoherent alignment.
     */
    static Alignment align(float[][] logits, String text,
                           long cropStartMs, long cropDurationMs) {
        Tokenized tokenized = tokenize(text);
        if (logits == null || logits.length < 8 || tokenized == null
                || tokenized.words.isEmpty() || cropDurationMs <= 0) return null;
        int tCount = logits.length;
        for (float[] frame : logits) {
            if (frame == null || frame.length != VOCAB_SIZE) return null;
        }
        int kCount = tokenized.symbols.size();
        // CTC demands at least one distinct frame per label and an
        // intervening blank for repeated identical adjacent symbols.
        int repeated = 0;
        for (int k = 1; k < kCount; k++) {
            if (tokenized.symbols.get(k).equals(tokenized.symbols.get(k - 1))) repeated++;
        }
        if (tCount < kCount + repeated + 2) return null;
        if (tCount > 1600 || kCount > 160) return null;

        // Expanded CTC states: blank, char0, blank, char1, ... blank.
        // A skip of the middle blank is valid only when adjacent letters
        // differ (e.g. "HELLO" requires a blank between its two L's).
        int stateCount = 2 * kCount + 1;
        double[] previous = new double[stateCount];
        double[] next = new double[stateCount];
        Arrays.fill(previous, Double.NEGATIVE_INFINITY);
        previous[0] = 0.0;
        int[][] parent = new int[tCount][stateCount];

        for (int t = 0; t < tCount; t++) {
            Arrays.fill(next, Double.NEGATIVE_INFINITY);
            for (int state = 0; state < stateCount; state++) {
                int emitted = state % 2 == 0
                        ? BLANK : tokenized.symbols.get(state / 2);
                double winner = previous[state];
                int chosen = state;
                if (state >= 1 && previous[state - 1] > winner) {
                    winner = previous[state - 1];
                    chosen = state - 1;
                }
                if (state >= 2 && state % 2 == 1
                        && tokenized.symbols.get(state / 2)
                        .intValue() != tokenized.symbols.get((state - 2) / 2)
                        .intValue()
                        && previous[state - 2] > winner) {
                    winner = previous[state - 2];
                    chosen = state - 2;
                }
                if (winner == Double.NEGATIVE_INFINITY) {
                    parent[t][state] = -1;
                    continue;
                }
                next[state] = winner + logits[t][emitted];
                parent[t][state] = chosen;
            }
            double[] old = previous; previous = next; next = old;
        }

        int finalState = stateCount - 1;
        if (previous[stateCount - 2] > previous[finalState]) finalState = stateCount - 2;
        if (!Double.isFinite(previous[finalState])) return null;

        int[] first = new int[kCount], last = new int[kCount];
        double[] strongestPosterior = new double[kCount];
        Arrays.fill(first, -1);
        Arrays.fill(last, -1);
        int state = finalState;
        for (int t = tCount - 1; t >= 0; t--) {
            if (state < 0) return null;
            if (state % 2 == 1) {
                int token = state / 2;
                first[token] = t;
                if (last[token] < 0) last[token] = t;
                int symbol = tokenized.symbols.get(token);
                strongestPosterior[token] = Math.max(
                        strongestPosterior[token],
                        softmaxProbability(logits[t], symbol));
            }
            state = parent[t][state];
        }
        for (int i = 0; i < kCount; i++) if (first[i] < 0 || last[i] < 0) return null;

        List<Word> words = new ArrayList<>();
        int wi = 0;
        int tokenIndex = 0;
        double confidenceSum = 0.0;
        for (String word : tokenized.words) {
            int wordFirst = -1, wordLast = -1;
            double sum = 0.0;
            for (int j = 0; j < word.length(); j++) {
                while (tokenIndex < kCount && tokenized.tokenToWord.get(tokenIndex) == -1) {
                    tokenIndex++;
                }
                if (tokenIndex >= kCount || tokenized.tokenToWord.get(tokenIndex) != wi) return null;
                if (wordFirst < 0) wordFirst = first[tokenIndex];
                wordLast = last[tokenIndex];
                sum += strongestPosterior[tokenIndex];
                tokenIndex++;
            }
            double confidence = sum / word.length();
            long start = cropStartMs + Math.round(
                    wordFirst * (cropDurationMs / (double) tCount));
            long end = cropStartMs + Math.round(
                    (wordLast + 1L) * (cropDurationMs / (double) tCount));
            if (end <= start) return null;
            if (!words.isEmpty() && start < words.get(words.size() - 1).endMs) return null;
            words.add(new Word(word, start, end, confidence));
            confidenceSum += confidence;
            wi++;
        }
        if (words.isEmpty()) return null;
        Alignment result = new Alignment(words, confidenceSum / words.size());
        return result.usable() ? result : null;
    }

    private static double softmaxProbability(float[] values, int symbol) {
        float max = Float.NEGATIVE_INFINITY;
        for (float value : values) max = Math.max(max, value);
        double sum = 0.0;
        for (float value : values) sum += Math.exp(value - max);
        return sum <= 0.0 ? 0.0 : Math.exp(values[symbol] - max) / sum;
    }
}

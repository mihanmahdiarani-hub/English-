package com.mihan.englishaitutor.v2;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Refines Whisper's coarse segment timing against the actual 16 kHz mono PCM waveform.
 *
 * The free whisper-android API exposes segment timestamps but no VAD controls. For movie
 * dialogue we therefore:
 *  1) split multi-sentence Whisper segments into smaller teaching units, and
 *  2) move boundaries toward nearby low-energy valleys in the real audio.
 *
 * Audio never leaves the device.
 */
public final class DialogueTimingRefiner {
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_MS = 20;
    private static final int SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000;
    private static final int WAV_HEADER_BYTES = 44;

    private static final long MIN_DIALOGUE_MS = 280L;
    private static final long SEARCH_BEFORE_MS = 520L;
    private static final long SEARCH_AFTER_MS = 620L;

    private static final Pattern SENTENCE_BREAK =
            Pattern.compile("(?<=[.!?])\\s+(?=[\\\"'\\[(]*[A-Z0-9])");
    private static final Pattern SPEAKER_DASH =
            Pattern.compile("\\s+[—–-]\\s+(?=[A-Z0-9\\\"'])");

    private DialogueTimingRefiner() {}

    public static List<WhisperBridge.Segment> refine(
            File wavFile,
            List<WhisperBridge.Segment> rawSegments) {

        List<MutableSegment> pieces = splitIntoTeachingUnits(rawSegments);
        if (pieces.isEmpty()) return new ArrayList<>();

        Collections.sort(pieces, Comparator.comparingLong(a -> a.startMs));

        try {
            WaveEnergy wave = WaveEnergy.read(wavFile);
            refineStartsAndEnds(wave, pieces);
        } catch (Throwable t) {
            Diagnostics.error("TIMING_REFINE", t);
            // Text splitting is still useful even if waveform analysis fails.
            normalizeMonotonic(pieces);
        }

        List<WhisperBridge.Segment> out = new ArrayList<>();
        for (MutableSegment s : pieces) {
            String text = clean(s.text);
            if (text.isEmpty()) continue;
            long start = Math.max(0L, s.startMs);
            long end = Math.max(start + MIN_DIALOGUE_MS, s.endMs);
            out.add(new WhisperBridge.Segment(start, end, text));
        }

        Diagnostics.log("TIMING_REFINE", "raw=" + (rawSegments == null ? 0 : rawSegments.size())
                + " refined=" + out.size());
        return out;
    }

    private static List<MutableSegment> splitIntoTeachingUnits(
            List<WhisperBridge.Segment> rawSegments) {
        List<MutableSegment> out = new ArrayList<>();
        if (rawSegments == null) return out;

        for (WhisperBridge.Segment raw : rawSegments) {
            if (raw == null) continue;
            String text = clean(raw.getText());
            long start = raw.getStartMs();
            long end = raw.getEndMs();
            if (text.isEmpty() || end <= start) continue;

            List<String> parts = splitText(text);
            if (parts.size() <= 1 || end - start < 900L) {
                out.add(new MutableSegment(start, end, text));
                continue;
            }

            int totalWeight = 0;
            for (String p : parts) totalWeight += Math.max(1, wordWeight(p));
            long duration = end - start;
            long cursor = start;
            int usedWeight = 0;

            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                int weight = Math.max(1, wordWeight(part));
                long partEnd;
                if (i == parts.size() - 1) {
                    partEnd = end;
                } else {
                    usedWeight += weight;
                    partEnd = start + Math.round(duration * (usedWeight / (double) totalWeight));
                }
                out.add(new MutableSegment(cursor, Math.max(cursor + MIN_DIALOGUE_MS, partEnd), part));
                cursor = partEnd;
            }
        }
        return out;
    }

    private static List<String> splitText(String text) {
        List<String> out = new ArrayList<>();
        String[] sentenceParts = SENTENCE_BREAK.split(text);
        for (String sentence : sentenceParts) {
            String cleanSentence = clean(sentence);
            if (cleanSentence.isEmpty()) continue;
            String[] dashParts = SPEAKER_DASH.split(cleanSentence);
            if (dashParts.length == 1) {
                out.add(cleanSentence);
            } else {
                for (String p : dashParts) {
                    String c = clean(p);
                    if (!c.isEmpty()) out.add(c);
                }
            }
        }
        return out;
    }

    private static int wordWeight(String text) {
        String c = clean(text);
        if (c.isEmpty()) return 1;
        return Math.max(1, c.split("\\s+").length);
    }

    private static void refineStartsAndEnds(WaveEnergy wave, List<MutableSegment> s) {
        if (s.isEmpty()) return;

        // First refine each Whisper edge against actual speech energy. Keeping real silence
        // gaps is important: AUTO should pause right after speech, not halfway through a gap.
        for (MutableSegment item : s) {
            long rawStart = item.startMs;
            long rawEnd = item.endMs;
            item.startMs = wave.findSpeechOnset(rawStart, 360L);
            item.endMs = wave.findSpeechOffset(rawEnd, 420L);
            if (item.endMs < item.startMs + MIN_DIALOGUE_MS) {
                item.startMs = rawStart;
                item.endMs = Math.max(rawEnd, rawStart + MIN_DIALOGUE_MS);
            }
        }

        // Resolve only overlaps / almost-touching boundaries. For genuine silence gaps,
        // preserve the independent end/start so stop timing stays at the end of speech.
        for (int i = 0; i < s.size() - 1; i++) {
            MutableSegment left = s.get(i);
            MutableSegment right = s.get(i + 1);
            long gap = right.startMs - left.endMs;
            if (gap > 120L) continue;

            long expected = (left.endMs + right.startMs) / 2L;
            long min = Math.max(left.startMs + MIN_DIALOGUE_MS,
                    expected - SEARCH_BEFORE_MS);
            long max = Math.min(right.endMs - MIN_DIALOGUE_MS,
                    expected + SEARCH_AFTER_MS);
            long boundary = max > min
                    ? wave.findQuietBoundary(expected, min, max)
                    : expected;

            boundary = Math.max(left.startMs + MIN_DIALOGUE_MS, boundary);
            boundary = Math.min(right.endMs - MIN_DIALOGUE_MS, boundary);
            left.endMs = boundary;
            right.startMs = boundary;
        }

        normalizeMonotonic(s);
    }

    private static void normalizeMonotonic(List<MutableSegment> s) {
        long previousEnd = 0L;
        for (MutableSegment item : s) {
            item.startMs = Math.max(0L, item.startMs);
            // Only collapse overlap; do not erase genuine silence between dialogues.
            if (item.startMs < previousEnd) item.startMs = previousEnd;
            item.endMs = Math.max(item.startMs + MIN_DIALOGUE_MS, item.endMs);
            previousEnd = item.endMs;
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static final class MutableSegment {
        long startMs;
        long endMs;
        String text;

        MutableSegment(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static final class WaveEnergy {
        final double[] frameEnergy;

        WaveEnergy(double[] frameEnergy) {
            this.frameEnergy = frameEnergy;
        }

        static WaveEnergy read(File wav) throws Exception {
            if (wav == null || !wav.isFile() || wav.length() <= WAV_HEADER_BYTES) {
                throw new IllegalArgumentException("WAV unavailable for timing refinement");
            }

            try (RandomAccessFile f = new RandomAccessFile(wav, "r")) {
                byte[] header = new byte[WAV_HEADER_BYTES];
                f.readFully(header);
                if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
                    throw new IllegalArgumentException("Unsupported WAV header");
                }

                long samples = (f.length() - WAV_HEADER_BYTES) / 2L;
                int frames = (int) Math.max(1L, (samples + SAMPLES_PER_FRAME - 1L) / SAMPLES_PER_FRAME);
                double[] energy = new double[frames];

                for (int frame = 0; frame < frames; frame++) {
                    long sum = 0L;
                    int count = 0;
                    for (int i = 0; i < SAMPLES_PER_FRAME; i++) {
                        int lo = f.read();
                        int hi = f.read();
                        if (lo < 0 || hi < 0) break;
                        short sample = (short) ((hi << 8) | lo);
                        sum += Math.abs((int) sample);
                        count++;
                    }
                    energy[frame] = count == 0 ? 0.0 : sum / (double) count;
                }
                return new WaveEnergy(energy);
            }
        }

        long findQuietBoundary(long expectedMs, long minMs, long maxMs) {
            int minFrame = frameFor(minMs);
            int maxFrame = frameFor(maxMs);
            int expectedFrame = frameFor(expectedMs);
            if (maxFrame <= minFrame) return expectedMs;

            double localScale = percentile(minFrame, maxFrame, 0.70);
            localScale = Math.max(180.0, localScale);

            double bestScore = Double.MAX_VALUE;
            int bestFrame = expectedFrame;
            final int halfWindow = 3; // 140 ms energy valley

            for (int f = minFrame; f <= maxFrame; f++) {
                double quiet = averageEnergy(f - halfWindow, f + halfWindow);
                double distance = Math.abs(f - expectedFrame) * FRAME_MS;
                double distancePenalty = localScale * 0.55
                        * (distance / Math.max(1.0, (maxMs - minMs) / 2.0));
                double score = quiet + distancePenalty;
                if (score < bestScore) {
                    bestScore = score;
                    bestFrame = f;
                }
            }
            return bestFrame * (long) FRAME_MS;
        }

        long findSpeechOnset(long expectedMs, long radiusMs) {
            int start = frameFor(Math.max(0L, expectedMs - radiusMs));
            int end = frameFor(expectedMs + radiusMs);
            double threshold = Math.max(220.0, percentile(start, end, 0.55) * 0.55);
            for (int f = start; f <= end; f++) {
                if (averageEnergy(f, f + 2) >= threshold) {
                    return Math.max(0L, (f - 1L) * FRAME_MS);
                }
            }
            return expectedMs;
        }

        long findSpeechOffset(long expectedMs, long radiusMs) {
            int start = frameFor(Math.max(0L, expectedMs - radiusMs));
            int end = frameFor(expectedMs + radiusMs);
            double threshold = Math.max(220.0, percentile(start, end, 0.55) * 0.50);
            for (int f = end; f >= start; f--) {
                if (averageEnergy(f - 2, f) >= threshold) {
                    return (f + 2L) * FRAME_MS;
                }
            }
            return expectedMs;
        }

        int frameFor(long ms) {
            if (frameEnergy.length == 0) return 0;
            long frame = Math.max(0L, ms / FRAME_MS);
            return (int) Math.min(frameEnergy.length - 1L, frame);
        }

        double averageEnergy(int from, int to) {
            if (frameEnergy.length == 0) return 0.0;
            int a = Math.max(0, from);
            int b = Math.min(frameEnergy.length - 1, to);
            if (b < a) return 0.0;
            double sum = 0.0;
            for (int i = a; i <= b; i++) sum += frameEnergy[i];
            return sum / (b - a + 1.0);
        }

        double percentile(int from, int to, double p) {
            if (frameEnergy.length == 0) return 0.0;
            int a = Math.max(0, from);
            int b = Math.min(frameEnergy.length - 1, to);
            if (b < a) return 0.0;
            List<Double> values = new ArrayList<>();
            for (int i = a; i <= b; i++) values.add(frameEnergy[i]);
            Collections.sort(values);
            int ix = (int) Math.round((values.size() - 1) * Math.max(0.0, Math.min(1.0, p)));
            return values.get(ix);
        }
    }
}

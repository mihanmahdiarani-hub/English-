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
    // 5 ms waveform windows = 80 PCM samples at 16 kHz; do not conflate
    // millisecond timestamp resolution with infallible speech recognition.
    private static final int FRAME_MS = AudioTimeline.ENERGY_FRAME_MS;
    private static final int SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000;
    private static final int WAV_HEADER_BYTES = 44;

    private static final long MIN_DIALOGUE_MS = AudioTimeline.MIN_DIALOGUE_MS;
    private static final long SEARCH_RADIUS_MS = AudioTimeline.EDGE_SEARCH_MS;

    private static final Pattern SENTENCE_BREAK =
            Pattern.compile("(?<=[.!?])\\s+(?=[\\\"'\\[(]*[A-Z0-9])");
    private static final Pattern SPEAKER_DASH =
            Pattern.compile("\\s+[—–-]\\s+(?=[A-Z0-9\\\"'])");

    private DialogueTimingRefiner() {}

    public static List<WhisperBridge.Segment> refine(
            File wavFile,
            List<WhisperBridge.Segment> rawSegments) {

        WaveEnergy wave = null;
        try {
            wave = WaveEnergy.read(wavFile);
        } catch (Throwable t) {
            Diagnostics.error("TIMING_REFINE_WAVE", t);
            // Fall back to Whisper's own sentence boundaries. NEVER assign
            // durations by counting words if the real audio is unavailable.
        }

        List<MutableSegment> pieces = splitIntoTeachingUnits(rawSegments, wave);
        if (pieces.isEmpty()) return new ArrayList<>();
        Collections.sort(pieces, Comparator.comparingLong(a -> a.startMs));

        if (wave != null) refineStartsAndEnds(wave, pieces);
        else normalizeMonotonic(pieces);

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
            List<WhisperBridge.Segment> rawSegments, WaveEnergy wave) {
        List<MutableSegment> out = new ArrayList<>();
        if (rawSegments == null) return out;

        for (WhisperBridge.Segment raw : rawSegments) {
            if (raw == null) continue;
            String text = clean(raw.getText());
            long startMs = raw.getStartMs();
            long endMs = raw.getEndMs();
            if (text.isEmpty() || endMs <= startMs) continue;

            List<String> parts = splitText(text);
            if (parts.size() <= 1
                    || endMs - startMs < parts.size() * MIN_DIALOGUE_MS + 60L
                    || wave == null) {
                out.add(new MutableSegment(startMs, endMs, text));
                continue;
            }

            // Old code split by the fraction of WORDS in each phrase. That
            // fabricated a timestamp regardless of actual speech and caused
            // word fragments to be assigned to adjacent dialogue rows.
            // Now only split if the waveform contains a clear, sustained
            // silence between spoken parts near each weighted prediction.
            int totalWeight = 0;
            for (String part : parts) totalWeight += Math.max(1, wordWeight(part));
            long[] boundaries = new long[parts.size() - 1];
            long cursor = startMs;
            int usedWeight = 0;
            boolean trustworthy = true;
            for (int i = 0; i < boundaries.length; i++) {
                usedWeight += Math.max(1, wordWeight(parts.get(i)));
                long nominal = startMs + Math.round((endMs - startMs)
                        * (usedWeight / (double) totalWeight));
                long limit = endMs - (parts.size() - 1L - i) * MIN_DIALOGUE_MS;
                long pause = wave.findSentencePause(nominal,
                        cursor + MIN_DIALOGUE_MS, limit);
                if (pause < 0L || pause <= cursor + MIN_DIALOGUE_MS
                        || pause >= limit) {
                    trustworthy = false;
                    break;
                }
                boundaries[i] = pause;
                cursor = pause;
            }

            if (!trustworthy) {
                out.add(new MutableSegment(startMs, endMs, text));
                continue;
            }

            cursor = startMs;
            for (int i = 0; i < parts.size(); i++) {
                long pieceEnd = i < boundaries.length ? boundaries[i] : endMs;
                out.add(new MutableSegment(cursor, pieceEnd, parts.get(i)));
                cursor = pieceEnd;
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

        // Preserve ORIGINAL Whisper bounds before refining. The former
        // findSpeechOnset() scanned from start-360ms and took the FIRST loud
        // frame (often previous dialogue); findSpeechOffset() scanned from
        // end+420ms backwards and took the LAST loud frame (often NEXT
        // dialogue). Combined they could shift a sentence by ~1 second.
        final long[][] original = new long[s.size()][2];
        for (int i = 0; i < s.size(); i++) {
            original[i][0] = s.get(i).startMs;
            original[i][1] = s.get(i).endMs;
        }

        for (int i = 0; i < s.size(); i++) {
            MutableSegment item = s.get(i);
            long rawStart = original[i][0];
            long rawEnd = original[i][1];
            long lowStart = Math.max(0L, rawStart - SEARCH_RADIUS_MS);
            long highStart = Math.min(rawEnd - MIN_DIALOGUE_MS,
                    rawStart + SEARCH_RADIUS_MS);
            // Do not find the preceding actor's onset when there is a
            // genuine gap after that actor's Whisper segment.
            if (i > 0 && original[i - 1][1] <= rawStart) {
                lowStart = Math.max(lowStart, original[i - 1][1]);
            }
            long adjustedStart = highStart > lowStart
                    ? wave.nearestEdge(rawStart, lowStart, highStart, true)
                    : rawStart;

            long lowEnd = Math.max(adjustedStart + MIN_DIALOGUE_MS,
                    rawEnd - SEARCH_RADIUS_MS);
            long highEnd = Math.min(wave.durationMs(),
                    rawEnd + SEARCH_RADIUS_MS);
            // Never snap this segment's end to speech after the next known
            // segment start (that was a primary one-second spill source).
            if (i + 1 < s.size() && original[i + 1][0] >= rawEnd) {
                highEnd = Math.min(highEnd, original[i + 1][0]);
            }
            long adjustedEnd = highEnd > lowEnd
                    ? wave.nearestEdge(rawEnd, lowEnd, highEnd, false)
                    : rawEnd;
            if (adjustedEnd - adjustedStart < MIN_DIALOGUE_MS) {
                // No trustworthy transition: keep the raw Whisper boundary
                // rather than inventing another sentence's audio.
                item.startMs = rawStart;
                item.endMs = rawEnd;
            } else {
                item.startMs = adjustedStart;
                item.endMs = adjustedEnd;
            }
        }

        // Only merge boundaries that actually collide. The previous code
        // merged even 120ms of genuine silence into the nearest dialogue.
        for (int i = 0; i < s.size() - 1; i++) {
            MutableSegment left = s.get(i);
            MutableSegment right = s.get(i + 1);
            if (right.startMs > left.endMs) continue;

            long expected = (left.endMs + right.startMs) / 2L;
            long low = Math.max(left.startMs + MIN_DIALOGUE_MS,
                    expected - SEARCH_RADIUS_MS);
            long high = Math.min(right.endMs - MIN_DIALOGUE_MS,
                    expected + SEARCH_RADIUS_MS);
            if (high <= low) continue;
            long boundary = wave.findQuietBoundary(expected, low, high);
            boundary = Math.max(low, Math.min(high, boundary));
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
            final int halfWindow = 3; // 35 ms energy valley at 5-ms frames

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

        /**
         * A sentence split is valid only across sustained acoustic silence.
         * Keeping one multi-sentence Whisper row is less misleading than
         * inventing separate timestamps where actors speak continuously.
         */
        long findSentencePause(long nominalMs, long earliestMs, long latestMs) {
            long loMs = Math.max(earliestMs, nominalMs - 700L);
            long hiMs = Math.min(latestMs, nominalMs + 700L);
            if (hiMs <= loMs) return -1L;
            int lo = frameFor(loMs);
            int hi = frameFor(hiMs);
            if (hi - lo < 20) return -1L;

            double floor = percentile(lo, hi, 0.18);
            double speech = percentile(lo, hi, 0.85);
            if (speech - floor < Math.max(120.0, floor * 0.4)) return -1L;
            double quietThreshold = floor + (speech - floor) * 0.32;
            int minQuietFrames = 60 / FRAME_MS;
            int contextFrames = 45 / FRAME_MS;
            long bestMs = -1L;
            long bestDist = Long.MAX_VALUE;

            int startQuiet = -1;
            for (int f = lo; f <= hi + 1; f++) {
                boolean quiet = f <= hi && frameEnergy[f] < quietThreshold;
                if (quiet) {
                    if (startQuiet < 0) startQuiet = f;
                    continue;
                }
                if (startQuiet >= 0) {
                    int quietLength = f - startQuiet;
                    int before = startQuiet - 1;
                    int after = f;
                    if (quietLength >= minQuietFrames
                            && before - contextFrames >= 0
                            && after + contextFrames < frameEnergy.length
                            && averageEnergy(before - contextFrames, before) > quietThreshold
                            && averageEnergy(after, after + contextFrames) > quietThreshold) {
                        long center = (startQuiet + (f - startQuiet) / 2L)
                                * FRAME_MS;
                        long delta = Math.abs(center - nominalMs);
                        if (delta < bestDist) {
                            bestDist = delta;
                            bestMs = center;
                        }
                    }
                }
                startQuiet = -1;
            }
            return bestMs;
        }

        long nearestEdge(long expectedMs, long lowMs, long highMs, boolean onset) {
            return AudioTimeline.nearestSpeechEdge(
                    frameEnergy, FRAME_MS, expectedMs, lowMs, highMs, onset);
        }

        long durationMs() {
            return frameEnergy.length * (long) FRAME_MS;
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

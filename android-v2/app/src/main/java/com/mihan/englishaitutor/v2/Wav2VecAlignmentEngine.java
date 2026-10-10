package com.mihan.englishaitutor.v2;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Actual on-device Wav2Vec2 CTC acoustic inference and Viterbi alignment.
 *
 * Whisper provides TEXT, the ONNX acoustic model independently produces
 * frame-level 32-character CTC emissions from the ORIGINAL 16kHz WAV.
 * CtcWordAligner then computes supported word timestamps. No cloud/audio API.
 *
 * Work runs on MainActivity's background worker, NOT the UI thread.
 */
final class Wav2VecAlignmentEngine {
    private static final int SAMPLE_RATE = 16000;
    private static final int WAV_HEADER_BYTES = 44;
    private static final long MAX_CHUNK_MS = 11000L;
    private static final long CONTEXT_MS = 280L;
    private static final long ALLOWED_SHIFT_MS = 800L;

    interface Progress {
        void update(String text);
    }

    static final class Result {
        final List<WhisperBridge.Segment> segments;
        final List<List<CtcWordAligner.Word>> wordTimings;
        final int aligned;
        final int attempted;

        Result(List<WhisperBridge.Segment> segments,
               List<List<CtcWordAligner.Word>> wordTimings,
               int aligned, int attempted) {
            this.segments = segments;
            this.wordTimings = wordTimings;
            this.aligned = aligned;
            this.attempted = attempted;
        }
    }

    private Wav2VecAlignmentEngine() {}

    /**
     * Loads the model once for the whole movie, then aligns each Whisper
     * segment against actual CTC acoustic emissions. On failure, caller
     * keeps its original text/times and DOES NOT claim word-level accuracy.
     */
    static Result align(Context context, File audioWav,
                        List<WhisperBridge.Segment> source,
                        Progress progress) throws Exception {
        if (source == null || source.isEmpty()) {
            return new Result(Collections.emptyList(), Collections.emptyList(), 0, 0);
        }
        if (audioWav == null || !audioWav.isFile() || audioWav.length() <= 44L) {
            throw new IllegalArgumentException("Local 16k WAV missing for CTC alignment");
        }

        File model = AlignerModelManager.ensureReady(context,
                msg -> { if (progress != null) progress.update(msg); });
        OrtEnvironment environment = OrtEnvironment.getEnvironment();

        List<WhisperBridge.Segment> refined = new ArrayList<>(source.size());
        List<List<CtcWordAligner.Word>> timings = new ArrayList<>(source.size());
        int aligned = 0, attempted = 0;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            try (OrtSession session = environment.createSession(model.getAbsolutePath(), options);
                 RandomAccessFile wav = new RandomAccessFile(audioWav, "r")) {
                long durationMs = (wav.length() - WAV_HEADER_BYTES) * 1000L
                        / (SAMPLE_RATE * 2L);
                int idx = 0;
                for (WhisperBridge.Segment input : source) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Alignment cancelled");
                    }
                    idx++;
                    WhisperBridge.Segment output = input;
                    List<CtcWordAligner.Word> found = Collections.emptyList();
                    long originalStart = input.getStartMs();
                    long originalEnd = input.getEndMs();
                    long from = Math.max(0L, originalStart - CONTEXT_MS);
                    long to = Math.min(durationMs, originalEnd + CONTEXT_MS);
                    boolean reasonable = originalEnd > originalStart
                            && to > from + 100L
                            && to - from <= MAX_CHUNK_MS
                            && input.getText() != null;
                    if (reasonable) {
                        attempted++;
                        try {
                            float[] waveform = readAndNormalizePcm(wav, from, to);
                            CtcWordAligner.Alignment forced =
                                    inferAndAlign(environment, session, waveform,
                                            input.getText(), from, to - from);
                            if (forced != null && forced.usable()) {
                                CtcWordAligner.Word first = forced.words.get(0);
                                CtcWordAligner.Word last =
                                        forced.words.get(forced.words.size() - 1);
                                long newStart = first.startMs;
                                long newEnd = last.endMs;
                                // Silence/music or a wrong Whisper transcript
                                // can make even CTC align to unrelated speech.
                                // Never move a boundary >800ms from Whisper.
                                if (newEnd > newStart + 40L
                                        && Math.abs(newStart - originalStart)
                                        <= ALLOWED_SHIFT_MS
                                        && Math.abs(newEnd - originalEnd)
                                        <= ALLOWED_SHIFT_MS
                                        && (refined.isEmpty()
                                            || newStart >= refined.get(refined.size()-1).getEndMs())) {
                                    output = new WhisperBridge.Segment(
                                            newStart, newEnd, input.getText());
                                    found = forced.words;
                                    aligned++;
                                }
                            }
                        } catch (OutOfMemoryError oom) {
                            // This device cannot afford the 95MB model and
                            // transformer activations. Stop cleanly; Whisper
                            // fallback remains available for all sentences.
                            throw new IllegalStateException(
                                    "Not enough device RAM for CTC acoustic alignment", oom);
                        } catch (Exception failedSegment) {
                            Diagnostics.log("CTC_SKIP", "dialogue=" + idx
                                    + " " + failedSegment.getClass().getSimpleName());
                        }
                    }
                    refined.add(output);
                    timings.add(found);
                    if (progress != null && (idx == 1 || idx % 8 == 0 || idx == source.size())) {
                        progress.update("تطبیق واقعی صدا و کلمات: "
                                + idx + "/" + source.size()
                                + " • موفق: " + aligned);
                    }
                }
            }
        }
        Diagnostics.log("CTC", "acoustically aligned=" + aligned
                + "/" + attempted + " out of " + source.size());
        return new Result(refined, timings, aligned, attempted);
    }

    private static CtcWordAligner.Alignment inferAndAlign(
            OrtEnvironment environment, OrtSession session,
            float[] waveform, String transcript, long startMs, long durationMs)
            throws Exception {
        if (waveform.length < 1600) return null;
        // Wav2Vec2 expects normalized float32 waveform at 16 kHz. The ONNX
        // file contains the convolutional frontend + transformer + CTC head.
        ByteBuffer raw = ByteBuffer.allocateDirect(waveform.length * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer input = raw.asFloatBuffer();
        input.put(waveform);
        input.flip();
        try (OnnxTensor tensor = OnnxTensor.createTensor(
                     environment, input, new long[]{1L, waveform.length});
             OrtSession.Result response = session.run(
                     Collections.singletonMap("input_values", tensor))) {
            if (response.size() == 0 || !(response.get(0) instanceof OnnxTensor)) {
                return null;
            }
            OnnxTensor logits = (OnnxTensor) response.get(0);
            if (!(logits.getInfo() instanceof TensorInfo)) return null;
            long[] shape = ((TensorInfo) logits.getInfo()).getShape();
            if (shape.length != 3 || shape[0] != 1 || shape[1] <= 0
                    || shape[1] > 1600 || shape[2] != CtcWordAligner.VOCAB_SIZE)
                return null;
            int frames = (int) shape[1];
            FloatBuffer buffer = logits.getFloatBuffer();
            buffer.rewind();
            float[][] emissions = new float[frames][CtcWordAligner.VOCAB_SIZE];
            for (float[] frame : emissions) buffer.get(frame);
            return CtcWordAligner.align(emissions, transcript, startMs, durationMs);
        }
    }

    private static float[] readAndNormalizePcm(
            RandomAccessFile wav, long startMs, long stopMs) throws Exception {
        long length = wav.length();
        long first = startMs * SAMPLE_RATE / 1000L;
        long last = stopMs * SAMPLE_RATE / 1000L;
        int count = (int) Math.max(0L, Math.min(last - first,
                (length - WAV_HEADER_BYTES) / 2L - first));
        if (count <= 0) return new float[0];
        if (count > SAMPLE_RATE * 12) {
            throw new IllegalArgumentException("Oversize CTC inference window");
        }
        wav.seek(WAV_HEADER_BYTES + first * 2L);
        float[] samples = new float[count];
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            int lo = wav.readUnsignedByte();
            int hi = wav.readUnsignedByte();
            short pcm = (short) ((hi << 8) | lo);
            samples[i] = pcm / 32768f;
            sum += samples[i];
        }
        double mean = sum / count;
        double variance = 0.0;
        for (float one : samples) {
            double delta = one - mean;
            variance += delta * delta;
        }
        double std = Math.sqrt(variance / count + 1.0e-7);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) ((samples[i] - mean) / std);
        }
        return samples;
    }
}

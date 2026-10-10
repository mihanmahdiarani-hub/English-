package com.mihan.englishaitutor.v2;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Fully offline, actual neural speech enhancement using streaming GTCRN.
 *
 * The original 16k mono WAV is NEVER modified. Two sample-aligned stems:
 *   speech.wav     = speech-oriented GTCRN result (Whisper/CTC input)
 *   background.wav = original - speech (music/effects + any residual voice)
 *
 * GTCRN is NOT a perfect 3-stem music/sfx/dialogue separator. It is a small
 * speech denoiser trained on DNS3; overlapping voices/music can leak.
 * It must NEVER be used for the learner's original actor PCM playback.
 *
 * Model: Xiaobin-Rong/gtcrn, stream/onnx_models/gtcrn_simple.onnx
 * MIT-licensed; pinned and verified via git blob SHA in GitHub CI, packaged
 * only in English AI Tutor's own APK assets.
 */
final class SpeechStemEnhancer {
    private static final String MODEL_ASSET = "gtcrn_simple.onnx";
    private static final int SAMPLE_RATE = 16000;
    private static final int HEADER_BYTES = 44;
    private static final long MODEL_BYTES = 535190L;
    private static final long[] SPEC_SHAPE = {1, 257, 1, 2};
    private static final long[] CONV_SHAPE = {2, 1, 16, 16, 33};
    private static final long[] TRA_SHAPE = {2, 3, 1, 1, 16};
    private static final long[] INTER_SHAPE = {2, 1, 33, 16};
    private static final int CONV_COUNT = 2 * 1 * 16 * 16 * 33;
    private static final int TRA_COUNT = 2 * 3 * 1 * 1 * 16;
    private static final int INTER_COUNT = 2 * 1 * 33 * 16;

    interface Progress {
        void update(int percent, String stage);
    }

    private SpeechStemEnhancer() {}

    static File forRecognition(LocalArchiveManager.Archive archive) {
        return archive != null && archive.hasSpeechStem()
                ? archive.speechFile : archive == null ? null : archive.audioFile;
    }

    static void separate(Context context, File original, File speech, File background,
                         Progress progress) throws Exception {
        if (original == null || !original.isFile()
                || original.length() <= HEADER_BYTES)
            throw new IllegalArgumentException("Original movie WAV is unavailable");
        if (speech.equals(original) || background.equals(original) || speech.equals(background))
            throw new IllegalArgumentException("Speech stem must preserve the original WAV");
        File modelFile = prepareModel(context);
        File voiceTemp = new File(speech.getAbsolutePath() + ".tmp");
        File bgTemp = new File(background.getAbsolutePath() + ".tmp");
        if (voiceTemp.exists()) voiceTemp.delete();
        if (bgTemp.exists()) bgTemp.delete();
        try {
            render(modelFile, original, voiceTemp, bgTemp, progress);
            if (voiceTemp.length() != original.length()
                    || bgTemp.length() != original.length())
                throw new IllegalStateException("Speech stem duration is not sample-aligned");
            Files.move(voiceTemp.toPath(), speech.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.move(bgTemp.toPath(), background.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            Diagnostics.log("SPEECH_STEMS", "ready originalBytes=" + original.length()
                    + " speechBytes=" + speech.length() + " residualBytes=" + background.length()
                    + " PTS=unchanged model=GTCRN");
            if (progress != null) progress.update(100, "گفتار و صدای پس‌زمینه جدا شدند");
        } finally {
            if (voiceTemp.exists()) voiceTemp.delete();
            if (bgTemp.exists()) bgTemp.delete();
        }
    }

    private static File prepareModel(Context context) throws Exception {
        File destination = new File(context.getFilesDir(), MODEL_ASSET);
        if (destination.isFile() && destination.length() == MODEL_BYTES) return destination;
        File tmp = new File(context.getFilesDir(), MODEL_ASSET + ".tmp");
        try (InputStream in = context.getAssets().open(MODEL_ASSET)) {
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (tmp.length() != MODEL_BYTES)
            throw new IllegalStateException("GTCRN asset missing or wrong size");
        Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return destination;
    }

    private static FloatBuffer allocate(int length) {
        return ByteBuffer.allocateDirect(length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static OnnxTensor tensor(OrtEnvironment env, FloatBuffer buffer,
                                     float[] values, long[] shape) throws Exception {
        buffer.clear();
        buffer.put(values);
        buffer.flip();
        return OnnxTensor.createTensor(env, buffer, shape);
    }

    private static void copyOutput(OrtSession.Result result, String name, float[] target)
            throws Exception {
        ai.onnxruntime.OnnxValue value = result.get(name)
                .orElseThrow(() -> new IllegalStateException("Missing GTCRN output: " + name));
        if (!(value instanceof OnnxTensor))
            throw new IllegalStateException("Unexpected GTCRN output: " + name);
        FloatBuffer view = ((OnnxTensor) value).getFloatBuffer();
        view.rewind();
        if (view.remaining() != target.length)
            throw new IllegalStateException("Invalid GTCRN tensor length: " + name);
        view.get(target);
    }

    private static void render(File modelFile, File original, File voiceTemp,
                               File bgTemp, Progress progress) throws Exception {
        GtcrnStft stft = new GtcrnStft();
        final float[] sourceFrame = new float[GtcrnStft.FFT_SIZE];
        final float[] frequencies = new float[GtcrnStft.COMPLEX_LENGTH];
        final float[] enhancedFrequency = new float[GtcrnStft.COMPLEX_LENGTH];
        final float[] enhancedFrame = new float[GtcrnStft.FFT_SIZE];
        final float[] pending = new float[GtcrnStft.FFT_SIZE];
        final float[] weights = new float[GtcrnStft.FFT_SIZE];
        final float[] conv = new float[CONV_COUNT];
        final float[] tra = new float[TRA_COUNT];
        final float[] inter = new float[INTER_COUNT];
        final FloatBuffer inputBuffer = allocate(frequencies.length);
        final FloatBuffer convBuffer = allocate(conv.length);
        final FloatBuffer traBuffer = allocate(tra.length);
        final FloatBuffer interBuffer = allocate(inter.length);
        final byte[] raw = new byte[GtcrnStft.FFT_SIZE * 2];

        try (RandomAccessFile in = new RandomAccessFile(original, "r");
             RandomAccessFile outVoice = new RandomAccessFile(voiceTemp, "rw");
             RandomAccessFile outBg = new RandomAccessFile(bgTemp, "rw");
             OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            long samples = validatePcmWav(in);
            if (samples > (Integer.MAX_VALUE - HEADER_BYTES) / 2L)
                throw new IllegalArgumentException("WAV exceeds RIFF 32-bit maximum");
            outVoice.setLength(0);
            outBg.setLength(0);
            writeHeader(outVoice, samples * 2L);
            writeHeader(outBg, samples * 2L);
            options.setIntraOpNumThreads(1);
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            try (OrtSession session = env.createSession(modelFile.getAbsolutePath(), options)) {
                long countWritten = 0L;
                int lastPercent = -1;
                // Center-padding by half a window preserves original sample zero.
                // No new samples are inserted: every input index maps exactly
                // to the same output index in BOTH generated stems.
                for (long start = -GtcrnStft.HOP; start <= samples;
                     start += GtcrnStft.HOP) {
                    if (Thread.currentThread().isInterrupted())
                        throw new InterruptedException("Speech stem processing cancelled");
                    loadFrame(in, start, samples, raw, sourceFrame);
                    stft.forward(sourceFrame, frequencies);
                    try (OnnxTensor input = tensor(env, inputBuffer, frequencies, SPEC_SHAPE);
                         OnnxTensor stateConv = tensor(env, convBuffer, conv, CONV_SHAPE);
                         OnnxTensor stateTra = tensor(env, traBuffer, tra, TRA_SHAPE);
                         OnnxTensor stateInter = tensor(env, interBuffer, inter, INTER_SHAPE)) {
                        Map<String, OnnxTensor> inputs = new HashMap<>();
                        inputs.put("mix", input);
                        inputs.put("conv_cache", stateConv);
                        inputs.put("tra_cache", stateTra);
                        inputs.put("inter_cache", stateInter);
                        try (OrtSession.Result result = session.run(inputs)) {
                            copyOutput(result, "enh", enhancedFrequency);
                            copyOutput(result, "conv_cache_out", conv);
                            copyOutput(result, "tra_cache_out", tra);
                            copyOutput(result, "inter_cache_out", inter);
                        }
                    }
                    stft.inverse(enhancedFrequency, enhancedFrame);
                    for (int i = 0; i < GtcrnStft.FFT_SIZE; i++) {
                        pending[i] += enhancedFrame[i];
                        weights[i] += stft.windowPower(i);
                    }
                    for (int i = 0; i < GtcrnStft.HOP; i++) {
                        long at = start + i;
                        if (at >= 0L && at < samples) {
                            float enhanced = weights[i] > 1.0e-5f
                                    ? pending[i] / weights[i] : 0f;
                            float originalSample = sourceFrame[i];
                            if (!Float.isFinite(enhanced))
                                throw new IllegalStateException("GTCRN produced invalid speech");
                            // Background is algebraic residual, NOT an
                            // independently classified music or effects stem.
                            writePcm(outVoice, enhanced);
                            writePcm(outBg, originalSample - enhanced);
                            countWritten++;
                        }
                    }
                    System.arraycopy(pending, GtcrnStft.HOP, pending, 0, GtcrnStft.HOP);
                    System.arraycopy(weights, GtcrnStft.HOP, weights, 0, GtcrnStft.HOP);
                    Arrays.fill(pending, GtcrnStft.HOP, GtcrnStft.FFT_SIZE, 0f);
                    Arrays.fill(weights, GtcrnStft.HOP, GtcrnStft.FFT_SIZE, 0f);
                    int percent = (int) Math.min(100L, Math.max(0L, start) * 100L
                            / Math.max(1L, samples));
                    if (percent >= lastPercent + 5) {
                        lastPercent = percent;
                        if (progress != null)
                            progress.update(percent, "جداسازی گفتار از موسیقی/افکت با GTCRN");
                    }
                }
                if (countWritten != samples)
                    throw new IllegalStateException("GTCRN timebase changed: "
                            + countWritten + " vs " + samples);
            }
        }
    }

    private static long validatePcmWav(RandomAccessFile f) throws Exception {
        byte[] header = new byte[HEADER_BYTES];
        f.readFully(header);
        if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F'
                || header[3] != 'F' || header[8] != 'W' || header[9] != 'A'
                || header[10] != 'V' || header[11] != 'E'
                || readLE16(header, 20) != 1 || readLE16(header, 22) != 1
                || readLE32(header, 24) != SAMPLE_RATE
                || readLE16(header, 34) != 16)
            throw new IllegalArgumentException("GTCRN requires 16 kHz mono PCM WAV");
        return (f.length() - HEADER_BYTES) / 2L;
    }

    private static void loadFrame(RandomAccessFile in, long start,
                                  long samples, byte[] buffer, float[] out) throws Exception {
        Arrays.fill(out, 0f);
        long left = Math.max(0L, start);
        long right = Math.min(samples, start + GtcrnStft.FFT_SIZE);
        if (right <= left) return;
        int count = (int) (right - left);
        in.seek(HEADER_BYTES + 2 * left);
        in.readFully(buffer, 0, count * 2);
        int offset = (int) (left - start);
        for (int i = 0; i < count; i++) {
            int lo = buffer[2 * i] & 255;
            int hi = buffer[2 * i + 1] & 255;
            out[i + offset] = (short) ((hi << 8) | lo) / 32768f;
        }
    }

    private static void writePcm(RandomAccessFile f, float sample) throws Exception {
        int pcm = Math.max(-32768, Math.min(32767, Math.round(sample * 32768f)));
        f.write(pcm & 255);
        f.write((pcm >>> 8) & 255);
    }

    private static int readLE16(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8);
    }
    private static int readLE32(byte[] b, int p) {
        return readLE16(b, p) | readLE16(b, p + 2) << 16;
    }
    private static void writeHeader(RandomAccessFile f, long bytes) throws Exception {
        f.writeBytes("RIFF"); write32(f, (int) (36L + bytes)); f.writeBytes("WAVE");
        f.writeBytes("fmt "); write32(f, 16); write16(f, 1); write16(f, 1);
        write32(f, SAMPLE_RATE); write32(f, SAMPLE_RATE * 2);
        write16(f, 2); write16(f, 16); f.writeBytes("data"); write32(f, (int) bytes);
    }
    private static void write32(RandomAccessFile f, int n) throws Exception {
        f.write(n & 255); f.write(n >>> 8 & 255);
        f.write(n >>> 16 & 255); f.write(n >>> 24 & 255);
    }
    private static void write16(RandomAccessFile f, int n) throws Exception {
        f.write(n & 255); f.write(n >>> 8 & 255);
    }
}

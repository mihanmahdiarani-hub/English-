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
import java.util.HashMap;
import java.util.Map;

/**
 * True OFFLINE Silero VAD ONNX speech probabilities for a short 16k PCM window.
 * 512 samples / 32ms per frame, with 64 sample streaming context and RNN state.
 * The model is bundled inside the APK; no network, server or external uploads.
 *
 * Silero is a speech detector, NOT speech-to-word alignment or source separation.
 * Bounds returned by VadSpeechWindow are only used as alternate CTC inputs.
 */
final class SileroVad implements AutoCloseable {
    private static final String MODEL_ASSET = "silero_vad.onnx";
    private static final long MODEL_BYTES = 2327524L;
    private static final int HEADER = 44;
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME = 512;
    private static final int CONTEXT = 64;
    private static final int STATE_SIZE = 2 * 128;
    private final OrtEnvironment env;
    private final OrtSession session;

    SileroVad(Context context) throws Exception {
        File target = new File(context.getFilesDir(), MODEL_ASSET);
        if (!target.isFile() || target.length() != MODEL_BYTES) {
            File temp = new File(context.getFilesDir(), MODEL_ASSET + ".tmp");
            try (InputStream from = context.getAssets().open(MODEL_ASSET)) {
                Files.copy(from, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (temp.length() != MODEL_BYTES) {
                temp.delete();
                throw new IllegalStateException("Silero VAD ONNX model not bundled");
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            session = env.createSession(target.getAbsolutePath(), options);
        }
    }

    float[] speechProbabilities(RandomAccessFile wav, long fromMs, long toMs)
            throws Exception {
        long samples = Math.max(0L, (wav.length() - HEADER) / 2L);
        long from = Math.max(0L, fromMs * SAMPLE_RATE / 1000L);
        long to = Math.min(samples, toMs * SAMPLE_RATE / 1000L);
        if (to - from < FRAME || to - from > SAMPLE_RATE * 12L)
            return new float[0];
        int frames = (int) ((to - from + FRAME - 1L) / FRAME);
        float[] probabilities = new float[frames];
        float[] state = new float[STATE_SIZE];
        float[] input = new float[CONTEXT + FRAME];
        byte[] raw = new byte[FRAME * 2];
        FloatBuffer speechBuf = ByteBuffer.allocateDirect(input.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        FloatBuffer stateBuf = ByteBuffer.allocateDirect(STATE_SIZE * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < frames; i++) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("VAD cancelled");
            // Retain the previous frame's 64-sample context, not audio
            // from another sentence. RNN state is reset for each window.
            int first = (int) (from + (long) i * FRAME);
            int count = (int) Math.min(FRAME, to - first);
            wav.seek(HEADER + 2L * first);
            wav.readFully(raw, 0, count * 2);
            for (int j = 0; j < FRAME; j++) {
                if (j >= count) input[CONTEXT + j] = 0f;
                else {
                    int lo = raw[j * 2] & 255;
                    int hi = raw[j * 2 + 1] & 255;
                    input[CONTEXT + j] = (short) ((hi << 8) | lo) / 32768f;
                }
            }
            try (OnnxTensor tInput = floatTensor(speechBuf, input,
                            new long[]{1L, CONTEXT + FRAME});
                 OnnxTensor tState = floatTensor(stateBuf, state,
                            new long[]{2L, 1L, 128L});
                 OnnxTensor tRate = OnnxTensor.createTensor(env, Long.valueOf(SAMPLE_RATE))) {
                Map<String, OnnxTensor> map = new HashMap<>();
                map.put("input", tInput);
                map.put("state", tState);
                map.put("sr", tRate);
                try (OrtSession.Result result = session.run(map)) {
                    FloatBuffer probability = ((OnnxTensor) result.get(0)).getFloatBuffer();
                    probability.rewind();
                    probabilities[i] = probability.get();
                    FloatBuffer nextState = ((OnnxTensor) result.get(1)).getFloatBuffer();
                    nextState.rewind();
                    if (nextState.remaining() != STATE_SIZE)
                        throw new IllegalStateException("Silero VAD state shape mismatch");
                    nextState.get(state);
                }
            }
            // Advance persistent VAD input context.
            System.arraycopy(input, FRAME, input, 0, CONTEXT);
        }
        return probabilities;
    }

    private OnnxTensor floatTensor(FloatBuffer buffer, float[] values, long[] shape)
            throws Exception {
        buffer.clear();
        buffer.put(values);
        buffer.flip();
        return OnnxTensor.createTensor(env, buffer, shape);
    }

    @Override public void close() throws Exception {
        session.close();
    }
}

package com.mihan.englishaitutor;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Base64;
import android.webkit.WebView;

import org.json.JSONObject;

public final class NativeLiveMic {
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 1600;

    private final Activity activity;
    private final WebView webView;

    private volatile boolean running = false;
    private volatile boolean muted = false;
    private AudioRecord recorder;
    private Thread worker;

    public NativeLiveMic(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    public synchronized String start() {
        try {
            if (running) return result(true, "");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return result(false, "مجوز میکروفن برای English AI Tutor فعال نیست.");
            }

            int minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBuffer <= 0) return result(false, "Android نتوانست اندازه بافر میکروفن را تعیین کند.");

            int bufferBytes = Math.max(minBuffer * 2, FRAME_SAMPLES * 4);
            recorder = buildRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferBytes);
            if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseRecorder();
                recorder = buildRecorder(MediaRecorder.AudioSource.MIC, bufferBytes);
            }
            if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseRecorder();
                return result(false, "Android نتوانست منبع میکروفن Native را باز کند.");
            }

            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                releaseRecorder();
                return result(false, "میکروفن Native شروع به ضبط نکرد.");
            }

            running = true;
            muted = false;
            worker = new Thread(this::captureLoop, "EnglishTutorNativeMic");
            worker.start();
            return result(true, "");
        } catch (Throwable t) {
            stop();
            return result(false, t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private AudioRecord buildRecorder(int source, int bufferBytes) {
        try {
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            return new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void captureLoop() {
        short[] samples = new short[FRAME_SAMPLES];
        while (running) {
            AudioRecord current = recorder;
            if (current == null) break;
            int read;
            try {
                read = current.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
            } catch (Throwable t) {
                dispatchError("خواندن میکروفن Native متوقف شد: " + t.getClass().getSimpleName());
                break;
            }
            if (read <= 0) continue;
            if (muted) continue;

            byte[] pcm = new byte[read * 2];
            int peakAbs = 0;
            for (int i = 0; i < read; i++) {
                short s = samples[i];
                int abs = Math.abs((int) s);
                if (abs > peakAbs) peakAbs = abs;
                pcm[i * 2] = (byte) (s & 0xff);
                pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
            }
            double peak = Math.min(1.0, peakAbs / 32768.0);
            String b64 = Base64.encodeToString(pcm, Base64.NO_WRAP);
            dispatchPcm(b64, peak);
        }
        running = false;
    }

    private void dispatchPcm(String b64, double peak) {
        if (webView == null) return;
        final String js = "window.__englishTutorNativeMicPcm&&window.__englishTutorNativeMicPcm(" +
                JSONObject.quote(b64) + "," + peak + ");";
        webView.post(() -> {
            if (running) webView.evaluateJavascript(js, null);
        });
    }

    private void dispatchError(String message) {
        if (webView == null) return;
        final String js = "window.__englishTutorNativeMicError&&window.__englishTutorNativeMicError(" +
                JSONObject.quote(message) + ");";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    public void setMuted(boolean value) {
        muted = value;
    }

    public synchronized void stop() {
        running = false;
        muted = false;
        AudioRecord current = recorder;
        recorder = null;
        if (current != null) {
            try { current.stop(); } catch (Throwable ignored) {}
            try { current.release(); } catch (Throwable ignored) {}
        }
        worker = null;
    }

    private void releaseRecorder() {
        AudioRecord current = recorder;
        recorder = null;
        if (current != null) {
            try { current.release(); } catch (Throwable ignored) {}
        }
    }

    private String result(boolean ok, String error) {
        try {
            JSONObject object = new JSONObject();
            object.put("ok", ok);
            object.put("sampleRate", SAMPLE_RATE);
            if (error != null && !error.isEmpty()) object.put("error", error);
            return object.toString();
        } catch (Exception ignored) {
            return ok ? "{\"ok\":true}" : "{\"ok\":false}";
        }
    }
}

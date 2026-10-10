package com.mihan.englishaitutor.v2;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays the selected word using ACTUAL source movie PCM extracted earlier
 * by VideoAudioExtractor, not synthesized or network-generated speech.
 *
 * Why AudioTrack instead of a Media3 seek? An isolated 60-150ms word is often
 * shorter than decoder seek/buffer latency. Static PCM playback can select
 * exact 16kHz sample frames without replaying the film's video or the next
 * actor. Device speaker latency and CTC alignment accuracy remain hardware
 * / content dependent and MUST be checked on a real phone.
 */
final class MovieWordAudioPlayer implements AutoCloseable {
    interface Callback {
        void onFinished(boolean finished, String message);
    }

    private final ExecutorService audioThread = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final Object lock = new Object();
    private AudioTrack active;
    private volatile boolean closed;

    void play(File wav, long startMs, long endMs, Callback callback) {
        if (closed) {
            if (callback != null) callback.onFinished(false, "پخش صوت بسته شده است.");
            return;
        }
        final int ticket = generation.incrementAndGet();
        interruptPlayback();
        audioThread.execute(() -> {
            AudioTrack track = null;
            boolean finished = false;
            String error = "";
            try {
                if (ticket != generation.get() || closed) return;
                byte[] pcm = WordAudioSlice.read(wav, startMs, endMs);
                WordAudioSlice.fadeEdges(pcm);
                if (ticket != generation.get() || closed) return;

                AudioFormat format = new AudioFormat.Builder()
                        .setSampleRate(WordAudioSlice.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                int minBytes = AudioTrack.getMinBufferSize(
                        WordAudioSlice.SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minBytes < 0) throw new IllegalStateException("AudioTrack unsupported");
                track = new AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(pcm.length, minBytes))
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED)
                    throw new IllegalStateException("Cannot initialize Android AudioTrack");
                int written = track.write(pcm, 0, pcm.length);
                if (written != pcm.length)
                    throw new IllegalStateException("Incomplete original-movie word PCM");

                synchronized (lock) {
                    if (closed || ticket != generation.get()) return;
                    active = track;
                }
                track.play();
                int frames = pcm.length / 2;
                long timeout = SystemClock.elapsedRealtime()
                        + Math.max(1400L, (endMs - startMs) + 1400L);
                while (!closed && ticket == generation.get()
                        && SystemClock.elapsedRealtime() < timeout) {
                    if (track.getPlaybackHeadPosition() >= frames) {
                        finished = true;
                        break;
                    }
                    Thread.sleep(8L);
                }
                if (!finished && ticket == generation.get() && !closed) {
                    error = "پخش واژه در خروجی صدای گوشی به پایان نرسید.";
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                error = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                Diagnostics.error("MOVIE_WORD_AUDIO", failure);
            } finally {
                synchronized (lock) {
                    if (active == track) active = null;
                }
                if (track != null) {
                    try { if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
                        track.stop(); } catch (Exception ignored) {}
                    try { track.release(); } catch (Exception ignored) {}
                }
                if (!closed && ticket == generation.get() && callback != null) {
                    final boolean result = finished;
                    final String note = error;
                    main.post(() -> {
                        if (!closed && ticket == generation.get())
                            callback.onFinished(result, note);
                    });
                }
            }
        });
    }

    void stop() {
        generation.incrementAndGet();
        interruptPlayback();
    }

    private void interruptPlayback() {
        AudioTrack old;
        synchronized (lock) { old = active; }
        if (old != null) {
            try { old.pause(); } catch (Exception ignored) {}
            try { old.flush(); } catch (Exception ignored) {}
        }
    }

    @Override public void close() {
        closed = true;
        stop();
        audioThread.shutdownNow();
    }
}

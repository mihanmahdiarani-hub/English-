package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * END-TO-END ANDROID EMULATOR TEST, not a unit test of guessed timing.
 *
 * Fixture #1 is an actual MP4 with H264 video track AND AAC audio track.
 * Audio begins ~378ms after video, and contains an 800Hz tone. The EXACT
 * production VideoAudioExtractor.run uses Android MediaExtractor+MediaCodec
 * to create 16k/mono PCM. Verify RIFF WAV structure, nonzero audio data,
 * and PTS-delayed silence; reject video without audio.
 *
 * No Whisper, Gemini, Wav2Vec2, cloud/network or real user movie is needed.
 */
@RunWith(AndroidJUnit4.class)
public final class VideoAudioExtractorInstrumentedTest {
    private static final String TAG = "EAT_AUDIO_EXTRACT_TEST";

    @Test
    public void mp4VideoWithDelayedAacAudioProducesAlignedWav() throws Exception {
        Context instrumentation = InstrumentationRegistry.getInstrumentation().getContext();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File movie = copyFixture(instrumentation, target, "delayed_audio_video.mp4");
        File wav = new File(target.getCacheDir(), "real_audio_extraction_test.wav");
        if (wav.exists()) assertTrue(wav.delete());

        // Prove test input is a MOVIE and its voice is an actual audio stream.
        boolean hasVideo = false, hasAudio = false;
        long firstAudioPts = -1L;
        MediaExtractor mp4 = new MediaExtractor();
        try {
            mp4.setDataSource(target, Uri.fromFile(movie), null);
            for (int i = 0; i < mp4.getTrackCount(); i++) {
                MediaFormat track = mp4.getTrackFormat(i);
                String mime = track.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) hasVideo = true;
                if (mime != null && mime.startsWith("audio/")) {
                    hasAudio = true;
                    mp4.selectTrack(i);
                    firstAudioPts = mp4.getSampleTime();
                    mp4.unselectTrack(i);
                }
            }
        } finally {
            mp4.release();
        }
        assertTrue("Fixture must contain a real video track", hasVideo);
        assertTrue("Fixture must contain a separate AAC audio track", hasAudio);
        assertTrue("Fixture audio intentionally starts late; PTS must be nonzero",
                firstAudioPts > 250000L);
        Log.i(TAG, "MP4 contains video and audio, first audio PTS (us)=" + firstAudioPts);

        AtomicInteger lastProgress = new AtomicInteger(-1);
        File actual = VideoAudioExtractor.extract(
                target, Uri.fromFile(movie), wav,
                (percent, stage) -> lastProgress.set(percent));
        assertEquals("Production extractor must return WAV output path",
                wav.getCanonicalPath(), actual.getCanonicalPath());
        assertTrue("Production extractor did not write the WAV", actual.isFile());
        assertTrue("WAV is unexpectedly small", actual.length() > 30000L);
        assertEquals("Extraction must report completion", 100, lastProgress.get());

        try (RandomAccessFile pcm = new RandomAccessFile(actual, "r")) {
            byte[] head = new byte[44];
            pcm.readFully(head);
            assertEquals("RIFF", new String(head, 0, 4, StandardCharsets.US_ASCII));
            assertEquals("WAVE", new String(head, 8, 4, StandardCharsets.US_ASCII));
            assertEquals("fmt ", new String(head, 12, 4, StandardCharsets.US_ASCII));
            assertEquals("data", new String(head, 36, 4, StandardCharsets.US_ASCII));
            assertEquals("PCM encoding", 1, shortLe(head, 20));
            assertEquals("Mono channel count", 1, shortLe(head, 22));
            assertEquals("Whisper-compatible sample rate", 16000, intLe(head, 24));
            assertEquals("16-bit output", 16, shortLe(head, 34));
            long pcmBytes = pcm.length() - 44L;
            assertEquals("WAV header data chunk length", pcmBytes, intLe(head, 40));
            long durationMs = pcmBytes * 1000L / 32000L;
            assertTrue("WAV must retain video-time audio PTS, not drop 0.38s offset: " +
                    durationMs + "ms", durationMs >= 1700L && durationMs <= 2300L);

            int beforeSpeechMax = maxAbsoluteSample(pcm, 50, 200);
            int speechMax = maxAbsoluteSample(pcm, 700, 850);
            Log.i(TAG, "WAV durationMs=" + durationMs
                    + " beforeSpeechMax=" + beforeSpeechMax
                    + " speechMax=" + speechMax
                    + " PCM bytes=" + pcmBytes);
            assertTrue("Timestamp-aligned waveform must contain quiet lead-in",
                    beforeSpeechMax < 80);
            assertTrue("Real decoded AAC audio must be present after its initial offset",
                    speechMax > 200);
        }
        Log.i(TAG, "PASS: Android MediaExtractor + MediaCodec actually isolated AAC from MP4");
    }

    @Test
    public void silentMovieWithoutAudioTrackFailsExplicitly() throws Exception {
        Context instrumentation = InstrumentationRegistry.getInstrumentation().getContext();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File movie = copyFixture(instrumentation, target, "video_without_audio.mp4");
        File wav = new File(target.getCacheDir(), "should_not_exist_audio.wav");
        if (wav.exists()) wav.delete();
        try {
            VideoAudioExtractor.extract(target, Uri.fromFile(movie), wav,
                    (percent, stage) -> {});
            fail("Video with NO audio track must never manufacture fake sound");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("صدا"));
        }
        assertFalse("A silent video must not produce a fake WAV", wav.exists());
        Log.i(TAG, "PASS: video without sound is rejected by the production extractor");
    }

    private static File copyFixture(Context instrument, Context target, String name)
            throws Exception {
        File out = new File(target.getCacheDir(), name);
        try (InputStream in = instrument.getAssets().open(name);
             FileOutputStream dest = new FileOutputStream(out, false)) {
            byte[] buf = new byte[16384];
            int read;
            while ((read = in.read(buf)) != -1) {
                if (read > 0) dest.write(buf, 0, read);
            }
        }
        assertTrue("Test MP4 fixture missing", out.length() > 2000L);
        return out;
    }

    private static int shortLe(byte[] b, int at) {
        return (b[at] & 255) | ((b[at + 1] & 255) << 8);
    }
    private static long intLe(byte[] b, int at) {
        return (b[at] & 255L)
                | ((b[at + 1] & 255L) << 8)
                | ((b[at + 2] & 255L) << 16)
                | ((b[at + 3] & 255L) << 24);
    }
    private static int maxAbsoluteSample(RandomAccessFile wave, int fromMs, int toMs)
            throws Exception {
        wave.seek(44L + fromMs * 32L);
        int count = (toMs - fromMs) * 16;
        int peak = 0;
        for (int i = 0; i < count; i++) {
            int lo = wave.read();
            int hi = wave.read();
            if (lo < 0 || hi < 0) break;
            short value = (short) ((hi << 8) | lo);
            peak = Math.max(peak, Math.abs((int) value));
        }
        return peak;
    }
}

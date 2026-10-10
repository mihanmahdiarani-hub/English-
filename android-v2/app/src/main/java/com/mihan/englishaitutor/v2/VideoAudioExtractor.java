package com.mihan.englishaitutor.v2;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes a local video's audio to 16 kHz mono WAV. Nothing is uploaded. */
public final class VideoAudioExtractor {
    private static final int OUTPUT_RATE = 16000;
    private VideoAudioExtractor() {}

    public interface ProgressListener {
        void onProgress(int percent, String stage);
    }

    public static File extract(Context context, Uri uri, File out,
                               ProgressListener progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        RandomAccessFile wav = null;
        // Never damage a previously cached WAV if extraction fails halfway.
        File temporaryWav = new File(out.getAbsolutePath() + ".timeline.tmp");
        try {
            extractor.setDataSource(context, uri, null);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0 || format == null) throw new IllegalArgumentException("این ویدئو صدای قابل خواندن ندارد");
            extractor.selectTrack(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IllegalStateException("فرمت صدا نامشخص است");
            if (Build.VERSION.SDK_INT >= 24) {
                try { format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT); }
                catch (Throwable ignored) {}
            }

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (temporaryWav.exists() && !temporaryWav.delete()) {
                throw new IllegalStateException("Temporary audio file cannot be replaced");
            }
            wav = new RandomAccessFile(temporaryWav, "rw");
            writeHeader(wav, 0L);

            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : -1L;
            int inRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            long inputFrame = 0L;
            double nextOutputFrame = 0.0;
            long dataBytes = 0L;
            boolean inputDone = false;
            boolean outputDone = false;
            int lastPercent = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputDone) {
                if (!inputDone) {
                    int ix = decoder.dequeueInputBuffer(10000L);
                    if (ix >= 0) {
                        ByteBuffer b = decoder.getInputBuffer(ix);
                        if (b == null) throw new IllegalStateException("Audio decoder input unavailable");
                        b.clear();
                        int size = extractor.readSampleData(b, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(ix, 0, size, Math.max(0L, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                    }
                }

                int ox = decoder.dequeueOutputBuffer(info, 10000L);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = decoder.getOutputFormat();
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) throw new IllegalStateException("Codec صوتی PCM 16-bit نمی‌دهد");
                } else if (ox >= 0) {
                    ByteBuffer raw = decoder.getOutputBuffer(ox);
                    if (raw != null && info.size > 0) {
                        ByteBuffer pcm = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        pcm.position(info.offset);
                        pcm.limit(info.offset + info.size);
                        int chCount = Math.max(1, channels);
                        int frames = pcm.remaining() / (chCount * 2);
                        ByteArrayOutputStream converted = new ByteArrayOutputStream(Math.max(256, frames));
                        double step = inRate / (double) OUTPUT_RATE;
                        for (int frame = 0; frame < frames; frame++) {
                            int sum = 0;
                            short center = 0;
                            for (int ch = 0; ch < chCount; ch++) {
                                short s = pcm.getShort();
                                sum += s;
                                if (ch == 2) center = s;
                            }
                            short mono = chCount >= 3 ? center : (short) (sum / chCount);
                            long absolute = inputFrame++;
                            while (absolute + 0.0001 >= nextOutputFrame) {
                                converted.write(mono & 0xff);
                                converted.write((mono >> 8) & 0xff);
                                nextOutputFrame += step;
                            }
                        }
                        byte[] bytes = converted.toByteArray();
                        // MediaCodec timestamps live on the ORIGINAL MOVIE'S
                        // microsecond time axis. The old code concatenated
                        // decoded packets and discarded every PTS gap or track
                        // start offset, causing accumulating Whisper/video
                        // drift (occasionally >1 second).
                        long discrepancy = AudioTimeline.reconcilePcmFrames(
                                dataBytes / 2L, info.presentationTimeUs, OUTPUT_RATE);
                        if (discrepancy > 0L) {
                            // Insert 16-kHz silence for a real timestamp gap.
                            long count = discrepancy;
                            byte[] zeros = new byte[16384];
                            while (count > 0L) {
                                int samples = (int) Math.min(count, zeros.length / 2L);
                                wav.write(zeros, 0, samples * 2);
                                dataBytes += samples * 2L;
                                count -= samples;
                            }
                        } else if (discrepancy < 0L) {
                            // Some edits overlap PTS ranges. Discard repeated
                            // PCM frames instead of transcribing them twice.
                            int skipBytes = (int) Math.min(bytes.length,
                                    Math.min(Integer.MAX_VALUE / 2L, -discrepancy) * 2L);
                            if (skipBytes > 0) {
                                byte[] trimmed = new byte[bytes.length - skipBytes];
                                System.arraycopy(bytes, skipBytes, trimmed, 0, trimmed.length);
                                bytes = trimmed;
                            }
                        }
                        if (Math.abs(discrepancy) > OUTPUT_RATE / 4L) {
                            Diagnostics.log("AUDIO_TIMELINE",
                                    "PTS correction ms=" + (discrepancy * 1000L / OUTPUT_RATE)
                                    + " atMovieMs=" + (info.presentationTimeUs / 1000L));
                        }
                        wav.write(bytes);
                        dataBytes += bytes.length;
                    }
                    if (durationUs > 0 && info.presentationTimeUs >= 0) {
                        int p = (int) Math.min(99L, info.presentationTimeUs * 100L / durationUs);
                        if (p != lastPercent) {
                            lastPercent = p;
                            if (progress != null) progress.onProgress(p, "استخراج صدای کلیپ");
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(ox, false);
                }
            }

            wav.seek(0L);
            writeHeader(wav, dataBytes);
            wav.close();
            wav = null;
            // On Android API 26+, a same-directory move replaces the WAV
            // only when its corrected extraction completed successfully.
            Files.move(temporaryWav.toPath(), out.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            Diagnostics.log("AUDIO_TIMELINE", "corrected WAV samples="
                    + (dataBytes / 2L) + " durationMs="
                    + (dataBytes * 1000L / (OUTPUT_RATE * 2L)));
            if (progress != null) progress.onProgress(100, "صدا آماده شد");
            return out;
        } finally {
            try { extractor.release(); } catch (Throwable ignored) {}
            if (decoder != null) {
                try { decoder.stop(); } catch (Throwable ignored) {}
                try { decoder.release(); } catch (Throwable ignored) {}
            }
            if (wav != null) try { wav.close(); } catch (Throwable ignored) {}
            if (temporaryWav.exists()) temporaryWav.delete();
        }
    }

    private static void writeHeader(RandomAccessFile f, long dataBytes) throws Exception {
        f.writeBytes("RIFF"); writeIntLE(f, (int) (36L + dataBytes)); f.writeBytes("WAVE");
        f.writeBytes("fmt "); writeIntLE(f, 16); writeShortLE(f, (short) 1); writeShortLE(f, (short) 1);
        writeIntLE(f, OUTPUT_RATE); writeIntLE(f, OUTPUT_RATE * 2); writeShortLE(f, (short) 2); writeShortLE(f, (short) 16);
        f.writeBytes("data"); writeIntLE(f, (int) dataBytes);
    }

    private static void writeIntLE(RandomAccessFile f, int v) throws Exception {
        f.write(v & 255); f.write((v >>> 8) & 255); f.write((v >>> 16) & 255); f.write((v >>> 24) & 255);
    }

    private static void writeShortLE(RandomAccessFile f, short v) throws Exception {
        f.write(v & 255); f.write((v >>> 8) & 255);
    }
}

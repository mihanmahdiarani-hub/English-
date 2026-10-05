package com.mihan.englishaitutor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

final class LocalAudioPassthrough {
    static final class Result {
        final String sourceMime;
        final int sampleCount;
        final long durationUs;

        Result(String sourceMime, int sampleCount, long durationUs) {
            this.sourceMime = sourceMime;
            this.sampleCount = sampleCount;
            this.durationUs = durationUs;
        }
    }

    private LocalAudioPassthrough() {}

    static Result extract(Context context, Uri uri, long startMs, long endMs, File outputFile) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean completed = false;
        try {
            extractor.setDataSource(context, uri, null);

            int audioTrack = -1;
            MediaFormat audioFormat = null;
            String audioMime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = format;
                    audioMime = mime;
                    break;
                }
            }

            if (audioTrack < 0 || audioFormat == null || audioMime == null) {
                throw new IOException("No audio track was found in this media file");
            }

            // The fast path deliberately handles AAC only. AAC can be copied directly into
            // an M4A/MP4 container without decoding/re-encoding, which is very reliable and fast.
            // Other codecs fall back to Media3 transcoding in MainActivity.
            if (!"audio/mp4a-latm".equalsIgnoreCase(audioMime)) {
                throw new IOException("Fast path does not support source audio codec: " + audioMime);
            }

            extractor.selectTrack(audioTrack);
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrack = muxer.addTrack(audioFormat);
            muxer.start();
            muxerStarted = true;

            long startUs = Math.max(0L, startMs * 1000L);
            long endUs = endMs > 0 ? endMs * 1000L : Long.MAX_VALUE;
            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }

            int requestedBuffer = 1024 * 1024;
            if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                requestedBuffer = Math.max(requestedBuffer, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            requestedBuffer = Math.min(requestedBuffer, 8 * 1024 * 1024);
            ByteBuffer buffer = ByteBuffer.allocateDirect(requestedBuffer);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            int samples = 0;
            long firstPtsUs = -1L;
            long lastPtsUs = -1L;
            while (true) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;

                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs < 0) break;
                if (sampleTimeUs < startUs) {
                    if (!extractor.advance()) break;
                    continue;
                }
                if (sampleTimeUs >= endUs) break;

                if (firstPtsUs < 0) firstPtsUs = sampleTimeUs;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(0L, sampleTimeUs - firstPtsUs);
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(muxerTrack, buffer, info);
                lastPtsUs = sampleTimeUs;
                samples++;

                if (!extractor.advance()) break;
            }

            if (samples == 0) {
                throw new IOException("No audio samples were found inside the selected time range");
            }

            muxer.stop();
            muxerStarted = false;
            completed = true;
            long durationUs = firstPtsUs >= 0 && lastPtsUs >= firstPtsUs ? (lastPtsUs - firstPtsUs) : 0L;
            return new Result(audioMime, samples, durationUs);
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Exception ignored) {}
                }
                try { muxer.release(); } catch (Exception ignored) {}
            }
            if (!completed && outputFile.exists()) {
                try { outputFile.delete(); } catch (Exception ignored) {}
            }
        }
    }
}

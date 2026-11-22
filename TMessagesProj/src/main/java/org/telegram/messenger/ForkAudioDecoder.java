/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: minimal audio decoder for the on-device speech-to-text
 * engine (Feature 3). Telegram voice messages are OGG/Opus; whisper.cpp
 * needs 16 kHz mono float PCM, so this decodes the file with the
 * platform MediaExtractor/MediaCodec (which ship an Opus decoder),
 * mixes down to mono and resamples to 16 kHz with linear
 * interpolation. The decode is streamed chunk by chunk into a single
 * growing float array (audit I1: the first implementation buffered the
 * whole PCM byte stream, a full-rate mono array and the resampled
 * array at the same time, ~556 MB of transient heap at the 20-minute
 * cap), and production stops at a hard sample budget so the bound
 * holds even when a file's metadata understates its length (audit I2).
 * Blocking; call on a background thread. Returns null when the device
 * cannot decode the file.
 */

package org.telegram.messenger;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class ForkAudioDecoder {

    public static final int TARGET_SAMPLE_RATE = 16000;

    // TG fork (audit I2): hard budget for the decoded output. The
    // controller caps on-device transcription at 20 minutes, but that
    // check trusts the message's metadata duration, so the decoder
    // enforces the same bound itself: decoding stops once this many
    // 16 kHz samples have been produced, whatever the file contains.
    private static final int MAX_OUTPUT_SAMPLES = 1200 * TARGET_SAMPLE_RATE;

    public static float[] decodeTo16kMono(File file) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0 || format == null) {
                return null;
            }
            extractor.selectTrack(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 1;
            if (sampleRate <= 0) {
                return null;
            }
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : -1;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            StreamingMonoResampler resampler = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        int size = inputBuffer != null ? extractor.readSampleData(inputBuffer, 0) : -1;
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    boolean stop = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0 && outputBuffer != null) {
                        if (resampler == null) {
                            resampler = new StreamingMonoResampler(sampleRate, channels, durationUs);
                        }
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        resampler.feed(outputBuffer);
                        if (resampler.isFull()) {
                            stop = true;
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if (stop) {
                        outputDone = true;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    int newRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int newChannels = outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? Math.max(1, outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : channels;
                    if (resampler != null && (newRate != sampleRate || newChannels != channels)) {
                        // The stream changed format after decoding had
                        // already started; the samples fed so far were
                        // converted with the old format and cannot be
                        // redone, so fail cleanly instead of returning
                        // audio resampled at the wrong rate.
                        FileLog.e("ForkAudioDecoder: audio format changed mid-stream");
                        return null;
                    }
                    sampleRate = newRate;
                    channels = newChannels;
                }
            }
            return resampler != null ? resampler.finish() : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignore) {
            }
            extractor.release();
        }
    }

    // Streaming 16-bit little-endian PCM -> mono float in [-1, 1],
    // resampled to 16 kHz by linear interpolation, fed with codec
    // output buffers as they arrive. Only one chunk of source-rate
    // samples and the single growing 16 kHz output array are ever
    // held, so memory stays proportional to the output alone.
    private static class StreamingMonoResampler {
        private final int channels;
        private final double step; // source frames per output sample
        private float[] out;
        private int outCount;
        private long outIndex; // output samples produced; position = outIndex * step
        private float[] chunkMono = new float[0];
        private int chunkFrames;
        private long chunkBase; // absolute source-frame index of chunkMono[0]
        private float prevLast; // mono value of source frame chunkBase - 1

        StreamingMonoResampler(int sampleRate, int channels, long durationUs) {
            this.channels = channels;
            this.step = (double) sampleRate / TARGET_SAMPLE_RATE;
            int initial = 1 << 16;
            if (durationUs > 0) {
                long estimate = durationUs * TARGET_SAMPLE_RATE / 1000000L + 4096;
                initial = (int) Math.max(1 << 16, Math.min(MAX_OUTPUT_SAMPLES, estimate));
            }
            out = new float[initial];
        }

        boolean isFull() {
            return outCount >= MAX_OUTPUT_SAMPLES;
        }

        void feed(ByteBuffer buffer) {
            int frames = buffer.remaining() / (2 * channels);
            if (frames <= 0) {
                return;
            }
            float savedLast = chunkFrames > 0 ? chunkMono[chunkFrames - 1] : 0f;
            if (chunkMono.length < frames) {
                chunkMono = new float[frames];
            }
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int base = buffer.position();
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += buffer.getShort(base + (i * channels + c) * 2);
                }
                chunkMono[i] = (sum / (float) channels) / 32768f;
            }
            prevLast = savedLast;
            chunkBase += chunkFrames;
            chunkFrames = frames;
            // Emit every output sample whose two interpolation endpoints
            // are available now; a pair straddling the chunk boundary
            // reads the previous chunk's last frame via prevLast.
            long lastAvailable = chunkBase + chunkFrames - 1;
            while (!isFull()) {
                double position = outIndex * step;
                long index = (long) position;
                if (index + 1 > lastAvailable) {
                    break;
                }
                float fraction = (float) (position - index);
                float a = frameAt(index);
                float b = frameAt(index + 1);
                append(a + (b - a) * fraction);
                outIndex++;
            }
        }

        private float frameAt(long absoluteIndex) {
            if (absoluteIndex < chunkBase) {
                return prevLast; // only chunkBase - 1 is ever requested
            }
            return chunkMono[(int) (absoluteIndex - chunkBase)];
        }

        private void append(float sample) {
            if (outCount == out.length) {
                int grown = out.length + (out.length >> 1);
                if (grown <= out.length || grown > MAX_OUTPUT_SAMPLES) {
                    grown = MAX_OUTPUT_SAMPLES;
                }
                if (grown == out.length) {
                    return; // already at the budget cap
                }
                out = Arrays.copyOf(out, grown);
            }
            out[outCount++] = sample;
        }

        // Flush the tail (the final frame is reused as the second
        // interpolation endpoint, clamped exactly like the previous
        // whole-buffer implementation) and trim to the exact length.
        float[] finish() {
            long totalFrames = chunkBase + chunkFrames;
            if (totalFrames <= 0) {
                return null;
            }
            long lastAvailable = totalFrames - 1;
            while (!isFull()) {
                double position = outIndex * step;
                long index = (long) position;
                if (index > lastAvailable) {
                    break;
                }
                float fraction = (float) (position - index);
                float a = frameAt(index);
                float b = frameAt(Math.min(index + 1, lastAvailable));
                append(a + (b - a) * fraction);
                outIndex++;
            }
            if (outCount <= 0) {
                return null;
            }
            return outCount == out.length ? out : Arrays.copyOf(out, outCount);
        }
    }
}

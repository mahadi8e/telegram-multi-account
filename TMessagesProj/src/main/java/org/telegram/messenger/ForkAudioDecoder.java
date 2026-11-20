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
 * interpolation. Blocking; call on a background thread. Returns null
 * when the device cannot decode the file.
 */

package org.telegram.messenger;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;

public class ForkAudioDecoder {

    public static final int TARGET_SAMPLE_RATE = 16000;

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

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcmBytes = new ByteArrayOutputStream();
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
                    if (info.size > 0 && outputBuffer != null) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        outputBuffer.get(chunk);
                        pcmBytes.write(chunk, 0, chunk.length);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = Math.max(1, outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    }
                }
            }
            return toMonoFloat16k(pcmBytes.toByteArray(), sampleRate, channels);
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

    // 16-bit little-endian PCM -> mono float in [-1, 1], resampled to
    // 16 kHz by linear interpolation when the source rate differs.
    private static float[] toMonoFloat16k(byte[] bytes, int sampleRate, int channels) {
        int frameSize = 2 * channels;
        int frames = bytes.length / frameSize;
        if (frames <= 0 || sampleRate <= 0) {
            return null;
        }
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int offset = (i * channels + c) * 2;
                int sample = (bytes[offset] & 0xff) | (bytes[offset + 1] << 8);
                sum += (short) sample;
            }
            mono[i] = (sum / (float) channels) / 32768f;
        }
        if (sampleRate == TARGET_SAMPLE_RATE) {
            return mono;
        }
        int outLength = (int) ((long) frames * TARGET_SAMPLE_RATE / sampleRate);
        if (outLength <= 0) {
            return null;
        }
        float[] out = new float[outLength];
        double step = (double) sampleRate / TARGET_SAMPLE_RATE;
        for (int i = 0; i < outLength; i++) {
            double position = i * step;
            int index = (int) position;
            float fraction = (float) (position - index);
            float a = mono[Math.min(index, frames - 1)];
            float b = mono[Math.min(index + 1, frames - 1)];
            out[i] = a + (b - a) * fraction;
        }
        return out;
    }
}

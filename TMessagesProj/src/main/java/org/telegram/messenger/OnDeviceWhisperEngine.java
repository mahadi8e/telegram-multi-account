/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * TG fork: on-device speech-to-text engine (Feature 3), backed by
 * whisper.cpp v1.7.5 (vendored under TMessagesProj/jni/whisper/, MIT)
 * through the JNI bridge in TMessagesProj/jni/whisper_jni.cpp, which is
 * linked into the app's main native library. Transcription runs fully
 * on the device: no API key, no network, and — because no audio ever
 * leaves the phone — this engine is also allowed in secret chats,
 * mirroring the ML Kit policy of Feature 2. The model (WhisperModel)
 * is downloaded once, explicitly, from the fork voice-to-text settings.
 * The native context is created lazily on first use and kept for the
 * process lifetime; all calls are serialized on LOCK (the controller
 * already runs transcriptions on a serial queue).
 */

package org.telegram.messenger;

import java.io.File;

public class OnDeviceWhisperEngine extends ForkSttEngine {

    static {
        // The bridge lives in the app's main native library, which
        // ApplicationLoader has already loaded by the time any engine
        // runs; this call only documents the dependency and is a no-op
        // then. A failure here must not poison the class: the native
        // methods below would fail individually and be reported as a
        // transcription error instead.
        try {
            System.loadLibrary("tmessages.49");
        } catch (Throwable ignore) {
        }
    }

    private static native long nativeInit(String modelPath);

    private static native String nativeTranscribe(long context, float[] samples, String language, int threads);

    private static native String nativeDetectedLanguage(long context);

    private static native void nativeFree(long context);

    private static final Object LOCK = new Object();
    private static long nativeContext = 0;

    @Override
    public String getId() {
        return ID_ONDEVICE;
    }

    @Override
    public boolean isCloud() {
        return false;
    }

    @Override
    public void transcribe(File audioFile, String languageHint, Callback callback) {
        synchronized (LOCK) {
            try {
                if (!WhisperModel.isDownloaded()) {
                    callback.onError("on-device model is not downloaded");
                    return;
                }
                float[] pcm = ForkAudioDecoder.decodeTo16kMono(audioFile);
                if (pcm == null || pcm.length == 0) {
                    callback.onError("could not decode the voice message on this device");
                    return;
                }
                if (nativeContext == 0) {
                    nativeContext = nativeInit(WhisperModel.getModelFile().getAbsolutePath());
                    if (nativeContext == 0) {
                        callback.onError("could not load the on-device model");
                        return;
                    }
                }
                int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
                String text = nativeTranscribe(nativeContext, pcm, languageHint, threads);
                if (text == null) {
                    callback.onError("on-device transcription failed");
                    return;
                }
                String language = null;
                try {
                    language = nativeDetectedLanguage(nativeContext);
                } catch (Throwable ignore) {
                }
                callback.onSuccess(text.trim(), language);
            } catch (Throwable e) {
                FileLog.e(e);
                callback.onError("on-device transcription failed");
            }
        }
    }
}

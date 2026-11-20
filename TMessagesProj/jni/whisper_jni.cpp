// TG fork: JNI bridge for the on-device Whisper speech-to-text engine
// (Feature 3). Wraps whisper.cpp (vendored under jni/whisper/) for
// org.telegram.messenger.OnDeviceWhisperEngine. The native context is
// created once per model load and reused across transcriptions; calls
// arrive on the fork's serial transcription queue and the Java side
// additionally synchronizes on the engine instance, so no concurrent
// access to the context can happen.

#include <jni.h>

#include <cstdint>
#include <string>

#include <whisper.h>

static whisper_context *ctx_from_handle(jlong handle) {
    return reinterpret_cast<whisper_context *>(static_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_OnDeviceWhisperEngine_nativeInit(JNIEnv *env, jclass clazz, jstring modelPath) {
    (void) clazz;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        return 0;
    }
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ctx));
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_OnDeviceWhisperEngine_nativeTranscribe(JNIEnv *env, jclass clazz, jlong handle, jfloatArray samples, jstring language, jint threads) {
    (void) clazz;
    whisper_context *ctx = ctx_from_handle(handle);
    if (ctx == nullptr || samples == nullptr) {
        return nullptr;
    }
    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) {
        return nullptr;
    }
    std::string languageBuf;
    const char *languageC = nullptr;
    if (language != nullptr) {
        const char *l = env->GetStringUTFChars(language, nullptr);
        if (l != nullptr) {
            languageBuf = l;
            env->ReleaseStringUTFChars(language, l);
            languageC = languageBuf.c_str();
        }
    }
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.translate = false;
    params.language = languageC; // nullptr = auto-detect
    params.no_timestamps = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.single_segment = false;
    params.suppress_blank = true;

    const int rc = whisper_full(ctx, params, data, static_cast<int>(n));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0) {
        return nullptr;
    }
    std::string text;
    const int nSegments = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSegments; i++) {
        const char *segment = whisper_full_get_segment_text(ctx, i);
        if (segment != nullptr) {
            text += segment;
        }
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_OnDeviceWhisperEngine_nativeDetectedLanguage(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    whisper_context *ctx = ctx_from_handle(handle);
    if (ctx == nullptr) {
        return nullptr;
    }
    const int langId = whisper_full_lang_id(ctx);
    if (langId < 0) {
        return nullptr;
    }
    const char *name = whisper_lang_str(langId);
    return name != nullptr ? env->NewStringUTF(name) : nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_OnDeviceWhisperEngine_nativeFree(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    whisper_context *ctx = ctx_from_handle(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

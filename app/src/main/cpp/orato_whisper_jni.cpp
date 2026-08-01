#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <cstring>

#include "whisper.h"
#include "ggml.h"

#define TAG "OratoWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static std::atomic_bool g_abort_flag{false};

static bool abort_callback(void * /*user_data*/) {
    return g_abort_flag.load();
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_orato_app_speech_WhisperNative_initializeContext(
        JNIEnv *env, jclass /*clazz*/, jstring model_path_str) {
    if (model_path_str == nullptr) {
        return 0;
    }
    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    if (model_path == nullptr) {
        return 0;
    }
    LOGI("initializeContext path=%s", model_path);
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx =
            whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(model_path_str, model_path);
    if (ctx == nullptr) {
        LOGW("whisper_init_from_file_with_params failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jint JNICALL
Java_com_orato_app_speech_WhisperNative_transcribe(
        JNIEnv *env,
        jclass /*clazz*/,
        jlong context_ptr,
        jfloatArray samples,
        jstring language_code,
        jint num_threads,
        jboolean translate,
        jstring initial_prompt,
        jboolean carry_initial_prompt,
        jboolean suppress_nst) {
    if (context_ptr == 0 || samples == nullptr) {
        return -1;
    }
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    jsize n_samples = env->GetArrayLength(samples);
    if (n_samples <= 0) {
        return -2;
    }
    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);
    if (audio == nullptr) {
        return -3;
    }

    g_abort_flag.store(false);

    const char *lang = "it";
    const char *lang_chars = nullptr;
    if (language_code != nullptr) {
        lang_chars = env->GetStringUTFChars(language_code, nullptr);
        if (lang_chars != nullptr && lang_chars[0] != '\0') {
            lang = lang_chars;
        }
    }

    const char *prompt_chars = nullptr;
    if (initial_prompt != nullptr) {
        prompt_chars = env->GetStringUTFChars(initial_prompt, nullptr);
    }

    int threads = num_threads;
    if (threads < 1) threads = 1;
    if (threads > 8) threads = 8;

    struct whisper_full_params params =
            whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translate == JNI_TRUE;
    params.language = lang;
    params.n_threads = threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    // suppress_nst=false: keep non-speech / hesitation-like tokens available for Italian fillers.
    params.suppress_nst = suppress_nst == JNI_TRUE;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    params.token_timestamps = false;
    params.initial_prompt = (prompt_chars != nullptr && prompt_chars[0] != '\0')
            ? prompt_chars
            : nullptr;
    params.carry_initial_prompt = carry_initial_prompt == JNI_TRUE;
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = nullptr;

    whisper_reset_timings(ctx);
    LOGI("whisper_full n_samples=%d threads=%d lang=%s suppress_nst=%d carry_prompt=%d",
         (int) n_samples, threads, lang,
         (int) params.suppress_nst, (int) params.carry_initial_prompt);
    int rc = whisper_full(ctx, params, audio, n_samples);
    if (rc != 0) {
        LOGW("whisper_full failed rc=%d", rc);
    }

    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    if (lang_chars != nullptr) {
        env->ReleaseStringUTFChars(language_code, lang_chars);
    }
    if (prompt_chars != nullptr) {
        env->ReleaseStringUTFChars(initial_prompt, prompt_chars);
    }
    if (g_abort_flag.load()) {
        return -100; // cancelled
    }
    return rc;
}

JNIEXPORT void JNICALL
Java_com_orato_app_speech_WhisperNative_requestCancellation(
        JNIEnv * /*env*/, jclass /*clazz*/) {
    g_abort_flag.store(true);
}

JNIEXPORT void JNICALL
Java_com_orato_app_speech_WhisperNative_releaseContext(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong context_ptr) {
    if (context_ptr == 0) {
        return;
    }
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    whisper_free(ctx);
}

JNIEXPORT jint JNICALL
Java_com_orato_app_speech_WhisperNative_getSegmentCount(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong context_ptr) {
    if (context_ptr == 0) return 0;
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    return whisper_full_n_segments(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_orato_app_speech_WhisperNative_getSegmentText(
        JNIEnv *env, jclass /*clazz*/, jlong context_ptr, jint index) {
    if (context_ptr == 0) {
        return env->NewStringUTF("");
    }
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    const char *text = whisper_full_get_segment_text(ctx, index);
    if (text == nullptr) text = "";
    return env->NewStringUTF(text);
}

JNIEXPORT jlong JNICALL
Java_com_orato_app_speech_WhisperNative_getSegmentT0(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong context_ptr, jint index) {
    if (context_ptr == 0) return 0;
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    return whisper_full_get_segment_t0(ctx, index);
}

JNIEXPORT jlong JNICALL
Java_com_orato_app_speech_WhisperNative_getSegmentT1(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong context_ptr, jint index) {
    if (context_ptr == 0) return 0;
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    return whisper_full_get_segment_t1(ctx, index);
}

JNIEXPORT jstring JNICALL
Java_com_orato_app_speech_WhisperNative_getSystemInfo(
        JNIEnv *env, jclass /*clazz*/) {
    const char *info = whisper_print_system_info();
    if (info == nullptr) info = "";
    return env->NewStringUTF(info);
}

} // extern "C"

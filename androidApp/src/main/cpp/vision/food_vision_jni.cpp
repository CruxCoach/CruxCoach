// JNI bridge for com.cruxcoach.android.foodvision.NativeFoodVision.
// Only the ":vision" process loads this library (see FoodVisionService).

#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <memory>
#include <string>

#include "food_vision.h"

namespace {

constexpr const char * kTag = "CruxVision";

struct Session {
    std::unique_ptr<cruxvision::Engine> engine;
    std::atomic<bool> cancel{false};
};

std::string g_last_load_error;

void android_log(int level, const char * text) {
    // ggml levels: 1 debug, 2 info, 3 warn, 4 error.
    if (level < 3) return;
    __android_log_write(level >= 4 ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, kTag, text);
}

std::string to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

Session * session(jlong handle) { return reinterpret_cast<Session *>(handle); }

jbyteArray to_bytes(JNIEnv * env, const std::string & text) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_cruxcoach_android_foodvision_NativeFoodVision_nativeLoad(
        JNIEnv * env, jobject, jstring model, jstring mmproj, jint threads, jint n_ctx, jint image_max_tokens) {
    cruxvision::set_log_sink(android_log);
    cruxvision::LoadParams params;
    params.model_path = to_string(env, model);
    params.mmproj_path = to_string(env, mmproj);
    params.n_threads = threads;
    params.n_ctx = n_ctx;
    params.image_max_tokens = image_max_tokens;
    std::string error;
    auto engine = cruxvision::Engine::load(params, error);
    if (!engine) {
        g_last_load_error = error;
        return 0;
    }
    g_last_load_error.clear();
    auto * s = new Session();
    s->engine = std::move(engine);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL
Java_com_cruxcoach_android_foodvision_NativeFoodVision_nativeLastLoadError(JNIEnv * env, jobject) {
    return env->NewStringUTF(g_last_load_error.c_str());
}

// Returns UTF-8 bytes (model text may hold characters outside modified
// UTF-8, which NewStringUTF would reject) of a JSON envelope:
// {"ok":true,"text":"…","promptTokens":n,"outputTokens":n,"imageMs":n,"generateMs":n}
// or {"ok":false,"error":"code", …}.
JNIEXPORT jbyteArray JNICALL
Java_com_cruxcoach_android_foodvision_NativeFoodVision_nativeRun(
        JNIEnv * env, jobject, jlong handle, jbyteArray rgb, jint width, jint height,
        jstring system_prompt, jstring user_prompt, jstring grammar, jint max_tokens) {
    Session * s = session(handle);
    if (s == nullptr) return to_bytes(env, "{\"ok\":false,\"error\":\"not_loaded\"}");
    // An empty array means a text-only request (a typed meal).
    const jsize length = rgb != nullptr ? env->GetArrayLength(rgb) : 0;
    if (length > 0 && (width <= 0 || height <= 0 || length != static_cast<jsize>(width) * height * 3)) {
        return to_bytes(env, "{\"ok\":false,\"error\":\"invalid_image\"}");
    }
    jbyte * pixels = length > 0 ? env->GetByteArrayElements(rgb, nullptr) : nullptr;
    cruxvision::RunParams params;
    params.rgb = reinterpret_cast<const uint8_t *>(pixels);
    params.width = width;
    params.height = height;
    params.system_prompt = to_string(env, system_prompt);
    params.user_prompt = to_string(env, user_prompt);
    params.grammar = to_string(env, grammar);
    params.max_tokens = max_tokens;

    s->cancel.store(false);
    cruxvision::Timings timings;
    std::string error;
    const std::string text = s->engine->run(params, s->cancel, timings, error);
    if (pixels != nullptr) env->ReleaseByteArrayElements(rgb, pixels, JNI_ABORT);

    std::string json = "{\"ok\":";
    json += error.empty() ? "true" : "false";
    json += ",\"error\":\"" + cruxvision::json_escape(error) + "\"";
    json += ",\"text\":\"" + cruxvision::json_escape(text) + "\"";
    json += ",\"promptTokens\":" + std::to_string(timings.prompt_tokens);
    json += ",\"outputTokens\":" + std::to_string(timings.output_tokens);
    json += ",\"imageMs\":" + std::to_string(static_cast<long long>(timings.image_ms));
    json += ",\"generateMs\":" + std::to_string(static_cast<long long>(timings.generate_ms));
    json += "}";
    return to_bytes(env, json);
}

JNIEXPORT void JNICALL
Java_com_cruxcoach_android_foodvision_NativeFoodVision_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Session * s = session(handle);
    if (s != nullptr) s->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_cruxcoach_android_foodvision_NativeFoodVision_nativeFree(JNIEnv *, jobject, jlong handle) {
    delete session(handle);
}

}  // extern "C"

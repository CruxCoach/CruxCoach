// C bridge for Swift over Android's food_vision.cpp (see food_vision_c.h).
// Same session, envelope and input checks as food_vision_jni.cpp.

#include "food_vision_c.h"

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>

#include "food_vision.h"

struct crux_vision_session {
    std::unique_ptr<cruxvision::Engine> engine;
    std::atomic<bool> cancel{false};
};

namespace {

char * copy_out(const std::string & text) {
    char * out = static_cast<char *>(std::malloc(text.size() + 1));
    if (out == nullptr) return nullptr;
    std::memcpy(out, text.data(), text.size());
    out[text.size()] = '\0';
    return out;
}

void write_error(const std::string & error, char * error_out, int32_t error_capacity) {
    if (error_out == nullptr || error_capacity <= 0) return;
    const size_t n = std::min(error.size(), static_cast<size_t>(error_capacity - 1));
    std::memcpy(error_out, error.data(), n);
    error_out[n] = '\0';
}

std::string text_or_empty(const char * text) { return text != nullptr ? std::string(text) : std::string(); }

}  // namespace

extern "C" {

crux_vision_session * crux_vision_load(const char * model_path, const char * mmproj_path, int32_t n_threads,
                                       int32_t n_ctx, int32_t image_max_tokens, char * error_out,
                                       int32_t error_capacity) {
    cruxvision::LoadParams params;
    params.model_path = text_or_empty(model_path);
    params.mmproj_path = text_or_empty(mmproj_path);
    params.n_threads = n_threads;
    params.n_ctx = n_ctx;
    params.image_max_tokens = image_max_tokens;
    std::string error;
    auto engine = cruxvision::Engine::load(params, error);
    if (!engine) {
        write_error(error.empty() ? "model_load_failed" : error, error_out, error_capacity);
        return nullptr;
    }
    write_error("", error_out, error_capacity);
    auto * session = new crux_vision_session();
    session->engine = std::move(engine);
    return session;
}

char * crux_vision_run(crux_vision_session * session, const uint8_t * rgb, int64_t rgb_length, int32_t width,
                       int32_t height, const char * system_prompt, const char * user_prompt, const char * grammar,
                       int32_t max_tokens) {
    if (session == nullptr) return copy_out("{\"ok\":false,\"error\":\"not_loaded\"}");
    // No pixels means a text-only request (a typed meal).
    const int64_t length = rgb != nullptr ? rgb_length : 0;
    if (length > 0 && (width <= 0 || height <= 0 || length != static_cast<int64_t>(width) * height * 3)) {
        return copy_out("{\"ok\":false,\"error\":\"invalid_image\"}");
    }
    cruxvision::RunParams params;
    params.rgb = length > 0 ? rgb : nullptr;
    params.width = width;
    params.height = height;
    params.system_prompt = text_or_empty(system_prompt);
    params.user_prompt = text_or_empty(user_prompt);
    params.grammar = text_or_empty(grammar);
    params.max_tokens = max_tokens;

    session->cancel.store(false);
    cruxvision::Timings timings;
    std::string error;
    const std::string text = session->engine->run(params, session->cancel, timings, error);

    std::string json = "{\"ok\":";
    json += error.empty() ? "true" : "false";
    json += ",\"error\":\"" + cruxvision::json_escape(error) + "\"";
    json += ",\"text\":\"" + cruxvision::json_escape(text) + "\"";
    json += ",\"promptTokens\":" + std::to_string(timings.prompt_tokens);
    json += ",\"outputTokens\":" + std::to_string(timings.output_tokens);
    json += ",\"imageMs\":" + std::to_string(static_cast<long long>(timings.image_ms));
    json += ",\"generateMs\":" + std::to_string(static_cast<long long>(timings.generate_ms));
    json += "}";
    return copy_out(json);
}

void crux_vision_cancel(crux_vision_session * session) {
    if (session != nullptr) session->cancel.store(true);
}

void crux_vision_free(crux_vision_session * session) { delete session; }

void crux_vision_string_free(char * text) { std::free(text); }

}  // extern "C"

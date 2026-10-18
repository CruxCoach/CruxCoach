// C surface of the on-device food photo engine for Swift (FEAT-069 on iOS).
//
// The engine itself is Android's androidApp/src/main/cpp/vision/food_vision.cpp,
// compiled unchanged; this header mirrors the JNI functions of
// food_vision_jni.cpp (load, run, cancel, free) so both apps drive the same
// code with the same JSON envelope.

#ifndef CRUX_FOOD_VISION_C_H
#define CRUX_FOOD_VISION_C_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct crux_vision_session crux_vision_session;

// Loads the model and its vision projector. Returns NULL on failure and
// writes the error code ("model_load_failed", "mmproj_load_failed", …) as a
// NUL-terminated string into error_out (at most error_capacity bytes).
crux_vision_session * crux_vision_load(const char * model_path, const char * mmproj_path, int32_t n_threads,
                                       int32_t n_ctx, int32_t image_max_tokens, char * error_out,
                                       int32_t error_capacity);

// Runs one request. rgb is packed RGB (width * height * 3 bytes) or NULL with
// rgb_length 0 for a text-only request. Returns a malloc'd UTF-8 JSON
// envelope, the same as Android's nativeRun:
// {"ok":true,"error":"","text":"…","promptTokens":n,"outputTokens":n,"imageMs":n,"generateMs":n}.
// Release it with crux_vision_string_free. Blocks; one run per session at a time.
char * crux_vision_run(crux_vision_session * session, const uint8_t * rgb, int64_t rgb_length, int32_t width,
                       int32_t height, const char * system_prompt, const char * user_prompt, const char * grammar,
                       int32_t max_tokens);

// Asks a running crux_vision_run to stop; safe from any thread.
void crux_vision_cancel(crux_vision_session * session);

// Frees the session. Never while a run on it is still in progress.
void crux_vision_free(crux_vision_session * session);

void crux_vision_string_free(char * text);

#ifdef __cplusplus
}
#endif

#endif  // CRUX_FOOD_VISION_C_H

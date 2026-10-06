// On-device food photo recognition (FEAT-069).
//
// A thin layer over llama.cpp + libmtmd: load one vision-language model,
// feed one RGB image plus a prompt, and return the model's answer. The
// answer is constrained by a GBNF grammar so the app always gets JSON it can
// parse. Nutrient values never come from the model; the app looks them up
// in the bundled BLS 4.0 table.
//
// The same code runs inside the app (through food_vision_jni.cpp, in the
// separate ":vision" process) and on a desktop through
// tools/food-vision/food_vision_cli.cpp, which is how prompts are evaluated.

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

namespace cruxvision {

struct LoadParams {
    std::string model_path;
    std::string mmproj_path;
    int n_threads = 4;
    int n_ctx = 2048;
    // Upper bound for image tokens; 0 keeps the model's default.
    int image_max_tokens = 0;
};

struct RunParams {
    // Packed RGB, width * height * 3 bytes, row-major; nullptr for a
    // text-only request (a meal the user typed).
    const uint8_t * rgb = nullptr;
    int width = 0;
    int height = 0;
    std::string system_prompt;
    std::string user_prompt;
    // GBNF with a rule named "root"; empty means unconstrained output.
    std::string grammar;
    int max_tokens = 384;
};

struct Timings {
    double load_ms = 0;
    double image_ms = 0;     // tokenize + encode image + decode prompt
    double generate_ms = 0;
    int prompt_tokens = 0;   // including image tokens
    int output_tokens = 0;
};

// Receives llama.cpp/mtmd log lines. level follows ggml_log_level.
using LogSink = void (*)(int level, const char * text);
void set_log_sink(LogSink sink);

class Engine {
public:
    // Returns nullptr and fills error when the model cannot be loaded.
    static std::unique_ptr<Engine> load(const LoadParams & params, std::string & error, Timings * timings = nullptr);
    ~Engine();

    Engine(const Engine &) = delete;
    Engine & operator=(const Engine &) = delete;

    // Runs one image. Returns the generated text, or an empty string with
    // error set. Not thread-safe: one run at a time per engine. cancel may be
    // flipped from another thread and stops prompt decoding and generation.
    std::string run(const RunParams & params, const std::atomic<bool> & cancel, Timings & timings, std::string & error);

private:
    Engine();
    struct Impl;
    std::unique_ptr<Impl> impl;
};

// Escapes text for embedding in a JSON string literal (without quotes).
std::string json_escape(const std::string & text);

}  // namespace cruxvision

#include "food_vision.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <mutex>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace cruxvision {

namespace {

LogSink g_log_sink = nullptr;

void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    LogSink sink = g_log_sink;
    if (sink != nullptr) {
        sink(static_cast<int>(level), text);
    } else if (level >= GGML_LOG_LEVEL_WARN) {
        std::fputs(text, stderr);
    }
}

void init_backend_once() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(log_callback, nullptr);
        mtmd_helper_log_set(log_callback, nullptr);
        llama_backend_init();
    });
}

double elapsed_ms(std::chrono::steady_clock::time_point since) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - since).count();
}

// Qwen3.5 chat format with thinking switched off, which is what the model's
// own template renders for enable_thinking = false. The media marker is
// replaced by <|vision_start|> image tokens <|vision_end|> in mtmd_tokenize;
// a text-only request (a typed meal) has no marker.
std::string build_prompt(const std::string & system_prompt, const std::string & user_prompt, bool with_image) {
    std::string prompt;
    if (!system_prompt.empty()) {
        prompt += "<|im_start|>system\n" + system_prompt + "<|im_end|>\n";
    }
    prompt += "<|im_start|>user\n";
    if (with_image) prompt += mtmd_default_marker();
    prompt += user_prompt;
    prompt += "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
    return prompt;
}

bool abort_requested(void * data) {
    return static_cast<const std::atomic<bool> *>(data)->load();
}

// Greedy pick that consults the grammar only when the best token breaks it,
// the same rejection trick llama.cpp's common sampler uses. Checking the
// whole ~250k-token vocabulary against the grammar on every step made
// decoding many times slower than the model itself.
llama_token sample_greedy(llama_context * ctx, llama_sampler * grammar, int n_vocab, std::vector<llama_token_data> & scratch) {
    const float * logits = llama_get_logits_ith(ctx, -1);
    if (logits == nullptr || n_vocab <= 0) return LLAMA_TOKEN_NULL;
    llama_token best = 0;
    for (int i = 1; i < n_vocab; ++i) {
        if (logits[i] > logits[best]) best = i;
    }
    if (grammar == nullptr) return best;

    llama_token_data single = { best, logits[best], 0.0f };
    llama_token_data_array one = { &single, 1, -1, false };
    llama_sampler_apply(grammar, &one);
    if (one.data[0].logit != -INFINITY) return best;

    scratch.resize(static_cast<size_t>(n_vocab));
    for (int i = 0; i < n_vocab; ++i) scratch[static_cast<size_t>(i)] = { i, logits[i], 0.0f };
    llama_token_data_array all = { scratch.data(), scratch.size(), -1, false };
    llama_sampler_apply(grammar, &all);
    llama_token pick = LLAMA_TOKEN_NULL;
    float pick_logit = -INFINITY;
    for (size_t i = 0; i < all.size; ++i) {
        if (all.data[i].logit > pick_logit) {
            pick_logit = all.data[i].logit;
            pick = all.data[i].id;
        }
    }
    return pick;
}

// Follows the grammar-shaped JSON as it streams in: {"items":[{...},{...}]}.
// The grammar allows no escapes inside strings, so a quote always toggles.
struct ItemTracker {
    size_t scanned = 0;
    int depth = 0;
    bool in_string = false;
    size_t item_start = 0;
    size_t last_item_end = 0;
    std::vector<std::string> seen;
};

std::string without_layout(const std::string & text) {
    std::string out;
    bool in_string = false;
    for (const char c : text) {
        if (c == '"') in_string = !in_string;
        if (!in_string && (c == ' ' || c == '\n' || c == '\t' || c == '\r')) continue;
        out += c;
    }
    return out;
}

// True when the item that just closed repeats an earlier one exactly. The
// output is then cut back to the end of the previous item and closed.
bool stop_on_repeated_item(std::string & output, ItemTracker & t) {
    for (; t.scanned < output.size(); ++t.scanned) {
        const char c = output[t.scanned];
        if (c == '"') {
            t.in_string = !t.in_string;
            continue;
        }
        if (t.in_string) continue;
        if (c == '{') {
            if (++t.depth == 2) t.item_start = t.scanned;
        } else if (c == '}') {
            if (t.depth-- == 2) {
                const std::string item = without_layout(output.substr(t.item_start, t.scanned - t.item_start + 1));
                for (const auto & previous : t.seen) {
                    if (previous == item) {
                        output.resize(t.last_item_end);
                        output += "]}";
                        return true;
                    }
                }
                t.seen.push_back(item);
                t.last_item_end = t.scanned + 1;
            }
        }
    }
    return false;
}

struct ChunksDeleter { void operator()(mtmd_input_chunks * c) const { mtmd_input_chunks_free(c); } };
struct BitmapDeleter { void operator()(mtmd_bitmap * b) const { mtmd_bitmap_free(b); } };
struct SamplerDeleter { void operator()(llama_sampler * s) const { llama_sampler_free(s); } };

}  // namespace

void set_log_sink(LogSink sink) { g_log_sink = sink; }

struct Engine::Impl {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * mctx = nullptr;
    int n_batch = 512;
    int n_ctx = 0;

    ~Impl() {
        if (mctx != nullptr) mtmd_free(mctx);
        if (ctx != nullptr) llama_free(ctx);
        if (model != nullptr) llama_model_free(model);
    }
};

Engine::Engine() : impl(new Impl()) {}
Engine::~Engine() = default;

std::unique_ptr<Engine> Engine::load(const LoadParams & params, std::string & error, Timings * timings) {
    init_backend_once();
    const auto started = std::chrono::steady_clock::now();
    std::unique_ptr<Engine> engine(new Engine());
    Impl & s = *engine->impl;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    s.model = llama_model_load_from_file(params.model_path.c_str(), mparams);
    if (s.model == nullptr) {
        error = "model_load_failed";
        return nullptr;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(params.n_ctx);
    cparams.n_batch = static_cast<uint32_t>(s.n_batch);
    cparams.n_ubatch = static_cast<uint32_t>(s.n_batch);
    cparams.n_seq_max = 1;
    cparams.n_threads = params.n_threads;
    cparams.n_threads_batch = params.n_threads;
    cparams.no_perf = true;
    s.ctx = llama_init_from_model(s.model, cparams);
    if (s.ctx == nullptr) {
        error = "context_init_failed";
        return nullptr;
    }
    s.n_ctx = static_cast<int>(llama_n_ctx(s.ctx));

    mtmd_context_params vparams = mtmd_context_params_default();
    vparams.use_gpu = false;
    vparams.print_timings = false;
    vparams.n_threads = params.n_threads;
    vparams.warmup = false;
    if (params.image_max_tokens > 0) vparams.image_max_tokens = params.image_max_tokens;
    s.mctx = mtmd_init_from_file(params.mmproj_path.c_str(), s.model, vparams);
    if (s.mctx == nullptr) {
        error = "mmproj_load_failed";
        return nullptr;
    }
    if (!mtmd_support_vision(s.mctx)) {
        error = "mmproj_without_vision";
        return nullptr;
    }
    if (timings != nullptr) timings->load_ms = elapsed_ms(started);
    return engine;
}

std::string Engine::run(const RunParams & params, const std::atomic<bool> & cancel, Timings & timings, std::string & error) {
    Impl & s = *impl;
    const bool with_image = params.rgb != nullptr;
    if (with_image && (params.width <= 0 || params.height <= 0)) {
        error = "invalid_image";
        return {};
    }

    llama_memory_clear(llama_get_memory(s.ctx), true);
    llama_set_abort_callback(s.ctx, abort_requested, const_cast<std::atomic<bool> *>(&cancel));
    struct AbortReset {
        llama_context * ctx;
        ~AbortReset() { llama_set_abort_callback(ctx, nullptr, nullptr); }
    } abort_reset{ s.ctx };

    const auto image_started = std::chrono::steady_clock::now();
    std::unique_ptr<mtmd_bitmap, BitmapDeleter> bitmap;
    if (with_image) {
        bitmap.reset(mtmd_bitmap_init(static_cast<uint32_t>(params.width), static_cast<uint32_t>(params.height), params.rgb));
        if (!bitmap) {
            error = "invalid_image";
            return {};
        }
    }

    const std::string prompt = build_prompt(params.system_prompt, params.user_prompt, with_image);
    mtmd_input_text text;
    text.text = prompt.c_str();
    text.text_len = prompt.size();
    text.add_special = true;
    text.parse_special = true;

    std::unique_ptr<mtmd_input_chunks, ChunksDeleter> chunks(mtmd_input_chunks_init());
    const mtmd_bitmap * bitmaps[] = { bitmap.get() };
    if (mtmd_tokenize(s.mctx, chunks.get(), &text, bitmaps, with_image ? 1 : 0) != 0) {
        error = "tokenize_failed";
        return {};
    }
    const int prompt_tokens = static_cast<int>(mtmd_helper_get_n_tokens(chunks.get()));
    timings.prompt_tokens = prompt_tokens;
    if (prompt_tokens + params.max_tokens > s.n_ctx) {
        error = "context_too_small";
        return {};
    }

    llama_pos n_past = 0;
    if (mtmd_helper_eval_chunks(s.mctx, s.ctx, chunks.get(), 0, 0, s.n_batch, true, &n_past) != 0) {
        error = cancel.load() ? "cancelled" : "prompt_eval_failed";
        return {};
    }
    timings.image_ms = elapsed_ms(image_started);

    const llama_vocab * vocab = llama_model_get_vocab(s.model);
    std::unique_ptr<llama_sampler, SamplerDeleter> grammar;
    if (!params.grammar.empty()) {
        grammar.reset(llama_sampler_init_grammar(vocab, params.grammar.c_str(), "root"));
        if (!grammar) {
            error = "grammar_invalid";
            return {};
        }
    }

    const auto generate_started = std::chrono::steady_clock::now();
    const int n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> candidates;
    ItemTracker items;
    std::string output;
    llama_batch batch = llama_batch_init(1, 0, 1);
    char piece[256];
    int produced = 0;
    bool finished = false;
    for (; produced < params.max_tokens; ++produced) {
        if (cancel.load()) {
            error = "cancelled";
            break;
        }
        const llama_token token = sample_greedy(s.ctx, grammar.get(), n_vocab, candidates);
        if (token == LLAMA_TOKEN_NULL || llama_vocab_is_eog(vocab, token)) {
            finished = true;
            break;
        }
        if (grammar) llama_sampler_accept(grammar.get(), token);
        const int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) {
            output.append(piece, static_cast<size_t>(n));
            // Greedy decoding of a small model can fall into a loop that
            // repeats the same item until the list is full. Stop at the first
            // exact repeat and close the JSON ourselves.
            if (stop_on_repeated_item(output, items)) {
                ++produced;
                finished = true;
                break;
            }
        }

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        if (llama_decode(s.ctx, batch) != 0) {
            error = cancel.load() ? "cancelled" : "decode_failed";
            break;
        }
        ++n_past;
    }
    llama_batch_free(batch);
    timings.generate_ms = elapsed_ms(generate_started);
    timings.output_tokens = produced;
    if (!error.empty()) return {};
    // Out of tokens in the middle of an item: keep the complete ones.
    if (!finished && !params.grammar.empty() && items.last_item_end > 0) {
        output.resize(items.last_item_end);
        output += "]}";
    }
    return output;
}

std::string json_escape(const std::string & text) {
    std::string out;
    out.reserve(text.size() + 8);
    for (const unsigned char c : text) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

}  // namespace cruxvision

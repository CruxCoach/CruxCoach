// Desktop harness for the app's food photo recognition (FEAT-069).
//
// Runs exactly the native engine, prompt and grammar the app ships, so a
// prompt change can be evaluated on a laptop or server before it reaches a
// phone. Prints one JSON line per image.
//
//   food_vision_cli --model M.gguf --mmproj P.gguf
//       --assets androidApp/src/main/assets/foodvision [--max-side 640]
//       [--threads 4] img1.jpg [img2.jpg ...]

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "food_vision.h"

// libmtmd already contains a non-static stb_image; keep this copy private.
#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

namespace {

std::string read_file(const std::string & path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        std::fprintf(stderr, "cannot read %s\n", path.c_str());
        std::exit(2);
    }
    std::stringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

std::string trim(const std::string & s) {
    const auto begin = s.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) return {};
    const auto end = s.find_last_not_of(" \t\r\n");
    return s.substr(begin, end - begin + 1);
}

// Box-filter downscale, close to what Android's filtered Bitmap scaling does
// for the factors used here (a phone photo down to 640 px).
std::vector<uint8_t> downscale(const uint8_t * src, int w, int h, int max_side, int & out_w, int & out_h) {
    const double scale = std::min(1.0, static_cast<double>(max_side) / std::max(w, h));
    out_w = std::max(1, static_cast<int>(w * scale + 0.5));
    out_h = std::max(1, static_cast<int>(h * scale + 0.5));
    std::vector<uint8_t> dst(static_cast<size_t>(out_w) * out_h * 3);
    for (int y = 0; y < out_h; ++y) {
        const int y0 = y * h / out_h;
        const int y1 = std::max(y0 + 1, (y + 1) * h / out_h);
        for (int x = 0; x < out_w; ++x) {
            const int x0 = x * w / out_w;
            const int x1 = std::max(x0 + 1, (x + 1) * w / out_w);
            unsigned long sum[3] = {0, 0, 0};
            for (int yy = y0; yy < y1; ++yy) {
                for (int xx = x0; xx < x1; ++xx) {
                    const uint8_t * p = src + (static_cast<size_t>(yy) * w + xx) * 3;
                    sum[0] += p[0]; sum[1] += p[1]; sum[2] += p[2];
                }
            }
            const unsigned long n = static_cast<unsigned long>((y1 - y0) * (x1 - x0));
            uint8_t * q = dst.data() + (static_cast<size_t>(y) * out_w + x) * 3;
            q[0] = static_cast<uint8_t>(sum[0] / n);
            q[1] = static_cast<uint8_t>(sum[1] / n);
            q[2] = static_cast<uint8_t>(sum[2] / n);
        }
    }
    return dst;
}

void quiet_log(int level, const char * text) {
    if (level >= 3 /* GGML_LOG_LEVEL_WARN */) std::fputs(text, stderr);
}

}  // namespace

int main(int argc, char ** argv) {
    cruxvision::LoadParams load;
    std::string assets;
    int max_side = 640;
    std::vector<std::string> images;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto next = [&]() -> std::string {
            if (i + 1 >= argc) { std::fprintf(stderr, "missing value for %s\n", arg.c_str()); std::exit(2); }
            return argv[++i];
        };
        if (arg == "--model") load.model_path = next();
        else if (arg == "--mmproj") load.mmproj_path = next();
        else if (arg == "--assets") assets = next();
        else if (arg == "--threads") load.n_threads = std::atoi(next().c_str());
        else if (arg == "--ctx") load.n_ctx = std::atoi(next().c_str());
        else if (arg == "--image-max-tokens") load.image_max_tokens = std::atoi(next().c_str());
        else if (arg == "--max-side") max_side = std::atoi(next().c_str());
        else images.push_back(arg);
    }
    if (load.model_path.empty() || load.mmproj_path.empty() || assets.empty() || images.empty()) {
        std::fprintf(stderr, "usage: %s --model M --mmproj P --assets DIR [--threads N] [--max-side PX] images...\n", argv[0]);
        return 2;
    }

    cruxvision::set_log_sink(quiet_log);
    cruxvision::RunParams run;
    run.system_prompt = trim(read_file(assets + "/system-v1.txt"));
    run.user_prompt = trim(read_file(assets + "/user-v1.txt"));
    run.grammar = read_file(assets + "/grammar-v1.gbnf");

    std::string error;
    cruxvision::Timings load_timings;
    auto engine = cruxvision::Engine::load(load, error, &load_timings);
    if (!engine) {
        std::printf("{\"ok\":false,\"error\":\"%s\"}\n", error.c_str());
        return 1;
    }
    std::fprintf(stderr, "loaded in %.0f ms\n", load_timings.load_ms);

    std::atomic<bool> cancel(false);
    for (const auto & path : images) {
        int w = 0, h = 0, channels = 0;
        uint8_t * pixels = stbi_load(path.c_str(), &w, &h, &channels, 3);
        if (pixels == nullptr) {
            std::printf("{\"image\":\"%s\",\"ok\":false,\"error\":\"decode_failed\"}\n", cruxvision::json_escape(path).c_str());
            continue;
        }
        int sw = 0, sh = 0;
        std::vector<uint8_t> rgb = downscale(pixels, w, h, max_side, sw, sh);
        stbi_image_free(pixels);
        run.rgb = rgb.data();
        run.width = sw;
        run.height = sh;
        cruxvision::Timings t;
        std::string err;
        const std::string out = engine->run(run, cancel, t, err);
        std::printf("{\"image\":\"%s\",\"ok\":%s,\"error\":\"%s\",\"text\":\"%s\",\"width\":%d,\"height\":%d,"
                    "\"prompt_tokens\":%d,\"output_tokens\":%d,\"image_ms\":%.0f,\"generate_ms\":%.0f}\n",
                    cruxvision::json_escape(path).c_str(), err.empty() ? "true" : "false", err.c_str(),
                    cruxvision::json_escape(out).c_str(), sw, sh, t.prompt_tokens, t.output_tokens, t.image_ms, t.generate_ms);
        std::fflush(stdout);
    }
    return 0;
}

// Offline image worker. A separate executable keeps its GGML symbols and
// allocations out of the proven llama.cpp process. No server or network calls.
#include "stable-diffusion.h"
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

static std::string read_text(const char* path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("Cannot open prompt file");
    std::string s((std::istreambuf_iterator<char>(f)), {});
    if (s.size() > 32768) throw std::runtime_error("Prompt exceeds 32 KiB");
    return s;
}
static void stage(const char* s) { std::cout << "@@STAGE " << s << std::endl; }
static void log_cb(sd_log_level_t level, const char* text, void*) {
    if (level >= SD_LOG_INFO && text) { std::cerr << text << std::flush; }
}
static void progress_cb(int step, int total, float, void*) {
    std::cout << "@@STEP " << step << " " << total << std::endl;
}
static void write_u32(std::ofstream& f, uint32_t v) {
    for (int i=0; i<4; ++i) f.put(static_cast<char>((v >> (8*i)) & 255));
}
int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--self-test") {
        std::cout << "ROSALINA_IMAGE_WORKER_V1 " << sd_commit() << std::endl;
        return 0;
    }
    // model prompt.txt negative.txt init.rgb|- output.rimg width height steps seed strength threads
    if (argc != 12) { std::cerr << "Expected 11 rendering arguments\n"; return 2; }
    sd_image_t* images = nullptr;
    int count = 0;
    try {
        const int w = std::stoi(argv[6]), h = std::stoi(argv[7]);
        const int steps = std::stoi(argv[8]), threads = std::stoi(argv[11]);
        const auto seed = std::stoll(argv[9]);
        const float strength = std::stof(argv[10]);
        if (w < 128 || h < 128 || w % 64 || h % 64 || w*h > 512*512 || steps < 1 || steps > 30 || threads < 1 || threads > 4 || strength < .1f || strength > .9f)
            throw std::runtime_error("Invalid generation limits");
        const auto prompt = read_text(argv[2]), negative = read_text(argv[3]);
        if (prompt.empty()) throw std::runtime_error("Empty image prompt");
        sd_set_log_callback(log_cb, nullptr);
        sd_set_progress_callback(progress_cb, nullptr);
        stage("Loading image model");
        sd_ctx_params_t cp;
        sd_ctx_params_init(&cp);
        cp.model_path = argv[1];
        cp.n_threads = threads;
        cp.backend = "cpu";
        cp.params_backend = "cpu";
        cp.enable_mmap = true;
        cp.flash_attn = true;
        std::unique_ptr<sd_ctx_t, decltype(&free_sd_ctx)> ctx(new_sd_ctx(&cp), free_sd_ctx);
        if (!ctx) throw std::runtime_error("Image model could not be loaded; see native log");
        if (!sd_ctx_supports_image_generation(ctx.get())) throw std::runtime_error("This is not an image-generation model");
        sd_img_gen_params_t gp;
        sd_img_gen_params_init(&gp);
        gp.prompt = prompt.c_str();
        gp.negative_prompt = negative.c_str();
        gp.width = w; gp.height = h; gp.batch_count = 1;
        gp.seed = seed; gp.strength = strength;
        gp.sample_params.sample_method = EULER_A_SAMPLE_METHOD;
        gp.sample_params.scheduler = DISCRETE_SCHEDULER;
        gp.sample_params.sample_steps = steps;
        gp.sample_params.guidance.txt_cfg = 7.0f;
        gp.vae_tiling_params.enabled = true;
        gp.vae_tiling_params.tile_size_w = 256;
        gp.vae_tiling_params.tile_size_h = 256;
        std::vector<uint8_t> initial;
        if (std::string(argv[4]) != "-") {
            stage("Preparing reference photo");
            initial.resize(static_cast<size_t>(w)*h*3);
            std::ifstream f(argv[4], std::ios::binary);
            if (!f.read(reinterpret_cast<char*>(initial.data()), initial.size()) || f.peek() != EOF)
                throw std::runtime_error("Reference RGB data has incorrect dimensions");
            gp.init_image = {static_cast<uint32_t>(w), static_cast<uint32_t>(h), 3, initial.data()};
        }
        stage("Generating image");
        if (!generate_image(ctx.get(), &gp, &images, &count) || !images || count != 1)
            throw std::runtime_error("Image generation failed; see native log");
        const auto& im = images[0];
        if (!im.data || im.width != static_cast<uint32_t>(w) || im.height != static_cast<uint32_t>(h) || im.channel != 3)
            throw std::runtime_error("Unexpected image output shape");
        stage("Encoding result");
        std::ofstream out(argv[5], std::ios::binary);
        out.write("RIMG", 4); write_u32(out, im.width); write_u32(out, im.height); write_u32(out, im.channel);
        out.write(reinterpret_cast<char*>(im.data), static_cast<size_t>(im.width)*im.height*im.channel);
        out.flush();
        if (!out) throw std::runtime_error("Unable to save native image output");
        for (int i=0; i<count; ++i) std::free(images[i].data);
        std::free(images); images = nullptr;
        stage("Native render finished");
        return 0;
    } catch (const std::exception& e) {
        if (images) { for (int i=0; i<count; ++i) std::free(images[i].data); std::free(images); }
        std::cerr << "ROSALINA_ERROR: " << e.what() << std::endl;
        return 1;
    }
}

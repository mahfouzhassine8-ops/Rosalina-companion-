// Actual text-conditioned image-to-video diffusion. No pan/zoom or still-frame loops.
// Isolated executable: no GGML symbol sharing with the locked chat/image engines.
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
#if defined(__linux__) || defined(__ANDROID__)
#include <unistd.h>
#endif

static void stage(const std::string& s) { std::cout << "@@STAGE " << s << std::endl; }
static void log_cb(sd_log_level_t l, const char* s, void*) {
    if(l >= SD_LOG_INFO && s) std::cerr << s << std::flush;
}
static void progress_cb(int n,int total,float seconds,void*) {
    std::cout << "@@STEP " << n << ' ' << total << ' ' << seconds << std::endl;
}
static std::string text_file(const char* path) {
    std::ifstream f(path,std::ios::binary);
    if(!f) throw std::runtime_error("Cannot read prompt file");
    f.seekg(0,std::ios::end); const auto n=f.tellg();
    if(n < 0 || n > 16384) throw std::runtime_error("Prompt exceeds 16 KiB");
    f.seekg(0); return std::string(std::istreambuf_iterator<char>(f),{});
}
static void u32(std::ofstream& f,uint32_t n) {
    for(int i=0;i<4;++i) f.put(static_cast<char>((n>>(8*i))&255));
}
struct Frames {
    sd_image_t* data=nullptr; int count=0;
    ~Frames() { if(data) { for(int i=0;i<count;++i) std::free(data[i].data); std::free(data); } }
};
int main(int argc,char** argv) {
    if(argc==2 && std::string(argv[1])=="--self-test") {
        std::cout << "ROSALINA_MOTION_V1 " << sd_commit() << " CPU\n"; return 0;
    }
    // diffusion.gguf text_encoder.gguf tae.safetensors prompt negative reference.rgb output.rvf width height frames steps seed threads
    if(argc!=14) { std::cerr << "Expected 13 motion arguments\n"; return 2; }
    try {
#if defined(__linux__) || defined(__ANDROID__)
        std::cout << "@@PID " << static_cast<long long>(getpid()) << std::endl;
#endif
        const int w=std::stoi(argv[8]),h=std::stoi(argv[9]),frames=std::stoi(argv[10]);
        const int steps=std::stoi(argv[11]),threads=std::stoi(argv[13]);
        const auto seed=std::stoll(argv[12]);
        // Small shapes/frame counts are used by CI, not offered as shorter user clips.
        if(w<64 || h<64 || w%32 || h%32 || w*h>65536 || frames<5 || frames>81 || (frames-1)%4 || steps<1 || steps>30 || threads<1 || threads>4)
            throw std::runtime_error("Motion parameters outside tested bounds");
        const auto prompt=text_file(argv[4]),negative=text_file(argv[5]);
        if(prompt.empty()) throw std::runtime_error("Describe the motion first");
        std::vector<uint8_t> pixels(static_cast<size_t>(w)*h*3);
        std::ifstream ref(argv[6],std::ios::binary);
        if(!ref.read(reinterpret_cast<char*>(pixels.data()),pixels.size()) || ref.peek()!=EOF)
            throw std::runtime_error("Reference photo RGB dimensions do not match request");
        sd_set_log_callback(log_cb,nullptr);
        sd_set_progress_callback(progress_cb,nullptr);
        stage("Loading Wan video model and prompt encoder");
        sd_ctx_params_t cp; sd_ctx_params_init(&cp);
        cp.diffusion_model_path=argv[1]; cp.t5xxl_path=argv[2]; cp.taesd_path=argv[3];
        cp.tae_preview_only=false; cp.enable_mmap=true;
        cp.n_threads=threads; cp.backend="cpu"; cp.params_backend="cpu";
        cp.flash_attn=true; cp.diffusion_flash_attn=true;
        cp.disable_prefetch=true;
        std::unique_ptr<sd_ctx_t,decltype(&free_sd_ctx)> ctx(new_sd_ctx(&cp),free_sd_ctx);
        if(!ctx) throw std::runtime_error("Video models failed to load; see native diagnostics");
        if(!sd_ctx_supports_video_generation(ctx.get())) throw std::runtime_error("Loaded model does not support video generation");
        stage("Encoding your photo and motion description");
        sd_vid_gen_params_t gp; sd_vid_gen_params_init(&gp);
        gp.prompt=prompt.c_str(); gp.negative_prompt=negative.c_str();
        gp.init_image={static_cast<uint32_t>(w),static_cast<uint32_t>(h),3,pixels.data()};
        gp.width=w; gp.height=h; gp.video_frames=frames; gp.fps=8; gp.seed=seed;
        gp.sample_params.sample_method=EULER_SAMPLE_METHOD;
        gp.sample_params.sample_steps=steps; gp.sample_params.guidance.txt_cfg=5.0f;
        gp.sample_params.flow_shift=3.0f;
        gp.sample_params.scheduler=sd_get_default_scheduler(ctx.get(),EULER_SAMPLE_METHOD);
        gp.vae_tiling_params.enabled=true;
        gp.vae_tiling_params.tile_size_w=128; gp.vae_tiling_params.tile_size_h=128;
        Frames output; sd_audio_t* audio=nullptr; int native_fps=8;
        stage("Generating new video frames locally");
        const bool ok=generate_video(ctx.get(),&gp,&output.data,&output.count,&audio,&native_fps);
        if(audio) { free_sd_audio(audio); std::free(audio); }
        if(!ok || !output.data || output.count<frames)
            throw std::runtime_error("Video generation did not return the requested frames");
        stage("Writing generated frames");
        std::ofstream f(argv[7],std::ios::binary);
        if(!f) throw std::runtime_error("Cannot create frame output");
        f.write("RVF1",4); u32(f,w); u32(f,h); u32(f,frames); u32(f,8);
        for(int i=0;i<frames;++i) {
            const auto& im=output.data[i];
            if(!im.data || im.width!=static_cast<uint32_t>(w) || im.height!=static_cast<uint32_t>(h) || im.channel!=3)
                throw std::runtime_error("Unexpected generated frame format");
            f.write(reinterpret_cast<char*>(im.data),static_cast<size_t>(w)*h*3);
        }
        f.flush(); if(!f) throw std::runtime_error("Frame output could not be completed");
        std::cout << "@@FRAMES " << frames << " 8 " << native_fps << std::endl;
        stage("Video frames ready for MP4 encoding"); return 0;
    } catch(const std::exception& e) { std::cerr << "ROSALINA_ERROR: " << e.what() << std::endl; return 1; }
}

#!/usr/bin/env python3
"""Derive workers using exact anchors, leaving all locked native source directories unchanged."""
from pathlib import Path
import sys
root=Path(__file__).resolve().parent.parent
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
def replace(source,old,new):
    if source.count(old)!=1:raise RuntimeError(f'Expected one preservation anchor: {old[:100]!r}')
    return source.replace(old,new,1)
image=(root/'image-engine/worker.cpp').read_text()
image=replace(image,'#include "stable-diffusion.h"','#include "stable-diffusion.h"\n#include "backend.h"\n#include <atomic>')
image=replace(image,'static void log_cb(sd_log_level_t level, const char* text, void*) {\n    if (level >= SD_LOG_INFO && text) { std::cerr << text << std::flush; }\n}\nstatic void progress_cb(int step, int total, float, void*) {\n    std::cout << "@@STEP " << step << " " << total << std::endl;\n}',r'''static std::atomic<bool> sampling{false};
static std::atomic<int> requested_steps{0};
static void log_cb(sd_log_level_t level, const char* text, void*) {
    if(!text)return;
    const std::string line(text);
    if(line.find("generating image:")!=std::string::npos) { sampling.store(true);stage("Sampling"); }
    if(line.find("sampling completed")!=std::string::npos) { sampling.store(false);stage("Decoding"); }
    if(line.find("get_learned_condition completed")!=std::string::npos)stage("Encoding prompt");
    if(level>=SD_LOG_INFO)std::cerr << text << std::flush;
}
static void progress_cb(int step, int total, float seconds, void*) {
    if(sampling.load() && total>0 && total<=requested_steps.load() && step>=1 && step<=total)
        std::cout << "@@SAMPLE " << step << ' ' << total << ' ' << seconds << std::endl;
}''')
image=replace(image,'    try {\n        const int w','    try {\n        std::cout << "@@PID " << getpid() << std::endl;\n        const auto backend=selected_backend();\n        const int w')
image=replace(image,'        const auto seed = std::stoll(argv[9]);','        requested_steps.store(steps);\n        const auto seed = std::stoll(argv[9]);')
image=replace(image,'cp.backend = "cpu";','cp.backend = backend.c_str();')
image=replace(image,'cp.params_backend = "cpu";','cp.params_backend = backend.c_str();')
image=replace(image,'        if (!sd_ctx_supports_image_generation','        report_backend(backend);\n        if (!sd_ctx_supports_image_generation')
image=replace(image,'stage("Generating image");','stage("Encoding prompt");')
image=replace(image,'stage("Encoding result");','stage("Saving");')
motion=(root/'motion-engine/worker.cpp').read_text()
motion=replace(motion,'#include "stable-diffusion.h"','#include "stable-diffusion.h"\n#include "backend.h"')
motion=replace(motion,'static std::atomic<int> progress_phase{0};','static std::atomic<int> requested_steps{0};\nstatic std::atomic<int> progress_phase{0};')
motion=replace(motion,'if(progress_phase.load()==1)','if(progress_phase.load()==1 && total>0 && total<=requested_steps.load() && n>=1 && n<=total)')
motion=replace(motion,'        const auto seed=std::stoll(argv[12]);','        requested_steps.store(steps);\n        const auto backend=selected_backend();\n        const auto seed=std::stoll(argv[12]);')
motion=replace(motion,'cp.n_threads=threads; cp.backend="cpu"; cp.params_backend="cpu";','cp.n_threads=threads; cp.backend=backend.c_str(); cp.params_backend=backend.c_str();')
motion=replace(motion,'        if(!sd_ctx_supports_video_generation','        report_backend(backend);\n        if(!sd_ctx_supports_video_generation')
for name,source in [('image',image),('motion',motion)]:
    # Existing --self-test semantics remain intact; --probe actually dispatches computation.
    anchor='int main(int argc, char** argv) {' if 'int main(int argc, char** argv) {' in source else 'int main(int argc,char** argv) {'
    source=replace(source,anchor,anchor+'\n    if(argc==2 && std::string(argv[1])=="--probe") {\n        try { return probe_backend(); } catch(const std::exception& e) { std::cerr << "PROBE_ERROR: " << e.what() << std::endl; return 1; }\n    }')
    (out/f'{name}.cpp').write_text(source)

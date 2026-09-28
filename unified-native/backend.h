#pragma once
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#include <unistd.h>

static std::string selected_backend() {
#ifdef ROSALINA_VULKAN
    ggml_backend_load_all();
    for(size_t i=0;i<ggml_backend_dev_count();++i) {
        auto dev=ggml_backend_dev_get(i);
        std::string name=ggml_backend_dev_name(dev);
        if(name.find("Vulkan")!=std::string::npos || name.find("vulkan")!=std::string::npos) {
            size_t free=0,total=0;ggml_backend_dev_memory(dev,&free,&total);
            std::cout << "@@DEVICE " << name << " description=" << ggml_backend_dev_description(dev)
                      << " free=" << free << " total=" << total << std::endl;
            return name;
        }
    }
    throw std::runtime_error("No Vulkan device registered at runtime");
#else
    return "cpu";
#endif
}
static void report_backend(const std::string& backend) {
    std::cout << "@@BACKEND " << backend;
#ifdef ROSALINA_VULKAN
    std::cout << " model context initialized; per-operation CPU fallback may occur";
#endif
    std::cout << std::endl;
}

/** An actual isolated device computation, not a compile-time Vulkan flag or capability string. */
static int probe_backend() {
    std::cout << "@@PID " << getpid() << "\n@@STAGE Checking backend computation" << std::endl;
    const auto name=selected_backend();
    ggml_backend_load_all();
    ggml_backend_dev_t device=nullptr;
    for(size_t i=0;i<ggml_backend_dev_count();++i) {
        auto d=ggml_backend_dev_get(i);
        if(std::string(ggml_backend_dev_name(d))==name || (name=="cpu" && ggml_backend_dev_type(d)==GGML_BACKEND_DEVICE_TYPE_CPU)){device=d;break;}
    }
    if(!device)throw std::runtime_error("Requested probe device unavailable");
    struct Resources {
        ggml_context* ctx=nullptr;ggml_backend_t backend=nullptr;ggml_backend_buffer_t buffer=nullptr;
        ~Resources(){if(buffer)ggml_backend_buffer_free(buffer);if(ctx)ggml_free(ctx);if(backend)ggml_backend_free(backend);}
    } owned;
    owned.backend=ggml_backend_dev_init(device,nullptr);
    if(!owned.backend)throw std::runtime_error("Backend initialization failed");
    owned.ctx=ggml_init({16*1024*1024,nullptr,true});
    if(!owned.ctx)throw std::runtime_error("Probe graph allocation failed");
    auto a=ggml_new_tensor_2d(owned.ctx,GGML_TYPE_F32,32,32);
    auto b=ggml_new_tensor_2d(owned.ctx,GGML_TYPE_F32,32,32);
    auto output=ggml_mul_mat(owned.ctx,a,b);
    auto graph=ggml_new_graph(owned.ctx);ggml_build_forward_expand(graph,output);
    if(!ggml_backend_dev_supports_op(device,output))throw std::runtime_error("Backend cannot execute probe operation");
    owned.buffer=ggml_backend_alloc_ctx_tensors(owned.ctx,owned.backend);
    if(!owned.buffer)throw std::runtime_error("Backend probe buffer allocation failed");
    std::vector<float> input(1024,1.f),result(1024,0.f);
    ggml_backend_tensor_set(a,input.data(),0,input.size()*sizeof(float));
    ggml_backend_tensor_set(b,input.data(),0,input.size()*sizeof(float));
    if(ggml_backend_graph_compute(owned.backend,graph)!=GGML_STATUS_SUCCESS)throw std::runtime_error("Backend graph compute failed");
    ggml_backend_synchronize(owned.backend);
    ggml_backend_tensor_get(output,result.data(),0,result.size()*sizeof(float));
    for(auto value:result)if(!std::isfinite(value) || std::fabs(value-32.f)>.01f)throw std::runtime_error("Backend computation returned incorrect values");
    std::cout << "@@BACKEND " << ggml_backend_name(owned.backend) << " compute probe passed (not a model benchmark)\n@@PROBE PASS" << std::endl;
    return 0;
}

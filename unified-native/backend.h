#pragma once
#include "ggml-backend.h"
#include <stdexcept>
#include <iostream>
#include <string>
#include <unistd.h>
static std::string selected_backend() {
#ifdef ROSALINA_VULKAN
    ggml_backend_load_all();
    for(size_t i=0;i<ggml_backend_dev_count();++i) {
        auto dev=ggml_backend_dev_get(i);
        std::string name=ggml_backend_dev_name(dev);
        if(name.find("Vulkan")!=std::string::npos || name.find("vulkan")!=std::string::npos) {
            size_t free=0,total=0;ggml_backend_dev_memory(dev,&free,&total);
            std::cout << "@@DEVICE " << name << " free=" << free << " total=" << total << std::endl;
            return name;
        }
    }
    throw std::runtime_error("No Vulkan device was registered at runtime");
#else
    return "cpu";
#endif
}
static void report_backend(const std::string& backend) {
    std::cout << "@@BACKEND " << backend;
#ifdef ROSALINA_VULKAN
    std::cout << " context initialized; candidate, per-operation CPU fallback may occur";
#endif
    std::cout << std::endl;
}

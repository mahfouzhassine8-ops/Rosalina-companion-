cmake_minimum_required(VERSION 3.22.1)
project(windows_android_jit_poc LANGUAGES C)
add_library(androidjitprobe SHARED
    probe_jni.c
    probe_engine.c
    android_jit_region.c
    ../../../../vendor/windowsios/core/classify.c
    ../../../../vendor/windowsios/core/operands.c)
target_include_directories(androidjitprobe PRIVATE
    ../../../../vendor/windowsios/core)
target_compile_options(androidjitprobe PRIVATE -O2 -Wall -Wextra -fno-omit-frame-pointer)
target_link_libraries(androidjitprobe log)

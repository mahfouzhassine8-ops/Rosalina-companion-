//
// Created by Han Yin on 10/31/25.
//

#ifndef AICHAT_LOGGING_H
#define AICHAT_LOGGING_H

#endif //AICHAT_LOGGING_H

#pragma once
#include <android/log.h>
#include <mutex>
#include <string>

#ifndef LOG_TAG
#define LOG_TAG "ai-chat"
#endif

#ifndef LOG_MIN_LEVEL
#if defined(NDEBUG)
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#else
#define LOG_MIN_LEVEL ANDROID_LOG_VERBOSE
#endif
#endif

static std::mutex ai_recent_error_mutex;
static std::string ai_recent_error_log;

static inline void ai_clear_recent_error_log() {
    std::lock_guard<std::mutex> lock(ai_recent_error_mutex);
    ai_recent_error_log.clear();
}

static inline void ai_record_native_error(const char *text) {
    if (!text || !*text) return;
    std::lock_guard<std::mutex> lock(ai_recent_error_mutex);
    ai_recent_error_log.append(text);
    if (ai_recent_error_log.empty() || ai_recent_error_log.back() != '\n') {
        ai_recent_error_log.push_back('\n');
    }
    constexpr size_t MAX_NATIVE_ERROR_BYTES = 8192;
    if (ai_recent_error_log.size() > MAX_NATIVE_ERROR_BYTES) {
        ai_recent_error_log.erase(0, ai_recent_error_log.size() - MAX_NATIVE_ERROR_BYTES);
    }
}

static inline std::string ai_get_recent_error_log() {
    std::lock_guard<std::mutex> lock(ai_recent_error_mutex);
    return ai_recent_error_log;
}

static inline int ai_should_log(int prio) {
    return __android_log_is_loggable(prio, LOG_TAG, LOG_MIN_LEVEL);
}

#if LOG_MIN_LEVEL <= ANDROID_LOG_VERBOSE
#define LOGv(...) do { if (ai_should_log(ANDROID_LOG_VERBOSE)) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGv(...) ((void)0)
#endif

#if LOG_MIN_LEVEL <= ANDROID_LOG_DEBUG
#define LOGd(...) do { if (ai_should_log(ANDROID_LOG_DEBUG)) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGd(...) ((void)0)
#endif

#define LOGi(...)   do { if (ai_should_log(ANDROID_LOG_INFO )) __android_log_print(ANDROID_LOG_INFO , LOG_TAG, __VA_ARGS__); } while (0)
#define LOGw(...)   do { if (ai_should_log(ANDROID_LOG_WARN )) __android_log_print(ANDROID_LOG_WARN , LOG_TAG, __VA_ARGS__); } while (0)
#define LOGe(...)   do { if (ai_should_log(ANDROID_LOG_ERROR)) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); } while (0)

static inline int android_log_prio_from_ggml(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN:  return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_INFO:  return ANDROID_LOG_INFO;
        case GGML_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        default:                   return ANDROID_LOG_DEFAULT;
    }
}

static inline void aichat_android_log_callback(enum ggml_log_level level,
                                              const char* text,
                                              void* /*user*/) {
    const int prio = android_log_prio_from_ggml(level);
    if (prio >= ANDROID_LOG_WARN) {
        ai_record_native_error(text);
    }
    if (!ai_should_log(prio)) return;
    __android_log_write(prio, LOG_TAG, text);
}

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <memory>
#include <cstring>
#include <chrono> // For timing
#include <thread>
#include <mutex>
#include <future>
#include <utility>
#include <regex>
#include <fstream>
#include <sstream>
#include <iostream>
#include <cstdio>
#include <android/bitmap.h>
#include <libraw/libraw.h>
#include <HalideBuffer.h>
#include <HalideRuntime.h>
#include "ColorPipe.h"
#include "hdrplus_raw_pipeline.h" // Generated header

#define TAG "HdrPlusJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace Halide::Runtime;

namespace {
JavaVM* g_jvm = nullptr;
jclass g_colorProcessorClass = nullptr;
// Thread-local storage for Halide profiling report
thread_local std::string halide_report_buffer;

extern "C" void halide_print(void* user_context, const char* str) {
    halide_report_buffer += str;
}

struct HalideStageStats {
    long align = 0;
    long merge = 0;
    long black_white = 0;
    long white_balance = 0;
    long demosaic = 0;
    long denoise = 0;
    long srgb = 0;
};

HalideStageStats parseHalideReport(const std::string& report) {
    HalideStageStats stats;
    std::regex re("([\\w\\.]+):\\s*([\\d\\.]+)(ms|s)");
    std::smatch match;

    std::string line;
    std::stringstream ss(report);
    while (std::getline(ss, line)) {
        if (std::regex_search(line, match, re)) {
            std::string name = match[1].str();
            float val = 0.0f;
            try {
                val = std::stof(match[2].str());
            } catch (const std::exception& e) {
                LOGE("Failed to parse value in halide report: %s", match[2].str().c_str());
                continue;
            }
            std::string unit = match[3].str();
            long ms = (unit == "s") ? (long)(val * 1000) : (long)val;

            if (name.find("alignment") != std::string::npos || name.find("layer_") != std::string::npos) stats.align += ms;
            else if (name.find("merge_") != std::string::npos) stats.merge += ms;
            else if (name.find("black_white_level") != std::string::npos) stats.black_white += ms;
            else if (name.find("white_balance") != std::string::npos) stats.white_balance += ms;
            else if (name.find("demosaic") != std::string::npos) stats.demosaic += ms;
            else if (name.find("bilateral") != std::string::npos || name.find("desaturate_noise") != std::string::npos) stats.denoise += ms;
            else if (name.find("srgb_output") != std::string::npos) stats.srgb += ms;
        }
    }
    return stats;
}

void fillDebugStats(JNIEnv* env,
                    jlongArray debugStats,
                    jlong copyMs,
                    jlong halideMs,
                    jlong postProcessMs,
                    jlong dngEncodeMs,
                    jlong saveMs,
                    jlong dngJoinWaitMs,
                    jlong totalMs,
                    jlong jniOverheadMs,
                    const HalideStageStats& stageStats) {
    if (debugStats == nullptr) return;
    const jsize len = env->GetArrayLength(debugStats);
    if (len <= 0) return;

    jlong stats[15] = {
            halideMs,
            copyMs,
            postProcessMs,
            dngEncodeMs,
            saveMs,
            dngJoinWaitMs,
            totalMs,
            stageStats.align,
            stageStats.merge,
            stageStats.demosaic,
            stageStats.denoise,
            stageStats.srgb,
            jniOverheadMs,
            stageStats.black_white,
            stageStats.white_balance
    };

    env->SetLongArrayRegion(debugStats, 0, std::min<jsize>(len, 15), stats);
}

struct GlobalBuffers {
    Buffer<uint16_t> inputPool;
    Buffer<uint16_t> outputPool;
    std::vector<uint16_t> interleavedPool;
    bool isInitialized = false;

    void ensureCapacity(int w, int h, int frames) {
        if (!isInitialized || inputPool.width() < w || inputPool.height() < h || inputPool.dim(2).extent() < frames) {
            inputPool = Buffer<uint16_t>(w, h, frames);
            outputPool = Buffer<uint16_t>(w, h, 3);
            interleavedPool.resize(static_cast<size_t>(w) * h * 3);
            isInitialized = true;
            LOGD("Memory pool (re)allocated: %d x %d x %d", w, h, frames);
        }
    }
};

GlobalBuffers g_hdrPlusBuffers;
std::mutex g_hdrPlusMutex;

} // namespace

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = env->FindClass("com/android/example/cameraxbasic/processor/ColorProcessor");
    if (clazz) g_colorProcessorClass = (jclass)env->NewGlobalRef(clazz);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_initMemoryPool(
        JNIEnv* env,
        jobject /* this */,
        jint width,
        jint height,
        jint frames
) {
    LOGD("Initializing memory pool: %dx%d, %d frames", width, height, frames);
    std::lock_guard<std::mutex> lock(g_hdrPlusMutex);
    g_hdrPlusBuffers.ensureCapacity(width, height, frames);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_exportHdrPlus(
        JNIEnv* env,
        jobject /* this */,
        jstring tempRawPath,
        jint width,
        jint height,
        jint orientation,
        jfloat digitalGain,
        jint targetLog,
        jstring lutPath,
        jstring tiffPath,
        jstring jpgPath,
        jstring dngPath,
        jint iso,
        jlong exposureTime,
        jfloat fNumber,
        jfloat focalLength,
        jlong captureTimeMillis,
        jfloatArray ccm,
        jfloatArray whiteBalance
) {
    LOGD("Native exportHdrPlus started.");
    std::lock_guard<std::mutex> lock(g_hdrPlusMutex);

    const char* temp_path_cstr = env->GetStringUTFChars(tempRawPath, 0);
    std::ifstream in(temp_path_cstr, std::ios::binary);
    if (!in.is_open()) {
        LOGE("Failed to open temp raw file: %s", temp_path_cstr);
        env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);
        return -1;
    }

    g_hdrPlusBuffers.ensureCapacity(width, height, 1);
    std::vector<uint16_t>& finalImage = g_hdrPlusBuffers.interleavedPool;
    size_t dataSize = (size_t)width * height * 3;
    in.read((char*)finalImage.data(), dataSize * sizeof(uint16_t));
    if (!in) {
        LOGE("Failed to read complete temp raw data. Read %zu bytes", in.gcount());
        in.close();
        env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);
        return -1;
    }
    in.close();
    env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);

    // Prepare Metadata
    jfloat* wbData = env->GetFloatArrayElements(whiteBalance, nullptr);
    std::vector<float> wbVec = {wbData[0], wbData[1], wbData[2], wbData[3]};
    env->ReleaseFloatArrayElements(whiteBalance, wbData, JNI_ABORT);

    jfloat* ccmData = env->GetFloatArrayElements(ccm, nullptr);
    std::vector<float> ccmVec(9);
    for(int i=0; i<9; ++i) ccmVec[i] = ccmData[i];
    env->ReleaseFloatArrayElements(ccm, ccmData, JNI_ABORT);

    // Load LUT
    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    }

    const char* tiff_path_cstr = (tiffPath) ? env->GetStringUTFChars(tiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (jpgPath) ? env->GetStringUTFChars(jpgPath, 0) : nullptr;
    const char* dng_path_cstr = (dng_path_cstr) ? env->GetStringUTFChars(dngPath, 0) : nullptr;

    // Save DNG
    if (dng_path_cstr) {
        LOGD("Exporting DNG to %s", dng_path_cstr);
        bool dng_ok = write_dng(dng_path_cstr, width, height, finalImage, 65535, iso, exposureTime, fNumber, focalLength, captureTimeMillis, ccmVec, orientation);
        if (!dng_ok) LOGE("Failed to write DNG: %s", dng_path_cstr);
    }

    // Save TIFF/BMP (High Quality Export path)
    if (tiff_path_cstr || jpg_path_cstr) {
        LOGD("Exporting TIFF/JPG: TIFF=%s, JPG=%s", tiff_path_cstr ? tiff_path_cstr : "null", jpg_path_cstr ? jpg_path_cstr : "null");
        process_and_save_image(
            finalImage, width, height, digitalGain, targetLog, lut,
            tiff_path_cstr, jpg_path_cstr,
            1, ccmVec.data(), wbVec.data(), orientation, nullptr,
            false, 1 // Not preview, no downsample
        );
    }

    if (tiffPath) env->ReleaseStringUTFChars(tiffPath, tiff_path_cstr);
    if (jpgPath) env->ReleaseStringUTFChars(jpgPath, jpg_path_cstr);
    if (dngPath) env->ReleaseStringUTFChars(dngPath, dng_path_cstr);

    const char* temp_path_cstr_del = env->GetStringUTFChars(tempRawPath, 0);
    std::remove(temp_path_cstr_del);
    env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr_del);

    LOGD("Native exportHdrPlus finished.");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_processHdrPlus(
        JNIEnv* env,
        jobject /* this */,
        jobjectArray dngBuffers, // Array of ByteBuffers
        jint width,
        jint height,
        jint orientation,
        jint whiteLevel,
        jint blackLevel,
        jfloatArray whiteBalance, // [r, g0, g1, b]
        jfloatArray ccm,          // [3x3] or [3x4] flat
        jint cfaPattern,
        jint iso,
        jlong exposureTime,
        jfloat fNumber,
        jfloat focalLength,
        jlong captureTimeMillis,
        jint targetLog,
        jstring lutPath,
        jstring outputTiffPath,
        jstring outputJpgPath,
        jstring outputDngPath,
        jfloat digitalGain,
        jlongArray debugStats,
        jobject outputBitmap,
        jboolean isAsync,
        jstring tempRawPath
) {
    LOGD("Native processHdrPlus started.");
    std::lock_guard<std::mutex> lock(g_hdrPlusMutex);
    auto nativeStart = std::chrono::high_resolution_clock::now();

    auto jniPrepStart = std::chrono::high_resolution_clock::now();
    int numFrames = env->GetArrayLength(dngBuffers);
    if (numFrames < 2) {
        LOGE("HDR+ requires at least 2 frames.");
        return -1;
    }
    LOGD("Processing %d frames.", numFrames);

    // 1. Prepare Inputs for Halide
    g_hdrPlusBuffers.ensureCapacity(width, height, numFrames);
    uint16_t* rawDataPtr = g_hdrPlusBuffers.inputPool.data();

    std::vector<uint16_t*> framePtrs(numFrames, nullptr);

    for (int i = 0; i < numFrames; i++) {
        jobject bufObj = env->GetObjectArrayElement(dngBuffers, i);
        uint16_t* src = (uint16_t*)env->GetDirectBufferAddress(bufObj);
        env->DeleteLocalRef(bufObj);
        if (!src) {
            LOGE("Failed to get direct buffer address for frame %d", i);
            return -1;
        }
        framePtrs[i] = src;
    }

    const size_t frameSizeBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * sizeof(uint16_t);
    auto copyStart = std::chrono::high_resolution_clock::now();
    #pragma omp parallel for
    for (int i = 0; i < numFrames; i++) {
        std::memcpy(rawDataPtr + (static_cast<size_t>(i) * width * height), framePtrs[i], frameSizeBytes);
    }
    auto copyEnd = std::chrono::high_resolution_clock::now();
    auto copyDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(copyEnd - copyStart).count();

    Buffer<uint16_t> inputBuf = g_hdrPlusBuffers.inputPool;

    // 2. Prepare Metadata
    jfloat* wbData = env->GetFloatArrayElements(whiteBalance, nullptr);
    float wb_r = wbData[0];
    float wb_g0 = wbData[1];
    float wb_g1 = wbData[2];
    float wb_b = wbData[3];

    std::vector<float> wbVec = {wb_r, wb_g0, wb_g1, wb_b};

    env->ReleaseFloatArrayElements(whiteBalance, wbData, JNI_ABORT);

    jfloat* ccmData = env->GetFloatArrayElements(ccm, nullptr);
    std::vector<float> ccmVec(9);
    for(int i=0; i<9; ++i) ccmVec[i] = ccmData[i];
    env->ReleaseFloatArrayElements(ccm, ccmData, JNI_ABORT);

    std::vector<float> identityCCM = {
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
    Buffer<float> ccmHalideBuf(identityCCM.data(), 3, 3);

    // 3. Prepare Output Buffer (16-bit Linear RGB)
    Buffer<uint16_t> outputBuf = g_hdrPlusBuffers.outputPool;
    auto jniPrepEnd = std::chrono::high_resolution_clock::now();
    auto jniPrepMs = std::chrono::duration_cast<std::chrono::milliseconds>(jniPrepEnd - jniPrepStart).count();

    // 4. Run Pipeline
    float compression = 1.0f; // Unused now in our modified generator
    float gain = 1.0f;        // Unused now

    // Map Android SENSOR_INFO_COLOR_FILTER_ARRANGEMENT to Halide CfaPattern
    int halideCfa = 1; // Default RGGB
    switch (cfaPattern) {
        case 0: halideCfa = 1; break; // RGGB
        case 1: halideCfa = 2; break; // GRBG
        case 2: halideCfa = 4; break; // GBRG
        case 3: halideCfa = 3; break; // BGGR
        default: halideCfa = 1; break;
    }

    static bool halideThreadsConfigured = false;
    if (!halideThreadsConfigured) {
        int cpuThreads = static_cast<int>(std::thread::hardware_concurrency());
        if (cpuThreads <= 0) cpuThreads = 4;
        halide_set_num_threads(cpuThreads);
        halideThreadsConfigured = true;
        LOGD("Configured Halide thread pool: %d", cpuThreads);
    }

    int result = 0;
    auto halideStart = std::chrono::high_resolution_clock::now();

    result = hdrplus_raw_pipeline(
        inputBuf,
        (uint16_t)blackLevel,
        (uint16_t)whiteLevel,
        wb_r, wb_g0, wb_g1, wb_b,
        halideCfa,
        ccmHalideBuf,
        compression,
        gain,
        outputBuf
    );

    auto halideEnd = std::chrono::high_resolution_clock::now();

    halide_report_buffer.clear();
    halide_profiler_report(nullptr);
    HalideStageStats stageStats = parseHalideReport(halide_report_buffer);
    halide_profiler_reset();

    auto durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(halideEnd - halideStart).count();

    if (result != 0) {
        LOGE("Halide execution failed with code %d", result);
        return -1;
    }

    LOGD("Halide pipeline finished.");

    // 5. Post-Processing (Log + LUT)

    unsigned char* bitmapPixels = nullptr;
    if (outputBitmap) {
        if (AndroidBitmap_lockPixels(env, outputBitmap, (void**)&bitmapPixels) < 0) {
            LOGE("Failed to lock bitmap pixels.");
            bitmapPixels = nullptr;
        }
    }

    // Load LUT
    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    }

    // Process Output
    std::vector<uint16_t>& finalImage = g_hdrPlusBuffers.interleavedPool;

    int stride_x = outputBuf.dim(0).stride();
    int stride_y = outputBuf.dim(1).stride();
    int stride_c = outputBuf.dim(2).stride();
    const uint16_t* raw_ptr = outputBuf.data();

    uint16_t clip_limit = 16383; // Original limit

    auto postStart = std::chrono::high_resolution_clock::now();
    #pragma omp parallel for
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
             uint16_t r_val = raw_ptr[x * stride_x + y * stride_y + 0 * stride_c];
             uint16_t g_val = raw_ptr[x * stride_x + y * stride_y + 1 * stride_c];
             uint16_t b_val = raw_ptr[x * stride_x + y * stride_y + 2 * stride_c];

             r_val = std::min(r_val, clip_limit);
             g_val = std::min(g_val, clip_limit);
             b_val = std::min(b_val, clip_limit);

             int idx = (y * width + x) * 3;
             finalImage[idx + 0] = r_val << 2;
             finalImage[idx + 1] = g_val << 2;
             finalImage[idx + 2] = b_val << 2;
        }
    }
    auto postEnd = std::chrono::high_resolution_clock::now();
    auto postDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(postEnd - postStart).count();

    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    const char* dng_path_cstr = (outputDngPath) ? env->GetStringUTFChars(outputDngPath, 0) : nullptr;

    std::string tiffPathStr = (tiff_path_cstr) ? tiff_path_cstr : "";
    std::string jpgPathStr = (jpg_path_cstr) ? jpg_path_cstr : "";
    std::string dngPathStr = (dng_path_cstr) ? dng_path_cstr : "";
    std::string dngName(dng_path_cstr ? dng_path_cstr : "");

    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
    if (outputDngPath) env->ReleaseStringUTFChars(outputDngPath, dng_path_cstr);

    int dngWhiteLevel = 65535; // Full 16-bit range now

    auto saveStart = std::chrono::high_resolution_clock::now();

    if (bitmapPixels) {
        process_and_save_image(
            finalImage,
            width,
            height,
            digitalGain,
            targetLog,
            lut,
            nullptr,
            nullptr,
            1,
            ccmVec.data(),
            wbVec.data(),
            orientation,
            bitmapPixels,
            true, 4 // Fast preview, 4x downsample
        );
        AndroidBitmap_unlockPixels(env, outputBitmap);
    }

    size_t lastSlash = dngName.find_last_of('/');
    std::string baseName = (lastSlash != std::string::npos) ? dngName.substr(lastSlash + 1) : "HDRPLUS";
    if (baseName.find(".dng") != std::string::npos) baseName = baseName.substr(0, baseName.find(".dng"));
    if (baseName.find("_linear") != std::string::npos) baseName = baseName.substr(0, baseName.find("_linear"));

    bool runAsync = (bool)isAsync;
    bool hasBgTasks = !tiffPathStr.empty() || !jpgPathStr.empty() || !dngPathStr.empty();

    const char* temp_raw_path_cstr = (tempRawPath) ? env->GetStringUTFChars(tempRawPath, 0) : nullptr;
    if (temp_raw_path_cstr) {
        std::ofstream out(temp_raw_path_cstr, std::ios::binary);
        if (out.is_open()) {
            size_t dataSize = (size_t)width * height * 3;
            out.write((char*)finalImage.data(), dataSize * sizeof(unsigned short));
            out.close();
        } else {
            LOGE("Failed to open temp raw file for writing: %s", temp_raw_path_cstr);
        }
        env->ReleaseStringUTFChars(tempRawPath, temp_raw_path_cstr);
    }

    if (hasBgTasks) {
        auto saveFunc = [
            finalImageData = runAsync ? finalImage : std::vector<uint16_t>(),
            runAsync,
            &finalImage,
            width, height, digitalGain, targetLog, lut,
            tiffPathStr, jpgPathStr, dngPathStr, baseName,
            ccmVec, wbVec, orientation,
            dngWhiteLevel, iso, exposureTime, fNumber, focalLength, captureTimeMillis
        ]() mutable {
            if (!dngPathStr.empty()) {
                write_dng(dngPathStr.c_str(), width, height, runAsync ? finalImageData : finalImage, dngWhiteLevel, iso, exposureTime, fNumber, focalLength, captureTimeMillis, ccmVec, orientation);
            }

            if (!tiffPathStr.empty() || !jpgPathStr.empty()) {
                bool isPrev = !runAsync;
                process_and_save_image(
                    runAsync ? finalImageData : finalImage, width, height, digitalGain, targetLog, lut,
                    tiffPathStr.empty() ? nullptr : tiffPathStr.c_str(),
                    jpgPathStr.empty() ? nullptr : jpgPathStr.c_str(),
                    1, ccmVec.data(), wbVec.data(), orientation, nullptr,
                    isPrev, isPrev ? 4 : 1
                );
            }

            if (runAsync && g_jvm && g_colorProcessorClass) {
                JNIEnv* env = nullptr;
                bool isAttached = false;
                int getEnvStat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
                if (getEnvStat == JNI_EDETACHED) {
                    if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                        isAttached = true;
                    }
                }

                if (env) {
                    jmethodID method = env->GetStaticMethodID(g_colorProcessorClass, "onBackgroundSaveComplete", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;FIZZ)V");
                    if (method) {
                        jstring jBaseName = env->NewStringUTF(baseName.c_str());
                        jstring jTiffPath = tiffPathStr.empty() ? nullptr : env->NewStringUTF(tiffPathStr.c_str());
                        jstring jDngPath = dngPathStr.empty() ? nullptr : env->NewStringUTF(dngPathStr.c_str());
                        jstring jJpgPath = jpgPathStr.empty() ? nullptr : env->NewStringUTF(jpgPathStr.c_str());

                        env->CallStaticVoidMethod(g_colorProcessorClass, method, jBaseName, jTiffPath, jDngPath, jJpgPath, nullptr, 1.0f, orientation, !tiffPathStr.empty(), !jpgPathStr.empty());

                        if (jBaseName) env->DeleteLocalRef(jBaseName);
                        if (jJpgPath) env->DeleteLocalRef(jJpgPath);
                        if (jTiffPath) env->DeleteLocalRef(jTiffPath);
                        if (jDngPath) env->DeleteLocalRef(jDngPath);
                    }
                    if (isAttached) g_jvm->DetachCurrentThread();
                }
            }
        };

        if (runAsync) {
            std::thread(std::move(saveFunc)).detach();
        } else {
            saveFunc();
        }
    }

    auto saveEnd = std::chrono::high_resolution_clock::now();
    auto saveDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(saveEnd - saveStart).count();

    auto nativeEnd = std::chrono::high_resolution_clock::now();
    auto totalDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(nativeEnd - nativeStart).count();

    fillDebugStats(env,
                   debugStats,
                   (jlong)copyDurationMs,
                   (jlong)durationMs,
                   (jlong)postDurationMs,
                   0,
                   (jlong)saveDurationMs,
                   0,
                   (jlong)totalDurationMs,
                   (jlong)jniPrepMs,
                   stageStats);

    return 0;
}

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
            try { val = std::stof(match[2].str()); } catch (...) { continue; }
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

void fillDebugStats(JNIEnv* env, jlongArray debugStats, jlong copyMs, jlong halideMs, jlong postProcessMs, jlong dngEncodeMs, jlong saveMs, jlong dngJoinWaitMs, jlong totalMs, jlong jniOverheadMs, const HalideStageStats& stageStats) {
    if (debugStats == nullptr) return;
    const jsize len = env->GetArrayLength(debugStats);
    if (len <= 0) return;
    jlong stats[15] = { halideMs, copyMs, postProcessMs, dngEncodeMs, saveMs, dngJoinWaitMs, totalMs, stageStats.align, stageStats.merge, stageStats.demosaic, stageStats.denoise, stageStats.srgb, jniOverheadMs, stageStats.black_white, stageStats.white_balance };
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
Java_com_android_example_cameraxbasic_processor_ColorProcessor_initMemoryPool(JNIEnv* env, jobject /* this */, jint width, jint height, jint frames) {
    std::lock_guard<std::mutex> lock(g_hdrPlusMutex);
    g_hdrPlusBuffers.ensureCapacity(width, height, frames);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_exportHdrPlus(
    JNIEnv* env, jobject /* this */, jstring tempRawPath, jint width, jint height, jint orientation, jfloat digitalGain, jint targetLog, jstring lutPath, jstring tiffPath, jstring jpgPath, jstring dngPath,
    jint iso, jlong exposureTime, jfloat fNumber, jfloat focalLength, jlong captureTimeMillis, jfloatArray ccm, jfloatArray whiteBalance, jfloat zoomFactor, jboolean mirror
) {
    LOGD("Native exportHdrPlus started.");
    std::lock_guard<std::mutex> lock(g_hdrPlusMutex);

    if (!tempRawPath) return -1;
    const char* temp_path_cstr = env->GetStringUTFChars(tempRawPath, 0);
    if (!temp_path_cstr) return -1;

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
    bool read_ok = !!in;
    in.close();
    env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);

    if (!read_ok) { LOGE("Failed to read temp raw data."); return -1; }

    jfloat* wbData = env->GetFloatArrayElements(whiteBalance, nullptr);
    std::vector<float> wbVec = {wbData[0], wbData[1], wbData[2], wbData[3]};
    env->ReleaseFloatArrayElements(whiteBalance, wbData, JNI_ABORT);

    jfloat* ccmData = env->GetFloatArrayElements(ccm, nullptr);
    std::vector<float> ccmVec(9); for(int i=0; i<9; ++i) ccmVec[i] = ccmData[i];
    env->ReleaseFloatArrayElements(ccm, ccmData, JNI_ABORT);

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut; if (lut_path_cstr) { lut = load_lut(lut_path_cstr); env->ReleaseStringUTFChars(lutPath, lut_path_cstr); }

    const char* tiff_path_cstr = (tiffPath) ? env->GetStringUTFChars(tiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (jpgPath) ? env->GetStringUTFChars(jpgPath, 0) : nullptr;
    const char* dng_path_cstr = (dngPath) ? env->GetStringUTFChars(dngPath, 0) : nullptr;

    if (dng_path_cstr) {
        LOGD("Exporting DNG to %s", dng_path_cstr);
        write_dng(dng_path_cstr, width, height, finalImage, 65535, iso, exposureTime, fNumber, focalLength, captureTimeMillis, ccmVec, orientation, (bool)mirror);
    }

    bool saveOk = true;
    if (tiff_path_cstr || jpg_path_cstr) {
        LOGD("Exporting TIFF/JPG: TIFF=%s, JPG=%s", tiff_path_cstr ? tiff_path_cstr : "null", jpg_path_cstr ? jpg_path_cstr : "null");
        saveOk = process_and_save_image(finalImage, width, height, digitalGain, targetLog, lut, tiff_path_cstr, jpg_path_cstr, 1, ccmVec.data(), wbVec.data(), orientation, nullptr, false, 1, zoomFactor, (bool)mirror);
    }

    if (tiffPath && tiff_path_cstr) env->ReleaseStringUTFChars(tiffPath, tiff_path_cstr);
    if (jpgPath && jpg_path_cstr) env->ReleaseStringUTFChars(jpgPath, jpg_path_cstr);
    if (dngPath && dng_path_cstr) env->ReleaseStringUTFChars(dngPath, dng_path_cstr);

    const char* temp_path_cstr_del = env->GetStringUTFChars(tempRawPath, 0);
    if (temp_path_cstr_del) {
        std::remove(temp_path_cstr_del);
        env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr_del);
    }

    LOGD("Native exportHdrPlus finished. Success=%d", saveOk);
    return saveOk ? 0 : -2;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_processHdrPlus(
    JNIEnv* env, jobject /* this */, jobjectArray dngBuffers, jint width, jint height, jint orientation, jint whiteLevel, jint blackLevel, jfloatArray whiteBalance, jfloatArray ccm, jint cfaPattern,
    jint iso, jlong exposureTime, jfloat fNumber, jfloat focalLength, jlong captureTimeMillis, jint targetLog, jstring lutPath, jstring outputTiffPath, jstring outputJpgPath, jstring outputDngPath,
    jfloat digitalGain, jlongArray debugStats, jobject outputBitmap, jboolean isAsync, jstring tempRawPath, jfloat zoomFactor, jboolean mirror
) {
    LOGD("Native processHdrPlus started.");
    std::lock_guard<std::mutex> lock(g_hdrPlusMutex);
    auto nativeStart = std::chrono::high_resolution_clock::now();
    auto jniPrepStart = std::chrono::high_resolution_clock::now();

    int numFrames = env->GetArrayLength(dngBuffers);
    if (numFrames < 2) { LOGE("HDR+ requires at least 2 frames."); return -1; }

    g_hdrPlusBuffers.ensureCapacity(width, height, numFrames);
    uint16_t* rawDataPtr = g_hdrPlusBuffers.inputPool.data();
    std::vector<uint16_t*> framePtrs(numFrames, nullptr);
    for (int i = 0; i < numFrames; i++) {
        jobject bufObj = env->GetObjectArrayElement(dngBuffers, i);
        framePtrs[i] = (uint16_t*)env->GetDirectBufferAddress(bufObj);
        env->DeleteLocalRef(bufObj);
        if (!framePtrs[i]) { LOGE("Failed to get direct buffer address for frame %d", i); return -1; }
    }

    const size_t frameSizeBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * sizeof(uint16_t);
    auto copyStart = std::chrono::high_resolution_clock::now();
    #pragma omp parallel for
    for (int i = 0; i < numFrames; i++) { std::memcpy(rawDataPtr + (static_cast<size_t>(i) * width * height), framePtrs[i], frameSizeBytes); }
    auto copyDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - copyStart).count();

    // Create properly dimensioned Halide buffers wrapping the pool memory
    Buffer<uint16_t> inputBuf(rawDataPtr, width, height, numFrames);
    Buffer<uint16_t> outputBuf(g_hdrPlusBuffers.outputPool.data(), width, height, 3);

    jfloat* wbData = env->GetFloatArrayElements(whiteBalance, nullptr);
    float wb_r = wbData[0], wb_g0 = wbData[1], wb_g1 = wbData[2], wb_b = wbData[3];
    std::vector<float> wbVec = {wb_r, wb_g0, wb_g1, wb_b};
    env->ReleaseFloatArrayElements(whiteBalance, wbData, JNI_ABORT);

    jfloat* ccmData = env->GetFloatArrayElements(ccm, nullptr);
    std::vector<float> ccmVec(9); for(int i=0; i<9; ++i) ccmVec[i] = ccmData[i];
    env->ReleaseFloatArrayElements(ccm, ccmData, JNI_ABORT);

    std::vector<float> identityCCM = { 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f };
    Buffer<float> ccmHalideBuf(identityCCM.data(), 3, 3);
    auto jniPrepMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - jniPrepStart).count();

    int halideCfa = 1;
    switch (cfaPattern) { case 0: halideCfa = 1; break; case 1: halideCfa = 2; break; case 2: halideCfa = 4; break; case 3: halideCfa = 3; break; default: halideCfa = 1; break; }

    static bool halideThreadsConfigured = false;
    if (!halideThreadsConfigured) {
        int cpuThreads = (int)std::thread::hardware_concurrency(); if (cpuThreads <= 0) cpuThreads = 4;
        halide_set_num_threads(cpuThreads); halideThreadsConfigured = true;
    }

    auto halideStart = std::chrono::high_resolution_clock::now();
    int halide_res = hdrplus_raw_pipeline(inputBuf, (uint16_t)blackLevel, (uint16_t)whiteLevel, wb_r, wb_g0, wb_g1, wb_b, halideCfa, ccmHalideBuf, 1.0f, 1.0f, outputBuf);
    auto halideDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - halideStart).count();

    halide_report_buffer.clear(); halide_profiler_report(nullptr);
    HalideStageStats stageStats = parseHalideReport(halide_report_buffer); halide_profiler_reset();

    if (halide_res != 0) { LOGE("Halide failed: %d", halide_res); return -1; }

    unsigned char* bitmapPixels = nullptr;
    if (outputBitmap) AndroidBitmap_lockPixels(env, outputBitmap, (void**)&bitmapPixels);

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut; if (lut_path_cstr) { lut = load_lut(lut_path_cstr); env->ReleaseStringUTFChars(lutPath, lut_path_cstr); }

    std::vector<uint16_t>& finalImage = g_hdrPlusBuffers.interleavedPool;
    int stride_x = outputBuf.dim(0).stride(), stride_y = outputBuf.dim(1).stride(), stride_c = outputBuf.dim(2).stride();
    const uint16_t* raw_ptr = outputBuf.data();
    auto postStart = std::chrono::high_resolution_clock::now();
    #pragma omp parallel for
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
             uint16_t r = raw_ptr[x*stride_x + y*stride_y + 0*stride_c];
             uint16_t g = raw_ptr[x*stride_x + y*stride_y + 1*stride_c];
             uint16_t b = raw_ptr[x*stride_x + y*stride_y + 2*stride_c];
             int idx = (y * width + x) * 3;
             // Halide output is scaled by 0.25 (14-bit range).
             // We shift left by 2 to restore full 16-bit range for final saving.
             finalImage[idx+0] = (uint16_t)std::min((int)r << 2, 65535);
             finalImage[idx+1] = (uint16_t)std::min((int)g << 2, 65535);
             finalImage[idx+2] = (uint16_t)std::min((int)b << 2, 65535);
        }
    }
    auto postDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - postStart).count();

    const char* tiff_p_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_p_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    const char* dng_p_cstr = (outputDngPath) ? env->GetStringUTFChars(outputDngPath, 0) : nullptr;
    std::string tiffPathStr = tiff_p_cstr ? tiff_p_cstr : "", jpgPathStr = jpg_p_cstr ? jpg_p_cstr : "", dngPathStr = dng_p_cstr ? dng_p_cstr : "";
    std::string baseName = dngPathStr.empty() ? "HDRPLUS" : dngPathStr.substr(dngPathStr.find_last_of('/')+1);
    if (outputTiffPath && tiff_p_cstr) env->ReleaseStringUTFChars(outputTiffPath, tiff_p_cstr);
    if (outputJpgPath && jpg_p_cstr) env->ReleaseStringUTFChars(outputJpgPath, jpg_p_cstr);
    if (outputDngPath && dng_p_cstr) env->ReleaseStringUTFChars(outputDngPath, dng_p_cstr);

    auto saveStart = std::chrono::high_resolution_clock::now();
    if (bitmapPixels) {
        process_and_save_image(finalImage, width, height, digitalGain, targetLog, lut, nullptr, nullptr, 1, ccmVec.data(), wbVec.data(), orientation, bitmapPixels, true, 4, zoomFactor, (bool)mirror);
        AndroidBitmap_unlockPixels(env, outputBitmap);
    }

    const char* tr_p_cstr = (tempRawPath) ? env->GetStringUTFChars(tempRawPath, 0) : nullptr;
    if (tr_p_cstr) {
        std::ofstream out(tr_p_cstr, std::ios::binary); if (out.is_open()) { out.write((char*)finalImage.data(), (size_t)width*height*3*2); out.close(); }
        env->ReleaseStringUTFChars(tempRawPath, tr_p_cstr);
    }

    if (!tiffPathStr.empty() || !jpgPathStr.empty() || !dngPathStr.empty()) {
        auto saveFunc = [fImg = (bool)isAsync ? finalImage : std::vector<uint16_t>(), isAsync, &finalImage, width, height, digitalGain, targetLog, lut, tiffPathStr, jpgPathStr, dngPathStr, baseName, ccmVec, wbVec, orientation, iso, exposureTime, fNumber, focalLength, captureTimeMillis, zoomFactor, mirror]() mutable {
            const std::vector<uint16_t>& img = isAsync ? fImg : finalImage;
            bool dngOk = true;
            if (!dngPathStr.empty()) dngOk = write_dng(dngPathStr.c_str(), width, height, img, 65535, iso, exposureTime, fNumber, focalLength, captureTimeMillis, ccmVec, orientation, (bool)mirror);

            bool otherOk = true;
            if (!tiffPathStr.empty() || !jpgPathStr.empty()) {
                otherOk = process_and_save_image(img, width, height, digitalGain, targetLog, lut, tiffPathStr.empty()?nullptr:tiffPathStr.c_str(), jpgPathStr.empty()?nullptr:jpgPathStr.c_str(), 1, ccmVec.data(), wbVec.data(), orientation, nullptr, !isAsync, isAsync?1:4, zoomFactor, (bool)mirror);
            }

            if (isAsync && g_jvm && g_colorProcessorClass) {
                JNIEnv* env = nullptr; if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
                    jmethodID m = env->GetStaticMethodID(g_colorProcessorClass, "onBackgroundSaveComplete", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;FIZZ)V");
                    if (m) { jstring jB = env->NewStringUTF(baseName.c_str()), jT = tiffPathStr.empty()?nullptr:env->NewStringUTF(tiffPathStr.c_str()), jD = dngPathStr.empty()?nullptr:env->NewStringUTF(dngPathStr.c_str()), jJ = jpgPathStr.empty()?nullptr:env->NewStringUTF(jpgPathStr.c_str()); env->CallStaticVoidMethod(g_colorProcessorClass, m, jB, jT, jD, jJ, nullptr, 1.0f, orientation, !tiffPathStr.empty(), !jpgPathStr.empty()); if (jB) env->DeleteLocalRef(jB); if (jJ) env->DeleteLocalRef(jJ); if (jT) env->DeleteLocalRef(jT); if (jD) env->DeleteLocalRef(jD); }
                    g_jvm->DetachCurrentThread();
                }
            }
        };
        if (isAsync) std::thread(std::move(saveFunc)).detach(); else saveFunc();
    }
    fillDebugStats(env, debugStats, copyDurationMs, halideDurationMs, postDurationMs, 0, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now()-saveStart).count(), 0, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now()-nativeStart).count(), jniPrepMs, stageStats);
    return 0;
}

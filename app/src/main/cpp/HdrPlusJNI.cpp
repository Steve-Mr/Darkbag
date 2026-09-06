#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <memory>
#include <algorithm>
#include <exception>
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
#include <cmath>
#include <android/bitmap.h>
#include <libraw/libraw.h>
#include <HalideBuffer.h>
#include <HalideRuntime.h>
#include "ColorPipe.h"
#include "hdrplus_fast_pipeline.h"
#include "hdrplus_raw_pipeline.h" // Generated header
#include "hdrplus_high_pipeline.h"
#include "hdrplus_single_pipeline.h" // Generated header for single frame
#include "rawvideo/FastGuidedFilter.h"


#define TAG "HdrPlusJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace Halide::Runtime;

namespace {
constexpr uint16_t kMax14BitValue = 16383; // 2^14 - 1
constexpr uint16_t kMax16BitValue = 65535; // 2^16 - 1
JavaVM* g_jvm = nullptr;
jclass g_colorProcessorClass = nullptr;
jclass g_byteBufferClass = nullptr;
jclass g_captureMetadataClass = nullptr;
jclass g_integerClass = nullptr;
jclass g_longClass = nullptr;
jclass g_floatClass = nullptr;

struct CaptureMetadataFieldIDs {
    jfieldID iso;
    jfieldID exposureTime;
    jfieldID fNumber;
    jfieldID focalLength;
    jfieldID dateTimeOriginal;
    jfieldID make;
    jfieldID model;
    jfieldID uniqueCameraModel;
    jfieldID software;
    jfieldID imageDescription;
} g_metadataFields;

struct BoxedMethodIDs {
    jmethodID intValue;
    jmethodID longValue;
    jmethodID floatValue;
} g_boxedMethods;

thread_local std::string halide_report_buffer;

extern "C" void halide_print(void* user_context, const char* str) {
    halide_report_buffer += str;
}

struct HalideStageStats {
    int64_t align = 0;
    int64_t merge = 0;
    int64_t black_white = 0;
    int64_t white_balance = 0;
    int64_t demosaic = 0;
    int64_t denoise = 0;
    int64_t srgb = 0;
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
                LOGE("Failed to parse value in halide report: %s, error: %s", match[2].str().c_str(), e.what());
                continue;
            }
            std::string unit = match[3].str();
            int64_t ms = (unit == "s") ? (int64_t)(val * 1000) : (int64_t)val;

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

#include <unordered_map>
std::unordered_map<std::string, std::shared_ptr<std::vector<uint16_t>>> g_sharedMemoryMap;
std::mutex g_sharedMemoryMutex;

std::string getStringField(JNIEnv* env, jobject obj, jfieldID fieldID, const std::string& defaultValue) {
    jstring jstr = (jstring)env->GetObjectField(obj, fieldID);
    if (!jstr) return defaultValue;
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    std::string result = cstr ? cstr : defaultValue;
    if (cstr) env->ReleaseStringUTFChars(jstr, cstr);
    env->DeleteLocalRef(jstr);
    return result;
}

int getIntField(JNIEnv* env, jobject obj, jfieldID fieldID, int defaultValue) {
    jobject boxed = env->GetObjectField(obj, fieldID);
    if (!boxed) return defaultValue;
    int result = env->CallIntMethod(boxed, g_boxedMethods.intValue);
    env->DeleteLocalRef(boxed);
    return result;
}

int64_t getLongField(JNIEnv* env, jobject obj, jfieldID fieldID, int64_t defaultValue) {
    jobject boxed = env->GetObjectField(obj, fieldID);
    if (!boxed) return defaultValue;
    int64_t result = (int64_t)env->CallLongMethod(boxed, g_boxedMethods.longValue);
    env->DeleteLocalRef(boxed);
    return result;
}

float getFloatField(JNIEnv* env, jobject obj, jfieldID fieldID, float defaultValue) {
    jobject boxed = env->GetObjectField(obj, fieldID);
    if (!boxed) return defaultValue;
    float result = env->CallFloatMethod(boxed, g_boxedMethods.floatValue);
    env->DeleteLocalRef(boxed);
    return result;
}


} // namespace

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    init_color_pipe(); jclass colorProcClazz = env->FindClass("top/maary/darkbag/processor/ColorProcessor");
    if (!colorProcClazz) return JNI_ERR;
    g_colorProcessorClass = (jclass)env->NewGlobalRef(colorProcClazz);

    jclass byteBufClazz = env->FindClass("java/nio/ByteBuffer");
    if (!byteBufClazz) return JNI_ERR;
    g_byteBufferClass = (jclass)env->NewGlobalRef(byteBufClazz);

    jclass metadataClazz = env->FindClass("top/maary/darkbag/models/CaptureMetadata");
    if (!metadataClazz) return JNI_ERR;
    g_captureMetadataClass = (jclass)env->NewGlobalRef(metadataClazz);

    auto getField = [&](jclass clazz, const char* name, const char* sig) -> jfieldID {
        jfieldID fid = env->GetFieldID(clazz, name, sig);
        if (!fid) {
            LOGE("Failed to find field %s with signature %s", name, sig);
        }
        return fid;
    };

    g_metadataFields.iso = getField(metadataClazz, "iso", "Ljava/lang/Integer;");
    g_metadataFields.exposureTime = getField(metadataClazz, "exposureTime", "Ljava/lang/Long;");
    g_metadataFields.fNumber = getField(metadataClazz, "fNumber", "Ljava/lang/Float;");
    g_metadataFields.focalLength = getField(metadataClazz, "focalLength", "Ljava/lang/Float;");
    g_metadataFields.dateTimeOriginal = getField(metadataClazz, "dateTimeOriginal", "Ljava/lang/Long;");
    g_metadataFields.make = getField(metadataClazz, "make", "Ljava/lang/String;");
    g_metadataFields.model = getField(metadataClazz, "model", "Ljava/lang/String;");
    g_metadataFields.uniqueCameraModel = getField(metadataClazz, "uniqueCameraModel", "Ljava/lang/String;");
    g_metadataFields.software = getField(metadataClazz, "software", "Ljava/lang/String;");
    g_metadataFields.imageDescription = getField(metadataClazz, "imageDescription", "Ljava/lang/String;");

    if (!g_metadataFields.iso || !g_metadataFields.exposureTime || !g_metadataFields.fNumber ||
        !g_metadataFields.focalLength || !g_metadataFields.dateTimeOriginal || !g_metadataFields.make ||
        !g_metadataFields.model || !g_metadataFields.uniqueCameraModel || !g_metadataFields.software ||
        !g_metadataFields.imageDescription) {
        return JNI_ERR;
    }

    auto getBoxedInfo = [&](const char* clazzName, const char* methodName, const char* sig, jclass& outClazz, jmethodID& outMethod) -> bool {
        jclass clazz = env->FindClass(clazzName);
        if (!clazz) return false;
        outClazz = (jclass)env->NewGlobalRef(clazz);
        outMethod = env->GetMethodID(clazz, methodName, sig);
        if (!outMethod) {
            LOGE("Failed to find method %s with signature %s in class %s", methodName, sig, clazzName);
        }
        return outMethod != nullptr;
    };

    if (!getBoxedInfo("java/lang/Integer", "intValue", "()I", g_integerClass, g_boxedMethods.intValue)) return JNI_ERR;
    if (!getBoxedInfo("java/lang/Long", "longValue", "()J", g_longClass, g_boxedMethods.longValue)) return JNI_ERR;
    if (!getBoxedInfo("java/lang/Float", "floatValue", "()F", g_floatClass, g_boxedMethods.floatValue)) return JNI_ERR;

    return JNI_VERSION_1_6;
}

ImageMetadata metadataFromJava(JNIEnv* env, jobject metadataObj) {
    ImageMetadata meta;
    if (!metadataObj) return meta;

    meta.iso = getIntField(env, metadataObj, g_metadataFields.iso, 100);
    meta.exposureTime = getLongField(env, metadataObj, g_metadataFields.exposureTime, 10000000L);
    meta.fNumber = getFloatField(env, metadataObj, g_metadataFields.fNumber, 1.8f);
    meta.focalLength = getFloatField(env, metadataObj, g_metadataFields.focalLength, 0.0f);
    meta.captureTimeMillis = getLongField(env, metadataObj, g_metadataFields.dateTimeOriginal, 0);
    meta.make = getStringField(env, metadataObj, g_metadataFields.make, "Unknown");
    meta.model = getStringField(env, metadataObj, g_metadataFields.model, "Unknown");
    meta.uniqueCameraModel = getStringField(env, metadataObj, g_metadataFields.uniqueCameraModel, meta.model);
    meta.software = getStringField(env, metadataObj, g_metadataFields.software, "Darkbag");
    meta.imageDescription = getStringField(env, metadataObj, g_metadataFields.imageDescription, "Processed by Darkbag");

    return meta;
}

extern "C" JNIEXPORT void JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_initMemoryPool(JNIEnv* env, jobject /* this */, jint width, jint height, jint frames) {

    g_hdrPlusBuffers.ensureCapacity(width, height, frames);
}

extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_exportHdrPlus(
    JNIEnv* env, jobject /* this */, jstring tempRawPath, jint width, jint height, jint orientation, jfloat digitalGain, jint targetLog, jstring lutPath,
    jfloat exposure, jfloat contrast, jfloat saturation, jfloat highlights, jfloat shadows, jfloat whites, jfloat blacks,
    jstring jpgPath, jstring dngPath,
    jfloatArray ccm, jfloatArray whiteBalance, jfloat zoomFactor, jboolean mirror,
    jobject metadata,
    jboolean enableMemoryColor,
    jint colorEngineMode
) {
    LOGD("Native exportHdrPlus started (enableMemoryColor=%d, colorEngineMode=%d).", enableMemoryColor, colorEngineMode);

    if (!tempRawPath) return -1;
    const char* temp_path_cstr = env->GetStringUTFChars(tempRawPath, 0);
    if (!temp_path_cstr) return -1;

    std::shared_ptr<std::vector<uint16_t>> sharedMem;
    {
        std::lock_guard<std::mutex> mapLock(g_sharedMemoryMutex);
        auto it = g_sharedMemoryMap.find(temp_path_cstr);
        if (it != g_sharedMemoryMap.end()) {
            sharedMem = it->second;
            g_sharedMemoryMap.erase(it);
        }
    }

    if (!sharedMem) {
        LOGE("Failed to find shared memory for tempRawPath: %s", temp_path_cstr);
        env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);
        return -1;
    }
    
    // We can use a reference to the shared vector
    const std::vector<uint16_t>& finalImage = *sharedMem;
    env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);

    jfloat* wbData = env->GetFloatArrayElements(whiteBalance, nullptr);
    std::vector<float> wbVec = {wbData[0], wbData[1], wbData[2], wbData[3]};
    env->ReleaseFloatArrayElements(whiteBalance, wbData, JNI_ABORT);

    jfloat* ccmData = env->GetFloatArrayElements(ccm, nullptr);
    std::vector<float> ccmVec(9); for(int i=0; i<9; ++i) ccmVec[i] = ccmData[i];
    env->ReleaseFloatArrayElements(ccm, ccmData, JNI_ABORT);


    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut; if (lut_path_cstr) { lut = load_lut(lut_path_cstr); env->ReleaseStringUTFChars(lutPath, lut_path_cstr); }

    const char* jpg_path_cstr = (jpgPath) ? env->GetStringUTFChars(jpgPath, 0) : nullptr;
    const char* dng_path_cstr = (dngPath) ? env->GetStringUTFChars(dngPath, 0) : nullptr;

    ImageMetadata meta = metadataFromJava(env, metadata);

    if (dng_path_cstr) {
        LOGD("Exporting DNG to %s", dng_path_cstr);
        float baselineExposure = (digitalGain > 0.0f) ? std::log2(digitalGain) : 0.0f;
        write_dng(dng_path_cstr, width, height, finalImage.data(), 1, width, width*height, kMax16BitValue, ccmVec, meta, orientation, (bool)mirror, baselineExposure, wbVec.data());
    }

    bool saveOk = true;
    if (jpg_path_cstr) {
        LOGD("Exporting JPG: JPG=%s", jpg_path_cstr);
        saveOk = process_and_save_image(finalImage.data(), 1, width, width*height, nullptr, 0, 0, width, height, digitalGain, targetLog, lut,
                                        exposure, contrast, saturation, highlights, shadows, whites, blacks,
                                        jpg_path_cstr, nullptr, &meta, 1, ccmVec.data(), wbVec.data(), orientation, nullptr, 0, 0, false, 1, zoomFactor, (bool)mirror, (bool)enableMemoryColor, (int)colorEngineMode);
    }
    if (jpgPath && jpg_path_cstr) env->ReleaseStringUTFChars(jpgPath, jpg_path_cstr);
    if (dngPath && dng_path_cstr) env->ReleaseStringUTFChars(dngPath, dng_path_cstr);

    // No longer a physical file, so we don't delete anything
    // (the shared ptr cleans itself up)

    LOGD("Native exportHdrPlus finished. Success=%d", saveOk);
    return saveOk ? 0 : -2;
}

extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_processHdrPlus(
    JNIEnv* env, jobject /* this */, jobject dngBuffer, jint numFrames, jint width, jint height, jint orientation, jint whiteLevel, jintArray blackLevelPattern, jfloatArray lensShadingMap, jint lensShadingRows, jint lensShadingCols, jboolean useSensorColorMatrix, jfloatArray whiteBalance, jfloatArray ccm, jfloatArray ccmAlt, jboolean exportMatrixAB, jint cfaPattern,
    jint targetLog, jstring lutPath, jstring outputJpgPath, jstring outputDngPath,
    jfloat digitalGain, jlongArray debugStats, jobject outputBitmap, jstring tempRawPath, jfloat zoomFactor, jboolean mirror,
    jobject metadata,
    jboolean enableMemoryColor,
    jint colorEngineMode,
    jboolean enableDualStreamFusion
) {
    LOGD("Native processHdrPlus started (enableMemoryColor=%d, colorEngineMode=%d).", enableMemoryColor, colorEngineMode);
    (void)useSensorColorMatrix;

    auto nativeStart = std::chrono::high_resolution_clock::now();
    auto jniPrepStart = std::chrono::high_resolution_clock::now();

    if (numFrames < 1) { LOGE("Processing requires at least 1 frame."); return -1; }
    if (!dngBuffer) { LOGE("dngBuffer is null"); return -1; }

    g_hdrPlusBuffers.ensureCapacity(width, height, numFrames);
    
    uint16_t* rawDataPtr = (uint16_t*)env->GetDirectBufferAddress(dngBuffer);
    if (!rawDataPtr) { LOGE("Failed to get direct buffer address"); return -1; }
    
    const size_t totalSizeBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * numFrames * sizeof(uint16_t);
    jlong capacity = env->GetDirectBufferCapacity(dngBuffer);
    if (capacity < (jlong)totalSizeBytes) {
        LOGE("Direct buffer capacity %lld is smaller than expected %zu", (long long)capacity, totalSizeBytes);
        return -1;
    }
    
    auto copyDurationMs = 0; // Zero copy!

    // Create properly dimensioned Halide buffers wrapping the pool memory
    Buffer<uint16_t> inputBuf(rawDataPtr, width, height, numFrames);
    Buffer<uint16_t> outputBuf(g_hdrPlusBuffers.outputPool.data(), width, height, 3);

    jfloat* wbData = env->GetFloatArrayElements(whiteBalance, nullptr);
    float wb_r = wbData[0], wb_g0 = wbData[1], wb_g1 = wbData[2], wb_b = wbData[3];
    std::vector<float> wbVec = {wb_r, wb_g0, wb_g1, wb_b};
    env->ReleaseFloatArrayElements(whiteBalance, wbData, JNI_ABORT);
    int bl_pattern[4] = {64, 64, 64, 64};
    if (blackLevelPattern && env->GetArrayLength(blackLevelPattern) >= 4) {
        env->GetIntArrayRegion(blackLevelPattern, 0, 4, bl_pattern);
    }
    uint16_t bl_r = (uint16_t)std::max(0, bl_pattern[0]);
    uint16_t bl_g0 = (uint16_t)std::max(0, bl_pattern[1]);
    uint16_t bl_g1 = (uint16_t)std::max(0, bl_pattern[2]);
    uint16_t bl_b = (uint16_t)std::max(0, bl_pattern[3]);

    std::vector<float> lensShadingVec;
    if (lensShadingMap && lensShadingRows > 0 && lensShadingCols > 0) {
        const jsize l = env->GetArrayLength(lensShadingMap);
        const int expected = 4 * lensShadingRows * lensShadingCols;
        if (l >= expected) {
            lensShadingVec.resize(expected);
            env->GetFloatArrayRegion(lensShadingMap, 0, expected, lensShadingVec.data());
        }
    }

    jfloat* ccmData = env->GetFloatArrayElements(ccm, nullptr);
    std::vector<float> ccmVec(9); for(int i=0; i<9; ++i) ccmVec[i] = ccmData[i];
    env->ReleaseFloatArrayElements(ccm, ccmData, JNI_ABORT);

    std::vector<float> ccmAltVec;
    if (ccmAlt && env->GetArrayLength(ccmAlt) >= 9) {
        jfloat* ccmAltData = env->GetFloatArrayElements(ccmAlt, nullptr);
        ccmAltVec.assign(ccmAltData, ccmAltData + 9);
        env->ReleaseFloatArrayElements(ccmAlt, ccmAltData, JNI_ABORT);
    }

    Buffer<float> ccmHalideBuf(ccmVec.data(), 3, 3);
    auto jniPrepMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - jniPrepStart).count();

    int halideCfa = 1;
    switch (cfaPattern) { case 0: halideCfa = 1; break; case 1: halideCfa = 2; break; case 2: halideCfa = 4; break; case 3: halideCfa = 3; break; default: halideCfa = 1; break; }

    static bool halideThreadsConfigured = false;
    if (!halideThreadsConfigured) {
        int cpuThreads = (int)std::thread::hardware_concurrency(); if (cpuThreads <= 0) cpuThreads = 4;
        halide_set_num_threads(cpuThreads); halideThreadsConfigured = true;
    }

    int iso = 100;
    if (metadata) {
        jclass metaClass = env->GetObjectClass(metadata);
        jmethodID getIso = env->GetMethodID(metaClass, "getIso", "()Ljava/lang/Integer;");
        if (getIso) {
            jobject isoObj = env->CallObjectMethod(metadata, getIso);
            if (isoObj) {
                jclass intClass = env->GetObjectClass(isoObj);
                jmethodID intValue = env->GetMethodID(intClass, "intValue", "()I");
                if (intValue) {
                    iso = env->CallIntMethod(isoObj, intValue);
                }
                env->DeleteLocalRef(intClass);
            }
            env->DeleteLocalRef(isoObj);
        }
        env->DeleteLocalRef(metaClass);
    }
    
    int denoiseLevel = 1;
    if (iso < 400) denoiseLevel = 0;
    else if (iso >= 1600) denoiseLevel = 2;

    Buffer<float> lscMapBuf;
    std::vector<float> dummyLsc = {1.0f, 1.0f, 1.0f, 1.0f};
    if (lensShadingVec.empty()) {
        lscMapBuf = Buffer<float>(dummyLsc.data(), 1, 1, 4);
    } else {
        lscMapBuf = Buffer<float>(lensShadingVec.data(), lensShadingCols, lensShadingRows, 4);
    }

    auto halideStart = std::chrono::high_resolution_clock::now();
    int halide_res;
    if (numFrames == 1) {
        halide_res = hdrplus_single_pipeline(inputBuf, bl_r, bl_g0, bl_g1, bl_b, (uint16_t)whiteLevel, wb_r, wb_g0, wb_g1, wb_b, halideCfa, ccmHalideBuf, lscMapBuf, 1.0f, 1.0f, outputBuf);
    } else {
        if (denoiseLevel == 0) {
            halide_res = hdrplus_fast_pipeline(inputBuf, bl_r, bl_g0, bl_g1, bl_b, (uint16_t)whiteLevel, wb_r, wb_g0, wb_g1, wb_b, halideCfa, ccmHalideBuf, lscMapBuf, 1.0f, 1.0f, outputBuf);
        } else if (denoiseLevel == 2) {
            halide_res = hdrplus_high_pipeline(inputBuf, bl_r, bl_g0, bl_g1, bl_b, (uint16_t)whiteLevel, wb_r, wb_g0, wb_g1, wb_b, halideCfa, ccmHalideBuf, lscMapBuf, 1.0f, 1.0f, outputBuf);
        } else {
            halide_res = hdrplus_raw_pipeline(inputBuf, bl_r, bl_g0, bl_g1, bl_b, (uint16_t)whiteLevel, wb_r, wb_g0, wb_g1, wb_b, halideCfa, ccmHalideBuf, lscMapBuf, 1.0f, 1.0f, outputBuf);
        }
    }
    auto halideDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - halideStart).count();

    halide_report_buffer.clear(); halide_profiler_report(nullptr);
    HalideStageStats stageStats = parseHalideReport(halide_report_buffer); halide_profiler_reset();

    if (halide_res != 0) { LOGE("Halide failed: %d", halide_res); return -1; }

    unsigned char* bitmapPixels = nullptr;
    if (outputBitmap) AndroidBitmap_lockPixels(env, outputBitmap, (void**)&bitmapPixels);

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut; if (lut_path_cstr) { lut = load_lut(lut_path_cstr); env->ReleaseStringUTFChars(lutPath, lut_path_cstr); }

    int stride_x = outputBuf.dim(0).stride(), stride_y = outputBuf.dim(1).stride(), stride_c = outputBuf.dim(2).stride();
    uint16_t* raw_ptr = outputBuf.data();

    if (enableDualStreamFusion && iso >= 600) {
        auto fusionStart = std::chrono::high_resolution_clock::now();
        rawvideo::FastGuidedFilter::filterPlanarRGB(raw_ptr, width, height, iso, 4);
        auto fusionDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - fusionStart).count();
        LOGD("FastGuidedFilter multi-scale dual-stream fusion completed in %lld ms (iso=%d)", (long long)fusionDurationMs, iso);
    }
    auto postStart = std::chrono::high_resolution_clock::now();
    auto postDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - postStart).count();

    const char* jpg_p_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    const char* dng_p_cstr = (outputDngPath) ? env->GetStringUTFChars(outputDngPath, 0) : nullptr;
    std::string jpgPathStr = jpg_p_cstr ? jpg_p_cstr : "", dngPathStr = dng_p_cstr ? dng_p_cstr : "";
    if (outputJpgPath && jpg_p_cstr) env->ReleaseStringUTFChars(outputJpgPath, jpg_p_cstr);
    if (outputDngPath && dng_p_cstr) env->ReleaseStringUTFChars(outputDngPath, dng_p_cstr);

    auto saveStart = std::chrono::high_resolution_clock::now();
    const int fastPreviewDownsample = compute_preview_downsample_factor(width, height, 1280);

    AndroidBitmapInfo info;
    int out_w = 0, out_h = 0;
    if (outputBitmap) {
        AndroidBitmap_getInfo(env, outputBitmap, &info);
        out_w = info.width;
        out_h = info.height;
    }

    if (bitmapPixels) {
        process_and_save_image(raw_ptr, stride_x, stride_y, stride_c, lensShadingVec.empty() ? nullptr : lensShadingVec.data(), lensShadingRows, lensShadingCols,
                                width, height, digitalGain, targetLog, lut,
                                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, // HSWB not used for preview in standard pipe yet
                                nullptr, nullptr, nullptr, 1, ccmVec.data(), wbVec.data(), orientation, bitmapPixels, out_w, out_h, true, fastPreviewDownsample, zoomFactor, (bool)mirror, (bool)enableMemoryColor, (int)colorEngineMode);
        AndroidBitmap_unlockPixels(env, outputBitmap);
    }

    const char* tr_p_cstr = (tempRawPath) ? env->GetStringUTFChars(tempRawPath, 0) : nullptr;
    if (tr_p_cstr) {
        // Copy the planar output directly. The reader (e.g. exportHdrPlus) now knows it's planar.
        auto sharedBuf = std::make_shared<std::vector<uint16_t>>(raw_ptr, raw_ptr + width*height*3);
        {
            std::lock_guard<std::mutex> mapLock(g_sharedMemoryMutex);
            g_sharedMemoryMap[tr_p_cstr] = sharedBuf;
        }
        env->ReleaseStringUTFChars(tempRawPath, tr_p_cstr);
    }

    if (!jpgPathStr.empty() || !dngPathStr.empty()) {
        ImageMetadata meta = metadataFromJava(env, metadata);
        if (!dngPathStr.empty()) {
            float baselineExposure = (digitalGain > 0.0f) ? std::log2(digitalGain) : 0.0f;
            write_dng(dngPathStr.c_str(), width, height, raw_ptr, stride_x, stride_y, stride_c, kMax16BitValue, ccmVec, meta, orientation, (bool)mirror, baselineExposure, wbVec.data());
        }

        if (!jpgPathStr.empty()) {
            process_and_save_image(raw_ptr, stride_x, stride_y, stride_c, lensShadingVec.empty() ? nullptr : lensShadingVec.data(), lensShadingRows, lensShadingCols,
                                    width, height, digitalGain, targetLog, lut,
                                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                                    jpgPathStr.c_str(), nullptr, &meta, 1, ccmVec.data(), wbVec.data(), orientation, nullptr, 0, 0, true, fastPreviewDownsample, zoomFactor, (bool)mirror, (bool)enableMemoryColor, (int)colorEngineMode);

            if (exportMatrixAB && !jpgPathStr.empty() && ccmAltVec.size() == 9) {
                std::string suffix = useSensorColorMatrix ? "_AB_CAPTURE_CCM.jpg" : "_AB_SENSOR_CCM.jpg";
                std::string altJpgPath = jpgPathStr;
                size_t dot = altJpgPath.find_last_of('.');
                if (dot == std::string::npos) dot = altJpgPath.size();
                altJpgPath = altJpgPath.substr(0, dot) + suffix;
                process_and_save_image(raw_ptr, stride_x, stride_y, stride_c, lensShadingVec.empty() ? nullptr : lensShadingVec.data(), lensShadingRows, lensShadingCols,
                                        width, height, digitalGain, targetLog, lut,
                                        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                                        altJpgPath.c_str(), nullptr, &meta, 1, ccmAltVec.data(), wbVec.data(), orientation, nullptr, 0, 0, false, 1, zoomFactor, (bool)mirror, (bool)enableMemoryColor, (int)colorEngineMode);
            }
        }
    }
    fillDebugStats(env, debugStats, (jlong)copyDurationMs, (jlong)halideDurationMs, (jlong)postDurationMs, 0, (jlong)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now()-saveStart).count(), 0, (jlong)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now()-nativeStart).count(), (jlong)jniPrepMs, stageStats);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_processSingleFrameRaw(
    JNIEnv* env, jobject /* this */, jobject bayerBuffer, jint width, jint height, jint orientation, jint whiteLevel, jintArray blackLevelPattern, jfloatArray lensShadingMap, jint lensShadingRows, jint lensShadingCols, jfloatArray whiteBalance, jfloatArray ccm, jint cfaPattern,
    jint targetLog, jstring lutPath, jstring outputJpgPath, jstring outputDngPath,
    jfloat digitalGain, jlongArray debugStats, jobject outputBitmap, jstring tempRawPath, jfloat zoomFactor, jboolean mirror,
    jobject metadata,
    jboolean enableMemoryColor,
    jint colorEngineMode,
    jboolean enableDualStreamFusion
) {
    LOGD("Native processSingleFrameRaw started (enableMemoryColor=%d, colorEngineMode=%d, enableDualStreamFusion=%d).", enableMemoryColor, colorEngineMode, enableDualStreamFusion);

    // Call the existing processHdrPlus logic directly with the buffer and numFrames=1
    return Java_top_maary_darkbag_processor_ColorProcessor_processHdrPlus(
        env, nullptr, bayerBuffer, 1, width, height, orientation, whiteLevel, blackLevelPattern, lensShadingMap, lensShadingRows, lensShadingCols,
        false, // useSensorColorMatrix
        whiteBalance, ccm, nullptr, // ccmAlt
        false, // exportMatrixAB
        cfaPattern, targetLog, lutPath,
        outputJpgPath, outputDngPath, digitalGain, debugStats, outputBitmap, tempRawPath, zoomFactor, mirror, metadata,
        enableMemoryColor,
        colorEngineMode,
        enableDualStreamFusion
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_writeBayerDng(
    JNIEnv* env, jobject /* this */,
    jstring filename,
    jint width, jint height,
    jobject bayerBuffer,
    jint cfaPattern,
    jint whiteLevel,
    jint blackLevel,
    jfloatArray ccm,
    jint orientation,
    jfloatArray whiteBalance,
    jobject metadata,
    jbyteArray thumbnailJpeg,
    jint thumbWidth,
    jint thumbHeight
) {
    if (!filename || !bayerBuffer) {
        LOGE("writeBayerDng: invalid arguments");
        return JNI_FALSE;
    }

    const char* filePath = env->GetStringUTFChars(filename, nullptr);
    const unsigned short* bayerPtr = (const unsigned short*)env->GetDirectBufferAddress(bayerBuffer);
    if (!bayerPtr) {
        LOGE("writeBayerDng: failed to get bayer buffer address");
        env->ReleaseStringUTFChars(filename, filePath);
        return JNI_FALSE;
    }

    std::vector<float> ccmVec;
    if (ccm) {
        jsize ccmLen = env->GetArrayLength(ccm);
        if (ccmLen >= 9) {
            ccmVec.resize(ccmLen);
            env->GetFloatArrayRegion(ccm, 0, ccmLen, ccmVec.data());
        }
    }

    std::vector<float> wbVec;
    if (whiteBalance) {
        jsize wbLen = env->GetArrayLength(whiteBalance);
        if (wbLen >= 4) {
            wbVec.resize(wbLen);
            env->GetFloatArrayRegion(whiteBalance, 0, wbLen, wbVec.data());
        }
    }

    ImageMetadata meta = metadataFromJava(env, metadata);

    const unsigned char* thumbPtr = nullptr;
    size_t thumbSize = 0;
    std::vector<unsigned char> thumbBuf;
    if (thumbnailJpeg) {
        jsize len = env->GetArrayLength(thumbnailJpeg);
        if (len > 0) {
            thumbBuf.resize(len);
            env->GetByteArrayRegion(thumbnailJpeg, 0, len, reinterpret_cast<jbyte*>(thumbBuf.data()));
            thumbPtr = thumbBuf.data();
            thumbSize = static_cast<size_t>(len);
        }
    }

    bool ok = write_bayer_dng(
        filePath,
        width, height,
        bayerPtr,
        cfaPattern,
        whiteLevel, blackLevel,
        ccmVec,
        meta,
        orientation,
        wbVec.empty() ? nullptr : wbVec.data(),
        thumbPtr,
        thumbSize,
        thumbWidth,
        thumbHeight
    );

    env->ReleaseStringUTFChars(filename, filePath);
    return ok ? JNI_TRUE : JNI_FALSE;
}


#include <jni.h>
#include <android/log.h>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include <string>
#include <memory>
#include <algorithm>
#include <exception>
#include <cstring>
#include <chrono> // For timing
#include <thread>
#include <mutex>
#include <atomic>
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
#include "HdrPlusStreamingSession.h"
#include "demosaic/RcdDemosaic.h"
#include "gpu/GpuColorPipeEngine.h"
#include "gpu/GpuRcdComputeEngine.h"


#define TAG "HdrPlusJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {
__attribute__((weak)) void halide_profiler_report(void* /* user_context */) {}
__attribute__((weak)) void halide_profiler_reset() {}
}

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
    jfieldID focalLengthIn35mmFilm;
    jfieldID dateTimeOriginal;
    jfieldID dateTimeDigitized;
    jfieldID offsetTime;
    jfieldID offsetTimeOriginal;
    jfieldID offsetTimeDigitized;
    jfieldID make;
    jfieldID model;
    jfieldID uniqueCameraModel;
    jfieldID lensModel;
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

static std::unordered_map<std::string, std::shared_ptr<SharedCaptureResult>> g_sharedMemoryMap;
std::mutex g_sharedMemoryMutex;
static std::atomic<int64_t> g_nextSessionHandle{1};
static std::mutex g_streamingSessionsMutex;
static std::unordered_map<int64_t, std::shared_ptr<HdrPlusStreamingSession>> g_activeStreamingSessions;

static int64_t registerSession(std::shared_ptr<HdrPlusStreamingSession> session) {
    if (!session) return 0;
    int64_t handle = g_nextSessionHandle.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_streamingSessionsMutex);
    g_activeStreamingSessions[handle] = std::move(session);
    return handle;
}

static std::shared_ptr<HdrPlusStreamingSession> getValidSession(jlong handle) {
    if (handle <= 0) return nullptr;
    std::lock_guard<std::mutex> lock(g_streamingSessionsMutex);
    auto it = g_activeStreamingSessions.find(handle);
    if (it != g_activeStreamingSessions.end()) {
        return it->second;
    }
    return nullptr;
}

static void unregisterSession(int64_t handle) {
    if (handle <= 0) return;
    std::lock_guard<std::mutex> lock(g_streamingSessionsMutex);
    g_activeStreamingSessions.erase(handle);
}
static std::unordered_set<void*> g_nativeAllocatedBuffers;
static std::mutex g_nativeBufferMutex;

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
    g_metadataFields.focalLengthIn35mmFilm = getField(metadataClazz, "focalLengthIn35mmFilm", "Ljava/lang/Integer;");
    g_metadataFields.dateTimeOriginal = getField(metadataClazz, "dateTimeOriginal", "Ljava/lang/Long;");
    g_metadataFields.dateTimeDigitized = getField(metadataClazz, "dateTimeDigitized", "Ljava/lang/Long;");
    g_metadataFields.offsetTime = getField(metadataClazz, "offsetTime", "Ljava/lang/String;");
    g_metadataFields.offsetTimeOriginal = getField(metadataClazz, "offsetTimeOriginal", "Ljava/lang/String;");
    g_metadataFields.offsetTimeDigitized = getField(metadataClazz, "offsetTimeDigitized", "Ljava/lang/String;");
    g_metadataFields.make = getField(metadataClazz, "make", "Ljava/lang/String;");
    g_metadataFields.model = getField(metadataClazz, "model", "Ljava/lang/String;");
    g_metadataFields.uniqueCameraModel = getField(metadataClazz, "uniqueCameraModel", "Ljava/lang/String;");
    g_metadataFields.lensModel = getField(metadataClazz, "lensModel", "Ljava/lang/String;");
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
    meta.focalLengthIn35mmFilm = getIntField(env, metadataObj, g_metadataFields.focalLengthIn35mmFilm, 0);
    meta.captureTimeMillis = getLongField(env, metadataObj, g_metadataFields.dateTimeOriginal, 0);
    meta.digitizedTimeMillis = getLongField(env, metadataObj, g_metadataFields.dateTimeDigitized, meta.captureTimeMillis);
    meta.offsetTime = getStringField(env, metadataObj, g_metadataFields.offsetTime, "");
    meta.offsetTimeOriginal = getStringField(env, metadataObj, g_metadataFields.offsetTimeOriginal, meta.offsetTime);
    meta.offsetTimeDigitized = getStringField(env, metadataObj, g_metadataFields.offsetTimeDigitized, meta.offsetTime);
    meta.make = getStringField(env, metadataObj, g_metadataFields.make, "Unknown");
    meta.model = getStringField(env, metadataObj, g_metadataFields.model, "Unknown");
    meta.uniqueCameraModel = getStringField(env, metadataObj, g_metadataFields.uniqueCameraModel, meta.model);
    meta.lensModel = getStringField(env, metadataObj, g_metadataFields.lensModel, "");
    meta.software = getStringField(env, metadataObj, g_metadataFields.software, "Darkbag");
    meta.imageDescription = getStringField(env, metadataObj, g_metadataFields.imageDescription, "Processed by Darkbag");

    return meta;
}

extern "C" JNIEXPORT void JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_initMemoryPool(JNIEnv* env, jobject /* this */, jint width, jint height, jint frames) {
    // No-op: Output buffers are allocated on-demand directly into sharedBuf or locally scoped,
    // avoiding dangerous concurrent reallocations during camera switches.
    (void)env;
    (void)width;
    (void)height;
    (void)frames;
}

extern "C" JNIEXPORT jobject JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_allocateDirectBuffer(JNIEnv* env, jobject /* this */, jlong capacity) {
    if (capacity <= 0) return nullptr;
    void* ptr = malloc(static_cast<size_t>(capacity));
    if (!ptr) {
        LOGE("Failed to allocate native direct buffer of size %lld", (long long)capacity);
        return nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(g_nativeBufferMutex);
        g_nativeAllocatedBuffers.insert(ptr);
    }
    return env->NewDirectByteBuffer(ptr, capacity);
}

extern "C" JNIEXPORT void JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_copyBayerWithStride(
    JNIEnv* env, jobject /* this */, jobject srcBuffer, jint srcPos, jobject dstBuffer, jint dstPos, jint width, jint height, jint rowStride, jint pixelStride
) {
    if (!srcBuffer || !dstBuffer) return;
    uint8_t* srcBase = (uint8_t*)env->GetDirectBufferAddress(srcBuffer);
    uint8_t* dstBase = (uint8_t*)env->GetDirectBufferAddress(dstBuffer);
    if (!srcBase || !dstBase) return;

    uint8_t* src = srcBase + srcPos;
    uint8_t* dst = dstBase + dstPos;

    const size_t rowLength = static_cast<size_t>(width) * pixelStride;
    if (rowStride == (jint)rowLength) {
        memcpy(dst, src, rowLength * height);
    } else {
        #pragma omp parallel for
        for (int y = 0; y < height; ++y) {
            const uint8_t* srcRow = src + static_cast<size_t>(y) * rowStride;
            uint8_t* dstRow = dst + static_cast<size_t>(y) * rowLength;
            memcpy(dstRow, srcRow, rowLength);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_freeDirectBuffer(JNIEnv* env, jobject /* this */, jobject buffer) {
    if (!buffer) return;
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (!ptr) return;

    bool isNative = false;
    {
        std::lock_guard<std::mutex> lock(g_nativeBufferMutex);
        auto it = g_nativeAllocatedBuffers.find(ptr);
        if (it != g_nativeAllocatedBuffers.end()) {
            g_nativeAllocatedBuffers.erase(it);
            isNative = true;
        }
    }
    if (isNative) {
        free(ptr);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_freeSharedRawMemory(JNIEnv* env, jobject /* this */, jstring tempRawPath) {
    if (!tempRawPath) return;
    const char* temp_path_cstr = env->GetStringUTFChars(tempRawPath, 0);
    if (!temp_path_cstr) return;
    {
        std::lock_guard<std::mutex> mapLock(g_sharedMemoryMutex);
        auto it = g_sharedMemoryMap.find(temp_path_cstr);
        if (it != g_sharedMemoryMap.end()) {
            g_sharedMemoryMap.erase(it);
        }
    }
    env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);
}

static void extract_calibration_data(
    JNIEnv* env,
    jfloatArray colorMatrix1,
    jfloatArray colorMatrix2,
    jfloatArray forwardMatrix1,
    jfloatArray forwardMatrix2,
    jfloatArray neutralColorPoint,
    std::vector<float>& cm1Vec,
    std::vector<float>& cm2Vec,
    std::vector<float>& fm1Vec,
    std::vector<float>& fm2Vec,
    std::vector<float>& neutralVec,
    const float*& cm1Ptr,
    const float*& cm2Ptr,
    const float*& fm1Ptr,
    const float*& fm2Ptr,
    const float*& neutralPtr
) {
    cm1Ptr = nullptr;
    cm2Ptr = nullptr;
    fm1Ptr = nullptr;
    fm2Ptr = nullptr;
    neutralPtr = nullptr;

    if (colorMatrix1) {
        jsize len = env->GetArrayLength(colorMatrix1);
        if (len >= 9) {
            cm1Vec.resize(9);
            env->GetFloatArrayRegion(colorMatrix1, 0, 9, cm1Vec.data());
            cm1Ptr = cm1Vec.data();
        }
    }
    if (colorMatrix2) {
        jsize len = env->GetArrayLength(colorMatrix2);
        if (len >= 9) {
            cm2Vec.resize(9);
            env->GetFloatArrayRegion(colorMatrix2, 0, 9, cm2Vec.data());
            cm2Ptr = cm2Vec.data();
        }
    }
    if (forwardMatrix1) {
        jsize len = env->GetArrayLength(forwardMatrix1);
        if (len >= 9) {
            fm1Vec.resize(9);
            env->GetFloatArrayRegion(forwardMatrix1, 0, 9, fm1Vec.data());
            fm1Ptr = fm1Vec.data();
        }
    }
    if (forwardMatrix2) {
        jsize len = env->GetArrayLength(forwardMatrix2);
        if (len >= 9) {
            fm2Vec.resize(9);
            env->GetFloatArrayRegion(forwardMatrix2, 0, 9, fm2Vec.data());
            fm2Ptr = fm2Vec.data();
        }
    }
    if (neutralColorPoint) {
        jsize len = env->GetArrayLength(neutralColorPoint);
        if (len >= 3) {
            neutralVec.resize(3);
            env->GetFloatArrayRegion(neutralColorPoint, 0, 3, neutralVec.data());
            neutralPtr = neutralVec.data();
        }
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_exportHdrPlus(
    JNIEnv* env, jobject /* this */, jstring tempRawPath, jint width, jint height, jint orientation, jfloat digitalGain, jint targetLog, jstring lutPath,
    jfloat exposure, jfloat contrast, jfloat saturation, jfloat highlights, jfloat shadows, jfloat whites, jfloat blacks,
    jstring jpgPath, jstring dngPath,
    jfloatArray ccm, jfloatArray whiteBalance, jfloat zoomFactor, jboolean mirror,
    jobject metadata,
    jboolean enableMemoryColor,
    jint colorEngineMode,
    jfloatArray colorMatrix1,
    jfloatArray colorMatrix2,
    jfloatArray forwardMatrix1,
    jfloatArray forwardMatrix2,
    jint calibrationIlluminant1,
    jint calibrationIlluminant2,
    jfloatArray neutralColorPoint,
    jboolean faithfulHighlights,
    jlongArray debugStats,
    jint outJpgFd,
    jint outDngFd,
    jint dngCompressionMode,
    jint rawOutputType,
    jint cfaPattern,
    jintArray blackLevelPattern,
    jint whiteLevel,
    jfloatArray dynamicBlackLevel,
    jdoubleArray noiseProfile,
    jintArray activeArea,
    jfloatArray lensShadingMap,
    jint lensShadingRows,
    jint lensShadingCols
) {
    LOGD("Native exportHdrPlus started (enableMemoryColor=%d, colorEngineMode=%d, faithful=%d, rawOutputType=%d).", enableMemoryColor, colorEngineMode, faithfulHighlights, rawOutputType);

    if (!tempRawPath) return -1;
    const char* temp_path_cstr = env->GetStringUTFChars(tempRawPath, 0);
    if (!temp_path_cstr) return -1;

    std::shared_ptr<SharedCaptureResult> sharedResult;
    {
        std::lock_guard<std::mutex> mapLock(g_sharedMemoryMutex);
        auto it = g_sharedMemoryMap.find(temp_path_cstr);
        if (it != g_sharedMemoryMap.end()) {
            sharedResult = it->second;
            // Retain in g_sharedMemoryMap across decoupled export passes (JPEG / DNG).
            // Explicitly erased and freed via freeSharedRawMemory when the task completes.
        }
    }

    if (!sharedResult) {
        LOGE("Failed to find shared memory for tempRawPath: %s", temp_path_cstr);
        env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);
        return -1;
    }
    env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);

    jfloat* wbData = env->GetFloatArrayElements(whiteBalance, nullptr);
    std::vector<float> wbVec = {wbData[0], wbData[1], wbData[2], wbData[3]};
    env->ReleaseFloatArrayElements(whiteBalance, wbData, JNI_ABORT);

    float bl_pattern[4] = {64.0f, 64.0f, 64.0f, 64.0f};
    bool has_bl = false;
    if (dynamicBlackLevel && env->GetArrayLength(dynamicBlackLevel) >= 4) {
        env->GetFloatArrayRegion(dynamicBlackLevel, 0, 4, bl_pattern);
        has_bl = true;
    } else if (blackLevelPattern && env->GetArrayLength(blackLevelPattern) >= 4) {
        int int_bl[4] = {64, 64, 64, 64};
        env->GetIntArrayRegion(blackLevelPattern, 0, 4, int_bl);
        bl_pattern[0] = (float)int_bl[0];
        bl_pattern[1] = (float)int_bl[1];
        bl_pattern[2] = (float)int_bl[2];
        bl_pattern[3] = (float)int_bl[3];
        has_bl = true;
    }

    double noise_prof[8] = {0};
    const double* noise_ptr = nullptr;
    if (noiseProfile && env->GetArrayLength(noiseProfile) >= 8) {
        env->GetDoubleArrayRegion(noiseProfile, 0, 8, noise_prof);
        noise_ptr = noise_prof;
    }

    int active_area[4] = {0};
    const int* active_area_ptr = nullptr;
    if (activeArea && env->GetArrayLength(activeArea) >= 4) {
        env->GetIntArrayRegion(activeArea, 0, 4, active_area);
        active_area_ptr = active_area;
    }

    std::vector<float> lensShadingVec;
    const float* lens_shading_ptr = nullptr;
    if (lensShadingMap && lensShadingRows > 0 && lensShadingCols > 0) {
        int lsSize = env->GetArrayLength(lensShadingMap);
        int expected = 4 * lensShadingRows * lensShadingCols;
        if (lsSize >= expected) {
            lensShadingVec.resize(expected);
            env->GetFloatArrayRegion(lensShadingMap, 0, expected, lensShadingVec.data());
            lens_shading_ptr = lensShadingVec.data();
        }
    }

    jfloat* ccmData = env->GetFloatArrayElements(ccm, nullptr);
    std::vector<float> ccmVec(9); for(int i=0; i<9; ++i) ccmVec[i] = ccmData[i];
    env->ReleaseFloatArrayElements(ccm, ccmData, JNI_ABORT);

    std::vector<float> cm1Vec, cm2Vec, fm1Vec, fm2Vec, neutralVec;
    const float* cm1Ptr = nullptr;
    const float* cm2Ptr = nullptr;
    const float* fm1Ptr = nullptr;
    const float* fm2Ptr = nullptr;
    const float* neutralPtr = nullptr;
    extract_calibration_data(env, colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2, neutralColorPoint,
                             cm1Vec, cm2Vec, fm1Vec, fm2Vec, neutralVec,
                             cm1Ptr, cm2Ptr, fm1Ptr, fm2Ptr, neutralPtr);

    std::string lutPathStr;
    if (lutPath) {
        const char* lut_cstr = env->GetStringUTFChars(lutPath, nullptr);
        if (lut_cstr) {
            lutPathStr = lut_cstr;
            env->ReleaseStringUTFChars(lutPath, lut_cstr);
        }
    }
    LUT3D lut;
    if (!lutPathStr.empty()) {
        auto cached = get_cached_lut(lutPathStr.c_str());
        if (cached) lut = *cached;
    }

    const char* jpg_path_cstr = (jpgPath) ? env->GetStringUTFChars(jpgPath, 0) : nullptr;
    const char* dng_path_cstr = (dngPath) ? env->GetStringUTFChars(dngPath, 0) : nullptr;

    ImageMetadata meta = metadataFromJava(env, metadata);

    auto exportStart = std::chrono::high_resolution_clock::now();
    jlong dngMs = 0;
    jlong jpgMs = 0;

    bool dngOk = true;
    bool saveOk = true;
    jlong colorPipeMs = 0;
    jlong jpegEncodeMs = 0;
    std::future<bool> dngFuture;

    const bool shouldExportDng = (outDngFd >= 0 || dng_path_cstr);
    const bool shouldExportJpg = (outJpgFd >= 0 || jpg_path_cstr);

    auto doDngExport = [&]() -> bool {
        LOGD("Exporting DNG to %s (outDngFd=%d, mode=%d, rawOutputType=%d)", dng_path_cstr ? dng_path_cstr : "FD", outDngFd, dngCompressionMode, rawOutputType);
        auto dngStart = std::chrono::high_resolution_clock::now();
        float baselineExposure = (digitalGain > 0.0f) ? std::log2(digitalGain) : 0.0f;
        bool isBayer = (rawOutputType == 0);

        const uint16_t* dngRawData = nullptr;
        int dngStrideX = 1;
        int dngStrideY = width;
        int dngStrideC = 0;
        int effectiveWhiteLevel = whiteLevel;

        if (isBayer) {
            if (!sharedResult->bayerBuf.empty()) {
                dngRawData = sharedResult->bayerBuf.data();
                dngStrideX = 1;
                dngStrideY = width;
                dngStrideC = 0;
                effectiveWhiteLevel = (whiteLevel > 0) ? whiteLevel : 1023;
            } else {
                // Fallback to rgbBuf if bayerBuf is not present
                if (sharedResult->rgbBuf.empty() && sharedResult->gpuRgbTexture != 0) {
                    LOGD("Lazy readback of GPU unified texture %u for Bayer fallback DNG export (%dx%d)",
                         sharedResult->gpuRgbTexture, width, height);
                    sharedResult->rgbBuf.resize(static_cast<size_t>(width) * height * 3);
                    bool okRb = darkbag::gpu::GpuRcdComputeEngine::instance().readbackRgbTextureToCpu(
                        sharedResult->gpuRgbTexture, width, height, sharedResult->rgbBuf.data()
                    );
                    if (!okRb) {
                        LOGE("GPU readback failed for Bayer fallback DNG export");
                        sharedResult->rgbBuf.clear();
                    }
                }
                dngRawData = sharedResult->rgbBuf.data();
                dngStrideX = 1;
                dngStrideY = width;
                dngStrideC = width * height;
                isBayer = false;
                effectiveWhiteLevel = kMax16BitValue;
            }
        } else {
            // Linear DNG requested (rawOutputType != 0)
            if (sharedResult->rgbBuf.empty() && sharedResult->gpuRgbTexture != 0) {
                LOGD("Lazy readback of GPU unified texture %u for Linear DNG export (%dx%d)",
                     sharedResult->gpuRgbTexture, width, height);
                sharedResult->rgbBuf.resize(static_cast<size_t>(width) * height * 3);
                bool okRb = darkbag::gpu::GpuRcdComputeEngine::instance().readbackRgbTextureToCpu(
                    sharedResult->gpuRgbTexture, width, height, sharedResult->rgbBuf.data()
                );
                if (!okRb) {
                    LOGE("GPU readback failed for Linear DNG export");
                    sharedResult->rgbBuf.clear();
                }
            }
            dngRawData = sharedResult->rgbBuf.data();
            dngStrideX = 1;
            dngStrideY = width;
            dngStrideC = width * height;
            effectiveWhiteLevel = kMax16BitValue;
        }

        if (!dngRawData || sharedResult->rgbBuf.empty()) {
            LOGE("doDngExport: missing raw data for DNG export after readback");
            return false;
        }

        bool ok = write_dng(
            dng_path_cstr, width, height, dngRawData,
            dngStrideX, dngStrideY, dngStrideC,
            effectiveWhiteLevel,
            ccmVec, meta, orientation, (bool)mirror, baselineExposure, wbVec.data(),
            cm1Ptr, cm2Ptr, fm1Ptr, fm2Ptr, (int)calibrationIlluminant1, (int)calibrationIlluminant2, neutralPtr,
            outDngFd, (int)dngCompressionMode,
            isBayer, (int)cfaPattern, has_bl ? bl_pattern : nullptr,
            noise_ptr, active_area_ptr, lens_shading_ptr, lensShadingRows, lensShadingCols
        );
        dngMs = (jlong)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - dngStart).count();
        return ok;
    };

    if (shouldExportDng) {
        if (shouldExportJpg) {
            // Concurrently write DNG in background thread while main thread executes ColorPipe + JPEG
            dngFuture = std::async(std::launch::async, doDngExport);
        } else {
            dngOk = doDngExport();
        }
    }

    if (shouldExportJpg) {
        LOGD("Exporting JPG: JPG=%s (outJpgFd=%d)", jpg_path_cstr ? jpg_path_cstr : "FD", outJpgFd);
        auto jpgStart = std::chrono::high_resolution_clock::now();
        float effectiveZoom = (sharedResult && sharedResult->isZoomCropped) ? 1.0f : zoomFactor;
        float physicalZoom = zoomFactor;
        const float* effectiveWb = (sharedResult && sharedResult->isWhiteBalanceApplied) ? nullptr : wbVec.data();
        int64_t measuredColorPipe = 0;
        int64_t measuredJpegEncode = 0;

        bool gpuAttemptSuccess = false;
        if (darkbag::gpu::GpuColorPipeEngine::instance().isAvailable()) {
            if (sharedResult && sharedResult->gpuRgbTexture != 0) {
                LOGD("Invoking zero-copy GPU-to-GPU ColorPipe from unified texture %u (%dx%d, LSC=%d)",
                     sharedResult->gpuRgbTexture, width, height, lens_shading_ptr != nullptr ? 1 : 0);
                gpuAttemptSuccess = darkbag::gpu::GpuColorPipeEngine::instance().processAndSaveImageFromTexture(
                    sharedResult->gpuRgbTexture,
                    width, height,
                    digitalGain, targetLog,
                    lutPathStr, &lut,
                    exposure, contrast, saturation,
                    highlights, shadows, whites, blacks,
                    jpg_path_cstr, outJpgFd,
                    ccmVec.data(), effectiveWb,
                    orientation, (bool)mirror, effectiveZoom,
                    (int)colorEngineMode, faithfulHighlights,
                    &measuredColorPipe, &measuredJpegEncode,
                    lens_shading_ptr, lensShadingRows, lensShadingCols,
                    physicalZoom
                );
            } else if (sharedResult && !sharedResult->rgbBuf.empty()) {
                gpuAttemptSuccess = darkbag::gpu::GpuColorPipeEngine::instance().processAndSaveImage(
                    sharedResult->rgbBuf.data(),
                    width, height,
                    1, width, width * height,
                    digitalGain, targetLog,
                    lutPathStr, &lut,
                    exposure, contrast, saturation,
                    highlights, shadows, whites, blacks,
                    jpg_path_cstr, outJpgFd,
                    ccmVec.data(), effectiveWb,
                    orientation, (bool)mirror, effectiveZoom,
                    (int)colorEngineMode, faithfulHighlights,
                    &measuredColorPipe, &measuredJpegEncode,
                    lens_shading_ptr, lensShadingRows, lensShadingCols,
                    physicalZoom
                );
            }
            if (gpuAttemptSuccess) {
                saveOk = true;
                LOGD("GPU ColorPipe executed successfully (render: %lld ms, jpeg: %lld ms)",
                     (long long)measuredColorPipe, (long long)measuredJpegEncode);
            } else {
                LOGW("GPU ColorPipe returned false, safely falling back to CPU ColorPipe");
            }
        }

        if (!gpuAttemptSuccess) {
            // Lazy readback of GPU texture if rgbBuf is empty for CPU ColorPipe fallback
            if (sharedResult && sharedResult->rgbBuf.empty() && sharedResult->gpuRgbTexture != 0) {
                LOGW("Lazy readback of GPU unified texture %u for CPU ColorPipe fallback (%dx%d)",
                     sharedResult->gpuRgbTexture, width, height);
                sharedResult->rgbBuf.resize(static_cast<size_t>(width) * height * 3);
                bool okRb = darkbag::gpu::GpuRcdComputeEngine::instance().readbackRgbTextureToCpu(
                    sharedResult->gpuRgbTexture, width, height, sharedResult->rgbBuf.data()
                );
                if (!okRb) {
                    LOGE("GPU readback failed for CPU ColorPipe fallback");
                    sharedResult->rgbBuf.clear();
                }
            }
            if (sharedResult && !sharedResult->rgbBuf.empty()) {
                saveOk = process_and_save_image(sharedResult->rgbBuf.data(), 1, width, width*height,
                                                lens_shading_ptr, lensShadingRows, lensShadingCols,
                                                width, height, digitalGain, targetLog, lut,
                                                exposure, contrast, saturation, highlights, shadows, whites, blacks,
                                                jpg_path_cstr, nullptr, &meta, 1, ccmVec.data(), effectiveWb, orientation, nullptr, 0, 0, false, 1, effectiveZoom, (bool)mirror, (bool)enableMemoryColor, (int)colorEngineMode, faithfulHighlights, outJpgFd,
                                                &measuredColorPipe, &measuredJpegEncode);
            } else {
                LOGE("CPU ColorPipe fallback failed: no RGB buffer available");
                saveOk = false;
            }
        }
        jpgMs = (jlong)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - jpgStart).count();
        colorPipeMs = (jlong)measuredColorPipe;
        jpegEncodeMs = (jlong)measuredJpegEncode;
        if (jpegEncodeMs == 0 && jpgMs > colorPipeMs) {
            jpegEncodeMs = jpgMs - colorPipeMs;
        }
    }

    if (dngFuture.valid()) {
        dngOk = dngFuture.get();
    }

    auto exportTotalMs = (jlong)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - exportStart).count();
    jlong postMs = (exportTotalMs - dngMs - jpgMs > 0) ? (exportTotalMs - dngMs - jpgMs) : 0;

    if (debugStats != nullptr) {
        const jsize len = env->GetArrayLength(debugStats);
        if (len >= 5) {
            jlong stats[15] = {0};
            env->GetLongArrayRegion(debugStats, 0, std::min<jsize>(len, 15), stats);
            stats[2] = colorPipeMs;
            stats[3] = dngMs;
            stats[4] = jpegEncodeMs;
            env->SetLongArrayRegion(debugStats, 0, std::min<jsize>(len, 15), stats);
        }
    }
    if (jpgPath && jpg_path_cstr) env->ReleaseStringUTFChars(jpgPath, jpg_path_cstr);
    if (dngPath && dng_path_cstr) env->ReleaseStringUTFChars(dngPath, dng_path_cstr);

    // No longer a physical file, so we don't delete anything
    // (the shared ptr cleans itself up)

    bool overallSuccess = saveOk && dngOk;
    LOGD("Native exportHdrPlus finished. Success=%d (saveOk=%d, dngOk=%d)", overallSuccess, saveOk, dngOk);
    return overallSuccess ? 0 : -2;
}

extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_processHdrPlus(
    JNIEnv* env, jobject /* this */, jobject dngBuffer, jint numFrames, jint width, jint height, jint orientation, jint whiteLevel, jintArray blackLevelPattern, jfloatArray lensShadingMap, jint lensShadingRows, jint lensShadingCols, jboolean useSensorColorMatrix, jfloatArray whiteBalance, jfloatArray ccm, jfloatArray ccmAlt, jboolean exportMatrixAB, jint cfaPattern,
    jint targetLog, jstring lutPath, jstring outputJpgPath, jstring outputDngPath,
    jfloat digitalGain, jlongArray debugStats, jobject outputBitmap, jstring tempRawPath, jfloat zoomFactor, jboolean mirror,
    jobject metadata,
    jboolean enableMemoryColor,
    jint colorEngineMode,
    jfloatArray colorMatrix1,
    jfloatArray colorMatrix2,
    jfloatArray forwardMatrix1,
    jfloatArray forwardMatrix2,
    jint calibrationIlluminant1,
    jint calibrationIlluminant2,
    jfloatArray neutralColorPoint,
    jint dngCompressionMode,
    jdoubleArray noiseProfile
) {
    LOGD("Native processHdrPlus started (enableMemoryColor=%d, colorEngineMode=%d, dngCompressionMode=%d).", enableMemoryColor, colorEngineMode, dngCompressionMode);
    (void)useSensorColorMatrix;

    // Parse Camera2 SENSOR_NOISE_PROFILE if provided
    std::vector<double> noiseProfileVec;
    if (noiseProfile) {
        jsize npLen = env->GetArrayLength(noiseProfile);
        if (npLen > 0) {
            noiseProfileVec.resize(npLen);
            env->GetDoubleArrayRegion(noiseProfile, 0, npLen, noiseProfileVec.data());
            LOGD("Native processHdrPlus: Received SENSOR_NOISE_PROFILE (%d values, %d channels):",
                 (int)npLen, (int)(npLen / 2));
            for (int ch = 0; ch < npLen / 2; ++ch) {
                LOGD("  Channel %d: Slope (S) = %.6e, Intercept (O) = %.6e",
                     ch, noiseProfileVec[ch * 2], noiseProfileVec[ch * 2 + 1]);
            }
        }
    } else {
        LOGD("Native processHdrPlus: No noiseProfile provided");
    }

    auto nativeStart = std::chrono::high_resolution_clock::now();
    auto jniPrepStart = std::chrono::high_resolution_clock::now();

    if (numFrames < 1) { LOGE("Processing requires at least 1 frame."); return -1; }
    if (!dngBuffer) { LOGE("dngBuffer is null"); return -1; }

    uint16_t* rawDataPtr = (uint16_t*)env->GetDirectBufferAddress(dngBuffer);
    if (!rawDataPtr) { LOGE("Failed to get direct buffer address"); return -1; }
    
    const size_t totalSizeBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * numFrames * sizeof(uint16_t);
    jlong capacity = env->GetDirectBufferCapacity(dngBuffer);
    if (capacity < (jlong)totalSizeBytes) {
        LOGE("Direct buffer capacity %lld is smaller than expected %zu", (long long)capacity, totalSizeBytes);
        return -1;
    }
    
    auto copyDurationMs = 0; // Zero copy!

    Buffer<uint16_t> inputBuf(rawDataPtr, width, height, numFrames);

    const char* tr_p_cstr = (tempRawPath) ? env->GetStringUTFChars(tempRawPath, 0) : nullptr;
    std::shared_ptr<SharedCaptureResult> sharedResult;
    Buffer<uint16_t> bayerBuf;
    Buffer<uint16_t> outputBuf;
    if (tr_p_cstr) {
        sharedResult = std::make_shared<SharedCaptureResult>();
        sharedResult->bayerBuf.resize(static_cast<size_t>(width) * height);
        sharedResult->rgbBuf.resize(static_cast<size_t>(width) * height * 3);
        sharedResult->noiseProfile = noiseProfileVec;
        bayerBuf = Buffer<uint16_t>(sharedResult->bayerBuf.data(), width, height);
        outputBuf = Buffer<uint16_t>(sharedResult->rgbBuf.data(), width, height, 3);
    } else {

        bayerBuf = Buffer<uint16_t>(width, height);
        outputBuf = Buffer<uint16_t>(width, height, 3);
    }

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

    // numFrames == 1 is exactly the minimal single-frame (non-HDR+) path: keep its
    // sensor data linear and handle saturated highlights point-wise (see ColorPipe).
    const bool faithfulHighlights = (numFrames == 1);

    std::vector<float> cm1Vec, cm2Vec, fm1Vec, fm2Vec, neutralVec;
    const float* cm1Ptr = nullptr;
    const float* cm2Ptr = nullptr;
    const float* fm1Ptr = nullptr;
    const float* fm2Ptr = nullptr;
    const float* neutralPtr = nullptr;
    extract_calibration_data(env, colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2, neutralColorPoint,
                             cm1Vec, cm2Vec, fm1Vec, fm2Vec, neutralVec,
                             cm1Ptr, cm2Ptr, fm1Ptr, fm2Ptr, neutralPtr);

    Buffer<float> ccmHalideBuf(ccmVec.data(), 3, 3);
    auto jniPrepMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - jniPrepStart).count();

    int halideCfa = 1;
    switch (cfaPattern) { case 0: halideCfa = 1; break; case 1: halideCfa = 2; break; case 2: halideCfa = 4; break; case 3: halideCfa = 3; break; default: halideCfa = 1; break; }

    static bool halideThreadsConfigured = false;
    if (!halideThreadsConfigured) {
        int cpuThreads = (int)std::thread::hardware_concurrency();
        if (cpuThreads <= 0) cpuThreads = 4;
        int halideThreads = (cpuThreads >= 6) ? (cpuThreads - 2) : std::max(2, cpuThreads - 1);
        halide_set_num_threads(halideThreads);
        halideThreadsConfigured = true;
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
        halide_res = hdrplus_single_pipeline(inputBuf, bl_r, bl_g0, bl_g1, bl_b, (uint16_t)whiteLevel, wb_r, wb_g0, wb_g1, wb_b, halideCfa, ccmHalideBuf, lscMapBuf, 1.0f, 1.0f, outputBuf, bayerBuf);
    } else {
        if (denoiseLevel == 0) {
            halide_res = hdrplus_fast_pipeline(inputBuf, bl_r, bl_g0, bl_g1, bl_b, (uint16_t)whiteLevel, wb_r, wb_g0, wb_g1, wb_b, halideCfa, ccmHalideBuf, lscMapBuf, 1.0f, 1.0f, outputBuf, bayerBuf);
        } else if (denoiseLevel == 2) {
            halide_res = hdrplus_high_pipeline(inputBuf, bl_r, bl_g0, bl_g1, bl_b, (uint16_t)whiteLevel, wb_r, wb_g0, wb_g1, wb_b, halideCfa, ccmHalideBuf, lscMapBuf, 1.0f, 1.0f, outputBuf, bayerBuf);
        } else {
            halide_res = hdrplus_raw_pipeline(inputBuf, bl_r, bl_g0, bl_g1, bl_b, (uint16_t)whiteLevel, wb_r, wb_g0, wb_g1, wb_b, halideCfa, ccmHalideBuf, lscMapBuf, 1.0f, 1.0f, outputBuf, bayerBuf);
        }
    }
    auto halideDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - halideStart).count();

    halide_report_buffer.clear(); halide_profiler_report(nullptr);
    HalideStageStats stageStats = parseHalideReport(halide_report_buffer);

    if (halide_res != 0) {
        LOGE("Halide failed: %d", halide_res);
        if (tr_p_cstr) env->ReleaseStringUTFChars(tempRawPath, tr_p_cstr);
        return -1;
    }

    if (sharedResult) {
        sharedResult->isWhiteBalanceApplied = true;
    }

    unsigned char* bitmapPixels = nullptr;
    if (outputBitmap) AndroidBitmap_lockPixels(env, outputBitmap, (void**)&bitmapPixels);

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut; if (lut_path_cstr) { auto cached = get_cached_lut(lut_path_cstr); if (cached) lut = *cached; env->ReleaseStringUTFChars(lutPath, lut_path_cstr); }

    int stride_x = outputBuf.dim(0).stride(), stride_y = outputBuf.dim(1).stride(), stride_c = outputBuf.dim(2).stride();
    const uint16_t* raw_ptr = outputBuf.data();
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
                                nullptr, nullptr, nullptr, 1, ccmVec.data(), nullptr, orientation, bitmapPixels, out_w, out_h, true, fastPreviewDownsample, zoomFactor, (bool)mirror, (bool)enableMemoryColor, (int)colorEngineMode, faithfulHighlights);
        AndroidBitmap_unlockPixels(env, outputBitmap);
    }

    if (tr_p_cstr) {
        {
            std::lock_guard<std::mutex> mapLock(g_sharedMemoryMutex);
            g_sharedMemoryMap[tr_p_cstr] = sharedResult;
        }
        env->ReleaseStringUTFChars(tempRawPath, tr_p_cstr);
    }

    if (!jpgPathStr.empty() || !dngPathStr.empty()) {
        ImageMetadata meta = metadataFromJava(env, metadata);
        if (!dngPathStr.empty()) {
            float baselineExposure = (digitalGain > 0.0f) ? std::log2(digitalGain) : 0.0f;
            write_dng(dngPathStr.c_str(), width, height, raw_ptr, stride_x, stride_y, stride_c, kMax16BitValue, ccmVec, meta, orientation, (bool)mirror, baselineExposure, wbVec.data(),
                      cm1Ptr, cm2Ptr, fm1Ptr, fm2Ptr, (int)calibrationIlluminant1, (int)calibrationIlluminant2, neutralPtr, -1, (int)dngCompressionMode);
        }

        if (!jpgPathStr.empty()) {
            process_and_save_image(raw_ptr, stride_x, stride_y, stride_c, lensShadingVec.empty() ? nullptr : lensShadingVec.data(), lensShadingRows, lensShadingCols,
                                    width, height, digitalGain, targetLog, lut,
                                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                                    jpgPathStr.c_str(), nullptr, &meta, 1, ccmVec.data(), nullptr, orientation, nullptr, 0, 0, true, fastPreviewDownsample, zoomFactor, (bool)mirror, (bool)enableMemoryColor, (int)colorEngineMode, faithfulHighlights);

            if (exportMatrixAB && !jpgPathStr.empty() && ccmAltVec.size() == 9) {
                std::string suffix = useSensorColorMatrix ? "_AB_CAPTURE_CCM.jpg" : "_AB_SENSOR_CCM.jpg";
                std::string altJpgPath = jpgPathStr;
                size_t dot = altJpgPath.find_last_of('.');
                if (dot == std::string::npos) dot = altJpgPath.size();
                altJpgPath = altJpgPath.substr(0, dot) + suffix;
                process_and_save_image(raw_ptr, stride_x, stride_y, stride_c, lensShadingVec.empty() ? nullptr : lensShadingVec.data(), lensShadingRows, lensShadingCols,
                                        width, height, digitalGain, targetLog, lut,
                                        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                                        altJpgPath.c_str(), nullptr, &meta, 1, ccmAltVec.data(), nullptr, orientation, nullptr, 0, 0, false, 1, zoomFactor, (bool)mirror, (bool)enableMemoryColor, (int)colorEngineMode, faithfulHighlights);
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
    jfloatArray colorMatrix1,
    jfloatArray colorMatrix2,
    jfloatArray forwardMatrix1,
    jfloatArray forwardMatrix2,
    jint calibrationIlluminant1,
    jint calibrationIlluminant2,
    jfloatArray neutralColorPoint,
    jint dngCompressionMode
) {
    auto nativeStart = std::chrono::high_resolution_clock::now();
    LOGD("Native processSingleFrameRaw started (enableMemoryColor=%d, colorEngineMode=%d, %dx%d, orientation=%d, WL=%d, cfa=%d).",
         enableMemoryColor, colorEngineMode, width, height, orientation, whiteLevel, cfaPattern);

    (void)metadata;
    (void)colorMatrix1; (void)colorMatrix2;
    (void)forwardMatrix1; (void)forwardMatrix2;
    (void)calibrationIlluminant1; (void)calibrationIlluminant2;
    (void)neutralColorPoint; (void)dngCompressionMode;
    (void)outputJpgPath; (void)outputDngPath;

    std::vector<float> lensShadingVec;
    const float* lens_shading_ptr = nullptr;
    if (lensShadingMap && lensShadingRows > 0 && lensShadingCols > 0) {
        int lsSize = env->GetArrayLength(lensShadingMap);
        int expected = 4 * lensShadingRows * lensShadingCols;
        if (lsSize >= expected) {
            lensShadingVec.resize(expected);
            env->GetFloatArrayRegion(lensShadingMap, 0, expected, lensShadingVec.data());
            lens_shading_ptr = lensShadingVec.data();
        }
    }

    if (!bayerBuffer) { LOGE("processSingleFrameRaw: bayerBuffer is null"); return -1; }
    uint16_t* rawDataPtr = (uint16_t*)env->GetDirectBufferAddress(bayerBuffer);
    if (!rawDataPtr) { LOGE("processSingleFrameRaw: Failed to get direct buffer address"); return -1; }

    const size_t numPixels = static_cast<size_t>(width) * height;
    jlong capacity = env->GetDirectBufferCapacity(bayerBuffer);
    if (capacity < (jlong)(numPixels * sizeof(uint16_t))) {
        LOGE("processSingleFrameRaw: Direct buffer capacity %lld < expected %zu",
             (long long)capacity, numPixels * sizeof(uint16_t));
        return -1;
    }

    const char* tr_p_cstr = (tempRawPath) ? env->GetStringUTFChars(tempRawPath, 0) : nullptr;
    auto sharedResult = std::make_shared<SharedCaptureResult>();
    sharedResult->bayerBuf.resize(numPixels);
    // Note: Do not pre-allocate sharedResult->rgbBuf when GPU demosaicing is available!

    // 1. Copy raw CFA into sharedResult->bayerBuf for native DNG writing (< 10ms)
    std::memcpy(sharedResult->bayerBuf.data(), rawDataPtr, numPixels * sizeof(uint16_t));

    // 2. Parse Black Level and White Balance
    int bl_pattern[4] = {64, 64, 64, 64};
    if (blackLevelPattern && env->GetArrayLength(blackLevelPattern) >= 4) {
        env->GetIntArrayRegion(blackLevelPattern, 0, 4, bl_pattern);
    }
    uint16_t bl_array[4] = {
        (uint16_t)std::max(0, bl_pattern[0]),
        (uint16_t)std::max(0, bl_pattern[1]),
        (uint16_t)std::max(0, bl_pattern[2]),
        (uint16_t)std::max(0, bl_pattern[3])
    };

    float wb_array[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    if (whiteBalance && env->GetArrayLength(whiteBalance) >= 4) {
        env->GetFloatArrayRegion(whiteBalance, 0, 4, wb_array);
    }

    // 3. High-Fidelity RCD Demosaicing (CPU OpenMP+NEON: 339ms, zero GPU contention)
    auto demosaicStart = std::chrono::high_resolution_clock::now();
    sharedResult->rgbBuf.resize(numPixels * 3);
    darkbag::demosaic::rcd_demosaic(
        sharedResult->bayerBuf.data(),
        width,
        height,
        cfaPattern,
        bl_array,
        static_cast<uint16_t>(whiteLevel),
        wb_array,
        sharedResult->rgbBuf.data()
    );
    auto demosaicDurationMs = (jlong)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now() - demosaicStart
    ).count();

    sharedResult->isWhiteBalanceApplied = false;
    sharedResult->isZoomCropped = false;

    // 4. Save to shared memory map
    if (tr_p_cstr) {
        {
            std::lock_guard<std::mutex> mapLock(g_sharedMemoryMutex);
            g_sharedMemoryMap[tr_p_cstr] = sharedResult;
        }
        env->ReleaseStringUTFChars(tempRawPath, tr_p_cstr);
    }

    // 5. Handle outputBitmap if requested (for backwards compatibility)
    unsigned char* bitmapPixels = nullptr;
    if (outputBitmap) AndroidBitmap_lockPixels(env, outputBitmap, (void**)&bitmapPixels);
    if (bitmapPixels) {
        if (sharedResult->rgbBuf.empty() && sharedResult->gpuRgbTexture != 0) {
            LOGD("Lazy readback of GPU unified texture %u for outputBitmap preview (%dx%d)",
                 sharedResult->gpuRgbTexture, width, height);
            sharedResult->rgbBuf.resize(numPixels * 3);
            darkbag::gpu::GpuRcdComputeEngine::instance().readbackRgbTextureToCpu(
                sharedResult->gpuRgbTexture, width, height, sharedResult->rgbBuf.data()
            );
        }
        AndroidBitmapInfo info;
        AndroidBitmap_getInfo(env, outputBitmap, &info);
        std::vector<float> ccmVec(9, 0.0f);
        if (ccm && env->GetArrayLength(ccm) >= 9) {
            env->GetFloatArrayRegion(ccm, 0, 9, ccmVec.data());
        }
        const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
        LUT3D lut;
        if (lut_path_cstr) {
            auto cached = get_cached_lut(lut_path_cstr);
            if (cached) lut = *cached;
            env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
        }
        const int fastPreviewDownsample = compute_preview_downsample_factor(width, height, 1280);
        process_and_save_image(sharedResult->rgbBuf.data(), 1, width, width * height, lens_shading_ptr, lensShadingRows, lensShadingCols,
                                width, height, digitalGain, targetLog, lut,
                                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                                nullptr, nullptr, nullptr, 1, ccmVec.data(), wb_array, orientation,
                                bitmapPixels, info.width, info.height, true, fastPreviewDownsample, zoomFactor, (bool)mirror,
                                (bool)enableMemoryColor, (int)colorEngineMode, true);
        AndroidBitmap_unlockPixels(env, outputBitmap);
    }

    auto totalNativeMs = (jlong)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now() - nativeStart
    ).count();

    // 6. Record timing stats
    if (debugStats != nullptr) {
        const jsize len = env->GetArrayLength(debugStats);
        if (len >= 15) {
            jlong stats[15] = {0};
            env->GetLongArrayRegion(debugStats, 0, 15, stats);
            stats[0] = 0; // copyDurationMs
            stats[1] = demosaicDurationMs; // halideDurationMs (Stage 1 compute)
            stats[6] = totalNativeMs; // nativeDurationMs
            stats[8] = 0; // normalizeMs (Single frame has no accumulation normalization)
            stats[9] = demosaicDurationMs; // fusionComputeMs (RCD demosaic time)
            env->SetLongArrayRegion(debugStats, 0, 15, stats);
        }
    }

    LOGD("Native processSingleFrameRaw completed in %lld ms (RCD demosaic: %lld ms)",
         (long long)totalNativeMs, (long long)demosaicDurationMs);
    return 0;
}


extern "C" JNIEXPORT jlong JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_nativeCreateStreamingSession(
    JNIEnv* env, jobject /* this */,
    jint width, jint height, jint orientation, jint whiteLevel,
    jintArray blackLevelPattern,
    jfloatArray lensShadingMap, jint lensShadingRows, jint lensShadingCols,
    jfloatArray whiteBalance, jfloatArray ccm,
    jint cfaPattern, jdoubleArray noiseProfile,
    jint fusionMode, jfloat zoomFactor
) {
    int bl_pattern[4] = {64, 64, 64, 64};
    if (blackLevelPattern && env->GetArrayLength(blackLevelPattern) >= 4) {
        env->GetIntArrayRegion(blackLevelPattern, 0, 4, bl_pattern);
    }

    float wb[4] = {2.0f, 1.0f, 1.0f, 1.5f};
    if (whiteBalance && env->GetArrayLength(whiteBalance) >= 4) {
        env->GetFloatArrayRegion(whiteBalance, 0, 4, wb);
    }

    float ccmArr[9] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    if (ccm && env->GetArrayLength(ccm) >= 9) {
        env->GetFloatArrayRegion(ccm, 0, 9, ccmArr);
    }

    std::vector<float> lscVec;
    const float* lscPtr = nullptr;
    if (lensShadingMap && lensShadingRows > 0 && lensShadingCols > 0) {
        int expected = 4 * lensShadingRows * lensShadingCols;
        if (env->GetArrayLength(lensShadingMap) >= expected) {
            lscVec.resize(expected);
            env->GetFloatArrayRegion(lensShadingMap, 0, expected, lscVec.data());
            lscPtr = lscVec.data();
        }
    }

    std::vector<double> npVec;
    const double* npPtr = nullptr;
    int npLen = 0;
    if (noiseProfile) {
        npLen = env->GetArrayLength(noiseProfile);
        if (npLen > 0) {
            npVec.resize(npLen);
            env->GetDoubleArrayRegion(noiseProfile, 0, npLen, npVec.data());
            npPtr = npVec.data();
        }
    }

    auto session = std::make_shared<HdrPlusStreamingSession>(
        width, height, orientation, whiteLevel,
        bl_pattern, lscPtr, lensShadingRows, lensShadingCols,
        wb, ccmArr, cfaPattern, npPtr, npLen,
        fusionMode, zoomFactor
    );
    int64_t handle = registerSession(session);
    LOGD("nativeCreateStreamingSession: handle=%lld (%dx%d, fusionMode=%d, zoom=%.2f)",
         (long long)handle, width, height, fusionMode, zoomFactor);
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_nativePushStreamingFrame(
    JNIEnv* env, jobject /* this */,
    jlong sessionHandle, jobject frameBuffer
) {
    auto session = getValidSession(sessionHandle);
    if (!session) {
        LOGE("nativePushStreamingFrame: invalid session handle %lld", (long long)sessionHandle);
        return JNI_FALSE;
    }
    if (!frameBuffer) {
        LOGE("nativePushStreamingFrame: frameBuffer is null");
        return JNI_FALSE;
    }
    uint16_t* rawData = static_cast<uint16_t*>(env->GetDirectBufferAddress(frameBuffer));
    if (!rawData) {
        LOGE("nativePushStreamingFrame: failed to get direct buffer address");
        return JNI_FALSE;
    }
    jlong capacity = env->GetDirectBufferCapacity(frameBuffer);
    size_t expectedBytes = static_cast<size_t>(session->width()) * session->height() * sizeof(uint16_t);
    if (capacity < static_cast<jlong>(expectedBytes)) {
        LOGE("nativePushStreamingFrame: buffer capacity %lld < expected %zu", (long long)capacity, expectedBytes);
        return JNI_FALSE;
    }

    size_t numPixels = static_cast<size_t>(session->width()) * session->height();
    bool success = session->pushFrame(rawData, numPixels);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_nativeComputeGcamFrameScore(
    JNIEnv* env, jobject /* this */,
    jobject frameBuffer,
    jint width,
    jint height,
    jint cfaPattern,
    jfloat noiseProfileS,
    jfloat noiseProfileO,
    jlong /* exposureTimeNs */,
    jlong timeDeltaFromFirstNs
) {
    if (!frameBuffer) {
        LOGE("nativeComputeGcamFrameScore: frameBuffer is null");
        return 0.0f;
    }
    const uint16_t* rawData = static_cast<const uint16_t*>(env->GetDirectBufferAddress(frameBuffer));
    if (!rawData) {
        LOGE("nativeComputeGcamFrameScore: failed to get direct buffer address");
        return 0.0f;
    }
    jlong capacity = env->GetDirectBufferCapacity(frameBuffer);
    size_t expectedBytes = static_cast<size_t>(width) * height * sizeof(uint16_t);
    if (capacity < static_cast<jlong>(expectedBytes)) {
        LOGE("nativeComputeGcamFrameScore: buffer capacity %lld < expected %zu", (long long)capacity, expectedBytes);
        return 0.0f;
    }
    if (width < 8 || height < 8) {
        LOGE("nativeComputeGcamFrameScore: dimensions too small (%dx%d)", width, height);
        return 0.0f;
    }

    int regionW = std::min(512, width - 4);
    int regionH = std::min(512, height - 4);
    if (regionW < 4 || regionH < 4) {
        return 0.0f;
    }

    int startX = (width - regionW) / 2;
    int endX = startX + regionW;
    int startY = (height - regionH) / 2;
    int endY = startY + regionH;

    if (startX < 2) startX = 2;
    if (endX > width - 2) endX = width - 2;
    if (startY < 2) startY = 2;
    if (endY > height - 2) endY = height - 2;

    // Align startY to an even row
    if (startY % 2 != 0) {
        startY++;
    }

    // Bayer Green channel phase:
    // RGGB (0): row 0 is (R, Gr) => Green at x odd (parity 1)
    // GRBG (1): row 0 is (Gr, R) => Green at x even (parity 0)
    // GBRG (2): row 0 is (Gb, B) => Green at x even (parity 0)
    // BGGR (3): row 0 is (B, Gb) => Green at x odd (parity 1)
    int targetXParity = (cfaPattern == 1 || cfaPattern == 2) ? 0 : 1;
    if ((startX % 2) != targetXParity) {
        startX++;
    }

    constexpr int kStride = 2;
    double sumEnergy = 0.0;
    int64_t sampleCount = 0;

    for (int y = startY; y < endY; y += kStride) {
        const uint16_t* rowCenter = rawData + static_cast<size_t>(y) * width;
        const uint16_t* rowUp = rawData + static_cast<size_t>(y - 2) * width;
        const uint16_t* rowDown = rawData + static_cast<size_t>(y + 2) * width;

        for (int x = startX; x < endX; x += kStride) {
            float g = static_cast<float>(rowCenter[x]);
            float lap = 4.0f * g - static_cast<float>(rowCenter[x - 2])
                                 - static_cast<float>(rowCenter[x + 2])
                                 - static_cast<float>(rowUp[x])
                                 - static_cast<float>(rowDown[x]);
            float var = std::max(16.0f, noiseProfileS * g + noiseProfileO);
            sumEnergy += (lap * lap) / var;
            sampleCount++;
        }
    }

    float sharpness = (sampleCount > 0) ? static_cast<float>(sumEnergy / sampleCount) : 0.0f;
    float timePenalty = 0.5f * (static_cast<float>(std::max(0LL, static_cast<long long>(timeDeltaFromFirstNs))) / 100000000.0f);
    float finalScore = std::max(0.0f, sharpness - timePenalty);
    return finalScore;
}


extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_nativeFinishStreamingSession(
    JNIEnv* env, jobject /* this */,
    jlong sessionHandle, jstring tempRawPath, jobject outputBitmap,
    jfloat digitalGain, jint targetLog, jstring lutPath,
    jfloat zoomFactor, jboolean mirror,
    jboolean enableMemoryColor, jint colorEngineMode,
    jint fusionMode,
    jlongArray debugStats
) {
    auto session = getValidSession(sessionHandle);
    if (!session) {
        LOGE("nativeFinishStreamingSession: invalid session handle %lld", (long long)sessionHandle);
        return -1;
    }

    // Set fusion parameters before finish
    session->setFusionParameters(fusionMode, zoomFactor);

    // Unregister session so new push calls on this handle fail immediately,
    // while `session` shared_ptr keeps it alive during finish & bitmap rendering.
    unregisterSession(sessionHandle);

    const bool faithfulHighlights = (session->framesPushed() == 1);

    std::shared_ptr<SharedCaptureResult> sharedResult;
    Halide::Runtime::Buffer<uint16_t> outRgbBuf;
    Halide::Runtime::Buffer<uint16_t> outBayerBuf;
    int finishRes = session->finish(sharedResult, outRgbBuf, outBayerBuf);
    if (finishRes != 0 || !sharedResult) {
        LOGE("nativeFinishStreamingSession: finish failed with code %d", finishRes);
        return -1;
    }

    if (debugStats != nullptr) {
        const jsize len = env->GetArrayLength(debugStats);
        if (len > 0) {
            jlong stats[20] = {0};
            env->GetLongArrayRegion(debugStats, 0, std::min<jsize>(len, 20), stats);
            stats[0] = session->normalizeMs() + session->fusionComputeMs();
            stats[8] = session->normalizeMs();
            stats[9] = session->fusionComputeMs();
            if (len >= 19) {
                stats[15] = (jlong)session->pushCount();
                stats[16] = (session->pushCount() > 0) ? (session->pushTotalMs() / session->pushCount()) : 0;
                stats[17] = session->pushMinMs();
                stats[18] = session->pushMaxMs();
            }
            if (len >= 20) {
                stats[19] = session->normalizeMs();
            }
            env->SetLongArrayRegion(debugStats, 0, std::min<jsize>(len, 20), stats);
        }
    }

    const char* tr_p_cstr = tempRawPath ? env->GetStringUTFChars(tempRawPath, 0) : nullptr;
    if (tr_p_cstr) {
        {
            std::lock_guard<std::mutex> mapLock(g_sharedMemoryMutex);
            g_sharedMemoryMap[tr_p_cstr] = sharedResult;
        }
        env->ReleaseStringUTFChars(tempRawPath, tr_p_cstr);
    }

    if (outputBitmap) {
        unsigned char* bitmapPixels = nullptr;
        int out_w = 0, out_h = 0;
        if (AndroidBitmap_lockPixels(env, outputBitmap, reinterpret_cast<void**>(&bitmapPixels)) >= 0) {
            AndroidBitmapInfo info;
            AndroidBitmap_getInfo(env, outputBitmap, &info);
            out_w = info.width;
            out_h = info.height;
        }
        if (bitmapPixels) {
            const char* lut_path_cstr = lutPath ? env->GetStringUTFChars(lutPath, 0) : nullptr;
            LUT3D lut;
            if (lut_path_cstr) {
                auto cached = get_cached_lut(lut_path_cstr);
                if (cached) lut = *cached;
                env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
            }

            int width = session->width();
            int height = session->height();
            if (sharedResult->rgbBuf.empty() && sharedResult->gpuRgbTexture != 0) {
                LOGD("Lazy readback of GPU unified texture %u for streaming finish outputBitmap preview (%dx%d)",
                     sharedResult->gpuRgbTexture, width, height);
                sharedResult->rgbBuf.resize(static_cast<size_t>(width) * height * 3);
                bool okRb = darkbag::gpu::GpuRcdComputeEngine::instance().readbackRgbTextureToCpu(
                    sharedResult->gpuRgbTexture, width, height, sharedResult->rgbBuf.data()
                );
                if (!okRb) {
                    LOGE("GPU readback failed for streaming finish outputBitmap preview");
                    sharedResult->rgbBuf.clear();
                }
            }
            uint16_t* raw_ptr = sharedResult->rgbBuf.data();
            if (!raw_ptr || sharedResult->rgbBuf.empty()) {
                LOGE("nativeFinishStreamingSession: missing raw buffer for preview, skipping");
            } else {
                int stride_x = 1;
                int stride_y = width;
                int stride_c = width * height;
                const int fastPreviewDownsample = compute_preview_downsample_factor(width, height, 1280);

                float effectiveZoom = (sharedResult && sharedResult->isZoomCropped) ? 1.0f : zoomFactor;
                const float* effectiveWb = (sharedResult && sharedResult->isWhiteBalanceApplied) ? nullptr : session->whiteBalanceData();

                process_and_save_image(
                    raw_ptr, stride_x, stride_y, stride_c,
                    session->lensShadingData(), session->lensShadingRows(), session->lensShadingCols(),
                    width, height, digitalGain, targetLog, lut,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                    nullptr, nullptr, nullptr, 1,
                    session->ccmData(), effectiveWb,
                    session->orientation(), bitmapPixels, out_w, out_h,
                    true, fastPreviewDownsample, effectiveZoom, (bool)mirror,
                    (bool)enableMemoryColor, (int)colorEngineMode, faithfulHighlights
                );
            }
            AndroidBitmap_unlockPixels(env, outputBitmap);
        }
    }

    LOGD("nativeFinishStreamingSession: successfully finished session %lld", (long long)sessionHandle);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_nativeAbortStreamingSession(
    JNIEnv* /* env */, jobject /* this */, jlong sessionHandle
) {
    auto session = getValidSession(sessionHandle);
    if (session) {
        LOGD("nativeAbortStreamingSession: aborting session %lld", (long long)sessionHandle);
        unregisterSession(sessionHandle);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_rcdDemosaicNative(
    JNIEnv* env, jobject /* this */,
    jobject bayerBuffer,
    jint width,
    jint height,
    jint cfaPattern,
    jintArray blackLevelPattern,
    jint whiteLevel,
    jfloatArray whiteBalanceGains,
    jobject rgbBuffer
) {
    if (!bayerBuffer || !rgbBuffer || !blackLevelPattern) {
        LOGE("rcdDemosaicNative: Null buffer or parameters provided");
        return;
    }

    auto* bayerData = static_cast<const uint16_t*>(env->GetDirectBufferAddress(bayerBuffer));
    auto* rgbData = static_cast<uint16_t*>(env->GetDirectBufferAddress(rgbBuffer));
    
    if (!bayerData || !rgbData) {
        LOGE("rcdDemosaicNative: Invalid direct buffers");
        return;
    }

    jint* blData = env->GetIntArrayElements(blackLevelPattern, nullptr);
    uint16_t blArray[4] = {
        static_cast<uint16_t>(std::max(0, blData[0])),
        static_cast<uint16_t>(std::max(0, blData[1])),
        static_cast<uint16_t>(std::max(0, blData[2])),
        static_cast<uint16_t>(std::max(0, blData[3]))
    };
    env->ReleaseIntArrayElements(blackLevelPattern, blData, JNI_ABORT);

    float wbArray[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    const float* wbPtr = nullptr;
    if (whiteBalanceGains && env->GetArrayLength(whiteBalanceGains) >= 4) {
        env->GetFloatArrayRegion(whiteBalanceGains, 0, 4, wbArray);
        wbPtr = wbArray;
    }

    bool gpuDemosaicOk = false;
    if (darkbag::gpu::GpuRcdComputeEngine::instance().isAvailable()) {
        int64_t gpuComputeMs = 0;
        gpuDemosaicOk = darkbag::gpu::GpuRcdComputeEngine::instance().demosaicToCpuBuffer(
            bayerData,
            width,
            height,
            cfaPattern,
            blArray,
            static_cast<uint16_t>(whiteLevel),
            wbPtr,
            rgbData,
            &gpuComputeMs
        );
        if (gpuDemosaicOk) {
            LOGD("rcdDemosaicNative GPU Compute succeeded in %lld ms", (long long)gpuComputeMs);
        } else {
            LOGW("rcdDemosaicNative GPU Compute failed, falling back to CPU");
        }
    }

    if (!gpuDemosaicOk) {
        darkbag::demosaic::rcd_demosaic(
            bayerData,
            width,
            height,
            cfaPattern,
            blArray,
            static_cast<uint16_t>(whiteLevel),
            wbPtr,
            rgbData
        );
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_nativeWriteRawImageDng(
    JNIEnv* env, jobject /* this */,
    jobject rawBuffer, jint bufferOffset,
    jint width, jint height,
    jint rowStrideBytes, jint pixelStrideBytes,
    jstring outputPath, jint outFd,
    jint orientationDegrees, jint whiteLevel,
    jfloatArray blackLevelPattern, jint cfaPattern,
    jfloatArray colorMatrix1, jfloatArray colorMatrix2,
    jfloatArray forwardMatrix1, jfloatArray forwardMatrix2,
    jint calibrationIlluminant1, jint calibrationIlluminant2,
    jfloatArray neutralColorPoint,
    jfloatArray lensShadingMap, jint lensShadingRows, jint lensShadingCols,
    jintArray activeArea, jdoubleArray noiseProfile,
    jint iso, jlong exposureTimeNanos, jfloat focalLength, jint focalLength35mm, jfloat fNumber,
    jint dngCompressionMode, jboolean isHdrPlus,
    jobject metadataObj, jfloat digitalGain
) {
    if (!rawBuffer || width <= 0 || height <= 0 || bufferOffset < 0) return JNI_FALSE;
    jlong capacity = env->GetDirectBufferCapacity(rawBuffer);
    uint8_t* rawBase = static_cast<uint8_t*>(env->GetDirectBufferAddress(rawBuffer));
    if (!rawBase || capacity <= 0) return JNI_FALSE;

    int stride_x = (pixelStrideBytes > 0) ? (pixelStrideBytes / 2) : 1;
    int stride_y = (rowStrideBytes > 0) ? (rowStrideBytes / 2) : width;
    int stride_c = 0;

    size_t requiredBytes = static_cast<size_t>(bufferOffset) +
        (static_cast<size_t>(height - 1) * stride_y + static_cast<size_t>(width) * stride_x) * sizeof(unsigned short);
    if (static_cast<size_t>(capacity) < requiredBytes) {
        LOGE("nativeWriteRawImageDng: Direct buffer capacity %lld < required %zu",
             (long long)capacity, requiredBytes);
        return JNI_FALSE;
    }

    const unsigned short* planarData = reinterpret_cast<const unsigned short*>(rawBase + bufferOffset);

    const char* outPathCStr = outputPath ? env->GetStringUTFChars(outputPath, nullptr) : nullptr;

    float bl_pattern[4] = {64.0f, 64.0f, 64.0f, 64.0f};
    bool has_bl = false;
    if (blackLevelPattern && env->GetArrayLength(blackLevelPattern) >= 4) {
        env->GetFloatArrayRegion(blackLevelPattern, 0, 4, bl_pattern);
        has_bl = true;
    }

    std::vector<float> cm1Vec, cm2Vec, fm1Vec, fm2Vec, neutralVec;
    const float* cm1Ptr = nullptr;
    const float* cm2Ptr = nullptr;
    const float* fm1Ptr = nullptr;
    const float* fm2Ptr = nullptr;
    const float* neutralPtr = nullptr;

    if (colorMatrix1 && env->GetArrayLength(colorMatrix1) >= 9) {
        cm1Vec.resize(9);
        env->GetFloatArrayRegion(colorMatrix1, 0, 9, cm1Vec.data());
        cm1Ptr = cm1Vec.data();
    }
    if (colorMatrix2 && env->GetArrayLength(colorMatrix2) >= 9) {
        cm2Vec.resize(9);
        env->GetFloatArrayRegion(colorMatrix2, 0, 9, cm2Vec.data());
        cm2Ptr = cm2Vec.data();
    }
    if (forwardMatrix1 && env->GetArrayLength(forwardMatrix1) >= 9) {
        fm1Vec.resize(9);
        env->GetFloatArrayRegion(forwardMatrix1, 0, 9, fm1Vec.data());
        fm1Ptr = fm1Vec.data();
    }
    if (forwardMatrix2 && env->GetArrayLength(forwardMatrix2) >= 9) {
        fm2Vec.resize(9);
        env->GetFloatArrayRegion(forwardMatrix2, 0, 9, fm2Vec.data());
        fm2Ptr = fm2Vec.data();
    }
    if (neutralColorPoint && env->GetArrayLength(neutralColorPoint) >= 3) {
        neutralVec.resize(3);
        env->GetFloatArrayRegion(neutralColorPoint, 0, 3, neutralVec.data());
        neutralPtr = neutralVec.data();
    }

    std::vector<float> lensShadingVec;
    const float* lens_shading_ptr = nullptr;
    if (lensShadingMap && lensShadingRows > 0 && lensShadingCols > 0) {
        int lsSize = env->GetArrayLength(lensShadingMap);
        int expected = 4 * lensShadingRows * lensShadingCols;
        if (lsSize >= expected) {
            lensShadingVec.resize(expected);
            env->GetFloatArrayRegion(lensShadingMap, 0, expected, lensShadingVec.data());
            lens_shading_ptr = lensShadingVec.data();
        }
    }

    int active_area[4] = {0};
    const int* active_area_ptr = nullptr;
    if (activeArea && env->GetArrayLength(activeArea) >= 4) {
        env->GetIntArrayRegion(activeArea, 0, 4, active_area);
        active_area_ptr = active_area;
    }

    double noise_prof[8] = {0};
    const double* noise_ptr = nullptr;
    if (noiseProfile && env->GetArrayLength(noiseProfile) >= 8) {
        env->GetDoubleArrayRegion(noiseProfile, 0, 8, noise_prof);
        noise_ptr = noise_prof;
    }

    ImageMetadata meta;
    if (metadataObj) {
        meta = metadataFromJava(env, metadataObj);
    }
    if (meta.iso == 0) meta.iso = iso;
    if (meta.exposureTime == 0) meta.exposureTime = exposureTimeNanos;
    if (meta.focalLength == 0.0f) meta.focalLength = focalLength;
    if (meta.focalLengthIn35mmFilm == 0) meta.focalLengthIn35mmFilm = focalLength35mm;
    if (meta.fNumber == 0.0f) meta.fNumber = fNumber;
    if (meta.uniqueCameraModel.empty() || meta.uniqueCameraModel == "Unknown") {
        meta.uniqueCameraModel = "Darkbag";
    }

    std::vector<float> ccmVec(9, 0.0f);
    if (fm1Ptr) {
        // D50 XYZ -> sRGB Bradford adaptation matrix to map ForwardMatrix1 to sRGB
        const float M_XYZ_D50_TO_SRGB[9] = {
             3.1338561f, -1.6168667f, -0.4906146f,
            -0.9787684f,  1.9161415f,  0.0334540f,
             0.0719453f, -0.2289914f,  1.4052427f
        };
        for (int r = 0; r < 3; ++r) {
            for (int c = 0; c < 3; ++c) {
                float sum = 0.0f;
                for (int k = 0; k < 3; ++k) {
                    sum += M_XYZ_D50_TO_SRGB[r * 3 + k] * fm1Ptr[k * 3 + c];
                }
                ccmVec[r * 3 + c] = sum;
            }
        }
    } else if (cm1Ptr) {
        for (int i = 0; i < 9; ++i) ccmVec[i] = cm1Ptr[i];
    } else {
        ccmVec = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    }

    float baselineExposure = (digitalGain > 0.0f) ? std::log2(digitalGain) : 0.0f;
    std::vector<float> wbVec = {1.0f, 1.0f, 1.0f, 1.0f};
    if (neutralPtr) {
        wbVec[0] = 1.0f / std::max(1e-4f, neutralPtr[0]);
        wbVec[1] = 1.0f / std::max(1e-4f, neutralPtr[1]);
        wbVec[2] = 1.0f / std::max(1e-4f, neutralPtr[1]);
        wbVec[3] = 1.0f / std::max(1e-4f, neutralPtr[2]);
    }

    bool ok = write_dng(
        outPathCStr, width, height, planarData,
        stride_x, stride_y, stride_c,
        whiteLevel,
        ccmVec, meta, orientationDegrees, false, baselineExposure, wbVec.data(),
        cm1Ptr, cm2Ptr, fm1Ptr, fm2Ptr,
        calibrationIlluminant1, calibrationIlluminant2, neutralPtr,
        outFd, dngCompressionMode,
        /*isBayer=*/true, cfaPattern, has_bl ? bl_pattern : nullptr,
        noise_ptr, active_area_ptr, lens_shading_ptr, lensShadingRows, lensShadingCols
    );

    if (outPathCStr) {
        env->ReleaseStringUTFChars(outputPath, outPathCStr);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <memory>
#include <chrono>
#include <future>
#include <dlfcn.h>
#include <libraw/libraw.h>
#include <HalideBuffer.h>
#include "ColorPipe.h"

// Included generated headers for both versions
#include "hdrplus_raw_pipeline_cpu.h"
#include "hdrplus_raw_pipeline_gpu.h"

#define TAG "HdrPlusJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace Halide::Runtime;

// Check for OpenCL availability at runtime
bool is_opencl_available() {
    void* handle = dlopen("libOpenCL.so", RTLD_LAZY);
    if (!handle) {
        handle = dlopen("libPVROCL.so", RTLD_LAZY);
    }
    if (handle) {
        dlclose(handle);
        return true;
    }
    return false;
}

// Redirect Halide output to Android Logcat
extern "C" void halide_print(void *user_context, const char *msg) {
    __android_log_print(ANDROID_LOG_DEBUG, "HalideRuntime", "%s", msg);
}

// Custom error handler to avoid abort()
extern "C" void halide_error(void *user_context, const char *msg) {
    __android_log_print(ANDROID_LOG_ERROR, "HalideRuntime", "Halide Error: %s", msg);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_processHdrPlus(
        JNIEnv* env,
        jobject /* this */,
        jobjectArray dngBuffers,
        jint width,
        jint height,
        jint orientation,
        jint whiteLevel,
        jint blackLevel,
        jfloatArray whiteBalance,
        jfloatArray ccm,
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
        jboolean useGpu,
        jlongArray debugStats
) {
    LOGD("Native processHdrPlus started. Requested useGpu=%d", useGpu);

    bool actualUseGpu = false;
    if (useGpu) {
        if (is_opencl_available()) {
            actualUseGpu = true;
            LOGD("OpenCL detected. Using GPU pipeline.");
        } else {
            LOGD("OpenCL NOT detected. Falling back to CPU pipeline.");
        }
    } else {
        LOGD("Using CPU pipeline (requested).");
    }

    int numFrames = env->GetArrayLength(dngBuffers);
    if (numFrames < 2) {
        LOGE("HDR+ requires at least 2 frames.");
        return -1;
    }

    // 1. Prepare Inputs for Halide
    std::vector<uint16_t> rawData(width * height * numFrames);
    for (int i = 0; i < numFrames; i++) {
        jobject bufObj = env->GetObjectArrayElement(dngBuffers, i);
        uint16_t* src = (uint16_t*)env->GetDirectBufferAddress(bufObj);
        if (!src) {
            LOGE("Failed to get direct buffer address for frame %d", i);
            return -1;
        }
        std::copy(src, src + (width * height), rawData.data() + (i * width * height));
    }
    Buffer<uint16_t> inputBuf(rawData.data(), width, height, numFrames);

    // 2. Prepare Metadata
    jfloat* wbData = env->GetFloatArrayElements(whiteBalance, nullptr);
    float wb_r = wbData[0], wb_g0 = wbData[1], wb_g1 = wbData[2], wb_b = wbData[3];
    std::vector<float> wbVec = {wb_r, wb_g0, wb_g1, wb_b};
    env->ReleaseFloatArrayElements(whiteBalance, wbData, JNI_ABORT);

    jfloat* ccmData = env->GetFloatArrayElements(ccm, nullptr);
    std::vector<float> ccmVec(9);
    for(int i=0; i<9; ++i) ccmVec[i] = ccmData[i];
    env->ReleaseFloatArrayElements(ccm, ccmData, JNI_ABORT);

    Buffer<float> ccmHalideBuf(ccmVec.data(), 3, 3);

    Matrix3x3 srgb_to_xyz = get_srgb_to_xyz_matrix();
    Buffer<float> m_srgb_to_xyz_buf(srgb_to_xyz.m, 3, 3);

    Matrix3x3 xyz_to_target = get_xyz_to_target_matrix(targetLog);
    Buffer<float> m_xyz_to_target_buf(xyz_to_target.m, 3, 3);

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    bool has_lut = false;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
        if (lut.size > 0) has_lut = true;
    }

    std::vector<float> dummyLutData(3 * 2 * 2 * 2, 0.0f);
    Buffer<float> lutBuf;
    int lut_size = 0;
    if (has_lut) {
        lutBuf = Buffer<float>((float*)lut.data.data(), 3, lut.size, lut.size, lut.size);
        lut_size = lut.size;
    } else {
        lutBuf = Buffer<float>(dummyLutData.data(), 3, 2, 2, 2);
        lut_size = 2;
    }

    // 3. Prepare Output Buffers
    Buffer<uint16_t> outputLinear(width, height, 3);
    Buffer<uint16_t> outputFinal(width, height, 3);

    // 4. Run Pipeline
    if (cfaPattern == 0) cfaPattern = 3;
    else if (cfaPattern == 3) cfaPattern = 0;

    auto halideStart = std::chrono::high_resolution_clock::now();
    int result = 0;

    if (actualUseGpu) {
        result = hdrplus_raw_pipeline_gpu(
            inputBuf, (uint16_t)blackLevel, (uint16_t)whiteLevel,
            wb_r, wb_g0, wb_g1, wb_b, cfaPattern, ccmHalideBuf,
            digitalGain, targetLog, m_srgb_to_xyz_buf, m_xyz_to_target_buf,
            lutBuf, lut_size, has_lut, outputLinear, outputFinal
        );
    } else {
        result = hdrplus_raw_pipeline_cpu(
            inputBuf, (uint16_t)blackLevel, (uint16_t)whiteLevel,
            wb_r, wb_g0, wb_g1, wb_b, cfaPattern, ccmHalideBuf,
            digitalGain, targetLog, m_srgb_to_xyz_buf, m_xyz_to_target_buf,
            lutBuf, lut_size, has_lut, outputLinear, outputFinal
        );
    }

    auto halideEnd = std::chrono::high_resolution_clock::now();
    auto durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(halideEnd - halideStart).count();

    if (debugStats != nullptr) {
        jlong stats[] = {(jlong)durationMs};
        env->SetLongArrayRegion(debugStats, 0, 1, stats);
    }

    if (result != 0) {
        LOGE("Halide execution failed with code %d", result);
        return -1;
    }

    LOGD("Halide pipeline finished in %ld ms.", (long)durationMs);

    // 5. Parallel Post-Processing & Saving
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    const char* dng_path_cstr = (outputDngPath) ? env->GetStringUTFChars(outputDngPath, 0) : nullptr;

    std::vector<uint16_t> linearInterleaved(width * height * 3);
    {
        const uint16_t* ptr = outputLinear.data();
        int stride_x = outputLinear.dim(0).stride(), stride_y = outputLinear.dim(1).stride(), stride_c = outputLinear.dim(2).stride();
        uint16_t clip_limit = 16383;
        #pragma omp parallel for
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                uint16_t r = std::min(ptr[x * stride_x + y * stride_y + 0 * stride_c], clip_limit);
                uint16_t g = std::min(ptr[x * stride_x + y * stride_y + 1 * stride_c], clip_limit);
                uint16_t b = std::min(ptr[x * stride_x + y * stride_y + 2 * stride_c], clip_limit);
                int idx = (y * width + x) * 3;
                linearInterleaved[idx + 0] = r << 2; linearInterleaved[idx + 1] = g << 2; linearInterleaved[idx + 2] = b << 2;
            }
        }
    }

    std::vector<uint16_t> finalInterleaved(width * height * 3);
    {
        const uint16_t* ptr = outputFinal.data();
        int stride_x = outputFinal.dim(0).stride(), stride_y = outputFinal.dim(1).stride(), stride_c = outputFinal.dim(2).stride();
        #pragma omp parallel for
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 3;
                finalInterleaved[idx + 0] = ptr[x * stride_x + y * stride_y + 0 * stride_c];
                finalInterleaved[idx + 1] = ptr[x * stride_x + y * stride_y + 1 * stride_c];
                finalInterleaved[idx + 2] = ptr[x * stride_x + y * stride_y + 2 * stride_c];
            }
        }
    }

    auto dng_task = std::async(std::launch::async, [&]() {
        if (!dng_path_cstr) return true;
        return write_dng(dng_path_cstr, width, height, linearInterleaved, 65535, iso, exposureTime, fNumber, focalLength, captureTimeMillis, ccmVec, orientation);
    });

    auto img_task = std::async(std::launch::async, [&]() {
        save_processed_image_simple(finalInterleaved, width, height, tiff_path_cstr, jpg_path_cstr, orientation);
    });

    bool dng_ok = dng_task.get();
    img_task.wait();

    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
    if (outputDngPath) env->ReleaseStringUTFChars(outputDngPath, dng_path_cstr);

    if (!dng_ok) LOGE("Failed to write DNG file.");
    return 0;
}

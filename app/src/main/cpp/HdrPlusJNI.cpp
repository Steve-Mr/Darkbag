#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <memory>
#include <cstring>
#include <chrono> // For timing
#include <thread>
#include <future>
#include <libraw/libraw.h>
#include <HalideBuffer.h>
#include <HalideRuntime.h>
#include "ColorPipe.h"
#include "hdrplus_raw_pipeline.h" // Generated header

#define TAG "HdrPlusJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace Halide::Runtime;

namespace {
void fillDebugStats(JNIEnv* env,
                    jlongArray debugStats,
                    jlong copyMs,
                    jlong halideMs,
                    jlong postProcessMs,
                    jlong dngMs,
                    jlong saveMs,
                    jlong totalMs) {
    if (debugStats == nullptr) return;
    const jsize len = env->GetArrayLength(debugStats);
    if (len <= 0) return;

    // Backward compatible layout:
    // [0] halideMs (legacy)
    // [1] copyMs
    // [2] postProcessMs (planar -> interleaved)
    // [3] dngMs
    // [4] logPathSaveMs (process + save tiff/jpg)
    // [5] totalNativeMs
    jlong stats[6] = {
            halideMs,
            copyMs,
            postProcessMs,
            dngMs,
            saveMs,
            totalMs,
    };

    env->SetLongArrayRegion(debugStats, 0, std::min<jsize>(len, 6), stats);
}
} // namespace

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
        jlongArray debugStats
) {
    LOGD("Native processHdrPlus started.");
    auto nativeStart = std::chrono::high_resolution_clock::now();

    int numFrames = env->GetArrayLength(dngBuffers);
    if (numFrames < 2) {
        LOGE("HDR+ requires at least 2 frames.");
        return -1;
    }
    LOGD("Processing %d frames.", numFrames);

    // 1. Prepare Inputs for Halide
    std::vector<uint16_t> rawData(width * height * numFrames);
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
        std::memcpy(rawData.data() + (static_cast<size_t>(i) * width * height), framePtrs[i], frameSizeBytes);
    }
    auto copyEnd = std::chrono::high_resolution_clock::now();
    auto copyDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(copyEnd - copyStart).count();

    Buffer<uint16_t> inputBuf(rawData.data(), width, height, numFrames);

    // 2. Prepare Metadata
    jfloat* wbData = env->GetFloatArrayElements(whiteBalance, nullptr);
    float wb_r = wbData[0];
    float wb_g0 = wbData[1];
    float wb_g1 = wbData[2];
    float wb_b = wbData[3];

    // Store WB in vector for ColorPipe
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
    Buffer<uint16_t> outputBuf(width, height, 3);

    // 4. Run Pipeline
    float compression = 1.0f; // Unused now in our modified generator
    float gain = 1.0f;        // Unused now

    // Fix for Red/Blue Swap (Bayer Phase Mismatch)
    // Assuming RGGB (0) <-> BGGR (3) mismatch
    if (cfaPattern == 0) {
        cfaPattern = 3; // Force BGGR
        LOGD("Swapped CFA: RGGB -> BGGR");
    } else if (cfaPattern == 3) {
        cfaPattern = 0; // Force RGGB
        LOGD("Swapped CFA: BGGR -> RGGB");
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
        cfaPattern,
        ccmHalideBuf,
        compression,
        gain,
        outputBuf
    );

    auto halideEnd = std::chrono::high_resolution_clock::now();
    auto durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(halideEnd - halideStart).count();

    if (result != 0) {
        LOGE("Halide execution failed with code %d", result);
        return -1;
    }

    LOGD("Halide pipeline finished.");

    // 5. Post-Processing (Log + LUT)

    // Load LUT
    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    }

    // Process Output
    // finalImage: Linear RGB (clipped) for DNG
    std::vector<unsigned short> finalImage(width * height * 3);

    // Halide Output is Planar x, y, c
    int stride_x = outputBuf.dim(0).stride();
    int stride_y = outputBuf.dim(1).stride();
    int stride_c = outputBuf.dim(2).stride();
    const uint16_t* raw_ptr = outputBuf.data();

    // Determine clipping limit for Linear DNG
    // We scale data by 4x to fill 16-bit range, effectively moving the white point from 16383 to 65532.
    // This fixes issues where some DNG viewers ignore WhiteLevel for LinearRaw and assume 65535.
    uint16_t clip_limit = 16383; // Original limit

    auto postStart = std::chrono::high_resolution_clock::now();
    #pragma omp parallel for
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
             // Read Halide Planar
             uint16_t r_val = raw_ptr[x * stride_x + y * stride_y + 0 * stride_c];
             uint16_t g_val = raw_ptr[x * stride_x + y * stride_y + 1 * stride_c];
             uint16_t b_val = raw_ptr[x * stride_x + y * stride_y + 2 * stride_c];

             // Clipping to fix Pink Highlights (R > G saturation)
             r_val = std::min(r_val, clip_limit);
             g_val = std::min(g_val, clip_limit);
             b_val = std::min(b_val, clip_limit);

             // Write Interleaved (16-bit Linear) for DNG, Scaled by 4x
             int idx = (y * width + x) * 3;
             finalImage[idx + 0] = r_val << 2;
             finalImage[idx + 1] = g_val << 2;
             finalImage[idx + 2] = b_val << 2;
        }
    }
    auto postEnd = std::chrono::high_resolution_clock::now();
    auto postDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(postEnd - postStart).count();

    // Prepare paths
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    const char* dng_path_cstr = (outputDngPath) ? env->GetStringUTFChars(outputDngPath, 0) : nullptr;

    bool dng_ok = true;
    auto dngStart = std::chrono::high_resolution_clock::now();

    // Save DNG (Raw Path)
    int dngWhiteLevel = 65535; // Full 16-bit range now
    auto dngTask = std::async(std::launch::async, [&]() {
        if (dng_path_cstr) {
            return write_dng(dng_path_cstr, width, height, finalImage, dngWhiteLevel, iso, exposureTime, fNumber, focalLength, captureTimeMillis, ccmVec, orientation);
        }
        return true;
    });

    // Save Processed Images (Log/LUT Path)
    // Pass finalImage (Linear) + Gain + Logic to shared pipeline
    auto saveStart = std::chrono::high_resolution_clock::now();
    process_and_save_image(
        finalImage,
        width,
        height,
        digitalGain, // Gain to account for exposure (Data is already scaled 4x)
        targetLog,
        lut,
        tiff_path_cstr,
        jpg_path_cstr,
        1, // sourceColorSpace = Camera Native (requires ccm)
        ccmVec.data(), // CCM (Sensor -> XYZ) from Camera2 API
        wbVec.data(),   // WB Gains (Currently unused in HDR+ path, but kept for API)
        orientation // Pass orientation for TIFF writing
    );
    auto saveEnd = std::chrono::high_resolution_clock::now();
    auto saveDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(saveEnd - saveStart).count();

    dng_ok = dngTask.get();
    auto dngEnd = std::chrono::high_resolution_clock::now();
    auto dngDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(dngEnd - dngStart).count();

    // Release Strings
    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
    if (outputDngPath) env->ReleaseStringUTFChars(outputDngPath, dng_path_cstr);

    if (!dng_ok) LOGE("Failed to write DNG file.");

    auto nativeEnd = std::chrono::high_resolution_clock::now();
    auto totalDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(nativeEnd - nativeStart).count();
    fillDebugStats(env,
                   debugStats,
                   (jlong)copyDurationMs,
                   (jlong)durationMs,
                   (jlong)postDurationMs,
                   (jlong)dngDurationMs,
                   (jlong)saveDurationMs,
                   (jlong)totalDurationMs);

    return 0;
}

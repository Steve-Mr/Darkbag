#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <memory>
#include <cstring>
#include <chrono> // For timing
#include <thread>
#include <future>
#include <utility>
#include <regex>
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

extern "C" void halide_error(void* user_context, const char* str) {
    LOGE("HALIDE ERROR: %s", str);
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
    // Robust regex to capture Stage Name, Value, and Unit (ms or s)
    // Example: "  srgb_output: 80.5ms (1.2%)"
    std::regex re("([\\w\\._]+):\\s*([\\d\\.]+)\\s*(ms|s)");

    std::string line;
    std::stringstream ss(report);
    LOGD("Full Halide Profiler Report:\n%s", report.c_str());

    while (std::getline(ss, line)) {
        std::smatch match;
        if (std::regex_search(line, match, re)) {
            std::string name = match[1].str();
            float val = std::stof(match[2].str());
            std::string unit = match[3].str();
            long ms = (unit == "s") ? (long)(val * 1000.0f) : (long)val;

            if (name.find("alignment") != std::string::npos ||
                name.find("layer_") != std::string::npos ||
                name.find("scores") != std::string::npos) {
                stats.align += ms;
            } else if (name.find("merge_") != std::string::npos) {
                stats.merge += ms;
            } else if (name.find("black_white") != std::string::npos) {
                stats.black_white += ms;
            } else if (name.find("white_balance") != std::string::npos) {
                stats.white_balance += ms;
            } else if (name.find("demosaic") != std::string::npos) {
                stats.demosaic += ms;
            } else if (name.find("bilateral") != std::string::npos ||
                       name.find("desaturate") != std::string::npos ||
                       name.find("gauss") != std::string::npos) {
                stats.denoise += ms;
            } else if (name.find("srgb") != std::string::npos) {
                stats.srgb += ms;
            }
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

    // Expanded layout:
    // [0] halideMs (legacy)
    // [1] copyMs
    // [2] postProcessMs (planar -> interleaved)
    // [3] dngEncodeMs (time spent inside write_dng)
    // [4] logPathSaveMs (process + save tiff/bmp)
    // [5] dngJoinWaitMs (time waiting at dngTask.get())
    // [6] totalNativeMs
    // [7] Stage: Align
    // [8] Stage: Merge
    // [9] Stage: Demosaic
    // [10] Stage: Denoise
    // [11] Stage: sRGB
    // [12] JNI Overhead
    // [13] Stage: BlackWhite
    // [14] Stage: WB
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
    // Removed interleavedPool from global singleton to avoid race conditions with Background Workers
    bool isInitialized = false;

    void ensureCapacity(int w, int h, int frames) {
        if (!isInitialized || inputPool.width() < w || inputPool.height() < h || inputPool.dim(2).extent() < frames) {
            // Allocate new buffers. Note: Halide buffers do not zero-initialize by default.
            inputPool = Buffer<uint16_t>(w, h, frames);
            outputPool = Buffer<uint16_t>(w, h, 3);
            isInitialized = true;
            LOGD("Memory pool (re)allocated: %d x %d x %d", w, h, frames);
        }
    }
};

// Guarded by processingSemaphore(1) in Kotlin to ensure exclusive access
GlobalBuffers g_hdrPlusBuffers;

} // namespace

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = env->FindClass("com/android/example/cameraxbasic/processor/ColorProcessor");
    if (clazz) g_colorProcessorClass = (jclass)env->NewGlobalRef(clazz);

    // Register Halide Handlers
    halide_set_error_handler(halide_error);
    halide_set_custom_print(halide_print);
    LOGD("JNI_OnLoad: Halide error/print handlers registered.");

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
        jfloatArray whiteBalance,
        jfloat zoomFactor
) {
    LOGD("Native exportHdrPlus started.");

    const char* temp_path_cstr = env->GetStringUTFChars(tempRawPath, 0);
    std::ifstream in(temp_path_cstr, std::ios::binary);
    if (!in.is_open()) {
        LOGE("Failed to open temp raw file: %s", temp_path_cstr);
        env->ReleaseStringUTFChars(tempRawPath, temp_path_cstr);
        return -1;
    }

    // Use local allocation for background export to avoid race conditions with the main capture pipeline
    std::vector<uint16_t> finalImage(static_cast<size_t>(width) * height * 3);
    in.read((char*)finalImage.data(), finalImage.size() * sizeof(uint16_t));
    in.close();
    // Delete temp file after reading
    std::remove(temp_path_cstr);
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
    const char* dng_path_cstr = (dngPath) ? env->GetStringUTFChars(dngPath, 0) : nullptr;

    // Save DNG
    if (dng_path_cstr) {
        write_dng(dng_path_cstr, width, height, finalImage, 65535, iso, exposureTime, fNumber, focalLength, captureTimeMillis, ccmVec, orientation);
    }

    // Save TIFF/BMP (High Quality Export path)
    if (tiff_path_cstr || jpg_path_cstr) {
        process_and_save_image(
            finalImage, width, height, digitalGain, targetLog, lut,
            tiff_path_cstr, jpg_path_cstr,
            1, ccmVec.data(), wbVec.data(), orientation, nullptr,
            false, 1, zoomFactor
        );
    }

    if (tiffPath) env->ReleaseStringUTFChars(tiffPath, tiff_path_cstr);
    if (jpgPath) env->ReleaseStringUTFChars(jpgPath, jpg_path_cstr);
    if (dngPath) env->ReleaseStringUTFChars(dngPath, dng_path_cstr);

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
        jstring tempRawPath,
        jfloat zoomFactor
) {
    LOGD("Native processHdrPlus started.");
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

    // Use direct constructor to ensure clean metadata and explicit dimensions
    Buffer<uint16_t> inputBuf(rawDataPtr, width, height, numFrames);

    if (rawDataPtr) {
        LOGD("Input RAW Sample [0,0,0]: %d, [1,1,0]: %d, middle [%d,%d,0]: %d",
             (int)rawDataPtr[0], (int)rawDataPtr[width + 1], width/2, height/2, (int)rawDataPtr[(height/2)*width + (width/2)]);
    }

    // --- Signal Probe & Normalization ---
    uint16_t minV = 0xFFFF, maxV = 0;
    size_t nonZeroCount = 0;
    for (size_t i = 0; i < std::min<size_t>(1000000, static_cast<size_t>(width)*height); ++i) {
        uint16_t v = rawDataPtr[i];
        if (v < minV) minV = v;
        if (v > maxV) maxV = v;
        if (v > 0) nonZeroCount++;
    }
    LOGD("Input Signal Check (Frame 0): Min=%d, Max=%d, NonZero=%zu/1M", minV, maxV, nonZeroCount);

    inputBuf.set_host_dirty();

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

    Buffer<float> ccmHalideBuf(ccmVec.data(), 3, 3);
    ccmHalideBuf.set_host_dirty();

    LOGD("Passing Metadata to Halide: WL=%d, BL=%d, WB={%.2f, %.2f, %.2f, %.2f}, CFA=%d",
         whiteLevel, blackLevel, wb_r, wb_g0, wb_g1, wb_b, cfaPattern);
    LOGD("CCM: %.3f %.3f %.3f | %.3f %.3f %.3f | %.3f %.3f %.3f",
         ccmVec[0], ccmVec[1], ccmVec[2], ccmVec[3], ccmVec[4], ccmVec[5], ccmVec[6], ccmVec[7], ccmVec[8]);

    // 3. Prepare Output Buffer (16-bit Linear RGB)
    // Instead of wrapping a pool pointer, let Halide allocate a fresh buffer to avoid Gralloc/Re-use issues
    Buffer<uint16_t> outputBuf(width, height, 3);
    // Pattern initialization to detect if Halide writes anything
    outputBuf.fill(0x7FFF);
    outputBuf.set_host_dirty();

    LOGD("Input Buffer: %dx%dx%d, strides: %d, %d, %d",
         inputBuf.dim(0).extent(), inputBuf.dim(1).extent(), inputBuf.dim(2).extent(),
         inputBuf.dim(0).stride(), inputBuf.dim(1).stride(), inputBuf.dim(2).stride());
    LOGD("Output Buffer: %dx%dx%d, strides: %d, %d, %d",
         outputBuf.dim(0).extent(), outputBuf.dim(1).extent(), outputBuf.dim(2).extent(),
         outputBuf.dim(0).stride(), outputBuf.dim(1).stride(), outputBuf.dim(2).stride());

    // Host dirty flags are enough to trigger automatic GPU upload in Halide AOT
    inputBuf.set_host_dirty();
    ccmHalideBuf.set_host_dirty();

    auto jniPrepEnd = std::chrono::high_resolution_clock::now();
    auto jniPrepMs = std::chrono::duration_cast<std::chrono::milliseconds>(jniPrepEnd - jniPrepStart).count();

    // 4. Run Pipeline
    float compression = 1.0f;
    float gain = digitalGain;

    // Fix for Red/Blue Swap (Bayer Phase Mismatch)
    // Most Moto devices require swapping RGGB(0) and BGGR(3) for this Halide pipeline.
    int originalCfa = cfaPattern;
    if (cfaPattern == 0) cfaPattern = 3;
    else if (cfaPattern == 3) cfaPattern = 0;
    LOGD("CFA Swap: %d -> %d (DigitalGain=%.3f)", originalCfa, cfaPattern, gain);

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
    auto halideMs = std::chrono::duration_cast<std::chrono::milliseconds>(halideEnd - halideStart).count();

    // Sync back to host memory if GPU was used
    int copyRet = outputBuf.copy_to_host();

    LOGD("Halide Result Code: %d, Time: %ld ms, copy_to_host: %d, device_handle: %llu",
         result, (long)halideMs, copyRet, (unsigned long long)outputBuf.raw_buffer()->device);

    bool allZeros = true;
    if (outputBuf.data()) {
        LOGD("Halide Output Sample [0,0]: %d, %d, %d", (int)outputBuf(0, 0, 0), (int)outputBuf(0, 0, 1), (int)outputBuf(0, 0, 2));
        LOGD("Halide Output Sample [mid,mid]: %d, %d, %d", (int)outputBuf(width/2, height/2, 0), (int)outputBuf(width/2, height/2, 1), (int)outputBuf(width/2, height/2, 2));

        // Simple check if it's all zeros (or still the pattern)
        if (outputBuf(width/2, height/2, 0) != 0 && outputBuf(width/2, height/2, 0) != 0x7FFF) {
            allZeros = false;
        }
    }

    if (allZeros) {
        LOGW("Halide output is all zero! Applying DIAGNOSTIC BYPASS + PATTERN");
        const uint16_t* src0 = inputBuf.data();
        #pragma omp parallel for
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Read RAW Frame 0
                uint16_t raw_val = src0[y * width + x];

                // Add a faint synthetic gradient to the middle to verify I/O
                uint16_t synth = (uint16_t)((x % 256) << 8);

                // Output: Red = RAW, Green = Synthetic, Blue = Half-RAW
                outputBuf(x, y, 0) = raw_val;
                outputBuf(x, y, 1) = synth;
                outputBuf(x, y, 2) = raw_val / 2;
            }
        }
        LOGD("Bypass Sample [mid,mid]: R=%d, G=%d, B=%d",
             (int)outputBuf(width/2, height/2, 0), (int)outputBuf(width/2, height/2, 1), (int)outputBuf(width/2, height/2, 2));
    }

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

    // Lock Bitmap if provided
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
    // Use local allocation for interleaved result to prevent race conditions during background save
    std::vector<uint16_t> finalImage(static_cast<size_t>(width) * height * 3);

    // Halide Output is Planar x, y, c
    int stride_x = outputBuf.dim(0).stride();
    int stride_y = outputBuf.dim(1).stride();
    int stride_c = outputBuf.dim(2).stride();
    const uint16_t* raw_ptr = outputBuf.data();

    LOGD("Output Buffer Strides: x=%d, y=%d, c=%d, Extents: %d, %d, %d",
         stride_x, stride_y, stride_c, outputBuf.dim(0).extent(), outputBuf.dim(1).extent(), outputBuf.dim(2).extent());

    // Determine scaling for 16-bit output.
    // We scale the Halide output (which is in the sensor's range, roughly 0 to whiteLevel)
    // to fill the full 16-bit range (0 to 65535).
    float outputScale = 65535.0f / (float)whiteLevel;
    LOGD("Output Scaling: mapping [0, %d] -> [0, 65535] (scale=%.3f)", whiteLevel, outputScale);

    auto postStart = std::chrono::high_resolution_clock::now();
    #pragma omp parallel for
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
             // Read Halide Planar
             uint16_t r_raw = raw_ptr[x * stride_x + y * stride_y + 0 * stride_c];
             uint16_t g_raw = raw_ptr[x * stride_x + y * stride_y + 1 * stride_c];
             uint16_t b_raw = raw_ptr[x * stride_x + y * stride_y + 2 * stride_c];

             // Scale to 16-bit and clamp
             uint16_t r_val = (uint16_t)std::min(65535.0f, std::max(0.0f, r_raw * outputScale));
             uint16_t g_val = (uint16_t)std::min(65535.0f, std::max(0.0f, g_raw * outputScale));
             uint16_t b_val = (uint16_t)std::min(65535.0f, std::max(0.0f, b_raw * outputScale));

             // Write Interleaved (16-bit Linear) for DNG and JPEG processing
             int idx = (y * width + x) * 3;
             finalImage[idx + 0] = r_val;
             finalImage[idx + 1] = g_val;
             finalImage[idx + 2] = b_val;
        }
    }
    auto postEnd = std::chrono::high_resolution_clock::now();
    auto postDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(postEnd - postStart).count();

    // Prepare paths
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    const char* dng_path_cstr = (outputDngPath) ? env->GetStringUTFChars(outputDngPath, 0) : nullptr;

    // Prepare for Background I/O (Copy strings before releasing)
    std::string tiffPathStr = (tiff_path_cstr) ? tiff_path_cstr : "";
    std::string jpgPathStr = (jpg_path_cstr) ? jpg_path_cstr : "";
    std::string dngPathStr = (dng_path_cstr) ? dng_path_cstr : "";
    std::string dngName(dng_path_cstr ? dng_path_cstr : "");

    // Release JNI Strings early
    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
    if (outputDngPath) env->ReleaseStringUTFChars(outputDngPath, dng_path_cstr);

    // DNG White Level
    int dngWhiteLevel = 65535; // Full 16-bit range now

    // Save Processed Images (Log/LUT Path)
    auto saveStart = std::chrono::high_resolution_clock::now();

    // 1. Synchronous Processing for Bitmap (Legacy Preview)
    if (bitmapPixels) {
        process_and_save_image(
            finalImage,
            width,
            height,
            digitalGain,
            targetLog,
            lut,
            nullptr, // No TIFF path for sync call
            nullptr, // No JPG path for sync call
            1,
            ccmVec.data(),
            wbVec.data(),
            orientation,
            bitmapPixels,
            true, 4 // Fast preview, 4x downsample
        );
        AndroidBitmap_unlockPixels(env, outputBitmap);
    }

    // 2. Background I/O for TIFF, BMP, and DNG
    // We move the heavy I/O to a background thread and return to Java immediately.
    // This allows the UI to show the result while saving continues.
    size_t lastSlash = dngName.find_last_of('/');
    std::string baseName = (lastSlash != std::string::npos) ? dngName.substr(lastSlash + 1) : "HDRPLUS";
    if (baseName.find(".dng") != std::string::npos) baseName = baseName.substr(0, baseName.find(".dng"));
    if (baseName.find("_linear") != std::string::npos) baseName = baseName.substr(0, baseName.find("_linear"));

    // Decide whether to run I/O asynchronously.
    bool runAsync = (bool)isAsync;
    bool hasBgTasks = !tiffPathStr.empty() || !jpgPathStr.empty() || !dngPathStr.empty();

    const char* temp_raw_path_cstr = (tempRawPath) ? env->GetStringUTFChars(tempRawPath, 0) : nullptr;
    if (temp_raw_path_cstr) {
        std::ofstream out(temp_raw_path_cstr, std::ios::binary);
        if (out.is_open()) {
            out.write((char*)finalImage.data(), finalImage.size() * sizeof(unsigned short));
            out.close();
            LOGD("Saved intermediate RAW to %s", temp_raw_path_cstr);
        }
        env->ReleaseStringUTFChars(tempRawPath, temp_raw_path_cstr);
    }

    if (hasBgTasks) {
        // Since we are using g_hdrPlusBuffers.interleavedPool, we must COPY it
        // if we are running in background to avoid it being overwritten by the next capture.
        // However, the user wants "High Speed", and we have a Semaphore(2) in Kotlin.
        // If we use WorkManager, the data is already saved to disk as tempRaw.
        // So we don't need finalImage in the closure if we only use it for saving tempRaw.

        // Wait, saveFunc currently uses finalImage for DNG and TIFF saving.
        // If we use HQ Background Export (WorkManager), tiffPathStr and dngPathStr will be empty here.
        // Let's check CameraFragment.kt.

        auto saveFunc = [
            finalImageData = runAsync ? finalImage : std::vector<uint16_t>(), // Only copy if async
            runAsync,
            &finalImage, // Capture by reference for synchronous path
            width, height, digitalGain, targetLog, lut,
            tiffPathStr, jpgPathStr, dngPathStr, baseName,
            ccmVec, wbVec, orientation,
            dngWhiteLevel, iso, exposureTime, fNumber, focalLength, captureTimeMillis,
            zoomFactor
        ]() mutable {
            LOGD("Background save task started.");

            // Save DNG
            if (!dngPathStr.empty()) {
                write_dng(dngPathStr.c_str(), width, height, runAsync ? finalImageData : finalImage, dngWhiteLevel, iso, exposureTime, fNumber, focalLength, captureTimeMillis, ccmVec, orientation);
            }

            // Save TIFF/BMP
            if (!tiffPathStr.empty() || !jpgPathStr.empty()) {
                // If it's a preview JPG (not async), use downsampling and rotation.
                // If it's a background HQ task (async), use full resolution.
                bool isPrev = !runAsync;
                process_and_save_image(
                    runAsync ? finalImageData : finalImage, width, height, digitalGain, targetLog, lut,
                    tiffPathStr.empty() ? nullptr : tiffPathStr.c_str(),
                    jpgPathStr.empty() ? nullptr : jpgPathStr.c_str(),
                    1, ccmVec.data(), wbVec.data(), orientation, nullptr,
                isPrev, isPrev ? 4 : 1, zoomFactor
                );
            }
            LOGD("Background save task finished.");

            // Notify Java ONLY if running asynchronously (legacy mode).
            // In synchronous mode, Kotlin handles the saving itself after return.
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
                    jmethodID method = env->GetStaticMethodID(g_colorProcessorClass, "onBackgroundSaveComplete", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;)V");
                    if (method) {
                        jstring jBaseName = env->NewStringUTF(baseName.c_str());
                        jstring jTiffPath = tiffPathStr.empty() ? nullptr : env->NewStringUTF(tiffPathStr.c_str());
                        jstring jDngPath = dngPathStr.empty() ? nullptr : env->NewStringUTF(dngPathStr.c_str());
                        jstring jJpgPath = jpgPathStr.empty() ? nullptr : env->NewStringUTF(jpgPathStr.c_str());

                        env->CallStaticVoidMethod(g_colorProcessorClass, method, jBaseName, jTiffPath, jDngPath, jJpgPath, !tiffPathStr.empty(), nullptr);

                        if (jBaseName) env->DeleteLocalRef(jBaseName);
                        if (jTiffPath) env->DeleteLocalRef(jTiffPath);
                        if (jDngPath) env->DeleteLocalRef(jDngPath);
                        if (jJpgPath) env->DeleteLocalRef(jJpgPath);
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

    // Since we backgrounded the I/O, these stats will reflect only the synchronous part.
    fillDebugStats(env,
                   debugStats,
                   (jlong)copyDurationMs,
                   (jlong)durationMs,
                   (jlong)postDurationMs,
                   0, // DNG Encode ms (now in background)
                   (jlong)saveDurationMs,
                   0, // DNG Join wait ms (now in background)
                   (jlong)totalDurationMs,
                   (jlong)jniPrepMs,
                   stageStats);

    return 0;
}

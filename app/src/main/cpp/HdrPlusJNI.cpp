#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <memory>
#include <chrono> // For timing
#include <libraw/libraw.h>
#include <HalideBuffer.h>
#include "ColorPipe.h"
#include "ColorMatrices.h"
#include "hdrplus_raw_pipeline.h" // Generated header

#define TAG "HdrPlusJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace Halide::Runtime;

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

    int numFrames = env->GetArrayLength(dngBuffers);
    if (numFrames < 2) {
        LOGE("HDR+ requires at least 2 frames.");
        return -1;
    }
    LOGD("Processing %d frames.", numFrames);

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
    float wb_r = wbData[0];
    float wb_g0 = wbData[1];
    float wb_g1 = wbData[2];
    float wb_b = wbData[3];
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

    if (debugStats != nullptr) {
        jlong stats[] = {(jlong)durationMs};
        env->SetLongArrayRegion(debugStats, 0, 1, stats);
    }

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
    uint16_t clip_limit = 16383; // 65535 / 4

    #pragma omp parallel for
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
             // Read Halide Planar
             uint16_t r_val = raw_ptr[x * stride_x + y * stride_y + 0 * stride_c];
             uint16_t g_val = raw_ptr[x * stride_x + y * stride_y + 1 * stride_c];
             uint16_t b_val = raw_ptr[x * stride_x + y * stride_y + 2 * stride_c];

             // Write Interleaved (16-bit Linear) for DNG
             int idx = (y * width + x) * 3;

             // Clipping to fix Pink Highlights (R > G saturation)
             finalImage[idx + 0] = std::min(r_val, clip_limit);
             finalImage[idx + 1] = std::min(g_val, clip_limit);
             finalImage[idx + 2] = std::min(b_val, clip_limit);
        }
    }

    // Prepare paths
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    const char* dng_path_cstr = (outputDngPath) ? env->GetStringUTFChars(outputDngPath, 0) : nullptr;

    bool dng_ok = true;

    // Prepare WB Gains Vector for DNG Writer and Matrix Calculation
    std::vector<float> wbGains = {wb_r, (wb_g0 + wb_g1) / 2.0f, wb_b};
    // Use 4-component vector if needed for writer, but 3 for Matrix diagonal.
    std::vector<float> wbGainsFull = {wb_r, wb_g0, wb_g1, wb_b};

    // --- CCM Matrix Setup ---
    // Android returns COLOR_CORRECTION_TRANSFORM which is Sensor (Camera Native) -> XYZ.
    // DNG requires ColorMatrix1 which is XYZ -> Sensor (Camera Native).

    // 1. Construct M_CamNative_to_XYZ from ccmVec
    Mat3x3 M_CamNative_to_XYZ;
    int ccmIdx = 0;
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            M_CamNative_to_XYZ.m[i][j] = ccmVec[ccmIdx++];
        }
    }

    // 2. Calculate M_XYZ_to_CamNative (Inverse of Android CCM) for DNG
    Mat3x3 M_XYZ_to_CamNative = mat_inv(M_CamNative_to_XYZ);
    std::vector<float> dngCcmVec(9);
    ccmIdx = 0;
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            dngCcmVec[ccmIdx++] = M_XYZ_to_CamNative.m[i][j];
        }
    }

    // Save DNG (Raw Path) - finalImage is in Camera_WB space
    // We pass the WB gains so write_dng can adjust the ColorMatrix1 tag.
    int dngWhiteLevel = 16383;
    if (dng_path_cstr) {
        // Pass dngCcmVec (XYZ->Sensor) instead of raw ccmVec
        dng_ok = write_dng(dng_path_cstr, width, height, finalImage, dngWhiteLevel, iso, exposureTime, fNumber, focalLength, captureTimeMillis, dngCcmVec, wbGainsFull, orientation);
    }

    // --- Color Space Conversion (Camera WB -> ProPhoto RGB) ---

    // 3. Construct M_CamWB_to_CamNative (Undo White Balance)
    // Camera_WB = Camera_Native * WB_Gains
    // Camera_Native = Camera_WB * (1/WB_Gains)
    // Matrix is diagonal of inverse gains.
    Mat3x3 M_CamWB_to_CamNative = mat_diag(1.0f / wb_r, 1.0f / ((wb_g0+wb_g1)/2.0f), 1.0f / wb_b);

    // 4. Calculate Combined Matrix:
    // Chain: Camera_WB -> Camera_Native -> XYZ -> ProPhoto
    // M = M_XYZ_to_Pro * M_CamNative_to_XYZ * M_CamWB_to_CamNative
    Mat3x3 M_Temp = mat_mul(M_CamNative_to_XYZ, M_CamWB_to_CamNative);
    Mat3x3 M_CamWB_to_Pro = mat_mul(M_XYZ_D50_to_ProPhoto, M_Temp);
    // Apply in-place to finalImage
    #pragma omp parallel for
    for (int i = 0; i < width * height; i++) {
        float r = (float)finalImage[i * 3 + 0];
        float g = (float)finalImage[i * 3 + 1];
        float b = (float)finalImage[i * 3 + 2];

        float r_out = M_CamWB_to_Pro.m[0][0] * r + M_CamWB_to_Pro.m[0][1] * g + M_CamWB_to_Pro.m[0][2] * b;
        float g_out = M_CamWB_to_Pro.m[1][0] * r + M_CamWB_to_Pro.m[1][1] * g + M_CamWB_to_Pro.m[1][2] * b;
        float b_out = M_CamWB_to_Pro.m[2][0] * r + M_CamWB_to_Pro.m[2][1] * g + M_CamWB_to_Pro.m[2][2] * b;

        finalImage[i * 3 + 0] = (unsigned short)std::max(0.0f, std::min(65535.0f, r_out));
        finalImage[i * 3 + 1] = (unsigned short)std::max(0.0f, std::min(65535.0f, g_out));
        finalImage[i * 3 + 2] = (unsigned short)std::max(0.0f, std::min(65535.0f, b_out));
    }

    // Save Processed Images (Log/LUT Path)
    // Pass finalImage (now ProPhoto RGB Linear) + Gain + Logic to shared pipeline
    process_and_save_image(
        finalImage,
        width,
        height,
        4.0f * digitalGain, // Gain to account for 0.25x headroom + exposure
        targetLog,
        lut,
        tiff_path_cstr,
        jpg_path_cstr
    );

    // Release Strings
    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
    if (outputDngPath) env->ReleaseStringUTFChars(outputDngPath, dng_path_cstr);

    if (!dng_ok) LOGE("Failed to write DNG file.");

    return 0;
}

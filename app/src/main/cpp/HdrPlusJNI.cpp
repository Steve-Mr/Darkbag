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

    // Save DNG (Raw Path)
    // The DNG ColorMatrix1 tag requires XYZ -> Sensor matrix.
    // The ccmVec passed from Android is Sensor -> XYZ.
    // We must invert it.
    float ccm_arr[9];
    for(int i=0; i<9; i++) ccm_arr[i] = ccmVec[i];

    float dng_color_matrix[9];
    std::vector<float> dngCcmVec(9);

    if (mat_inv(ccm_arr, dng_color_matrix)) {
        for(int i=0; i<9; i++) dngCcmVec[i] = dng_color_matrix[i];
    } else {
        // Fallback to identity or original if singular (unlikely)
        dngCcmVec = ccmVec;
    }

    int dngWhiteLevel = 16383;
    if (dng_path_cstr) {
        dng_ok = write_dng(dng_path_cstr, width, height, finalImage, dngWhiteLevel, iso, exposureTime, fNumber, focalLength, captureTimeMillis, dngCcmVec, orientation);
    }

    // Convert Camera Native -> ProPhoto RGB
    // M_Sensor_to_Pro = M_XYZ_to_Pro * CCM * M_WB_inv
    // CCM is Sensor -> XYZ
    // We need to account for the fact that finalImage is already White Balanced.
    // finalImage ~= Sensor * WB
    // Sensor ~= finalImage / WB
    // XYZ = CCM * Sensor = CCM * (finalImage / WB)
    // Pro = M_XYZ_to_Pro * CCM * diag(1/WB) * finalImage

    float m_sensor_to_pro[9];
    float m_wb_inv[9];
    float wb_inv_vec[3] = {1.0f/wb_r, 1.0f/((wb_g0+wb_g1)/2.0f), 1.0f/wb_b};
    mat_diag(wb_inv_vec, m_wb_inv);

    float temp_mat[9];
    mat_mat_mult(M_XYZ_D50_to_ProPhoto, ccm_arr, temp_mat); // Temp = M_XYZ * CCM
    mat_mat_mult(temp_mat, m_wb_inv, m_sensor_to_pro);      // Final = Temp * WB_inv

    #pragma omp parallel for
    for (int i = 0; i < width * height; i++) {
        float r = (float)finalImage[i*3+0];
        float g = (float)finalImage[i*3+1];
        float b = (float)finalImage[i*3+2];

        float in[3] = {r, g, b};
        float out[3];

        mat_vec_mult(m_sensor_to_pro, in, out);

        finalImage[i*3+0] = (unsigned short)std::max(0.0f, std::min(65535.0f, out[0]));
        finalImage[i*3+1] = (unsigned short)std::max(0.0f, std::min(65535.0f, out[1]));
        finalImage[i*3+2] = (unsigned short)std::max(0.0f, std::min(65535.0f, out[2]));
    }

    // Save Processed Images (Log/LUT Path)
    // Pass finalImage (Linear ProPhoto) + Gain + Logic to shared pipeline
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

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <memory>
#include <libraw/libraw.h>
#include <HalideBuffer.h>
#include "ColorPipe.h"
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
        jint whiteLevel,
        jint blackLevel,
        jfloatArray whiteBalance, // [r, g0, g1, b]
        jfloatArray ccm,          // [3x3] or [3x4] flat
        jint cfaPattern,
        jint targetLog,
        jstring lutPath,
        jstring outputTiffPath,
        jstring outputJpgPath,
        jstring outputDngPath
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

    Buffer<float> ccmHalideBuf(ccmVec.data(), 3, 3);

    // 3. Prepare Output Buffer (16-bit Linear RGB)
    // The generator now outputs uint16_t in planar format (Halide default: x, y, c)
    // However, for efficiency, let's try to ask for interleaved if possible, or handle planar.
    // Halide Buffer default construction with 3 dims is usually [x, y, c] or [c, x, y] depending on how you read it.
    // Let's assume standard planar: width, height, channels.

    // We allocate a buffer for 16-bit output.
    Buffer<uint16_t> outputBuf(width, height, 3);

    // 4. Run Pipeline
    float compression = 1.0f; // Unused now in our modified generator
    float gain = 1.0f;        // Unused now

    int result = hdrplus_raw_pipeline(
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

    if (result != 0) {
        LOGE("Halide execution failed with code %d", result);
        return -1;
    }

    LOGD("Halide pipeline finished.");

    // 5. Post-Processing (Log + LUT)
    // Now we have 16-bit Linear RGB in outputBuf.
    // We need to convert this to the format expected by processLibRawOutput/ColorPipe.
    // ColorPipe expects a libraw_processed_image_t-like structure or just raw RGB data.
    // Let's reuse the logic: Apply Log -> Apply LUT -> Save.

    // Load LUT
    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    }

    // Process Output
    std::vector<unsigned short> finalImage(width * height * 3);

    // Halide Output is Planar (x, y, c) or (c, x, y)?
    // By default Halide uses planar x, y, c (stride[0]=1, stride[1]=width, stride[2]=width*height).
    // Let's verify strides if we could, but assuming standard Generator compilation.
    // We need to interleave it for TIFF writer (R,G,B, R,G,B...)

    const uint16_t* ptrR = outputBuf.data() + 0 * width * height; // Channel 0?
    // Wait, Halide dim order is x, y, c.
    // If outputBuf(x, y, c), then C is the 3rd dimension.
    // So distinct planes.
    // Let's assume Channel 0=R, 1=G, 2=B (based on srgb implementation).

    // Pointer arithmetic depends on strides.
    // outputBuf.dim(2).stride() usually is width*height.
    int stride_x = outputBuf.dim(0).stride();
    int stride_y = outputBuf.dim(1).stride();
    int stride_c = outputBuf.dim(2).stride();
    const uint16_t* raw_ptr = outputBuf.data();

    #pragma omp parallel for
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
             // Read Halide Planar
             uint16_t r_val = raw_ptr[x * stride_x + y * stride_y + 0 * stride_c];
             uint16_t g_val = raw_ptr[x * stride_x + y * stride_y + 1 * stride_c];
             uint16_t b_val = raw_ptr[x * stride_x + y * stride_y + 2 * stride_c];

             // Write Interleaved (16-bit)
             // DISABLED LOG/LUT: Outputting linear 16-bit RGB directly.
             int idx = (y * width + x) * 3;
             finalImage[idx + 0] = r_val;
             finalImage[idx + 1] = g_val;
             finalImage[idx + 2] = b_val;
        }
    }

    // Save
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    const char* dng_path_cstr = (outputDngPath) ? env->GetStringUTFChars(outputDngPath, 0) : nullptr;

    if (tiff_path_cstr) write_tiff(tiff_path_cstr, width, height, finalImage);
    if (jpg_path_cstr) write_bmp(jpg_path_cstr, width, height, finalImage);
    if (dng_path_cstr) write_dng(dng_path_cstr, width, height, finalImage, whiteLevel);

    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
    if (outputDngPath) env->ReleaseStringUTFChars(outputDngPath, dng_path_cstr);

    return 0;
}

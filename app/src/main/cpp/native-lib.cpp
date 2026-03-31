#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <iostream>
#include <memory>
#include <android/bitmap.h>
#include <libraw/libraw.h>
#include "ColorPipe.h"

#define TAG "ColorProcessorNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_processRaw(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray dngData,
        jint targetLog,
        jstring lutPath,
        jfloat exposure,
        jfloat contrast,
        jfloat saturation,
        jfloat highlights,
        jfloat shadows,
        jfloat whites,
        jfloat blacks,
        jfloat digitalGain,
        jstring outputJpgPath,
        jstring outputTiffPath,
        jboolean useGpu, // Ignored in new pipeline
        jint orientation,
        jboolean mirror,
        jobject outputBitmap,
        jint downsampleFactor,
        jfloat zoomFactor,
        jobject metadataObj
) {
    LOGD("Native processRaw started using LibRaw.");

    // Get DNG Bytes
    jsize len = env->GetArrayLength(dngData);
    if (len <= 0) return -1;

    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(dngData, 0, len, (jbyte*)buf);

    // LibRaw Processing
    LibRaw RawProcessor;

    // Open buffer
    if (RawProcessor.open_buffer(buf, len) != LIBRAW_SUCCESS) {
        LOGE("LibRaw open_buffer failed");
        delete[] buf;
        return -1;
    }

    // Unpack
    if (RawProcessor.unpack() != LIBRAW_SUCCESS) {
        LOGE("LibRaw unpack failed");
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Configure params
    RawProcessor.imgdata.params.output_bps = 16;
    RawProcessor.imgdata.params.gamm[0] = 1.0;
    RawProcessor.imgdata.params.gamm[1] = 1.0;
    RawProcessor.imgdata.params.no_auto_bright = 1;
    RawProcessor.imgdata.params.use_camera_wb = 1;
    RawProcessor.imgdata.params.output_color = 4; // ProPhotoRGB
    RawProcessor.imgdata.params.user_flip = 0;    // Disable internal rotation to avoid double-rotation with Kotlin

    // Process
    if (RawProcessor.dcraw_process() != LIBRAW_SUCCESS) {
        LOGE("LibRaw dcraw_process failed");
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Get Mem Image
    int ret = 0;
    libraw_processed_image_t* image = RawProcessor.dcraw_make_mem_image(&ret);
    if (!image) {
        LOGE("LibRaw make_mem_image failed: %d", ret);
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Check Format
    if (image->type != LIBRAW_IMAGE_BITMAP || image->colors != 3 || image->bits != 16) {
        LOGE("LibRaw output format mismatch: Type=%d, Colors=%d, Bits=%d", image->type, image->colors, image->bits);
        LibRaw::dcraw_clear_mem(image);
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Load LUT
    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    }

    // Copy LibRaw data to std::vector for shared processing
    std::vector<unsigned short> rawImage(image->width * image->height * 3);
    unsigned short* src = (unsigned short*)image->data;
    std::copy(src, src + (image->width * image->height * 3), rawImage.begin());

    // Paths
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;

    AndroidBitmapInfo info;
    int out_w = 0, out_h = 0;
    unsigned char* bitmapPixels = nullptr;
    if (outputBitmap) {
        AndroidBitmap_getInfo(env, outputBitmap, &info);
        out_w = info.width;
        out_h = info.height;
        if (AndroidBitmap_lockPixels(env, outputBitmap, (void**)&bitmapPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            bitmapPixels = nullptr;
        }
    }

    ImageMetadata meta;
    if (metadataObj) {
        meta = metadataFromJava(env, metadataObj);
    }

    // Use Shared Pipeline
    bool saveOk = process_and_save_image(
        rawImage,
        image->width,
        image->height,
        digitalGain,
        targetLog,
        lut,
        exposure, contrast, saturation, highlights, shadows, whites, blacks,
        jpg_path_cstr,
        tiff_path_cstr,
        metadataObj ? &meta : nullptr,
        0, // sourceColorSpace = ProPhoto (LibRaw output_color=4)
        nullptr, // ccm is not used for ProPhoto path
        nullptr, // wb is not used for ProPhoto path (LibRaw handles it)
        (int)orientation,
        bitmapPixels, // out_rgb_buffer
        out_w,
        out_h,
        outputBitmap != nullptr, // isPreview
        (int)downsampleFactor, // downsampleFactor
        (float)zoomFactor, // zoomFactor
        (bool)mirror
    );

    if (bitmapPixels) AndroidBitmap_unlockPixels(env, outputBitmap);

    // Release Strings
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);

    // Cleanup
    LibRaw::dcraw_clear_mem(image);
    RawProcessor.recycle();
    delete[] buf;

    return saveOk ? 0 : -1;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_loadLutData(
        JNIEnv* env,
        jobject /* this */,
        jstring lutPath) {

    const char* path = env->GetStringUTFChars(lutPath, 0);
    LUT3D lut = load_lut(path);
    env->ReleaseStringUTFChars(lutPath, path);

    if (lut.size == 0) return nullptr;

    std::vector<float> floatData;
    floatData.reserve(lut.data.size() * 3);
    for (const auto& vec : lut.data) {
        floatData.push_back(vec.r);
        floatData.push_back(vec.g);
        floatData.push_back(vec.b);
    }

    jfloatArray result = env->NewFloatArray(floatData.size());
    env->SetFloatArrayRegion(result, 0, floatData.size(), floatData.data());
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_saveBitmapToTiff(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jstring outputTiffPath,
        jobject metadataObj) {

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;

    void* pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return false;

    const char* path = env->GetStringUTFChars(outputTiffPath, 0);
    ImageMetadata meta = metadataFromJava(env, metadataObj);
    bool ok = write_tiff_rgba8(path, info.width, info.height, (unsigned char*)pixels, &meta);

    env->ReleaseStringUTFChars(outputTiffPath, path);
    AndroidBitmap_unlockPixels(env, bitmap);

    return ok;
}

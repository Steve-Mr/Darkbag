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
#include <libraw/libraw.h>
#include <android/bitmap.h>
#include "ColorPipe.h"

#define TAG "ColorProcessorNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct RawCache {
    std::vector<unsigned char> originalDng;
    std::vector<unsigned short> linearRgb;
    int width = 0;
    int height = 0;
};
static RawCache g_rawCache;

static bool ensure_raw_cache(JNIEnv* env, jbyteArray dngData) {
    jsize len = env->GetArrayLength(dngData);
    if (len <= 0) return false;

    // Quick check if already cached
    if (g_rawCache.originalDng.size() == (size_t)len) {
        bool match = true;
        unsigned char* buf = (unsigned char*)env->GetByteArrayElements(dngData, nullptr);
        if (memcmp(buf, g_rawCache.originalDng.data(), len) != 0) match = false;
        env->ReleaseByteArrayElements(dngData, (jbyte*)buf, JNI_ABORT);
        if (match) return true;
    }

    LOGD("Refreshing Raw Cache...");
    g_rawCache.originalDng.resize(len);
    env->GetByteArrayRegion(dngData, 0, len, (jbyte*)g_rawCache.originalDng.data());

    LibRaw RawProcessor;
    if (RawProcessor.open_buffer(g_rawCache.originalDng.data(), len) != LIBRAW_SUCCESS) return false;
    if (RawProcessor.unpack() != LIBRAW_SUCCESS) { RawProcessor.recycle(); return false; }

    RawProcessor.imgdata.params.output_bps = 16;
    RawProcessor.imgdata.params.gamm[0] = 1.0;
    RawProcessor.imgdata.params.gamm[1] = 1.0;
    RawProcessor.imgdata.params.no_auto_bright = 1;
    RawProcessor.imgdata.params.use_camera_wb = 1;
    RawProcessor.imgdata.params.output_color = 4; // ProPhotoRGB
    RawProcessor.imgdata.params.user_flip = 0;

    if (RawProcessor.dcraw_process() != LIBRAW_SUCCESS) { RawProcessor.recycle(); return false; }

    int ret = 0;
    libraw_processed_image_t* image = RawProcessor.dcraw_make_mem_image(&ret);
    if (!image) { RawProcessor.recycle(); return false; }

    g_rawCache.width = image->width;
    g_rawCache.height = image->height;
    g_rawCache.linearRgb.resize((size_t)image->width * image->height * 3);
    memcpy(g_rawCache.linearRgb.data(), image->data, (size_t)image->width * image->height * 3 * 2);

    LibRaw::dcraw_clear_mem(image);
    RawProcessor.recycle();
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_processRaw(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray dngData,
        jint targetLog,
        jstring lutPath,
        jstring outputTiffPath,
        jstring outputJpgPath,
        jboolean useGpu, // Ignored
        jint orientation,
        jboolean mirror,
        jfloat exposure,
        jfloat contrast,
        jfloat highlights,
        jfloat shadows,
        jfloat whites,
        jfloat blacks,
        jfloat saturation
) {
    if (!ensure_raw_cache(env, dngData)) return -1;

    BasicAdjustments adj;
    adj.exposure = exposure; adj.contrast = contrast; adj.highlights = highlights;
    adj.shadows = shadows; adj.whites = whites; adj.blacks = blacks; adj.saturation = saturation;

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut; if (lut_path_cstr) { lut = load_lut(lut_path_cstr); env->ReleaseStringUTFChars(lutPath, lut_path_cstr); }

    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;

    bool saveOk = process_and_save_image(
        g_rawCache.linearRgb, g_rawCache.width, g_rawCache.height,
        1.0f, targetLog, lut, tiff_path_cstr, jpg_path_cstr,
        0, nullptr, nullptr, (int)orientation, nullptr, false, 1, 1.0f, (bool)mirror, adj
    );

    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);

    return saveOk ? 0 : -1;
}

extern "C" JNIEXPORT jobject JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_processRawToBitmap(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray dngData,
        jint targetLog,
        jstring lutPath,
        jint orientation,
        jboolean mirror,
        jfloat exposure,
        jfloat contrast,
        jfloat highlights,
        jfloat shadows,
        jfloat whites,
        jfloat blacks,
        jfloat saturation,
        jint downsampleFactor
) {
    if (!ensure_raw_cache(env, dngData)) return nullptr;

    BasicAdjustments adj;
    adj.exposure = exposure; adj.contrast = contrast; adj.highlights = highlights;
    adj.shadows = shadows; adj.whites = whites; adj.blacks = blacks; adj.saturation = saturation;

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut; if (lut_path_cstr) { lut = load_lut(lut_path_cstr); env->ReleaseStringUTFChars(lutPath, lut_path_cstr); }

    int outW = g_rawCache.width / downsampleFactor;
    int outH = g_rawCache.height / downsampleFactor;
    if (orientation == 90 || orientation == 270) std::swap(outW, outH);

    // Create Android Bitmap
    jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888FieldID = env->GetStaticFieldID(bitmapConfigClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb8888Config = env->GetStaticObjectField(bitmapConfigClass, argb8888FieldID);

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethodID = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethodID, outW, outH, argb8888Config);

    void* pixels = nullptr;
    AndroidBitmap_lockPixels(env, bitmap, &pixels);

    process_and_save_image(
        g_rawCache.linearRgb, g_rawCache.width, g_rawCache.height,
        1.0f, targetLog, lut, nullptr, nullptr,
        0, nullptr, nullptr, (int)orientation, (unsigned char*)pixels, true, downsampleFactor, 1.0f, (bool)mirror, adj
    );

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
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

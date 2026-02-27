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

// Shared helper to decode DNG using LibRaw
static libraw_processed_image_t* decode_dng_to_raw(const unsigned char* buf, jsize len, LibRaw& RawProcessor, int quality = 3) {
    if (RawProcessor.open_buffer(buf, len) != LIBRAW_SUCCESS) {
        LOGE("LibRaw open_buffer failed");
        return nullptr;
    }
    if (RawProcessor.unpack() != LIBRAW_SUCCESS) {
        LOGE("LibRaw unpack failed");
        return nullptr;
    }

    RawProcessor.imgdata.params.output_bps = 16;
    RawProcessor.imgdata.params.gamm[0] = 1.0;
    RawProcessor.imgdata.params.gamm[1] = 1.0;
    RawProcessor.imgdata.params.no_auto_bright = 1;
    RawProcessor.imgdata.params.use_camera_wb = 1;
    RawProcessor.imgdata.params.output_color = 4; // ProPhotoRGB
    RawProcessor.imgdata.params.user_flip = 0;
    RawProcessor.imgdata.params.user_qual = quality;

    if (RawProcessor.dcraw_process() != LIBRAW_SUCCESS) {
        LOGE("LibRaw dcraw_process failed");
        return nullptr;
    }

    int ret = 0;
    libraw_processed_image_t* image = RawProcessor.dcraw_make_mem_image(&ret);
    if (!image) {
        LOGE("LibRaw make_mem_image failed: %d", ret);
        return nullptr;
    }

    if (image->type != LIBRAW_IMAGE_BITMAP || image->colors != 3 || image->bits != 16) {
        LOGE("LibRaw output format mismatch");
        LibRaw::dcraw_clear_mem(image);
        return nullptr;
    }

    return image;
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
        jboolean useGpu, // Ignored in new pipeline
        jint orientation,
        jboolean mirror,
        jfloat exposure,
        jfloat highlights,
        jfloat shadows,
        jfloat whites,
        jfloat blacks,
        jfloat contrast,
        jfloat saturation,
        jint quality
) {
    LOGD("Native processRaw started using LibRaw.");

    unsigned char* buf = nullptr;
    libraw_processed_image_t* image = nullptr;
    const char* lut_path_cstr = nullptr;
    const char* tiff_path_cstr = nullptr;
    const char* jpg_path_cstr = nullptr;
    LibRaw RawProcessor;
    jint result = -1;

    Adjustments adj;
    adj.exposure = exposure;
    adj.highlights = highlights;
    adj.shadows = shadows;
    adj.whites = whites;
    adj.blacks = blacks;
    adj.contrast = contrast;
    adj.saturation = saturation;

    jsize len = env->GetArrayLength(dngData);
    if (len <= 0) return -1;

    buf = new unsigned char[len];
    env->GetByteArrayRegion(dngData, 0, len, (jbyte*)buf);

    lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
    }

    image = decode_dng_to_raw(buf, len, RawProcessor, (int)quality);
    if (!image) goto cleanup;

    {
        std::vector<unsigned short> rawImage(image->width * image->height * 3);
        unsigned short* src = (unsigned short*)image->data;
        std::copy(src, src + (image->width * image->height * 3), rawImage.begin());

        tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
        jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;

        bool saveOk = process_and_save_image(
            rawImage, image->width, image->height, 1.0f, targetLog, lut,
            tiff_path_cstr, jpg_path_cstr, 0, nullptr, nullptr, (int)orientation,
            nullptr, false, 1, 1.0f, (bool)mirror, adj
        );
        result = saveOk ? 0 : -1;
    }

cleanup:
    if (lut_path_cstr) env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    if (tiff_path_cstr) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (jpg_path_cstr) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
    if (image) LibRaw::dcraw_clear_mem(image);
    RawProcessor.recycle();
    delete[] buf;

    return result;
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

extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_processRawToBitmap(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray dngData,
        jint targetLog,
        jstring lutPath,
        jobject bitmap,
        jint orientation,
        jboolean mirror,
        jfloat exposure,
        jfloat highlights,
        jfloat shadows,
        jfloat whites,
        jfloat blacks,
        jfloat contrast,
        jfloat saturation,
        jint quality
) {
    unsigned char* buf = nullptr;
    libraw_processed_image_t* image = nullptr;
    const char* lut_path_cstr = nullptr;
    LibRaw RawProcessor;
    jint result = -1;
    bool locked = false;

    Adjustments adj;
    adj.exposure = exposure;
    adj.highlights = highlights;
    adj.shadows = shadows;
    adj.whites = whites;
    adj.blacks = blacks;
    adj.contrast = contrast;
    adj.saturation = saturation;

    jsize len = env->GetArrayLength(dngData);
    if (len <= 0) return -1;
    buf = new unsigned char[len];
    env->GetByteArrayRegion(dngData, 0, len, (jbyte*)buf);

    lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
    }

    image = decode_dng_to_raw(buf, len, RawProcessor, (int)quality);
    if (!image) goto cleanup;

    {
        std::vector<unsigned short> rawImage(image->width * image->height * 3);
        unsigned short* src = (unsigned short*)image->data;
        std::copy(src, src + (image->width * image->height * 3), rawImage.begin());

        AndroidBitmapInfo info;
        void* pixels;
        if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) goto cleanup;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) goto cleanup;
        locked = true;

        bool ok = process_and_save_image(
            rawImage, image->width, image->height, 1.0f, targetLog, lut,
            nullptr, nullptr, 0, nullptr, nullptr, orientation,
            (unsigned char*)pixels, true, 1, 1.0f, mirror, adj,
            info.width, info.height, info.stride
        );
        result = ok ? 0 : -1;
    }

cleanup:
    if (locked) AndroidBitmap_unlockPixels(env, bitmap);
    if (lut_path_cstr) env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    if (image) LibRaw::dcraw_clear_mem(image);
    RawProcessor.recycle();
    delete[] buf;
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_extractDngThumbnail(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray dngData
) {
    jsize len = env->GetArrayLength(dngData);
    if (len <= 0) return nullptr;

    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(dngData, 0, len, (jbyte*)buf);

    LibRaw RawProcessor;
    jobject bitmap = nullptr;

    if (RawProcessor.open_buffer(buf, len) == LIBRAW_SUCCESS) {
        if (RawProcessor.unpack_thumb() == LIBRAW_SUCCESS) {
            int ret = 0;
            libraw_processed_image_t* thumb = RawProcessor.dcraw_make_mem_thumb(&ret);
            if (thumb) {
                if (thumb->type == LIBRAW_IMAGE_JPEG) {
                    // It's a JPEG thumbnail, we need to decode it to a Bitmap.
                    // Use Android's BitmapFactory.
                    jclass factoryClass = env->FindClass("android/graphics/BitmapFactory");
                    jmethodID decodeMethod = env->GetStaticMethodID(factoryClass, "decodeByteArray", "([BII)Landroid/graphics/Bitmap;");
                    jbyteArray thumbArray = env->NewByteArray(thumb->data_size);
                    env->SetByteArrayRegion(thumbArray, 0, thumb->data_size, (jbyte*)thumb->data);
                    bitmap = env->CallStaticObjectMethod(factoryClass, decodeMethod, thumbArray, 0, thumb->data_size);
                } else if (thumb->type == LIBRAW_IMAGE_BITMAP) {
                    // It's a raw bitmap thumbnail.
                    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
                    jmethodID createMethod = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
                    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
                    jfieldID configField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
                    jobject config = env->GetStaticObjectField(configClass, configField);

                    bitmap = env->CallStaticObjectMethod(bitmapClass, createMethod, (jint)thumb->width, (jint)thumb->height, config);

                    void* pixels;
                    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0) {
                        // Assuming thumb is 8-bit RGB or 16-bit RGB. LibRaw usually provides 8-bit for thumb.
                        unsigned char* src = thumb->data;
                        unsigned char* dst = (unsigned char*)pixels;
                        for (int i = 0; i < thumb->width * thumb->height; ++i) {
                            dst[i*4 + 0] = src[i*3 + 0]; // R
                            dst[i*4 + 1] = src[i*3 + 1]; // G
                            dst[i*4 + 2] = src[i*3 + 2]; // B
                            dst[i*4 + 3] = 255;          // A
                        }
                        AndroidBitmap_unlockPixels(env, bitmap);
                    }
                }
                LibRaw::dcraw_clear_mem(thumb);
            }
        }
    }

    RawProcessor.recycle();
    delete[] buf;
    return bitmap;
}

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

namespace {

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

jclass g_integerClass = nullptr;
jclass g_longClass = nullptr;
jclass g_floatClass = nullptr;
jclass g_metadataClass = nullptr;

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

void ensureIDsInitialized(JNIEnv* env) {
    static bool initialized = false;
    if (initialized) return;

    jclass integerClass = env->FindClass("java/lang/Integer");
    g_boxedMethods.intValue = env->GetMethodID(integerClass, "intValue", "()I");

    jclass longClass = env->FindClass("java/lang/Long");
    g_boxedMethods.longValue = env->GetMethodID(longClass, "longValue", "()J");

    jclass floatClass = env->FindClass("java/lang/Float");
    g_boxedMethods.floatValue = env->GetMethodID(floatClass, "floatValue", "()F");

    jclass metadataClazz = env->FindClass("top/maary/darkbag/models/CaptureMetadata");
    g_metadataFields.iso = env->GetFieldID(metadataClazz, "iso", "Ljava/lang/Integer;");
    g_metadataFields.exposureTime = env->GetFieldID(metadataClazz, "exposureTime", "Ljava/lang/Long;");
    g_metadataFields.fNumber = env->GetFieldID(metadataClazz, "fNumber", "Ljava/lang/Float;");
    g_metadataFields.focalLength = env->GetFieldID(metadataClazz, "focalLength", "Ljava/lang/Float;");
    g_metadataFields.focalLengthIn35mmFilm = env->GetFieldID(metadataClazz, "focalLengthIn35mmFilm", "Ljava/lang/Integer;");
    g_metadataFields.dateTimeOriginal = env->GetFieldID(metadataClazz, "dateTimeOriginal", "Ljava/lang/Long;");
    g_metadataFields.dateTimeDigitized = env->GetFieldID(metadataClazz, "dateTimeDigitized", "Ljava/lang/Long;");
    g_metadataFields.offsetTime = env->GetFieldID(metadataClazz, "offsetTime", "Ljava/lang/String;");
    g_metadataFields.offsetTimeOriginal = env->GetFieldID(metadataClazz, "offsetTimeOriginal", "Ljava/lang/String;");
    g_metadataFields.offsetTimeDigitized = env->GetFieldID(metadataClazz, "offsetTimeDigitized", "Ljava/lang/String;");
    g_metadataFields.make = env->GetFieldID(metadataClazz, "make", "Ljava/lang/String;");
    g_metadataFields.model = env->GetFieldID(metadataClazz, "model", "Ljava/lang/String;");
    g_metadataFields.uniqueCameraModel = env->GetFieldID(metadataClazz, "uniqueCameraModel", "Ljava/lang/String;");
    g_metadataFields.lensModel = env->GetFieldID(metadataClazz, "lensModel", "Ljava/lang/String;");
    g_metadataFields.software = env->GetFieldID(metadataClazz, "software", "Ljava/lang/String;");
    g_metadataFields.imageDescription = env->GetFieldID(metadataClazz, "imageDescription", "Ljava/lang/String;");

    initialized = true;
}

ImageMetadata metadataFromJava(JNIEnv* env, jobject metadataObj) {
    ImageMetadata meta;
    if (!metadataObj) return meta;

    ensureIDsInitialized(env);

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

} // namespace


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
        jobject metadataObj,
        jboolean enableMemoryColor
) {
    LOGD("Native processRaw started using LibRaw (enableMemoryColor=%d).", enableMemoryColor);

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
    } else if (lutPath) {
        LOGE("GetStringUTFChars failed for lutPath");
        LibRaw::dcraw_clear_mem(image);
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Copy LibRaw data to std::vector for shared processing
    std::vector<unsigned short> rawImage(image->width * image->height * 3);
    unsigned short* src = (unsigned short*)image->data;
    std::copy(src, src + (image->width * image->height * 3), rawImage.begin());

    // Paths
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    if (outputJpgPath && !jpg_path_cstr) { LOGE("GetStringUTFChars failed for outputJpgPath"); }
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    if (outputTiffPath && !tiff_path_cstr) { LOGE("GetStringUTFChars failed for outputTiffPath"); }

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
        rawImage.data(), 3, image->width * 3, 1, nullptr, 0, 0,
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
        (bool)mirror,
        (bool)enableMemoryColor
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
    bool ok = false;
    if (path) {
        ImageMetadata meta = metadataFromJava(env, metadataObj);
        ok = write_tiff_rgba8(path, info.width, info.height, (unsigned char*)pixels, &meta);
        env->ReleaseStringUTFChars(outputTiffPath, path);
    } else {
        LOGE("GetStringUTFChars failed for outputTiffPath in saveBitmapToTiff");
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    return ok;
}

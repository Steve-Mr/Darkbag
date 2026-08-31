#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include <cmath>
#include <cstring>
#include <algorithm>

#include <omp.h>
#include "RawVideoContainer.h"
#include "RawVideoRecorder.h"
#include "../ColorPipe.h"

#define TAG "RawVideoJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace darkbag::rawvideo;

extern "C" {

JNIEXPORT jlong JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeStartRecording(
        JNIEnv* env,
        jobject /* thiz */,
        jstring jOutputPath,
        jint width,
        jint height,
        jint bitDepth,
        jint cfaPattern,
        jfloat fps,
        jint compressionType,
        jint audioSampleRate,
        jint audioChannels,
        jint audioBitDepth,
        jint whiteLevel,
        jfloatArray jBlackLevel,
        jfloatArray jColorMatrix1,
        jfloatArray jColorMatrix2,
        jfloatArray jNeutralPoint,
        jstring jLutName,
        jstring jLogName
) {
    const char* outputPathStr = env->GetStringUTFChars(jOutputPath, nullptr);
    if (!outputPathStr) return 0;

    FileHeader header{};
    header.width = static_cast<uint32_t>(width);
    header.height = static_cast<uint32_t>(height);
    header.bitDepth = static_cast<uint32_t>(bitDepth);
    header.cfaPattern = static_cast<uint32_t>(cfaPattern);
    header.fps = fps;
    header.compressionType = static_cast<uint32_t>(compressionType);
    header.audioSampleRate = static_cast<uint32_t>(audioSampleRate);
    header.audioChannels = static_cast<uint32_t>(audioChannels);
    header.audioBitDepth = static_cast<uint32_t>(audioBitDepth);
    header.whiteLevel = static_cast<uint32_t>(whiteLevel);

    if (jBlackLevel) {
        jfloat* bl = env->GetFloatArrayElements(jBlackLevel, nullptr);
        for (int i = 0; i < 4 && i < env->GetArrayLength(jBlackLevel); ++i) {
            header.blackLevel[i] = bl[i];
        }
        env->ReleaseFloatArrayElements(jBlackLevel, bl, JNI_ABORT);
    }

    if (jColorMatrix1) {
        jfloat* cm1 = env->GetFloatArrayElements(jColorMatrix1, nullptr);
        for (int i = 0; i < 9 && i < env->GetArrayLength(jColorMatrix1); ++i) {
            header.colorMatrix1[i] = cm1[i];
        }
        env->ReleaseFloatArrayElements(jColorMatrix1, cm1, JNI_ABORT);
    }

    if (jColorMatrix2) {
        jfloat* cm2 = env->GetFloatArrayElements(jColorMatrix2, nullptr);
        for (int i = 0; i < 9 && i < env->GetArrayLength(jColorMatrix2); ++i) {
            header.colorMatrix2[i] = cm2[i];
        }
        env->ReleaseFloatArrayElements(jColorMatrix2, cm2, JNI_ABORT);
    }

    if (jNeutralPoint) {
        jfloat* np = env->GetFloatArrayElements(jNeutralPoint, nullptr);
        for (int i = 0; i < 3 && i < env->GetArrayLength(jNeutralPoint); ++i) {
            header.neutralColorPoint[i] = np[i];
        }
        env->ReleaseFloatArrayElements(jNeutralPoint, np, JNI_ABORT);
    }

    if (jLutName) {
        const char* lutStr = env->GetStringUTFChars(jLutName, nullptr);
        strncpy(header.activeLutName, lutStr, sizeof(header.activeLutName) - 1);
        env->ReleaseStringUTFChars(jLutName, lutStr);
    }

    if (jLogName) {
        const char* logStr = env->GetStringUTFChars(jLogName, nullptr);
        strncpy(header.activeLogName, logStr, sizeof(header.activeLogName) - 1);
        env->ReleaseStringUTFChars(jLogName, logStr);
    }

    auto* recorder = new RawVideoRecorder();
    if (!recorder->startRecording(outputPathStr, header)) {
        delete recorder;
        env->ReleaseStringUTFChars(jOutputPath, outputPathStr);
        return 0;
    }

    env->ReleaseStringUTFChars(jOutputPath, outputPathStr);
    return reinterpret_cast<jlong>(recorder);
}

JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativePushVideoFrame(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jobject jBayerBuffer,
        jint dataSize,
        jint width,
        jint height,
        jint rowStride,
        jlong timestampNs,
        jlong exposureTimeNs,
        jint iso,
        jfloatArray jNeutralColorPoint
) {
    auto* recorder = reinterpret_cast<RawVideoRecorder*>(handle);
    if (!recorder) return JNI_FALSE;

    auto* bufferPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(jBayerBuffer));
    if (!bufferPtr) return JNI_FALSE;

    float neutralPoint[3] = {1.0f, 1.0f, 1.0f};
    if (jNeutralColorPoint) {
        jfloat* np = env->GetFloatArrayElements(jNeutralColorPoint, nullptr);
        for (int i = 0; i < 3 && i < env->GetArrayLength(jNeutralColorPoint); ++i) {
            neutralPoint[i] = np[i];
        }
        env->ReleaseFloatArrayElements(jNeutralColorPoint, np, JNI_ABORT);
    }

    bool success = recorder->pushVideoFrame(
            bufferPtr,
            static_cast<size_t>(dataSize),
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            static_cast<uint32_t>(rowStride),
            static_cast<uint64_t>(timestampNs),
            static_cast<uint64_t>(exposureTimeNs),
            static_cast<uint32_t>(iso),
            neutralPoint
    );

    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativePushAudioPacket(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jobject jPcmBuffer,
        jint pcmSize,
        jlong timestampNs,
        jint sampleCount
) {
    auto* recorder = reinterpret_cast<RawVideoRecorder*>(handle);
    if (!recorder) return JNI_FALSE;

    auto* bufferPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(jPcmBuffer));
    if (!bufferPtr) return JNI_FALSE;

    bool success = recorder->pushAudioPacket(
            bufferPtr,
            static_cast<size_t>(pcmSize),
            static_cast<uint64_t>(timestampNs),
            static_cast<uint32_t>(sampleCount)
    );

    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeStopRecording(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle
) {
    auto* recorder = reinterpret_cast<RawVideoRecorder*>(handle);
    if (!recorder) return JNI_TRUE;

    bool success = recorder->stopRecording();
    delete recorder;
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeOpenReader(
        JNIEnv* env,
        jobject /* thiz */,
        jstring jFilePath
) {
    const char* filePathStr = env->GetStringUTFChars(jFilePath, nullptr);
    if (!filePathStr) return 0;

    auto* reader = new RawVideoReader();
    if (!reader->open(filePathStr)) {
        delete reader;
        env->ReleaseStringUTFChars(jFilePath, filePathStr);
        return 0;
    }

    env->ReleaseStringUTFChars(jFilePath, filePathStr);
    return reinterpret_cast<jlong>(reader);
}

JNIEXPORT jlong JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeOpenReaderFd(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint fd
) {
    if (fd < 0) return 0;
    auto* reader = new RawVideoReader();
    if (!reader->openFd(fd)) {
        delete reader;
        return 0;
    }
    return reinterpret_cast<jlong>(reader);
}

JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeGetHeader(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jintArray jIntParams,    // [width, height, bitDepth, cfaPattern, compressionType, audioSampleRate, audioChannels, whiteLevel, frameCount]
        jfloatArray jFloatParams, // [fps, blackLevel(4), neutralPoint(3)]
        jobjectArray jStringParams // [activeLutName, activeLogName]
) {
    auto* reader = reinterpret_cast<RawVideoReader*>(handle);
    if (!reader) return JNI_FALSE;

    FileHeader header{};
    if (!reader->readHeader(header)) return JNI_FALSE;

    if (jIntParams && env->GetArrayLength(jIntParams) >= 9) {
        jint intData[9] = {
                static_cast<jint>(header.width),
                static_cast<jint>(header.height),
                static_cast<jint>(header.bitDepth),
                static_cast<jint>(header.cfaPattern),
                static_cast<jint>(header.compressionType),
                static_cast<jint>(header.audioSampleRate),
                static_cast<jint>(header.audioChannels),
                static_cast<jint>(header.whiteLevel),
                static_cast<jint>(header.frameCount)
        };
        env->SetIntArrayRegion(jIntParams, 0, 9, intData);
    }

    if (jFloatParams && env->GetArrayLength(jFloatParams) >= 8) {
        jfloat floatData[8] = {
                header.fps,
                header.blackLevel[0], header.blackLevel[1], header.blackLevel[2], header.blackLevel[3],
                header.neutralColorPoint[0], header.neutralColorPoint[1], header.neutralColorPoint[2]
        };
        env->SetFloatArrayRegion(jFloatParams, 0, 8, floatData);
    }

    if (jStringParams && env->GetArrayLength(jStringParams) >= 2) {
        jstring lutStr = env->NewStringUTF(header.activeLutName);
        jstring logStr = env->NewStringUTF(header.activeLogName);
        env->SetObjectArrayElement(jStringParams, 0, lutStr);
        env->SetObjectArrayElement(jStringParams, 1, logStr);
    }

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeGetFrameCount(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle
) {
    auto* reader = reinterpret_cast<RawVideoReader*>(handle);
    if (!reader) return 0;
    return static_cast<jint>(reader->getFrameIndex().size());
}

JNIEXPORT jint JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeReadFrame(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jint frameIndex,
        jlongArray jMetadata, // [timestampNs, exposureTimeNs, iso]
        jobject jOutBuffer
) {
    auto* reader = reinterpret_cast<RawVideoReader*>(handle);
    if (!reader) return -1;

    auto* outBufferPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(jOutBuffer));
    if (!outBufferPtr) return -1;
    jlong bufferCapacity = env->GetDirectBufferCapacity(jOutBuffer);

    VideoFrameHeader vh{};
    std::vector<uint8_t> payload;
    if (!reader->readVideoFrame(static_cast<uint32_t>(frameIndex), vh, payload)) {
        return -1;
    }

    if (jMetadata && env->GetArrayLength(jMetadata) >= 3) {
        jlong meta[3] = {
                static_cast<jlong>(vh.timestampNs),
                static_cast<jlong>(vh.exposureTimeNs),
                static_cast<jlong>(vh.iso)
        };
        env->SetLongArrayRegion(jMetadata, 0, 3, meta);
    }

    const auto& header = reader->getHeader();
    if (header.compressionType == static_cast<uint32_t>(CompressionType::NEON_DPCM_LZ4)) {
        if (vh.uncompressedSize > static_cast<uint32_t>(bufferCapacity)) {
            LOGE("Buffer capacity (%lld) too small for uncompressed frame (%u)", (long long)bufferCapacity, vh.uncompressedSize);
            return -1;
        }

        size_t decompressed = RawVideoRecorder::decompressFrame(
                payload.data(), payload.size(), outBufferPtr, static_cast<size_t>(bufferCapacity), true);
        return static_cast<jint>(decompressed);
    } else {
        if (payload.size() > static_cast<size_t>(bufferCapacity)) {
            return -1;
        }
        std::memcpy(outBufferPtr, payload.data(), payload.size());
        return static_cast<jint>(payload.size());
    }
}

JNIEXPORT jint JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeReadAudioPacket(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jint packetIndex,
        jobject jOutByteBuffer
) {
    auto* reader = reinterpret_cast<RawVideoReader*>(handle);
    if (!reader || packetIndex < 0) return -1;

    void* outBufferPtr = env->GetDirectBufferAddress(jOutByteBuffer);
    jlong bufferCapacity = env->GetDirectBufferCapacity(jOutByteBuffer);
    if (!outBufferPtr || bufferCapacity <= 0) return -1;

    AudioPacketHeader ah{};
    std::vector<uint8_t> pcm;
    if (!reader->readAudioPacket(static_cast<uint32_t>(packetIndex), ah, pcm)) {
        return -1;
    }

    if (pcm.size() > static_cast<size_t>(bufferCapacity)) {
        return -1;
    }

    std::memcpy(outBufferPtr, pcm.data(), pcm.size());
    return static_cast<jint>(pcm.size());
}

JNIEXPORT void JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeCloseReader(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle
) {
    auto* reader = reinterpret_cast<RawVideoReader*>(handle);
    if (reader) {
        reader->close();
        delete reader;
    }
}

static const std::array<uint8_t, 1024> kGammaLut = [] {
    std::array<uint8_t, 1024> table{};
    for (size_t i = 0; i < 1024; ++i) {
        float val = static_cast<float>(i) / 1023.0f;
        float gammaVal = std::pow(val, 1.0f / 2.2f) * 255.0f;
        table[i] = static_cast<uint8_t>(std::clamp(gammaVal, 0.0f, 255.0f));
    }
    return table;
}();

// Fast Demosaicing of 16-bit RAW_SENSOR / Linear Bayer to RGBA_8888 Android Bitmap with White Balance, Tone Curves, and 3D LUT
JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_rawvideo_RawVideoNative_nativeDebayerFrameToBitmap(
        JNIEnv* env,
        jobject /* thiz */,
        jobject jBayerBuffer,
        jint width,
        jint height,
        jint cfaPattern,
        jint whiteLevel,
        jfloat blackLevel,
        jfloatArray jNeutralPoint,
        jint targetLog,
        jstring jLutPath,
        jfloat exposure,
        jfloat contrast,
        jfloat saturation,
        jobject jOutBitmap
) {
    auto* rawBytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jBayerBuffer));
    if (!rawBytes || !jOutBitmap || width <= 0 || height <= 0) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, jOutBitmap, &info) < 0) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jOutBitmap, &pixels) < 0) return JNI_FALSE;

    auto* outPixels = static_cast<uint32_t*>(pixels);
    const int outWidth = static_cast<int>(info.width);
    const int outHeight = static_cast<int>(info.height);

    // 1. Calculate White Balance Gains from Neutral Point
    float wbR = 2.0f;
    float wbG = 1.0f;
    float wbB = 1.6f;

    if (jNeutralPoint) {
        jsize npLen = env->GetArrayLength(jNeutralPoint);
        if (npLen >= 3) {
            jfloat npVals[3];
            env->GetFloatArrayRegion(jNeutralPoint, 0, 3, npVals);
            if (npVals[0] > 0.001f && npVals[1] > 0.001f && npVals[2] > 0.001f) {
                // If neutralPoint contains valid inverse-gain coordinates
                if (std::abs(npVals[0] - 1.0f) > 0.001f || std::abs(npVals[2] - 1.0f) > 0.001f) {
                    wbR = 1.0f / npVals[0];
                    wbG = 1.0f / npVals[1];
                    wbB = 1.0f / npVals[2];
                    wbR /= wbG;
                    wbB /= wbG;
                    wbG = 1.0f;
                }
            }
        }
    }

    // 2. Load 3D Film Simulation LUT if specified
    LUT3D lut;
    lut.size = 0;
    if (jLutPath) {
        const char* lutPathC = env->GetStringUTFChars(jLutPath, nullptr);
        if (lutPathC && lutPathC[0] != '\0') {
            lut = load_lut(lutPathC);
        }
        if (lutPathC) {
            env->ReleaseStringUTFChars(jLutPath, lutPathC);
        }
    }

    float exposureMult = std::pow(2.0f, exposure);
    float norm = (1.0f / std::max(1.0f, static_cast<float>(whiteLevel) - blackLevel)) * exposureMult;
    float contrastFactor = 1.0f + contrast;
    float satFactor = 1.0f + saturation;

    const size_t rowStride = static_cast<size_t>(width) * 2; // 16-bit RAW_SENSOR (2 bytes per pixel)

    // Fast 2x2 Bayer downsampled debayering directly to target bitmap size
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < outHeight; ++y) {
        int srcY = (y * height) / outHeight;
        srcY = std::min(height - 2, srcY & ~1); // Even row

        const auto* row0 = reinterpret_cast<const uint16_t*>(rawBytes + srcY * rowStride);
        const auto* row1 = reinterpret_cast<const uint16_t*>(rawBytes + (srcY + 1) * rowStride);

        for (int x = 0; x < outWidth; ++x) {
            int srcX = (x * width) / outWidth;
            srcX = std::min(width - 2, srcX & ~1); // Even col

            uint16_t p00 = row0[srcX];
            uint16_t p01 = row0[srcX + 1];
            uint16_t p10 = row1[srcX];
            uint16_t p11 = row1[srcX + 1];

            float r = 0.0f, g = 0.0f, b = 0.0f;
            if (cfaPattern == static_cast<int>(CfaPattern::RGGB)) {
                r = static_cast<float>(p00);
                g = static_cast<float>(p01 + p10) * 0.5f;
                b = static_cast<float>(p11);
            } else if (cfaPattern == static_cast<int>(CfaPattern::BGGR)) {
                b = static_cast<float>(p00);
                g = static_cast<float>(p01 + p10) * 0.5f;
                r = static_cast<float>(p11);
            } else if (cfaPattern == static_cast<int>(CfaPattern::GRBG)) {
                g = static_cast<float>(p00);
                r = static_cast<float>(p01);
                b = static_cast<float>(p10);
            } else { // GBRG
                g = static_cast<float>(p00);
                b = static_cast<float>(p01);
                r = static_cast<float>(p10);
            }

            // 1. Subtract Black Level, Normalize and Apply White Balance Gains
            r = std::max(0.0f, (r - blackLevel) * norm * wbR);
            g = std::max(0.0f, (g - blackLevel) * norm * wbG);
            b = std::max(0.0f, (b - blackLevel) * norm * wbB);

            // 2. Contrast in linear domain (pivot at 0.18 mid-gray)
            if (contrast != 0.0f) {
                r = std::max(0.0f, (r - 0.18f) * contrastFactor + 0.18f);
                g = std::max(0.0f, (g - 0.18f) * contrastFactor + 0.18f);
                b = std::max(0.0f, (b - 0.18f) * contrastFactor + 0.18f);
            }

            // 3. Log Tone Curve or standard sRGB Gamma
            if (targetLog >= 0) {
                r = apply_log(r, targetLog);
                g = apply_log(g, targetLog);
                b = apply_log(b, targetLog);
            } else {
                r = srgb_oetf(r);
                g = srgb_oetf(g);
                b = srgb_oetf(b);
            }

            // 4. 3D Film Simulation LUT
            if (lut.size > 0) {
                Vec3 graded = apply_lut(lut, {r, g, b});
                r = graded.r;
                g = graded.g;
                b = graded.b;
            }

            // 5. Saturation
            if (saturation != 0.0f) {
                float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = luma + (r - luma) * satFactor;
                g = luma + (g - luma) * satFactor;
                b = luma + (b - luma) * satFactor;
            }

            uint32_t ru = static_cast<uint32_t>(std::clamp(r * 255.0f, 0.0f, 255.0f));
            uint32_t gu = static_cast<uint32_t>(std::clamp(g * 255.0f, 0.0f, 255.0f));
            uint32_t bu = static_cast<uint32_t>(std::clamp(b * 255.0f, 0.0f, 255.0f));
            uint32_t au = 0xFF;

            outPixels[y * outWidth + x] = (au << 24) | (bu << 16) | (gu << 8) | ru;
        }
    }

    AndroidBitmap_unlockPixels(env, jOutBitmap);
    return JNI_TRUE;
}

} // extern "C"

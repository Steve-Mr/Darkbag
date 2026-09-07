#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <vector>
#include <algorithm>
#include <cmath>
#include <cstring>

#include "BurstFrameSelector.h"

#define TAG "ImagingJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

/**
 * JNI function: nativeEvaluateBurstAndCompact
 * Evaluates burst sharpness, calculates Gaussian-weighted anchor frame, filters blurry frames,
 * and performs in-place zero-copy compact rearrangement in megaBuffer.
 *
 * Returned jintArray layout:
 *   [0]: anchorIndex (original frame index)
 *   [1]: finalNumFrames (number of accepted frames compactly stored at slots 0 until finalNumFrames)
 *   [2 .. 1 + finalNumFrames]: accepted original frame indices in new slot order
 */
JNIEXPORT jintArray JNICALL
Java_top_maary_darkbag_imaging_ImagingNative_nativeEvaluateBurstAndCompact(
    JNIEnv* env,
    jobject /* thiz */,
    jobject jMegaBuffer,
    jint numFrames,
    jint width,
    jint height,
    jint rowStride,
    jint cfaPattern,
    jint iso,
    jint triggerIndex,
    jfloat rejectionThreshold
) {
    if (!jMegaBuffer || numFrames <= 0 || width <= 0 || height <= 0 || rowStride <= 0) {
        LOGE("nativeEvaluateBurstAndCompact: Invalid parameters (frames=%d, w=%d, h=%d, stride=%d)",
             numFrames, width, height, rowStride);
        return nullptr;
    }

    auto* bufferPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(jMegaBuffer));
    if (!bufferPtr) {
        LOGE("nativeEvaluateBurstAndCompact: Failed to get direct buffer address");
        return nullptr;
    }

    // Strict capacity validation
    const size_t frameSizeBytes = static_cast<size_t>(height) * static_cast<size_t>(rowStride);
    const size_t requiredCapacity = static_cast<size_t>(numFrames) * frameSizeBytes;
    const jlong capacity = env->GetDirectBufferCapacity(jMegaBuffer);
    if (capacity < static_cast<jlong>(requiredCapacity)) {
        LOGE("nativeEvaluateBurstAndCompact: Direct buffer capacity %lld is smaller than required %zu",
             static_cast<long long>(capacity), requiredCapacity);
        return nullptr;
    }

    // 1. Evaluate sharpness & select anchor frame
    auto result = darkbag::imaging::BurstFrameSelector::evaluateBurst(
        bufferPtr,
        numFrames,
        frameSizeBytes,
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
        static_cast<uint32_t>(rowStride),
        cfaPattern,
        static_cast<uint32_t>(iso),
        triggerIndex,
        rejectionThreshold
    );

    // 2. Perform in-place compact rearrangement
    int finalCount = darkbag::imaging::BurstFrameSelector::compactAcceptedFramesInPlace(
        bufferPtr,
        numFrames,
        frameSizeBytes,
        result.acceptedIndices,
        result.anchorIndex
    );

    LOGI("nativeEvaluateBurstAndCompact: anchor=%d, accepted=%d/%d compactly rearranged in-place",
         result.anchorIndex, finalCount, numFrames);

    // 3. Construct return array: [anchorIndex, finalCount, acceptedIndices...]
    const jsize outSize = 2 + static_cast<jsize>(result.acceptedIndices.size());
    jintArray jResult = env->NewIntArray(outSize);
    if (!jResult) return nullptr;

    std::vector<jint> rawOut(outSize);
    rawOut[0] = result.anchorIndex;
    rawOut[1] = finalCount;
    for (size_t i = 0; i < result.acceptedIndices.size(); ++i) {
        rawOut[2 + i] = result.acceptedIndices[i];
    }

    env->SetIntArrayRegion(jResult, 0, outSize, rawOut.data());
    return jResult;
}

/**
 * JNI function: nativeGenerateStridedThumbnail
 * Ultra-fast shutter feedback fallback thumbnail generator.
 * Directly samples 256x256 grid from 16-bit Bayer buffer with 2x2 Bayer Quad single-point sampling,
 * black level subtraction, white balance gains, CCM restoration, highlight desaturation, and sRGB OETF.
 * Writes directly to outBitmap with zero off-heap and zero heap allocations.
 * Execution time < 4ms.
 */
JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_imaging_ImagingNative_nativeGenerateStridedThumbnail(
    JNIEnv* env,
    jobject /* thiz */,
    jobject jBayerBuffer,
    jint width,
    jint height,
    jint rowStride,
    jint cfaPattern,
    jint whiteLevel,
    jfloat blackLevel,
    jfloatArray jWhiteBalance,
    jfloatArray jCcm,
    jobject jOutBitmap,
    jint orientation
) {
    if (!jBayerBuffer || !jOutBitmap || width < 4 || height < 4) {
        LOGE("nativeGenerateStridedThumbnail: Invalid inputs");
        return JNI_FALSE;
    }

    auto* bayerBytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(jBayerBuffer));
    if (!bayerBytes) {
        LOGE("nativeGenerateStridedThumbnail: Failed to get Bayer buffer address");
        return JNI_FALSE;
    }

    if (rowStride <= 0) {
        rowStride = width * 2; // Default 16-bit RAW_SENSOR
    }

    const jlong capacity = env->GetDirectBufferCapacity(jBayerBuffer);
    const size_t minRequired = static_cast<size_t>(height) * static_cast<size_t>(rowStride);
    if (capacity > 0 && capacity < static_cast<jlong>(minRequired)) {
        LOGE("nativeGenerateStridedThumbnail: Buffer capacity %lld smaller than 1 frame (%zu)",
             static_cast<long long>(capacity), minRequired);
        return JNI_FALSE;
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, jOutBitmap, &info) < 0) {
        LOGE("nativeGenerateStridedThumbnail: AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("nativeGenerateStridedThumbnail: Unsupported bitmap format %d (expected RGBA_8888)", info.format);
        return JNI_FALSE;
    }

    const int targetW = info.width > 0 ? static_cast<int>(info.width) : 256;
    const int targetH = info.height > 0 ? static_cast<int>(info.height) : 256;

    // Calculate strides based on target size (256x256) ensuring even alignment to Bayer 2x2 quad
    const int stepX = std::max(2, (width / targetW) & ~1);
    const int stepY = std::max(2, (height / targetH) & ~1);

    // Parse White Balance gains
    float wbR = 1.0f;
    float wbG = 1.0f;
    float wbB = 1.0f;
    if (jWhiteBalance) {
        jsize wbLen = env->GetArrayLength(jWhiteBalance);
        if (wbLen >= 4) {
            jfloat wbVals[4];
            env->GetFloatArrayRegion(jWhiteBalance, 0, 4, wbVals);
            float gAvg = 0.5f * (wbVals[1] + wbVals[2]);
            if (gAvg > 1e-4f) {
                wbR = wbVals[0] / gAvg;
                wbB = wbVals[3] / gAvg;
                wbG = 1.0f;
            }
        } else if (wbLen >= 3) {
            jfloat wbVals[3];
            env->GetFloatArrayRegion(jWhiteBalance, 0, 3, wbVals);
            if (wbVals[1] > 1e-4f) {
                wbR = wbVals[0] / wbVals[1];
                wbB = wbVals[2] / wbVals[1];
                wbG = 1.0f;
            }
        }
    }

    // Parse 3x3 Color Correction Matrix
    float ccm[9] = {1.0f, 0.0f, 0.0f,  0.0f, 1.0f, 0.0f,  0.0f, 0.0f, 1.0f};
    bool hasCcm = false;
    if (jCcm && env->GetArrayLength(jCcm) >= 9) {
        env->GetFloatArrayRegion(jCcm, 0, 9, ccm);
        hasCcm = true;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jOutBitmap, &pixels) < 0 || !pixels) {
        LOGE("nativeGenerateStridedThumbnail: AndroidBitmap_lockPixels failed");
        return JNI_FALSE;
    }

    auto* outPixels = static_cast<uint32_t*>(pixels);
    const int stridePixels = static_cast<int>(info.stride / sizeof(uint32_t));
    const float denom = std::max(1.0f, static_cast<float>(whiteLevel) - blackLevel);

    // Loop exactly targetH x targetW (typically 256x256) times
    #pragma omp parallel for schedule(static)
    for (int outY = 0; outY < targetH; ++outY) {
        for (int outX = 0; outX < targetW; ++outX) {
            int sx = outX;
            int sy = outY;
            if (orientation == 90) {
                sx = outY;
                sy = (targetW - 1) - outX;
            } else if (orientation == 180) {
                sx = (targetW - 1) - outX;
                sy = (targetH - 1) - outY;
            } else if (orientation == 270) {
                sx = (targetH - 1) - outY;
                sy = outX;
            }

            int srcX = std::min(width - 2, std::max(0, (sx * stepX) & ~1));
            int srcY = std::min(height - 2, std::max(0, (sy * stepY) & ~1));

            const auto* row0 = reinterpret_cast<const uint16_t*>(bayerBytes + static_cast<size_t>(srcY) * rowStride);
            const auto* row1 = reinterpret_cast<const uint16_t*>(bayerBytes + static_cast<size_t>(srcY + 1) * rowStride);

            uint16_t p00 = row0[srcX];
            uint16_t p01 = row0[srcX + 1];
            uint16_t p10 = row1[srcX];
            uint16_t p11 = row1[srcX + 1];

            float r = 0.0f, g = 0.0f, b = 0.0f;
            switch (cfaPattern) {
                case 1: // GRBG: (0,0)=Gr, (0,1)=R, (1,0)=B, (1,1)=Gb
                    g = static_cast<float>(p00 + p11) * 0.5f;
                    r = static_cast<float>(p01);
                    b = static_cast<float>(p10);
                    break;
                case 2: // GBRG: (0,0)=Gb, (0,1)=B, (1,0)=R, (1,1)=Gr
                    g = static_cast<float>(p00 + p11) * 0.5f;
                    b = static_cast<float>(p01);
                    r = static_cast<float>(p10);
                    break;
                case 3: // BGGR: (0,0)=B, (0,1)=Gb, (1,0)=Gr, (1,1)=R
                    b = static_cast<float>(p00);
                    g = static_cast<float>(p01 + p10) * 0.5f;
                    r = static_cast<float>(p11);
                    break;
                case 0: // RGGB: (0,0)=R, (0,1)=Gr, (1,0)=Gb, (1,1)=B
                default:
                    r = static_cast<float>(p00);
                    g = static_cast<float>(p01 + p10) * 0.5f;
                    b = static_cast<float>(p11);
                    break;
            }

            // 1. Black level subtraction & normalization
            float rawR = std::clamp((r - blackLevel) / denom, 0.0f, 1.0f);
            float rawG = std::clamp((g - blackLevel) / denom, 0.0f, 1.0f);
            float rawB = std::clamp((b - blackLevel) / denom, 0.0f, 1.0f);

            // 2. White balance gains
            r = rawR * wbR;
            g = rawG * wbG;
            b = rawB * wbB;

            // 3. Color Correction Matrix (Sensor -> sRGB)
            if (hasCcm) {
                float cr = ccm[0] * r + ccm[1] * g + ccm[2] * b;
                float cg = ccm[3] * r + ccm[4] * g + ccm[5] * b;
                float cb = ccm[6] * r + ccm[7] * g + ccm[8] * b;
                r = std::max(0.0f, cr);
                g = std::max(0.0f, cg);
                b = std::max(0.0f, cb);
            }

            // Highlight desaturation protection against highlight tinting
            float maxRaw = std::max({rawR, rawG, rawB});
            if (maxRaw > 0.92f) {
                float t = std::clamp((maxRaw - 0.92f) / 0.08f, 0.0f, 1.0f);
                float blendFactor = t * t * (3.0f - 2.0f * t);
                float peakLuma = std::max({r, g, b});
                r = r * (1.0f - blendFactor) + peakLuma * blendFactor;
                g = g * (1.0f - blendFactor) + peakLuma * blendFactor;
                b = b * (1.0f - blendFactor) + peakLuma * blendFactor;
            }

            // 4. Fast sRGB Tone Curve (Gamma OETF)
            auto srgb_gamma = [](float x) -> float {
                if (x <= 0.0031308f) return 12.92f * x;
                return 1.055f * std::pow(x, 1.0f / 2.4f) - 0.055f;
            };

            r = srgb_gamma(r);
            g = srgb_gamma(g);
            b = srgb_gamma(b);

            uint32_t ru = static_cast<uint32_t>(std::clamp(r * 255.0f, 0.0f, 255.0f));
            uint32_t gu = static_cast<uint32_t>(std::clamp(g * 255.0f, 0.0f, 255.0f));
            uint32_t bu = static_cast<uint32_t>(std::clamp(b * 255.0f, 0.0f, 255.0f));
            uint32_t au = 0xFF;

            // Little-endian RGBA_8888 packing
            outPixels[outY * stridePixels + outX] = (au << 24) | (bu << 16) | (gu << 8) | ru;
        }
    }

    AndroidBitmap_unlockPixels(env, jOutBitmap);
    return JNI_TRUE;
}

} // extern "C"

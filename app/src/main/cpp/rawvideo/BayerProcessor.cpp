#include "BayerProcessor.h"
#include <cstring>
#include <algorithm>
#include <vector>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#if defined(_OPENMP)
#include <omp.h>
#endif

namespace darkbag {
namespace rawvideo {

void BayerProcessor::bayer2x2BinningNEON(
    const uint8_t* srcBytes,
    uint32_t srcWidth,
    uint32_t srcHeight,
    uint32_t srcRowStrideBytes,
    uint16_t* dst,
    BinningMode mode
) {
    if (!srcBytes || !dst || srcWidth < 2 || srcHeight < 2) {
        return;
    }

    const uint32_t dstWidth = srcWidth / 2;
    const uint32_t dstHeight = srcHeight / 2;
    const uint32_t numBlocksY = srcHeight / 4;

    #if defined(_OPENMP)
    #pragma omp parallel for schedule(static)
    #endif
    for (uint32_t y_b = 0; y_b < numBlocksY; ++y_b) {
        const auto* r0 = reinterpret_cast<const uint16_t*>(srcBytes + (4 * y_b + 0) * srcRowStrideBytes);
        const auto* r1 = reinterpret_cast<const uint16_t*>(srcBytes + (4 * y_b + 1) * srcRowStrideBytes);
        const auto* r2 = reinterpret_cast<const uint16_t*>(srcBytes + (4 * y_b + 2) * srcRowStrideBytes);
        const auto* r3 = reinterpret_cast<const uint16_t*>(srcBytes + (4 * y_b + 3) * srcRowStrideBytes);

        uint16_t* outRow0 = dst + (2 * y_b + 0) * dstWidth;
        uint16_t* outRow1 = dst + (2 * y_b + 1) * dstWidth;

        uint32_t x = 0;
        uint32_t dstX = 0;

        #if defined(__ARM_NEON) || defined(__ARM_NEON__)
        for (; x + 16 <= srcWidth && dstX + 8 <= dstWidth; x += 16, dstX += 8) {
            // Row 0
            uint16x8_t r0_0 = vld1q_u16(r0 + x);
            uint16x8_t r0_1 = vld1q_u16(r0 + x + 8);
            uint32x4x2_t r0_uzp = vuzpq_u32(vreinterpretq_u32_u16(r0_0), vreinterpretq_u32_u16(r0_1));
            uint16x8_t r0_even = vreinterpretq_u16_u32(r0_uzp.val[0]);
            uint16x8_t r0_odd  = vreinterpretq_u16_u32(r0_uzp.val[1]);

            // Row 2
            uint16x8_t r2_0 = vld1q_u16(r2 + x);
            uint16x8_t r2_1 = vld1q_u16(r2 + x + 8);
            uint32x4x2_t r2_uzp = vuzpq_u32(vreinterpretq_u32_u16(r2_0), vreinterpretq_u32_u16(r2_1));
            uint16x8_t r2_even = vreinterpretq_u16_u32(r2_uzp.val[0]);
            uint16x8_t r2_odd  = vreinterpretq_u16_u32(r2_uzp.val[1]);

            // Row 1
            uint16x8_t r1_0 = vld1q_u16(r1 + x);
            uint16x8_t r1_1 = vld1q_u16(r1 + x + 8);
            uint32x4x2_t r1_uzp = vuzpq_u32(vreinterpretq_u32_u16(r1_0), vreinterpretq_u32_u16(r1_1));
            uint16x8_t r1_even = vreinterpretq_u16_u32(r1_uzp.val[0]);
            uint16x8_t r1_odd  = vreinterpretq_u16_u32(r1_uzp.val[1]);

            // Row 3
            uint16x8_t r3_0 = vld1q_u16(r3 + x);
            uint16x8_t r3_1 = vld1q_u16(r3 + x + 8);
            uint32x4x2_t r3_uzp = vuzpq_u32(vreinterpretq_u32_u16(r3_0), vreinterpretq_u32_u16(r3_1));
            uint16x8_t r3_even = vreinterpretq_u16_u32(r3_uzp.val[0]);
            uint16x8_t r3_odd  = vreinterpretq_u16_u32(r3_uzp.val[1]);

            if (mode == BinningMode::SUMMATION) {
                uint16x8_t r0_h_sum = vqaddq_u16(r0_even, r0_odd);
                uint16x8_t r2_h_sum = vqaddq_u16(r2_even, r2_odd);
                uint16x8_t out0 = vqaddq_u16(r0_h_sum, r2_h_sum);
                vst1q_u16(outRow0 + dstX, out0);

                uint16x8_t r1_h_sum = vqaddq_u16(r1_even, r1_odd);
                uint16x8_t r3_h_sum = vqaddq_u16(r3_even, r3_odd);
                uint16x8_t out1 = vqaddq_u16(r1_h_sum, r3_h_sum);
                vst1q_u16(outRow1 + dstX, out1);
            } else {
                uint16x8_t r0_h_avg = vrhaddq_u16(r0_even, r0_odd);
                uint16x8_t r2_h_avg = vrhaddq_u16(r2_even, r2_odd);
                uint16x8_t out0 = vrhaddq_u16(r0_h_avg, r2_h_avg);
                vst1q_u16(outRow0 + dstX, out0);

                uint16x8_t r1_h_avg = vrhaddq_u16(r1_even, r1_odd);
                uint16x8_t r3_h_avg = vrhaddq_u16(r3_even, r3_odd);
                uint16x8_t out1 = vrhaddq_u16(r1_h_avg, r3_h_avg);
                vst1q_u16(outRow1 + dstX, out1);
            }
        }
        #endif

        // Scalar loop for remaining 4-pixel chunks
        for (; x + 4 <= srcWidth && dstX + 2 <= dstWidth; x += 4, dstX += 2) {
            if (mode == BinningMode::SUMMATION) {
                uint32_t c0 = static_cast<uint32_t>(r0[x])     + r0[x + 2] + r2[x]     + r2[x + 2];
                uint32_t c1 = static_cast<uint32_t>(r0[x + 1]) + r0[x + 3] + r2[x + 1] + r2[x + 3];
                outRow0[dstX]     = static_cast<uint16_t>(std::min(c0, 65535u));
                outRow0[dstX + 1] = static_cast<uint16_t>(std::min(c1, 65535u));

                uint32_t c2 = static_cast<uint32_t>(r1[x])     + r1[x + 2] + r3[x]     + r3[x + 2];
                uint32_t c3 = static_cast<uint32_t>(r1[x + 1]) + r1[x + 3] + r3[x + 1] + r3[x + 3];
                outRow1[dstX]     = static_cast<uint16_t>(std::min(c2, 65535u));
                outRow1[dstX + 1] = static_cast<uint16_t>(std::min(c3, 65535u));
            } else {
                uint32_t c0 = (static_cast<uint32_t>(r0[x])     + r0[x + 2] + r2[x]     + r2[x + 2] + 2) / 4;
                uint32_t c1 = (static_cast<uint32_t>(r0[x + 1]) + r0[x + 3] + r2[x + 1] + r2[x + 3] + 2) / 4;
                outRow0[dstX]     = static_cast<uint16_t>(c0);
                outRow0[dstX + 1] = static_cast<uint16_t>(c1);

                uint32_t c2 = (static_cast<uint32_t>(r1[x])     + r1[x + 2] + r3[x]     + r3[x + 2] + 2) / 4;
                uint32_t c3 = (static_cast<uint32_t>(r1[x + 1]) + r1[x + 3] + r3[x + 1] + r3[x + 3] + 2) / 4;
                outRow1[dstX]     = static_cast<uint16_t>(c2);
                outRow1[dstX + 1] = static_cast<uint16_t>(c3);
            }
        }

        // Remainder if boundary contains an extra 2 pixels
        if (x + 2 <= srcWidth && dstX < dstWidth) {
            if (mode == BinningMode::SUMMATION) {
                outRow0[dstX] = static_cast<uint16_t>(std::min<uint32_t>(65535u, static_cast<uint32_t>(r0[x]) + r2[x]));
                if (x + 1 < srcWidth && dstX + 1 < dstWidth) {
                    outRow0[dstX + 1] = static_cast<uint16_t>(std::min<uint32_t>(65535u, static_cast<uint32_t>(r0[x + 1]) + r2[x + 1]));
                }
                outRow1[dstX] = static_cast<uint16_t>(std::min<uint32_t>(65535u, static_cast<uint32_t>(r1[x]) + r3[x]));
                if (x + 1 < srcWidth && dstX + 1 < dstWidth) {
                    outRow1[dstX + 1] = static_cast<uint16_t>(std::min<uint32_t>(65535u, static_cast<uint32_t>(r1[x + 1]) + r3[x + 1]));
                }
            } else {
                outRow0[dstX] = static_cast<uint16_t>((static_cast<uint32_t>(r0[x]) + r2[x] + 1) / 2);
                if (x + 1 < srcWidth && dstX + 1 < dstWidth) {
                    outRow0[dstX + 1] = static_cast<uint16_t>((static_cast<uint32_t>(r0[x + 1]) + r2[x + 1] + 1) / 2);
                }
                outRow1[dstX] = static_cast<uint16_t>((static_cast<uint32_t>(r1[x]) + r3[x] + 1) / 2);
                if (x + 1 < srcWidth && dstX + 1 < dstWidth) {
                    outRow1[dstX + 1] = static_cast<uint16_t>((static_cast<uint32_t>(r1[x + 1]) + r3[x + 1] + 1) / 2);
                }
            }
        }
    }

    // Handle vertical remainder if srcHeight is not a multiple of 4 (e.g., 2 remaining rows)
    uint32_t remY = numBlocksY * 4;
    if (remY + 2 <= srcHeight && 2 * numBlocksY < dstHeight) {
        const auto* remR0 = reinterpret_cast<const uint16_t*>(srcBytes + remY * srcRowStrideBytes);
        const auto* remR1 = reinterpret_cast<const uint16_t*>(srcBytes + (remY + 1) * srcRowStrideBytes);
        uint16_t* remOutRow0 = dst + (2 * numBlocksY) * dstWidth;
        uint16_t* remOutRow1 = (2 * numBlocksY + 1 < dstHeight) ? dst + (2 * numBlocksY + 1) * dstWidth : nullptr;

        uint32_t rx = 0;
        uint32_t rDstX = 0;
        for (; rx + 4 <= srcWidth && rDstX + 2 <= dstWidth; rx += 4, rDstX += 2) {
            if (mode == BinningMode::SUMMATION) {
                remOutRow0[rDstX]     = static_cast<uint16_t>(std::min<uint32_t>(65535u, static_cast<uint32_t>(remR0[rx])     + remR0[rx + 2]));
                remOutRow0[rDstX + 1] = static_cast<uint16_t>(std::min<uint32_t>(65535u, static_cast<uint32_t>(remR0[rx + 1]) + remR0[rx + 3]));
                if (remOutRow1) {
                    remOutRow1[rDstX]     = static_cast<uint16_t>(std::min<uint32_t>(65535u, static_cast<uint32_t>(remR1[rx])     + remR1[rx + 2]));
                    remOutRow1[rDstX + 1] = static_cast<uint16_t>(std::min<uint32_t>(65535u, static_cast<uint32_t>(remR1[rx + 1]) + remR1[rx + 3]));
                }
            } else {
                remOutRow0[rDstX]     = static_cast<uint16_t>((static_cast<uint32_t>(remR0[rx])     + remR0[rx + 2] + 1) / 2);
                remOutRow0[rDstX + 1] = static_cast<uint16_t>((static_cast<uint32_t>(remR0[rx + 1]) + remR0[rx + 3] + 1) / 2);
                if (remOutRow1) {
                    remOutRow1[rDstX]     = static_cast<uint16_t>((static_cast<uint32_t>(remR1[rx])     + remR1[rx + 2] + 1) / 2);
                    remOutRow1[rDstX + 1] = static_cast<uint16_t>((static_cast<uint32_t>(remR1[rx + 1]) + remR1[rx + 3] + 1) / 2);
                }
            }
        }
    }
}

void BayerProcessor::bayer4x4BinningNEON(
    const uint8_t* srcBytes,
    uint32_t srcWidth,
    uint32_t srcHeight,
    uint32_t srcRowStrideBytes,
    uint16_t* dst,
    BinningMode mode
) {
    if (!srcBytes || !dst || srcWidth < 4 || srcHeight < 4) {
        return;
    }

    const uint32_t midWidth = srcWidth / 2;
    const uint32_t midHeight = srcHeight / 2;
    std::vector<uint16_t> tempBuffer(static_cast<size_t>(midWidth) * midHeight);

    // Pass 1: 2x2 binning from input into tempBuffer
    bayer2x2BinningNEON(srcBytes, srcWidth, srcHeight, srcRowStrideBytes, tempBuffer.data(), mode);

    // Pass 2: 2x2 binning from tempBuffer into dst
    bayer2x2BinningNEON(
        reinterpret_cast<const uint8_t*>(tempBuffer.data()),
        midWidth,
        midHeight,
        midWidth * sizeof(uint16_t),
        dst,
        mode
    );
}

void BayerProcessor::cropBayer16(
    const uint8_t* srcBytes,
    uint32_t srcWidth,
    uint32_t srcHeight,
    uint32_t srcRowStrideBytes,
    uint16_t* dst,
    uint32_t dstWidth,
    uint32_t dstHeight
) {
    if (!srcBytes || !dst || srcWidth == 0 || srcHeight == 0 || dstWidth == 0 || dstHeight == 0) {
        return;
    }

    uint32_t effectiveDstW = std::min(dstWidth, srcWidth) & ~1u;
    uint32_t effectiveDstH = std::min(dstHeight, srcHeight) & ~1u;

    uint32_t startX = ((srcWidth - effectiveDstW) / 2) & ~1u;
    uint32_t startY = ((srcHeight - effectiveDstH) / 2) & ~1u;

    const size_t rowBytesToCopy = effectiveDstW * sizeof(uint16_t);

    #if defined(_OPENMP)
    #pragma omp parallel for schedule(static)
    #endif
    for (uint32_t y = 0; y < effectiveDstH; ++y) {
        const uint8_t* srcRow = srcBytes + (startY + y) * srcRowStrideBytes + startX * sizeof(uint16_t);
        uint16_t* dstRow = dst + y * effectiveDstW;
        std::memcpy(dstRow, srcRow, rowBytesToCopy);
    }
}

BayerProcessResult BayerProcessor::processBayerFrame(
    const uint8_t* srcBytes,
    uint32_t srcWidth,
    uint32_t srcHeight,
    uint32_t srcRowStrideBytes,
    DownsampleMode mode,
    uint8_t* dstBytes
) {
    BayerProcessResult result{};
    if (!srcBytes || !dstBytes || srcWidth == 0 || srcHeight == 0) {
        return result;
    }

    auto* dst16 = reinterpret_cast<uint16_t*>(dstBytes);

    switch (mode) {
        case DownsampleMode::BINNING_2K_OPEN_GATE_4_3: {
            const uint32_t targetW = (srcWidth / 2) & ~1u;
            const uint32_t targetH = (srcHeight / 2) & ~1u;
            bayer2x2BinningNEON(srcBytes, targetW * 2, targetH * 2, srcRowStrideBytes, dst16);
            result.outWidth = targetW;
            result.outHeight = targetH;
            result.outDataSize = static_cast<size_t>(targetW) * targetH * sizeof(uint16_t);
            return result;
        }
        case DownsampleMode::BINNING_1080P: {
            // Target: 1920x1080
            // Check if sensor is large enough for 2x2 binning into at least 1920x1080
            if (srcWidth >= 3840 && srcHeight >= 2160) {
                // Zero-allocation path: bin directly from centered window
                uint32_t binnedW = srcWidth / 2;
                uint32_t binnedH = srcHeight / 2;
                uint32_t cropStartX_b = ((binnedW - 1920) / 2) & ~1u;
                uint32_t cropStartY_b = ((binnedH - 1080) / 2) & ~1u;
                uint32_t startX_in = cropStartX_b * 2;
                uint32_t startY_in = cropStartY_b * 2;

                const uint8_t* subSrc = srcBytes + startY_in * srcRowStrideBytes + startX_in * sizeof(uint16_t);
                bayer2x2BinningNEON(subSrc, 1920 * 2, 1080 * 2, srcRowStrideBytes, dst16);

                result.outWidth = 1920;
                result.outHeight = 1080;
                result.outDataSize = 1920 * 1080 * sizeof(uint16_t);
            } else if (srcWidth >= 1920 && srcHeight >= 1080) {
                // Sensor is too small for 2x2 binning to 1080p, but >= 1080p native: crop directly
                cropBayer16(srcBytes, srcWidth, srcHeight, srcRowStrideBytes, dst16, 1920, 1080);
                result.outWidth = 1920;
                result.outHeight = 1080;
                result.outDataSize = 1920 * 1080 * sizeof(uint16_t);
            } else {
                uint32_t outW = srcWidth & ~1u;
                uint32_t outH = srcHeight & ~1u;
                cropBayer16(srcBytes, srcWidth, srcHeight, srcRowStrideBytes, dst16, outW, outH);
                result.outWidth = outW;
                result.outHeight = outH;
                result.outDataSize = outW * outH * sizeof(uint16_t);
            }
            break;
        }
        case DownsampleMode::BINNING_4X4: {
            const uint32_t targetW = (srcWidth / 4) & ~1u;
            const uint32_t targetH = (srcHeight / 4) & ~1u;
            bayer4x4BinningNEON(srcBytes, targetW * 4, targetH * 4, srcRowStrideBytes, dst16, BinningMode::AVERAGE);
            result.outWidth = targetW;
            result.outHeight = targetH;
            result.outDataSize = static_cast<size_t>(targetW) * targetH * sizeof(uint16_t);
            return result;
        }
        case DownsampleMode::CROP_4K: {
            // Target: 3840x2160
            uint32_t targetW = (srcWidth >= 3840) ? 3840 : (srcWidth & ~1u);
            uint32_t targetH = (srcHeight >= 2160) ? 2160 : (srcHeight & ~1u);
            cropBayer16(srcBytes, srcWidth, srcHeight, srcRowStrideBytes, dst16, targetW, targetH);
            result.outWidth = targetW;
            result.outHeight = targetH;
            result.outDataSize = targetW * targetH * sizeof(uint16_t);
            break;
        }
        case DownsampleMode::NONE:
        default: {
            // Straight copy respecting stride to produce packed buffer
            result.outWidth = srcWidth;
            result.outHeight = srcHeight;
            result.outDataSize = srcWidth * srcHeight * sizeof(uint16_t);

            if (srcRowStrideBytes == srcWidth * sizeof(uint16_t)) {
                std::memcpy(dstBytes, srcBytes, result.outDataSize);
            } else {
                const size_t rowBytes = srcWidth * sizeof(uint16_t);
                #if defined(_OPENMP)
                #pragma omp parallel for schedule(static)
                #endif
                for (uint32_t y = 0; y < srcHeight; ++y) {
                    std::memcpy(dstBytes + y * rowBytes,
                                srcBytes + y * srcRowStrideBytes,
                                rowBytes);
                }
            }
            break;
        }
    }

    return result;
}

void BayerProcessor::demosaicHamiltonAdams(
    const uint16_t* bayerData,
    uint32_t width,
    uint32_t height,
    int cfaPattern,
    const int* blackLevelPattern,
    int whiteLevel,
    uint16_t* outPlanarRgb
) {
    if (!bayerData || !outPlanarRgb || width < 4 || height < 4) {
        return;
    }

    const int safePattern = std::clamp(cfaPattern, 0, 3);
    const float safeWhiteLevel = (whiteLevel > 0) ? static_cast<float>(whiteLevel) : 1023.0f;

    // cfaColor[pattern][row][col]: 0=Red, 1=Green, 2=Blue
    static const int cfaColor[4][2][2] = {
        { {0, 1}, {1, 2} }, // 0: RGGB
        { {1, 0}, {2, 1} }, // 1: GRBG
        { {1, 2}, {0, 1} }, // 2: GBRG
        { {2, 1}, {1, 0} }  // 3: BGGR
    };

    const uint32_t padW = width + 4;
    const uint32_t padH = height + 4;
    std::vector<float> padData(padW * padH);

    // 1. Black level subtract, normalize to [0, 65535.0f], and fill padded buffer with mirror reflection
    #if defined(_OPENMP)
    #pragma omp parallel for schedule(static)
    #endif
    for (uint32_t y = 0; y < height; ++y) {
        const uint32_t py = y + 2;
        const auto* srcRow = bayerData + (size_t)y * width;
        auto* padRow = padData.data() + (size_t)py * padW;

        for (uint32_t x = 0; x < width; ++x) {
            int phase = static_cast<int>((y & 1) * 2 + (x & 1));
            float bl = blackLevelPattern ? static_cast<float>(blackLevelPattern[phase]) : 64.0f;
            float scale = 65535.0f / std::max(1.0f, safeWhiteLevel - bl);
            float norm = std::clamp((static_cast<float>(srcRow[x]) - bl) * scale, 0.0f, 65535.0f);
            padRow[x + 2] = norm;
        }

        // Horizontal mirror padding (2 pixels on each side)
        padRow[1] = padRow[3];
        padRow[0] = padRow[4];
        padRow[padW - 2] = padRow[padW - 4];
        padRow[padW - 1] = padRow[padW - 5];
    }

    // Vertical mirror padding (2 rows top and bottom)
    std::memcpy(padData.data() + 1 * padW, padData.data() + 3 * padW, padW * sizeof(float));
    std::memcpy(padData.data() + 0 * padW, padData.data() + 4 * padW, padW * sizeof(float));
    std::memcpy(padData.data() + (padH - 2) * padW, padData.data() + (padH - 4) * padW, padW * sizeof(float));
    std::memcpy(padData.data() + (padH - 1) * padW, padData.data() + (padH - 5) * padW, padW * sizeof(float));

    uint16_t* planeR = outPlanarRgb + 0 * (size_t)width * height;
    uint16_t* planeG = outPlanarRgb + 1 * (size_t)width * height;
    uint16_t* planeB = outPlanarRgb + 2 * (size_t)width * height;

    // Buffers for color differences (R - G and B - G)
    std::vector<float> padDR(padW * padH, 0.0f);
    std::vector<float> padDB(padW * padH, 0.0f);

    // 2. Populate known pixels and interpolate Green at Red/Blue locations
    #if defined(_OPENMP)
    #pragma omp parallel for schedule(static)
    #endif
    for (uint32_t y = 0; y < height; ++y) {
        const uint32_t py = y + 2;
        const auto* padRow = padData.data() + (size_t)py * padW;
        const auto* padRowM1 = padData.data() + (size_t)(py - 1) * padW;
        const auto* padRowP1 = padData.data() + (size_t)(py + 1) * padW;
        const auto* padRowM2 = padData.data() + (size_t)(py - 2) * padW;
        const auto* padRowP2 = padData.data() + (size_t)(py + 2) * padW;

        auto* rowG = planeG + (size_t)y * width;
        auto* rowR = planeR + (size_t)y * width;
        auto* rowB = planeB + (size_t)y * width;

        auto* rowDR = padDR.data() + (size_t)py * padW;
        auto* rowDB = padDB.data() + (size_t)py * padW;

        for (uint32_t x = 0; x < width; ++x) {
            const uint32_t px = x + 2;
            int ch = cfaColor[safePattern][y & 1][x & 1];
            float P = padRow[px];

            if (ch == 1) {
                // Known Green
                rowG[x] = static_cast<uint16_t>(P);
            } else if (ch == 0) {
                // Known Red, interpolate Green
                rowR[x] = static_cast<uint16_t>(P);
                float dH = std::abs(padRow[px - 1] - padRow[px + 1]) + std::abs(2.0f * P - padRow[px - 2] - padRow[px + 2]);
                float dV = std::abs(padRowM1[px] - padRowP1[px]) + std::abs(2.0f * P - padRowM2[px] - padRowP2[px]);

                float g_val;
                if (dH < dV) {
                    g_val = 0.5f * (padRow[px - 1] + padRow[px + 1]) + 0.25f * (2.0f * P - padRow[px - 2] - padRow[px + 2]);
                } else if (dV < dH) {
                    g_val = 0.5f * (padRowM1[px] + padRowP1[px]) + 0.25f * (2.0f * P - padRowM2[px] - padRowP2[px]);
                } else {
                    g_val = 0.25f * (padRow[px - 1] + padRow[px + 1] + padRowM1[px] + padRowP1[px]) +
                            0.125f * (4.0f * P - padRow[px - 2] - padRow[px + 2] - padRowM2[px] - padRowP2[px]);
                }
                float clampedG = std::clamp(g_val, 0.0f, 65535.0f);
                rowG[x] = static_cast<uint16_t>(clampedG);
                rowDR[px] = P - clampedG;
            } else {
                // Known Blue, interpolate Green
                rowB[x] = static_cast<uint16_t>(P);
                float dH = std::abs(padRow[px - 1] - padRow[px + 1]) + std::abs(2.0f * P - padRow[px - 2] - padRow[px + 2]);
                float dV = std::abs(padRowM1[px] - padRowP1[px]) + std::abs(2.0f * P - padRowM2[px] - padRowP2[px]);

                float g_val;
                if (dH < dV) {
                    g_val = 0.5f * (padRow[px - 1] + padRow[px + 1]) + 0.25f * (2.0f * P - padRow[px - 2] - padRow[px + 2]);
                } else if (dV < dH) {
                    g_val = 0.5f * (padRowM1[px] + padRowP1[px]) + 0.25f * (2.0f * P - padRowM2[px] - padRowP2[px]);
                } else {
                    g_val = 0.25f * (padRow[px - 1] + padRow[px + 1] + padRowM1[px] + padRowP1[px]) +
                            0.125f * (4.0f * P - padRow[px - 2] - padRow[px + 2] - padRowM2[px] - padRowP2[px]);
                }
                float clampedG = std::clamp(g_val, 0.0f, 65535.0f);
                rowG[x] = static_cast<uint16_t>(clampedG);
                rowDB[px] = P - clampedG;
            }
        }

        // Horizontal mirror padding for padDR and padDB
        rowDR[1] = rowDR[3];
        rowDR[0] = rowDR[4];
        rowDR[padW - 2] = rowDR[padW - 4];
        rowDR[padW - 1] = rowDR[padW - 5];

        rowDB[1] = rowDB[3];
        rowDB[0] = rowDB[4];
        rowDB[padW - 2] = rowDB[padW - 4];
        rowDB[padW - 1] = rowDB[padW - 5];
    }

    // Vertical mirror padding for padDR and padDB
    std::memcpy(padDR.data() + 1 * padW, padDR.data() + 3 * padW, padW * sizeof(float));
    std::memcpy(padDR.data() + 0 * padW, padDR.data() + 4 * padW, padW * sizeof(float));
    std::memcpy(padDR.data() + (padH - 2) * padW, padDR.data() + (padH - 4) * padW, padW * sizeof(float));
    std::memcpy(padDR.data() + (padH - 1) * padW, padDR.data() + (padH - 5) * padW, padW * sizeof(float));

    std::memcpy(padDB.data() + 1 * padW, padDB.data() + 3 * padW, padW * sizeof(float));
    std::memcpy(padDB.data() + 0 * padW, padDB.data() + 4 * padW, padW * sizeof(float));
    std::memcpy(padDB.data() + (padH - 2) * padW, padDB.data() + (padH - 4) * padW, padW * sizeof(float));
    std::memcpy(padDB.data() + (padH - 1) * padW, padDB.data() + (padH - 5) * padW, padW * sizeof(float));

    // 3. Interpolate Red and Blue in color-difference domain
    #if defined(_OPENMP)
    #pragma omp parallel for schedule(static)
    #endif
    for (uint32_t y = 0; y < height; ++y) {
        const uint32_t py = y + 2;
        const auto* drRow = padDR.data() + (size_t)py * padW;
        const auto* drRowM1 = padDR.data() + (size_t)(py - 1) * padW;
        const auto* drRowP1 = padDR.data() + (size_t)(py + 1) * padW;

        const auto* dbRow = padDB.data() + (size_t)py * padW;
        const auto* dbRowM1 = padDB.data() + (size_t)(py - 1) * padW;
        const auto* dbRowP1 = padDB.data() + (size_t)(py + 1) * padW;

        const auto* rowG = planeG + (size_t)y * width;
        auto* rowR = planeR + (size_t)y * width;
        auto* rowB = planeB + (size_t)y * width;

        for (uint32_t x = 0; x < width; ++x) {
            const uint32_t px = x + 2;
            int ch = cfaColor[safePattern][y & 1][x & 1];
            float G_val = static_cast<float>(rowG[x]);

            // Interpolate Red if not known
            if (ch != 0) {
                float dr;
                if (ch == 1) {
                    // Green pixel: Red is either horizontally or vertically adjacent
                    if (cfaColor[safePattern][y & 1][(x + 1) & 1] == 0) {
                        dr = 0.5f * (drRow[px - 1] + drRow[px + 1]);
                    } else {
                        dr = 0.5f * (drRowM1[px] + drRowP1[px]);
                    }
                } else {
                    // Blue pixel: Red is at 4 diagonal neighbors
                    float dD1 = std::abs(drRowM1[px + 1] - drRowP1[px - 1]);
                    float dD2 = std::abs(drRowM1[px - 1] - drRowP1[px + 1]);
                    if (dD1 < dD2) {
                        dr = 0.5f * (drRowM1[px + 1] + drRowP1[px - 1]);
                    } else if (dD2 < dD1) {
                        dr = 0.5f * (drRowM1[px - 1] + drRowP1[px + 1]);
                    } else {
                        dr = 0.25f * (drRowM1[px - 1] + drRowM1[px + 1] + drRowP1[px - 1] + drRowP1[px + 1]);
                    }
                }
                rowR[x] = static_cast<uint16_t>(std::clamp(G_val + dr, 0.0f, 65535.0f));
            }

            // Interpolate Blue if not known
            if (ch != 2) {
                float db;
                if (ch == 1) {
                    // Green pixel: Blue is either horizontally or vertically adjacent
                    if (cfaColor[safePattern][y & 1][(x + 1) & 1] == 2) {
                        db = 0.5f * (dbRow[px - 1] + dbRow[px + 1]);
                    } else {
                        db = 0.5f * (dbRowM1[px] + dbRowP1[px]);
                    }
                } else {
                    // Red pixel: Blue is at 4 diagonal neighbors
                    float dD1 = std::abs(dbRowM1[px + 1] - dbRowP1[px - 1]);
                    float dD2 = std::abs(dbRowM1[px - 1] - dbRowP1[px + 1]);
                    if (dD1 < dD2) {
                        db = 0.5f * (dbRowM1[px + 1] + dbRowP1[px - 1]);
                    } else if (dD2 < dD1) {
                        db = 0.5f * (dbRowM1[px - 1] + dbRowP1[px + 1]);
                    } else {
                        db = 0.25f * (dbRowM1[px - 1] + dbRowM1[px + 1] + dbRowP1[px - 1] + dbRowP1[px + 1]);
                    }
                }
                rowB[x] = static_cast<uint16_t>(std::clamp(G_val + db, 0.0f, 65535.0f));
            }
        }
    }
}

} // namespace rawvideo
} // namespace darkbag

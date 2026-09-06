#include "BayerProcessor.h"
#include <cstring>
#include <algorithm>

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
    uint16_t* dst
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
            uint16x8_t r0_h_avg = vrhaddq_u16(r0_even, r0_odd);

            // Row 2
            uint16x8_t r2_0 = vld1q_u16(r2 + x);
            uint16x8_t r2_1 = vld1q_u16(r2 + x + 8);
            uint32x4x2_t r2_uzp = vuzpq_u32(vreinterpretq_u32_u16(r2_0), vreinterpretq_u32_u16(r2_1));
            uint16x8_t r2_even = vreinterpretq_u16_u32(r2_uzp.val[0]);
            uint16x8_t r2_odd  = vreinterpretq_u16_u32(r2_uzp.val[1]);
            uint16x8_t r2_h_avg = vrhaddq_u16(r2_even, r2_odd);

            // Vertical combine for even output row
            uint16x8_t out0 = vrhaddq_u16(r0_h_avg, r2_h_avg);
            vst1q_u16(outRow0 + dstX, out0);

            // Row 1
            uint16x8_t r1_0 = vld1q_u16(r1 + x);
            uint16x8_t r1_1 = vld1q_u16(r1 + x + 8);
            uint32x4x2_t r1_uzp = vuzpq_u32(vreinterpretq_u32_u16(r1_0), vreinterpretq_u32_u16(r1_1));
            uint16x8_t r1_even = vreinterpretq_u16_u32(r1_uzp.val[0]);
            uint16x8_t r1_odd  = vreinterpretq_u16_u32(r1_uzp.val[1]);
            uint16x8_t r1_h_avg = vrhaddq_u16(r1_even, r1_odd);

            // Row 3
            uint16x8_t r3_0 = vld1q_u16(r3 + x);
            uint16x8_t r3_1 = vld1q_u16(r3 + x + 8);
            uint32x4x2_t r3_uzp = vuzpq_u32(vreinterpretq_u32_u16(r3_0), vreinterpretq_u32_u16(r3_1));
            uint16x8_t r3_even = vreinterpretq_u16_u32(r3_uzp.val[0]);
            uint16x8_t r3_odd  = vreinterpretq_u16_u32(r3_uzp.val[1]);
            uint16x8_t r3_h_avg = vrhaddq_u16(r3_even, r3_odd);

            // Vertical combine for odd output row
            uint16x8_t out1 = vrhaddq_u16(r1_h_avg, r3_h_avg);
            vst1q_u16(outRow1 + dstX, out1);
        }
        #endif

        // Scalar loop for remaining 4-pixel chunks
        for (; x + 4 <= srcWidth && dstX + 2 <= dstWidth; x += 4, dstX += 2) {
            uint32_t c0 = (static_cast<uint32_t>(r0[x])     + r0[x + 2] + r2[x]     + r2[x + 2] + 2) / 4;
            uint32_t c1 = (static_cast<uint32_t>(r0[x + 1]) + r0[x + 3] + r2[x + 1] + r2[x + 3] + 2) / 4;
            outRow0[dstX]     = static_cast<uint16_t>(c0);
            outRow0[dstX + 1] = static_cast<uint16_t>(c1);

            uint32_t c2 = (static_cast<uint32_t>(r1[x])     + r1[x + 2] + r3[x]     + r3[x + 2] + 2) / 4;
            uint32_t c3 = (static_cast<uint32_t>(r1[x + 1]) + r1[x + 3] + r3[x + 1] + r3[x + 3] + 2) / 4;
            outRow1[dstX]     = static_cast<uint16_t>(c2);
            outRow1[dstX + 1] = static_cast<uint16_t>(c3);
        }

        // Remainder if boundary contains an extra 2 pixels
        if (x + 2 <= srcWidth && dstX < dstWidth) {
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
            remOutRow0[rDstX]     = static_cast<uint16_t>((static_cast<uint32_t>(remR0[rx])     + remR0[rx + 2] + 1) / 2);
            remOutRow0[rDstX + 1] = static_cast<uint16_t>((static_cast<uint32_t>(remR0[rx + 1]) + remR0[rx + 3] + 1) / 2);
            if (remOutRow1) {
                remOutRow1[rDstX]     = static_cast<uint16_t>((static_cast<uint32_t>(remR1[rx])     + remR1[rx + 2] + 1) / 2);
                remOutRow1[rDstX + 1] = static_cast<uint16_t>((static_cast<uint32_t>(remR1[rx + 1]) + remR1[rx + 3] + 1) / 2);
            }
        }
    }
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

} // namespace rawvideo
} // namespace darkbag

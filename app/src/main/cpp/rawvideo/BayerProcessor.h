#pragma once

#include <cstdint>
#include <cstddef>

namespace darkbag {
namespace rawvideo {

enum class DownsampleMode : uint32_t {
    NONE = 0,
    CROP_4K = 1,
    BINNING_1080P = 2
};

struct BayerProcessResult {
    uint32_t outWidth = 0;
    uint32_t outHeight = 0;
    size_t outDataSize = 0;
};

class BayerProcessor {
public:
    /**
     * Lossless 2x2 Bayer Binning using ARM NEON vector instructions (vrhadd_u16).
     * Preserves CFA pattern without cross-channel mixing (never mixes Gr and Gb).
     * Computes rounding halving averages for 4x4 input blocks into 2x2 output blocks:
     *   R'  = (R00 + R02 + R20 + R22 + 2) / 4
     *   Gr' = (Gr01 + Gr03 + Gr21 + Gr23 + 2) / 4
     *   Gb' = (Gb10 + Gb12 + Gb30 + Gb32 + 2) / 4
     *   B'  = (B11 + B13 + B31 + B33 + 2) / 4
     *
     * Outputs to dst (uint16_t buffer of size (srcWidth / 2) * (srcHeight / 2) * 2 bytes).
     * Includes scalar fallback for non-NEON targets and odd/remainder boundaries.
     */
    static void bayer2x2BinningNEON(
        const uint8_t* srcBytes,
        uint32_t srcWidth,
        uint32_t srcHeight,
        uint32_t srcRowStrideBytes,
        uint16_t* dst
    );

    static void bayer2x2BinningNEON(
        const uint8_t* srcBytes,
        uint32_t srcWidth,
        uint32_t srcHeight,
        uint32_t srcRowStrideBytes,
        uint8_t* dstBytes
    ) {
        bayer2x2BinningNEON(srcBytes, srcWidth, srcHeight, srcRowStrideBytes, reinterpret_cast<uint16_t*>(dstBytes));
    }

    /**
     * Stride-aware center window crop for 16-bit Bayer data.
     * Offsets strictly aligned to even coordinates to preserve CFA phase:
     *   startX = ((srcWidth - dstWidth) / 2) & ~1u;
     *   startY = ((srcHeight - dstHeight) / 2) & ~1u;
     */
    static void cropBayer16(
        const uint8_t* srcBytes,
        uint32_t srcWidth,
        uint32_t srcHeight,
        uint32_t srcRowStrideBytes,
        uint16_t* dst,
        uint32_t dstWidth,
        uint32_t dstHeight
    );

    static void cropBayer16(
        const uint8_t* srcBytes,
        uint32_t srcWidth,
        uint32_t srcHeight,
        uint32_t srcRowStrideBytes,
        uint8_t* dstBytes,
        uint32_t dstWidth,
        uint32_t dstHeight
    ) {
        cropBayer16(srcBytes, srcWidth, srcHeight, srcRowStrideBytes, reinterpret_cast<uint16_t*>(dstBytes), dstWidth, dstHeight);
    }

    /**
     * Process raw Bayer sensor frame according to DownsampleMode:
     * - BINNING_1080P: 2x2 Bayer binning with center crop to 1920x1080 (even offsets).
     * - CROP_4K: Center crop to 3840x2160 (even offsets).
     * - NONE: Straight copy respecting stride (or returns original dimensions).
     */
    static BayerProcessResult processBayerFrame(
        const uint8_t* srcBytes,
        uint32_t srcWidth,
        uint32_t srcHeight,
        uint32_t srcRowStrideBytes,
        DownsampleMode mode,
        uint8_t* dstBytes
    );

    static BayerProcessResult processBayerFrame(
        const uint8_t* srcBytes,
        uint32_t srcWidth,
        uint32_t srcHeight,
        uint32_t srcRowStrideBytes,
        DownsampleMode mode,
        uint16_t* dst
    ) {
        return processBayerFrame(srcBytes, srcWidth, srcHeight, srcRowStrideBytes, mode, reinterpret_cast<uint8_t*>(dst));
    }
};

} // namespace rawvideo
} // namespace darkbag

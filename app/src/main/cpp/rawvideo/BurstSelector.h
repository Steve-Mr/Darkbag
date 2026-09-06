#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

namespace darkbag {
namespace burst {

struct FrameScore {
    int index = 0;
    float rawSharpness = 0.0f;
    float weightedScore = 0.0f;
    bool isRejected = false;
};

struct BurstSelectionResult {
    int anchorIndex = 0;
    int acceptedCount = 0;
    std::vector<int> acceptedIndices; // In preferred processing order (anchor at [0])
    std::vector<FrameScore> frameScores;
};

class BurstSelector {
public:
    /**
     * Evaluates sharpness on a single Bayer frame by analyzing the green channel gradient energy.
     * Uses ARM NEON for fast computation (< 1.5ms per 12MP frame).
     */
    static float evaluateFrameSharpness(
        const uint8_t* bayerData,
        uint32_t width,
        uint32_t height,
        uint32_t rowStride,
        int cfaPattern = 0,
        uint32_t iso = 100
    );

    /**
     * Evaluates a burst of N frames stored contiguously or separately.
     * @param framePointers Array of pointers to each frame's raw Bayer data
     * @param numFrames Number of frames in burst
     * @param width Width in pixels
     * @param height Height in pixels
     * @param rowStride Row stride in bytes
     * @param cfaPattern CFA arrangement (0: RGGB, 1: GRBG, 2: GBRG, 3: BGGR)
     * @param iso Camera ISO (used for noise floor normalization)
     * @param triggerIndex Shutter trigger index (prioritizes temporal closeness)
     * @param rejectionThreshold Ratio relative to anchor score (e.g. 0.45f) below which frames are discarded
     */
    static BurstSelectionResult evaluateBurst(
        const std::vector<const uint8_t*>& framePointers,
        uint32_t width,
        uint32_t height,
        uint32_t rowStride,
        int cfaPattern = 0,
        uint32_t iso = 100,
        int triggerIndex = 0,
        float rejectionThreshold = 0.45f
    );
};

} // namespace burst
} // namespace darkbag

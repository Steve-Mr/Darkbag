#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

namespace darkbag {
namespace imaging {

struct FrameScore {
    int index = 0;
    float rawSharpness = 0.0f;
    float weightedScore = 0.0f;
    bool isRejected = false;
};

struct BurstSelectionResult {
    int anchorIndex = 0;
    int acceptedCount = 0;
    std::vector<int> acceptedIndices; // [anchorIndex, ...] in preferred processing order
    std::vector<FrameScore> frameScores;
};

class BurstFrameSelector {
public:
    /**
     * Evaluates sharpness on a single Bayer frame by analyzing green channel gradient energy.
     * Uniformly samples both even rows (Gr) and odd rows (Gb) to fix PR #292's omission of Gb.
     * Uses dynamic noise floor based on ISO to reject shot/read noise.
     *
     * @param bayerData Pointer to raw 16-bit Bayer frame data
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @param rowStride Row stride in bytes
     * @param cfaPattern CFA arrangement (0: RGGB, 1: GRBG, 2: GBRG, 3: BGGR)
     * @param iso Camera ISO sensitivity
     * @return Sharpness score (mean squared gradient above noise floor)
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
     * Evaluates a burst of N frames stored separately or within a contiguous buffer.
     * Computes Gaussian temporal distance-weighted sharpness scores relative to triggerIndex.
     * Identifies the optimal anchor frame and filters out blurred frames below rejectionThreshold.
     *
     * @param framePointers Pointers to each frame's raw Bayer data
     * @param width Frame width
     * @param height Frame height
     * @param rowStride Row stride in bytes
     * @param cfaPattern CFA arrangement
     * @param iso Camera ISO
     * @param triggerIndex Shutter trigger index
     * @param rejectionThreshold Ratio relative to anchor score below which frames are discarded
     * @return Selection result with anchorIndex, acceptedCount, and acceptedIndices
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

    /**
     * Overload for contiguous megaBuffer.
     */
    static BurstSelectionResult evaluateBurst(
        const uint8_t* megaBuffer,
        int numFrames,
        size_t frameSizeBytes,
        uint32_t width,
        uint32_t height,
        uint32_t rowStride,
        int cfaPattern = 0,
        uint32_t iso = 100,
        int triggerIndex = 0,
        float rejectionThreshold = 0.45f
    );

    /**
     * In-place compact rearrangement:
     * Moves the physical frame at anchorIndex to slot 0, and compactly places all retained
     * acceptedIndices into slots 0 until finalNumFrames.
     * Performed in-place via chunked swapping (64KB buffer), avoiding large 24MB allocations
     * and eliminating Java-side buffer reallocations / copies.
     *
     * @param megaBuffer Pointer to contiguous burst buffer
     * @param totalNumFrames Total number of frames in megaBuffer
     * @param frameSizeBytes Size of each frame in bytes
     * @param acceptedIndices Retained frame indices
     * @param anchorIndex Index of chosen anchor frame
     * @return Number of accepted frames compactly stored at slots [0, finalNumFrames)
     */
    static int compactAcceptedFramesInPlace(
        uint8_t* megaBuffer,
        int totalNumFrames,
        size_t frameSizeBytes,
        const std::vector<int>& acceptedIndices,
        int anchorIndex
    );
};

} // namespace imaging
} // namespace darkbag

#include "BurstFrameSelector.h"
#include <cmath>
#include <algorithm>
#include <cstring>

#if defined(_OPENMP)
#include <omp.h>
#endif

namespace darkbag {
namespace imaging {

float BurstFrameSelector::evaluateFrameSharpness(
    const uint8_t* bayerData,
    uint32_t width,
    uint32_t height,
    uint32_t rowStride,
    int cfaPattern,
    uint32_t iso
) {
    if (!bayerData || width < 16 || height < 16) {
        return 0.0f;
    }

    // Determine green pixel starting offsets for even/odd rows based on CFA arrangement:
    // CFA patterns: 0: RGGB, 1: GRBG, 2: GBRG, 3: BGGR
    //
    // RGGB:
    //   Row 0 (even): R(0), Gr(1), R(2), Gr(3) ...  -> even row green offset = 1 (Gr)
    //   Row 1 (odd):  Gb(0), B(1), Gb(2), B(3) ...  -> odd row green offset  = 0 (Gb)
    // GRBG:
    //   Row 0 (even): Gr(0), R(1), Gr(2), R(3) ...  -> even row green offset = 0 (Gr)
    //   Row 1 (odd):  B(0), Gb(1), B(2), Gb(3) ...  -> odd row green offset  = 1 (Gb)
    // GBRG:
    //   Row 0 (even): Gb(0), B(1), Gb(2), B(3) ...  -> even row green offset = 0 (Gb)
    //   Row 1 (odd):  R(0), Gr(1), R(2), Gr(3) ...  -> odd row green offset  = 1 (Gr)
    // BGGR:
    //   Row 0 (even): B(0), Gb(1), B(2), Gb(3) ...  -> even row green offset = 1 (Gb)
    //   Row 1 (odd):  Gr(0), R(1), Gr(2), R(3) ...  -> odd row green offset  = 0 (Gr)
    uint32_t gOffsetEven = 1;
    uint32_t gOffsetOdd = 0;
    switch (cfaPattern) {
        case 1: // GRBG
            gOffsetEven = 0;
            gOffsetOdd = 1;
            break;
        case 2: // GBRG
            gOffsetEven = 0;
            gOffsetOdd = 1;
            break;
        case 3: // BGGR
            gOffsetEven = 1;
            gOffsetOdd = 0;
            break;
        case 0: // RGGB
        default:
            gOffsetEven = 1;
            gOffsetOdd = 0;
            break;
    }

    // Dynamic noise floor threshold based on sensor ISO to avoid scoring shot/read noise as sharpness
    const float noiseFloor = std::max(6.0f, 2.0f + 0.015f * static_cast<float>(iso));
    const uint16_t noiseFloorU16 = static_cast<uint16_t>(noiseFloor);

    // Step rows by 4; inside each iteration, evaluate both row y (even) and row y+1 (odd)
    // to ensure Gr and Gb are uniformly sampled (fixing PR #292's omission of Gb).
    const uint32_t stepY = 4;
    const uint32_t maxY = height > 8 ? height - 8 : 0;
    const uint32_t maxX = width > 8 ? width - 8 : 0;

    double totalGradientEnergy = 0.0;
    uint64_t sampleCount = 0;

    #if defined(_OPENMP)
    #pragma omp parallel for reduction(+:totalGradientEnergy, sampleCount) schedule(static)
    #endif
    for (uint32_t y = 2; y < maxY; y += stepY) {
        // 1. Even row green channel (Gr in RGGB)
        const auto* rowEvenCurr = reinterpret_cast<const uint16_t*>(bayerData + y * rowStride);
        const auto* rowEvenDown2 = reinterpret_cast<const uint16_t*>(bayerData + (y + 2) * rowStride);

        for (uint32_t x = 2 + gOffsetEven; x < maxX; x += 4) {
            uint16_t g0 = rowEvenCurr[x];
            uint16_t gRight = rowEvenCurr[x + 2];
            uint16_t gDown = rowEvenDown2[x];

            uint32_t diffH = (g0 > gRight) ? (g0 - gRight) : (gRight - g0);
            uint32_t diffV = (g0 > gDown)  ? (g0 - gDown)  : (gDown - g0);

            float magH = (diffH > noiseFloorU16) ? static_cast<float>(diffH - noiseFloorU16) : 0.0f;
            float magV = (diffV > noiseFloorU16) ? static_cast<float>(diffV - noiseFloorU16) : 0.0f;

            totalGradientEnergy += static_cast<double>(magH * magH + magV * magV);
            sampleCount++;
        }

        // 2. Odd row green channel (Gb in RGGB)
        const auto* rowOddCurr = reinterpret_cast<const uint16_t*>(bayerData + (y + 1) * rowStride);
        const auto* rowOddDown2 = reinterpret_cast<const uint16_t*>(bayerData + (y + 3) * rowStride);

        for (uint32_t x = 2 + gOffsetOdd; x < maxX; x += 4) {
            uint16_t g0 = rowOddCurr[x];
            uint16_t gRight = rowOddCurr[x + 2];
            uint16_t gDown = rowOddDown2[x];

            uint32_t diffH = (g0 > gRight) ? (g0 - gRight) : (gRight - g0);
            uint32_t diffV = (g0 > gDown)  ? (g0 - gDown)  : (gDown - g0);

            float magH = (diffH > noiseFloorU16) ? static_cast<float>(diffH - noiseFloorU16) : 0.0f;
            float magV = (diffV > noiseFloorU16) ? static_cast<float>(diffV - noiseFloorU16) : 0.0f;

            totalGradientEnergy += static_cast<double>(magH * magH + magV * magV);
            sampleCount++;
        }
    }

    if (sampleCount == 0) return 0.0f;
    return static_cast<float>(totalGradientEnergy / static_cast<double>(sampleCount));
}

BurstSelectionResult BurstFrameSelector::evaluateBurst(
    const std::vector<const uint8_t*>& framePointers,
    uint32_t width,
    uint32_t height,
    uint32_t rowStride,
    int cfaPattern,
    uint32_t iso,
    int triggerIndex,
    float rejectionThreshold
) {
    BurstSelectionResult result{};
    const int numFrames = static_cast<int>(framePointers.size());
    if (numFrames <= 0) return result;

    result.frameScores.resize(numFrames);

    float maxWeightedScore = -1.0f;
    int bestAnchor = 0;

    const float sigma = 3.5f;
    const float twoSigmaSq = 2.0f * sigma * sigma;

    // Evaluate sharpness & temporal Gaussian weighting for each frame
    for (int i = 0; i < numFrames; ++i) {
        float sharpness = evaluateFrameSharpness(framePointers[i], width, height, rowStride, cfaPattern, iso);

        float timeDiff = static_cast<float>(i - triggerIndex);
        float timeWeight = std::exp(-(timeDiff * timeDiff) / twoSigmaSq);
        float weightedScore = sharpness * timeWeight;

        result.frameScores[i].index = i;
        result.frameScores[i].rawSharpness = sharpness;
        result.frameScores[i].weightedScore = weightedScore;

        if (weightedScore > maxWeightedScore) {
            maxWeightedScore = weightedScore;
            bestAnchor = i;
        }
    }

    result.anchorIndex = bestAnchor;

    // Anchor is always retained and placed at index 0
    result.acceptedIndices.push_back(bestAnchor);

    const float rejectThresholdScore = maxWeightedScore * rejectionThreshold;

    for (int i = 0; i < numFrames; ++i) {
        if (i == bestAnchor) continue;

        if (result.frameScores[i].weightedScore < rejectThresholdScore) {
            result.frameScores[i].isRejected = true;
        } else {
            result.acceptedIndices.push_back(i);
        }
    }

    result.acceptedCount = static_cast<int>(result.acceptedIndices.size());
    return result;
}

BurstSelectionResult BurstFrameSelector::evaluateBurst(
    const uint8_t* megaBuffer,
    int numFrames,
    size_t frameSizeBytes,
    uint32_t width,
    uint32_t height,
    uint32_t rowStride,
    int cfaPattern,
    uint32_t iso,
    int triggerIndex,
    float rejectionThreshold
) {
    BurstSelectionResult result{};
    if (!megaBuffer || numFrames <= 0 || frameSizeBytes == 0) return result;

    std::vector<const uint8_t*> framePointers(numFrames);
    for (int i = 0; i < numFrames; ++i) {
        framePointers[i] = megaBuffer + static_cast<size_t>(i) * frameSizeBytes;
    }

    return evaluateBurst(
        framePointers,
        width,
        height,
        rowStride,
        cfaPattern,
        iso,
        triggerIndex,
        rejectionThreshold
    );
}

int BurstFrameSelector::compactAcceptedFramesInPlace(
    uint8_t* megaBuffer,
    int totalNumFrames,
    size_t frameSizeBytes,
    const std::vector<int>& acceptedIndices,
    int anchorIndex
) {
    if (!megaBuffer || totalNumFrames <= 0 || frameSizeBytes == 0) {
        return 0;
    }

    if (anchorIndex < 0 || anchorIndex >= totalNumFrames) {
        anchorIndex = 0;
    }

    // Build target frame ordering:
    // Slot 0 must be anchorIndex, followed by the other accepted frame indices without duplicates.
    std::vector<int> targetOrder;
    targetOrder.reserve(acceptedIndices.size() + 1);
    targetOrder.push_back(anchorIndex);

    std::vector<bool> included(totalNumFrames, false);
    included[anchorIndex] = true;

    for (int idx : acceptedIndices) {
        if (idx >= 0 && idx < totalNumFrames && !included[idx]) {
            targetOrder.push_back(idx);
            included[idx] = true;
        }
    }

    const int finalNumFrames = static_cast<int>(targetOrder.size());
    if (finalNumFrames <= 0) {
        return 0;
    }

    // Track which original frame is currently located at each physical slot in megaBuffer
    std::vector<int> currentFrameAtSlot(totalNumFrames);
    for (int i = 0; i < totalNumFrames; ++i) {
        currentFrameAtSlot[i] = i;
    }

    // 64KB stack buffer for zero-heap-allocation chunked swapping
    constexpr size_t CHUNK_SIZE = 64 * 1024;
    std::vector<uint8_t> chunkBuffer(CHUNK_SIZE);

    // Rearrange targetOrder[dst] into slot dst for dst = 0 .. finalNumFrames - 1
    for (int dst = 0; dst < finalNumFrames; ++dst) {
        int wantedFrame = targetOrder[dst];
        if (currentFrameAtSlot[dst] == wantedFrame) {
            continue; // Already at the right physical slot
        }

        // Find which slot currently holds wantedFrame
        int currentSlot = -1;
        for (int s = dst + 1; s < totalNumFrames; ++s) {
            if (currentFrameAtSlot[s] == wantedFrame) {
                currentSlot = s;
                break;
            }
        }

        if (currentSlot == -1) {
            continue;
        }

        // Swap physical memory between slot dst and currentSlot in 64KB chunks
        uint8_t* p1 = megaBuffer + static_cast<size_t>(dst) * frameSizeBytes;
        uint8_t* p2 = megaBuffer + static_cast<size_t>(currentSlot) * frameSizeBytes;
        size_t bytesLeft = frameSizeBytes;

        while (bytesLeft > 0) {
            size_t toCopy = std::min(bytesLeft, CHUNK_SIZE);
            std::memcpy(chunkBuffer.data(), p1, toCopy);
            std::memcpy(p1, p2, toCopy);
            std::memcpy(p2, chunkBuffer.data(), toCopy);
            p1 += toCopy;
            p2 += toCopy;
            bytesLeft -= toCopy;
        }

        // Update tracking
        std::swap(currentFrameAtSlot[dst], currentFrameAtSlot[currentSlot]);
    }

    return finalNumFrames;
}

} // namespace imaging
} // namespace darkbag

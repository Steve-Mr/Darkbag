#pragma once

namespace darkbag {
namespace gpu {

/**
 * GLSL ES 3.1 Compute Shaders for Phase 3C: End-to-End GPU Align & Merge Pipeline.
 *
 * Implements GPU-native multi-frame raw Bayer accumulation:
 * - Pass 1: Bayer Downsampling Pyramid Generation (Coarse-to-fine multi-resolution)
 * - Pass 2: Hierarchical Block Matching Optical Flow (Displacement vector search with L1 regularization)
 * - Pass 3: Motion-Adaptive Temporal Accumulation (Robust bilateral weighting with Bayer parity preservation)
 * - Pass 4: Accumulation Normalization & Zero-Copy Handover to GpuRcdComputeEngine
 */

// Pass 0: Reference Frame Accumulation Buffer Initializer
static const char* kInitAccumulateComputeShader = R"glsl(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

uniform highp usampler2D uRefBayer;
layout(r32f) uniform highp writeonly image2D uAccumValOut;
layout(r32f) uniform highp writeonly image2D uAccumWeightOut;

uniform int uWidth;
uniform int uHeight;

void main() {
    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
    if (coord.x >= uWidth || coord.y >= uHeight) {
        return;
    }
    float val = float(texelFetch(uRefBayer, coord, 0).r);
    imageStore(uAccumValOut, coord, vec4(val, 0.0, 0.0, 0.0));
    imageStore(uAccumWeightOut, coord, vec4(1.0, 0.0, 0.0, 0.0));
}
)glsl";

// Pass 1: Bayer Pyramid Downsampler (Downsamples Bayer CFA into luminance pyramid)
static const char* kPyramidDownsampleComputeShader = R"glsl(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

uniform highp usampler2D uBayerInput;
layout(r32ui) uniform highp writeonly uimage2D uPyramidOutput;

uniform int uSrcWidth;
uniform int uSrcHeight;
uniform int uCfaPattern; // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR

void main() {
    ivec2 dstCoord = ivec2(gl_GlobalInvocationID.xy);
    ivec2 srcCoord = dstCoord * 2;
    if (srcCoord.x + 1 >= uSrcWidth || srcCoord.y + 1 >= uSrcHeight) {
        return;
    }

    // Read 2x2 Bayer quad via hardware-cached texelFetch
    uint p00 = texelFetch(uBayerInput, srcCoord + ivec2(0, 0), 0).r;
    uint p10 = texelFetch(uBayerInput, srcCoord + ivec2(1, 0), 0).r;
    uint p01 = texelFetch(uBayerInput, srcCoord + ivec2(0, 1), 0).r;
    uint p11 = texelFetch(uBayerInput, srcCoord + ivec2(1, 1), 0).r;

    // Average luminance across the quad for robust motion estimation
    uint avgVal = (p00 + p10 + p01 + p11 + 2u) >> 2u;
    imageStore(uPyramidOutput, dstCoord, uvec4(avgVal, 0u, 0u, 0u));
}
)glsl";

// Pass 2: Hierarchical Block Matching / Motion Vector Estimator
static const char* kBlockMatchingComputeShader = R"glsl(#version 310 es
layout(local_size_x = 8, local_size_y = 8) in;

uniform highp usampler2D uRefPyramid;
uniform highp usampler2D uCandPyramid;
layout(r32i) uniform highp writeonly iimage2D uMotionVectors;

uniform int uWidth;
uniform int uHeight;
uniform int uSearchRadius; // e.g., 4 or 8 pixels
uniform float uLambda;     // Motion regularization factor (e.g., 2.0 ~ 4.0)

void main() {
    ivec2 blockCoord = ivec2(gl_GlobalInvocationID.xy);
    ivec2 blockOrigin = blockCoord * 8;

    if (blockOrigin.x >= uWidth || blockOrigin.y >= uHeight) {
        return;
    }

    // Cache 8x8 reference block in local registers to eliminate redundant global texture fetches
    uint refBlock[8][8];
    for (int by = 0; by < 8; ++by) {
        for (int bx = 0; bx < 8; ++bx) {
            ivec2 rPos = clamp(blockOrigin + ivec2(bx, by), ivec2(0), ivec2(uWidth - 1, uHeight - 1));
            refBlock[by][bx] = texelFetch(uRefPyramid, rPos, 0).r;
        }
    }

    uint bestCost = 0xFFFFFFFFu;
    ivec2 bestDisplacement = ivec2(0, 0);

    // Block matching within search window
    for (int dy = -uSearchRadius; dy <= uSearchRadius; ++dy) {
        for (int dx = -uSearchRadius; dx <= uSearchRadius; ++dx) {
            uint currentSad = 0u;

            for (int by = 0; by < 8; ++by) {
                for (int bx = 0; bx < 8; ++bx) {
                    ivec2 cPos = clamp(blockOrigin + ivec2(bx + dx, by + dy), ivec2(0), ivec2(uWidth - 1, uHeight - 1));
                    int rVal = int(refBlock[by][bx]);
                    int cVal = int(texelFetch(uCandPyramid, cPos, 0).r);
                    currentSad += uint(abs(rVal - cVal));
                }
            }

            // Spatial L1 regularization: penalize non-zero displacement to bias flat areas to (0,0)
            uint regCost = uint(uLambda * float(abs(dx) + abs(dy)));
            uint totalCost = currentSad + regCost;

            if (totalCost < bestCost) {
                bestCost = totalCost;
                bestDisplacement = ivec2(dx, dy);
            }
        }
    }

    // Pack 16-bit 2D displacement into standard Core GLES 3.1 r32i format
    int packedMv = (bestDisplacement.y << 16) | (bestDisplacement.x & 0xFFFF);
    imageStore(uMotionVectors, blockCoord, ivec4(packedMv, 0, 0, 0));
}
)glsl";

// Pass 3: Temporal Motion-Adaptive Accumulation Kernel
static const char* kTemporalAccumulateComputeShader = R"glsl(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

uniform highp usampler2D uCandBayer;
layout(r32f) uniform highp readonly image2D uAccumValIn;
layout(r32f) uniform highp readonly image2D uAccumWeightIn;
layout(r32i) uniform highp readonly iimage2D uMotionVectors;

layout(r32f) uniform highp writeonly image2D uAccumValOut;
layout(r32f) uniform highp writeonly image2D uAccumWeightOut;

uniform int uWidth;
uniform int uHeight;
uniform vec2 uNoiseModel;          // x = shot noise scale (S), y = read noise offset (O)
uniform float uGhostingSensitivity;// Typically 1.0 ~ 2.0

void main() {
    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
    if (coord.x >= uWidth || coord.y >= uHeight) {
        return;
    }

    // Retrieve motion vector for this block (8x8 block grid) and unpack
    ivec2 blockCoord = coord / 8;
    int packedMv = imageLoad(uMotionVectors, blockCoord).r;
    ivec2 mv = ivec2(packedMv << 16 >> 16, packedMv >> 16);

    // Aligned position must preserve Bayer CFA 2x2 parity
    ivec2 alignedMv = (mv / 2) * 2;

    // Strict Bayer grid parity clamping to prevent border channel mixing
    ivec2 minOffset = -((coord) / 2 * 2);
    ivec2 maxOffset = ((ivec2(uWidth - 1, uHeight - 1) - coord) / 2 * 2);
    ivec2 clampedMv = clamp(alignedMv, minOffset, maxOffset);
    ivec2 candCoord = coord + clampedMv;

    float candVal = float(texelFetch(uCandBayer, candCoord, 0).r);
    float prevVal = imageLoad(uAccumValIn, coord).r;
    float prevWeight = imageLoad(uAccumWeightIn, coord).r;

    // Current effective reference estimate
    float refEst = (prevWeight > 0.001) ? (prevVal / prevWeight) : candVal;
    float diff = candVal - refEst;

    // Signal-dependent variance (shot + read noise model)
    float localVariance = max(uNoiseModel.x * refEst + uNoiseModel.y, 1.0);
    float normDiffSq = (diff * diff) / localVariance;
    float weight = 1.0 / (1.0 + uGhostingSensitivity * normDiffSq);

    // Temporal accumulation
    float newWeight = prevWeight + weight;
    float newVal = prevVal + (candVal * weight);

    imageStore(uAccumValOut, coord, vec4(newVal, 0.0, 0.0, 0.0));
    imageStore(uAccumWeightOut, coord, vec4(newWeight, 0.0, 0.0, 0.0));
}
)glsl";

// Pass 4: Accumulation Normalization to Unified Bayer CFA Output
static const char* kAccumNormalizeComputeShader = R"glsl(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

layout(r32f)  uniform highp readonly image2D uAccumVal;
layout(r32f)  uniform highp readonly image2D uAccumWeight;
layout(r32ui) uniform highp writeonly uimage2D uNormalizedBayerOut;

uniform int uWidth;
uniform int uHeight;

void main() {
    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
    if (coord.x >= uWidth || coord.y >= uHeight) {
        return;
    }

    float val = imageLoad(uAccumVal, coord).r;
    float weight = imageLoad(uAccumWeight, coord).r;

    float normalized = (weight > 0.001) ? (val / weight) : val;
    uint outVal = uint(clamp(normalized + 0.5, 0.0, 65535.0));

    imageStore(uNormalizedBayerOut, coord, uvec4(outVal, 0u, 0u, 0u));
}
)glsl";

} // namespace gpu
} // namespace darkbag

#pragma once

namespace darkbag {
namespace gpu {

/**
 * Workgroup dimensions for RCD Compute Shaders.
 * 16x16 = 256 invocations, optimal for Qualcomm Adreno (wave 64/128) and ARM Mali (warp 16/32).
 */
constexpr int kRcdWorkgroupSizeX = 16;
constexpr int kRcdWorkgroupSizeY = 16;

/**
 * ==============================================================================================
 * Pass A Compute Shader: Directional Gradients + Green Channel Interpolation (Kernel Fusion)
 * ==============================================================================================
 *
 * Algorithm Overview (Luis Sanz Rodriguez RCD Algorithm - Steps 1, 2, 3):
 * 1. Cooperative Shared Memory Load:
 *    - Each 16x16 workgroup loads a (16 + 2*HALO) x (16 + 2*HALO) tile with HALO = 6 (28x28 = 784 items).
 *    - Global coordinates are robustly clamped to [0, width-1] and [0, height-1] to eliminate out-of-bounds.
 *    - Raw 16-bit uint samples are normalized to float [0.0, 1.0] after per-channel black level subtraction.
 * 2. High-Pass Difference Filters & Directional Discrimination:
 *    - Computes 7-point vertical high-pass filter squared (bufferV) and horizontal counterpart (bufferH).
 *    - Sums 3-point vertical and horizontal neighborhood stats (V_Stat, H_Stat) to obtain VH_Dir.
 * 3. Ratio-Corrected Low-Pass Filter (LPF):
 *    - Evaluates 3x3 LPF incorporating center, cardinal green, and diagonal opposite colors.
 *    - Evaluates LPF at center and cardinal offsets (+-2 pixels) to compute ratio-corrected estimates (N, S, W, E).
 * 4. Cardinal Edge Interpolation & Blending:
 *    - Combines directional estimates along minimum gradient direction (V_Est and H_Est).
 *    - Discriminator (VH_Disc) selects decisive interpolation direction between central and 4-diagonal average.
 * 5. Output:
 *    - Fully reconstructed Green channel image written to uGreenImg (GL_R16UI format, range 0~65535).
 */
static const char* kRcdPassAComputeShader = R"glsl(#version 310 es
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp image2D;
precision highp uimage2D;

layout(local_size_x = 16, local_size_y = 16) in;

// Image dimensions and CFA pattern
uniform int uWidth;
uniform int uHeight;
uniform int uCfaPattern; // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR

// Sensor calibration uniforms
uniform vec4 uBlackLevel; // x: R, y: G0, z: G1, w: B
uniform float uWhiteLevel;

// Input & Output
uniform highp usampler2D uBayerTex;
layout(r32f, binding = 0) uniform writeonly highp image2D uGreenImg;

// Cooperative shared memory tile: 16 + 2 * HALO = 28x28 (784 floats = 3.1 KB)
const int HALO = 6;
const int TILE_SIZE = 28;
shared float sBayer[28][28];

// Color determination in Bayer pattern: 0=R, 1=G, 2=B
int getCfaColor(int pattern, int r, int c) {
    int r2 = r & 1;
    int c2 = c & 1;
    if (pattern == 0) { // RGGB: (0,0)=R, (0,1)=G, (1,0)=G, (1,1)=B
        return (r2 == 0) ? ((c2 == 0) ? 0 : 1) : ((c2 == 0) ? 1 : 2);
    } else if (pattern == 1) { // GRBG: (0,0)=G, (0,1)=R, (1,0)=B, (1,1)=G
        return (r2 == 0) ? ((c2 == 0) ? 1 : 0) : ((c2 == 0) ? 2 : 1);
    } else if (pattern == 2) { // GBRG: (0,0)=G, (0,1)=B, (1,0)=R, (1,1)=G
        return (r2 == 0) ? ((c2 == 0) ? 1 : 2) : ((c2 == 0) ? 0 : 1);
    } else { // 3: BGGR: (0,0)=B, (0,1)=G, (1,0)=G, (1,1)=R
        return (r2 == 0) ? ((c2 == 0) ? 2 : 1) : ((c2 == 0) ? 1 : 0);
    }
}

// Normalize raw 16-bit Bayer CFA pixel with channel-dependent black level
float normalizePixel(uint raw, int r, int c) {
    int color = getCfaColor(uCfaPattern, r, c);
    float bl;
    if (color == 0) {
        bl = uBlackLevel.x; // R
    } else if (color == 1) {
        bl = ((r & 1) == 0) ? uBlackLevel.y : uBlackLevel.z; // G0 (even row) / G1 (odd row)
    } else {
        bl = uBlackLevel.w; // B
    }
    float denom = max(1.0, uWhiteLevel - bl);
    return clamp((float(raw) - bl) / denom, 0.0, 1.0);
}

// Quantize normalized float [0.0, 1.0] to 16-bit integer [0, 65535]
uint denormalizePixel(float val) {
    return uint(clamp(val * 65535.0 + 0.5, 0.0, 65535.0));
}

// High-pass filter squared in vertical direction (Step 1.1)
float calcBufferV(int y, int x) {
    float diff = (sBayer[y-3][x] - sBayer[y-1][x] - sBayer[y+1][x] + sBayer[y+3][x])
               - 3.0 * (sBayer[y-2][x] + sBayer[y+2][x])
               + 6.0 * sBayer[y][x];
    return diff * diff;
}

// High-pass filter squared in horizontal direction (Step 1.1)
float calcBufferH(int y, int x) {
    float diff = (sBayer[y][x-3] - sBayer[y][x-1] - sBayer[y][x+1] + sBayer[y][x+3])
               - 3.0 * (sBayer[y][x-2] + sBayer[y][x+2])
               + 6.0 * sBayer[y][x];
    return diff * diff;
}

// Directional discrimination strength VH_Dir (Step 1.2)
float calcVhDir(int y, int x) {
    const float epssq = 1e-10;
    float vStat = max(epssq, calcBufferV(y-1, x) + calcBufferV(y, x) + calcBufferV(y+1, x));
    float hStat = max(epssq, calcBufferH(y, x-1) + calcBufferH(y, x) + calcBufferH(y, x+1));
    return vStat / (vStat + hStat);
}

// Low-pass filter incorporating 3x3 local samples from raw CFA (Step 2)
float calcLpf(int y, int x) {
    return sBayer[y][x]
         + 0.5 * (sBayer[y-1][x] + sBayer[y+1][x] + sBayer[y][x-1] + sBayer[y][x+1])
         + 0.25 * (sBayer[y-1][x-1] + sBayer[y-1][x+1] + sBayer[y+1][x-1] + sBayer[y+1][x+1]);
}

void main() {
    int tid = int(gl_LocalInvocationIndex);
    ivec2 groupOrigin = ivec2(gl_WorkGroupID.xy) * 16;

    // Cooperative loading of 28x28 tile into on-chip shared memory
    for (int i = tid; i < 784; i += 256) {
        int sy = i / 28;
        int sx = i % 28;
        ivec2 gCoord = groupOrigin + ivec2(sx - HALO, sy - HALO);
        gCoord = clamp(gCoord, ivec2(0), ivec2(uWidth - 1, uHeight - 1));
        uint raw = texelFetch(uBayerTex, gCoord, 0).r;
        sBayer[sy][sx] = normalizePixel(raw, gCoord.y, gCoord.x);
    }
    barrier();

    ivec2 localId = ivec2(gl_LocalInvocationID.xy);
    ivec2 outCoord = groupOrigin + localId;
    if (outCoord.x >= uWidth || outCoord.y >= uHeight) {
        return;
    }

    int cy = localId.y + HALO;
    int cx = localId.x + HALO;
    int cColor = getCfaColor(uCfaPattern, outCoord.y, outCoord.x);

    float greenVal;
    if (cColor == 1) {
        // Pixel is already Green on sensor CFA
        greenVal = sBayer[cy][cx];
    } else {
        // Red or Blue pixel: directional interpolation using ratio correction (Step 3)
        const float eps = 1e-5;
        float cfai = sBayer[cy][cx];

        // 1. Cardinal directional gradients
        float nGrad = eps + (abs(sBayer[cy-1][cx] - sBayer[cy+1][cx]) + abs(cfai - sBayer[cy-2][cx]))
                          + (abs(sBayer[cy-1][cx] - sBayer[cy-3][cx]) + abs(sBayer[cy-2][cx] - sBayer[cy-4][cx]));
        float sGrad = eps + (abs(sBayer[cy-1][cx] - sBayer[cy+1][cx]) + abs(cfai - sBayer[cy+2][cx]))
                          + (abs(sBayer[cy+1][cx] - sBayer[cy+3][cx]) + abs(sBayer[cy+2][cx] - sBayer[cy+4][cx]));
        float wGrad = eps + (abs(sBayer[cy][cx-1] - sBayer[cy][cx+1]) + abs(cfai - sBayer[cy][cx-2]))
                          + (abs(sBayer[cy][cx-1] - sBayer[cy][cx-3]) + abs(sBayer[cy][cx-2] - sBayer[cy][cx-4]));
        float eGrad = eps + (abs(sBayer[cy][cx-1] - sBayer[cy][cx+1]) + abs(cfai - sBayer[cy][cx+2]))
                          + (abs(sBayer[cy][cx+1] - sBayer[cy][cx+3]) + abs(sBayer[cy][cx+2] - sBayer[cy][cx+4]));

        // 2. LPF ratio-corrected green estimates
        float lpfi = calcLpf(cy, cx);
        float lpfN = calcLpf(cy - 2, cx);
        float lpfS = calcLpf(cy + 2, cx);
        float lpfW = calcLpf(cy, cx - 2);
        float lpfE = calcLpf(cy, cx + 2);

        float nEst = sBayer[cy-1][cx] * (2.0 * lpfi) / (eps + lpfi + lpfN);
        float sEst = sBayer[cy+1][cx] * (2.0 * lpfi) / (eps + lpfi + lpfS);
        float wEst = sBayer[cy][cx-1] * (2.0 * lpfi) / (eps + lpfi + lpfW);
        float eEst = sBayer[cy][cx+1] * (2.0 * lpfi) / (eps + lpfi + lpfE);

        float vEst = (sGrad * nEst + nGrad * sEst) / (nGrad + sGrad);
        float hEst = (wGrad * eEst + eGrad * wEst) / (eGrad + wGrad);

        // 3. Directional discrimination strength blending
        float vhCenter = calcVhDir(cy, cx);
        float vhNeighbour = 0.25 * ((calcVhDir(cy-1, cx-1) + calcVhDir(cy-1, cx+1))
                                  + (calcVhDir(cy+1, cx-1) + calcVhDir(cy+1, cx+1)));

        float vhDisc = (abs(0.5 - vhCenter) < abs(0.5 - vhNeighbour)) ? vhNeighbour : vhCenter;
        greenVal = clamp(mix(vEst, hEst, vhDisc), 0.0, 1.0);
    }

    imageStore(uGreenImg, outCoord, vec4(greenVal, 0.0, 0.0, 0.0));
}
)glsl";

/**
 * ==============================================================================================
 * Pass B Compute Shader: Color Ratio Interpolation + Red/Blue Recovery + Planar Outputs
 * ==============================================================================================
 *
 * Algorithm Overview (Luis Sanz Rodriguez RCD Algorithm - Steps 3, 4 & Kernel Fusion):
 * 1. Inputs:
 *    - Raw Bayer CFA texture uBayerTex (GL_R16UI).
 *    - Pass A fully-interpolated Green texture uGreenTex (GL_R16UI).
 * 2. Cooperative Shared Memory Layout:
 *    - Tile dimension: (16 + 2*HALO) x (16 + 2*HALO) with HALO = 2 (20x20 = 400 items).
 *    - sBayer, sGreen: Local normalized sensor data.
 *    - sRatioR, sRatioB: Local chromaticity color ratio fields (R/G and B/G).
 * 3. Multi-Phase Cooperative Fusion:
 *    - Phase 1: Cooperatively fetch Bayer and Green, store normalized floats.
 *    - Phase 2: Compute native color ratios rho_R = R/G at Red pixels, rho_B = B/G at Blue pixels.
 *    - Phase 3: Interpolate opposite color ratios at Red/Blue pixels across 4 diagonal neighbors
 *      using Green-guided diagonal gradients (P & Q diagonals).
 *    - Phase 4: Full channel recovery at Green pixels using cardinal guided ratio interpolation.
 *      Native pixels strictly retain their raw sensor samples for maximum sharpness.
 * 4. Outputs:
 *    - 3 separate planar images: uOutTexR, uOutTexG, uOutTexB (GL_R16UI scaled to 0~65535).
 *    - Directly compatible with Phase 1 GpuColorPipeShader.h (uTexR, uTexG, uTexB sampled as .r).
 */
static const char* kRcdPassBComputeShader = R"glsl(#version 310 es
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp image2D;

layout(local_size_x = 16, local_size_y = 16) in;

// Image dimensions and CFA pattern
uniform int uWidth;
uniform int uHeight;
uniform int uCfaPattern; // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR

// Sensor calibration uniforms
uniform vec4 uBlackLevel; // x: R, y: G0, z: G1, w: B
uniform float uWhiteLevel;

// Inputs
uniform highp usampler2D uBayerTex;
layout(r32f, binding = 1) uniform readonly highp image2D uGreenImg;

// RGBA16F output containing R, G, B, and A channels (normalized float 0.0 ~ 1.0)
layout(rgba16f, binding = 0) uniform writeonly highp image2D uOutRgbImg;

// Cooperative shared memory tiles: 16 + 2 * HALO = 20x20 (4 buffers x 400 floats = 6.25 KB)
const int HALO = 2;
const int TILE_SIZE = 20;

shared float sBayer[20][20];
shared float sGreen[20][20];
shared float sRatioR[20][20];
shared float sRatioB[20][20];

// Color determination in Bayer pattern: 0=R, 1=G, 2=B
int getCfaColor(int pattern, int r, int c) {
    int r2 = r & 1;
    int c2 = c & 1;
    if (pattern == 0) { // RGGB
        return (r2 == 0) ? ((c2 == 0) ? 0 : 1) : ((c2 == 0) ? 1 : 2);
    } else if (pattern == 1) { // GRBG
        return (r2 == 0) ? ((c2 == 0) ? 1 : 0) : ((c2 == 0) ? 2 : 1);
    } else if (pattern == 2) { // GBRG
        return (r2 == 0) ? ((c2 == 0) ? 1 : 2) : ((c2 == 0) ? 0 : 1);
    } else { // 3: BGGR
        return (r2 == 0) ? ((c2 == 0) ? 2 : 1) : ((c2 == 0) ? 1 : 0);
    }
}

// Normalize raw 16-bit Bayer CFA pixel with channel-dependent black level
float normalizePixel(uint raw, int r, int c) {
    int color = getCfaColor(uCfaPattern, r, c);
    float bl;
    if (color == 0) {
        bl = uBlackLevel.x; // R
    } else if (color == 1) {
        bl = ((r & 1) == 0) ? uBlackLevel.y : uBlackLevel.z; // G0 / G1
    } else {
        bl = uBlackLevel.w; // B
    }
    float denom = max(1.0, uWhiteLevel - bl);
    return clamp((float(raw) - bl) / denom, 0.0, 1.0);
}

// Quantize normalized float [0.0, 1.0] to 16-bit integer [0, 65535]
uint denormalizePixel(float val) {
    return uint(clamp(val * 65535.0 + 0.5, 0.0, 65535.0));
}

void main() {
    int tid = int(gl_LocalInvocationIndex);
    ivec2 groupOrigin = ivec2(gl_WorkGroupID.xy) * 16;

    // Phase 1: Load Bayer CFA and interpolated Green into shared memory (400 elements)
    for (int i = tid; i < 400; i += 256) {
        int sy = i / 20;
        int sx = i % 20;
        ivec2 gCoord = groupOrigin + ivec2(sx - HALO, sy - HALO);
        gCoord = clamp(gCoord, ivec2(0), ivec2(uWidth - 1, uHeight - 1));

        uint rawBayer = texelFetch(uBayerTex, gCoord, 0).r;
        sBayer[sy][sx] = normalizePixel(rawBayer, gCoord.y, gCoord.x);

        sGreen[sy][sx] = imageLoad(uGreenImg, gCoord).r;
    }
    barrier();

    // Phase 2: Compute native color ratios for Red and Blue pixels (400 elements)
    for (int i = tid; i < 400; i += 256) {
        int sy = i / 20;
        int sx = i % 20;
        ivec2 gCoord = clamp(groupOrigin + ivec2(sx - HALO, sy - HALO), ivec2(0), ivec2(uWidth - 1, uHeight - 1));
        int color = getCfaColor(uCfaPattern, gCoord.y, gCoord.x);
        float gVal = max(sGreen[sy][sx], 1e-4);

        if (color == 0) {
            sRatioR[sy][sx] = sBayer[sy][sx] / gVal;
            sRatioB[sy][sx] = 0.0;
        } else if (color == 2) {
            sRatioR[sy][sx] = 0.0;
            sRatioB[sy][sx] = sBayer[sy][sx] / gVal;
        } else {
            sRatioR[sy][sx] = 0.0;
            sRatioB[sy][sx] = 0.0;
        }
    }
    barrier();

    // Phase 3: Interpolate opposite color ratio at Red and Blue pixels
    // Operates on the 18x18 interior region [1, 18] x [1, 18] (324 elements)
    for (int i = tid; i < 324; i += 256) {
        int sy = (i / 18) + 1;
        int sx = (i % 18) + 1;
        ivec2 gCoord = clamp(groupOrigin + ivec2(sx - HALO, sy - HALO), ivec2(0), ivec2(uWidth - 1, uHeight - 1));
        int color = getCfaColor(uCfaPattern, gCoord.y, gCoord.x);

        if (color == 0) {
            // Native Red pixel: interpolate missing Blue ratio from 4 diagonal Blue neighbors
            float rNW = sRatioB[sy-1][sx-1];
            float rNE = sRatioB[sy-1][sx+1];
            float rSW = sRatioB[sy+1][sx-1];
            float rSE = sRatioB[sy+1][sx+1];

            float gNW = sGreen[sy-1][sx-1];
            float gNE = sGreen[sy-1][sx+1];
            float gSW = sGreen[sy+1][sx-1];
            float gSE = sGreen[sy+1][sx+1];

            // Diagonal high-frequency color difference gradients
            float gradP = abs(gNW - gSE) + abs(rNW - rSE) * sGreen[sy][sx] + 1e-5;
            float gradQ = abs(gNE - gSW) + abs(rNE - rSW) * sGreen[sy][sx] + 1e-5;

            float wP = 1.0 / gradP;
            float wQ = 1.0 / gradQ;

            float estP = 0.5 * (rNW + rSE);
            float estQ = 0.5 * (rNE + rSW);

            sRatioB[sy][sx] = (wP * estP + wQ * estQ) / (wP + wQ);
        } else if (color == 2) {
            // Native Blue pixel: interpolate missing Red ratio from 4 diagonal Red neighbors
            float rNW = sRatioR[sy-1][sx-1];
            float rNE = sRatioR[sy-1][sx+1];
            float rSW = sRatioR[sy+1][sx-1];
            float rSE = sRatioR[sy+1][sx+1];

            float gNW = sGreen[sy-1][sx-1];
            float gNE = sGreen[sy-1][sx+1];
            float gSW = sGreen[sy+1][sx-1];
            float gSE = sGreen[sy+1][sx+1];

            float gradP = abs(gNW - gSE) + abs(rNW - rSE) * sGreen[sy][sx] + 1e-5;
            float gradQ = abs(gNE - gSW) + abs(rNE - rSW) * sGreen[sy][sx] + 1e-5;

            float wP = 1.0 / gradP;
            float wQ = 1.0 / gradQ;

            float estP = 0.5 * (rNW + rSE);
            float estQ = 0.5 * (rNE + rSW);

            sRatioR[sy][sx] = (wP * estP + wQ * estQ) / (wP + wQ);
        }
    }
    barrier();

    // Phase 4: Full channel recovery and output store (16x16 threads)
    ivec2 localId = ivec2(gl_LocalInvocationID.xy);
    ivec2 outCoord = groupOrigin + localId;
    if (outCoord.x >= uWidth || outCoord.y >= uHeight) {
        return;
    }

    int sy = localId.y + HALO;
    int sx = localId.x + HALO;
    int color = getCfaColor(uCfaPattern, outCoord.y, outCoord.x);

    float greenVal = sGreen[sy][sx];
    float redVal;
    float blueVal;

    if (color == 0) {
        // Red pixel: Red is native sensor sample; Blue is ratio-reconstructed
        redVal = sBayer[sy][sx];
        blueVal = clamp(sRatioB[sy][sx] * greenVal, 0.0, 1.0);
    } else if (color == 2) {
        // Blue pixel: Blue is native sensor sample; Red is ratio-reconstructed
        blueVal = sBayer[sy][sx];
        redVal = clamp(sRatioR[sy][sx] * greenVal, 0.0, 1.0);
    } else {
        // Green pixel: Interpolate Red and Blue ratios from cardinal neighbors
        float gN = sGreen[sy-1][sx];
        float gS = sGreen[sy+1][sx];
        float gW = sGreen[sy][sx-1];
        float gE = sGreen[sy][sx+1];

        // 1. Red channel cardinal reconstruction
        float rN = sRatioR[sy-1][sx];
        float rS = sRatioR[sy+1][sx];
        float rW = sRatioR[sy][sx-1];
        float rE = sRatioR[sy][sx+1];

        float gradH_R = abs(gW - gE) + abs(rW - rE) * greenVal + 1e-5;
        float gradV_R = abs(gN - gS) + abs(rN - rS) * greenVal + 1e-5;

        float wH_R = 1.0 / gradH_R;
        float wV_R = 1.0 / gradV_R;

        float estH_R = 0.5 * (rW + rE);
        float estV_R = 0.5 * (rN + rS);

        float ratioR = (wH_R * estH_R + wV_R * estV_R) / (wH_R + wV_R);
        redVal = clamp(ratioR * greenVal, 0.0, 1.0);

        // 2. Blue channel cardinal reconstruction
        float bN = sRatioB[sy-1][sx];
        float bS = sRatioB[sy+1][sx];
        float bW = sRatioB[sy][sx-1];
        float bE = sRatioB[sy][sx+1];

        float gradH_B = abs(gW - gE) + abs(bW - bE) * greenVal + 1e-5;
        float gradV_B = abs(gN - gS) + abs(bN - bS) * greenVal + 1e-5;

        float wH_B = 1.0 / gradH_B;
        float wV_B = 1.0 / gradV_B;

        float estH_B = 0.5 * (bW + bE);
        float estV_B = 0.5 * (bN + bS);

        float ratioB = (wH_B * estH_B + wV_B * estV_B) / (wH_B + wV_B);
        blueVal = clamp(ratioB * greenVal, 0.0, 1.0);
    }

    // Write RGBA16F output image containing fully demosaiced R, G, B channels
    imageStore(uOutRgbImg, outCoord, vec4(
        redVal,
        greenVal,
        blueVal,
        1.0
    ));
}
)glsl";

} // namespace gpu
} // namespace darkbag

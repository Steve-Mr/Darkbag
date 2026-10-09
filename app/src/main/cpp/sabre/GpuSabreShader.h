#pragma once

namespace darkbag {
namespace sabre {

/**
 * GLSL ES 3.1 Compute Shaders for GPU Sabre Multi-Frame Super-Resolution.
 *
 * Implements GPU-accelerated continuous subpixel sample regression directly
 * onto the target RGB grid, steered by local Structure Tensors and anisotropic
 * covariance metrics:
 *
 * - Pass 1: Green Quad Gradient & Anisotropic Steering Covariance Estimation
 * - Pass 2: Continuous Subpixel Gather Accumulation & Motion Rejection
 * - Pass 3: Dehomogenization, Guided Chrominance Recovery & Linear RGB Normalization
 * - Pass 4: Physical MTF Inverse Restoration via Noise-Gated Deconvolution
 */

// Pass 1: Structure Tensor & Steering Covariance Compute Shader (Half-resolution Quad Grid)
static const char* kSabreStructureTensorComputeShader = R"glsl(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

precision highp float;
precision highp int;

layout(binding = 0) uniform highp usampler2D uRefBayer;
layout(rgba16f, binding = 0) uniform highp writeonly image2D uCovImg;

uniform int uQuadWidth;
uniform int uQuadHeight;
uniform int uCfaPattern; // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
uniform float uZoomFactor;

float getGreenQuad(ivec2 qcoord) {
    ivec2 qc = clamp(qcoord, ivec2(0), ivec2(uQuadWidth - 1, uQuadHeight - 1));
    ivec2 bc = qc * 2;
    uint g0 = 0u;
    uint g1 = 0u;

    if (uCfaPattern == 0) { // RGGB: (1,0)=Gr, (0,1)=Gb
        g0 = texelFetch(uRefBayer, bc + ivec2(1, 0), 0).r;
        g1 = texelFetch(uRefBayer, bc + ivec2(0, 1), 0).r;
    } else if (uCfaPattern == 1) { // GRBG: (0,0)=Gr, (1,1)=Gb
        g0 = texelFetch(uRefBayer, bc + ivec2(0, 0), 0).r;
        g1 = texelFetch(uRefBayer, bc + ivec2(1, 1), 0).r;
    } else if (uCfaPattern == 2) { // GBRG: (0,0)=Gb, (1,1)=Gr
        g0 = texelFetch(uRefBayer, bc + ivec2(0, 0), 0).r;
        g1 = texelFetch(uRefBayer, bc + ivec2(1, 1), 0).r;
    } else { // BGGR: (1,0)=Gb, (0,1)=Gr
        g0 = texelFetch(uRefBayer, bc + ivec2(1, 0), 0).r;
        g1 = texelFetch(uRefBayer, bc + ivec2(0, 1), 0).r;
    }
    return 0.5 * (float(g0) + float(g1));
}

void main() {
    ivec2 qcoord = ivec2(gl_GlobalInvocationID.xy);
    if (qcoord.x >= uQuadWidth || qcoord.y >= uQuadHeight) {
        return;
    }

    // Central difference gradient in quad coordinates
    float gx = 0.5 * (getGreenQuad(qcoord + ivec2(1, 0)) - getGreenQuad(qcoord - ivec2(1, 0)));
    float gy = 0.5 * (getGreenQuad(qcoord + ivec2(0, 1)) - getGreenQuad(qcoord - ivec2(0, 1)));

    // Structure tensor components J = [J_xx, J_yy, J_xy]
    float jxx = gx * gx;
    float jyy = gy * gy;
    float jxy = gx * gy;

    // Eigenvalue decomposition
    float trace = jxx + jyy;
    float diff = jxx - jyy;
    float discriminant = sqrt(max(0.0, diff * diff + 4.0 * jxy * jxy));
    float lambda1 = 0.5 * (trace + discriminant); // Across edge (gradient)
    float lambda2 = 0.5 * (trace - discriminant); // Along edge (tangent)

    // Eigenvector 1 (gradient direction)
    float e1x = 1.0;
    float e1y = 0.0;
    if (abs(jxy) > 1e-4) {
        float norm = sqrt(jxy * jxy + (lambda1 - jxx) * (lambda1 - jxx));
        if (norm > 1e-6) {
            e1x = jxy / norm;
            e1y = (lambda1 - jxx) / norm;
        }
    } else if (jxx < jyy) {
        e1x = 0.0;
        e1y = 1.0;
    }
    // Eigenvector 2 (orthogonal, along edge)
    float e2x = -e1y;
    float e2y = e1x;

    // Coherence in [0, 1]
    float s1 = sqrt(max(0.0, lambda1));
    float s2 = sqrt(max(0.0, lambda2));
    float coherence = clamp((s1 - s2) / (s1 + s2 + 1e-5), 0.0, 1.0);

    // Dynamic kernel radii adaptation:
    // For 1x focal length (zoom <= 1.05x): compact sigma0 = 0.68, sharpened edge orientation
    // For zoom > 1.05x: smoothly interpolate sigma0 up to 0.85 matching super-resolution requirements
    float zoomT = smoothstep(1.05, 2.0, uZoomFactor);
    float sigma0 = mix(0.68, 0.85, zoomT);
    float kTan = mix(2.0, 1.8, zoomT);
    float kNorm = mix(1.2, 0.8, zoomT);

    float sigma_tangent = sigma0 * (1.0 + kTan * coherence);  // Elongate along edge
    float sigma_normal  = sigma0 / (1.0 + kNorm * coherence); // Narrow across edge

    float inv_s1_sq = 1.0 / (sigma_normal * sigma_normal);
    float inv_s2_sq = 1.0 / (sigma_tangent * sigma_tangent);

    // Covariance steering matrix Omega = R^T diag(inv_s1_sq, inv_s2_sq) R
    float covXX = inv_s1_sq * e1x * e1x + inv_s2_sq * e2x * e2x;
    float covYY = inv_s1_sq * e1y * e1y + inv_s2_sq * e2y * e2y;
    float covXY = inv_s1_sq * e1x * e1y + inv_s2_sq * e2x * e2y;

    imageStore(uCovImg, qcoord, vec4(covXX, covYY, covXY, coherence));
}
)glsl";

// Pass 2: Anisotropic Subpixel Gather Accumulation Compute Shader (Output Resolution Grid)
static const char* kSabreAccumulateComputeShader = R"glsl(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

precision highp float;
precision highp int;

layout(binding = 0) uniform highp usampler2D uRefBayer;
layout(binding = 1) uniform highp usampler2D uCandBayer;
layout(binding = 2) uniform highp sampler2D  uFlowTex;

layout(rgba16f, binding = 0) uniform highp readonly image2D uCovImg;
layout(rgba32f, binding = 1) uniform highp readonly image2D uInAccumImg;
layout(rgba32f, binding = 2) uniform highp readonly image2D uInWeightImg;
layout(rgba32f, binding = 3) uniform highp writeonly image2D uOutAccumImg;
layout(rgba32f, binding = 4) uniform highp writeonly image2D uOutWeightImg;

uniform int uWidth;
uniform int uHeight;
uniform int uSensorWidth;
uniform int uSensorHeight;
uniform int uQuadWidth;
uniform int uQuadHeight;
uniform int uCfaPattern; // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
uniform float uZoomFactor;
uniform float uCropXStart;
uniform float uCropYStart;
uniform vec2 uNoiseModel; // x = S (shot noise slope), y = O (read noise floor)
uniform int uIsRef;
uniform int uIsFirstFrame;
uniform int uHasFlow;
uniform int uFlowWidth;
uniform int uFlowHeight;

int getBayerChannel(int x, int y, int cfa) {
    int px = x & 1;
    int py = y & 1;
    if (cfa == 0) { // RGGB
        if (py == 0) return (px == 0) ? 0 : 1;
        else         return (px == 0) ? 1 : 2;
    } else if (cfa == 1) { // GRBG
        if (py == 0) return (px == 0) ? 1 : 0;
        else         return (px == 0) ? 2 : 1;
    } else if (cfa == 2) { // GBRG
        if (py == 0) return (px == 0) ? 1 : 2;
        else         return (px == 0) ? 0 : 1;
    } else { // BGGR
        if (py == 0) return (px == 0) ? 2 : 1;
        else         return (px == 0) ? 1 : 0;
    }
}

void main() {
    ivec2 outCoord = ivec2(gl_GlobalInvocationID.xy);
    if (outCoord.x >= uWidth || outCoord.y >= uHeight) {
        return;
    }

    int X = outCoord.x;
    int Y = outCoord.y;

    // Continuous reference sensor space
    float sx0 = float(X) / uZoomFactor + uCropXStart;
    float sy0 = float(Y) / uZoomFactor + uCropYStart;

    // Optical flow lookup (dx, dy)
    float dx = 0.0;
    float dy = 0.0;
    if (uIsRef == 0 && uHasFlow != 0) {
        float fx = (sx0 / float(uSensorWidth)) * float(uFlowWidth);
        float fy = (sy0 / float(uSensorHeight)) * float(uFlowHeight);
        int fix = clamp(int(fx), 0, uFlowWidth - 1);
        int fiy = clamp(int(fy), 0, uFlowHeight - 1);
        vec2 flow = texelFetch(uFlowTex, ivec2(fix, fiy), 0).xy;
        dx = flow.x;
        dy = flow.y;
    }

    // Candidate sensor center corresponding to reference (sx0, sy0)
    float cand_center_x = sx0 + dx;
    float cand_center_y = sy0 + dy;

    // Kernel radius
    float kernelRadius = max(1.5, uZoomFactor * 1.25);
    float kernelRadiusSq = kernelRadius * kernelRadius;
    float r_sensor = kernelRadius / uZoomFactor;

    int cx_min = max(0, int(floor(cand_center_x - r_sensor)));
    int cx_max = min(uSensorWidth - 1, int(ceil(cand_center_x + r_sensor)));
    int cy_min = max(0, int(floor(cand_center_y - r_sensor)));
    int cy_max = min(uSensorHeight - 1, int(ceil(cand_center_y + r_sensor)));

    // Steering covariance Omega at quad location
    int qx = clamp(int(sx0 * 0.5), 0, uQuadWidth - 1);
    int qy = clamp(int(sy0 * 0.5), 0, uQuadHeight - 1);
    vec4 cov = imageLoad(uCovImg, ivec2(qx, qy));
    float oxx = cov.x;
    float oyy = cov.y;
    float oxy = cov.z;

    float addAccumR = 0.0;
    float addWeightR = 0.0;
    float addAccumG = 0.0;
    float addWeightG = 0.0;
    float addAccumB = 0.0;
    float addWeightB = 0.0;

    for (int cy = cy_min; cy <= cy_max; ++cy) {
        float dty = float(cy) - cand_center_y; // sensor scale
        float dty_target = dty * uZoomFactor;

        for (int cx = cx_min; cx <= cx_max; ++cx) {
            float dtx = float(cx) - cand_center_x; // sensor scale
            float dtx_target = dtx * uZoomFactor;

            // Euclidean distance coarse screening in target space
            if (dtx_target * dtx_target + dty_target * dty_target > kernelRadiusSq) {
                continue;
            }

            // Anisotropic Mahalanobis distance d2 = delta^T Omega delta
            float d2 = dtx * dtx * oxx + dty * dty * oyy + 2.0 * dtx * dty * oxy;
            float w_spatial = exp2(-d2 * 0.72);

            float candVal = float(texelFetch(uCandBayer, ivec2(cx, cy), 0).r);
            float w_motion = 1.0;

            if (uIsRef == 0) {
                // Aligned position on reference sensor
                float sx = float(cx) - dx;
                float sy = float(cy) - dy;
                int px = cx & 1;
                int py = cy & 1;
                int ref_x = 2 * int(floor((sx - float(px)) * 0.5 + 0.5)) + px;
                int ref_y = 2 * int(floor((sy - float(py)) * 0.5 + 0.5)) + py;
                int min_x = px;
                int max_x = (uSensorWidth - 1) - (((uSensorWidth - 1) ^ px) & 1);
                int min_y = py;
                int max_y = (uSensorHeight - 1) - (((uSensorHeight - 1) ^ py) & 1);
                ref_x = clamp(ref_x, min_x, max_x);
                ref_y = clamp(ref_y, min_y, max_y);

                // Local mean and spatial variance over same-channel 3x3 neighborhood
                float vals[9];
                int count = 0;
                float sumVal = 0.0;
                for (int dy_off = -2; dy_off <= 2; dy_off += 2) {
                    int ny = ref_y + dy_off;
                    if (ny < 0 || ny >= uSensorHeight) continue;
                    for (int dx_off = -2; dx_off <= 2; dx_off += 2) {
                        int nx = ref_x + dx_off;
                        if (nx < 0 || nx >= uSensorWidth) continue;
                        float v = float(texelFetch(uRefBayer, ivec2(nx, ny), 0).r);
                        vals[count] = v;
                        sumVal += v;
                        count++;
                    }
                }

                float mean_ref = (count > 0) ? (sumVal / float(count)) : 0.0;
                float sumSqDiff = 0.0;
                for (int i = 0; i < count; ++i) {
                    float d = vals[i] - mean_ref;
                    sumSqDiff += d * d;
                }
                float var_spatial = (count > 0) ? (sumSqDiff / float(count)) : 0.0;

                const float eps = 1e-4;
                float var_total = var_spatial + (uNoiseModel.x * mean_ref + uNoiseModel.y) + eps;
                float diffVal = candVal - mean_ref;
                float distSq = (diffVal * diffVal) / var_total;

                w_motion = exp(-max(0.0, distSq - 4.0) * 0.5);
                if (w_motion < 0.01) {
                    w_motion = 0.0;
                }
            }

            float w = w_spatial * w_motion;
            if (w < 0.001) continue;

            int channel = getBayerChannel(cx, cy, uCfaPattern);
            if (channel == 0) {
                addAccumR += w * candVal;
                addWeightR += w;
            } else if (channel == 1) {
                addAccumG += w * candVal;
                addWeightG += w;
            } else {
                addAccumB += w * candVal;
                addWeightB += w;
            }
        }
    }

    vec4 prevVal = vec4(0.0);
    vec4 prevWeight = vec4(0.0);
    if (uIsFirstFrame == 0) {
        prevVal = imageLoad(uInAccumImg, outCoord);
        prevWeight = imageLoad(uInWeightImg, outCoord);
    }

    vec4 newVal = prevVal + vec4(addAccumR, addAccumG, addAccumB, 0.0);
    vec4 newWeight = prevWeight + vec4(addWeightR, addWeightG, addWeightB, 0.0);

    imageStore(uOutAccumImg, outCoord, newVal);
    imageStore(uOutWeightImg, outCoord, newWeight);
}
)glsl";

// Pass 3: Resolve & Guided Color Difference Reconstruction Compute Shader
static const char* kSabreResolveComputeShader = R"glsl(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

precision highp float;
precision highp int;

layout(binding = 0) uniform highp usampler2D uRefBayer;

layout(rgba32f, binding = 0) uniform highp readonly image2D uInAccumImg;
layout(rgba32f, binding = 1) uniform highp readonly image2D uInWeightImg;
layout(rgba16f, binding = 2) uniform highp writeonly image2D uOutRgbImg;

uniform int uWidth;
uniform int uHeight;
uniform int uCfaPattern; // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
uniform float uWhiteLevel;
uniform vec4 uBlackLevel; // r, g, b, 0.0

int getBayerChannel(int x, int y, int cfa) {
    int px = x & 1;
    int py = y & 1;
    if (cfa == 0) { // RGGB
        if (py == 0) return (px == 0) ? 0 : 1;
        else         return (px == 0) ? 1 : 2;
    } else if (cfa == 1) { // GRBG
        if (py == 0) return (px == 0) ? 1 : 0;
        else         return (px == 0) ? 2 : 1;
    } else if (cfa == 2) { // GBRG
        if (py == 0) return (px == 0) ? 1 : 2;
        else         return (px == 0) ? 0 : 1;
    } else { // BGGR
        if (py == 0) return (px == 0) ? 2 : 1;
        else         return (px == 0) ? 1 : 0;
    }
}

ivec2 clampBayerCoord(ivec2 p) {
    int x = p.x;
    int y = p.y;
    if (x < 0) {
        int rem = (x % 2 + 2) % 2;
        x = (rem == 0) ? 0 : 1;
    } else if (x >= uWidth) {
        int rem = (x % 2 + 2) % 2;
        int lastRem = ((uWidth - 1) % 2 + 2) % 2;
        x = (rem == lastRem) ? (uWidth - 1) : (uWidth - 2);
    }
    if (y < 0) {
        int rem = (y % 2 + 2) % 2;
        y = (rem == 0) ? 0 : 1;
    } else if (y >= uHeight) {
        int rem = (y % 2 + 2) % 2;
        int lastRem = ((uHeight - 1) % 2 + 2) % 2;
        y = (rem == lastRem) ? (uHeight - 1) : (uHeight - 2);
    }
    return ivec2(x, y);
}

float sampleRef(ivec2 p) {
    ivec2 cp = clampBayerCoord(p);
    return float(texelFetch(uRefBayer, cp, 0).r);
}

vec3 computeRcdPrior(ivec2 coord) {
    float refVal = sampleRef(coord);
    int refChan = getBayerChannel(coord.x, coord.y, uCfaPattern);

    float priorR = 0.0;
    float priorG = 0.0;
    float priorB = 0.0;

    if (refChan == 1) {
        // Native Green pixel: green is directly sampled from reference Bayer
        priorG = refVal;

        int hChan = getBayerChannel(coord.x + 1, coord.y, uCfaPattern);

        float h0 = sampleRef(coord - ivec2(1, 0));
        float h1 = sampleRef(coord + ivec2(1, 0));
        float gH0 = sampleRef(coord - ivec2(2, 0));
        float gH1 = sampleRef(coord + ivec2(2, 0));

        float v0 = sampleRef(coord - ivec2(0, 1));
        float v1 = sampleRef(coord + ivec2(0, 1));
        float gV0 = sampleRef(coord - ivec2(0, 2));
        float gV1 = sampleRef(coord + ivec2(0, 2));

        // Second-order Laplacian color-difference estimates
        float estH = 0.5 * (h0 + h1) + 0.25 * (2.0 * refVal - gH0 - gH1);
        float estV = 0.5 * (v0 + v1) + 0.25 * (2.0 * refVal - gV0 - gV1);

        if (hChan == 0) { // Horizontal is Red, Vertical is Blue
            priorR = estH;
            priorB = estV;
        } else {          // Horizontal is Blue, Vertical is Red
            priorR = estV;
            priorB = estH;
        }
    } else {
        // Native Red (refChan == 0) or Blue (refChan == 2) pixel
        float gW = sampleRef(coord - ivec2(1, 0));
        float gE = sampleRef(coord + ivec2(1, 0));
        float gN = sampleRef(coord - ivec2(0, 1));
        float gS = sampleRef(coord + ivec2(0, 1));

        float cW2 = sampleRef(coord - ivec2(2, 0));
        float cE2 = sampleRef(coord + ivec2(2, 0));
        float cN2 = sampleRef(coord - ivec2(0, 2));
        float cS2 = sampleRef(coord + ivec2(0, 2));

        // Directional Green estimates with Laplacian color-difference correction
        float estGH = 0.5 * (gW + gE) + 0.25 * (2.0 * refVal - cW2 - cE2);
        float gradH = abs(gW - gE) + abs(2.0 * refVal - cW2 - cE2);

        float estGV = 0.5 * (gN + gS) + 0.25 * (2.0 * refVal - cN2 - cS2);
        float gradV = abs(gN - gS) + abs(2.0 * refVal - cN2 - cS2);

        float wH = 1.0 / (1.0 + gradH);
        float wV = 1.0 / (1.0 + gradV);
        priorG = (wH * estGH + wV * estGV) / (wH + wV);

        // Diagonal opposite-color neighbors
        float opNW = sampleRef(coord + ivec2(-1, -1));
        float opNE = sampleRef(coord + ivec2(1, -1));
        float opSW = sampleRef(coord + ivec2(-1, 1));
        float opSE = sampleRef(coord + ivec2(1, 1));

        float gNW = 0.5 * (gW + gN);
        float gNE = 0.5 * (gE + gN);
        float gSW = 0.5 * (gW + gS);
        float gSE = 0.5 * (gE + gS);

        float diffNW = opNW - gNW;
        float diffNE = opNE - gNE;
        float diffSW = opSW - gSW;
        float diffSE = opSE - gSE;

        float gradP = abs(opNW - opSE) + abs(gNW - gSE);
        float gradQ = abs(opNE - opSW) + abs(gNE - gSW);

        float wP = 1.0 / (1.0 + gradP);
        float wQ = 1.0 / (1.0 + gradQ);

        float estDiffP = 0.5 * (diffNW + diffSE);
        float estDiffQ = 0.5 * (diffNE + diffSW);
        float oppDiff = (wP * estDiffP + wQ * estDiffQ) / (wP + wQ);
        float oppVal = priorG + oppDiff;

        if (refChan == 0) { // Native Red
            priorR = refVal;
            priorB = oppVal;
        } else {          // Native Blue
            priorB = refVal;
            priorR = oppVal;
        }
    }

    return vec3(priorR, priorG, priorB);
}

void main() {
    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
    if (coord.x >= uWidth || coord.y >= uHeight) {
        return;
    }

    vec4 accum = imageLoad(uInAccumImg, coord);
    vec4 weight = imageLoad(uInWeightImg, coord);

    float wr = weight.r;
    float wg = weight.g;
    float wb = weight.b;

    float bl_r = uBlackLevel.r;
    float bl_g = uBlackLevel.g;
    float bl_b = uBlackLevel.b;
    float wl = uWhiteLevel;

    float normR = (wr > 0.0001) ? (accum.r / wr) : bl_r;
    float normG = (wg > 0.0001) ? (accum.g / wg) : bl_g;
    float normB = (wb > 0.0001) ? (accum.b / wb) : bl_b;

    float rOut = normR;
    float gOut = normG;
    float bOut = normB;

    // Single-frame RCD (Ratio-preserving / Color-difference) reference prior regularizer:
    // When multi-frame weights are abundant (w >= 1.0), 100% Sabre physical full-color demosaicing is used.
    // When multi-frame weights are sparse (w < 1.0), RCD prior smoothly regularizes the result.
    if (wr < 1.0 || wg < 1.0 || wb < 1.0) {
        vec3 rcdPrior = computeRcdPrior(coord);
        rcdPrior.r = clamp(rcdPrior.r, bl_r, wl);
        rcdPrior.g = clamp(rcdPrior.g, bl_g, wl);
        rcdPrior.b = clamp(rcdPrior.b, bl_b, wl);

        float confR = clamp(wr, 0.0, 1.0);
        float confG = clamp(wg, 0.0, 1.0);
        float confB = clamp(wb, 0.0, 1.0);

        rOut = mix(rcdPrior.r, rOut, confR);
        gOut = mix(rcdPrior.g, gOut, confG);
        bOut = mix(rcdPrior.b, bOut, confB);
    }

    // Black level subtraction and normalization to [0.0, 1.0]
    float scale_r = 1.0 / max(1.0, wl - bl_r);
    float scale_g = 1.0 / max(1.0, wl - bl_g);
    float scale_b = 1.0 / max(1.0, wl - bl_b);

    float finalR = clamp((rOut - bl_r) * scale_r, 0.0, 1.0);
    float finalG = clamp((gOut - bl_g) * scale_g, 0.0, 1.0);
    float finalB = clamp((bOut - bl_b) * scale_b, 0.0, 1.0);

    imageStore(uOutRgbImg, coord, vec4(finalR, finalG, finalB, 1.0));
}
)glsl";

// Pass 4: Physical MTF Inverse Restoration Compute Shader (Noise-Gated Deconvolution)
static const char* kSabreMtfRestorationComputeShader = R"glsl(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

precision highp float;
precision highp int;

layout(rgba16f, binding = 0) uniform highp readonly image2D uInRgbImg;
layout(rgba16f, binding = 1) uniform highp writeonly image2D uOutRgbImg;

uniform int uWidth;
uniform int uHeight;
uniform vec2 uNoiseModel; // x = normalized S (shot noise slope), y = normalized O (read noise floor)
uniform float uSharpenStrength; // default 1.25
uniform float uCoringThreshold; // default 2.0

const vec3 kRec709 = vec3(0.2126, 0.7152, 0.0722);

void main() {
    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
    if (coord.x >= uWidth || coord.y >= uHeight) {
        return;
    }

    vec4 centerPixel = imageLoad(uInRgbImg, coord);
    vec3 centerRgb = centerPixel.rgb;
    float Y = dot(centerRgb, kRec709);

    float yBlur = 0.0;
    float yMin = Y;
    float yMax = Y;

    // 3x3 local Gaussian blur filter ([1, 2, 1] / 4 separable filter, normalized by 16.0)
    for (int dy = -1; dy <= 1; ++dy) {
        float wy = (dy == 0) ? 2.0 : 1.0;
        for (int dx = -1; dx <= 1; ++dx) {
            float wx = (dx == 0) ? 2.0 : 1.0;
            float w = (wx * wy) * 0.0625; // (wx * wy) / 16.0

            ivec2 nc = clamp(coord + ivec2(dx, dy), ivec2(0), ivec2(uWidth - 1, uHeight - 1));
            vec3 rgb = imageLoad(uInRgbImg, nc).rgb;
            float yVal = dot(rgb, kRec709);

            yBlur += w * yVal;
            yMin = min(yMin, yVal);
            yMax = max(yMax, yVal);
        }
    }

    // High-frequency detail Delta Y = Y - Y_blur
    float deltaY = Y - yBlur;

    // Physical noise standard deviation: sigma = sqrt(max(10^-7, S * Y + O))
    float sigma = sqrt(max(1e-7, uNoiseModel.x * Y + uNoiseModel.y));

    // Continuous noise coring: Delta Y_cored = sign(Delta Y) * max(0.0, |Delta Y| - uCoringThreshold * sigma)
    float deltaYCored = sign(deltaY) * max(0.0, abs(deltaY) - uCoringThreshold * sigma);

    // Wiener regularization damping: gain = Delta Y^2 / (Delta Y^2 + sigma^2 + 10^-6)
    float deltaYSq = deltaY * deltaY;
    float gain = deltaYSq / (deltaYSq + sigma * sigma + 1e-6);

    // Clamped luminance boost Delta Y_final = uSharpenStrength * gain * Delta Y_cored
    float deltaYFinal = uSharpenStrength * gain * deltaYCored;

    // Clamp Y_new to [Y_min, Y_max] of 3x3 neighborhood to strictly prevent halos/ringing
    float yNew = clamp(Y + deltaYFinal, yMin, yMax);

    // Scale RGB preserving chrominance: factor = Y_new / max(10^-4, Y)
    float factor = yNew / max(1e-4, Y);
    vec3 outRgb = clamp(centerRgb * factor, 0.0, 1.0);

    imageStore(uOutRgbImg, coord, vec4(outRgb, 1.0));
}
)glsl";

} // namespace sabre
} // namespace darkbag

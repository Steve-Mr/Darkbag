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

    // Anisotropic steering kernel radii (in quad pixel units)
    const float sigma0 = 0.85;
    float sigma_tangent = sigma0 * (1.0 + 1.8 * coherence);  // Elongate along edge
    float sigma_normal  = sigma0 / (1.0 + 0.8 * coherence);  // Narrow across edge

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

layout(rgba32f, binding = 0) uniform highp readonly image2D uInAccumImg;
layout(rgba32f, binding = 1) uniform highp readonly image2D uInWeightImg;
layout(rgba16f, binding = 2) uniform highp writeonly image2D uOutRgbImg;

uniform int uWidth;
uniform int uHeight;
uniform float uWhiteLevel;
uniform vec4 uBlackLevel; // r, g, b, 0.0

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

    float normR = accum.r / max(0.001, wr);
    float normG = accum.g / max(0.001, wg);
    float normB = accum.b / max(0.001, wb);

    float bl_r = uBlackLevel.r;
    float bl_g = uBlackLevel.g;
    float bl_b = uBlackLevel.b;
    float wl = uWhiteLevel;

    float rOut = normR;
    float gOut = normG;
    float bOut = normB;

    // Cross-Channel Guided Chrominance Filtering for sparse sites
    if (wr < 0.25 || wb < 0.25) {
        float sumDiffR = 0.0;
        float sumWeightR = 0.0;
        float sumDiffB = 0.0;
        float sumWeightB = 0.0;

        for (int dy = -2; dy <= 2; ++dy) {
            int ny = clamp(coord.y + dy, 0, uHeight - 1);
            for (int dx = -2; dx <= 2; ++dx) {
                int nx = clamp(coord.x + dx, 0, uWidth - 1);
                ivec2 ncoord = ivec2(nx, ny);

                vec4 nAccum = imageLoad(uInAccumImg, ncoord);
                vec4 nWeight = imageLoad(uInWeightImg, ncoord);

                float gNeighbor = nAccum.g / max(0.001, nWeight.g);
                float guideWeight = 1.0 / (1.0 + abs(gNeighbor - normG) * 0.05);

                if (nWeight.r > 0.05) {
                    float rNeighbor = nAccum.r / max(0.001, nWeight.r);
                    sumDiffR += guideWeight * (rNeighbor - gNeighbor);
                    sumWeightR += guideWeight;
                }
                if (nWeight.b > 0.05) {
                    float bNeighbor = nAccum.b / max(0.001, nWeight.b);
                    sumDiffB += guideWeight * (bNeighbor - gNeighbor);
                    sumWeightB += guideWeight;
                }
            }
        }

        if (wr < 0.25) {
            if (sumWeightR > 0.0) {
                rOut = clamp(normG + sumDiffR / sumWeightR, bl_r, wl);
            } else {
                rOut = (wr > 0.001) ? clamp(normR, bl_r, wl) : bl_r;
            }
        }
        if (wb < 0.25) {
            if (sumWeightB > 0.0) {
                bOut = clamp(normG + sumDiffB / sumWeightB, bl_b, wl);
            } else {
                bOut = (wb > 0.001) ? clamp(normB, bl_b, wl) : bl_b;
            }
        }
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

} // namespace sabre
} // namespace darkbag

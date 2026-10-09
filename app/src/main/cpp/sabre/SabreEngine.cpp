#include "SabreEngine.h"
#include <android/log.h>
#include <omp.h>
#include <cmath>
#include <cstring>
#include <algorithm>

#define TAG "SabreEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace sabre {

namespace {

inline float clampf(float v, float lo, float hi) {
    return std::max(lo, std::min(hi, v));
}

inline float fast_exp2(float p) {
    // Fast approx for exp2(p) where p <= 0
    if (p < -16.0f) return 0.0f;
    return std::exp2(p);
}

} // namespace

SabreEngine::SabreEngine(const SabreConfig& config)
    : m_config(config),
      m_width(config.width),
      m_height(config.height),
      m_numPixels(static_cast<size_t>(config.width) * config.height),
      m_framesAccumulated(0)
{
    float z = std::max(1.0f, m_config.zoomFactor);
    m_config.zoomFactor = z;

    m_cropWidth = static_cast<float>(m_width) / z;
    m_cropHeight = static_cast<float>(m_height) / z;
    m_cropXStart = (static_cast<float>(m_width) - m_cropWidth) * 0.5f;
    m_cropYStart = (static_cast<float>(m_height) - m_cropHeight) * 0.5f;

    m_quadWidth = m_width / 2;
    m_quadHeight = m_height / 2;
    size_t quadCount = static_cast<size_t>(m_quadWidth) * m_quadHeight;

    m_covXX.resize(quadCount, 1.0f);
    m_covYY.resize(quadCount, 1.0f);
    m_covXY.resize(quadCount, 0.0f);

    m_accumR.resize(m_numPixels, 0.0f);
    m_accumG.resize(m_numPixels, 0.0f);
    m_accumB.resize(m_numPixels, 0.0f);
    m_weightR.resize(m_numPixels, 0.0f);
    m_weightG.resize(m_numPixels, 0.0f);
    m_weightB.resize(m_numPixels, 0.0f);

    LOGD("SabreEngine initialized: %dx%d, CFA=%d, Zoom=%.2fx (crop: %.1fx%.1f at (%.1f, %.1f))",
         m_width, m_height, static_cast<int>(m_config.cfa), z,
         m_cropWidth, m_cropHeight, m_cropXStart, m_cropYStart);
}

SabreEngine::~SabreEngine() = default;

int SabreEngine::getBayerChannel(int x, int y) const {
    int px = x & 1;
    int py = y & 1;
    switch (m_config.cfa) {
        case CfaPattern::RGGB:
            if (py == 0) return (px == 0) ? 0 : 1; // R, G
            else         return (px == 0) ? 1 : 2; // G, B
        case CfaPattern::GRBG:
            if (py == 0) return (px == 0) ? 1 : 0; // G, R
            else         return (px == 0) ? 2 : 1; // B, G
        case CfaPattern::GBRG:
            if (py == 0) return (px == 0) ? 1 : 2; // G, B
            else         return (px == 0) ? 0 : 1; // R, G
        case CfaPattern::BGGR:
            if (py == 0) return (px == 0) ? 2 : 1; // B, G
            else         return (px == 0) ? 1 : 0; // G, R
    }
    return 1;
}

void SabreEngine::computeStructureTensorAndCovariance() {
    if (m_refBayer.empty()) return;

    // 1. Extract half-resolution green channel image for gradient computation
    std::vector<float> greenQuad(static_cast<size_t>(m_quadWidth) * m_quadHeight, 0.0f);

    #pragma omp parallel for schedule(static)
    for (int qy = 0; qy < m_quadHeight; ++qy) {
        for (int qx = 0; qx < m_quadWidth; ++qx) {
            int bx = qx * 2;
            int by = qy * 2;
            float g0 = 0.0f, g1 = 0.0f;
            switch (m_config.cfa) {
                case CfaPattern::RGGB:
                    g0 = static_cast<float>(m_refBayer[by * m_width + (bx + 1)]);
                    g1 = static_cast<float>(m_refBayer[(by + 1) * m_width + bx]);
                    break;
                case CfaPattern::GRBG:
                    g0 = static_cast<float>(m_refBayer[by * m_width + bx]);
                    g1 = static_cast<float>(m_refBayer[(by + 1) * m_width + (bx + 1)]);
                    break;
                case CfaPattern::GBRG:
                    g0 = static_cast<float>(m_refBayer[by * m_width + bx]);
                    g1 = static_cast<float>(m_refBayer[(by + 1) * m_width + (bx + 1)]);
                    break;
                case CfaPattern::BGGR:
                    g0 = static_cast<float>(m_refBayer[by * m_width + (bx + 1)]);
                    g1 = static_cast<float>(m_refBayer[(by + 1) * m_width + bx]);
                    break;
            }
            greenQuad[qy * m_quadWidth + qx] = 0.5f * (g0 + g1);
        }
    }

    // 2. Compute Structure Tensor J and Anisotropic Steering Covariance Matrix Omega
    #pragma omp parallel for schedule(dynamic, 16)
    for (int qy = 0; qy < m_quadHeight; ++qy) {
        for (int qx = 0; qx < m_quadWidth; ++qx) {
            // Central difference gradient in quad coordinates
            int qx_m = std::max(0, qx - 1);
            int qx_p = std::min(m_quadWidth - 1, qx + 1);
            int qy_m = std::max(0, qy - 1);
            int qy_p = std::min(m_quadHeight - 1, qy + 1);

            float gx = 0.5f * (greenQuad[qy * m_quadWidth + qx_p] - greenQuad[qy * m_quadWidth + qx_m]);
            float gy = 0.5f * (greenQuad[qy_p * m_quadWidth + qx] - greenQuad[qy_m * m_quadWidth + qx]);

            // Structure tensor components
            float jxx = gx * gx;
            float jyy = gy * gy;
            float jxy = gx * gy;

            // Eigenvalue decomposition
            float trace = jxx + jyy;
            float diff = jxx - jyy;
            float discriminant = std::sqrt(std::max(0.0f, diff * diff + 4.0f * jxy * jxy));
            float lambda1 = 0.5f * (trace + discriminant); // Across edge (gradient)
            float lambda2 = 0.5f * (trace - discriminant); // Along edge (tangent)

            // Eigenvector 1 (gradient direction)
            float e1x = 1.0f, e1y = 0.0f;
            if (std::abs(jxy) > 1e-4f) {
                float norm = std::sqrt(jxy * jxy + (lambda1 - jxx) * (lambda1 - jxx));
                if (norm > 1e-6f) {
                    e1x = jxy / norm;
                    e1y = (lambda1 - jxx) / norm;
                }
            } else if (jxx < jyy) {
                e1x = 0.0f;
                e1y = 1.0f;
            }
            // Eigenvector 2 (orthogonal, along edge)
            float e2x = -e1y;
            float e2y = e1x;

            // Coherence in [0, 1]
            float s1 = std::sqrt(std::max(0.0f, lambda1));
            float s2 = std::sqrt(std::max(0.0f, lambda2));
            float coherence = (s1 - s2) / (s1 + s2 + 1e-5f);
            coherence = clampf(coherence, 0.0f, 1.0f);

            // Anisotropic steering kernel radii (in quad pixel units)
            // Base radius sigma0 ~ 0.85 quad pixels
            const float sigma0 = 0.85f;
            float sigma_tangent = sigma0 * (1.0f + 1.8f * coherence);  // Elongate along edge
            float sigma_normal  = sigma0 / (1.0f + 0.8f * coherence);  // Narrow across edge

            float inv_s1_sq = 1.0f / (sigma_normal * sigma_normal);
            float inv_s2_sq = 1.0f / (sigma_tangent * sigma_tangent);

            // Covariance steering matrix Omega = R^T diag(inv_s1_sq, inv_s2_sq) R
            size_t qidx = static_cast<size_t>(qy) * m_quadWidth + qx;
            m_covXX[qidx] = inv_s1_sq * e1x * e1x + inv_s2_sq * e2x * e2x;
            m_covYY[qidx] = inv_s1_sq * e1y * e1y + inv_s2_sq * e2y * e2y;
            m_covXY[qidx] = inv_s1_sq * e1x * e1y + inv_s2_sq * e2x * e2y;
        }
    }
}

bool SabreEngine::setReferenceFrame(const uint16_t* refBayer) {
    if (!refBayer) return false;

    m_refBayer.assign(refBayer, refBayer + m_numPixels);
    computeStructureTensorAndCovariance();

    // Accumulate Frame 0 (zero optical flow)
    bool ok = accumulateFrame(refBayer, nullptr, nullptr, 0, 0);
    LOGD("SabreEngine: reference frame 0 set and accumulated (ok=%d)", ok);
    return ok;
}

bool SabreEngine::accumulateFrame(
    const uint16_t* altBayer,
    const float* flowX,
    const float* flowY,
    int flowWidth,
    int flowHeight
) {
    if (!altBayer) return false;

    const float z = m_config.zoomFactor;
    const float noiseS = m_config.noiseModelS;
    const float noiseO = m_config.noiseModelO;
    const bool isRef = (m_framesAccumulated == 0);
    const float kernelRadius = std::max(1.5f, m_config.zoomFactor * 1.25f);
    const float kernelRadiusSq = kernelRadius * kernelRadius;

    // Bounding box of crop in sensor pixels (with 2px margin)
    int ySensorMin = std::max(0, static_cast<int>(std::floor(m_cropYStart)) - 2);
    int ySensorMax = std::min(m_height - 1, static_cast<int>(std::ceil(m_cropYStart + m_cropHeight)) + 2);
    int xSensorMin = std::max(0, static_cast<int>(std::floor(m_cropXStart)) - 2);
    int xSensorMax = std::min(m_width - 1, static_cast<int>(std::ceil(m_cropXStart + m_cropWidth)) + 2);

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = ySensorMin; y <= ySensorMax; ++y) {
        for (int x = xSensorMin; x <= xSensorMax; ++x) {
            float dx = 0.0f;
            float dy = 0.0f;

            if (!isRef && flowX && flowY && flowWidth > 0 && flowHeight > 0) {
                // Map sensor pixel (x, y) to flow tile grid
                float fx = (static_cast<float>(x) / m_width) * flowWidth;
                float fy = (static_cast<float>(y) / m_height) * flowHeight;
                int ix = std::clamp(static_cast<int>(fx), 0, flowWidth - 1);
                int iy = std::clamp(static_cast<int>(fy), 0, flowHeight - 1);
                size_t flowIdx = static_cast<size_t>(iy) * flowWidth + ix;
                dx = flowX[flowIdx];
                dy = flowY[flowIdx];
            }

            // Aligned position on the reference sensor coordinate space
            float sx = static_cast<float>(x) - dx;
            float sy = static_cast<float>(y) - dy;

            // Map from reference sensor space to target output grid
            float tx = (sx - m_cropXStart) * z;
            float ty = (sy - m_cropYStart) * z;

            // Target neighborhood radius
            int itx_min = std::max(0, static_cast<int>(std::floor(tx - kernelRadius)));
            int itx_max = std::min(m_width - 1, static_cast<int>(std::ceil(tx + kernelRadius)));
            int ity_min = std::max(0, static_cast<int>(std::floor(ty - kernelRadius)));
            int ity_max = std::min(m_height - 1, static_cast<int>(std::ceil(ty + kernelRadius)));

            if (itx_min > itx_max || ity_min > ity_max) continue;

            // Anisotropic steering matrix at quad location
            int qx = std::clamp(static_cast<int>(sx * 0.5f), 0, m_quadWidth - 1);
            int qy = std::clamp(static_cast<int>(sy * 0.5f), 0, m_quadHeight - 1);
            size_t qidx = static_cast<size_t>(qy) * m_quadWidth + qx;
            float oxx = m_covXX[qidx];
            float oyy = m_covYY[qidx];
            float oxy = m_covXY[qidx];

            // Bayer channel for this sample
            int channel = getBayerChannel(x, y);
            float rawVal = static_cast<float>(altBayer[y * m_width + x]);

            // Motion rejection against reference frame
            float w_motion = 1.0f;
            if (!isRef && !m_refBayer.empty()) {
                int px = x & 1;
                int py = y & 1;
                int ref_x = 2 * static_cast<int>(std::round((sx - px) * 0.5f)) + px;
                int ref_y = 2 * static_cast<int>(std::round((sy - py) * 0.5f)) + py;
                int min_x = px;
                int max_x = (m_width - 1) - (((m_width - 1) ^ px) & 1);
                int min_y = py;
                int max_y = (m_height - 1) - (((m_height - 1) ^ py) & 1);
                ref_x = std::clamp(ref_x, min_x, max_x);
                ref_y = std::clamp(ref_y, min_y, max_y);

                // Local mean and spatial variance over same-channel 3x3 neighborhood
                float vals[9];
                int count = 0;
                float sumVal = 0.0f;
                for (int dy_off = -2; dy_off <= 2; dy_off += 2) {
                    int ny = ref_y + dy_off;
                    if (ny < 0 || ny >= m_height) continue;
                    for (int dx_off = -2; dx_off <= 2; dx_off += 2) {
                        int nx = ref_x + dx_off;
                        if (nx < 0 || nx >= m_width) continue;
                        float v = static_cast<float>(m_refBayer[static_cast<size_t>(ny) * m_width + nx]);
                        vals[count++] = v;
                        sumVal += v;
                    }
                }

                float mean_ref = (count > 0) ? (sumVal / static_cast<float>(count)) : 0.0f;
                float sumSqDiff = 0.0f;
                for (int i = 0; i < count; ++i) {
                    float d = vals[i] - mean_ref;
                    sumSqDiff += d * d;
                }
                float var_spatial = (count > 0) ? (sumSqDiff / static_cast<float>(count)) : 0.0f;

                const float eps = 1e-4f;
                float var_total = var_spatial + (noiseS * mean_ref + noiseO) + eps;
                float diffVal = rawVal - mean_ref;
                float distSq = (diffVal * diffVal) / var_total;

                w_motion = std::exp(-std::max(0.0f, distSq - 4.0f) * 0.5f);
                if (w_motion < 0.01f) {
                    w_motion = 0.0f;
                }
            }

            // Scatter sample into target grid pixels
            for (int ity = ity_min; ity <= ity_max; ++ity) {
                float dty_target = ty - static_cast<float>(ity);
                for (int itx = itx_min; itx <= itx_max; ++itx) {
                    float dtx_target = tx - static_cast<float>(itx);

                    // Euclidean distance coarse screening
                    if (dtx_target * dtx_target + dty_target * dty_target > kernelRadiusSq) {
                        continue;
                    }

                    float dtx = dtx_target / z; // Normalize to sensor scale
                    float dty = dty_target / z;

                    // Anisotropic distance D^2 = delta^T Omega delta
                    float d2 = dtx * dtx * oxx + dty * dty * oyy + 2.0f * dtx * dty * oxy;
                    float w_spatial = fast_exp2(-d2 * 0.72f);
                    float w = w_spatial * w_motion;

                    if (w < 0.001f) continue;

                    size_t outIdx = static_cast<size_t>(ity) * m_width + itx;

                    if (channel == 0) {
                        #pragma omp atomic
                        m_accumR[outIdx] += w * rawVal;
                        #pragma omp atomic
                        m_weightR[outIdx] += w;
                    } else if (channel == 1) {
                        #pragma omp atomic
                        m_accumG[outIdx] += w * rawVal;
                        #pragma omp atomic
                        m_weightG[outIdx] += w;
                    } else {
                        #pragma omp atomic
                        m_accumB[outIdx] += w * rawVal;
                        #pragma omp atomic
                        m_weightB[outIdx] += w;
                    }
                }
            }
        }
    }

    m_framesAccumulated++;
    LOGD("SabreEngine: accumulated frame %d", m_framesAccumulated);
    return true;
}

bool SabreEngine::resolve(uint16_t* outRgb, uint16_t* outBayer) {
    if (!outRgb) {
        LOGE("SabreEngine::resolve: outRgb is null");
        return false;
    }
    if (m_framesAccumulated == 0) {
        LOGE("SabreEngine::resolve: 0 frames accumulated");
        return false;
    }

    const uint16_t whiteLevel = m_config.whiteLevel;
    const float wl = static_cast<float>(whiteLevel);

    const float bl_r = static_cast<float>(m_config.blackLevel[0]);
    const float bl_g = 0.5f * (static_cast<float>(m_config.blackLevel[1]) + static_cast<float>(m_config.blackLevel[2]));
    const float bl_b = static_cast<float>(m_config.blackLevel[3]);

    const float scale_r = 65535.0f / std::max(1.0f, wl - bl_r);
    const float scale_g = 65535.0f / std::max(1.0f, wl - bl_g);
    const float scale_b = 65535.0f / std::max(1.0f, wl - bl_b);

    // Temporary normalized RGB buffers
    std::vector<float> normR(m_numPixels);
    std::vector<float> normG(m_numPixels);
    std::vector<float> normB(m_numPixels);

    // 1. Initial dehomogenization / channel normalization
    #pragma omp parallel for schedule(static)
    for (size_t i = 0; i < m_numPixels; ++i) {
        normR[i] = m_accumR[i] / std::max(0.001f, m_weightR[i]);
        normG[i] = m_accumG[i] / std::max(0.001f, m_weightG[i]);
        normB[i] = m_accumB[i] / std::max(0.001f, m_weightB[i]);
    }

    // 2. Cross-Channel Guided Chrominance Filtering
    // Green channel has high spatial density and high SNR.
    // Filter chroma differences (R - G) and (B - G) guided by Green gradients
    // to fill in sparse chroma sites and suppress chromatic aberration.
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < m_height; ++y) {
        for (int x = 0; x < m_width; ++x) {
            size_t idx = static_cast<size_t>(y) * m_width + x;

            float wr = m_weightR[idx];
            float wg = m_weightG[idx];
            float wb = m_weightB[idx];

            float gCenter = normG[idx];
            float rOut = normR[idx];
            float gOut = gCenter;
            float bOut = normB[idx];

            // If R or B weight is insufficient, use local guided color difference
            if (wr < 0.25f || wb < 0.25f) {
                float sumDiffR = 0.0f, sumWeightR = 0.0f;
                float sumDiffB = 0.0f, sumWeightB = 0.0f;

                for (int dy = -2; dy <= 2; ++dy) {
                    int ny = std::clamp(y + dy, 0, m_height - 1);
                    for (int dx = -2; dx <= 2; ++dx) {
                        int nx = std::clamp(x + dx, 0, m_width - 1);
                        size_t nidx = static_cast<size_t>(ny) * m_width + nx;

                        float gNeighbor = normG[nidx];
                        float guideWeight = 1.0f / (1.0f + std::abs(gNeighbor - gCenter) * 0.05f);

                        if (m_weightR[nidx] > 0.05f) {
                            sumDiffR += guideWeight * (normR[nidx] - gNeighbor);
                            sumWeightR += guideWeight;
                        }
                        if (m_weightB[nidx] > 0.05f) {
                            sumDiffB += guideWeight * (normB[nidx] - gNeighbor);
                            sumWeightB += guideWeight;
                        }
                    }
                }

                if (wr < 0.25f) {
                    if (sumWeightR > 0.0f) {
                        rOut = clampf(gCenter + sumDiffR / sumWeightR, bl_r, static_cast<float>(whiteLevel));
                    } else {
                        rOut = bl_r + std::max(0.0f, gOut - bl_g);
                    }
                }
                if (wb < 0.25f) {
                    if (sumWeightB > 0.0f) {
                        bOut = clampf(gCenter + sumDiffB / sumWeightB, bl_b, static_cast<float>(whiteLevel));
                    } else {
                        bOut = bl_b + std::max(0.0f, gOut - bl_g);
                    }
                }
            }

            // Pack planar RGB: channel 0 = R, channel 1 = G, channel 2 = B
            outRgb[idx]                          = static_cast<uint16_t>(clampf((rOut - bl_r) * scale_r, 0.0f, 65535.0f));
            outRgb[m_numPixels + idx]            = static_cast<uint16_t>(clampf((gOut - bl_g) * scale_g, 0.0f, 65535.0f));
            outRgb[2 * m_numPixels + idx]        = static_cast<uint16_t>(clampf((bOut - bl_b) * scale_b, 0.0f, 65535.0f));

            if (outBayer) {
                int c = getBayerChannel(x, y);
                float val = (c == 0) ? rOut : ((c == 1) ? gOut : bOut);
                outBayer[idx] = static_cast<uint16_t>(clampf(val, 0.0f, static_cast<float>(whiteLevel)));
            }
        }
    }

    LOGD("SabreEngine::resolve complete: %dx%d linear RGB super-resolved from %d frames",
         m_width, m_height, m_framesAccumulated);
    return true;
}

} // namespace sabre
} // namespace darkbag

#include "SabreEngine.h"
#include <omp.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <tuple>

#ifdef __ANDROID__
#include <android/log.h>
#define TAG "SabreEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGD(...) do {} while (0)
#define LOGW(...) do {} while (0)
#define LOGE(...) do {} while (0)
#endif

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

    // 2. Compute 3x3 Gaussian-integrated Structure Tensor J and SNR-gated Anisotropic Steering Covariance
    const float wl = std::max(1.0f, static_cast<float>(m_config.whiteLevel));
    const float dnNoiseS = m_config.noiseModelS * wl;
    const float dnNoiseO = m_config.noiseModelO * (wl * wl);
    const float tZoom = clampf((m_config.zoomFactor - 1.05f) / (2.0f - 1.05f), 0.0f, 1.0f);
    const float zoomT = tZoom * tZoom * (3.0f - 2.0f * tZoom);
    const float sigma0 = 0.58f + (0.50f - 0.58f) * zoomT;
    const float kTan = 1.8f + (1.6f - 1.8f) * zoomT;
    const float kNorm = 0.9f + (0.65f - 0.9f) * zoomT;

    auto quadGrad = [&](int px, int py) -> std::pair<float, float> {
        const int x = std::clamp(px, 0, m_quadWidth - 1);
        const int y = std::clamp(py, 0, m_quadHeight - 1);
        const int xm = std::max(0, x - 1);
        const int xp = std::min(m_quadWidth - 1, x + 1);
        const int ym = std::max(0, y - 1);
        const int yp = std::min(m_quadHeight - 1, y + 1);
        const float gx = 0.5f * (greenQuad[y * m_quadWidth + xp] - greenQuad[y * m_quadWidth + xm]);
        const float gy = 0.5f * (greenQuad[yp * m_quadWidth + x] - greenQuad[ym * m_quadWidth + x]);
        return {gx, gy};
    };

    #pragma omp parallel for schedule(dynamic, 16)
    for (int qy = 0; qy < m_quadHeight; ++qy) {
        for (int qx = 0; qx < m_quadWidth; ++qx) {
            float jxx = 0.0f;
            float jyy = 0.0f;
            float jxy = 0.0f;
            for (int dy = -1; dy <= 1; ++dy) {
                const float wy = (dy == 0) ? 2.0f : 1.0f;
                for (int dx = -1; dx <= 1; ++dx) {
                    const float wx = (dx == 0) ? 2.0f : 1.0f;
                    const float w = (wx * wy) * 0.0625f;
                    auto [gx, gy] = quadGrad(qx + dx, qy + dy);
                    jxx += w * gx * gx;
                    jyy += w * gy * gy;
                    jxy += w * gx * gy;
                }
            }

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

            // DN-scaled SNR gating suppresses flat-region noise false anisotropy
            float lumaC = greenQuad[qy * m_quadWidth + qx];
            float sigmaSq = std::max(1e-6f, dnNoiseS * std::max(lumaC, 0.0f) + dnNoiseO);
            float snrGate = trace / (trace + 4.0f * sigmaSq);

            // Coherence in [0, 1]
            float s1 = std::sqrt(std::max(0.0f, lambda1));
            float s2 = std::sqrt(std::max(0.0f, lambda2));
            float coherence = clampf(((s1 - s2) / (s1 + s2 + 1e-5f)) * snrGate, 0.0f, 1.0f);

            float sigma_tangent = sigma0 * (1.0f + kTan * coherence);  // Elongate along edge
            float sigma_normal  = sigma0 / (1.0f + kNorm * coherence); // Narrow across edge

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
    int flowHeight,
    int flowOriginX,
    int flowOriginY,
    int flowTileStep
) {
    if (!altBayer) return false;

    const float z = m_config.zoomFactor;
    const float wl = std::max(1.0f, static_cast<float>(m_config.whiteLevel));
    const float noiseS = m_config.noiseModelS * wl;
    const float noiseO = m_config.noiseModelO * (wl * wl);
    const bool isRef = (m_framesAccumulated == 0);
    const float kernelRadius = std::max(1.6f, m_config.zoomFactor * 1.6f);
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
                // Bilinear interpolation across neighboring tile flow vectors (ROI-mapped when flowTileStep > 0)
                float fx = (flowTileStep > 0)
                    ? ((static_cast<float>(x) - static_cast<float>(flowOriginX)) / static_cast<float>(flowTileStep) - 0.5f)
                    : ((static_cast<float>(x) / m_width) * flowWidth - 0.5f);
                float fy = (flowTileStep > 0)
                    ? ((static_cast<float>(y) - static_cast<float>(flowOriginY)) / static_cast<float>(flowTileStep) - 0.5f)
                    : ((static_cast<float>(y) / m_height) * flowHeight - 0.5f);
                float fx_c = std::clamp(fx, 0.0f, static_cast<float>(std::max(0, flowWidth - 1)));
                float fy_c = std::clamp(fy, 0.0f, static_cast<float>(std::max(0, flowHeight - 1)));
                int x0 = static_cast<int>(std::floor(fx_c));
                int y0 = static_cast<int>(std::floor(fy_c));
                int x1 = std::min(x0 + 1, flowWidth - 1);
                int y1 = std::min(y0 + 1, flowHeight - 1);
                float wx = fx_c - static_cast<float>(x0);
                float wy = fy_c - static_cast<float>(y0);
                auto sampleFlow = [&](int ix, int iy) -> std::pair<float, float> {
                    size_t idx = static_cast<size_t>(iy) * flowWidth + ix;
                    return {flowX[idx], flowY[idx]};
                };
                auto [fx00, fy00] = sampleFlow(x0, y0);
                auto [fx10, fy10] = sampleFlow(x1, y0);
                auto [fx01, fy01] = sampleFlow(x0, y1);
                auto [fx11, fy11] = sampleFlow(x1, y1);
                dx = (fx00 * (1.0f - wx) + fx10 * wx) * (1.0f - wy) +
                     (fx01 * (1.0f - wx) + fx11 * wx) * wy;
                dy = (fy00 * (1.0f - wx) + fy10 * wx) * (1.0f - wy) +
                     (fy01 * (1.0f - wx) + fy11 * wx) * wy;
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

            // Bilinear interpolation of anisotropic steering matrix in quad space
            float qxf = std::clamp(sx * 0.5f - 0.25f, 0.0f, static_cast<float>(std::max(0, m_quadWidth - 1)));
            float qyf = std::clamp(sy * 0.5f - 0.25f, 0.0f, static_cast<float>(std::max(0, m_quadHeight - 1)));
            int qx0 = static_cast<int>(std::floor(qxf));
            int qy0 = static_cast<int>(std::floor(qyf));
            int qx1 = std::min(qx0 + 1, std::max(0, m_quadWidth - 1));
            int qy1 = std::min(qy0 + 1, std::max(0, m_quadHeight - 1));
            float qwx = qxf - static_cast<float>(qx0);
            float qwy = qyf - static_cast<float>(qy0);
            auto lerpCov = [&](const std::vector<float>& buf) -> float {
                float v00 = buf[static_cast<size_t>(qy0) * m_quadWidth + qx0];
                float v10 = buf[static_cast<size_t>(qy0) * m_quadWidth + qx1];
                float v01 = buf[static_cast<size_t>(qy1) * m_quadWidth + qx0];
                float v11 = buf[static_cast<size_t>(qy1) * m_quadWidth + qx1];
                return (v00 * (1.0f - qwx) + v10 * qwx) * (1.0f - qwy) +
                       (v01 * (1.0f - qwx) + v11 * qwx) * qwy;
            };
            float oxx = lerpCov(m_covXX);
            float oyy = lerpCov(m_covYY);
            float oxy = lerpCov(m_covXY);

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
                const int maxKx = std::max(0, (m_width - 1 - px) >> 1);
                const int maxKy = std::max(0, (m_height - 1 - py) >> 1);
                const float lkX = std::clamp((sx - static_cast<float>(px)) * 0.5f, 0.0f, static_cast<float>(maxKx));
                const float lkY = std::clamp((sy - static_cast<float>(py)) * 0.5f, 0.0f, static_cast<float>(maxKy));
                const int kx0 = static_cast<int>(std::floor(lkX));
                const int ky0 = static_cast<int>(std::floor(lkY));
                const int kx1 = std::min(kx0 + 1, maxKx);
                const int ky1 = std::min(ky0 + 1, maxKy);
                const float kfx = lkX - static_cast<float>(kx0);
                const float kfy = lkY - static_cast<float>(ky0);
                const int sx0_r = px + (kx0 << 1);
                const int sx1_r = px + (kx1 << 1);
                const int sy0_r = py + (ky0 << 1);
                const int sy1_r = py + (ky1 << 1);
                const float r00 = static_cast<float>(m_refBayer[static_cast<size_t>(sy0_r) * m_width + sx0_r]);
                const float r10 = static_cast<float>(m_refBayer[static_cast<size_t>(sy0_r) * m_width + sx1_r]);
                const float r01 = static_cast<float>(m_refBayer[static_cast<size_t>(sy1_r) * m_width + sx0_r]);
                const float r11 = static_cast<float>(m_refBayer[static_cast<size_t>(sy1_r) * m_width + sx1_r]);
                const float refPred = (r00 * (1.0f - kfx) + r10 * kfx) * (1.0f - kfy) +
                                      (r01 * (1.0f - kfx) + r11 * kfx) * kfy;

                float var_noise = std::max(0.0f, noiseS * std::max(refPred, 0.0f) + noiseO);
                float var_total = 0.75f * var_spatial + std::max(var_noise, 16.0f) + eps;
                float diffVal = rawVal - refPred;
                float distSq = (diffVal * diffVal) / var_total;

                w_motion = std::exp(-std::max(0.0f, distSq - 2.5f) * 0.75f);
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

    // Temporary normalized RGB buffers
    std::vector<float> normR(m_numPixels);
    std::vector<float> normG(m_numPixels);
    std::vector<float> normB(m_numPixels);

    // 1. Initial dehomogenization / channel normalization
    #pragma omp parallel for schedule(static)
    for (size_t i = 0; i < m_numPixels; ++i) {
        normR[i] = (m_weightR[i] > 0.0001f) ? (m_accumR[i] / m_weightR[i]) : bl_r;
        normG[i] = (m_weightG[i] > 0.0001f) ? (m_accumG[i] / m_weightG[i]) : bl_g;
        normB[i] = (m_weightB[i] > 0.0001f) ? (m_accumB[i] / m_weightB[i]) : bl_b;
    }

    auto clampBayerCoord = [&](int x, int y) -> std::pair<int, int> {
        if (x < 0) {
            x = (x & 1) ? 1 : 0;
        } else if (x >= m_width) {
            int rem = x & 1;
            int lastRem = (m_width - 1) & 1;
            x = (rem == lastRem) ? (m_width - 1) : (m_width - 2);
        }
        if (y < 0) {
            y = (y & 1) ? 1 : 0;
        } else if (y >= m_height) {
            int rem = y & 1;
            int lastRem = (m_height - 1) & 1;
            y = (rem == lastRem) ? (m_height - 1) : (m_height - 2);
        }
        return {std::clamp(x, 0, m_width - 1), std::clamp(y, 0, m_height - 1)};
    };

    auto sampleRef = [&](int x, int y) -> float {
        auto [cx, cy] = clampBayerCoord(x, y);
        return static_cast<float>(m_refBayer[static_cast<size_t>(cy) * m_width + cx]);
    };

    auto computeRcdPrior = [&](int x, int y) -> std::tuple<float, float, float> {
        const float refVal = sampleRef(x, y);
        const int refChan = getBayerChannel(x, y);
        if (refChan == 1) {
            const int hChan = getBayerChannel(x + 1, y);
            const float h0 = sampleRef(x - 1, y), h1 = sampleRef(x + 1, y);
            const float gH0 = sampleRef(x - 2, y), gH1 = sampleRef(x + 2, y);
            const float v0 = sampleRef(x, y - 1), v1 = sampleRef(x, y + 1);
            const float gV0 = sampleRef(x, y - 2), gV1 = sampleRef(x, y + 2);
            const float estH = 0.5f * (h0 + h1) + 0.25f * (2.0f * refVal - gH0 - gH1);
            const float estV = 0.5f * (v0 + v1) + 0.25f * (2.0f * refVal - gV0 - gV1);
            return (hChan == 0) ? std::make_tuple(estH, refVal, estV)
                                : std::make_tuple(estV, refVal, estH);
        }
        const float gW = sampleRef(x - 1, y), gE = sampleRef(x + 1, y);
        const float gN = sampleRef(x, y - 1), gS = sampleRef(x, y + 1);
        const float cW2 = sampleRef(x - 2, y), cE2 = sampleRef(x + 2, y);
        const float cN2 = sampleRef(x, y - 2), cS2 = sampleRef(x, y + 2);

        const float estGH = 0.5f * (gW + gE) + 0.25f * (2.0f * refVal - cW2 - cE2);
        const float gradH = std::abs(gW - gE) + std::abs(2.0f * refVal - cW2 - cE2);
        const float estGV = 0.5f * (gN + gS) + 0.25f * (2.0f * refVal - cN2 - cS2);
        const float gradV = std::abs(gN - gS) + std::abs(2.0f * refVal - cN2 - cS2);
        const float wH = 1.0f / (1.0f + gradH);
        const float wV = 1.0f / (1.0f + gradV);
        const float priorG = (wH * estGH + wV * estGV) / std::max(wH + wV, 1e-6f);

        const float opNW = sampleRef(x - 1, y - 1), opNE = sampleRef(x + 1, y - 1);
        const float opSW = sampleRef(x - 1, y + 1), opSE = sampleRef(x + 1, y + 1);
        const float diffNW = opNW - 0.5f * (gW + gN), diffNE = opNE - 0.5f * (gE + gN);
        const float diffSW = opSW - 0.5f * (gW + gS), diffSE = opSE - 0.5f * (gE + gS);
        const float gradP = std::abs(opNW - opSE) + std::abs(0.5f * (gW + gN) - 0.5f * (gE + gS));
        const float gradQ = std::abs(opNE - opSW) + std::abs(0.5f * (gE + gN) - 0.5f * (gW + gS));
        const float wP = 1.0f / (1.0f + gradP);
        const float wQ = 1.0f / (1.0f + gradQ);
        const float oppVal = priorG + (wP * 0.5f * (diffNW + diffSE) + wQ * 0.5f * (diffNE + diffSW)) / std::max(wP + wQ, 1e-6f);
        return (refChan == 0) ? std::make_tuple(refVal, priorG, oppVal)
                              : std::make_tuple(oppVal, priorG, refVal);
    };

    auto sampleZoomedRcdPrior = [&](int outX, int outY) -> std::tuple<float, float, float> {
        if (m_config.zoomFactor <= 1.05f) {
            return computeRcdPrior(outX, outY);
        }
        const float sx = std::clamp(static_cast<float>(outX) / m_config.zoomFactor + m_cropXStart, 0.0f, static_cast<float>(m_width - 1));
        const float sy = std::clamp(static_cast<float>(outY) / m_config.zoomFactor + m_cropYStart, 0.0f, static_cast<float>(m_height - 1));
        const int x0 = static_cast<int>(std::floor(sx));
        const int y0 = static_cast<int>(std::floor(sy));
        const int x1 = std::min(x0 + 1, m_width - 1);
        const int y1 = std::min(y0 + 1, m_height - 1);
        const float wx = sx - static_cast<float>(x0);
        const float wy = sy - static_cast<float>(y0);
        auto [r00, g00, b00] = computeRcdPrior(x0, y0);
        auto [r10, g10, b10] = computeRcdPrior(x1, y0);
        auto [r01, g01, b01] = computeRcdPrior(x0, y1);
        auto [r11, g11, b11] = computeRcdPrior(x1, y1);
        auto bilerp = [&](float c00, float c10, float c01, float c11) {
            return (c00 * (1.0f - wx) + c10 * wx) * (1.0f - wy) + (c01 * (1.0f - wx) + c11 * wx) * wy;
        };
        return {bilerp(r00, r10, r01, r11), bilerp(g00, g10, g01, g11), bilerp(b00, b10, b01, b11)};
    };

    const float normScaleR = 1.0f / std::max(1.0f, wl - bl_r);
    const float normScaleG = 1.0f / std::max(1.0f, wl - bl_g);
    const float normScaleB = 1.0f / std::max(1.0f, wl - bl_b);

    std::vector<float> linR(m_numPixels), linG(m_numPixels), linB(m_numPixels);

    // 2. Continuous Zoom-Mapped RCD Prior & Green-Guided Chroma Difference Fusion
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

            if (!m_refBayer.empty() && (wr < 1.0f || wg < 1.0f || wb < 1.0f)) {
                auto [pr, pg, pb] = sampleZoomedRcdPrior(x, y);
                pr = clampf(pr, bl_r, wl);
                pg = clampf(pg, bl_g, wl);
                pb = clampf(pb, bl_b, wl);

                const float greenTrust = clampf((wg - 0.1f) / 0.5f, 0.0f, 1.0f);
                const float chromaPriorR = clampf(gCenter + (pr - pg), bl_r, wl);
                const float chromaPriorB = clampf(gCenter + (pb - pg), bl_b, wl);
                const float effPriorR = pr * (1.0f - greenTrust) + chromaPriorR * greenTrust;
                const float effPriorB = pb * (1.0f - greenTrust) + chromaPriorB * greenTrust;

                const float confR = clampf(wr, 0.0f, 1.0f);
                const float confG = clampf(wg, 0.0f, 1.0f);
                const float confB = clampf(wb, 0.0f, 1.0f);

                rOut = effPriorR * (1.0f - confR) + normR[idx] * confR;
                gOut = pg * (1.0f - confG) + normG[idx] * confG;
                bOut = effPriorB * (1.0f - confB) + normB[idx] * confB;
            }

            linR[idx] = clampf((rOut - bl_r) * normScaleR, 0.0f, 1.0f);
            linG[idx] = clampf((gOut - bl_g) * normScaleG, 0.0f, 1.0f);
            linB[idx] = clampf((bOut - bl_b) * normScaleB, 0.0f, 1.0f);
        }
    }

    // 3. Optional Zoom-Aware Multi-Scale Physical MTF Restoration (matching GPU Pass 4)
    const float baseSharpen = (m_config.sharpenStrength > 0.0f) ? m_config.sharpenStrength : 0.0f;
    const float baseCoring = (m_config.coringThreshold > 0.0f) ? m_config.coringThreshold : 2.0f;
    const float tZoomMtf = clampf((m_config.zoomFactor - 1.05f) / (2.0f - 1.05f), 0.0f, 1.0f);
    const float zoomMtf = tZoomMtf * tZoomMtf * (3.0f - 2.0f * tZoomMtf);
    const float effStrength = baseSharpen * (1.0f + 0.25f * zoomMtf);
    const float effCoring = baseCoring * (1.0f + 0.20f * zoomMtf);

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < m_height; ++y) {
        for (int x = 0; x < m_width; ++x) {
            size_t idx = static_cast<size_t>(y) * m_width + x;
            float rN = linR[idx];
            float gN = linG[idx];
            float bN = linB[idx];

            if (effStrength > 0.0f) {
                auto lumaAt = [&](int lx, int ly) -> float {
                    size_t nidx = static_cast<size_t>(std::clamp(ly, 0, m_height - 1)) * m_width +
                                  std::clamp(lx, 0, m_width - 1);
                    return 0.2126f * linR[nidx] + 0.7152f * linG[nidx] + 0.0722f * linB[nidx];
                };
                const float Y = 0.2126f * rN + 0.7152f * gN + 0.0722f * bN;
                float yBlurFine = 0.0f, yBlurMid = 0.0f, yMin = Y, yMax = Y;
                for (int dy = -1; dy <= 1; ++dy) {
                    const float wy = (dy == 0) ? 2.0f : 1.0f;
                    for (int dx = -1; dx <= 1; ++dx) {
                        const float wx = (dx == 0) ? 2.0f : 1.0f;
                        const float w = (wx * wy) * 0.0625f;
                        const float yf = lumaAt(x + dx, y + dy);
                        yBlurFine += w * yf;
                        yMin = std::min(yMin, yf);
                        yMax = std::max(yMax, yf);
                        yBlurMid += w * lumaAt(x + dx * 2, y + dy * 2);
                    }
                }
                const float deltaYFine = Y - yBlurFine;
                const float deltaYMid = Y - yBlurMid;
                const float deltaY = deltaYFine * (1.0f - zoomMtf) + (0.65f * deltaYFine + 0.35f * deltaYMid) * zoomMtf;
                const float sigma = std::sqrt(std::max(1e-7f, m_config.noiseModelS * Y + m_config.noiseModelO));
                const float sgn = (deltaY >= 0.0f) ? 1.0f : -1.0f;
                const float deltaYCored = sgn * std::max(0.0f, std::abs(deltaY) - effCoring * sigma);
                const float deltaYSq = deltaY * deltaY;
                const float gain = deltaYSq / (deltaYSq + sigma * sigma + 1e-6f);
                const float haloMargin = 0.35f * (yMax - yMin);
                const float yNew = clampf(Y + effStrength * gain * deltaYCored,
                                          std::max(0.0f, yMin - haloMargin),
                                          std::min(1.0f, yMax + haloMargin));
                const float factor = yNew / std::max(1e-4f, Y);
                rN = clampf(rN * factor, 0.0f, 1.0f);
                gN = clampf(gN * factor, 0.0f, 1.0f);
                bN = clampf(bN * factor, 0.0f, 1.0f);
            }

            // Pack planar RGB: channel 0 = R, channel 1 = G, channel 2 = B
            outRgb[idx]                   = static_cast<uint16_t>(clampf(rN * 65535.0f + 0.5f, 0.0f, 65535.0f));
            outRgb[m_numPixels + idx]     = static_cast<uint16_t>(clampf(gN * 65535.0f + 0.5f, 0.0f, 65535.0f));
            outRgb[2 * m_numPixels + idx] = static_cast<uint16_t>(clampf(bN * 65535.0f + 0.5f, 0.0f, 65535.0f));

            if (outBayer) {
                int px = x & 1, py = y & 1;
                int cfaCh = 1;
                if (m_config.cfa == CfaPattern::RGGB) cfaCh = (py == 0) ? ((px == 0) ? 0 : 1) : ((px == 0) ? 2 : 3);
                else if (m_config.cfa == CfaPattern::GRBG) cfaCh = (py == 0) ? ((px == 0) ? 1 : 0) : ((px == 0) ? 3 : 2);
                else if (m_config.cfa == CfaPattern::GBRG) cfaCh = (py == 0) ? ((px == 0) ? 2 : 3) : ((px == 0) ? 0 : 1);
                else cfaCh = (py == 0) ? ((px == 0) ? 3 : 2) : ((px == 0) ? 1 : 0);

                float valN = (cfaCh == 0) ? rN : ((cfaCh == 3) ? bN : gN);
                float bl = static_cast<float>(m_config.blackLevel[cfaCh]);
                outBayer[idx] = static_cast<uint16_t>(clampf(valN * (wl - bl) + bl + 0.5f, 0.0f, wl));
            }
        }
    }

    LOGD("SabreEngine::resolve complete: %dx%d linear RGB super-resolved from %d frames",
         m_width, m_height, m_framesAccumulated);
    return true;
}

} // namespace sabre
} // namespace darkbag

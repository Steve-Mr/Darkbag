#include "FastGuidedFilter.h"
#include <algorithm>
#include <cmath>
#include <vector>
#include <omp.h>

namespace rawvideo {

void FastGuidedFilter::boxFilter1D_X(const float* src, float* dst, int width, int height, int r) {
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < height; ++y) {
        const float* srcRow = src + y * width;
        float* dstRow = dst + y * width;

        float sum = 0.0f;
        for (int i = -r; i <= r; ++i) {
            int cx = std::clamp(i, 0, width - 1);
            sum += srcRow[cx];
        }

        for (int x = 0; x < width; ++x) {
            dstRow[x] = sum;
            int left = std::clamp(x - r, 0, width - 1);
            int right = std::clamp(x + r + 1, 0, width - 1);
            sum += srcRow[right] - srcRow[left];
        }
    }
}

void FastGuidedFilter::boxFilter1D_Y(const float* src, float* dst, int width, int height, int r) {
    #pragma omp parallel for schedule(static)
    for (int x = 0; x < width; ++x) {
        float sum = 0.0f;
        for (int i = -r; i <= r; ++i) {
            int cy = std::clamp(i, 0, height - 1);
            sum += src[cy * width + x];
        }

        for (int y = 0; y < height; ++y) {
            dst[y * width + x] = sum;
            int top = std::clamp(y - r, 0, height - 1);
            int bottom = std::clamp(y + r + 1, 0, height - 1);
            sum += src[bottom * width + x] - src[top * width + x];
        }
    }
}

void FastGuidedFilter::boxFilter2D(const float* src, float* dst, float* temp, int width, int height, int r) {
    boxFilter1D_X(src, temp, width, height, r);
    boxFilter1D_Y(temp, dst, width, height, r);
    const float norm = 1.0f / static_cast<float>((2 * r + 1) * (2 * r + 1));
    const int total = width * height;
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < total; ++i) {
        dst[i] *= norm;
    }
}

void FastGuidedFilter::filterPlanarRGB(
    uint16_t* planarRgb,
    int width,
    int height,
    int iso,
    int radius
) {
    if (!planarRgb || width < 16 || height < 16) return;

    const int subW = width / 2;
    const int subH = height / 2;
    const int subSize = subW * subH;
    const int fullPlaneSize = width * height;
    const int subRadius = std::max(1, radius / 2);

    const uint16_t* srcR = planarRgb + 0 * fullPlaneSize;
    const uint16_t* srcG = planarRgb + 1 * fullPlaneSize;
    const uint16_t* srcB = planarRgb + 2 * fullPlaneSize;

    // Allocate memory for sub-sampled calculations
    std::vector<float> I_sub(subSize);
    std::vector<float> p_sub(subSize);
    std::vector<float> temp(subSize);
    std::vector<float> mean_I(subSize);
    std::vector<float> mean_p(subSize);
    std::vector<float> corr_I(subSize);
    std::vector<float> corr_Ip(subSize);
    std::vector<float> var_I(subSize);
    std::vector<float> a(subSize);
    std::vector<float> b(subSize);
    std::vector<float> mean_a(subSize);
    std::vector<float> mean_b(subSize);

    const float inv65535 = 1.0f / 65535.0f;

    // 1. Subsample Green channel as guidance image I
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < subH; ++y) {
        int sy0 = y * 2;
        int sy1 = sy0 + 1;
        for (int x = 0; x < subW; ++x) {
            int sx0 = x * 2;
            int sx1 = sx0 + 1;
            float g00 = static_cast<float>(srcG[sy0 * width + sx0]);
            float g01 = static_cast<float>(srcG[sy0 * width + sx1]);
            float g10 = static_cast<float>(srcG[sy1 * width + sx0]);
            float g11 = static_cast<float>(srcG[sy1 * width + sx1]);
            I_sub[y * subW + x] = (g00 + g01 + g10 + g11) * 0.25f * inv65535;
        }
    }

    // 2. Compute guidance statistics: mean_I, corr_I, var_I
    boxFilter2D(I_sub.data(), mean_I.data(), temp.data(), subW, subH, subRadius);

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < subSize; ++i) {
        temp[i] = I_sub[i] * I_sub[i];
    }
    boxFilter2D(temp.data(), corr_I.data(), mean_p.data(), subW, subH, subRadius);

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < subSize; ++i) {
        var_I[i] = std::max(0.0f, corr_I[i] - mean_I[i] * mean_I[i]);
    }

    // Adaptive regularizer epsilon based on ISO
    const float eps = std::max(0.0002f, (static_cast<float>(iso) / 100.0f) * 0.00035f);

    // 3. Process each channel: R, G, B
    const uint16_t* srcChannels[3] = {srcR, srcG, srcB};
    uint16_t* dstChannels[3] = {
        planarRgb + 0 * fullPlaneSize,
        planarRgb + 1 * fullPlaneSize,
        planarRgb + 2 * fullPlaneSize
    };

    for (int c = 0; c < 3; ++c) {
        const uint16_t* srcC = srcChannels[c];
        uint16_t* dstC = dstChannels[c];

        // Subsample channel c
        #pragma omp parallel for schedule(static)
        for (int y = 0; y < subH; ++y) {
            int sy0 = y * 2;
            int sy1 = sy0 + 1;
            for (int x = 0; x < subW; ++x) {
                int sx0 = x * 2;
                int sx1 = sx0 + 1;
                float p00 = static_cast<float>(srcC[sy0 * width + sx0]);
                float p01 = static_cast<float>(srcC[sy0 * width + sx1]);
                float p10 = static_cast<float>(srcC[sy1 * width + sx0]);
                float p11 = static_cast<float>(srcC[sy1 * width + sx1]);
                p_sub[y * subW + x] = (p00 + p01 + p10 + p11) * 0.25f * inv65535;
            }
        }

        // Compute mean_p, corr_Ip, cov_Ip
        boxFilter2D(p_sub.data(), mean_p.data(), temp.data(), subW, subH, subRadius);

        #pragma omp parallel for schedule(static)
        for (int i = 0; i < subSize; ++i) {
            temp[i] = I_sub[i] * p_sub[i];
        }
        boxFilter2D(temp.data(), corr_Ip.data(), a.data(), subW, subH, subRadius);

        #pragma omp parallel for schedule(static)
        for (int i = 0; i < subSize; ++i) {
            float cov = corr_Ip[i] - mean_I[i] * mean_p[i];
            float ai = cov / (var_I[i] + eps);
            float bi = mean_p[i] - ai * mean_I[i];
            a[i] = ai;
            b[i] = bi;
        }

        // Smooth coefficients a and b
        boxFilter2D(a.data(), mean_a.data(), temp.data(), subW, subH, subRadius);
        boxFilter2D(b.data(), mean_b.data(), temp.data(), subW, subH, subRadius);

        // Bilinear reconstruction to full resolution
        #pragma omp parallel for schedule(dynamic, 32)
        for (int y = 0; y < height; ++y) {
            float subY = std::clamp((y - 0.5f) * 0.5f, 0.0f, static_cast<float>(subH - 1));
            int y0 = static_cast<int>(subY);
            int y1 = std::min(subH - 1, y0 + 1);
            float wy1 = subY - y0;
            float wy0 = 1.0f - wy1;

            const int rowOffset = y * width;
            const int subRow0 = y0 * subW;
            const int subRow1 = y1 * subW;

            for (int x = 0; x < width; ++x) {
                float subX = std::clamp((x - 0.5f) * 0.5f, 0.0f, static_cast<float>(subW - 1));
                int x0 = static_cast<int>(subX);
                int x1 = std::min(subW - 1, x0 + 1);
                float wx1 = subX - x0;
                float wx0 = 1.0f - wx1;

                float a00 = mean_a[subRow0 + x0];
                float a01 = mean_a[subRow0 + x1];
                float a10 = mean_a[subRow1 + x0];
                float a11 = mean_a[subRow1 + x1];
                float interpA = (a00 * wx0 + a01 * wx1) * wy0 + (a10 * wx0 + a11 * wx1) * wy1;

                float b00 = mean_b[subRow0 + x0];
                float b01 = mean_b[subRow0 + x1];
                float b10 = mean_b[subRow1 + x0];
                float b11 = mean_b[subRow1 + x1];
                float interpB = (b00 * wx0 + b01 * wx1) * wy0 + (b10 * wx0 + b11 * wx1) * wy1;

                float guideG = static_cast<float>(srcG[rowOffset + x]) * inv65535;
                float filtered = (interpA * guideG + interpB) * 65535.0f;

                int idx = rowOffset + x;
                float finalVal = std::clamp(filtered, 0.0f, 65535.0f);
                dstC[idx] = static_cast<uint16_t>(finalVal + 0.5f);
            }
        }
    }
}

} // namespace rawvideo

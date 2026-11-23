#include "TileAligner.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <omp.h>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define TAG "TileAligner"
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

static inline int getOptimalThreadCount() {
    // Cap to 4 threads to prioritize big cores and avoid slow little-core barrier stalling
    return std::min(4, std::max(1, omp_get_num_procs()));
}

static float computeWeightedMedian(const float* vals, const float* weights, int count) {
    if (count <= 0) return 0.0f;
    if (count == 1) return vals[0];

    int idx[9];
    for (int i = 0; i < count; ++i) {
        idx[i] = i;
    }
    std::sort(idx, idx + count, [vals](int a, int b) {
        float va = vals[a];
        float vb = vals[b];
        if (std::isnan(va) || std::isnan(vb)) {
            return !std::isnan(va);
        }
        return va < vb;
    });

    float totalWeight = 0.0f;
    for (int i = 0; i < count; ++i) {
        totalWeight += weights[i];
    }
    float halfWeight = 0.5f * totalWeight;

    float cumWeight = 0.0f;
    for (int i = 0; i < count; ++i) {
        cumWeight += weights[idx[i]];
        if (cumWeight >= halfWeight) {
            return vals[idx[i]];
        }
    }
    return vals[idx[count - 1]];
}

template <typename T>
static inline std::pair<float, float> computeNeighborhoodMedian3x3(
    const T* flowX,
    const T* flowY,
    int tx,
    int ty,
    int tilesX,
    int tilesY
) {
    float nbrX[9];
    float nbrY[9];
    float weights[9];
    int count = 0;

    for (int ddy = -1; ddy <= 1; ++ddy) {
        const int ny = ty + ddy;
        if (ny < 0 || ny >= tilesY) continue;
        for (int ddx = -1; ddx <= 1; ++ddx) {
            const int nx = tx + ddx;
            if (nx < 0 || nx >= tilesX) continue;
            const int nIdx = ny * tilesX + nx;
            nbrX[count] = static_cast<float>(flowX[nIdx]);
            nbrY[count] = static_cast<float>(flowY[nIdx]);
            weights[count] = (ddx == 0 && ddy == 0) ? 2.0f : ((ddx == 0 || ddy == 0) ? 2.0f : 1.0f);
            count++;
        }
    }

    return {
        computeWeightedMedian(nbrX, weights, count),
        computeWeightedMedian(nbrY, weights, count)
    };
}

static void fillPadding(std::vector<uint16_t>& image, int width, int height, int pad, int stride) {
    if (image.empty() || width <= 0 || height <= 0) return;

    const int numThreads = getOptimalThreadCount();

    // 1. Replicate left and right columns for active rows
    #pragma omp parallel for num_threads(numThreads) schedule(static)
    for (int y = 0; y < height; ++y) {
        uint16_t* row = image.data() + (y + pad) * stride;
        uint16_t leftVal = row[pad];
        for (int p = 0; p < pad; ++p) {
            row[p] = leftVal;
        }
        uint16_t rightVal = row[pad + width - 1];
        for (int p = 0; p < pad; ++p) {
            row[pad + width + p] = rightVal;
        }
    }

    // 2. Replicate top rows
    const uint16_t* firstRow = image.data() + pad * stride;
    for (int p = 0; p < pad; ++p) {
        std::memcpy(image.data() + p * stride, firstRow, stride * sizeof(uint16_t));
    }

    // 3. Replicate bottom rows
    const uint16_t* lastRow = image.data() + (pad + height - 1) * stride;
    for (int p = 0; p < pad; ++p) {
        std::memcpy(image.data() + (pad + height + p) * stride, lastRow, stride * sizeof(uint16_t));
    }
}

static inline uint32_t computeSAD8x8(const uint16_t* ref, const uint16_t* alt, int stride) {
#if defined(__aarch64__)
    uint32x4_t sum32 = vdupq_n_u32(0);
    #pragma unroll
    for (int y = 0; y < 8; ++y) {
        uint16x8_t r = vld1q_u16(ref + y * stride);
        uint16x8_t a = vld1q_u16(alt + y * stride);
        sum32 = vpadalq_u16(sum32, vabdq_u16(r, a));
    }
    return vaddvq_u32(sum32);
#else
    uint32_t sad = 0;
    for (int y = 0; y < 8; ++y) {
        const uint16_t* rRow = ref + y * stride;
        const uint16_t* aRow = alt + y * stride;
        for (int x = 0; x < 8; ++x) {
            sad += std::abs(static_cast<int32_t>(rRow[x]) - static_cast<int32_t>(aRow[x]));
        }
    }
    return sad;
#endif
}

static inline uint32_t computeSAD16x16(const uint16_t* ref, const uint16_t* alt, int stride) {
#if defined(__aarch64__)
    uint32x4_t sum32 = vdupq_n_u32(0);
    #pragma unroll
    for (int y = 0; y < 16; ++y) {
        const uint16_t* rRow = ref + y * stride;
        const uint16_t* aRow = alt + y * stride;

        uint16x8_t r0 = vld1q_u16(rRow);
        uint16x8_t a0 = vld1q_u16(aRow);
        sum32 = vpadalq_u16(sum32, vabdq_u16(r0, a0));

        uint16x8_t r1 = vld1q_u16(rRow + 8);
        uint16x8_t a1 = vld1q_u16(aRow + 8);
        sum32 = vpadalq_u16(sum32, vabdq_u16(r1, a1));
    }
    return vaddvq_u32(sum32);
#else
    uint32_t sad = 0;
    for (int y = 0; y < 16; ++y) {
        const uint16_t* rRow = ref + y * stride;
        const uint16_t* aRow = alt + y * stride;
        for (int x = 0; x < 16; ++x) {
            sad += std::abs(static_cast<int32_t>(rRow[x]) - static_cast<int32_t>(aRow[x]));
        }
    }
    return sad;
#endif
}

static inline void catmullRomWeights(float t, float w[4]) {
    const float t2 = t * t;
    const float t3 = t2 * t;
    w[0] = -0.5f * t + t2 - 0.5f * t3;
    w[1] = 1.0f - 2.5f * t2 + 1.5f * t3;
    w[2] = 0.5f * t + 2.0f * t2 - 1.5f * t3;
    w[3] = -0.5f * t2 + 0.5f * t3;
}

// Separable 4x4 Catmull-Rom interpolation on the same-phase green Bayer lattice (step = 2 sensor px).
static inline float sampleSamePhaseGreenCubic(
    const uint16_t* bayer,
    int width,
    int height,
    float ax,
    float ay,
    int px,
    int py
) {
    const int maxKx = std::max(0, (width - 1 - px) >> 1);
    const int maxKy = std::max(0, (height - 1 - py) >> 1);

    const float lx = std::clamp((ax - static_cast<float>(px)) * 0.5f, 0.0f, static_cast<float>(maxKx));
    const float ly = std::clamp((ay - static_cast<float>(py)) * 0.5f, 0.0f, static_cast<float>(maxKy));

    const int ix = static_cast<int>(std::floor(lx));
    const int iy = static_cast<int>(std::floor(ly));
    const float fx = lx - static_cast<float>(ix);
    const float fy = ly - static_cast<float>(iy);

    float wx[4];
    float wy[4];
    catmullRomWeights(fx, wx);
    catmullRomWeights(fy, wy);

    int sx[4];
    for (int j = 0; j < 4; ++j) {
        const int kx = std::clamp(ix + j - 1, 0, maxKx);
        sx[j] = px + (kx << 1);
    }

    float sum = 0.0f;
    for (int i = 0; i < 4; ++i) {
        const int ky = std::clamp(iy + i - 1, 0, maxKy);
        const uint16_t* row = bayer + static_cast<size_t>(py + (ky << 1)) * width;
        const float rowInterp =
            wx[0] * static_cast<float>(row[sx[0]]) +
            wx[1] * static_cast<float>(row[sx[1]]) +
            wx[2] * static_cast<float>(row[sx[2]]) +
            wx[3] * static_cast<float>(row[sx[3]]);
        sum += wy[i] * rowInterp;
    }
    return sum;
}

TileAligner::TileAligner() = default;
TileAligner::~TileAligner() = default;

void TileAligner::allocateBuffers(int width, int height, float zoomFactor) {
    float clampedZoom = std::max(1.0f, zoomFactor);
    if (m_width == width && m_height == height && std::abs(m_zoomFactor - clampedZoom) < 1e-4f) {
        return;
    }
    m_width = width;
    m_height = height;
    m_zoomFactor = clampedZoom;

    if (m_zoomFactor <= 1.05f) {
        m_roiXStart = 0;
        m_roiYStart = 0;
        m_roiWidth = width & ~1;
        m_roiHeight = height & ~1;
        m_tileSizeLvl0 = 16;
        m_tileStepSensor = 32;
    } else {
        m_tileSizeLvl0 = (m_zoomFactor >= 1.35f) ? 8 : 16;
        m_tileStepSensor = m_tileSizeLvl0 * 2;

        const float cropW = static_cast<float>(width) / m_zoomFactor;
        const float cropH = static_cast<float>(height) / m_zoomFactor;
        const float cropX0 = (static_cast<float>(width) - cropW) * 0.5f;
        const float cropY0 = (static_cast<float>(height) - cropH) * 0.5f;

        constexpr int kMarginPx = 64;
        const int x0 = std::max(0, static_cast<int>(std::floor(cropX0)) - kMarginPx);
        const int y0 = std::max(0, static_cast<int>(std::floor(cropY0)) - kMarginPx);
        const int x1 = std::min(width, static_cast<int>(std::ceil(cropX0 + cropW)) + kMarginPx);
        const int y1 = std::min(height, static_cast<int>(std::ceil(cropY0 + cropH)) + kMarginPx);

        m_roiXStart = x0 & ~1;
        m_roiYStart = y0 & ~1;
        if (width - m_roiXStart < 32) {
            m_roiXStart = std::max(0, (width - 32) & ~1);
        }
        if (height - m_roiYStart < 32) {
            m_roiYStart = std::max(0, (height - 32) & ~1);
        }

        const int rawW = x1 - m_roiXStart;
        const int rawH = y1 - m_roiYStart;
        const int maxW = (width - m_roiXStart) & ~15;
        const int maxH = (height - m_roiYStart) & ~15;

        m_roiWidth = std::clamp((rawW + 15) & ~15, std::min(32, maxW), maxW);
        m_roiHeight = std::clamp((rawH + 15) & ~15, std::min(32, maxH), maxH);
    }

    m_lvl0Width = m_roiWidth / 2;
    m_lvl0Height = m_roiHeight / 2;
    m_stride0 = m_lvl0Width + 2 * PAD;
    size_t size0 = static_cast<size_t>(m_lvl0Height + 2 * PAD) * m_stride0;

    m_lvl1Width = m_lvl0Width / 2;
    m_lvl1Height = m_lvl0Height / 2;
    m_stride1 = m_lvl1Width + 2 * PAD;
    size_t size1 = static_cast<size_t>(m_lvl1Height + 2 * PAD) * m_stride1;

    m_lvl2Width = m_lvl1Width / 2;
    m_lvl2Height = m_lvl1Height / 2;
    m_stride2 = m_lvl2Width + 2 * PAD;
    size_t size2 = static_cast<size_t>(m_lvl2Height + 2 * PAD) * m_stride2;

    m_tilesX = std::max(1, m_lvl0Width / m_tileSizeLvl0);
    m_tilesY = std::max(1, m_lvl0Height / m_tileSizeLvl0);
    size_t totalTiles = static_cast<size_t>(m_tilesX) * m_tilesY;

    m_refRoiBayer.resize(static_cast<size_t>(m_roiWidth) * m_roiHeight);

    m_refLvl0.resize(size0);
    m_refLvl1.resize(size1);
    m_refLvl2.resize(size2);

    m_altLvl0.resize(size0);
    m_altLvl1.resize(size1);
    m_altLvl2.resize(size2);

    m_flowLvl2X.resize(totalTiles);
    m_flowLvl2Y.resize(totalTiles);
    m_flowLvl1X.resize(totalTiles);
    m_flowLvl1Y.resize(totalTiles);
}

void TileAligner::buildPyramid(
    const uint16_t* bayer,
    std::vector<uint16_t>& lvl0,
    std::vector<uint16_t>& lvl1,
    std::vector<uint16_t>& lvl2
) {
    const int numThreads = getOptimalThreadCount();

    // 1. Level 0: Bayer Quad fast grayscale downsampling L = (R + 2G + B) / 4 over ROI
    // Sample with full sensor stride m_width starting at even-aligned (m_roiXStart, m_roiYStart)
    #pragma omp parallel for num_threads(numThreads) schedule(static)
    for (int y = 0; y < m_lvl0Height; ++y) {
        int by = m_roiYStart + y * 2;
        const uint16_t* row0 = bayer + static_cast<size_t>(by) * m_width + m_roiXStart;
        const uint16_t* row1 = bayer + static_cast<size_t>(by + 1) * m_width + m_roiXStart;
        uint16_t* dst = lvl0.data() + (y + PAD) * m_stride0 + PAD;

        for (int x = 0; x < m_lvl0Width; ++x) {
            int bx = x * 2;
            uint32_t p00 = row0[bx];
            uint32_t p01 = row0[bx + 1];
            uint32_t p10 = row1[bx];
            uint32_t p11 = row1[bx + 1];

            uint32_t r, g0, g1, b;
            switch (m_cfaPattern) {
                case 0: // RGGB
                    r = p00; g0 = p01; g1 = p10; b = p11;
                    break;
                case 1: // GRBG
                    g0 = p00; r = p01; b = p10; g1 = p11;
                    break;
                case 2: // GBRG
                    g0 = p00; b = p01; r = p10; g1 = p11;
                    break;
                case 3: // BGGR
                default:
                    b = p00; g0 = p01; g1 = p10; r = p11;
                    break;
            }
            uint32_t l = (r + g0 + g1 + b + 2) >> 2;
            dst[x] = static_cast<uint16_t>(l);
        }
    }
    fillPadding(lvl0, m_lvl0Width, m_lvl0Height, PAD, m_stride0);

    // 2. Level 1: 2x mean downsampling
    #pragma omp parallel for num_threads(numThreads) schedule(static)
    for (int y = 0; y < m_lvl1Height; ++y) {
        const uint16_t* src0 = lvl0.data() + (2 * y + PAD) * m_stride0 + PAD;
        const uint16_t* src1 = lvl0.data() + (2 * y + 1 + PAD) * m_stride0 + PAD;
        uint16_t* dst = lvl1.data() + (y + PAD) * m_stride1 + PAD;

        for (int x = 0; x < m_lvl1Width; ++x) {
            uint32_t v00 = src0[2 * x];
            uint32_t v01 = src0[2 * x + 1];
            uint32_t v10 = src1[2 * x];
            uint32_t v11 = src1[2 * x + 1];
            dst[x] = static_cast<uint16_t>((v00 + v01 + v10 + v11 + 2) >> 2);
        }
    }
    fillPadding(lvl1, m_lvl1Width, m_lvl1Height, PAD, m_stride1);

    // 3. Level 2: 2x mean downsampling
    #pragma omp parallel for num_threads(numThreads) schedule(static)
    for (int y = 0; y < m_lvl2Height; ++y) {
        const uint16_t* src0 = lvl1.data() + (2 * y + PAD) * m_stride1 + PAD;
        const uint16_t* src1 = lvl1.data() + (2 * y + 1 + PAD) * m_stride1 + PAD;
        uint16_t* dst = lvl2.data() + (y + PAD) * m_stride2 + PAD;

        for (int x = 0; x < m_lvl2Width; ++x) {
            uint32_t v00 = src0[2 * x];
            uint32_t v01 = src0[2 * x + 1];
            uint32_t v10 = src1[2 * x];
            uint32_t v11 = src1[2 * x + 1];
            dst[x] = static_cast<uint16_t>((v00 + v01 + v10 + v11 + 2) >> 2);
        }
    }
    fillPadding(lvl2, m_lvl2Width, m_lvl2Height, PAD, m_stride2);
}

void TileAligner::setReferenceFrame(const uint16_t* refBayer, int width, int height, int cfaPattern, float zoomFactor) {
    if (!refBayer || width < 32 || height < 32) {
        LOGE("TileAligner::setReferenceFrame: invalid parameters (%p, %dx%d)", refBayer, width, height);
        return;
    }

    m_cfaPattern = cfaPattern;
    allocateBuffers(width, height, zoomFactor);

    for (int y = 0; y < m_roiHeight; ++y) {
        const uint16_t* srcRow = refBayer + static_cast<size_t>(m_roiYStart + y) * m_width + m_roiXStart;
        uint16_t* dstRow = m_refRoiBayer.data() + static_cast<size_t>(y) * m_roiWidth;
        std::memcpy(dstRow, srcRow, static_cast<size_t>(m_roiWidth) * sizeof(uint16_t));
    }

    buildPyramid(refBayer, m_refLvl0, m_refLvl1, m_refLvl2);
    m_hasRefFrame = true;

    LOGD("TileAligner: reference frame set (%dx%d, zoom=%.2fx, roi=%dx%d@(%d,%d), step=%dpx, cfa=%d, tiles=%dx%d)",
         m_width, m_height, m_zoomFactor, m_roiWidth, m_roiHeight, m_roiXStart, m_roiYStart,
         m_tileStepSensor, m_cfaPattern, m_tilesX, m_tilesY);
}

void TileAligner::refineFlowSubpixelGreen(
    const uint16_t* altBayer,
    std::vector<float>& flowX,
    std::vector<float>& flowY
) {
    const int totalTiles = m_tilesX * m_tilesY;
    const int numThreads = getOptimalThreadCount();
    const bool greenMatchesParity = (m_cfaPattern == 1 || m_cfaPattern == 2);
    // Use overlapping margin in dense mode (16px step) for higher gradient SNR matching L0 16x16 window
    const int winPad = (m_tileStepSensor <= 16) ? 4 : 0;

    #pragma omp parallel for num_threads(numThreads) schedule(guided)
    for (int tileIdx = 0; tileIdx < totalTiles; ++tileIdx) {
        const int tx = tileIdx % m_tilesX;
        const int ty = tileIdx / m_tilesX;

        const int xStart = std::max(2, tx * m_tileStepSensor - winPad);
        const int xEnd = std::min(m_roiWidth - 2, (tx + 1) * m_tileStepSensor + winPad);
        const int yStart = std::max(2, ty * m_tileStepSensor - winPad);
        const int yEnd = std::min(m_roiHeight - 2, (ty + 1) * m_tileStepSensor + winPad);

        const float initDx = flowX[tileIdx];
        const float initDy = flowY[tileIdx];
        float curDx = initDx;
        float curDy = initDy;
        double initErr = 0.0;
        int initCount = 0;

        for (int iter = 0; iter < 5; ++iter) {
            double h00 = 0.0;
            double h01 = 0.0;
            double h11 = 0.0;
            double bx = 0.0;
            double by = 0.0;
            double errSum = 0.0;
            int sampleCount = 0;

            for (int ry = yStart; ry < yEnd; ++ry) {
                const int py = (m_roiYStart + ry) & 1;
                const int startPx = greenMatchesParity ? (ry & 1) : ((ry & 1) ^ 1);
                int rx = xStart + ((xStart & 1) != startPx ? 1 : 0);
                const int px = (m_roiXStart + rx) & 1;

                const uint16_t* rowC = m_refRoiBayer.data() + static_cast<size_t>(ry) * m_roiWidth;
                const uint16_t* rowU = m_refRoiBayer.data() + static_cast<size_t>(ry - 2) * m_roiWidth;
                const uint16_t* rowD = m_refRoiBayer.data() + static_cast<size_t>(ry + 2) * m_roiWidth;

                for (; rx < xEnd; rx += 2) {
                    const float refVal = static_cast<float>(rowC[rx]);
                    const float gx = (static_cast<float>(rowC[rx + 2]) - static_cast<float>(rowC[rx - 2])) * 0.25f;
                    const float gy = (static_cast<float>(rowD[rx]) - static_cast<float>(rowU[rx])) * 0.25f;

                    const float ax = static_cast<float>(m_roiXStart + rx) + curDx;
                    const float ay = static_cast<float>(m_roiYStart + ry) + curDy;
                    const float altVal = sampleSamePhaseGreenCubic(altBayer, m_width, m_height, ax, ay, px, py);

                    const float r = altVal - refVal;
                    errSum += static_cast<double>(r) * r;
                    h00 += static_cast<double>(gx) * gx;
                    h11 += static_cast<double>(gy) * gy;
                    h01 += static_cast<double>(gx) * gy;
                    bx += static_cast<double>(gx) * r;
                    by += static_cast<double>(gy) * r;
                    sampleCount++;
                }
            }

            if (iter == 0) {
                initErr = errSum;
                initCount = sampleCount;
            }

            const double traceH = h00 + h11;
            if (sampleCount < 8 || traceH < 4.0 * static_cast<double>(sampleCount)) {
                break;
            }

            const double lambda = 2.5 * static_cast<double>(sampleCount) + 0.01 * traceH;
            const double a00 = h00 + lambda;
            const double a11 = h11 + lambda;
            const double det = a00 * a11 - h01 * h01;
            if (det <= 1e-3) {
                break;
            }

            const float stepX = std::clamp(static_cast<float>(-((a11 * bx - h01 * by) / det)), -0.75f, 0.75f);
            const float stepY = std::clamp(static_cast<float>(-((a00 * by - h01 * bx) / det)), -0.75f, 0.75f);

            const float nextDx = std::clamp(curDx + stepX, initDx - 1.5f, initDx + 1.5f);
            const float nextDy = std::clamp(curDy + stepY, initDy - 1.5f, initDy + 1.5f);
            const float appliedX = nextDx - curDx;
            const float appliedY = nextDy - curDy;
            curDx = nextDx;
            curDy = nextDy;

            if (appliedX * appliedX + appliedY * appliedY < 1e-4f) {
                break;
            }
        }

        if (initCount >= 8 && (curDx != initDx || curDy != initDy)) {
            double refinedErr = 0.0;
            for (int ry = yStart; ry < yEnd; ++ry) {
                const int py = (m_roiYStart + ry) & 1;
                const int startPx = greenMatchesParity ? (ry & 1) : ((ry & 1) ^ 1);
                int rx = xStart + ((xStart & 1) != startPx ? 1 : 0);
                const int px = (m_roiXStart + rx) & 1;

                const uint16_t* rowC = m_refRoiBayer.data() + static_cast<size_t>(ry) * m_roiWidth;
                for (; rx < xEnd; rx += 2) {
                    const float refVal = static_cast<float>(rowC[rx]);
                    const float ax = static_cast<float>(m_roiXStart + rx) + curDx;
                    const float ay = static_cast<float>(m_roiYStart + ry) + curDy;
                    const float altVal = sampleSamePhaseGreenCubic(altBayer, m_width, m_height, ax, ay, px, py);
                    const float r = altVal - refVal;
                    refinedErr += static_cast<double>(r) * r;
                }
            }

            if (refinedErr < initErr) {
                flowX[tileIdx] = curDx;
                flowY[tileIdx] = curDy;
            }
        }
    }
}

bool TileAligner::alignFrame(
    const uint16_t* altBayer,
    std::vector<float>& outFlowX,
    std::vector<float>& outFlowY,
    int& outFlowWidth,
    int& outFlowHeight
) {
    if (!altBayer || !m_hasRefFrame || m_tilesX <= 0 || m_tilesY <= 0) {
        LOGE("TileAligner::alignFrame: invalid state or input (alt=%p, hasRef=%d, tiles=%dx%d)",
             altBayer, m_hasRefFrame, m_tilesX, m_tilesY);
        return false;
    }

    // 1. Build alternate frame pyramid (reusing preallocated ROI buffers)
    buildPyramid(altBayer, m_altLvl0, m_altLvl1, m_altLvl2);

    int totalTiles = m_tilesX * m_tilesY;
    outFlowX.resize(totalTiles);
    outFlowY.resize(totalTiles);
    outFlowWidth = m_tilesX;
    outFlowHeight = m_tilesY;

    const int numThreads = getOptimalThreadCount();
    const bool isDenseMode = (m_tileSizeLvl0 == 8);
    const int l2Radius = isDenseMode ? 5 : 4;

    // 2. Level 2 Coarse Tile Matching (8x8 centered SAD window prevents small-aperture cluster jumps)
    #pragma omp parallel for num_threads(numThreads) schedule(guided)
    for (int tileIdx = 0; tileIdx < totalTiles; ++tileIdx) {
        int tx = tileIdx % m_tilesX;
        int ty = tileIdx / m_tilesX;
        int refX = isDenseMode ? (tx * 2 - 3) : (tx * 4 - 2);
        int refY = isDenseMode ? (ty * 2 - 3) : (ty * 4 - 2);
        const uint16_t* refPtr = m_refLvl2.data() + (refY + PAD) * m_stride2 + (refX + PAD);

        // Evaluate (0, 0) first to prefer zero displacement on ties
        const uint16_t* altPtr0 = m_altLvl2.data() + (refY + PAD) * m_stride2 + (refX + PAD);
        uint32_t minSAD = computeSAD8x8(refPtr, altPtr0, m_stride2);
        int bestDx = 0;
        int bestDy = 0;

        for (int dy = -l2Radius; dy <= l2Radius; ++dy) {
            for (int dx = -l2Radius; dx <= l2Radius; ++dx) {
                if (dx == 0 && dy == 0) continue;
                const uint16_t* altPtr = m_altLvl2.data() + (refY + dy + PAD) * m_stride2 + (refX + dx + PAD);
                uint32_t sad = computeSAD8x8(refPtr, altPtr, m_stride2);
                if (sad < minSAD) {
                    minSAD = sad;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }

        m_flowLvl2X[tileIdx] = static_cast<int16_t>(bestDx);
        m_flowLvl2Y[tileIdx] = static_cast<int16_t>(bestDy);
    }

    // 3. Level 1 Refinement (8x8 SAD window, overlapping when dense, evaluating self & 3x3 median L2 candidates)
    #pragma omp parallel for num_threads(numThreads) schedule(guided)
    for (int tileIdx = 0; tileIdx < totalTiles; ++tileIdx) {
        int tx = tileIdx % m_tilesX;
        int ty = tileIdx / m_tilesX;
        int refX = isDenseMode ? (tx * 4 - 2) : (tx * 8);
        int refY = isDenseMode ? (ty * 4 - 2) : (ty * 8);
        const uint16_t* refPtr = m_refLvl1.data() + (refY + PAD) * m_stride1 + (refX + PAD);

        const int selfL2X = m_flowLvl2X[tileIdx];
        const int selfL2Y = m_flowLvl2Y[tileIdx];
        auto [medL2Xf, medL2Yf] = computeNeighborhoodMedian3x3(
            m_flowLvl2X.data(), m_flowLvl2Y.data(), tx, ty, m_tilesX, m_tilesY
        );
        const int medL2X = static_cast<int>(std::lround(medL2Xf));
        const int medL2Y = static_cast<int>(std::lround(medL2Yf));

        uint32_t minSAD = UINT32_MAX;
        int bestDx = 2 * selfL2X;
        int bestDy = 2 * selfL2Y;

        auto searchAroundL2 = [&](int l2x, int l2y) {
            const int cx = 2 * l2x;
            const int cy = 2 * l2y;
            for (int ddy = -1; ddy <= 1; ++ddy) {
                for (int ddx = -1; ddx <= 1; ++ddx) {
                    const int dx = cx + ddx;
                    const int dy = cy + ddy;
                    const uint16_t* altPtr = m_altLvl1.data() + (refY + dy + PAD) * m_stride1 + (refX + dx + PAD);
                    const uint32_t sad = computeSAD8x8(refPtr, altPtr, m_stride1);
                    if (sad < minSAD) {
                        minSAD = sad;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }
        };

        searchAroundL2(selfL2X, selfL2Y);
        if (medL2X != selfL2X || medL2Y != selfL2Y) {
            searchAroundL2(medL2X, medL2Y);
        }

        m_flowLvl1X[tileIdx] = static_cast<int16_t>(bestDx);
        m_flowLvl1Y[tileIdx] = static_cast<int16_t>(bestDy);
    }

    // 4. Level 0 Fine Search (16x16 SAD window, overlapping when dense, [-1, +1] around 2 * L1) & Subpixel Parabolic Fit
    #pragma omp parallel for num_threads(numThreads) schedule(guided)
    for (int tileIdx = 0; tileIdx < totalTiles; ++tileIdx) {
        int tx = tileIdx % m_tilesX;
        int ty = tileIdx / m_tilesX;
        int refX = isDenseMode ? (tx * 8 - 4) : (tx * 16);
        int refY = isDenseMode ? (ty * 8 - 4) : (ty * 16);
        const uint16_t* refPtr = m_refLvl0.data() + (refY + PAD) * m_stride0 + (refX + PAD);

        int cx = 2 * m_flowLvl1X[tileIdx];
        int cy = 2 * m_flowLvl1Y[tileIdx];

        auto evalSad0 = [&](int dx, int dy) -> uint32_t {
            const uint16_t* altPtr = m_altLvl0.data() + (refY + dy + PAD) * m_stride0 + (refX + dx + PAD);
            return computeSAD16x16(refPtr, altPtr, m_stride0);
        };

        uint32_t gridSAD[3][3];
        // Evaluate prediction center (ddx=0, ddy=0) first
        gridSAD[1][1] = evalSad0(cx, cy);
        uint32_t minSAD = gridSAD[1][1];
        int bestDdx = 0;
        int bestDdy = 0;

        for (int ddy = -1; ddy <= 1; ++ddy) {
            for (int ddx = -1; ddx <= 1; ++ddx) {
                if (ddx == 0 && ddy == 0) continue;
                int dx = cx + ddx;
                int dy = cy + ddy;
                uint32_t sad = evalSad0(dx, dy);
                gridSAD[ddy + 1][ddx + 1] = sad;
                if (sad < minSAD) {
                    minSAD = sad;
                    bestDdx = ddx;
                    bestDdy = ddy;
                }
            }
        }

        int bestDx = cx + bestDdx;
        int bestDy = cy + bestDdy;

        // Subpixel Parabolic Fit
        auto getSAD0 = [&](int dx, int dy) -> float {
            int relX = dx - cx;
            int relY = dy - cy;
            if (relX >= -1 && relX <= 1 && relY >= -1 && relY <= 1) {
                return static_cast<float>(gridSAD[relY + 1][relX + 1]);
            }
            return static_cast<float>(evalSad0(dx, dy));
        };

        float sad00 = static_cast<float>(minSAD);
        float sadM10 = getSAD0(bestDx - 1, bestDy);     // SAD(-1, 0)
        float sadP10 = getSAD0(bestDx + 1, bestDy);     // SAD(+1, 0)
        float sad0M1 = getSAD0(bestDx, bestDy - 1);     // SAD(0, -1)
        float sad0P1 = getSAD0(bestDx, bestDy + 1);     // SAD(0, +1)

        float denomX = 2.0f * std::max(1.0f, sadM10 - 2.0f * sad00 + sadP10);
        float dxSub = (sadM10 - sadP10) / denomX;
        dxSub = std::clamp(dxSub, -0.5f, 0.5f);

        float denomY = 2.0f * std::max(1.0f, sad0M1 - 2.0f * sad00 + sad0P1);
        float dySub = (sad0M1 - sad0P1) / denomY;
        dySub = std::clamp(dySub, -0.5f, 0.5f);

        // Convert to sensor coordinates: 1 Level 0 pixel = 2 physical Bayer pixels
        float dxSensor = 2.0f * (static_cast<float>(bestDx) + dxSub);
        float dySensor = 2.0f * (static_cast<float>(bestDy) + dySub);

        outFlowX[tileIdx] = dxSensor;
        outFlowY[tileIdx] = dySensor;
    }

    // 4.5 Same-phase green-channel Gauss-Newton subpixel refinement for digital zoom crops
    if (m_zoomFactor > 1.05f) {
        refineFlowSubpixelGreen(altBayer, outFlowX, outFlowY);
    }

    // 5. Spatial Flow Field Regularization (3x3 weighted median filter & outlier clamping)
    // Prevents flow field tearing on periodic text and screen structures
    std::vector<float> rawFlowX = outFlowX;
    std::vector<float> rawFlowY = outFlowY;

    #pragma omp parallel for num_threads(numThreads) schedule(static)
    for (int tileIdx = 0; tileIdx < totalTiles; ++tileIdx) {
        int tx = tileIdx % m_tilesX;
        int ty = tileIdx / m_tilesX;

        auto [medX, medY] = computeNeighborhoodMedian3x3(
            rawFlowX.data(), rawFlowY.data(), tx, ty, m_tilesX, m_tilesY
        );

        float curX = rawFlowX[tileIdx];
        float curY = rawFlowY[tileIdx];
        float diffX = curX - medX;
        float diffY = curY - medY;

        // If flow vector deviates by more than 3.0 pixels from its 3x3 spatial neighborhood median,
        // replace with neighborhood median to prevent flow field tearing
        if (diffX * diffX + diffY * diffY > 9.0f) {
            outFlowX[tileIdx] = medX;
            outFlowY[tileIdx] = medY;
        }
    }

    return true;
}

} // namespace sabre
} // namespace darkbag

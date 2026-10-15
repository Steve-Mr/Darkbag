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

static inline uint32_t computeSAD4x4(const uint16_t* ref, const uint16_t* alt, int stride) {
#if defined(__aarch64__)
    uint16x4_t r0 = vld1_u16(ref);
    uint16x4_t a0 = vld1_u16(alt);
    uint16x4_t d0 = vabd_u16(r0, a0);

    uint16x4_t r1 = vld1_u16(ref + stride);
    uint16x4_t a1 = vld1_u16(alt + stride);
    uint16x4_t d1 = vabd_u16(r1, a1);

    uint16x4_t r2 = vld1_u16(ref + 2 * stride);
    uint16x4_t a2 = vld1_u16(alt + 2 * stride);
    uint16x4_t d2 = vabd_u16(r2, a2);

    uint16x4_t r3 = vld1_u16(ref + 3 * stride);
    uint16x4_t a3 = vld1_u16(alt + 3 * stride);
    uint16x4_t d3 = vabd_u16(r3, a3);

    uint32x4_t sum32 = vaddl_u16(d0, d1);
    sum32 = vaddw_u16(sum32, d2);
    sum32 = vaddw_u16(sum32, d3);
    return vaddvq_u32(sum32);
#else
    uint32_t sad = 0;
    for (int y = 0; y < 4; ++y) {
        const uint16_t* rRow = ref + y * stride;
        const uint16_t* aRow = alt + y * stride;
        for (int x = 0; x < 4; ++x) {
            sad += std::abs(static_cast<int32_t>(rRow[x]) - static_cast<int32_t>(aRow[x]));
        }
    }
    return sad;
#endif
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

TileAligner::TileAligner() = default;
TileAligner::~TileAligner() = default;

void TileAligner::allocateBuffers(int width, int height) {
    if (m_width == width && m_height == height) {
        return;
    }
    m_width = width;
    m_height = height;

    m_lvl0Width = width / 2;
    m_lvl0Height = height / 2;
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

    m_tilesX = m_lvl0Width / 16;
    m_tilesY = m_lvl0Height / 16;
    size_t totalTiles = static_cast<size_t>(m_tilesX) * m_tilesY;

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

    // 1. Level 0: Bayer Quad fast grayscale downsampling L = (R + 2G + B) / 4
    #pragma omp parallel for num_threads(numThreads) schedule(static)
    for (int y = 0; y < m_lvl0Height; ++y) {
        int by = y * 2;
        const uint16_t* row0 = bayer + by * m_width;
        const uint16_t* row1 = bayer + (by + 1) * m_width;
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

void TileAligner::setReferenceFrame(const uint16_t* refBayer, int width, int height, int cfaPattern) {
    if (!refBayer || width < 32 || height < 32) {
        LOGE("TileAligner::setReferenceFrame: invalid parameters (%p, %dx%d)", refBayer, width, height);
        return;
    }

    m_cfaPattern = cfaPattern;
    allocateBuffers(width, height);
    buildPyramid(refBayer, m_refLvl0, m_refLvl1, m_refLvl2);
    m_hasRefFrame = true;

    LOGD("TileAligner: reference frame set (%dx%d, cfa=%d, tiles=%dx%d)",
         m_width, m_height, m_cfaPattern, m_tilesX, m_tilesY);
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

    // 1. Build alternate frame pyramid (reusing preallocated buffers)
    buildPyramid(altBayer, m_altLvl0, m_altLvl1, m_altLvl2);

    int totalTiles = m_tilesX * m_tilesY;
    outFlowX.resize(totalTiles);
    outFlowY.resize(totalTiles);
    outFlowWidth = m_tilesX;
    outFlowHeight = m_tilesY;

    const int numThreads = getOptimalThreadCount();

    // 2. Level 2 Coarse Tile Matching (4x4 tiles, search range [-4, +4])
    #pragma omp parallel for num_threads(numThreads) schedule(guided)
    for (int tileIdx = 0; tileIdx < totalTiles; ++tileIdx) {
        int tx = tileIdx % m_tilesX;
        int ty = tileIdx / m_tilesX;
        int refX = tx * 4;
        int refY = ty * 4;
        const uint16_t* refPtr = m_refLvl2.data() + (refY + PAD) * m_stride2 + (refX + PAD);

        // Evaluate (0, 0) first to prefer zero displacement on ties
        const uint16_t* altPtr0 = m_altLvl2.data() + (refY + PAD) * m_stride2 + (refX + PAD);
        uint32_t minSAD = computeSAD4x4(refPtr, altPtr0, m_stride2);
        int bestDx = 0;
        int bestDy = 0;

        for (int dy = -4; dy <= 4; ++dy) {
            for (int dx = -4; dx <= 4; ++dx) {
                if (dx == 0 && dy == 0) continue;
                const uint16_t* altPtr = m_altLvl2.data() + (refY + dy + PAD) * m_stride2 + (refX + dx + PAD);
                uint32_t sad = computeSAD4x4(refPtr, altPtr, m_stride2);
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

    // 3. Level 1 Refinement (8x8 tiles, neighborhood [-1, +1] around predicted 2 * L2)
    #pragma omp parallel for num_threads(numThreads) schedule(guided)
    for (int tileIdx = 0; tileIdx < totalTiles; ++tileIdx) {
        int tx = tileIdx % m_tilesX;
        int ty = tileIdx / m_tilesX;
        int refX = tx * 8;
        int refY = ty * 8;
        const uint16_t* refPtr = m_refLvl1.data() + (refY + PAD) * m_stride1 + (refX + PAD);

        int cx = 2 * m_flowLvl2X[tileIdx];
        int cy = 2 * m_flowLvl2Y[tileIdx];

        // Evaluate prediction center first
        const uint16_t* altPtrC = m_altLvl1.data() + (refY + cy + PAD) * m_stride1 + (refX + cx + PAD);
        uint32_t minSAD = computeSAD8x8(refPtr, altPtrC, m_stride1);
        int bestDx = cx;
        int bestDy = cy;

        for (int ddy = -1; ddy <= 1; ++ddy) {
            for (int ddx = -1; ddx <= 1; ++ddx) {
                if (ddx == 0 && ddy == 0) continue;
                int dx = cx + ddx;
                int dy = cy + ddy;
                const uint16_t* altPtr = m_altLvl1.data() + (refY + dy + PAD) * m_stride1 + (refX + dx + PAD);
                uint32_t sad = computeSAD8x8(refPtr, altPtr, m_stride1);
                if (sad < minSAD) {
                    minSAD = sad;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }

        m_flowLvl1X[tileIdx] = static_cast<int16_t>(bestDx);
        m_flowLvl1Y[tileIdx] = static_cast<int16_t>(bestDy);
    }

    // 4. Level 0 Fine Search (16x16 tiles, neighborhood [-1, +1] around predicted 2 * L1) & Subpixel Parabolic Fit
    #pragma omp parallel for num_threads(numThreads) schedule(guided)
    for (int tileIdx = 0; tileIdx < totalTiles; ++tileIdx) {
        int tx = tileIdx % m_tilesX;
        int ty = tileIdx / m_tilesX;
        int refX = tx * 16;
        int refY = ty * 16;
        const uint16_t* refPtr = m_refLvl0.data() + (refY + PAD) * m_stride0 + (refX + PAD);

        int cx = 2 * m_flowLvl1X[tileIdx];
        int cy = 2 * m_flowLvl1Y[tileIdx];

        uint32_t gridSAD[3][3];
        // Evaluate prediction center (ddx=0, ddy=0) first
        const uint16_t* altPtrC = m_altLvl0.data() + (refY + cy + PAD) * m_stride0 + (refX + cx + PAD);
        gridSAD[1][1] = computeSAD16x16(refPtr, altPtrC, m_stride0);
        uint32_t minSAD = gridSAD[1][1];
        int bestDdx = 0;
        int bestDdy = 0;

        for (int ddy = -1; ddy <= 1; ++ddy) {
            for (int ddx = -1; ddx <= 1; ++ddx) {
                if (ddx == 0 && ddy == 0) continue;
                int dx = cx + ddx;
                int dy = cy + ddy;
                const uint16_t* altPtr = m_altLvl0.data() + (refY + dy + PAD) * m_stride0 + (refX + dx + PAD);
                uint32_t sad = computeSAD16x16(refPtr, altPtr, m_stride0);
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
            const uint16_t* altPtr = m_altLvl0.data() + (refY + dy + PAD) * m_stride0 + (refX + dx + PAD);
            return static_cast<float>(computeSAD16x16(refPtr, altPtr, m_stride0));
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

    // 5. Spatial Flow Field Regularization (3x3 weighted median filter & outlier clamping)
    // Prevents flow field tearing on periodic text and screen structures
    std::vector<float> rawFlowX = outFlowX;
    std::vector<float> rawFlowY = outFlowY;

    #pragma omp parallel for num_threads(numThreads) schedule(static)
    for (int tileIdx = 0; tileIdx < totalTiles; ++tileIdx) {
        int tx = tileIdx % m_tilesX;
        int ty = tileIdx / m_tilesX;

        float nbrX[9];
        float nbrY[9];
        float weights[9];
        int count = 0;

        for (int ddy = -1; ddy <= 1; ++ddy) {
            int ny = ty + ddy;
            if (ny < 0 || ny >= m_tilesY) continue;
            for (int ddx = -1; ddx <= 1; ++ddx) {
                int nx = tx + ddx;
                if (nx < 0 || nx >= m_tilesX) continue;
                int nIdx = ny * m_tilesX + nx;
                nbrX[count] = rawFlowX[nIdx];
                nbrY[count] = rawFlowY[nIdx];
                // Spatial weighting: center=2, cardinal=2, diagonal=1
                weights[count] = (ddx == 0 && ddy == 0) ? 2.0f : ((ddx == 0 || ddy == 0) ? 2.0f : 1.0f);
                count++;
            }
        }

        float medX = computeWeightedMedian(nbrX, weights, count);
        float medY = computeWeightedMedian(nbrY, weights, count);

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

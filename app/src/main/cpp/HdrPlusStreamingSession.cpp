#include "HdrPlusStreamingSession.h"
#include "hdrplus_accumulate_step.h"
#include "hdrplus_single_pipeline.h"
#include "ColorPipe.h"
#include "demosaic/RcdDemosaic.h"
#include <android/log.h>
#include <omp.h>
#include <cmath>

#define TAG "HdrPlusStreamingSession"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

HdrPlusStreamingSession::HdrPlusStreamingSession(
    int width,
    int height,
    int orientation,
    int whiteLevel,
    const int* blackLevelPattern,
    const float* lensShadingMap,
    int lensShadingRows,
    int lensShadingCols,
    const float* whiteBalance,
    const float* ccm,
    int cfaPattern,
    const double* noiseProfile,
    int noiseProfileLen,
    int fusionMode,
    float zoomFactor
) : m_width(width),
    m_height(height),
    m_orientation(orientation),
    m_whiteLevel(whiteLevel),
    m_cfaPattern(cfaPattern),
    m_lensShadingRows(lensShadingRows),
    m_lensShadingCols(lensShadingCols),
    m_accumIdx(0),
    m_framesPushed(0),
    m_fusionMode(fusionMode),
    m_zoomFactor(zoomFactor)
{
    m_bl_r  = static_cast<uint16_t>(std::max(0, blackLevelPattern ? blackLevelPattern[0] : 64));
    m_bl_g0 = static_cast<uint16_t>(std::max(0, blackLevelPattern ? blackLevelPattern[1] : 64));
    m_bl_g1 = static_cast<uint16_t>(std::max(0, blackLevelPattern ? blackLevelPattern[2] : 64));
    m_bl_b  = static_cast<uint16_t>(std::max(0, blackLevelPattern ? blackLevelPattern[3] : 64));

    m_wb_r  = whiteBalance ? whiteBalance[0] : 2.0f;
    m_wb_g0 = whiteBalance ? whiteBalance[1] : 1.0f;
    m_wb_g1 = whiteBalance ? whiteBalance[2] : 1.0f;
    m_wb_b  = whiteBalance ? whiteBalance[3] : 1.5f;
    m_whiteBalance = {m_wb_r, m_wb_g0, m_wb_g1, m_wb_b};

    m_ccm.resize(9);
    if (ccm) {
        std::memcpy(m_ccm.data(), ccm, 9 * sizeof(float));
    } else {
        m_ccm = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    }

    if (lensShadingMap && lensShadingRows > 0 && lensShadingCols > 0) {
        size_t lscCount = static_cast<size_t>(4) * lensShadingRows * lensShadingCols;
        m_lensShadingMap.resize(lscCount);
        std::memcpy(m_lensShadingMap.data(), lensShadingMap, lscCount * sizeof(float));
    }

    if (noiseProfile && noiseProfileLen > 0) {
        m_noiseProfile.assign(noiseProfile, noiseProfile + noiseProfileLen);
    }

    // Fixed constant O(1) memory allocation
    size_t numPixels = static_cast<size_t>(m_width) * m_height;
    m_refFrame.resize(numPixels);
    m_accumVal[0].resize(numPixels);
    m_accumVal[1].resize(numPixels);
    m_accumWeight[0].resize(numPixels);
    m_accumWeight[1].resize(numPixels);

    // Initialize Sabre Super-Resolution Engine
    darkbag::sabre::SabreConfig sabreCfg;
    sabreCfg.width = m_width;
    sabreCfg.height = m_height;
    sabreCfg.cfa = static_cast<darkbag::sabre::CfaPattern>(m_cfaPattern);
    sabreCfg.blackLevel[0] = m_bl_r;
    sabreCfg.blackLevel[1] = m_bl_g0;
    sabreCfg.blackLevel[2] = m_bl_g1;
    sabreCfg.blackLevel[3] = m_bl_b;
    sabreCfg.whiteLevel = static_cast<uint16_t>(m_whiteLevel);
    sabreCfg.whiteBalance[0] = m_wb_r;
    sabreCfg.whiteBalance[1] = m_wb_g0;
    sabreCfg.whiteBalance[2] = m_wb_g1;
    sabreCfg.whiteBalance[3] = m_wb_b;
    sabreCfg.zoomFactor = m_zoomFactor;
    if (m_noiseProfile.size() >= 2) {
        sabreCfg.noiseModelS = static_cast<float>(m_noiseProfile[0]);
        sabreCfg.noiseModelO = static_cast<float>(m_noiseProfile[1]);
    }
    m_sabreEngine = std::make_unique<darkbag::sabre::SabreEngine>(sabreCfg);

    LOGD("HdrPlusStreamingSession initialized: %dx%d, orientation=%d, WL=%d, BL=[%u,%u,%u,%u], fusionMode=%d, zoom=%.2f",
         m_width, m_height, m_orientation, m_whiteLevel, m_bl_r, m_bl_g0, m_bl_g1, m_bl_b, m_fusionMode, m_zoomFactor);
}

HdrPlusStreamingSession::~HdrPlusStreamingSession() {
    LOGD("HdrPlusStreamingSession destroyed (total frames pushed: %d)", m_framesPushed);
}

bool HdrPlusStreamingSession::pushFrame(const uint16_t* rawData, size_t numPixels) {
    std::lock_guard<std::mutex> lock(m_sessionMutex);
    if (!rawData) {
        LOGE("pushFrame: rawData is null");
        return false;
    }
    const size_t expectedPixels = static_cast<size_t>(m_width) * m_height;
    if (numPixels != expectedPixels) {
        LOGE("pushFrame: numPixels mismatch (got %zu, expected %zu)", numPixels, expectedPixels);
        return false;
    }

    if (m_framesPushed == 0) {
        // Frame 0 (Reference Frame)
        std::memcpy(m_refFrame.data(), rawData, numPixels * sizeof(uint16_t));

        #pragma omp parallel for schedule(static)
        for (size_t i = 0; i < numPixels; ++i) {
            m_accumVal[0][i] = static_cast<float>(rawData[i]);
            m_accumWeight[0][i] = 1.0f;
        }

        if (m_sabreEngine) {
            m_sabreEngine->setReferenceFrame(rawData);
        }

        m_accumIdx = 0;
        m_framesPushed = 1;
        LOGD("pushFrame: registered reference frame 0");
        return true;
    }

    // Frames 1..N-1 (Alternate Frames)
    int inIdx = m_accumIdx;
    int outIdx = 1 - m_accumIdx;

    Halide::Runtime::Buffer<uint16_t> refBuf(m_refFrame.data(), m_width, m_height);
    Halide::Runtime::Buffer<uint16_t> altBuf(const_cast<uint16_t*>(rawData), m_width, m_height);
    Halide::Runtime::Buffer<float> valIn(m_accumVal[inIdx].data(), m_width, m_height);
    Halide::Runtime::Buffer<float> weightIn(m_accumWeight[inIdx].data(), m_width, m_height);
    Halide::Runtime::Buffer<float> valOut(m_accumVal[outIdx].data(), m_width, m_height);
    Halide::Runtime::Buffer<float> weightOut(m_accumWeight[outIdx].data(), m_width, m_height);

    int res = hdrplus_accumulate_step(refBuf, altBuf, valIn, weightIn, valOut, weightOut);
    if (res != 0) {
        LOGE("hdrplus_accumulate_step failed with error %d at frame %d", res, m_framesPushed);
        return false;
    }

    if (m_sabreEngine) {
        m_sabreEngine->accumulateFrame(rawData);
    }

    m_accumIdx = outIdx;
    m_framesPushed++;
    LOGD("pushFrame: accumulated frame %d", m_framesPushed - 1);
    return true;
}

int HdrPlusStreamingSession::finish(
    std::shared_ptr<SharedCaptureResult>& outSharedResult,
    Halide::Runtime::Buffer<uint16_t>& outRgbBuf,
    Halide::Runtime::Buffer<uint16_t>& outBayerBuf
) {
    std::lock_guard<std::mutex> lock(m_sessionMutex);
    if (m_framesPushed <= 0) {
        LOGE("finish called with 0 frames pushed");
        return -1;
    }

    if (!outSharedResult) {
        outSharedResult = std::make_shared<SharedCaptureResult>();
    }

    const size_t numPixels = static_cast<size_t>(m_width) * m_height;
    outSharedResult->bayerBuf.resize(numPixels);
    outSharedResult->rgbBuf.resize(numPixels * 3);
    outSharedResult->noiseProfile = m_noiseProfile;

    // Determine effective fusion mode:
    // 0: Auto (Spatial+RCD for zoom < 1.25x, Sabre for zoom >= 1.25x)
    // 1: Spatial + RCD
    // 2: Sabre (Super-Resolution)
    // 3: Classic Wiener
    int effectiveMode = m_fusionMode;
    if (effectiveMode == 0) {
        effectiveMode = (m_zoomFactor >= 1.25f) ? 2 : 1;
    }

    if (effectiveMode == 2 && m_sabreEngine && m_framesPushed > 1) {
        LOGD("HdrPlusStreamingSession: resolving with Sabre Super-Resolution (zoom=%.2fx, frames=%d)",
             m_zoomFactor, m_framesPushed);
        bool sabreOk = m_sabreEngine->resolve(
            outSharedResult->rgbBuf.data(),
            outSharedResult->bayerBuf.data()
        );
        if (sabreOk) {
            if (m_zoomFactor > 1.05f) {
                outSharedResult->isZoomCropped = true;
            }
            outSharedResult->isWhiteBalanceApplied = false;
            outBayerBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->bayerBuf.data(), m_width, m_height);
            outRgbBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->rgbBuf.data(), m_width, m_height, 3);
            LOGD("HdrPlusStreamingSession: Sabre Super-Resolution resolve succeeded");
            return 0;
        }
        LOGW("HdrPlusStreamingSession: Sabre resolve returned false, falling back to Spatial + RCD");
    }

    // Normalize accumulated val / weight into m_refFrame buffer to avoid input/output aliasing
    const float* valData = m_accumVal[m_accumIdx].data();
    const float* weightData = m_accumWeight[m_accumIdx].data();

    #pragma omp parallel for schedule(static)
    for (size_t i = 0; i < numPixels; ++i) {
        float norm = valData[i] / std::max(0.001f, weightData[i]) + 0.5f;
        m_refFrame[i] = static_cast<uint16_t>(std::clamp(norm, 0.0f, 65535.0f));
    }

    std::copy(m_refFrame.begin(), m_refFrame.end(), outSharedResult->bayerBuf.begin());

    Halide::Runtime::Buffer<uint16_t> inputBuf(m_refFrame.data(), m_width, m_height, 1);
    outBayerBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->bayerBuf.data(), m_width, m_height);
    outRgbBuf = Halide::Runtime::Buffer<uint16_t>(outSharedResult->rgbBuf.data(), m_width, m_height, 3);
    Halide::Runtime::Buffer<float> ccmHalideBuf(m_ccm.data(), 3, 3);

    int halideCfa = 1;
    switch (m_cfaPattern) {
        case 0: halideCfa = 1; break; // RGGB
        case 1: halideCfa = 2; break; // GRBG
        case 2: halideCfa = 4; break; // GBRG
        case 3: halideCfa = 3; break; // BGGR
        default: halideCfa = 1; break;
    }

    Halide::Runtime::Buffer<float> lscMapBuf;
    std::vector<float> dummyLsc = {1.0f, 1.0f, 1.0f, 1.0f};
    if (m_lensShadingMap.empty() || m_lensShadingRows <= 0 || m_lensShadingCols <= 0) {
        lscMapBuf = Halide::Runtime::Buffer<float>(dummyLsc.data(), 1, 1, 4);
    } else {
        lscMapBuf = Halide::Runtime::Buffer<float>(const_cast<float*>(m_lensShadingMap.data()), m_lensShadingCols, m_lensShadingRows, 4);
    }

    int halide_res = hdrplus_single_pipeline(
        inputBuf,
        m_bl_r, m_bl_g0, m_bl_g1, m_bl_b,
        static_cast<uint16_t>(m_whiteLevel),
        m_wb_r, m_wb_g0, m_wb_g1, m_wb_b,
        halideCfa,
        ccmHalideBuf,
        lscMapBuf,
        1.0f, 1.0f,
        outRgbBuf,
        outBayerBuf
    );

    if (halide_res != 0) {
        LOGE("hdrplus_single_pipeline failed in streaming finish: %d", halide_res);
        return halide_res;
    }

    // High-Fidelity RCD Demosaicing (replaces Malvar 5x5 demosaic in outSharedResult->rgbBuf)
    if (effectiveMode != 3) {
        uint16_t bl_array[4] = {m_bl_r, m_bl_g0, m_bl_g1, m_bl_b};
        float wb_array[4] = {m_wb_r, m_wb_g0, m_wb_g1, m_wb_b};
        darkbag::demosaic::rcd_demosaic(
            outSharedResult->bayerBuf.data(),
            m_width,
            m_height,
            m_cfaPattern,
            bl_array,
            static_cast<uint16_t>(m_whiteLevel),
            wb_array,
            outSharedResult->rgbBuf.data()
        );
        outSharedResult->isWhiteBalanceApplied = false;
    } else {
        outSharedResult->isWhiteBalanceApplied = true;
    }

    LOGD("HdrPlusStreamingSession finish successful: %d frames accumulated into %dx%d result with %s demosaicing",
         m_framesPushed, m_width, m_height, (effectiveMode == 3) ? "Malvar" : "RCD");
    return 0;
}

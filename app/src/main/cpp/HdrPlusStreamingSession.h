#pragma once

#include <vector>
#include <memory>
#include <cstdint>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <HalideBuffer.h>
#include <HalideRuntime.h>

/**
 * Shared capture result holding normalized Bayer and demosaiced linear RGB buffers.
 * Stored in g_sharedMemoryMap for export and background processing.
 */
struct SharedCaptureResult {
    std::vector<uint16_t> bayerBuf; // width * height (Bayer CFA)
    std::vector<uint16_t> rgbBuf;   // width * height * 3 (Linear RGB)
    std::vector<double> noiseProfile;
};


/**
 * Streaming session for HDR+ burst accumulation.
 * Allows RAW Bayer frames to be pushed sequentially with constant O(1) memory,
 * performing Halide temporal weight accumulation with pure optical flow.
 */
class HdrPlusStreamingSession {
public:
    HdrPlusStreamingSession(
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
        int noiseProfileLen
    );

    ~HdrPlusStreamingSession();

    // Push an incoming RAW Bayer frame.
    // Frame 0 becomes the reference frame, subsequent frames are aligned and accumulated.
    bool pushFrame(const uint16_t* rawData, size_t numPixels);

    // Normalizes accumulated values and runs the single-frame pipeline to output RGB and Bayer.
    int finish(
        std::shared_ptr<SharedCaptureResult>& outSharedResult,
        Halide::Runtime::Buffer<uint16_t>& outRgbBuf,
        Halide::Runtime::Buffer<uint16_t>& outBayerBuf
    );

    int width() const { return m_width; }
    int height() const { return m_height; }
    int orientation() const { return m_orientation; }
    int whiteLevel() const { return m_whiteLevel; }
    int cfaPattern() const { return m_cfaPattern; }
    int framesPushed() const {
        std::lock_guard<std::mutex> lock(m_sessionMutex);
        return m_framesPushed;
    }

    const float* whiteBalanceData() const { return m_whiteBalance.data(); }
    const float* ccmData() const { return m_ccm.data(); }
    const float* lensShadingData() const { return m_lensShadingMap.empty() ? nullptr : m_lensShadingMap.data(); }
    int lensShadingRows() const { return m_lensShadingRows; }
    int lensShadingCols() const { return m_lensShadingCols; }

private:
    mutable std::mutex m_sessionMutex;
    int m_width;
    int m_height;
    int m_orientation;
    int m_whiteLevel;
    uint16_t m_bl_r;
    uint16_t m_bl_g0;
    uint16_t m_bl_g1;
    uint16_t m_bl_b;
    float m_wb_r;
    float m_wb_g0;
    float m_wb_g1;
    float m_wb_b;
    std::vector<float> m_whiteBalance;
    std::vector<float> m_ccm;
    int m_cfaPattern;
    std::vector<float> m_lensShadingMap;
    int m_lensShadingRows;
    int m_lensShadingCols;
    std::vector<double> m_noiseProfile;

    // Fixed O(1) ping-pong buffers

    std::vector<uint16_t> m_refFrame;
    std::vector<float> m_accumVal[2];
    std::vector<float> m_accumWeight[2];
    int m_accumIdx = 0;
    int m_framesPushed = 0;
};

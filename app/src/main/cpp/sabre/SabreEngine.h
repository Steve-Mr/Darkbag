#pragma once

#include <cstdint>
#include <vector>
#include <memory>
#include <algorithm>

namespace darkbag {
namespace sabre {

enum class CfaPattern {
    RGGB = 0,
    GRBG = 1,
    GBRG = 2,
    BGGR = 3
};

struct SabreConfig {
    int width = 0;
    int height = 0;
    CfaPattern cfa = CfaPattern::RGGB;
    uint16_t blackLevel[4] = {64, 64, 64, 64};
    uint16_t whiteLevel = 1023;
    float whiteBalance[4] = {2.0f, 1.0f, 1.0f, 1.5f};
    float zoomFactor = 1.0f; // Scale factor: >= 1.0f
    float noiseModelS = 1.0e-4f; // Poisson shot noise slope
    float noiseModelO = 1.0e-5f; // Read noise floor
    float sharpenStrength = 1.25f; // MTF restoration sharpening strength
    float coringThreshold = 2.0f;  // Noise coring threshold in sigma units
};

/**
 * SabreEngine -- Handheld Multi-Frame Super-Resolution Engine.
 *
 * Implements continuous subpixel sample regression from burst frames directly
 * onto the target RGB grid, steered by local Structure Tensors and anisotropic
 * covariance metrics, bypassing conventional demosaicing artifacts.
 *
 * When zoomFactor >= 1.0f, the cropped sensor region is mapped directly to
 * the full output resolution, recovering optical resolution lost to Bayer subsampling.
 */
class SabreEngine {
public:
    explicit SabreEngine(const SabreConfig& config);
    ~SabreEngine();

    // Initialize with the reference frame (Frame 0).
    // Computes local structure tensor J and anisotropic steering covariance matrix Omega.
    bool setReferenceFrame(const uint16_t* refBayer);

    // Accumulate an alternate frame (Frames 1..N-1) with optical flow tile vectors (dx, dy).
    // If flow is null or 0, falls back to global/local subpixel alignment.
    bool accumulateFrame(
        const uint16_t* altBayer,
        const float* flowX = nullptr,
        const float* flowY = nullptr,
        int flowWidth = 0,
        int flowHeight = 0
    );

    // Resolve accumulated subpixel samples directly into linear RGB (width * height * 3).
    // Optionally outputs synthesized/merged Bayer CFA (width * height) for DNG export.
    bool resolve(uint16_t* outRgb, uint16_t* outBayer = nullptr);

    int framesAccumulated() const { return m_framesAccumulated; }

private:
    SabreConfig m_config;
    int m_width;
    int m_height;
    size_t m_numPixels;
    int m_framesAccumulated;

    // Crop parameters for digital zoom
    float m_cropXStart;
    float m_cropYStart;
    float m_cropWidth;
    float m_cropHeight;

    // Reference frame Bayer backup
    std::vector<uint16_t> m_refBayer;

    // Local Structure Tensor & Anisotropic Covariance metrics (stored at half-res quad grid)
    int m_quadWidth;
    int m_quadHeight;
    // Anisotropic steering matrix elements: Omega_xx, Omega_yy, Omega_xy
    std::vector<float> m_covXX;
    std::vector<float> m_covYY;
    std::vector<float> m_covXY;

    // Running accumulators in target output RGB resolution (width * height)
    // 3 channels: 0=R, 1=G, 2=B
    std::vector<float> m_accumR;
    std::vector<float> m_accumG;
    std::vector<float> m_accumB;
    std::vector<float> m_weightR;
    std::vector<float> m_weightG;
    std::vector<float> m_weightB;

    // Helper methods
    void computeStructureTensorAndCovariance();
    int getBayerChannel(int x, int y) const; // Returns 0: R, 1: G, 2: B
};

} // namespace sabre
} // namespace darkbag

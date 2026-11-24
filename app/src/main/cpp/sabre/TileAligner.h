#pragma once

#include <cstdint>
#include <vector>
#include <cstddef>

namespace darkbag {
namespace sabre {

/**
 * TileAligner -- Hierarchical Tile Block Matching Aligner for Sabre Super-Resolution.
 *
 * Implements a 3-level image pyramid (Level 0: roiW/2 x roiH/2, Level 1: roiW/4 x roiH/4, Level 2: roiW/8 x roiH/8)
 * using Bayer Quad fast grayscale downsampling L = (R + 2G + B) / 4.
 *
 * When zoomFactor > 1.05x, pyramids are restricted to the centered sensor crop ROI
 * (+ safety margin) to focus compute on the active field of view; for zoomFactor >= 1.35x,
 * Level-0 tile size tightens from 16x16 quads (32px) to 8x8 quads (16px) for 4x denser flow,
 * followed by same-phase green-channel Gauss-Newton subpixel refinement.
 *
 * Outputs flow vectors in sensor coordinates: (dx_sensor, dy_sensor).
 */
class TileAligner {
public:
    TileAligner();
    ~TileAligner();

    TileAligner(const TileAligner&) = delete;
    TileAligner& operator=(const TileAligner&) = delete;
    TileAligner(TileAligner&&) noexcept = default;
    TileAligner& operator=(TileAligner&&) noexcept = default;

    /**
     * Set reference frame (Frame 0) Bayer CFA.
     * Computes Bayer Quad grayscale downsampling and builds the 3-level pyramid.
     *
     * @param refBayer Raw Bayer CFA 16-bit array (width * height).
     * @param width Sensor image width.
     * @param height Sensor image height.
     * @param cfaPattern CFA layout (0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR).
     * @param zoomFactor Digital zoom factor (>= 1.0f) for ROI & dense tile sizing.
     */
    void setReferenceFrame(const uint16_t* refBayer, int width, int height, int cfaPattern, float zoomFactor = 1.0f);

    /**
     * Align an alternate frame against the reference frame.
     * Computes the 3-level pyramid for the alternate frame and performs hierarchical SAD matching.
     *
     * @param altBayer Raw Bayer CFA 16-bit array of alternate frame.
     * @param outFlowX Output flattened X displacements in sensor coordinates (tilesX * tilesY).
     * @param outFlowY Output flattened Y displacements in sensor coordinates (tilesX * tilesY).
     * @param outFlowWidth Output number of tiles along X axis (tilesX).
     * @param outFlowHeight Output number of tiles along Y axis (tilesY).
     * @return true if alignment succeeded, false on error.
     */
    bool alignFrame(
        const uint16_t* altBayer,
        std::vector<float>& outFlowX,
        std::vector<float>& outFlowY,
        int& outFlowWidth,
        int& outFlowHeight
    );

    int width() const { return m_width; }
    int height() const { return m_height; }
    int tilesX() const { return m_tilesX; }
    int tilesY() const { return m_tilesY; }
    int roiXStart() const { return m_roiXStart; }
    int roiYStart() const { return m_roiYStart; }
    int roiWidth() const { return m_roiWidth; }
    int roiHeight() const { return m_roiHeight; }
    int tileStepSensor() const { return m_tileStepSensor; }
    float zoomFactor() const { return m_zoomFactor; }

private:
    int m_width = 0;
    int m_height = 0;
    int m_cfaPattern = 0;
    float m_zoomFactor = 1.0f;
    bool m_hasRefFrame = false;

    // Centered ROI in full-sensor coordinates (even-aligned)
    int m_roiXStart = 0;
    int m_roiYStart = 0;
    int m_roiWidth = 0;
    int m_roiHeight = 0;

    // Tile grid granularity (in Level-0 quad pixels and sensor pixels)
    int m_tileSizeLvl0 = 16;
    int m_tileStepSensor = 32;

    // Pyramid dimensions (active ROI image content)
    int m_lvl0Width = 0;
    int m_lvl0Height = 0;
    int m_lvl1Width = 0;
    int m_lvl1Height = 0;
    int m_lvl2Width = 0;
    int m_lvl2Height = 0;

    int m_tilesX = 0;
    int m_tilesY = 0;

    // Padding margin around each pyramid level
    static constexpr int PAD = 64;

    // Strides including padding on left and right (width + 2 * PAD)
    int m_stride0 = 0;
    int m_stride1 = 0;
    int m_stride2 = 0;

    // Reference ROI Bayer copy for same-phase green subpixel Gauss-Newton refinement
    std::vector<uint16_t> m_refRoiBayer;

    // Reference pyramids (with padding)
    std::vector<uint16_t> m_refLvl0;
    std::vector<uint16_t> m_refLvl1;
    std::vector<uint16_t> m_refLvl2;

    // Alternate pyramids (with padding, reused across frames)
    std::vector<uint16_t> m_altLvl0;
    std::vector<uint16_t> m_altLvl1;
    std::vector<uint16_t> m_altLvl2;

    // Intermediate flow vectors (reused across frames)
    std::vector<int16_t> m_flowLvl2X;
    std::vector<int16_t> m_flowLvl2Y;
    std::vector<int16_t> m_flowLvl1X;
    std::vector<int16_t> m_flowLvl1Y;

    void allocateBuffers(int width, int height, float zoomFactor);
    void buildPyramid(
        const uint16_t* bayer,
        std::vector<uint16_t>& lvl0,
        std::vector<uint16_t>& lvl1,
        std::vector<uint16_t>& lvl2
    );
    void refineFlowSubpixelGreen(
        const uint16_t* altBayer,
        std::vector<float>& flowX,
        std::vector<float>& flowY
    );
};

} // namespace sabre
} // namespace darkbag

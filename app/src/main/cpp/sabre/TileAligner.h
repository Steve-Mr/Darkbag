#pragma once

#include <cstdint>
#include <vector>
#include <cstddef>

namespace darkbag {
namespace sabre {

/**
 * TileAligner -- Hierarchical Tile Block Matching Aligner for Sabre Super-Resolution.
 *
 * Implements a 3-level image pyramid (Level 0: W/2 x H/2, Level 1: W/4 x H/4, Level 2: W/8 x H/8)
 * using Bayer Quad fast grayscale downsampling L = (R + 2G + B) / 4.
 *
 * Hierarchical matching:
 * - Level 2 (4x4 tile): Coarse search [-4, +4] with OpenMP parallel SAD.
 * - Level 1 (8x8 tile): Neighborhood [-1, +1] around predicted 2 * (dx2, dy2).
 * - Level 0 (16x16 tile): Fine search [-1, +1] around predicted 2 * (dx1, dy1)
 *   followed by subpixel parabolic interpolation.
 *
 * Outputs flow vectors in sensor coordinates: (dx_sensor, dy_sensor) = 2.0 * (dx0 + dx_sub, dy0 + dy_sub).
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
     */
    void setReferenceFrame(const uint16_t* refBayer, int width, int height, int cfaPattern);

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

private:
    int m_width = 0;
    int m_height = 0;
    int m_cfaPattern = 0;
    bool m_hasRefFrame = false;

    // Pyramid dimensions (active image content)
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

    void allocateBuffers(int width, int height);
    void buildPyramid(
        const uint16_t* bayer,
        std::vector<uint16_t>& lvl0,
        std::vector<uint16_t>& lvl1,
        std::vector<uint16_t>& lvl2
    );
};

} // namespace sabre
} // namespace darkbag

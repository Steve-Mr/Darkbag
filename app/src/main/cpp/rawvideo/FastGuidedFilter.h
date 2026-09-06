#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

namespace rawvideo {

class FastGuidedFilter {
public:
    /**
     * Applies Fast Guided Filter to 16-bit planar RGB data [R plane, G plane, B plane].
     * Uses Green channel (G) as guidance image for R, G, and B.
     *
     * @param planarRgb Pointer to buffer of size 3 * width * height uint16_t elements
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param iso Camera ISO (used to adapt regularizer epsilon)
     * @param radius Filter radius at full scale (default: 4)
     */
    static void filterPlanarRGB(
        uint16_t* planarRgb,
        int width,
        int height,
        int iso,
        int radius = 4
    );

private:
    static void boxFilter1D_X(
        const float* src,
        float* dst,
        int width,
        int height,
        int r
    );

    static void boxFilter1D_Y(
        const float* src,
        float* dst,
        int width,
        int height,
        int r
    );

    static void boxFilter2D(
        const float* src,
        float* dst,
        float* temp,
        int width,
        int height,
        int r
    );
};

} // namespace rawvideo

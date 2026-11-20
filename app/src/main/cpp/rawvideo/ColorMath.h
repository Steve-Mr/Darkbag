#pragma once

#include <cmath>
#include <algorithm>
#include <cstring>
#include <cstdint>
#include "color/WhiteBalanceEngine.h"

namespace darkbag {
namespace rawvideo {

/**
 * Computes an adaptive 3x3 color matrix based on DNG ForwardMatrix1 / ForwardMatrix2
 * interpolated by 2D Robertson Correlated Color Temperature (CCT) derived from the Neutral Point (AsShotNeutral).
 *
 * @param neutralPoint   Array of 3 floats representing the camera neutral point (AsShotNeutral).
 * @param fm1            DNG ForwardMatrix1 (3x3 row-major) or nullptr.
 * @param fm2            DNG ForwardMatrix2 (3x3 row-major) or nullptr.
 * @param illum1         Calibration illuminant 1 (e.g. 17 for StdA, 21 for D65).
 * @param illum2         Calibration illuminant 2 (e.g. 17 for StdA, 21 for D65).
 * @param outRowMajor    Output buffer for 3x3 row-major matrix (for CPU software rendering), or nullptr.
 * @param outColMajor    Output buffer for 3x3 col-major matrix (for OpenGL glUniformMatrix3fv), or nullptr.
 */
inline void computeAdaptiveColorMatrix(
    const float* neutralPoint,
    const float* fm1,
    const float* fm2,
    int illum1,
    int illum2,
    float outRowMajor[9],
    float outColMajor[9] = nullptr
) {
    if (outRowMajor) {
        outRowMajor[0] = 1.0f; outRowMajor[1] = 0.0f; outRowMajor[2] = 0.0f;
        outRowMajor[3] = 0.0f; outRowMajor[4] = 1.0f; outRowMajor[5] = 0.0f;
        outRowMajor[6] = 0.0f; outRowMajor[7] = 0.0f; outRowMajor[8] = 1.0f;
    }
    if (outColMajor) {
        outColMajor[0] = 1.0f; outColMajor[1] = 0.0f; outColMajor[2] = 0.0f;
        outColMajor[3] = 0.0f; outColMajor[4] = 1.0f; outColMajor[5] = 0.0f;
        outColMajor[6] = 0.0f; outColMajor[7] = 0.0f; outColMajor[8] = 1.0f;
    }

    if (!fm1 && !fm2) return;

    float compRowMajor[9] = {1,0,0, 0,1,0, 0,0,1};
    bool ok = darkbag::color::compute_render_ccm(
        fm1, fm2,
        nullptr, nullptr,
        illum1 ? illum1 : 21,
        illum2 ? illum2 : 17,
        neutralPoint,
        nullptr,
        compRowMajor
    );

    if (ok) {
        if (outRowMajor) {
            std::copy(compRowMajor, compRowMajor + 9, outRowMajor);
        }
        if (outColMajor) {
            for (int r = 0; r < 3; ++r) {
                for (int c = 0; c < 3; ++c) {
                    outColMajor[c * 3 + r] = compRowMajor[r * 3 + c];
                }
            }
        }
    }
}

} // namespace rawvideo
} // namespace darkbag

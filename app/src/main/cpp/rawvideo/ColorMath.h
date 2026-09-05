#pragma once

#include <cmath>
#include <algorithm>
#include <cstring>
#include <cstdint>

namespace darkbag {
namespace rawvideo {

/**
 * Computes an adaptive 3x3 color matrix based on DNG ForwardMatrix1 / ForwardMatrix2
 * interpolated by Correlated Color Temperature (CCT) derived from the Neutral Point (AsShotNeutral).
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

    const float* activeFm = fm1 ? fm1 : fm2;
    bool isTrivial = true;
    if (activeFm) {
        if (std::abs(activeFm[0] - 1.0f) > 0.01f || std::abs(activeFm[1]) > 0.01f ||
            std::abs(activeFm[4] - 1.0f) > 0.01f || std::abs(activeFm[8] - 1.0f) > 0.01f) {
            isTrivial = false;
        }
    }
    if (isTrivial && (!fm2 || std::abs(fm2[0] - 1.0f) <= 0.01f)) return;

    float interpFm[9];
    if (fm1 && fm2 && !isTrivial) {
        float t1 = (illum1 == 17) ? 2856.0f : (illum1 == 21) ? 6504.0f : 5000.0f;
        float t2 = (illum2 == 17) ? 2856.0f : (illum2 == 21) ? 6504.0f : 5000.0f;
        if (std::abs(t1 - t2) < 100.0f) {
            t1 = 2856.0f;
            t2 = 6504.0f;
        }

        float wbR = neutralPoint ? neutralPoint[0] : 0.5f;
        float wbB = neutralPoint ? neutralPoint[2] : 0.7f;
        float cct = 5500.0f;
        if (wbR > 0.001f && wbB > 0.001f) {
            float ratio = wbB / wbR;
            cct = 2000.0f + ratio * 3500.0f;
        }

        float m1 = 1.0e6f / t1;
        float m2 = 1.0e6f / t2;
        float mCur = 1.0e6f / std::clamp(cct, 2000.0f, 10000.0f);
        float weight = std::clamp((mCur - m2) / (m1 - m2), 0.0f, 1.0f);

        for (int i = 0; i < 9; ++i) {
            interpFm[i] = weight * fm1[i] + (1.0f - weight) * fm2[i];
        }
    } else if (fm1) {
        std::copy(fm1, fm1 + 9, interpFm);
    } else {
        std::copy(fm2, fm2 + 9, interpFm);
    }

    const float M_xyz_bradford[9] = {
        3.1338561f, -1.6168667f, -0.4906146f,
       -0.9787684f,  1.9161415f,  0.0334540f,
        0.0719453f, -0.2289914f,  1.4052427f
    };

    float compRowMajor[9];
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            compRowMajor[r * 3 + c] = 
                M_xyz_bradford[r * 3 + 0] * interpFm[0 * 3 + c] +
                M_xyz_bradford[r * 3 + 1] * interpFm[1 * 3 + c] +
                M_xyz_bradford[r * 3 + 2] * interpFm[2 * 3 + c];
        }
    }

    if (outRowMajor) {
        std::copy(compRowMajor, compRowMajor + 9, outRowMajor);
    }

    if (outColMajor) {
        // Convert row-major composite matrix to column-major order for glUniformMatrix3fv (transpose=GL_FALSE)
        for (int r = 0; r < 3; ++r) {
            for (int c = 0; c < 3; ++c) {
                outColMajor[c * 3 + r] = compRowMajor[r * 3 + c];
            }
        }
    }
}

} // namespace rawvideo
} // namespace darkbag

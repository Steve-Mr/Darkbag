#ifndef COLOR_MATRICES_H
#define COLOR_MATRICES_H

#include <cmath>
#include <algorithm>

// Matrix 3x3
struct Mat3x3 {
    float m[3][3];
};

// Matrix Multiplication (A * B)
inline Mat3x3 mat_mul(const Mat3x3& A, const Mat3x3& B) {
    Mat3x3 C = {{{0,0,0},{0,0,0},{0,0,0}}};
    for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
            for (int k = 0; k < 3; k++) {
                C.m[i][j] += A.m[i][k] * B.m[k][j];
            }
        }
    }
    return C;
}

// Matrix Vector Multiplication (M * v)
inline void mat_vec(const Mat3x3& M, float in[3], float out[3]) {
    out[0] = M.m[0][0] * in[0] + M.m[0][1] * in[1] + M.m[0][2] * in[2];
    out[1] = M.m[1][0] * in[0] + M.m[1][1] * in[1] + M.m[1][2] * in[2];
    out[2] = M.m[2][0] * in[0] + M.m[2][1] * in[1] + M.m[2][2] * in[2];
}

// --- Pre-calculated Matrices ---

// XYZ (D50) -> ProPhoto RGB (D50)
// Inverted from ProPhoto (D50) -> XYZ (D50)
static const Mat3x3 M_XYZ_D50_to_ProPhoto = {{
    {1.345956f, -0.255610f, -0.051112f},
    {-0.544597f, 1.508161f, 0.020535f},
    {0.000000f, 0.000000f, 1.211845f}
}};

// Target Gamut Conversions (ProPhoto D50 -> Target D65)
// Includes Bradford Adaptation D50 -> D65

// 1. Alexa Wide Gamut (Arri LogC3)
static const Mat3x3 M_ProPhoto_D50_to_AWG_D65 = {{
    {1.106372f, -0.029053f, -0.077319f},
    {-0.129433f, 1.108779f, 0.020653f},
    {0.005041f, -0.051099f, 1.046058f}
}};

// 2. S-Gamut3 (S-Log3)
static const Mat3x3 M_ProPhoto_D50_to_SG3_D65 = {{
    {1.072319f, -0.003596f, -0.068723f},
    {-0.027327f, 0.909242f, 0.118085f},
    {0.013176f, -0.015668f, 1.002491f}
}};

// 3. Rec.2020 (F-Log)
static const Mat3x3 M_ProPhoto_D50_to_Rec2020_D65 = {{
    {1.200620f, -0.057500f, -0.143119f},
    {-0.069926f, 1.080609f, -0.010683f},
    {0.005538f, -0.040778f, 1.035241f}
}};

// 4. V-Gamut (V-Log)
static const Mat3x3 M_ProPhoto_D50_to_VG_D65 = {{
    {1.115866f, -0.042460f, -0.073406f},
    {-0.028533f, 0.936797f, 0.091736f},
    {0.012848f, -0.008158f, 0.995310f}
}};

// 5. Rec.709 / sRGB (None / Gamma)
static const Mat3x3 M_ProPhoto_D50_to_Rec709_D65 = {{
    {2.034314f, -0.727536f, -0.306778f},
    {-0.228799f, 1.231719f, -0.002920f},
    {-0.008566f, -0.153283f, 1.161849f}
}};

#endif // COLOR_MATRICES_H

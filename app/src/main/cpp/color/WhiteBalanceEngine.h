#ifndef DARKBAG_COLOR_WHITE_BALANCE_ENGINE_H
#define DARKBAG_COLOR_WHITE_BALANCE_ENGINE_H

#include <cstdint>
#include <cmath>
#include <algorithm>

namespace darkbag::color {

// Standard CIE White Points (Y = 1.0, precision 1e-7)
inline constexpr float kW_D50[3] = {0.9642200f, 1.0000000f, 0.8252100f};
inline constexpr float kW_D65[3] = {0.9504559f, 1.0000000f, 1.0890578f};

// CIE XYZ D50 to sRGB (Bradford-adapted, D65 sRGB destination)
inline constexpr float kM_XYZ_D50_TO_SRGB[9] = {
    3.1338561f, -1.6168667f, -0.4906146f,
   -0.9787684f,  1.9161415f,  0.0334540f,
    0.0719453f, -0.2289914f,  1.4052427f
};

// CIE 2016 CAT16 Forward Matrix (Row-major, precision 1e-7)
inline constexpr float kM_CAT16[9] = {
    +0.4012880f, +0.6501730f, -0.0514610f,
    -0.2502680f, +1.2044140f, +0.0458540f,
    -0.0020790f, +0.0489520f, +0.9531270f
};

// CIE 2016 CAT16 Inverse Matrix (Row-major, precision 1e-7)
inline constexpr float kM_CAT16_INV[9] = {
    +1.8620679f, -1.0112546f, +0.1491868f,
    +0.3875265f, +0.6214474f, -0.0089740f,
    -0.0158415f, -0.0341229f, +1.0499644f
};

enum class WhiteBalanceMode : int {
    NEUTRAL = 0,          // 100% 忠实于场景中性点 (AsShotNeutral)
    AMBIENCE = 1,         // 暖光氛围保留 + 普朗克黑体轨迹 Tint 软钳制
    PRESET_OR_MANUAL = 2  // 绝对目标色温/色调 (manualTempK, manualTint)
};

struct WhiteBalanceParams {
    WhiteBalanceMode mode = WhiteBalanceMode::NEUTRAL;
    float tempOffsetMired = 0.0f;  // [-150, +150], 正值变暖
    float tintOffset = 0.0f;       // [-100, +100], 正值偏品红
    float manualTempK = 5500.0f;   // 仅 PRESET_OR_MANUAL 生效
    float manualTint = 0.0f;       // 仅 PRESET_OR_MANUAL 生效
};

struct WhiteBalanceResult {
    float wbGain[4] = {1.0f, 1.0f, 1.0f, 1.0f};   // 归一化传感器增益 [R, Ge, Go, B], G=1
    float neutral[3] = {1.0f, 1.0f, 1.0f};        // 物理中性点 (用于 DNG AsShotNeutral)
    float renderCcm[9] = {1,0,0, 0,1,0, 0,0,1};   // 含 CAT16 的 Sensor->sRGB_D65 矩阵
    float sceneTempK = 5500.0f;
    float sceneTint = 0.0f;
    float targetTempK = 5500.0f;
    float targetTint = 0.0f;
    float illuminantWeight = 1.0f;
    bool usedForwardMatrix = false;
    bool valid = false;
};

// 3x3 Matrix Math Helpers (Row-major)
void mat3_multiply(const float a[9], const float b[9], float out[9]);
void mat3_vec3_multiply(const float m[9], const float v[3], float out[3]);
bool mat3_invert(const float m[9], float out[9]);
float mat3_det(const float m[9]);
void mat3_identity(float out[9]);

// Color Space Conversions
void xy_to_uv(float x, float y, float& u, float& v);
void uv_to_xy(float u, float v, float& x, float& y);
void xy_to_xyz(float x, float y, float outXyz[3]);

// 2D Robertson Mapping (bidirectional)
void xy_to_temp_tint(float x, float y, float& outTempK, float& outTint, float* outDuv = nullptr);
void temp_tint_to_xy(float tempK, float tint, float& outX, float& outY);

// Illuminant Conversion
float illuminant_to_temp(int illuminant);

// CAT16 Chromatic Adaptation
void cat16_adapt_matrix(const float srcWhiteXyz[3], const float dstWhiteXyz[3], float outAdaptMat[9]);

// Canonical White Balance Resolver (Invariant 1 & Invariant 2 Contract)
bool resolve_canonical_wb(
    const float* neutralColorPoint,
    const float* colorCorrectionGains,
    float outWbGain[4],
    float outNeutral[3]
);

// Full White Balance & Render CCM Synthesizer
void compute_white_balance(
    const float* forwardMatrix1,
    const float* forwardMatrix2,
    const float* colorMatrix1,
    const float* colorMatrix2,
    int calibIllum1,
    int calibIllum2,
    const float* neutralColorPoint,
    const float* wb,
    const WhiteBalanceParams& params,
    WhiteBalanceResult& outResult
);

// Backward compatibility facade for ColorPipe and RawVideo
bool compute_render_ccm(
    const float* forwardMatrix1,
    const float* forwardMatrix2,
    const float* colorMatrix1,
    const float* colorMatrix2,
    int calibIllum1,
    int calibIllum2,
    const float* neutralColorPoint,
    const float* wb,
    float* outCcm
);

// Cross-camera CIE xy Neutral Point Mapping (Section 3.7)
bool map_neutral_across_cameras(
    const float mainNeutral[3],
    const float mainCm1[9],
    const float mainCm2[9],
    int mainIllum1,
    int mainIllum2,
    const float subCm1[9],
    const float subCm2[9],
    int subIllum1,
    int subIllum2,
    float outSubNeutral[3],
    float outSubWbGain[4]
);

} // namespace darkbag::color

#endif // DARKBAG_COLOR_WHITE_BALANCE_ENGINE_H

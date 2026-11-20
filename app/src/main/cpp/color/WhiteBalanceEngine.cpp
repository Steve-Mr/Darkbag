#include "WhiteBalanceEngine.h"

namespace darkbag::color {

struct RobertsonRow {
    float r; // Mired (1e6 / T)
    float u; // CIE 1960 UCS
    float v; // CIE 1960 UCS
    float t; // slope
};

// 31-entry Robertson Isotemperature Table (Wyszecki & Stiles, 2nd ed. / Adobe DNG SDK)
static constexpr RobertsonRow kRobertsonTable[31] = {
    {0.0f,   0.18006f, 0.26352f, -0.24341f},
    {10.0f,  0.18066f, 0.26589f, -0.25479f},
    {20.0f,  0.18133f, 0.26846f, -0.26876f},
    {30.0f,  0.18208f, 0.27119f, -0.28539f},
    {40.0f,  0.18293f, 0.27407f, -0.30470f},
    {50.0f,  0.18388f, 0.27709f, -0.32675f},
    {60.0f,  0.18494f, 0.28021f, -0.35156f},
    {70.0f,  0.18611f, 0.28342f, -0.37915f},
    {80.0f,  0.18740f, 0.28668f, -0.40955f},
    {90.0f,  0.18880f, 0.28997f, -0.44278f},
    {100.0f, 0.19032f, 0.29326f, -0.47888f},
    {125.0f, 0.19462f, 0.30141f, -0.58204f},
    {150.0f, 0.19962f, 0.30921f, -0.70471f},
    {175.0f, 0.20525f, 0.31647f, -0.84901f},
    {200.0f, 0.21142f, 0.32312f, -1.01820f},
    {225.0f, 0.21807f, 0.32909f, -1.21680f},
    {250.0f, 0.22511f, 0.33439f, -1.45120f},
    {275.0f, 0.23247f, 0.33904f, -1.72980f},
    {300.0f, 0.24010f, 0.34308f, -2.06370f},
    {325.0f, 0.24702f, 0.34655f, -2.46810f},
    {350.0f, 0.25591f, 0.34951f, -2.96410f},
    {375.0f, 0.26400f, 0.35200f, -3.58140f},
    {400.0f, 0.27218f, 0.35407f, -4.36330f},
    {425.0f, 0.28039f, 0.35577f, -5.37620f},
    {450.0f, 0.28863f, 0.35714f, -6.72620f},
    {475.0f, 0.29685f, 0.35821f, -8.49550f},
    {500.0f, 0.30505f, 0.35903f, -10.8410f},
    {525.0f, 0.31320f, 0.35961f, -13.9940f},
    {550.0f, 0.32129f, 0.35998f, -18.2850f},
    {575.0f, 0.32931f, 0.36017f, -24.2220f},
    {600.0f, 0.33724f, 0.36020f, -32.6130f}
};

void mat3_multiply(const float a[9], const float b[9], float out[9]) {
    float temp[9];
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            float sum = 0.0f;
            for (int k = 0; k < 3; ++k) {
                sum += a[r * 3 + k] * b[k * 3 + c];
            }
            temp[r * 3 + c] = sum;
        }
    }
    for (int i = 0; i < 9; ++i) out[i] = temp[i];
}

void mat3_vec3_multiply(const float m[9], const float v[3], float out[3]) {
    float temp[3];
    for (int r = 0; r < 3; ++r) {
        temp[r] = m[r * 3 + 0] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2];
    }
    out[0] = temp[0]; out[1] = temp[1]; out[2] = temp[2];
}

float mat3_det(const float m[9]) {
    return m[0] * (m[4] * m[8] - m[5] * m[7])
         - m[1] * (m[3] * m[8] - m[5] * m[6])
         + m[2] * (m[3] * m[7] - m[4] * m[6]);
}

bool mat3_invert(const float m[9], float out[9]) {
    float a00 = m[0], a01 = m[1], a02 = m[2];
    float a10 = m[3], a11 = m[4], a12 = m[5];
    float a20 = m[6], a21 = m[7], a22 = m[8];

    float c00 = a11 * a22 - a12 * a21;
    float c01 = a12 * a20 - a10 * a22;
    float c02 = a10 * a21 - a11 * a20;

    float det = a00 * c00 + a01 * c01 + a02 * c02;
    if (std::abs(det) < 1e-9f) {
        mat3_identity(out);
        return false;
    }
    float invDet = 1.0f / det;

    float c10 = a02 * a21 - a01 * a22;
    float c11 = a00 * a22 - a02 * a20;
    float c12 = a01 * a20 - a00 * a21;

    float c20 = a01 * a12 - a02 * a11;
    float c21 = a02 * a10 - a00 * a12;
    float c22 = a00 * a11 - a01 * a10;

    out[0] = c00 * invDet; out[1] = c10 * invDet; out[2] = c20 * invDet;
    out[3] = c01 * invDet; out[4] = c11 * invDet; out[5] = c21 * invDet;
    out[6] = c02 * invDet; out[7] = c12 * invDet; out[8] = c22 * invDet;
    return true;
}

void mat3_identity(float out[9]) {
    for (int i = 0; i < 9; ++i) out[i] = (i % 4 == 0) ? 1.0f : 0.0f;
}

void xy_to_uv(float x, float y, float& u, float& v) {
    float denom = 1.5f - x + 6.0f * y;
    if (std::abs(denom) < 1e-6f) {
        u = 0.20917f;
        v = 0.30727f; // D65 fallback
        return;
    }
    u = 2.0f * x / denom;
    v = 3.0f * y / denom;
}

void uv_to_xy(float u, float v, float& x, float& y) {
    float denom = 2.0f * u - 8.0f * v + 4.0f;
    if (std::abs(denom) < 1e-6f) {
        x = 0.3127f;
        y = 0.3290f; // D65 fallback
        return;
    }
    x = 3.0f * u / denom;
    y = 2.0f * v / denom;
}

void xy_to_xyz(float x, float y, float outXyz[3]) {
    float safeY = std::max(1e-5f, y);
    outXyz[0] = x / safeY;
    outXyz[1] = 1.0f;
    outXyz[2] = (1.0f - x - y) / safeY;
}

void xy_to_temp_tint(float x, float y, float& outTempK, float& outTint, float* outDuv) {
    float u, v;
    xy_to_uv(x, y, u, v);

    float lastDt = 0.0f;
    for (int i = 1; i < 31; ++i) {
        float lenI = std::sqrt(1.0f + kRobertsonTable[i].t * kRobertsonTable[i].t);
        float duI = 1.0f / lenI;
        float dvI = kRobertsonTable[i].t / lenI;

        float uu = u - kRobertsonTable[i].u;
        float vv = v - kRobertsonTable[i].v;
        float dt = -uu * dvI + vv * duI;

        if (dt <= 0.0f || i == 30) {
            if (dt > 0.0f) dt = 0.0f;
            float negDt = -dt;
            float f = (i == 1) ? 0.0f : (negDt / std::max(1e-6f, lastDt + negDt));

            float r = kRobertsonTable[i - 1].r * f + kRobertsonTable[i].r * (1.0f - f);
            outTempK = (r > 20.0f) ? (1e6f / r) : (1e6f / 20.0f);

            float u0 = kRobertsonTable[i - 1].u * f + kRobertsonTable[i].u * (1.0f - f);
            float v0 = kRobertsonTable[i - 1].v * f + kRobertsonTable[i].v * (1.0f - f);

            float lenPrev = std::sqrt(1.0f + kRobertsonTable[i - 1].t * kRobertsonTable[i - 1].t);
            float duPrev = 1.0f / lenPrev;
            float dvPrev = kRobertsonTable[i - 1].t / lenPrev;

            float duPrime = f * duPrev + (1.0f - f) * duI;
            float dvPrime = f * dvPrev + (1.0f - f) * dvI;
            float dirLen = std::sqrt(duPrime * duPrime + dvPrime * dvPrime);
            float du = duPrime / std::max(1e-6f, dirLen);
            float dv = dvPrime / std::max(1e-6f, dirLen);

            outTint = 3000.0f * ((u - u0) * du + (v - v0) * dv);
            if (outDuv) {
                *outDuv = -outTint / 3000.0f;
            }
            return;
        }
        lastDt = dt;
    }

    outTempK = 6504.0f;
    outTint = 0.0f;
    if (outDuv) *outDuv = 0.0f;
}

void temp_tint_to_xy(float tempK, float tint, float& outX, float& outY) {
    float tc = std::clamp(tempK, 1667.0f, 50000.0f);
    float r = 1e6f / tc;

    int idx = 1;
    for (int i = 1; i < 31; ++i) {
        if (r <= kRobertsonTable[i].r || i == 30) {
            idx = i;
            break;
        }
    }

    float f = (kRobertsonTable[idx].r - r) / std::max(1e-6f, kRobertsonTable[idx].r - kRobertsonTable[idx - 1].r);
    f = std::clamp(f, 0.0f, 1.0f);

    float u0 = f * kRobertsonTable[idx - 1].u + (1.0f - f) * kRobertsonTable[idx].u;
    float v0 = f * kRobertsonTable[idx - 1].v + (1.0f - f) * kRobertsonTable[idx].v;

    float lenPrev = std::sqrt(1.0f + kRobertsonTable[idx - 1].t * kRobertsonTable[idx - 1].t);
    float duPrev = 1.0f / lenPrev;
    float dvPrev = kRobertsonTable[idx - 1].t / lenPrev;

    float lenCurr = std::sqrt(1.0f + kRobertsonTable[idx].t * kRobertsonTable[idx].t);
    float duCurr = 1.0f / lenCurr;
    float dvCurr = kRobertsonTable[idx].t / lenCurr;

    float duPrime = f * duPrev + (1.0f - f) * duCurr;
    float dvPrime = f * dvPrev + (1.0f - f) * dvCurr;
    float dirLen = std::sqrt(duPrime * duPrime + dvPrime * dvPrime);
    float du = duPrime / std::max(1e-6f, dirLen);
    float dv = dvPrime / std::max(1e-6f, dirLen);

    float u = u0 + (tint / 3000.0f) * du;
    float v = v0 + (tint / 3000.0f) * dv;

    uv_to_xy(u, v, outX, outY);
}

float illuminant_to_temp(int illuminant) {
    switch (illuminant) {
        case 17:
        case 3:  return 2856.0f; // Standard Light A / Tungsten
        case 21: return 6504.0f; // D65
        case 20: return 5500.0f; // D55
        case 22: return 7500.0f; // D75
        case 1:
        case 4:  return 5500.0f; // Daylight / Flash
        case 2:  return 4000.0f; // Fluorescent
        default: return (illuminant == 17) ? 2856.0f : 6504.0f;
    }
}

void cat16_adapt_matrix(const float srcWhiteXyz[3], const float dstWhiteXyz[3], float outAdaptMat[9]) {
    float lmsSrc[3], lmsDst[3];
    mat3_vec3_multiply(kM_CAT16, srcWhiteXyz, lmsSrc);
    mat3_vec3_multiply(kM_CAT16, dstWhiteXyz, lmsDst);

    float diag[9] = {0};
    diag[0] = lmsDst[0] / ((std::abs(lmsSrc[0]) > 1e-5f) ? lmsSrc[0] : 1e-5f);
    diag[4] = lmsDst[1] / ((std::abs(lmsSrc[1]) > 1e-5f) ? lmsSrc[1] : 1e-5f);
    diag[8] = lmsDst[2] / ((std::abs(lmsSrc[2]) > 1e-5f) ? lmsSrc[2] : 1e-5f);

    float temp[9];
    mat3_multiply(kM_CAT16_INV, diag, temp);
    mat3_multiply(temp, kM_CAT16, outAdaptMat);
}

bool resolve_canonical_wb(
    const float* neutralColorPoint,
    const float* colorCorrectionGains,
    float outWbGain[4],
    float outNeutral[3]
) {
    // Priority 1: SENSOR_NEUTRAL_COLOR_POINT / AsShotNeutral [nR, nG, nB]
    if (neutralColorPoint &&
        std::isfinite(neutralColorPoint[0]) && neutralColorPoint[0] > 1e-4f &&
        std::isfinite(neutralColorPoint[1]) && neutralColorPoint[1] > 1e-4f &&
        std::isfinite(neutralColorPoint[2]) && neutralColorPoint[2] > 1e-4f) {
        float nG = neutralColorPoint[1];
        float nR = neutralColorPoint[0] / nG;
        float nB = neutralColorPoint[2] / nG;
        if (outNeutral) {
            outNeutral[0] = nR;
            outNeutral[1] = 1.0f;
            outNeutral[2] = nB;
        }
        if (outWbGain) {
            outWbGain[0] = 1.0f / nR;
            outWbGain[1] = 1.0f;
            outWbGain[2] = 1.0f;
            outWbGain[3] = 1.0f / nB;
        }
        return true;
    }

    // Priority 2: COLOR_CORRECTION_GAINS [gR, gGe, gGo, gB]
    if (colorCorrectionGains &&
        std::isfinite(colorCorrectionGains[0]) && colorCorrectionGains[0] > 1e-4f &&
        std::isfinite(colorCorrectionGains[3]) && colorCorrectionGains[3] > 1e-4f &&
        ((std::isfinite(colorCorrectionGains[1]) && colorCorrectionGains[1] > 1e-4f) ||
         (std::isfinite(colorCorrectionGains[2]) && colorCorrectionGains[2] > 1e-4f))) {
        float gGe = (std::isfinite(colorCorrectionGains[1]) && colorCorrectionGains[1] > 1e-4f)
                        ? colorCorrectionGains[1]
                        : colorCorrectionGains[2];
        float gGo = (std::isfinite(colorCorrectionGains[2]) && colorCorrectionGains[2] > 1e-4f)
                        ? colorCorrectionGains[2]
                        : colorCorrectionGains[1];
        float gAvg = 0.5f * (gGe + gGo);
        float effGAvg = (gAvg > 1e-4f) ? gAvg : 1.0f;
        float gR = colorCorrectionGains[0] / effGAvg;
        float gB = colorCorrectionGains[3] / effGAvg;
        if (outWbGain) {
            outWbGain[0] = gR;
            outWbGain[1] = 1.0f;
            outWbGain[2] = 1.0f;
            outWbGain[3] = gB;
        }
        if (outNeutral) {
            outNeutral[0] = 1.0f / gR;
            outNeutral[1] = 1.0f;
            outNeutral[2] = 1.0f / gB;
        }
        return true;
    }

    // Priority 3: Safe fallback
    if (outNeutral) {
        outNeutral[0] = 0.50f;
        outNeutral[1] = 1.00f;
        outNeutral[2] = 1.0f / 1.5f;
    }
    if (outWbGain) {
        outWbGain[0] = 2.0f;
        outWbGain[1] = 1.0f;
        outWbGain[2] = 1.0f;
        outWbGain[3] = 1.5f;
    }
    return false;
}

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
) {
    // 1. Resolve canonical WB and physical neutral
    resolve_canonical_wb(neutralColorPoint, wb, outResult.wbGain, outResult.neutral);

    float t1 = illuminant_to_temp(calibIllum1);
    float t2 = illuminant_to_temp(calibIllum2);
    float m1 = 1e6f / t1;
    float m2 = 1e6f / t2;

    float weight = 1.0f;
    float sceneTempK = 5500.0f;
    float sceneTint = 0.0f;
    float W_scene[3] = {kW_D65[0], kW_D65[1], kW_D65[2]};

    // 2. 10-iteration damped fixed-point iteration for scene illuminant (Section 3.4)
    if (colorMatrix1) {
        const float* cm1 = colorMatrix1;
        const float* cm2 = colorMatrix2 ? colorMatrix2 : colorMatrix1;

        float lastX = 0.34567f; // D50 initial guess
        float lastY = 0.35850f;
        bool iterSuccess = true;

        for (int pass = 0; pass < 10; ++pass) {
            float tempK, tint;
            xy_to_temp_tint(lastX, lastY, tempK, tint);

            float g = (std::abs(m1 - m2) > 1e-4f)
                          ? std::clamp((1e6f / tempK - m2) / (m1 - m2), 0.0f, 1.0f)
                          : 1.0f;

            float cm[9];
            for (int i = 0; i < 9; ++i) cm[i] = g * cm1[i] + (1.0f - g) * cm2[i];

            float invCm[9];
            if (!mat3_invert(cm, invCm)) {
                iterSuccess = false;
                break;
            }

            float xX = invCm[0] * outResult.neutral[0] + invCm[1] * outResult.neutral[1] + invCm[2] * outResult.neutral[2];
            float xY = invCm[3] * outResult.neutral[0] + invCm[4] * outResult.neutral[1] + invCm[5] * outResult.neutral[2];
            float xZ = invCm[6] * outResult.neutral[0] + invCm[7] * outResult.neutral[1] + invCm[8] * outResult.neutral[2];

            float sum = xX + xY + xZ;
            if (std::abs(sum) < 1e-6f) {
                iterSuccess = false;
                break;
            }

            float rawNextX = xX / sum;
            float rawNextY = xY / sum;
            float nextX = 0.5f * lastX + 0.5f * rawNextX;
            float nextY = 0.5f * lastY + 0.5f * rawNextY;

            if (std::abs(nextX - lastX) + std::abs(nextY - lastY) < 1e-7f) {
                lastX = nextX;
                lastY = nextY;
                break;
            }
            lastX = nextX;
            lastY = nextY;
        }

        if (iterSuccess) {
            xy_to_temp_tint(lastX, lastY, sceneTempK, sceneTint);
            weight = (std::abs(m1 - m2) > 1e-4f)
                         ? std::clamp((1e6f / sceneTempK - m2) / (m1 - m2), 0.0f, 1.0f)
                         : 1.0f;
            xy_to_xyz(lastX, lastY, W_scene);
        } else {
            float ratio = outResult.neutral[2] / std::max(1e-4f, outResult.neutral[0]);
            sceneTempK = 2000.0f + ratio * 3500.0f;
            sceneTint = 0.0f;
            weight = (std::abs(m1 - m2) > 1e-4f)
                         ? std::clamp((1e6f / sceneTempK - m2) / (m1 - m2), 0.0f, 1.0f)
                         : 1.0f;
            float xs, ys;
            temp_tint_to_xy(sceneTempK, 0.0f, xs, ys);
            xy_to_xyz(xs, ys, W_scene);
        }
    } else {
        float ratio = outResult.neutral[2] / std::max(1e-4f, outResult.neutral[0]);
        sceneTempK = 2000.0f + ratio * 3500.0f;
        sceneTint = 0.0f;
        weight = (std::abs(m1 - m2) > 1e-4f)
                     ? std::clamp((1e6f / sceneTempK - m2) / (m1 - m2), 0.0f, 1.0f)
                     : 1.0f;
        float xs, ys;
        temp_tint_to_xy(sceneTempK, 0.0f, xs, ys);
        xy_to_xyz(xs, ys, W_scene);
    }

    outResult.sceneTempK = sceneTempK;
    outResult.sceneTint = sceneTint;
    outResult.illuminantWeight = weight;

    // 3. Resolve target white point based on mode & creative offsets (Section 3.5)
    float Ms = 1e6f / std::clamp(sceneTempK, 1667.0f, 50000.0f);
    float Mbase = Ms;
    float tintBase = sceneTint;

    if (params.mode == WhiteBalanceMode::AMBIENCE) {
        // Incomplete Chromatic Adaptation (Warmth Retention)
        if (Ms > 200.0f) {
            float tStep = std::clamp((Ms - 200.0f) / (230.0f - 200.0f), 0.0f, 1.0f);
            float w = tStep * tStep * (3.0f - 2.0f * tStep); // smoothstep
            float d = Ms - 200.0f;
            float deltaM = 48.0f * std::tanh(0.22f * d * w / 48.0f);
            Mbase = Ms - deltaM;
        }
        // Planckian Tint Soft-Clamp
        float absTint = std::abs(sceneTint);
        if (absTint > 18.0f) {
            float sgn = (sceneTint >= 0.0f) ? 1.0f : -1.0f;
            tintBase = sgn * (18.0f + 18.0f * std::tanh((absTint - 18.0f) / 18.0f));
        }
    } else if (params.mode == WhiteBalanceMode::PRESET_OR_MANUAL) {
        float Tman = std::clamp(params.manualTempK, 1667.0f, 50000.0f);
        Mbase = 1e6f / Tman;
        tintBase = params.manualTint;
    }

    // Creative offsets
    float Mtarget = std::clamp(Mbase - params.tempOffsetMired, 20.0f, 600.0f);
    float targetTempK = 1e6f / Mtarget;
    float targetTint = std::clamp(tintBase - params.tintOffset, -150.0f, 150.0f);

    outResult.targetTempK = targetTempK;
    outResult.targetTint = targetTint;

    // 4. FM_D50 synthesis: Path A (ForwardMatrix) vs Path B (ColorMatrix + CAT16 Fallback)
    float FM_D50[9];
    outResult.usedForwardMatrix = false;
    outResult.valid = false;

    float fmMag = 0.0f;
    if (forwardMatrix1) {
        for (int i = 0; i < 9; ++i) fmMag += std::abs(forwardMatrix1[i]);
    }

    if (forwardMatrix1 && fmMag > 1e-3f && std::abs(mat3_det(forwardMatrix1)) > 1e-5f) {
        // Path A: ForwardMatrix path
        const float* fm1 = forwardMatrix1;
        const float* fm2 = forwardMatrix2 ? forwardMatrix2 : forwardMatrix1;

        float interpFm[9];
        for (int i = 0; i < 9; ++i) {
            interpFm[i] = weight * fm1[i] + (1.0f - weight) * fm2[i];
        }

        // White Point Row Normalization (Neutral Preservation Guard)
        float wFm[3];
        wFm[0] = interpFm[0] + interpFm[1] + interpFm[2];
        wFm[1] = interpFm[3] + interpFm[4] + interpFm[5];
        wFm[2] = interpFm[6] + interpFm[7] + interpFm[8];

        if (wFm[0] > 0.1f && wFm[1] > 0.1f && wFm[2] > 0.1f) {
            for (int r = 0; r < 3; ++r) {
                float scale = kW_D50[r] / wFm[r];
                for (int c = 0; c < 3; ++c) {
                    FM_D50[r * 3 + c] = interpFm[r * 3 + c] * scale;
                }
            }
            outResult.usedForwardMatrix = true;
            outResult.valid = true;
        }
    }

    // Path B Fallback: ColorMatrix-only with CAT16 adaptation
    if (!outResult.valid && colorMatrix1) {
        const float* cm1 = colorMatrix1;
        const float* cm2 = colorMatrix2 ? colorMatrix2 : colorMatrix1;

        float cm[9];
        for (int i = 0; i < 9; ++i) {
            cm[i] = weight * cm1[i] + (1.0f - weight) * cm2[i];
        }

        float invCm[9];
        if (mat3_invert(cm, invCm)) {
            // W_raw = invCm * neutral
            float wRaw[3];
            mat3_vec3_multiply(invCm, outResult.neutral, wRaw);

            float s = 1.0f / std::max(1e-5f, wRaw[1]);
            float wSceneB[3] = {s * wRaw[0], 1.0f, s * wRaw[2]};

            float mCatAdapt[9];
            cat16_adapt_matrix(wSceneB, kW_D50, mCatAdapt);

            // D_wb_inv = diag(neutral[0], 1.0, neutral[2])
            float dwbInv[9] = {0};
            dwbInv[0] = outResult.neutral[0];
            dwbInv[4] = 1.0f;
            dwbInv[8] = outResult.neutral[2];

            float tempA[9], tempB[9];
            mat3_multiply(mCatAdapt, invCm, tempA);
            mat3_multiply(tempA, dwbInv, tempB);

            for (int i = 0; i < 9; ++i) {
                FM_D50[i] = s * tempB[i];
            }
            outResult.usedForwardMatrix = false;
            outResult.valid = true;
        }
    }

    if (!outResult.valid) {
        mat3_identity(outResult.renderCcm);
        return;
    }

    // 5. CAT16 Shift Matrix Synthesis & Render CCM Composition
    float M_shift_norm[9];
    if (params.mode == WhiteBalanceMode::NEUTRAL &&
        std::abs(params.tempOffsetMired) < 1e-4f &&
        std::abs(params.tintOffset) < 1e-4f) {
        mat3_identity(M_shift_norm);
    } else {
        float xTarget, yTarget;
        temp_tint_to_xy(targetTempK, targetTint, xTarget, yTarget);
        float W_target[3];
        xy_to_xyz(xTarget, yTarget, W_target);

        float M_shift[9];
        cat16_adapt_matrix(W_target, W_scene, M_shift);

        float Y_out = M_shift[3] * kW_D50[0] + M_shift[4] * kW_D50[1] + M_shift[5] * kW_D50[2];
        float lumaScale = 1.0f / std::max(0.2f, Y_out);
        for (int i = 0; i < 9; ++i) {
            M_shift_norm[i] = M_shift[i] * lumaScale;
        }
    }

    // CCM_render = M_XYZ_D50_TO_SRGB * M_shift_norm * FM_D50
    float intermediate[9];
    mat3_multiply(kM_XYZ_D50_TO_SRGB, M_shift_norm, intermediate);
    mat3_multiply(intermediate, FM_D50, outResult.renderCcm);
}

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
) {
    if (!outCcm) return false;
    WhiteBalanceParams params;
    params.mode = WhiteBalanceMode::NEUTRAL;

    WhiteBalanceResult res;
    compute_white_balance(
        forwardMatrix1, forwardMatrix2,
        colorMatrix1, colorMatrix2,
        calibIllum1, calibIllum2,
        neutralColorPoint, wb,
        params, res
    );

    for (int i = 0; i < 9; ++i) {
        outCcm[i] = res.renderCcm[i];
    }
    return res.valid;
}

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
) {
    if (!mainNeutral || !mainCm1 || !subCm1) return false;

    float t1 = illuminant_to_temp(mainIllum1);
    float t2 = illuminant_to_temp(mainIllum2);
    float m1 = 1e6f / t1;
    float m2 = 1e6f / t2;

    const float* cm1 = mainCm1;
    const float* cm2 = mainCm2 ? mainCm2 : mainCm1;

    float lastX = 0.34567f;
    float lastY = 0.35850f;
    bool iterSuccess = true;

    for (int pass = 0; pass < 10; ++pass) {
        float tempK, tint;
        xy_to_temp_tint(lastX, lastY, tempK, tint);

        float g = (std::abs(m1 - m2) > 1e-4f)
                      ? std::clamp((1e6f / tempK - m2) / (m1 - m2), 0.0f, 1.0f)
                      : 1.0f;

        float cm[9];
        for (int i = 0; i < 9; ++i) cm[i] = g * cm1[i] + (1.0f - g) * cm2[i];

        float invCm[9];
        if (!mat3_invert(cm, invCm)) {
            iterSuccess = false;
            break;
        }

        float xX = invCm[0] * mainNeutral[0] + invCm[1] * mainNeutral[1] + invCm[2] * mainNeutral[2];
        float xY = invCm[3] * mainNeutral[0] + invCm[4] * mainNeutral[1] + invCm[5] * mainNeutral[2];
        float xZ = invCm[6] * mainNeutral[0] + invCm[7] * mainNeutral[1] + invCm[8] * mainNeutral[2];

        float sum = xX + xY + xZ;
        if (std::abs(sum) < 1e-6f) {
            iterSuccess = false;
            break;
        }

        float rawNextX = xX / sum;
        float rawNextY = xY / sum;
        float nextX = 0.5f * lastX + 0.5f * rawNextX;
        float nextY = 0.5f * lastY + 0.5f * rawNextY;

        if (std::abs(nextX - lastX) + std::abs(nextY - lastY) < 1e-7f) {
            lastX = nextX;
            lastY = nextY;
            break;
        }
        lastX = nextX;
        lastY = nextY;
    }

    float mainTempK = 5500.0f;
    float mainTint = 0.0f;
    if (iterSuccess) {
        xy_to_temp_tint(lastX, lastY, mainTempK, mainTint);
    } else {
        float ratio = mainNeutral[2] / std::max(1e-4f, mainNeutral[0]);
        mainTempK = 2000.0f + ratio * 3500.0f;
        temp_tint_to_xy(mainTempK, 0.0f, lastX, lastY);
    }

    float mainXyz[3];
    xy_to_xyz(lastX, lastY, mainXyz);

    // Map through sub camera color matrix
    float subT1 = illuminant_to_temp(subIllum1);
    float subT2 = illuminant_to_temp(subIllum2);
    float subM1 = 1e6f / subT1;
    float subM2 = 1e6f / subT2;
    float subG = (std::abs(subM1 - subM2) > 1e-4f)
                     ? std::clamp((1e6f / mainTempK - subM2) / (subM1 - subM2), 0.0f, 1.0f)
                     : 1.0f;

    const float* scm1 = subCm1;
    const float* scm2 = subCm2 ? subCm2 : subCm1;
    float subCm[9];
    for (int i = 0; i < 9; ++i) {
        subCm[i] = subG * scm1[i] + (1.0f - subG) * scm2[i];
    }

    float subRaw[3];
    mat3_vec3_multiply(subCm, mainXyz, subRaw);

    if (std::abs(subRaw[1]) < 1e-5f) return false;

    float nSubR = subRaw[0] / subRaw[1];
    float nSubB = subRaw[2] / subRaw[1];

    if (nSubR <= 1e-4f || nSubB <= 1e-4f) return false;

    float gSubR = 1.0f / nSubR;
    float gSubB = 1.0f / nSubB;

    // Plausibility envelope check (Section 3.7)
    if (gSubR < 0.5f || gSubR > 5.0f || gSubB < 0.5f || gSubB > 5.0f) {
        return false;
    }

    if (outSubNeutral) {
        outSubNeutral[0] = nSubR;
        outSubNeutral[1] = 1.0f;
        outSubNeutral[2] = nSubB;
    }
    if (outSubWbGain) {
        outSubWbGain[0] = gSubR;
        outSubWbGain[1] = 1.0f;
        outSubWbGain[2] = 1.0f;
        outSubWbGain[3] = gSubB;
    }
    return true;
}

} // namespace darkbag::color

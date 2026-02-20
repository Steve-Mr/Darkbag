#include "ColorPipe.h"
#include <tiffio.h>
#include <android/log.h>

#define TAG "ColorPipe"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#include <vector>
#include <ctime>
#include <future>
#include <array>
#include <cstdio>

// Define missing tags if needed (Standard EXIF tags)
#ifndef TIFFTAG_EXPOSURETIME
#define TIFFTAG_EXPOSURETIME 33434
#endif
#ifndef TIFFTAG_FNUMBER
#define TIFFTAG_FNUMBER 33437
#endif
#ifndef TIFFTAG_ISOSPEEDRATINGS
#define TIFFTAG_ISOSPEEDRATINGS 34855
#endif
#ifndef TIFFTAG_FOCALLENGTH
#define TIFFTAG_FOCALLENGTH 37386
#endif

// Define LinearRaw
#ifndef PHOTOMETRIC_LINEAR_RAW
#define PHOTOMETRIC_LINEAR_RAW 34892
#endif

// Define DNG Tags (Custom Tags 507xx)
#ifndef TIFFTAG_DNGVERSION
#define TIFFTAG_DNGVERSION 50706
#endif
#ifndef TIFFTAG_DNGBACKWARDVERSION
#define TIFFTAG_DNGBACKWARDVERSION 50707
#endif
#ifndef TIFFTAG_UNIQUECAMERAMODEL
#define TIFFTAG_UNIQUECAMERAMODEL 50708
#endif
#ifndef TIFFTAG_BLACKLEVEL
#define TIFFTAG_BLACKLEVEL 50714
#endif
#ifndef TIFFTAG_ACTIVEAREA
#define TIFFTAG_ACTIVEAREA 50710
#endif
#ifndef TIFFTAG_BLACKLEVELREPEATDIM
#define TIFFTAG_BLACKLEVELREPEATDIM 50713
#endif
#ifndef TIFFTAG_WHITELEVEL
#define TIFFTAG_WHITELEVEL 50717
#endif
#ifndef TIFFTAG_DEFAULTCROPORIGIN
#define TIFFTAG_DEFAULTCROPORIGIN 50719
#endif
#ifndef TIFFTAG_DEFAULTCROPSIZE
#define TIFFTAG_DEFAULTCROPSIZE 50720
#endif
#ifndef TIFFTAG_COLORMATRIX1
#define TIFFTAG_COLORMATRIX1 50721
#endif
#ifndef TIFFTAG_ASSHOTNEUTRAL
#define TIFFTAG_ASSHOTNEUTRAL 50728
#endif
#ifndef TIFFTAG_CALIBRATIONILLUMINANT1
#define TIFFTAG_CALIBRATIONILLUMINANT1 50778
#endif
#ifndef TIFFTAG_OPCODELIST1
#define TIFFTAG_OPCODELIST1 51008
#endif
#ifndef TIFFTAG_OPCODELIST2
#define TIFFTAG_OPCODELIST2 51009
#endif
#ifndef TIFFTAG_OPCODELIST3
#define TIFFTAG_OPCODELIST3 51022
#endif

#ifndef TIFFTAG_CFAREPEATPATTERNDIM
#define TIFFTAG_CFAREPEATPATTERNDIM 33421
#endif
#ifndef TIFFTAG_CFAPATTERN
#define TIFFTAG_CFAPATTERN 33422
#endif

static const TIFFFieldInfo dng_field_info[] = {
    { TIFFTAG_DNGVERSION, 4, 4, TIFF_BYTE, FIELD_CUSTOM, 1, 0, const_cast<char*>("DNGVersion") },
    { TIFFTAG_DNGBACKWARDVERSION, 4, 4, TIFF_BYTE, FIELD_CUSTOM, 1, 0, const_cast<char*>("DNGBackwardVersion") },
    { TIFFTAG_UNIQUECAMERAMODEL, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("UniqueCameraModel") },
    { TIFFTAG_ACTIVEAREA, 4, 4, TIFF_LONG, FIELD_CUSTOM, 1, 0, const_cast<char*>("ActiveArea") },
    { TIFFTAG_BLACKLEVEL, -1, -1, TIFF_LONG, FIELD_CUSTOM, 1, 1, const_cast<char*>("BlackLevel") },
    { TIFFTAG_BLACKLEVELREPEATDIM, 2, 2, TIFF_SHORT, FIELD_CUSTOM, 1, 0, const_cast<char*>("BlackLevelRepeatDim") },
    { TIFFTAG_WHITELEVEL, -1, -1, TIFF_LONG, FIELD_CUSTOM, 1, 1, const_cast<char*>("WhiteLevel") },
    { TIFFTAG_DEFAULTCROPORIGIN, 2, 2, TIFF_LONG, FIELD_CUSTOM, 1, 0, const_cast<char*>("DefaultCropOrigin") },
    { TIFFTAG_DEFAULTCROPSIZE, 2, 2, TIFF_LONG, FIELD_CUSTOM, 1, 0, const_cast<char*>("DefaultCropSize") },
    { TIFFTAG_COLORMATRIX1, -1, -1, TIFF_SRATIONAL, FIELD_CUSTOM, 1, 1, const_cast<char*>("ColorMatrix1") },
    { TIFFTAG_ASSHOTNEUTRAL, -1, -1, TIFF_RATIONAL, FIELD_CUSTOM, 1, 1, const_cast<char*>("AsShotNeutral") },
    { TIFFTAG_CALIBRATIONILLUMINANT1, 1, 1, TIFF_SHORT, FIELD_CUSTOM, 1, 0, const_cast<char*>("CalibrationIlluminant1") },
    { TIFFTAG_CFAREPEATPATTERNDIM, 2, 2, TIFF_SHORT, FIELD_CUSTOM, 1, 0, const_cast<char*>("CFARepeatPatternDim") },
    { TIFFTAG_CFAPATTERN, -1, -1, TIFF_BYTE, FIELD_CUSTOM, 1, 1, const_cast<char*>("CFAPattern") }
};

static void DNGTagExtender(TIFF *tif) {
    TIFFMergeFieldInfo(tif, dng_field_info, sizeof(dng_field_info) / sizeof(dng_field_info[0]));
}

// --- Matrix Math ---
Vec3 multiply(const Matrix3x3& mat, const Vec3& v) {
    return {
        mat.m[0] * v.r + mat.m[1] * v.g + mat.m[2] * v.b,
        mat.m[3] * v.r + mat.m[4] * v.g + mat.m[5] * v.b,
        mat.m[6] * v.r + mat.m[7] * v.g + mat.m[8] * v.b
    };
}

Matrix3x3 multiply(const Matrix3x3& a, const Matrix3x3& b) {
    Matrix3x3 res;
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            res.m[r * 3 + c] = a.m[r * 3 + 0] * b.m[0 * 3 + c] +
                               a.m[r * 3 + 1] * b.m[1 * 3 + c] +
                               a.m[r * 3 + 2] * b.m[2 * 3 + c];
        }
    }
    return res;
}

Matrix3x3 invert(const Matrix3x3& src) {
    float det = src.m[0] * (src.m[4] * src.m[8] - src.m[7] * src.m[5]) -
                src.m[1] * (src.m[3] * src.m[8] - src.m[5] * src.m[6]) +
                src.m[2] * (src.m[3] * src.m[7] - src.m[4] * src.m[6]);

    if (std::abs(det) < 1e-6f) return src;
    float invDet = 1.0f / det;
    Matrix3x3 res;
    res.m[0] = (src.m[4] * src.m[8] - src.m[5] * src.m[7]) * invDet;
    res.m[1] = (src.m[2] * src.m[7] - src.m[1] * src.m[8]) * invDet;
    res.m[2] = (src.m[1] * src.m[5] - src.m[2] * src.m[4]) * invDet;
    res.m[3] = (src.m[5] * src.m[6] - src.m[3] * src.m[8]) * invDet;
    res.m[4] = (src.m[0] * src.m[8] - src.m[2] * src.m[6]) * invDet;
    res.m[5] = (src.m[2] * src.m[3] - src.m[0] * src.m[5]) * invDet;
    res.m[6] = (src.m[3] * src.m[7] - src.m[4] * src.m[6]) * invDet;
    res.m[7] = (src.m[1] * src.m[6] - src.m[0] * src.m[7]) * invDet;
    res.m[8] = (src.m[0] * src.m[4] - src.m[1] * src.m[3]) * invDet;
    return res;
}

// --- Color Matrices ---
const Matrix3x3 M_sRGB_D65_to_XYZ = {
    0.41239080f, 0.35758434f, 0.18048079f,
    0.21263901f, 0.71516868f, 0.07219232f,
    0.01933082f, 0.11919478f, 0.95053215f
};

const Matrix3x3 M_XYZ_to_sRGB_D65 = invert(M_sRGB_D65_to_XYZ);

const Matrix3x3 M_ProPhoto_D50_to_XYZ = {
    0.79766723f, 0.13519223f, 0.03135253f,
    0.28803745f, 0.71187688f, 0.00008566f,
    0.00000000f, 0.00000000f, 0.82518828f
};

const Matrix3x3 M_XYZ_to_AlexaWideGamut_D65 = {
    1.99234198f, -0.57196805f, -0.29536100f,
    -0.79989925f, 1.74791391f, 0.01134474f,
    0.00760860f, -0.02558954f, 0.93508164f
};

const Matrix3x3 M_XYZ_to_SGamut3Cine_D65 = {
    1.84677897f, -0.52598612f, -0.21054521f,
    -0.44415326f, 1.25944290f, 0.14939997f,
    0.04085542f, 0.01564089f, 0.86820725f
};

const Matrix3x3 M_XYZ_to_VGamut_D65 = {
    1.59387222f, -0.31417914f, -0.18431177f,
    -0.51815173f, 1.35539124f, 0.12587867f,
    0.01117945f, 0.00319413f, 0.90553536f
};

const Matrix3x3 M_XYZ_to_Rec2020_D65 = {
    1.71665119f, -0.35567078f, -0.25336628f,
    -0.66668435f, 1.61648124f, 0.01576855f,
    0.01763986f, -0.04277061f, 0.94210312f
};

const Matrix3x3 M_XYZ_to_Rec709_D65 = {
    3.24096994f, -1.53738318f, -0.49861076f,
    -0.96924364f, 1.87596750f, 0.04155506f,
    0.05563008f, -0.20397696f, 1.05697151f
};

const Matrix3x3 M_Bradford_D50_to_D65 = {
    0.95553939f, -0.02305835f, 0.06322404f,
    -0.02831194f, 1.00994706f, 0.02102750f,
    0.01231027f, -0.02050341f, 1.33023150f
};

// --- Log Curves (CPU) ---
float srgb_oetf(float x) {
    if (x <= 0.0031308f) return 12.92f * x;
    else return 1.055f * pow(x, 1.0f / 2.4f) - 0.055f;
}

float arri_logc3(float x) {
    const float cut = 0.010591f, a = 5.555556f, b = 0.052272f, c = 0.247190f, d = 0.385537f, e = 5.367655f, f = 0.092809f;
    if (x > cut) return c * log10(a * x + b) + d;
    else return e * x + f;
}
float s_log3(float x) {
    if (x >= 0.01125000f) return (420.0f + log10((x + 0.01f) / (0.18f + 0.01f)) * 261.5f) / 1023.0f;
    else return (x * 171.2102946929f + 95.0f) / 1023.0f;
}
float f_log(float x) {
    const float a = 0.555556f, b = 0.009468f, c = 0.344676f, d = 0.790453f, cut = 0.00089f;
    if (x >= cut) return c * log10(a * x + b) + d;
    else return 8.52f * x + 0.0929f;
}
float vlog(float x) {
    const float cut = 0.01f, c = 0.241514f, b = 0.008730f, d = 0.598206f;
    if (x >= cut) return c * log10(x + b) + d;
    else return 5.6f * x + 0.125f;
}
float apply_log(float x, int type) {
    // Note: Log curves handle x < 0 usually by clipping or linear extension.
    // We clamp slightly above 0 if needed, but linear extension is better for noise.
    // Use a robust check that also handles NaN (NaN > 0 is false)
    x = (x > 0.0f) ? x : 0.0f;

    switch (type) {
        case 1: return arri_logc3(x);
        case 2:
        case 3: return f_log(x);
        case 5:
        case 6: return s_log3(x);
        case 7: return vlog(x);
        default: return srgb_oetf(x);
    }
}

// --- LUT (CPU) ---
LUT3D load_lut(const char* path) {
    LUT3D lut;
    lut.size = 0;
    std::ifstream file(path);
    if (!file.is_open()) return lut;
    char line[2048];
    int lineCount = 0;
    const int maxLines = 1000000;
    while (file.getline(line, sizeof(line)) && ++lineCount < maxLines) {
        if (line[0] == '\0' || line[0] == '#') continue;
        std::string lineStr(line);
        if (lineStr.find("LUT_3D_SIZE") != std::string::npos) {
            std::stringstream ss(lineStr); std::string temp; ss >> temp >> lut.size;
            if (lut.size > 0 && lut.size <= 64) {
                lut.data.reserve(lut.size * lut.size * lut.size);
            } else {
                lut.size = 0;
                return lut;
            }
            continue;
        }
        std::stringstream ss(lineStr); float r, g, b;
        if (ss >> r >> g >> b) lut.data.push_back({r, g, b});
    }
    if (lut.size > 0 && lut.data.size() != (size_t)(lut.size * lut.size * lut.size)) {
        LOGE("LUT size mismatch: expected %d^3=%zu, got %zu", lut.size, (size_t)(lut.size * lut.size * lut.size), lut.data.size());
        lut.size = 0;
        lut.data.clear();
    }
    return lut;
}

Vec3 apply_lut(const LUT3D& lut, Vec3 color) {
    if (lut.size <= 0 || lut.data.empty()) return color;
    float scale = static_cast<float>(lut.size - 1);

    // Robust clamping that handles NaN
    auto clamp01 = [](float v) {
        if (!(v > 0.0f)) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    };

    float r = clamp01(color.r) * scale;
    float g = clamp01(color.g) * scale;
    float b = clamp01(color.b) * scale;
    int r0 = (int)r; int r1 = std::min(r0 + 1, lut.size - 1);
    int g0 = (int)g; int g1 = std::min(g0 + 1, lut.size - 1);
    int b0 = (int)b; int b1 = std::min(b0 + 1, lut.size - 1);
    float dr = r - r0; float dg = g - g0; float db = b - b0;
    auto idx = [&](int x, int y, int z) { return x + y * lut.size + z * lut.size * lut.size; };
    Vec3 c000 = lut.data[idx(r0, g0, b0)], c100 = lut.data[idx(r1, g0, b0)];
    Vec3 c010 = lut.data[idx(r0, g1, b0)], c110 = lut.data[idx(r1, g1, b0)];
    Vec3 c001 = lut.data[idx(r0, g0, b1)], c101 = lut.data[idx(r1, g0, b1)];
    Vec3 c011 = lut.data[idx(r0, g1, b1)], c111 = lut.data[idx(r1, g1, b1)];
    Vec3 c00 = { c000.r * (1-dr) + c100.r * dr, c000.g * (1-dr) + c100.g * dr, c000.b * (1-dr) + c100.b * dr };
    Vec3 c10 = { c010.r * (1-dr) + c110.r * dr, c010.g * (1-dr) + c110.g * dr, c010.b * (1-dr) + c110.b * dr };
    Vec3 c01 = { c001.r * (1-dr) + c101.r * dr, c001.g * (1-dr) + c101.g * dr, c001.b * (1-dr) + c101.b * dr };
    Vec3 c11 = { c011.r * (1-dr) + c111.r * dr, c011.g * (1-dr) + c111.g * dr, c011.b * (1-dr) + c111.b * dr };
    Vec3 c0 = { c00.r * (1-dg) + c10.r * dg, c00.g * (1-dg) + c10.g * dg, c00.b * (1-dg) + c10.b * dg };
    Vec3 c1 = { c01.r * (1-dg) + c11.r * dg, c01.g * (1-dg) + c11.g * dg, c01.b * (1-dg) + c11.b * dg };
    return { c0.r * (1-db) + c1.r * db, c0.g * (1-db) + c1.g * db, c0.b * (1-db) + c1.b * db };
}

namespace {

struct AdaptiveEdgeComp {
    bool enabled = false;
    float lumaEdgeGain = 1.0f;
    std::array<float, 3> chromaEdgeGain{1.0f, 1.0f, 1.0f};
    float centerX = 0.0f;
    float centerY = 0.0f;
    float invMaxRadius = 1.0f;
};

constexpr float kCenterRegionRadius = 0.30f;
constexpr float kEdgeRegionStartRadius = 0.72f;
constexpr float kLumaDropThreshold = 0.95f;
constexpr float kGreenShiftThreshold = 1.03f;
constexpr float kCompStrength = 0.75f;
constexpr float kMinLumaEdgeGain = 1.0f;
constexpr float kMaxLumaEdgeGain = 1.35f;
constexpr float kMinChromaGain = 0.85f;
constexpr float kMaxChromaGain = 1.25f;
constexpr int kAnalysisStep = 8;

constexpr float kRec709LinearLumaR = 0.2126f;
constexpr float kRec709LinearLumaG = 0.7152f;
constexpr float kRec709LinearLumaB = 0.0722f;

constexpr float kBlendStartRadius = 0.55f;
constexpr float kBlendEndRadius = 1.0f;

inline float safe_div(float a, float b) {
    return (b > 1e-6f) ? (a / b) : 1.0f;
}
inline float clamp01(float v) {
    if (!(v > 0.0f)) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

std::string build_debug_stage_path(const char* basePath, const char* stageSuffix) {
    if (!basePath || !stageSuffix) return {};
    std::string p(basePath);
    size_t slash = p.find_last_of("/");
    size_t dot = p.find_last_of('.');
    if (dot == std::string::npos || (slash != std::string::npos && dot < slash)) {
        dot = p.size();
    }
    return p.substr(0, dot) + stageSuffix + ".jpg";
}


AdaptiveEdgeComp calculate_adaptive_edge_comp(const std::vector<unsigned short>& inputImage, int width, int height) {
    AdaptiveEdgeComp edgeComp;
    const float cx = 0.5f * (width - 1);
    const float cy = 0.5f * (height - 1);
    const float maxRadius = std::sqrt(cx * cx + cy * cy);

    edgeComp.centerX = cx;
    edgeComp.centerY = cy;
    edgeComp.invMaxRadius = (maxRadius > 1e-6f) ? (1.0f / maxRadius) : 1.0f;

    std::array<double, 3> centerSum{0.0, 0.0, 0.0};
    std::array<double, 3> edgeSum{0.0, 0.0, 0.0};
    int centerCount = 0;
    int edgeCount = 0;

    for (int y = 0; y < height; y += kAnalysisStep) {
        for (int x = 0; x < width; x += kAnalysisStep) {
            const float nx = (x - cx) * edgeComp.invMaxRadius;
            const float ny = (y - cy) * edgeComp.invMaxRadius;
            const float r = std::sqrt(nx * nx + ny * ny);

            size_t idx = (static_cast<size_t>(y) * width + x) * 3;
            float rr = static_cast<float>(inputImage[idx + 0]);
            float gg = static_cast<float>(inputImage[idx + 1]);
            float bb = static_cast<float>(inputImage[idx + 2]);

            if (r <= kCenterRegionRadius) {
                centerSum[0] += rr; centerSum[1] += gg; centerSum[2] += bb; centerCount++;
            } else if (r >= kEdgeRegionStartRadius) {
                edgeSum[0] += rr; edgeSum[1] += gg; edgeSum[2] += bb; edgeCount++;
            }
        }
    }

    if (centerCount <= 0 || edgeCount <= 0) {
        return edgeComp;
    }

    std::array<float, 3> centerMean{
        static_cast<float>(centerSum[0] / centerCount),
        static_cast<float>(centerSum[1] / centerCount),
        static_cast<float>(centerSum[2] / centerCount)
    };
    std::array<float, 3> edgeMean{
        static_cast<float>(edgeSum[0] / edgeCount),
        static_cast<float>(edgeSum[1] / edgeCount),
        static_cast<float>(edgeSum[2] / edgeCount)
    };

    float centerLuma = kRec709LinearLumaR * centerMean[0] + kRec709LinearLumaG * centerMean[1] + kRec709LinearLumaB * centerMean[2];
    float edgeLuma = kRec709LinearLumaR * edgeMean[0] + kRec709LinearLumaG * edgeMean[1] + kRec709LinearLumaB * edgeMean[2];

    float centerGvsRB = safe_div(centerMean[1], 0.5f * (centerMean[0] + centerMean[2]));
    float edgeGvsRB = safe_div(edgeMean[1], 0.5f * (edgeMean[0] + edgeMean[2]));

    // Conditionally enable compensation when edge luma drop and green shift are detected.
    // Gains are derived from center/edge statistics and clamped to safe bounds.
    bool needsComp = (edgeLuma < centerLuma * kLumaDropThreshold) && (edgeGvsRB > centerGvsRB * kGreenShiftThreshold);
    edgeComp.lumaEdgeGain = std::clamp(1.0f + (safe_div(centerLuma, edgeLuma) - 1.0f) * kCompStrength, kMinLumaEdgeGain, kMaxLumaEdgeGain);

    for (int ch = 0; ch < 3; ch++) {
        float target = safe_div(centerMean[ch], edgeMean[ch]);
        float mixed = 1.0f + (target - 1.0f) * kCompStrength;
        edgeComp.chromaEdgeGain[ch] = std::clamp(mixed, kMinChromaGain, kMaxChromaGain);
    }

    edgeComp.enabled = needsComp;
    if (!edgeComp.enabled) {
        edgeComp.lumaEdgeGain = 1.0f;
        edgeComp.chromaEdgeGain = {1.0f, 1.0f, 1.0f};
    }

    LOGD("Adaptive edge compensation active=%d. LumaEdgeGain=%.3f, ChromaEdgeGain=[%.3f, %.3f, %.3f], centerLuma=%.1f, edgeLuma=%.1f, centerGvsRB=%.4f, edgeGvsRB=%.4f",
         (int)edgeComp.enabled,
         edgeComp.lumaEdgeGain,
         edgeComp.chromaEdgeGain[0], edgeComp.chromaEdgeGain[1], edgeComp.chromaEdgeGain[2],
         centerLuma, edgeLuma, centerGvsRB, edgeGvsRB);

    return edgeComp;
}

} // namespace

bool process_and_save_image(
    const std::vector<unsigned short>& inputImage,
    int width, int height, float gain, int targetLog, const LUT3D& lut,
    const char* tiffPath, const char* jpgPath, int sourceColorSpace,
    const float* ccm, const float* wb, int orientation, unsigned char* out_rgb_buffer,
    bool isPreview, int downsampleFactor, float zoomFactor, bool mirror
) {
    LOGD("process_and_save_image: %dx%d, gain=%.2f, log=%d, lut=%d, tiff=%s, jpg=%s, preview=%d, ds=%d, zoom=%.2f, mirror=%d",
         width, height, gain, targetLog, lut.size, tiffPath ? tiffPath : "null", jpgPath ? jpgPath : "null", isPreview, downsampleFactor, zoomFactor, mirror);
    int outW = width / downsampleFactor, outH = height / downsampleFactor;
    bool swapDims = (orientation == 90 || orientation == 270);
    int finalW = swapDims ? outH : outW, finalH = swapDims ? outW : outH;
    Matrix3x3 effective_CCM = {0}; if (sourceColorSpace == 1 && ccm) std::copy(ccm, ccm + 9, effective_CCM.m);
    std::vector<unsigned short> processedImage; std::vector<unsigned char> previewRgb8;

    AdaptiveEdgeComp edgeComp = calculate_adaptive_edge_comp(inputImage, width, height);

    // Debug stage split output (A/B/C):
    // A: linear RGB input after adaptive edge compensation
    // B: after color-space matrix transform (before log/LUT)
    // C: after log curve (before LUT)
    const bool enableStageDebug = false;
    std::string debugBasePath = jpgPath ? std::string(jpgPath) : (tiffPath ? std::string(tiffPath) : std::string());
    std::string debugPathA = enableStageDebug ? build_debug_stage_path(debugBasePath.c_str(), "_debug_A_linear") : std::string();
    std::string debugPathB = enableStageDebug ? build_debug_stage_path(debugBasePath.c_str(), "_debug_B_matrix") : std::string();
    std::string debugPathC = enableStageDebug ? build_debug_stage_path(debugBasePath.c_str(), "_debug_C_log") : std::string();

    std::vector<unsigned char> debugA8;
    std::vector<unsigned char> debugB8;
    std::vector<unsigned char> debugC8;

    auto process_pixel = [&](int x, int y, Vec3* stageA, Vec3* stageB, Vec3* stageC) -> Vec3 {
        x = std::max(0, std::min(x, width - 1));
        y = std::max(0, std::min(y, height - 1));
        size_t idx = (static_cast<size_t>(y) * width + x) * 3;
        float norm_r = (float)inputImage[idx + 0] / 65535.0f * gain;
        float norm_g = (float)inputImage[idx + 1] / 65535.0f * gain;
        float norm_b = (float)inputImage[idx + 2] / 65535.0f * gain;

        if (edgeComp.enabled) {
            const float nx = (x - edgeComp.centerX) * edgeComp.invMaxRadius;
            const float ny = (y - edgeComp.centerY) * edgeComp.invMaxRadius;
            float r = std::sqrt(nx * nx + ny * ny);

            // Smooth radial blend: start near 55% radius and fully applied at edges.
            float t = std::clamp((r - kBlendStartRadius) / (kBlendEndRadius - kBlendStartRadius), 0.0f, 1.0f);
            t = t * t * (3.0f - 2.0f * t); // smoothstep

            float lumaGain = 1.0f + (edgeComp.lumaEdgeGain - 1.0f) * t;
            float rGain = (1.0f + (edgeComp.chromaEdgeGain[0] - 1.0f) * t) * lumaGain;
            float gGain = (1.0f + (edgeComp.chromaEdgeGain[1] - 1.0f) * t) * lumaGain;
            float bGain = (1.0f + (edgeComp.chromaEdgeGain[2] - 1.0f) * t) * lumaGain;

            norm_r *= rGain;
            norm_g *= gGain;
            norm_b *= bGain;
        }

        Vec3 colorA = {norm_r, norm_g, norm_b};
        if (stageA) *stageA = colorA;

        Vec3 color = colorA;
        if (sourceColorSpace == 1) { if (ccm) color = multiply(effective_CCM, color); color = multiply(M_sRGB_D65_to_XYZ, color); }
        else if (sourceColorSpace == 0) { color = multiply(M_ProPhoto_D50_to_XYZ, color); color = multiply(M_Bradford_D50_to_D65, color); }

        switch (targetLog) {
            case 1: color = multiply(M_XYZ_to_AlexaWideGamut_D65, color); break;
            case 2:
            case 3: color = multiply(M_XYZ_to_Rec2020_D65, color); break;
            case 5:
            case 6: color = multiply(M_XYZ_to_SGamut3Cine_D65, color); break;
            case 7: color = multiply(M_XYZ_to_VGamut_D65, color); break;
            default: color = multiply(M_XYZ_to_Rec709_D65, color); break;
        }
        if (stageB) *stageB = color;

        color.r = apply_log(color.r, targetLog); color.g = apply_log(color.g, targetLog); color.b = apply_log(color.b, targetLog);
        if (stageC) *stageC = color;

        if (lut.size > 0) color = apply_lut(lut, color);
        return color;
    };
    int cropW = (int)(width / zoomFactor);
    int cropH = (int)(height / zoomFactor);
    int cropX = (width - cropW) / 2;
    int cropY = (height - cropH) / 2;

    int finalW_zoomed = swapDims ? (cropH / downsampleFactor) : (cropW / downsampleFactor);
    int finalH_zoomed = swapDims ? (cropW / downsampleFactor) : (cropH / downsampleFactor);

    if (enableStageDebug) {
        size_t n = static_cast<size_t>(finalW_zoomed) * finalH_zoomed * 3;
        debugA8.resize(n);
        debugB8.resize(n);
        debugC8.resize(n);
    }

    if (isPreview) {
        previewRgb8.resize(static_cast<size_t>(finalW_zoomed) * finalH_zoomed * 3);
        #pragma omp parallel for
        for (int py = 0; py < finalH_zoomed; py++) {
            for (int px = 0; px < finalW_zoomed; px++) {
                int sx, sy;
                int opx = mirror ? (finalW_zoomed - 1 - px) : px;
                if (orientation == 90) { sx = py; sy = (finalW_zoomed - 1) - opx; }
                else if (orientation == 180) { sx = (finalW_zoomed - 1) - opx; sy = (finalH_zoomed - 1) - py; }
                else if (orientation == 270) { sx = (finalH_zoomed - 1) - py; sy = opx; }
                else { sx = opx; sy = py; }

                Vec3 color = process_pixel(cropX + sx * downsampleFactor, cropY + sy * downsampleFactor, nullptr, nullptr, nullptr);
                size_t outIdx = (static_cast<size_t>(py) * finalW_zoomed + px) * 3;
                previewRgb8[outIdx + 0] = (unsigned char)std::max(0.0f, std::min(255.0f, color.r * 255.0f + 0.5f));
                previewRgb8[outIdx + 1] = (unsigned char)std::max(0.0f, std::min(255.0f, color.g * 255.0f + 0.5f));
                previewRgb8[outIdx + 2] = (unsigned char)std::max(0.0f, std::min(255.0f, color.b * 255.0f + 0.5f));
            }
        }
    } else {
        processedImage.resize(static_cast<size_t>(finalW_zoomed) * finalH_zoomed * 3);
        #pragma omp parallel for
        for (int py = 0; py < finalH_zoomed; py++) {
            for (int px = 0; px < finalW_zoomed; px++) {
                int sx, sy;
                int opx = mirror ? (finalW_zoomed - 1 - px) : px;
                if (orientation == 90) { sx = py; sy = (finalW_zoomed - 1) - opx; }
                else if (orientation == 180) { sx = (finalW_zoomed - 1) - opx; sy = (finalH_zoomed - 1) - py; }
                else if (orientation == 270) { sx = (finalH_zoomed - 1) - py; sy = opx; }
                else { sx = opx; sy = py; }

                Vec3 stageA{}, stageB{}, stageC{};
                Vec3 color = process_pixel(cropX + sx, cropY + sy,
                                           enableStageDebug ? &stageA : nullptr,
                                           enableStageDebug ? &stageB : nullptr,
                                           enableStageDebug ? &stageC : nullptr);
                size_t outIdx = (static_cast<size_t>(py) * finalW_zoomed + px) * 3;
                processedImage[outIdx + 0] = (unsigned short)std::max(0.0f, std::min(65535.0f, color.r * 65535.0f));
                processedImage[outIdx + 1] = (unsigned short)std::max(0.0f, std::min(65535.0f, color.g * 65535.0f));
                processedImage[outIdx + 2] = (unsigned short)std::max(0.0f, std::min(65535.0f, color.b * 65535.0f));

                if (enableStageDebug) {
                    debugA8[outIdx + 0] = (unsigned char)(clamp01(stageA.r) * 255.0f);
                    debugA8[outIdx + 1] = (unsigned char)(clamp01(stageA.g) * 255.0f);
                    debugA8[outIdx + 2] = (unsigned char)(clamp01(stageA.b) * 255.0f);

                    debugB8[outIdx + 0] = (unsigned char)(clamp01(stageB.r) * 255.0f);
                    debugB8[outIdx + 1] = (unsigned char)(clamp01(stageB.g) * 255.0f);
                    debugB8[outIdx + 2] = (unsigned char)(clamp01(stageB.b) * 255.0f);

                    debugC8[outIdx + 0] = (unsigned char)(clamp01(stageC.r) * 255.0f);
                    debugC8[outIdx + 1] = (unsigned char)(clamp01(stageC.g) * 255.0f);
                    debugC8[outIdx + 2] = (unsigned char)(clamp01(stageC.b) * 255.0f);
                }

                // Note: out_rgb_buffer is usually for preview only, but we keep it here if needed.
                // It expects original dimensions though. This part might need adjustment if used for rotated large images.
                if (out_rgb_buffer && !swapDims) {
                    size_t bIdx = (static_cast<size_t>(py) * finalW + px) * 4;
                    out_rgb_buffer[bIdx+0] = (unsigned char)std::min(255, (processedImage[outIdx+0] + 128) >> 8);
                    out_rgb_buffer[bIdx+1] = (unsigned char)std::min(255, (processedImage[outIdx+1] + 128) >> 8);
                    out_rgb_buffer[bIdx+2] = (unsigned char)std::min(255, (processedImage[outIdx+2] + 128) >> 8);
                    out_rgb_buffer[bIdx+3] = 255;
                }
            }
        }
    }

    bool tiffOk = true;
    if (tiffPath) {
        tiffOk = write_tiff(tiffPath, finalW_zoomed, finalH_zoomed, processedImage, 0, false); // orientation 0 because already rotated and mirrored in pixels
        if (!tiffOk) LOGE("write_tiff failed for %s", tiffPath);
    }

    bool jpgOk = true;
    if (jpgPath) {
        if (isPreview && !previewRgb8.empty()) {
            jpgOk = stbi_write_jpg(jpgPath, finalW_zoomed, finalH_zoomed, 3, previewRgb8.data(), 95) != 0;
        } else {
            jpgOk = write_jpeg(jpgPath, finalW_zoomed, finalH_zoomed, processedImage, 95);
        }
        if (!jpgOk) LOGE("write_jpeg/stbi_write_jpg failed for %s", jpgPath);
        else {
            std::ifstream f(jpgPath, std::ios::binary | std::ios::ate);
            if (f.is_open()) {
                LOGD("Successfully wrote JPEG: %s, size: %lld bytes", jpgPath, (long long)f.tellg());
            } else {
                LOGE("Wrote JPEG but could not verify existence: %s", jpgPath);
            }
        }
    }
    if (enableStageDebug && !debugA8.empty()) {
        bool aOk = stbi_write_jpg(debugPathA.c_str(), finalW_zoomed, finalH_zoomed, 3, debugA8.data(), 95) != 0;
        bool bOk = stbi_write_jpg(debugPathB.c_str(), finalW_zoomed, finalH_zoomed, 3, debugB8.data(), 95) != 0;
        bool cOk = stbi_write_jpg(debugPathC.c_str(), finalW_zoomed, finalH_zoomed, 3, debugC8.data(), 95) != 0;
        LOGD("Stage debug outputs: A=%s (%d), B=%s (%d), C=%s (%d)",
             debugPathA.c_str(), (int)aOk,
             debugPathB.c_str(), (int)bOk,
             debugPathC.c_str(), (int)cOk);
    }

    return tiffOk && jpgOk;
}

bool write_jpeg(const char* filename, int width, int height, const std::vector<unsigned short>& data, int quality) {
    LOGD("write_jpeg: %s, %dx%d", filename, width, height);
    size_t total_pixels = static_cast<size_t>(width) * height;
    std::vector<unsigned char> rgb8;
    try {
        rgb8.resize(total_pixels * 3);
    } catch (const std::bad_alloc& e) {
        LOGE("Failed to allocate memory for JPEG conversion: %zu bytes", total_pixels * 3);
        return false;
    }

    #pragma omp parallel for
    for (size_t i = 0; i < total_pixels; i++) {
        rgb8[i * 3 + 0] = (unsigned char)std::min(255, (data[i * 3 + 0] + 128) >> 8);
        rgb8[i * 3 + 1] = (unsigned char)std::min(255, (data[i * 3 + 1] + 128) >> 8);
        rgb8[i * 3 + 2] = (unsigned char)std::min(255, (data[i * 3 + 2] + 128) >> 8);
    }
    int res = stbi_write_jpg(filename, width, height, 3, rgb8.data(), quality);
    if (res == 0) {
        LOGE("stbi_write_jpg failed for %s", filename);
    }
    return res != 0;
}

bool write_tiff(const char* filename, int width, int height, const std::vector<unsigned short>& data, int orientation, bool mirror) {
    TIFF* tif = TIFFOpen(filename, "w");
    if (!tif) return false;

    TIFFSetField(tif, TIFFTAG_IMAGEWIDTH, width);
    TIFFSetField(tif, TIFFTAG_IMAGELENGTH, height);
    TIFFSetField(tif, TIFFTAG_BITSPERSAMPLE, 16);
    TIFFSetField(tif, TIFFTAG_SAMPLESPERPIXEL, 3);
    TIFFSetField(tif, TIFFTAG_COMPRESSION, COMPRESSION_NONE);
    TIFFSetField(tif, TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_RGB);
    TIFFSetField(tif, TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);

    uint16_t tiffOrientation = 1;
    switch (orientation) {
        case 90: tiffOrientation = mirror ? 5 : 6; break;
        case 180: tiffOrientation = mirror ? 4 : 3; break;
        case 270: tiffOrientation = mirror ? 7 : 8; break;
        default: tiffOrientation = mirror ? 2 : 1; break;
    }
    TIFFSetField(tif, TIFFTAG_ORIENTATION, tiffOrientation);
    TIFFSetField(tif, TIFFTAG_ROWSPERSTRIP, height);

    if (TIFFWriteEncodedStrip(tif, 0, (void*)data.data(), static_cast<size_t>(width) * height * 3 * sizeof(unsigned short)) < 0) {
        TIFFClose(tif);
        return false;
    }

    TIFFClose(tif);
    return true;
}


bool write_bmp(const char* filename, int width, int height, const std::vector<unsigned short>& data) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) return false;

    size_t padded_width = (static_cast<size_t>(width) * 3 + 3) & (~3);
    size_t size = 54 + padded_width * height;

    unsigned char header[54] = {0};
    header[0] = 'B';
    header[1] = 'M';
    *(int*)&header[2] = static_cast<int>(size);
    *(int*)&header[10] = 54;
    *(int*)&header[14] = 40;
    *(int*)&header[18] = width;
    *(int*)&header[22] = height;
    *(short*)&header[26] = 1;
    *(short*)&header[28] = 24;

    file.write((char*)header, 54);

    std::vector<unsigned char> line(padded_width, 0);
    for (int y = height - 1; y >= 0; y--) {
        for (int x = 0; x < width; x++) {
            size_t idx = (static_cast<size_t>(y) * width + x) * 3;
            line[static_cast<size_t>(x) * 3 + 0] = (unsigned char)std::min(255, (data[idx + 2] + 128) >> 8);
            line[static_cast<size_t>(x) * 3 + 1] = (unsigned char)std::min(255, (data[idx + 1] + 128) >> 8);
            line[static_cast<size_t>(x) * 3 + 2] = (unsigned char)std::min(255, (data[idx + 0] + 128) >> 8);
        }
        file.write((char*)line.data(), padded_width);
    }

    bool result = file.good();
    file.close();
    return result;
}

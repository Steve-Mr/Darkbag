#include "ColorPipe.h"
#include <tiffio.h>
#include <android/log.h>

#define TAG "ColorPipe"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)


#include <turbojpeg.h>

static bool write_jpeg_turbo(const char* filename, int width, int height, int subsamp, const unsigned char* buffer, int quality) {
    tjhandle _jpegCompressor = tjInitCompress();
    if (!_jpegCompressor) {
        LOGE("tjInitCompress failed");
        return false;
    }
    
    unsigned char* jpegBuf = NULL;
    unsigned long jpegSize = 0;
    
    int tj_stat = tjCompress2(_jpegCompressor, buffer, width, 0, height, TJPF_RGB,
                              &jpegBuf, &jpegSize, subsamp, quality, TJFLAG_FASTDCT);
                              
    if (tj_stat != 0) {
        LOGE("tjCompress2 failed: %s", tjGetErrorStr());
        tjDestroy(_jpegCompressor);
        if (jpegBuf) tjFree(jpegBuf);
        return false;
    }
    
    FILE* file = fopen(filename, "wb");
    if (!file) {
        LOGE("Failed to open %s for writing", filename);
        tjDestroy(_jpegCompressor);
        tjFree(jpegBuf);
        return false;
    }
    fwrite(jpegBuf, 1, jpegSize, file);
    fclose(file);
    
    tjDestroy(_jpegCompressor);
    tjFree(jpegBuf);
    return true;
}

static const std::vector<unsigned char>& encode_rgb8_jpeg(
    const std::vector<unsigned char>& rgb8,
    int width,
    int height,
    int quality
) {
    thread_local std::vector<unsigned char> tls_jpeg_bytes;
    tls_jpeg_bytes.clear();
    
    if (rgb8.empty()) return tls_jpeg_bytes;
    
    tjhandle _jpegCompressor = tjInitCompress();
    if (!_jpegCompressor) return tls_jpeg_bytes;
    
    unsigned char* jpegBuf = NULL;
    unsigned long jpegSize = 0;
    
    int tj_stat = tjCompress2(_jpegCompressor, rgb8.data(), width, 0, height, TJPF_RGB,
                              &jpegBuf, &jpegSize, TJSAMP_420, quality, TJFLAG_FASTDCT);
                              
    if (tj_stat == 0 && jpegBuf != NULL) {
        tls_jpeg_bytes.assign(jpegBuf, jpegBuf + jpegSize);
        tjFree(jpegBuf);
    }
    tjDestroy(_jpegCompressor);
    
    return tls_jpeg_bytes;
}

#include <vector>
#include <ctime>
#include <future>
#include <array>
#include <cstdio>
#include <cstdint>

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
#ifndef TIFFTAG_DATETIMEORIGINAL
#define TIFFTAG_DATETIMEORIGINAL 36867
#endif
#ifndef TIFFTAG_DATETIMEDIGITIZED
#define TIFFTAG_DATETIMEDIGITIZED 36868
#endif
#ifndef TIFFTAG_OFFSETTIME
#define TIFFTAG_OFFSETTIME 36880
#endif
#ifndef TIFFTAG_OFFSETTIMEORIGINAL
#define TIFFTAG_OFFSETTIMEORIGINAL 36881
#endif
#ifndef TIFFTAG_OFFSETTIMEDIGITIZED
#define TIFFTAG_OFFSETTIMEDIGITIZED 36882
#endif
#ifndef TIFFTAG_SUBSECTIME
#define TIFFTAG_SUBSECTIME 37520
#endif
#ifndef TIFFTAG_SUBSECTIMEORIGINAL
#define TIFFTAG_SUBSECTIMEORIGINAL 37521
#endif
#ifndef TIFFTAG_SUBSECTIMEDIGITIZED
#define TIFFTAG_SUBSECTIMEDIGITIZED 37522
#endif
#ifndef TIFFTAG_LENSMODEL
#define TIFFTAG_LENSMODEL 0xA434
#endif
#ifndef TIFFTAG_FOCALLENGTHIN35MMFILM
#define TIFFTAG_FOCALLENGTHIN35MMFILM 0xA405
#endif

// Define LinearRaw
#ifndef PHOTOMETRIC_LINEAR_RAW
#define PHOTOMETRIC_LINEAR_RAW 34892
#endif

#ifndef PHOTOMETRIC_CFA
#define PHOTOMETRIC_CFA 32803
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

#ifndef TIFFTAG_BASELINEEXPOSURE
#define TIFFTAG_BASELINEEXPOSURE 50730
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
    { TIFFTAG_BLACKLEVEL, -1, -1, TIFF_LONG, FIELD_CUSTOM, 1, 1, const_cast<char*>("BlackLevel") },
    { TIFFTAG_WHITELEVEL, -1, -1, TIFF_LONG, FIELD_CUSTOM, 1, 1, const_cast<char*>("WhiteLevel") },
    { TIFFTAG_COLORMATRIX1, -1, -1, TIFF_SRATIONAL, FIELD_CUSTOM, 1, 1, const_cast<char*>("ColorMatrix1") },
    { TIFFTAG_ASSHOTNEUTRAL, -1, -1, TIFF_RATIONAL, FIELD_CUSTOM, 1, 1, const_cast<char*>("AsShotNeutral") },
    { TIFFTAG_CALIBRATIONILLUMINANT1, 1, 1, TIFF_SHORT, FIELD_CUSTOM, 1, 0, const_cast<char*>("CalibrationIlluminant1") },
    { TIFFTAG_BASELINEEXPOSURE, 1, 1, TIFF_SRATIONAL, FIELD_CUSTOM, 1, 0, const_cast<char*>("BaselineExposure") },
    { TIFFTAG_DATETIMEORIGINAL, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("DateTimeOriginal") },
    { TIFFTAG_DATETIMEDIGITIZED, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("DateTimeDigitized") },
    { TIFFTAG_OFFSETTIME, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("OffsetTime") },
    { TIFFTAG_OFFSETTIMEORIGINAL, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("OffsetTimeOriginal") },
    { TIFFTAG_OFFSETTIMEDIGITIZED, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("OffsetTimeDigitized") },
    { TIFFTAG_SUBSECTIME, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("SubSecTime") },
    { TIFFTAG_SUBSECTIMEORIGINAL, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("SubSecTimeOriginal") },
    { TIFFTAG_SUBSECTIMEDIGITIZED, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("SubSecTimeDigitized") },
    { TIFFTAG_LENSMODEL, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, const_cast<char*>("LensModel") },
    { TIFFTAG_FOCALLENGTHIN35MMFILM, 1, 1, TIFF_SHORT, FIELD_CUSTOM, 1, 0, const_cast<char*>("FocalLengthIn35mmFilm") }
};

static void DNGTagExtender(TIFF *tif) {
    TIFFMergeFieldInfo(tif, dng_field_info, sizeof(dng_field_info) / sizeof(dng_field_info[0]));
}

// --- Metadata Helpers ---
static void format_exif_time(int64_t millis, char* buffer) {
    time_t raw_time = (time_t)(millis / 1000);
    struct tm timeinfo;
    localtime_r(&raw_time, &timeinfo);
    strftime(buffer, 20, "%Y:%m:%d %H:%M:%S", &timeinfo);
}

static void format_exif_subsec(int64_t millis, char* buffer) {
    sprintf(buffer, "%03d", (int)(millis % 1000));
}

static void write_tiff_metadata(TIFF* tif, const ImageMetadata* metadata) {
    if (!metadata) return;

    TIFFSetField(tif, TIFFTAG_MAKE, metadata->make.c_str());
    TIFFSetField(tif, TIFFTAG_MODEL, metadata->model.c_str());
    TIFFSetField(tif, TIFFTAG_SOFTWARE, metadata->software.c_str());
    TIFFSetField(tif, TIFFTAG_IMAGEDESCRIPTION, metadata->imageDescription.c_str());
    if (!metadata->lensModel.empty()) {
        TIFFSetField(tif, TIFFTAG_LENSMODEL, metadata->lensModel.c_str());
    }

    char buffer[20];
    char subsec_buffer[4];

    format_exif_time(metadata->captureTimeMillis, buffer);
    TIFFSetField(tif, TIFFTAG_DATETIME, buffer);
    TIFFSetField(tif, TIFFTAG_DATETIMEORIGINAL, buffer);
    format_exif_subsec(metadata->captureTimeMillis, subsec_buffer);
    TIFFSetField(tif, TIFFTAG_SUBSECTIME, subsec_buffer);
    TIFFSetField(tif, TIFFTAG_SUBSECTIMEORIGINAL, subsec_buffer);

    format_exif_time(metadata->digitizedTimeMillis, buffer);
    TIFFSetField(tif, TIFFTAG_DATETIMEDIGITIZED, buffer);
    format_exif_subsec(metadata->digitizedTimeMillis, subsec_buffer);
    TIFFSetField(tif, TIFFTAG_SUBSECTIMEDIGITIZED, subsec_buffer);

    if (!metadata->offsetTime.empty()) TIFFSetField(tif, TIFFTAG_OFFSETTIME, metadata->offsetTime.c_str());
    if (!metadata->offsetTimeOriginal.empty()) TIFFSetField(tif, TIFFTAG_OFFSETTIMEORIGINAL, metadata->offsetTimeOriginal.c_str());
    if (!metadata->offsetTimeDigitized.empty()) TIFFSetField(tif, TIFFTAG_OFFSETTIMEDIGITIZED, metadata->offsetTimeDigitized.c_str());
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

// --- Initialization ---
void init_color_pipe() {
    TIFFSetWarningHandler(nullptr);
    TIFFSetErrorHandler(nullptr);
}

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
float f_log2(float x) {
    const float a = 5.555556f, b = 0.064829f, c = 0.245281f, d = 0.384316f, e = 8.799461f, f = 0.092864f, cut = 0.000889f;
    if (x >= cut) return c * log10(a * x + b) + d;
    else return e * x + f;
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
        case 2: return f_log(x);
        case 3:
        case 4: return f_log2(x);
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


AdaptiveEdgeComp calculate_adaptive_edge_comp(const unsigned short* planarData, int stride_x, int stride_y, int stride_c, int width, int height) {
    AdaptiveEdgeComp edgeComp;
    const float cx = 0.5f * (width - 1);
    const float cy = 0.5f * (height - 1);
    const float maxRadius = std::sqrt(cx * cx + cy * cy);

    edgeComp.centerX = cx;
    edgeComp.centerY = cy;
    edgeComp.invMaxRadius = (maxRadius > 1e-6f) ? (1.0f / maxRadius) : 1.0f;

    double c_sum0 = 0.0, c_sum1 = 0.0, c_sum2 = 0.0;
    double e_sum0 = 0.0, e_sum1 = 0.0, e_sum2 = 0.0;
    int centerCount = 0;
    int edgeCount = 0;

    #pragma omp parallel for reduction(+:c_sum0,c_sum1,c_sum2,e_sum0,e_sum1,e_sum2,centerCount,edgeCount)
    for (int y = 0; y < height; y += kAnalysisStep) {
        for (int x = 0; x < width; x += kAnalysisStep) {
            const float nx = (x - cx) * edgeComp.invMaxRadius;
            const float ny = (y - cy) * edgeComp.invMaxRadius;
            const float r = std::sqrt(nx * nx + ny * ny);

            size_t r_idx = x*stride_x + y*stride_y + 0*stride_c;
            size_t g_idx = x*stride_x + y*stride_y + 1*stride_c;
            size_t b_idx = x*stride_x + y*stride_y + 2*stride_c;
            float rr = static_cast<float>(planarData[r_idx]);
            float gg = static_cast<float>(planarData[g_idx]);
            float bb = static_cast<float>(planarData[b_idx]);

            if (r <= kCenterRegionRadius) {
                c_sum0 += rr; c_sum1 += gg; c_sum2 += bb; centerCount++;
            } else if (r >= kEdgeRegionStartRadius) {
                e_sum0 += rr; e_sum1 += gg; e_sum2 += bb; edgeCount++;
            }
        }
    }

    std::array<double, 3> centerSum{c_sum0, c_sum1, c_sum2};
    std::array<double, 3> edgeSum{e_sum0, e_sum1, e_sum2};

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

    // Adaptive edge compensation is disabled in favor of sensor-calibrated hardware LensShadingCorrection.
    edgeComp.enabled = false;
    edgeComp.lumaEdgeGain = 1.0f;
    edgeComp.chromaEdgeGain = {1.0f, 1.0f, 1.0f};
    return edgeComp;
}

} // namespace

bool process_and_save_image(
    const unsigned short* planarData,
    int stride_x,
    int stride_y,
    int stride_c,
    const float* lensShadingVec,
    int lensShadingRows,
    int lensShadingCols,
    int width, int height, float gain, int targetLog, const LUT3D& lut,
    float exposure, float contrast, float saturation,
    float highlights, float shadows, float whites, float blacks,
    const char* jpgPath, const char* tiffPath, const ImageMetadata* metadata, int sourceColorSpace,
    const float* ccm, const float* wb, int orientation, unsigned char* out_rgb_buffer,
    int out_width, int out_height,
    bool isPreview, int downsampleFactor, float zoomFactor, bool mirror
) {
    LOGD("process_and_save_image: %dx%d, gain=%.2f, log=%d, lut=%d, jpg=%s, tiff=%s, preview=%d, ds=%d, zoom=%.2f, mirror=%d",
         width, height, gain, targetLog, lut.size, jpgPath ? jpgPath : "null", tiffPath ? tiffPath : "null", isPreview, downsampleFactor, zoomFactor, mirror);
    int outW = width / downsampleFactor, outH = height / downsampleFactor;
    bool swapDims = (orientation == 90 || orientation == 270);
    int finalW = swapDims ? outH : outW, finalH = swapDims ? outW : outH;
    Matrix3x3 effective_CCM = {0}; if (sourceColorSpace == 1 && ccm) std::copy(ccm, ccm + 9, effective_CCM.m);
    thread_local std::vector<unsigned short> tls_processedImage; 
    thread_local std::vector<unsigned char> tls_previewRgb8;

    AdaptiveEdgeComp edgeComp = calculate_adaptive_edge_comp(planarData, stride_x, stride_y, stride_c, width, height);

    // Debug stage split output (A/B/C):
    const bool enableStageDebug = false;
    std::string debugBasePath = jpgPath ? std::string(jpgPath) : std::string();
    std::string debugPathA = enableStageDebug ? build_debug_stage_path(debugBasePath.c_str(), "_debug_A_linear") : std::string();
    std::string debugPathB = enableStageDebug ? build_debug_stage_path(debugBasePath.c_str(), "_debug_B_matrix") : std::string();
    std::string debugPathC = enableStageDebug ? build_debug_stage_path(debugBasePath.c_str(), "_debug_C_log") : std::string();

    thread_local std::vector<unsigned char> tls_debugA8;
    thread_local std::vector<unsigned char> tls_debugB8;
    thread_local std::vector<unsigned char> tls_debugC8;
    
    std::vector<unsigned short>& processedImage = tls_processedImage;
    std::vector<unsigned char>& previewRgb8 = tls_previewRgb8;
    std::vector<unsigned char>& debugA8 = tls_debugA8;
    std::vector<unsigned char>& debugB8 = tls_debugB8;
    std::vector<unsigned char>& debugC8 = tls_debugC8;

    const bool hasLsc = (lensShadingVec != nullptr && lensShadingRows > 0 && lensShadingCols > 0);
    auto lsc_idx = [&](int ch, int row, int col) -> int {
        return ch * lensShadingRows * lensShadingCols + row * lensShadingCols + col;
    };
    struct LscWeight { int idx0, idx1; float w0, w1; };
    std::vector<LscWeight> lscX, lscY;
    if (hasLsc) {
        lscX.resize(width); lscY.resize(height);
        for(int x=0; x<width; x++) {
            float fx = (width > 1) ? (float)x * (lensShadingCols - 1) / (float)(width - 1) : 0.0f;
            lscX[x].idx0 = std::clamp((int)std::floor(fx), 0, lensShadingCols - 1);
            lscX[x].idx1 = std::min(lscX[x].idx0 + 1, lensShadingCols - 1);
            lscX[x].w1 = fx - lscX[x].idx0;
            lscX[x].w0 = 1.0f - lscX[x].w1;
        }
        for(int y=0; y<height; y++) {
            float fy = (height > 1) ? (float)y * (lensShadingRows - 1) / (float)(height - 1) : 0.0f;
            lscY[y].idx0 = std::clamp((int)std::floor(fy), 0, lensShadingRows - 1);
            lscY[y].idx1 = std::min(lscY[y].idx0 + 1, lensShadingRows - 1);
            lscY[y].w1 = fy - lscY[y].idx0;
            lscY[y].w0 = 1.0f - lscY[y].w1;
        }
    }

    auto process_pixel = [&](int x, int y, Vec3* stageA, Vec3* stageB, Vec3* stageC) -> Vec3 {
        x = std::max(0, std::min(x, width - 1));
        y = std::max(0, std::min(y, height - 1));
        
        size_t r_idx = (size_t)y*stride_y + (size_t)x*stride_x + 0*stride_c;
        size_t g_idx = (size_t)y*stride_y + (size_t)x*stride_x + 1*stride_c;
        size_t b_idx = (size_t)y*stride_y + (size_t)x*stride_x + 2*stride_c;
        
        float r = static_cast<float>(planarData[r_idx]);
        float g = static_cast<float>(planarData[g_idx]);
        float b = static_cast<float>(planarData[b_idx]);
        
        // 1. Apply White Balance
        if (wb) {
            r *= wb[0];
            g *= wb[1];
            b *= wb[3];
        }

        // 2. Highlight Desaturation
        float max_rgb = std::max({r, g, b});
        float theoretical_max = 65535.0f * (wb ? std::max({wb[0], wb[1], wb[3]}) : 1.0f);
        float threshold = 65535.0f * 0.8f;
        if (max_rgb > threshold) {
            float desat = std::clamp((max_rgb - threshold) / (theoretical_max - threshold), 0.0f, 1.0f);
            desat = desat * desat * (3.0f - 2.0f * desat); // Smoothstep
            float y_lum = 0.299f * r + 0.587f * g + 0.114f * b;
            r = r * (1.0f - desat) + y_lum * desat;
            g = g * (1.0f - desat) + y_lum * desat;
            b = b * (1.0f - desat) + y_lum * desat;
        }

        r = std::min(r, 65535.0f);
        g = std::min(g, 65535.0f);
        b = std::min(b, 65535.0f);
        
        float exp_gain = std::pow(2.0f, exposure);
        float norm_r = (r / 65535.0f) * gain * exp_gain;
        float norm_g = (g / 65535.0f) * gain * exp_gain;
        float norm_b = (b / 65535.0f) * gain * exp_gain;

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
        else if (sourceColorSpace == 2) { color = multiply(M_sRGB_D65_to_XYZ, color); }
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

        // Apply ACES Filmic Tone Mapping ONLY for standard sRGB output
        if (targetLog == 0) {
            auto aces_fit = [](float v) {
                float a = 2.51f, b = 0.03f, c = 2.43f, d = 0.59f, e = 0.14f;
                return std::clamp((v * (a * v + b)) / (v * (c * v + d) + e), 0.0f, 1.0f);
            };
            color.r = aces_fit(color.r);
            color.g = aces_fit(color.g);
            color.b = aces_fit(color.b);
        }

        color.r = apply_log(color.r, targetLog); color.g = apply_log(color.g, targetLog); color.b = apply_log(color.b, targetLog);

        // 2. Contrast & Saturation (Log Space)
        auto apply_contrast = [&](float v) {
            return std::max(0.0f, (v - 0.5f) * (contrast + 1.0f) + 0.5f);
        };
        color.r = apply_contrast(color.r);
        color.g = apply_contrast(color.g);
        color.b = apply_contrast(color.b);

        float luma = 0.2126f * color.r + 0.7152f * color.g + 0.0722f * color.b;
        color.r = std::max(0.0f, luma + (color.r - luma) * (saturation + 1.0f));
        color.g = std::max(0.0f, luma + (color.g - luma) * (saturation + 1.0f));
        color.b = std::max(0.0f, luma + (color.b - luma) * (saturation + 1.0f));

        // 3. Highlights / Shadows / Whites / Blacks (Log Space)
        auto apply_hswb = [&](float v) {
            // Highlights: affecting upper range
            if (highlights != 0.0f) {
                float weight = std::pow(std::clamp(v, 0.0f, 1.0f), 2.0f);
                v += highlights * weight * 0.2f;
            }
            // Shadows: affecting lower range
            if (shadows != 0.0f) {
                float weight = std::pow(1.0f - std::clamp(v, 0.0f, 1.0f), 2.0f);
                v += shadows * weight * 0.2f;
            }
            // Whites: offset upper
            if (whites != 0.0f) {
                float weight = std::clamp((v - 0.5f) * 2.0f, 0.0f, 1.0f);
                v += whites * weight * 0.2f;
            }
            // Blacks: offset lower
            if (blacks != 0.0f) {
                float weight = std::clamp((0.5f - v) * 2.0f, 0.0f, 1.0f);
                v += blacks * weight * 0.2f;
            }
            return std::max(0.0f, v);
        };
        color.r = apply_hswb(color.r);
        color.g = apply_hswb(color.g);
        color.b = apply_hswb(color.b);

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
        // If a bitmap buffer is provided, prioritize its dimensions.
        // This ensures no out-of-bounds writes even if Kotlin and JNI have different size expectations.
        int renderW = (out_rgb_buffer && out_width > 0) ? out_width : finalW_zoomed;
        int renderH = (out_rgb_buffer && out_height > 0) ? out_height : finalH_zoomed;

        previewRgb8.resize(static_cast<size_t>(renderW) * renderH * 3);
        #pragma omp parallel for
        for (int py = 0; py < renderH; py++) {
            for (int px = 0; px < renderW; px++) {
                int sx, sy;
                int opx = mirror ? (renderW - 1 - px) : px;

                // Map back to source pixels using the actual render dimensions
                // This is safer than using downsampleFactor directly if dimensions mismatch
                float fx = (float)opx / renderW * (swapDims ? cropH : cropW);
                float fy = (float)py / renderH * (swapDims ? cropW : cropH);

                if (orientation == 90) { sx = (int)fy; sy = (cropH - 1) - (int)fx; }
                else if (orientation == 180) { sx = (cropW - 1) - (int)fx; sy = (cropH - 1) - (int)fy; }
                else if (orientation == 270) { sx = (cropW - 1) - (int)fy; sy = (int)fx; }
                else { sx = (int)fx; sy = (int)fy; }

                Vec3 color = process_pixel(cropX + sx, cropY + sy, nullptr, nullptr, nullptr);
                size_t outIdx = (static_cast<size_t>(py) * renderW + px) * 3;
                unsigned char r8 = (unsigned char)std::max(0.0f, std::min(255.0f, color.r * 255.0f + 0.5f));
                unsigned char g8 = (unsigned char)std::max(0.0f, std::min(255.0f, color.g * 255.0f + 0.5f));
                unsigned char b8 = (unsigned char)std::max(0.0f, std::min(255.0f, color.b * 255.0f + 0.5f));

                previewRgb8[outIdx + 0] = r8;
                previewRgb8[outIdx + 1] = g8;
                previewRgb8[outIdx + 2] = b8;

                if (out_rgb_buffer) {
                    size_t bIdx = (static_cast<size_t>(py) * renderW + px) * 4;
                    out_rgb_buffer[bIdx+0] = r8;
                    out_rgb_buffer[bIdx+1] = g8;
                    out_rgb_buffer[bIdx+2] = b8;
                    out_rgb_buffer[bIdx+3] = 255;
                }
            }
        }
        // Update dimensions for JPEG writing if we used bitmap dimensions
        finalW_zoomed = renderW;
        finalH_zoomed = renderH;
    } else {
        processedImage.resize(static_cast<size_t>(finalW_zoomed) * finalH_zoomed * 3);
        #pragma omp parallel for
        for (int py = 0; py < finalH_zoomed; py++) {
            for (int px = 0; px < finalW_zoomed; px++) {
                int sx, sy;
                int opx = mirror ? (finalW_zoomed - 1 - px) : px;

                float fx = (float)opx / finalW_zoomed * (swapDims ? cropH : cropW);
                float fy = (float)py / finalH_zoomed * (swapDims ? cropW : cropH);

                if (orientation == 90) { sx = (int)fy; sy = (cropH - 1) - (int)fx; }
                else if (orientation == 180) { sx = (cropW - 1) - (int)fx; sy = (cropH - 1) - (int)fy; }
                else if (orientation == 270) { sx = (cropW - 1) - (int)fy; sy = (int)fx; }
                else { sx = (int)fx; sy = (int)fy; }

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
                if (out_rgb_buffer) {
                    size_t bIdx = (static_cast<size_t>(py) * finalW_zoomed + px) * 4;
                    out_rgb_buffer[bIdx+0] = (unsigned char)std::min(255, (processedImage[outIdx+0] + 128) >> 8);
                    out_rgb_buffer[bIdx+1] = (unsigned char)std::min(255, (processedImage[outIdx+1] + 128) >> 8);
                    out_rgb_buffer[bIdx+2] = (unsigned char)std::min(255, (processedImage[outIdx+2] + 128) >> 8);
                    out_rgb_buffer[bIdx+3] = 255;
                }
            }
        }
    }

    bool tiffOk = true;
    if (tiffPath && !isPreview) {
        tiffOk = write_tiff(tiffPath, finalW_zoomed, finalH_zoomed, processedImage.data(), 3, finalW_zoomed*3, 1, metadata);
        if (!tiffOk) LOGE("write_tiff failed for %s", tiffPath);
        else LOGD("Successfully wrote TIFF: %s", tiffPath);
    }

    const int jpegQuality = isPreview ? 78 : 95;
    bool jpgOk = true;
    if (jpgPath) {
        if (isPreview && !previewRgb8.empty()) {
            jpgOk = write_jpeg_turbo(jpgPath, finalW_zoomed, finalH_zoomed, TJSAMP_422, previewRgb8.data(), jpegQuality);
        } else {
            jpgOk = write_jpeg(jpgPath, finalW_zoomed, finalH_zoomed, processedImage.data(), 3, finalW_zoomed*3, 1, jpegQuality);
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
        bool aOk = write_jpeg_turbo(debugPathA.c_str(), finalW_zoomed, finalH_zoomed, TJSAMP_444, debugA8.data(), 95);
        bool bOk = write_jpeg_turbo(debugPathB.c_str(), finalW_zoomed, finalH_zoomed, TJSAMP_444, debugB8.data(), 95);
        bool cOk = write_jpeg_turbo(debugPathC.c_str(), finalW_zoomed, finalH_zoomed, TJSAMP_444, debugC8.data(), 95);
        LOGD("Stage debug outputs: A=%s (%d), B=%s (%d), C=%s (%d)",
             debugPathA.c_str(), (int)aOk,
             debugPathB.c_str(), (int)bOk,
             debugPathC.c_str(), (int)cOk);
    }

    return jpgOk;
}

bool write_tiff(const char* filename, int width, int height, const unsigned short* planarData, int stride_x, int stride_y, int stride_c, const ImageMetadata* metadata) {
    LOGD("write_tiff: %s, %dx%d", filename, width, height);
    TIFF* tif = TIFFOpen(filename, "w");
    if (!tif) {
        LOGE("Could not open TIFF for writing: %s", filename);
        return false;
    }

    TIFFSetField(tif, TIFFTAG_IMAGEWIDTH, width);
    TIFFSetField(tif, TIFFTAG_IMAGELENGTH, height);
    TIFFSetField(tif, TIFFTAG_BITSPERSAMPLE, 16);
    TIFFSetField(tif, TIFFTAG_SAMPLESPERPIXEL, 3);
    TIFFSetField(tif, TIFFTAG_SAMPLEFORMAT, SAMPLEFORMAT_UINT);
    TIFFSetField(tif, TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_RGB);
    TIFFSetField(tif, TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);
    TIFFSetField(tif, TIFFTAG_COMPRESSION, COMPRESSION_NONE);
    TIFFSetField(tif, TIFFTAG_ORIENTATION, ORIENTATION_TOPLEFT);

    write_tiff_metadata(tif, metadata);

    std::vector<unsigned short> row(static_cast<size_t>(width) * 3);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            size_t r_idx = (size_t)y * stride_y + (size_t)x * stride_x + 0 * stride_c;
            size_t g_idx = (size_t)y * stride_y + (size_t)x * stride_x + 1 * stride_c;
            size_t b_idx = (size_t)y * stride_y + (size_t)x * stride_x + 2 * stride_c;
            row[x * 3 + 0] = planarData[r_idx];
            row[x * 3 + 1] = planarData[g_idx];
            row[x * 3 + 2] = planarData[b_idx];
        }
        if (TIFFWriteScanline(tif, (void*)row.data(), y, 0) < 0) {
            LOGE("Error writing TIFF scanline %d", y);
            TIFFClose(tif);
            return false;
        }
    }

    TIFFClose(tif);
    return true;
}

bool write_tiff_rgba8(const char* filename, int width, int height, const unsigned char* data, const ImageMetadata* metadata) {
    LOGD("write_tiff_rgba8: %s, %dx%d", filename, width, height);
    TIFF* tif = TIFFOpen(filename, "w");
    if (!tif) {
        LOGE("Could not open TIFF for writing: %s", filename);
        return false;
    }

    TIFFSetField(tif, TIFFTAG_IMAGEWIDTH, width);
    TIFFSetField(tif, TIFFTAG_IMAGELENGTH, height);
    TIFFSetField(tif, TIFFTAG_BITSPERSAMPLE, 8);
    TIFFSetField(tif, TIFFTAG_SAMPLESPERPIXEL, 3); // Force 3 channels (RGB)
    TIFFSetField(tif, TIFFTAG_SAMPLEFORMAT, SAMPLEFORMAT_UINT);
    TIFFSetField(tif, TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_RGB);
    TIFFSetField(tif, TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);
    TIFFSetField(tif, TIFFTAG_COMPRESSION, COMPRESSION_NONE);
    TIFFSetField(tif, TIFFTAG_ORIENTATION, ORIENTATION_TOPLEFT);

    write_tiff_metadata(tif, metadata);

    std::vector<unsigned char> row(static_cast<size_t>(width) * 3);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            row[x * 3 + 0] = data[(static_cast<size_t>(y) * width + x) * 4 + 0];
            row[x * 3 + 1] = data[(static_cast<size_t>(y) * width + x) * 4 + 1];
            row[x * 3 + 2] = data[(static_cast<size_t>(y) * width + x) * 4 + 2];
        }
        if (TIFFWriteScanline(tif, (void*)row.data(), y, 0) < 0) {
            TIFFClose(tif);
            return false;
        }
    }
    TIFFClose(tif);
    return true;
}

bool write_jpeg(const char* filename, int width, int height, const unsigned short* planarData, int stride_x, int stride_y, int stride_c, int quality) {
    LOGD("write_jpeg: %s, %dx%d", filename, width, height);
    size_t total_pixels = static_cast<size_t>(width) * height;
    thread_local std::vector<unsigned char> tls_rgb8;
    std::vector<unsigned char>& rgb8 = tls_rgb8;
    try {
        rgb8.resize(total_pixels * 3);
    } catch (const std::bad_alloc& e) {
        LOGE("Failed to allocate memory for JPEG conversion: %zu bytes", total_pixels * 3);
        return false;
    }

    #pragma omp parallel for
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            size_t r_idx = (size_t)y * stride_y + (size_t)x * stride_x + 0 * stride_c;
            size_t g_idx = (size_t)y * stride_y + (size_t)x * stride_x + 1 * stride_c;
            size_t b_idx = (size_t)y * stride_y + (size_t)x * stride_x + 2 * stride_c;
            size_t dst_idx = ((size_t)y * width + x) * 3;
            rgb8[dst_idx + 0] = (unsigned char)std::min(255, (planarData[r_idx] + 128) >> 8);
            rgb8[dst_idx + 1] = (unsigned char)std::min(255, (planarData[g_idx] + 128) >> 8);
            rgb8[dst_idx + 2] = (unsigned char)std::min(255, (planarData[b_idx] + 128) >> 8);
        }
    }
    return write_jpeg_turbo(filename, width, height, TJSAMP_422, rgb8.data(), quality);
}

int compute_preview_downsample_factor(int width, int height, int targetLongEdge) {
    if (width <= 0 || height <= 0 || targetLongEdge <= 0) return 1;
    const int longEdge = std::max(width, height);
    return std::max(1, (longEdge + targetLongEdge - 1) / targetLongEdge);
}



static const std::vector<unsigned char>& make_preview_rgb8(
    const unsigned short* planarData, int stride_x, int stride_y, int stride_c,
    int width,
    int height,
    int targetLongEdge,
    int orientation,
    bool mirror,
    float gain,
    int& outWidth,
    int& outHeight,
    const float* wbVec = nullptr
) {
    const int longEdge = std::max(width, height);
    const int scale = std::max(1, (longEdge + targetLongEdge - 1) / targetLongEdge);
    const int sampledWidth = std::max(1, width / scale);
    const int sampledHeight = std::max(1, height / scale);
    const bool swapDims = (orientation == 90 || orientation == 270);
    outWidth = swapDims ? sampledHeight : sampledWidth;
    outHeight = swapDims ? sampledWidth : sampledHeight;

    thread_local std::vector<unsigned char> preview;
    preview.resize(static_cast<size_t>(outWidth) * outHeight * 3);
    for (int y = 0; y < outHeight; ++y) {
        for (int x = 0; x < outWidth; ++x) {
            int sx = x;
            int sy = y;
            const int opx = mirror ? (outWidth - 1 - x) : x;
            if (orientation == 90) {
                sx = y;
                sy = (outWidth - 1) - opx;
            } else if (orientation == 180) {
                sx = (outWidth - 1) - opx;
                sy = (outHeight - 1) - y;
            } else if (orientation == 270) {
                sx = (outHeight - 1) - y;
                sy = opx;
            } else {
                sx = opx;
            }

            const int srcX = std::min(width - 1, sx * scale);
            const int srcY = std::min(height - 1, sy * scale);
            const size_t r_idx = (size_t)srcY*stride_y + (size_t)srcX*stride_x + 0*stride_c;
            const size_t g_idx = (size_t)srcY*stride_y + (size_t)srcX*stride_x + 1*stride_c;
            const size_t b_idx = (size_t)srcY*stride_y + (size_t)srcX*stride_x + 2*stride_c;
            const size_t dstIdx = (static_cast<size_t>(y) * outWidth + x) * 3;

            auto encodePreviewChannel = [&](unsigned short sample, float wbGain) -> unsigned char {
                const float linear = std::clamp((sample * wbGain / 65535.0f) * gain, 0.0f, 1.0f); // 16-bit max from Halide
                const float gammaEncoded = std::pow(linear, 1.0f / 2.2f);
                return (unsigned char)std::clamp(gammaEncoded * 255.0f + 0.5f, 0.0f, 255.0f);
            };

            float wb_r = wbVec ? wbVec[0] : 1.0f;
            float wb_g = wbVec ? wbVec[1] : 1.0f;
            float wb_b = wbVec ? wbVec[3] : 1.0f;

            preview[dstIdx + 0] = encodePreviewChannel(planarData[r_idx], wb_r);
            preview[dstIdx + 1] = encodePreviewChannel(planarData[g_idx], wb_g);
            preview[dstIdx + 2] = encodePreviewChannel(planarData[b_idx], wb_b);
        }
    }
    return preview;
}

Matrix3x3 inverse_matrix(const Matrix3x3& m) {
    Matrix3x3 inv;
    float det = m.m[0]*(m.m[4]*m.m[8] - m.m[5]*m.m[7]) -
                m.m[1]*(m.m[3]*m.m[8] - m.m[5]*m.m[6]) +
                m.m[2]*(m.m[3]*m.m[7] - m.m[4]*m.m[6]);
    float invdet = (det != 0.0f) ? (1.0f / det) : 1.0f;
    inv.m[0] = (m.m[4]*m.m[8] - m.m[5]*m.m[7]) * invdet;
    inv.m[1] = (m.m[2]*m.m[7] - m.m[1]*m.m[8]) * invdet;
    inv.m[2] = (m.m[1]*m.m[5] - m.m[2]*m.m[4]) * invdet;
    inv.m[3] = (m.m[5]*m.m[6] - m.m[3]*m.m[8]) * invdet;
    inv.m[4] = (m.m[0]*m.m[8] - m.m[2]*m.m[6]) * invdet;
    inv.m[5] = (m.m[2]*m.m[3] - m.m[0]*m.m[5]) * invdet;
    inv.m[6] = (m.m[3]*m.m[7] - m.m[4]*m.m[6]) * invdet;
    inv.m[7] = (m.m[1]*m.m[6] - m.m[0]*m.m[7]) * invdet;
    inv.m[8] = (m.m[0]*m.m[4] - m.m[1]*m.m[3]) * invdet;
    return inv;
}

bool write_dng(const char* filename, int width, int height, const unsigned short* planarData, int stride_x, int stride_y, int stride_c, int whiteLevel, const std::vector<float>& ccm, const ImageMetadata& metadata, int orientation, bool mirror, float baselineExposure, const float* wbVec) {
    TIFFSetTagExtender(DNGTagExtender);
    TIFF* tif = TIFFOpen(filename, "w");
    if (!tif) return false;

    TIFFSetField(tif, TIFFTAG_IMAGEWIDTH, width);
    TIFFSetField(tif, TIFFTAG_IMAGELENGTH, height);
    TIFFSetField(tif, TIFFTAG_BITSPERSAMPLE, 16);
    TIFFSetField(tif, TIFFTAG_COMPRESSION, COMPRESSION_NONE);

    uint16_t tiffOrientation = 1;
    switch (orientation) {
        case 90: tiffOrientation = mirror ? 5 : 6; break;
        case 180: tiffOrientation = mirror ? 4 : 3; break;
        case 270: tiffOrientation = mirror ? 7 : 8; break;
        default: tiffOrientation = mirror ? 2 : 1; break;
    }
    TIFFSetField(tif, TIFFTAG_ORIENTATION, tiffOrientation);
    TIFFSetField(tif, TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_LINEAR_RAW);
    TIFFSetField(tif, TIFFTAG_SAMPLESPERPIXEL, 3);
    TIFFSetField(tif, TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);
    TIFFSetField(tif, TIFFTAG_ROWSPERSTRIP, 1);
    TIFFSetField(tif, TIFFTAG_SUBFILETYPE, 0);

    write_tiff_metadata(tif, &metadata);

    static const uint8_t dng_version[] = {1, 4, 0, 0};
    TIFFSetField(tif, TIFFTAG_DNGVERSION, dng_version);
    static const uint8_t dng_backward_version[] = {1, 1, 0, 0};
    TIFFSetField(tif, TIFFTAG_DNGBACKWARDVERSION, dng_backward_version);
    TIFFSetField(tif, TIFFTAG_UNIQUECAMERAMODEL, metadata.uniqueCameraModel.c_str());

    uint32_t white_level_val = (uint32_t)whiteLevel;
    if (white_level_val == 0) white_level_val = 65535;
    TIFFSetField(tif, TIFFTAG_WHITELEVEL, 1, &white_level_val);
    uint32_t black_level_val = 0;
    TIFFSetField(tif, TIFFTAG_BLACKLEVEL, 1, &black_level_val);

    float as_shot_neutral[3] = {
        wbVec ? (1.0f / std::max(1e-4f, wbVec[0])) : 1.0f,
        wbVec ? (1.0f / std::max(1e-4f, wbVec[1])) : 1.0f,
        wbVec ? (1.0f / std::max(1e-4f, wbVec[3])) : 1.0f
    };
    TIFFSetField(tif, TIFFTAG_ASSHOTNEUTRAL, 3, as_shot_neutral);

    // In Android Camera2 API, CCM maps White-Balanced Sensor RGB -> sRGB Linear.
    // In Adobe DNG Specification, ColorMatrix1 maps XYZ -> Un-white-balanced Raw Sensor Space.
    // Therefore:
    // RawSensorRGB = diag(AsShotNeutral) * CCM^-1 * sRGB
    // ColorMatrix1 = diag(AsShotNeutral) * Inverse(CCM) * M_XYZ_to_sRGB_D65
    Matrix3x3 colorMatrix1 = M_XYZ_to_sRGB_D65; // Fallback
    if (ccm.size() >= 9) {
        Matrix3x3 sensor_to_srgb = {
            ccm[0], ccm[1], ccm[2],
            ccm[3], ccm[4], ccm[5],
            ccm[6], ccm[7], ccm[8]
        };
        Matrix3x3 srgb_to_sensor = inverse_matrix(sensor_to_srgb);
        colorMatrix1 = multiply(srgb_to_sensor, M_XYZ_to_sRGB_D65);
    }
    
    // Scale each row by AsShotNeutral to map XYZ into Raw Sensor space
    for (int r = 0; r < 3; r++) {
        colorMatrix1.m[r * 3 + 0] *= as_shot_neutral[r];
        colorMatrix1.m[r * 3 + 1] *= as_shot_neutral[r];
        colorMatrix1.m[r * 3 + 2] *= as_shot_neutral[r];
    }
    TIFFSetField(tif, TIFFTAG_COLORMATRIX1, 9, colorMatrix1.m);

    TIFFSetField(tif, TIFFTAG_CALIBRATIONILLUMINANT1, 21);
    float exposureTimeSec = (float)metadata.exposureTime / 1000000000.0f;
    TIFFSetField(tif, TIFFTAG_EXPOSURETIME, exposureTimeSec);
    TIFFSetField(tif, TIFFTAG_FNUMBER, metadata.fNumber);
    TIFFSetField(tif, TIFFTAG_FOCALLENGTH, metadata.focalLength);
    if (metadata.focalLengthIn35mmFilm > 0) {
        TIFFSetField(tif, TIFFTAG_FOCALLENGTHIN35MMFILM, (uint16_t)metadata.focalLengthIn35mmFilm);
    }

    float safeBaselineExposure = std::clamp(baselineExposure, 0.0f, 0.5f);
    TIFFSetField(tif, TIFFTAG_BASELINEEXPOSURE, safeBaselineExposure);

    unsigned short iso_short = (unsigned short)metadata.iso;
    TIFFSetField(tif, TIFFTAG_ISOSPEEDRATINGS, (uint16_t)1, &iso_short);

    std::vector<unsigned short> rowBuffer(width * 3);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
             size_t r_idx = (size_t)y*stride_y + (size_t)x*stride_x + 0*stride_c;
             size_t g_idx = (size_t)y*stride_y + (size_t)x*stride_x + 1*stride_c;
             size_t b_idx = (size_t)y*stride_y + (size_t)x*stride_x + 2*stride_c;
             
             // Keep pure Sensor Linear data in DNG without destructive pre-multiplied WB clamping
             rowBuffer[x*3+0] = planarData[r_idx];
             rowBuffer[x*3+1] = planarData[g_idx];
             rowBuffer[x*3+2] = planarData[b_idx];
        }
        if (TIFFWriteScanline(tif, rowBuffer.data(), y, 0) < 0) {
            TIFFClose(tif);
            return false;
        }
    }

    if (!TIFFWriteDirectory(tif)) {
        TIFFClose(tif);
        return false;
    }

    const struct PreviewSpec {
        int targetLongEdge;
        const char* description;
    } previewSpecs[] = {
        {512, "Darkbag Embedded JPEG Thumbnail"},
        {2048, "Darkbag Embedded JPEG Preview"},
    };

    for (const auto& spec : previewSpecs) {
        int previewWidth = 0, previewHeight = 0;
        const std::vector<unsigned char>& previewRgb8 = make_preview_rgb8(
            planarData, stride_x, stride_y, stride_c, width, height, spec.targetLongEdge, orientation, mirror, std::pow(2.0f, baselineExposure), previewWidth, previewHeight, wbVec
        );

        if (previewRgb8.empty()) {
            TIFFClose(tif);
            return false;
        }

        const std::vector<unsigned char>& jpegPreview = encode_rgb8_jpeg(previewRgb8, previewWidth, previewHeight, 82);
        if (jpegPreview.empty()) {
            TIFFClose(tif);
            return false;
        }

        TIFFSetField(tif, TIFFTAG_SUBFILETYPE, FILETYPE_REDUCEDIMAGE);
        TIFFSetField(tif, TIFFTAG_IMAGEWIDTH, previewWidth);
        TIFFSetField(tif, TIFFTAG_IMAGELENGTH, previewHeight);
        TIFFSetField(tif, TIFFTAG_BITSPERSAMPLE, 8);
        TIFFSetField(tif, TIFFTAG_COMPRESSION, COMPRESSION_JPEG);
        TIFFSetField(tif, TIFFTAG_ORIENTATION, ORIENTATION_TOPLEFT);
        TIFFSetField(tif, TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_YCBCR);
        TIFFSetField(tif, TIFFTAG_SAMPLESPERPIXEL, 3);
        TIFFSetField(tif, TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);
        TIFFSetField(tif, TIFFTAG_ROWSPERSTRIP, previewHeight);
        TIFFSetField(tif, TIFFTAG_JPEGCOLORMODE, JPEGCOLORMODE_RGB);
        TIFFSetField(tif, TIFFTAG_MAKE, metadata.make.c_str());
        TIFFSetField(tif, TIFFTAG_MODEL, metadata.model.c_str());
        TIFFSetField(tif, TIFFTAG_SOFTWARE, metadata.software.c_str());
        TIFFSetField(tif, TIFFTAG_IMAGEDESCRIPTION, spec.description);

        if (TIFFWriteRawStrip(tif, 0, const_cast<unsigned char*>(jpegPreview.data()), static_cast<tmsize_t>(jpegPreview.size())) < 0) {
            TIFFClose(tif);
            return false;
        }

        if (!TIFFWriteDirectory(tif)) {
            TIFFClose(tif);
            return false;
        }
    }

    TIFFClose(tif);
    return true;
}


bool write_bmp(const char* filename, int width, int height, const unsigned short* planarData, int stride_x, int stride_y, int stride_c) {
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
            size_t r_idx = (size_t)y * stride_y + (size_t)x * stride_x + 0 * stride_c;
            size_t g_idx = (size_t)y * stride_y + (size_t)x * stride_x + 1 * stride_c;
            size_t b_idx = (size_t)y * stride_y + (size_t)x * stride_x + 2 * stride_c;
            line[static_cast<size_t>(x) * 3 + 0] = (unsigned char)std::min(255, (planarData[b_idx] + 128) >> 8);
            line[static_cast<size_t>(x) * 3 + 1] = (unsigned char)std::min(255, (planarData[g_idx] + 128) >> 8);
            line[static_cast<size_t>(x) * 3 + 2] = (unsigned char)std::min(255, (planarData[r_idx] + 128) >> 8);
        }
        file.write((char*)line.data(), padded_width);
    }

    bool result = file.good();
    file.close();
    return result;
}

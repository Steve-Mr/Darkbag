#include "ColorPipe.h"
#include <tiffio.h>

#include <vector>
#include <ctime>

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
#define TIFFTAG_DNGVERSION 50706
#define TIFFTAG_DNGBACKWARDVERSION 50707
#define TIFFTAG_UNIQUECAMERAMODEL 50708
#define TIFFTAG_BLACKLEVEL 50714
#define TIFFTAG_WHITELEVEL 50717
#define TIFFTAG_COLORMATRIX1 50721
#define TIFFTAG_ASSHOTNEUTRAL 50728
#define TIFFTAG_CALIBRATIONILLUMINANT1 50778
#define TIFFTAG_OPCODELIST1 51008
#define TIFFTAG_OPCODELIST2 51009
#define TIFFTAG_OPCODELIST3 51022

static const TIFFFieldInfo dng_field_info[] = {
    { TIFFTAG_DNGVERSION, 4, 4, TIFF_BYTE, FIELD_CUSTOM, 1, 0, "DNGVersion" },
    { TIFFTAG_DNGBACKWARDVERSION, 4, 4, TIFF_BYTE, FIELD_CUSTOM, 1, 0, "DNGBackwardVersion" },
    { TIFFTAG_UNIQUECAMERAMODEL, -1, -1, TIFF_ASCII, FIELD_CUSTOM, 1, 0, "UniqueCameraModel" },
    { TIFFTAG_BLACKLEVEL, -1, -1, TIFF_LONG, FIELD_CUSTOM, 1, 1, "BlackLevel" },
    { TIFFTAG_WHITELEVEL, -1, -1, TIFF_LONG, FIELD_CUSTOM, 1, 1, "WhiteLevel" },
    { TIFFTAG_COLORMATRIX1, -1, -1, TIFF_RATIONAL, FIELD_CUSTOM, 1, 1, "ColorMatrix1" },
    { TIFFTAG_ASSHOTNEUTRAL, -1, -1, TIFF_RATIONAL, FIELD_CUSTOM, 1, 1, "AsShotNeutral" },
    { TIFFTAG_CALIBRATIONILLUMINANT1, 1, 1, TIFF_SHORT, FIELD_CUSTOM, 1, 0, "CalibrationIlluminant1" }
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

    if (std::abs(det) < 1e-6f) return src; // Degenerate fallback

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

// sRGB_D65 (RGB -> XYZ)
const Matrix3x3 M_sRGB_D65_to_XYZ = {
    0.41239080f, 0.35758434f, 0.18048079f,
    0.21263901f, 0.71516868f, 0.07219232f,
    0.01933082f, 0.11919478f, 0.95053215f
};

// XYZ_D65 -> sRGB (Needed for ColorMatrix1 if CCM is sRGB-based)
const Matrix3x3 M_XYZ_to_sRGB_D65 = invert(M_sRGB_D65_to_XYZ);

// ProPhoto_D50 (RGB -> XYZ)
const Matrix3x3 M_ProPhoto_D50_to_XYZ = {
    0.79766723f, 0.13519223f, 0.03135253f,
    0.28803745f, 0.71187688f, 0.00008566f,
    0.00000000f, 0.00000000f, 0.82518828f
};

// XYZ -> Target Matrices
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

// Bradford Adaptation D50 -> D65
const Matrix3x3 M_Bradford_D50_to_D65 = {
    0.95553939f, -0.02305835f, 0.06322404f,
    -0.02831194f, 1.00994706f, 0.02102750f,
    0.01231027f, -0.02050341f, 1.33023150f
};

// --- Log Curves (CPU) ---
float arri_logc3(float x) {
    const float cut = 0.010591f;
    const float a = 5.555556f;
    const float b = 0.052272f;
    const float c = 0.247190f;
    const float d = 0.385537f;
    const float e = 5.367655f;
    const float f = 0.092809f;
    if (x > cut) return c * log10(a * x + b) + d;
    else return e * x + f;
}
float s_log3(float x) {
    if (x >= 0.01125000f) return (420.0f + log10((x + 0.01f) / (0.18f + 0.01f)) * 261.5f) / 1023.0f;
    else return (x * 171.2102946929f + 95.0f) / 1023.0f;
}
float f_log(float x) {
    const float a = 0.555556f;
    const float b = 0.009468f;
    const float c = 0.344676f;
    const float d = 0.790453f;
    const float cut = 0.00089f;
    if (x >= cut) return c * log10(a * x + b) + d;
    else return 8.52f * x + 0.0929f;
}
float vlog(float x) {
    const float cut = 0.01f;
    const float c = 0.241514f;
    const float b = 0.008730f;
    const float d = 0.598206f;
    if (x >= cut) return c * log10(x + b) + d;
    else return 5.6f * x + 0.125f;
}
float apply_log(float x, int type) {
    // Note: Log curves handle x < 0 usually by clipping or linear extension.
    // We clamp slightly above 0 if needed, but linear extension is better for noise.
    if (x < 0) x = 0; // Simple safety
    switch (type) {
        case 1: return arri_logc3(x);
        case 2: return f_log(x);
        case 3: return f_log(x);
        case 5: return s_log3(x);
        case 6: return s_log3(x);
        case 7: return vlog(x);
        case 0: // Standard
            // Apply standard Gamma 2.2 (Rec.709 OETF approximation)
            return (x > 0.0f) ? pow(x, 1.0f/2.2f) : 0.0f;
        default: return (x > 0.0f) ? pow(x, 1.0f/2.2f) : 0.0f;
    }
}

// --- LUT (CPU) ---
LUT3D load_lut(const char* path) {
    LUT3D lut;
    lut.size = 0;
    std::ifstream file(path);
    if (!file.is_open()) return lut;

    const size_t MAX_LINE_LENGTH = 1024;
    const size_t MAX_DATA_POINTS = 64 * 64 * 64;

    std::string line;
    while (std::getline(file, line)) {
        if (line.length() > MAX_LINE_LENGTH) continue;
        if (line.empty() || line[0] == '#') continue;
        if (line.rfind("TITLE", 0) == 0 || line.rfind("DOMAIN", 0) == 0 || line.rfind("LUT_1D", 0) == 0) continue;

        if (line.find("LUT_3D_SIZE") != std::string::npos) {
            std::stringstream ss(line);
            std::string temp;
            ss >> temp >> lut.size;
            if (lut.size > 0 && lut.size <= 64) {
                 lut.data.reserve(lut.size * lut.size * lut.size);
            } else {
                 lut.size = 0;
                 return lut;
            }
            continue;
        }

        if (lut.data.size() >= MAX_DATA_POINTS) break;

        std::stringstream ss(line);
        float r, g, b;
        if (ss >> r >> g >> b) lut.data.push_back({r, g, b});
    }

    if (lut.size > 0 && lut.data.size() != (size_t)(lut.size * lut.size * lut.size)) {
        lut.size = 0; lut.data.clear();
    }
    return lut;
}

Vec3 apply_lut(const LUT3D& lut, Vec3 color) {
    if (lut.size == 0) return color;
    float scale = (lut.size - 1);
    float r = std::max(0.0f, std::min(1.0f, color.r)) * scale;
    float g = std::max(0.0f, std::min(1.0f, color.g)) * scale;
    float b = std::max(0.0f, std::min(1.0f, color.b)) * scale;
    int r0 = (int)r; int r1 = std::min(r0 + 1, lut.size - 1);
    int g0 = (int)g; int g1 = std::min(g0 + 1, lut.size - 1);
    int b0 = (int)b; int b1 = std::min(b0 + 1, lut.size - 1);
    float dr = r - r0; float dg = g - g0; float db = b - b0;
    auto idx = [&](int x, int y, int z) { return x + y * lut.size + z * lut.size * lut.size; };
    Vec3 c000 = lut.data[idx(r0, g0, b0)]; Vec3 c100 = lut.data[idx(r1, g0, b0)];
    Vec3 c010 = lut.data[idx(r0, g1, b0)]; Vec3 c110 = lut.data[idx(r1, g1, b0)];
    Vec3 c001 = lut.data[idx(r0, g0, b1)]; Vec3 c101 = lut.data[idx(r1, g0, b1)];
    Vec3 c011 = lut.data[idx(r0, g1, b1)]; Vec3 c111 = lut.data[idx(r1, g1, b1)];
    Vec3 c00 = { c000.r * (1-dr) + c100.r * dr, c000.g * (1-dr) + c100.g * dr, c000.b * (1-dr) + c100.b * dr };
    Vec3 c10 = { c010.r * (1-dr) + c110.r * dr, c010.g * (1-dr) + c110.g * dr, c010.b * (1-dr) + c110.b * dr };
    Vec3 c01 = { c001.r * (1-dr) + c101.r * dr, c001.g * (1-dr) + c101.g * dr, c001.b * (1-dr) + c101.b * dr };
    Vec3 c11 = { c011.r * (1-dr) + c111.r * dr, c011.g * (1-dr) + c111.g * dr, c011.b * (1-dr) + c111.b * dr };
    Vec3 c0 = { c00.r * (1-dg) + c10.r * dg, c00.g * (1-dg) + c10.g * dg, c00.b * (1-dg) + c10.b * dg };
    Vec3 c1 = { c01.r * (1-dg) + c11.r * dg, c01.g * (1-dg) + c11.g * dg, c01.b * (1-dg) + c11.b * dg };
    return { c0.r * (1-db) + c1.r * db, c0.g * (1-db) + c1.g * db, c0.b * (1-db) + c1.b * db };
}

// --- Shared Pipeline Implementation ---
void process_and_save_image(
    const std::vector<unsigned short>& inputImage,
    int width,
    int height,
    float gain,
    int targetLog,
    const LUT3D& lut,
    const char* tiffPath,
    const char* jpgPath,
    int sourceColorSpace,
    const float* ccm,
    const float* wb
) {
    // 1. Prepare Output Buffer
    std::vector<unsigned short> processedImage(width * height * 3);

    // Prepare Matrices
    Matrix3x3 sensor_to_sRGB = {0};
    Matrix3x3 effective_CCM = {0};

    if (sourceColorSpace == 1 && ccm && wb) {
        // Source is Camera Native (WB'd).
        // ccm: Sensor_Raw -> sRGB (Observed behavior)
        // wb: White Balance Gains.
        //
        // We want sRGB.
        // Input: Camera_WB.
        // Camera_WB = Camera_Raw * Scale_WB.
        // Camera_Raw = Camera_WB * Diagonal(1/Scale_WB).
        // sRGB = CCM * Camera_Raw.
        // sRGB = CCM * Diagonal(1/Scale_WB) * Camera_WB.
        //
        // So EffectiveCCM = CCM * Diagonal(1/Scale_WB).

        Matrix3x3 ccmMat;
        for(int i=0; i<9; ++i) ccmMat.m[i] = ccm[i];

        Matrix3x3 scaleMat = {
            1.0f/wb[0], 0.0f,       0.0f,
            0.0f,       1.0f/wb[1], 0.0f, // wb[1] is G0
            0.0f,       0.0f,       1.0f/wb[3]
        };

        effective_CCM = multiply(ccmMat, scaleMat);
    }

    // 2. Process Pixels
    #pragma omp parallel for
    for (int i = 0; i < width * height; i++) {
        unsigned short r_val = inputImage[i * 3 + 0];
        unsigned short g_val = inputImage[i * 3 + 1];
        unsigned short b_val = inputImage[i * 3 + 2];

        // 2a. Digital Gain & Normalization
        // Normalize to [0, 1] based on 16-bit range, then apply gain
        // NO CLAMPING here to preserve high dynamic range for Log/WideGamut
        float norm_r = (float)r_val / 65535.0f * gain;
        float norm_g = (float)g_val / 65535.0f * gain;
        float norm_b = (float)b_val / 65535.0f * gain;

        // 2b. Color Space Conversion -> XYZ (D65)
        Vec3 color = {norm_r, norm_g, norm_b};

        if (sourceColorSpace == 1) { // Camera Native (HDR+)
            // Step 1: Apply Effective CCM (Sensor_WB -> sRGB)
            if (ccm && wb) {
                color = multiply(effective_CCM, color);
            }
            // Step 2: sRGB -> XYZ (D65)
            // Since we assume the CCM outputs sRGB (not XYZ), we MUST convert to XYZ.
            color = multiply(M_sRGB_D65_to_XYZ, color);

        } else if (sourceColorSpace == 0) { // ProPhoto (LibRaw)
            // Step 1: ProPhoto D50 -> XYZ D50
            color = multiply(M_ProPhoto_D50_to_XYZ, color);
            // Step 2: Chromatic Adaptation D50 -> D65
            color = multiply(M_Bradford_D50_to_D65, color);
        }

        // 2c. XYZ (D65) -> Target Gamut (D65)
        switch (targetLog) {
            case 1: // ARRI LogC3 -> Alexa Wide Gamut
                color = multiply(M_XYZ_to_AlexaWideGamut_D65, color);
                break;
            case 2: // F-Log -> Rec.2020
            case 3:
                color = multiply(M_XYZ_to_Rec2020_D65, color);
                break;
            case 5: // S-Log3 -> S-Gamut3.Cine
            case 6:
                color = multiply(M_XYZ_to_SGamut3Cine_D65, color);
                break;
            case 7: // V-Log -> V-Gamut
                color = multiply(M_XYZ_to_VGamut_D65, color);
                break;
            case 0: // Standard -> Rec.709
            default:
                // Default to Rec.709 for standard display
                color = multiply(M_XYZ_to_Rec709_D65, color);
                break;
        }

        // 2d. Log / Gamma
        color.r = apply_log(color.r, targetLog);
        color.g = apply_log(color.g, targetLog);
        color.b = apply_log(color.b, targetLog);

        // 2e. LUT
        if (lut.size > 0) {
            color = apply_lut(lut, color);
        }

        // 3. Scale back to 16-bit
        processedImage[i * 3 + 0] = (unsigned short)std::max(0.0f, std::min(65535.0f, color.r * 65535.0f));
        processedImage[i * 3 + 1] = (unsigned short)std::max(0.0f, std::min(65535.0f, color.g * 65535.0f));
        processedImage[i * 3 + 2] = (unsigned short)std::max(0.0f, std::min(65535.0f, color.b * 65535.0f));
    }

    // 4. Save Files
    if (tiffPath) write_tiff(tiffPath, width, height, processedImage);
    if (jpgPath) write_bmp(jpgPath, width, height, processedImage);
}

// --- TIFF Writer ---

bool write_tiff(const char* filename, int width, int height, const std::vector<unsigned short>& data) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) return false;

    // Header
    char header[8] = {'I', 'I', 42, 0, 8, 0, 0, 0}; // Little endian, offset 8
    file.write(header, 8);

    // IFD
    short num_entries = 10;
    file.write((char*)&num_entries, 2);

    auto write_entry = [&](short tag, short type, int count, int value_or_offset) {
        file.write((char*)&tag, 2);
        file.write((char*)&type, 2);
        file.write((char*)&count, 4);
        file.write((char*)&value_or_offset, 4);
    };

    int data_offset = 8 + 2 + num_entries * 12 + 4; // Header + Num + Entries + NextPtr

    // 1. Width (256)
    write_entry(256, 3, 1, width); // SHORT
    // 2. Height (257)
    write_entry(257, 3, 1, height); // SHORT
    // 3. BitsPerSample (258) - R,G,B (3 values, need offset)
    int bps_offset = data_offset + width * height * 6; // Put metadata after image data
    write_entry(258, 3, 3, bps_offset);
    // 4. Compression (259) - 1 (None)
    write_entry(259, 3, 1, 1);
    // 5. Photometric (262) - 2 (RGB)
    write_entry(262, 3, 1, 2);
    // 6. StripOffsets (273) - data_offset
    write_entry(273, 4, 1, data_offset);
    // 7. SamplesPerPixel (277) - 3
    write_entry(277, 3, 1, 3);
    // 8. RowsPerStrip (278) - height
    write_entry(278, 3, 1, height);
    // 9. StripByteCounts (279) - width * height * 6
    write_entry(279, 4, 1, width * height * 6);
    // 10. PlanarConfig (284) - 1 (Chunky)
    write_entry(284, 3, 1, 1);

    int next_ifd = 0;
    file.write((char*)&next_ifd, 4);

    // Write Image Data
    file.write((char*)data.data(), data.size() * 2);

    // Write BitsPerSample array (16, 16, 16)
    short bps[3] = {16, 16, 16};
    file.write((char*)bps, 6);

    bool result = file.good();
    file.close();
    return result;
}

bool write_dng(const char* filename, int width, int height, const std::vector<unsigned short>& data, int whiteLevel, int iso, long exposureTime, float fNumber, float focalLength, long captureTimeMillis, const std::vector<float>& ccm, const std::vector<float>& wb, int orientation) {
    // Register DNG tags
    TIFFSetTagExtender(DNGTagExtender);

    TIFF* tif = TIFFOpen(filename, "w");
    if (!tif) return false;

    // Basic Tags
    TIFFSetField(tif, TIFFTAG_IMAGEWIDTH, width);
    TIFFSetField(tif, TIFFTAG_IMAGELENGTH, height);
    TIFFSetField(tif, TIFFTAG_BITSPERSAMPLE, 16);
    TIFFSetField(tif, TIFFTAG_COMPRESSION, COMPRESSION_NONE);

    // Orientation
    uint16_t tiffOrientation = 1;
    switch (orientation) {
        case 90: tiffOrientation = 6; break;
        case 180: tiffOrientation = 3; break;
        case 270: tiffOrientation = 8; break;
        default: tiffOrientation = 1; break;
    }
    TIFFSetField(tif, TIFFTAG_ORIENTATION, tiffOrientation);

    // [Critical] Use LinearRaw
    TIFFSetField(tif, TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_LINEAR_RAW);

    TIFFSetField(tif, TIFFTAG_SAMPLESPERPIXEL, 3);
    TIFFSetField(tif, TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);
    TIFFSetField(tif, TIFFTAG_ROWSPERSTRIP, height);

    // [Critical] Add NewSubfileType = 0 (Full resolution image)
    TIFFSetField(tif, TIFFTAG_SUBFILETYPE, 0);

    // Standard Metadata
    static const char* make = "Google";
    TIFFSetField(tif, TIFFTAG_MAKE, make);

    static const char* model = "HDR+ Device";
    TIFFSetField(tif, TIFFTAG_MODEL, model);

    static const char* software = "CameraXBasic HDR+";
    TIFFSetField(tif, TIFFTAG_SOFTWARE, software);

    // DateTime (306)
    time_t raw_time = (time_t)(captureTimeMillis / 1000);
    struct tm * timeinfo = localtime(&raw_time);
    char buffer[20];
    strftime(buffer, 20, "%Y:%m:%d %H:%M:%S", timeinfo);
    TIFFSetField(tif, TIFFTAG_DATETIME, buffer);

    // DNG Tags
    static const uint8_t dng_version[] = {1, 4, 0, 0};
    TIFFSetField(tif, TIFFTAG_DNGVERSION, dng_version);

    static const uint8_t dng_backward_version[] = {1, 1, 0, 0};
    TIFFSetField(tif, TIFFTAG_DNGBACKWARDVERSION, dng_backward_version);

    TIFFSetField(tif, TIFFTAG_UNIQUECAMERAMODEL, model);

    // White/Black Level (Critical for Readers)
    uint32_t white_level_val = (uint32_t)whiteLevel;
    if (white_level_val == 0) white_level_val = 65535;
    TIFFSetField(tif, TIFFTAG_WHITELEVEL, 1, &white_level_val);

    uint32_t black_level_val = 0; // Already subtracted in pipeline
    TIFFSetField(tif, TIFFTAG_BLACKLEVEL, 1, &black_level_val);

    // [Critical] Calculate ColorMatrix1 (Assuming CCM is Sensor->sRGB, not Sensor->XYZ)
    // ColorMatrix1: XYZ -> Camera Native (WB'd)
    //
    // Known:
    // sRGB = CCM * Camera_Raw
    // sRGB = XYZ_to_sRGB * XYZ
    // So: CCM * Camera_Raw = XYZ_to_sRGB * XYZ
    // Camera_Raw = Inv(CCM) * XYZ_to_sRGB * XYZ
    //
    // Camera_WB = Diagonal(WB) * Camera_Raw
    // Camera_WB = Diagonal(WB) * Inv(CCM) * XYZ_to_sRGB * XYZ
    //
    // So ColorMatrix1 = Diagonal(WB) * Inv(CCM) * XYZ_to_sRGB.

    Matrix3x3 ccmMat;
    for(int i=0; i<9; ++i) ccmMat.m[i] = ccm[i];

    Matrix3x3 invCcm = invert(ccmMat);

    Matrix3x3 scaleMat = {
        wb[0], 0.0f, 0.0f,
        0.0f, wb[1], 0.0f,
        0.0f, 0.0f, wb[3]
    };

    Matrix3x3 colorMatrix1 = multiply(scaleMat, invCcm);
    colorMatrix1 = multiply(colorMatrix1, M_XYZ_to_sRGB_D65);

    TIFFSetField(tif, TIFFTAG_COLORMATRIX1, 9, colorMatrix1.m);

    // AsShotNeutral is {1,1,1} because data is already WB'd
    static const float as_shot_neutral[] = {1.0f, 1.0f, 1.0f};
    TIFFSetField(tif, TIFFTAG_ASSHOTNEUTRAL, 3, as_shot_neutral);

    // Calibration Illuminant 1 = D65 (21). Standard sRGB D65.
    TIFFSetField(tif, TIFFTAG_CALIBRATIONILLUMINANT1, 21);

    // EXIF Metadata - Use correct standard libtiff types/pointers
    float exposureTimeSec = (float)exposureTime / 1000000000.0f;
    TIFFSetField(tif, TIFFTAG_EXPOSURETIME, exposureTimeSec);
    TIFFSetField(tif, TIFFTAG_FNUMBER, fNumber);
    TIFFSetField(tif, TIFFTAG_FOCALLENGTH, focalLength);

    unsigned short iso_short = (unsigned short)iso;
    TIFFSetField(tif, TIFFTAG_ISOSPEEDRATINGS, 1, &iso_short);

    // Write Data
    if (TIFFWriteEncodedStrip(tif, 0, (void*)data.data(), width * height * 3 * sizeof(unsigned short)) < 0) {
        TIFFClose(tif);
        return false;
    }

    TIFFClose(tif);
    return true;
}

bool write_bmp(const char* filename, int width, int height, const std::vector<unsigned short>& data) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) return false;

    int padded_width = (width * 3 + 3) & (~3);
    int size = 54 + padded_width * height;

    // Header
    unsigned char header[54] = {0};
    header[0] = 'B'; header[1] = 'M';
    *(int*)&header[2] = size;
    *(int*)&header[10] = 54;
    *(int*)&header[14] = 40;
    *(int*)&header[18] = width;
    *(int*)&header[22] = height;
    *(short*)&header[26] = 1;
    *(short*)&header[28] = 24; // 8-bit per channel

    file.write((char*)header, 54);

    // Data (Bottom-up)
    std::vector<unsigned char> line(padded_width, 0);
    for (int y = height - 1; y >= 0; y--) {
        for (int x = 0; x < width; x++) {
            int idx = (y * width + x) * 3;
            // BMP is BGR
            line[x*3+0] = (unsigned char)(data[idx+2] >> 8);
            line[x*3+1] = (unsigned char)(data[idx+1] >> 8);
            line[x*3+2] = (unsigned char)(data[idx+0] >> 8);
        }
        file.write((char*)line.data(), padded_width);
    }

    bool result = file.good();
    file.close();
    return result;
}

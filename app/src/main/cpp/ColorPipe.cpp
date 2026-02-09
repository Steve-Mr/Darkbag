#include "ColorPipe.h"
#include "ColorMatrices.h"
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

// 确保定义 LinearRaw (如果 libtiff 头文件没包含)
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
    if (x < 0) x = 0;
    switch (type) {
        case 1: return arri_logc3(x);
        case 2: return f_log(x);
        case 3: return f_log(x);
        case 5: return s_log3(x);
        case 6: return s_log3(x);
        case 7: return vlog(x);
        case 0: return x;
        default: return pow(x, 1.0f/2.2f);
    }
}

// --- LUT (CPU) ---
LUT3D load_lut(const char* path) {
    LUT3D lut;
    lut.size = 0;
    std::ifstream file(path);
    if (!file.is_open()) return lut;

    // Security limits to prevent DoS
    const size_t MAX_LINE_LENGTH = 1024;
    const size_t MAX_DATA_POINTS = 64 * 64 * 64; // Limit to standard 64^3 LUT size (approx 262k entries)

    std::string line;
    while (std::getline(file, line)) {
        if (line.length() > MAX_LINE_LENGTH) continue; // Skip overly long lines
        if (line.empty() || line[0] == '#') continue;
        if (line.rfind("TITLE", 0) == 0 || line.rfind("DOMAIN", 0) == 0 || line.rfind("LUT_1D", 0) == 0) continue;

        if (line.find("LUT_3D_SIZE") != std::string::npos) {
            std::stringstream ss(line);
            std::string temp;
            ss >> temp >> lut.size;
            // Enforce size limits
            if (lut.size > 0 && lut.size <= 64) {
                 lut.data.reserve(lut.size * lut.size * lut.size);
            } else {
                 lut.size = 0; // Invalid or too large
                 return lut;
            }
            continue;
        }

        if (lut.data.size() >= MAX_DATA_POINTS) break; // Hard stop

        std::stringstream ss(line);
        float r, g, b;
        if (ss >> r >> g >> b) lut.data.push_back({r, g, b});
    }

    // Strict validation of data count
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
    const char* jpgPath
) {
    // 1. Prepare Output Buffer
    std::vector<unsigned short> processedImage(width * height * 3);

    // Get Conversion Matrix
    const float* targetMatrix = get_prophoto_to_target_matrix(targetLog);

    // 2. Process Pixels (Digital Gain -> Color Space Conversion -> Log/Gamma -> LUT)
    #pragma omp parallel for
    for (int i = 0; i < width * height; i++) {
        unsigned short r_val = inputImage[i * 3 + 0];
        unsigned short g_val = inputImage[i * 3 + 1];
        unsigned short b_val = inputImage[i * 3 + 2];

        // 2a. Digital Gain & Normalization
        // Normalize to [0, 1] based on 16-bit range, then apply gain
        // Clamp to 0.0f, but allow > 1.0f for Log curves (HDR data)
        float norm_r = std::max(0.0f, (float)r_val / 65535.0f * gain);
        float norm_g = std::max(0.0f, (float)g_val / 65535.0f * gain);
        float norm_b = std::max(0.0f, (float)b_val / 65535.0f * gain);

        // 2b. Color Space Conversion (ProPhoto -> Target)
        float in[3] = {norm_r, norm_g, norm_b};
        float out[3];
        mat_vec_mult(targetMatrix, in, out);
        norm_r = out[0];
        norm_g = out[1];
        norm_b = out[2];

        // 2c. Log / Gamma
        if (targetLog == 0) {
            // Standard Gamma 2.2 (Fallback if "None" selected)
            norm_r = pow(norm_r, 1.0f/2.2f);
            norm_g = pow(norm_g, 1.0f/2.2f);
            norm_b = pow(norm_b, 1.0f/2.2f);
        } else {
            // Specific Log Curve
            norm_r = apply_log(norm_r, targetLog);
            norm_g = apply_log(norm_g, targetLog);
            norm_b = apply_log(norm_b, targetLog);
        }

        // 2c. LUT
        if (lut.size > 0) {
            Vec3 color = {norm_r, norm_g, norm_b};
            color = apply_lut(lut, color);
            norm_r = color.r;
            norm_g = color.g;
            norm_b = color.b;
        }

        // 3. Scale back to 16-bit
        processedImage[i * 3 + 0] = (unsigned short)std::max(0.0f, std::min(65535.0f, norm_r * 65535.0f));
        processedImage[i * 3 + 1] = (unsigned short)std::max(0.0f, std::min(65535.0f, norm_g * 65535.0f));
        processedImage[i * 3 + 2] = (unsigned short)std::max(0.0f, std::min(65535.0f, norm_b * 65535.0f));
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

bool write_dng(const char* filename, int width, int height, const std::vector<unsigned short>& data, int whiteLevel, int iso, long exposureTime, float fNumber, float focalLength, long captureTimeMillis, const std::vector<float>& ccm, int orientation) {
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
    // The pipeline scales values by 0.25x to preserve headroom.
    // We must set the WhiteLevel tag to match this scaled range (approx 16383 for 16-bit).
    // If passed whiteLevel is full range (e.g. 1023 or 65535), we divide by 4.
    // However, the caller (JNI) might already be passing the scaled value.
    // Let's assume the caller handles the scaling logic and passes the correct "effective white level" for the DNG.
    uint32_t white_level_val = (uint32_t)whiteLevel;
    if (white_level_val == 0) white_level_val = 65535;
    TIFFSetField(tif, TIFFTAG_WHITELEVEL, 1, &white_level_val);

    uint32_t black_level_val = 0; // Already subtracted in pipeline
    TIFFSetField(tif, TIFFTAG_BLACKLEVEL, 1, &black_level_val);

    // [Critical] Write real CCM
    TIFFSetField(tif, TIFFTAG_COLORMATRIX1, 9, ccm.data());

    // AsShotNeutral is {1,1,1} because data is already WB'd (Linear)
    static const float as_shot_neutral[] = {1.0f, 1.0f, 1.0f};
    TIFFSetField(tif, TIFFTAG_ASSHOTNEUTRAL, 3, as_shot_neutral);

    TIFFSetField(tif, TIFFTAG_CALIBRATIONILLUMINANT1, 21); // D65

    // EXIF Metadata - Use correct standard libtiff types/pointers
    float exposureTimeSec = (float)exposureTime / 1000000000.0f;
    TIFFSetField(tif, TIFFTAG_EXPOSURETIME, exposureTimeSec);
    TIFFSetField(tif, TIFFTAG_FNUMBER, fNumber);
    TIFFSetField(tif, TIFFTAG_FOCALLENGTH, focalLength);

    // ISO: Pass count and address because standard libtiff definition is variable length array
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

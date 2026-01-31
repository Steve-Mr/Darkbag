#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <iostream>
#include <memory>
#include <EGL/egl.h>
#include <GLES3/gl31.h>

#define TAG "ColorProcessorNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Constants
const float PROPHOTO_RGB_D50[9] = {
    1.3459433f, -0.2556075f, -0.0511118f,
    -0.5445989f, 1.5081673f, 0.0205351f,
    0.0f, 0.0f, 1.2118128f
};

struct Vec3 {
    float r, g, b;
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
struct LUT3D {
    int size;
    std::vector<Vec3> data;
};

LUT3D load_lut(const char* path) {
    LUT3D lut;
    lut.size = 0;
    std::ifstream file(path);
    if (!file.is_open()) return lut;

    std::string line;
    while (std::getline(file, line)) {
        if (line.empty() || line[0] == '#') continue;
        if (line.rfind("TITLE", 0) == 0 || line.rfind("DOMAIN", 0) == 0 || line.rfind("LUT_1D", 0) == 0) continue;
        if (line.find("LUT_3D_SIZE") != std::string::npos) {
            std::stringstream ss(line);
            std::string temp;
            ss >> temp >> lut.size;
            if (lut.size > 0 && lut.size <= 256) lut.data.reserve(lut.size * lut.size * lut.size);
            else lut.size = 0;
            continue;
        }
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

// --- DNG Patcher ---
// Simplified TIFF parser to find the IFD and append/update 50719/50720 tags
#define TAG_DEFAULT_CROP_ORIGIN 50719
#define TAG_DEFAULT_CROP_SIZE 50720

struct TiffEntry {
    short tag;
    short type;
    int count;
    int value_or_offset;
};

// Helper to read integer types
int read_int(const std::vector<unsigned char>& buf, int offset) {
    return buf[offset] | (buf[offset+1] << 8) | (buf[offset+2] << 16) | (buf[offset+3] << 24);
}
short read_short(const std::vector<unsigned char>& buf, int offset) {
    return buf[offset] | (buf[offset+1] << 8);
}

void patch_dng(const char* path, int cropX, int cropY, int cropW, int cropH) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) { LOGE("Failed to open DNG for patching"); return; }

    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);

    std::vector<unsigned char> data(size);
    if (!file.read((char*)data.data(), size)) { LOGE("Failed to read DNG"); return; }
    file.close();

    // Check Header (Little Endian 'II')
    if (data[0] != 'I' || data[1] != 'I') { LOGE("DNG not Little Endian, patcher supports LE only"); return; }

    int ifd_offset = read_int(data, 4);
    if (ifd_offset >= size) { LOGE("Invalid IFD offset"); return; }

    short num_entries = read_short(data, ifd_offset);

    std::vector<TiffEntry> entries;
    int current_pos = ifd_offset + 2;

    bool hasOrigin = false;
    bool hasSize = false;

    // Read existing entries
    for (int i=0; i<num_entries; i++) {
        TiffEntry entry;
        entry.tag = read_short(data, current_pos);
        entry.type = read_short(data, current_pos + 2);
        entry.count = read_int(data, current_pos + 4);
        entry.value_or_offset = read_int(data, current_pos + 8);

        // Filter out existing crop tags if any (we will replace them)
        if (entry.tag == TAG_DEFAULT_CROP_ORIGIN) { hasOrigin = true; continue; }
        if (entry.tag == TAG_DEFAULT_CROP_SIZE) { hasSize = true; continue; }

        entries.push_back(entry);
        current_pos += 12;
    }

    int next_ifd_link = read_int(data, current_pos);

    // Append New Data to End of Buffer
    int new_data_offset = data.size();

    int origin[2] = {cropX, cropY};
    int dim[2] = {cropW, cropH};

    // Append Origin Data
    int origin_offset = data.size();
    unsigned char* p = (unsigned char*)origin;
    for(int i=0; i<8; i++) data.push_back(p[i]);

    // Append Size Data
    int size_offset = data.size();
    p = (unsigned char*)dim;
    for(int i=0; i<8; i++) data.push_back(p[i]);

    // Create New Entries
    TiffEntry originEntry = {TAG_DEFAULT_CROP_ORIGIN, 4, 2, origin_offset}; // LONG (4)
    TiffEntry sizeEntry = {TAG_DEFAULT_CROP_SIZE, 4, 2, size_offset}; // LONG (4)

    entries.push_back(originEntry);
    entries.push_back(sizeEntry);

    // Sort entries
    std::sort(entries.begin(), entries.end(), [](const TiffEntry& a, const TiffEntry& b) {
        return (unsigned short)a.tag < (unsigned short)b.tag;
    });

    // Write New IFD at end
    int new_ifd_offset = data.size();

    short new_count = entries.size();
    data.push_back(new_count & 0xFF);
    data.push_back((new_count >> 8) & 0xFF);

    for (const auto& entry : entries) {
        data.push_back(entry.tag & 0xFF); data.push_back((entry.tag >> 8) & 0xFF);
        data.push_back(entry.type & 0xFF); data.push_back((entry.type >> 8) & 0xFF);
        data.push_back(entry.count & 0xFF); data.push_back((entry.count >> 8) & 0xFF); data.push_back((entry.count >> 16) & 0xFF); data.push_back((entry.count >> 24) & 0xFF);
        data.push_back(entry.value_or_offset & 0xFF); data.push_back((entry.value_or_offset >> 8) & 0xFF); data.push_back((entry.value_or_offset >> 16) & 0xFF); data.push_back((entry.value_or_offset >> 24) & 0xFF);
    }

    // Next IFD Link
    data.push_back(next_ifd_link & 0xFF);
    data.push_back((next_ifd_link >> 8) & 0xFF);
    data.push_back((next_ifd_link >> 16) & 0xFF);
    data.push_back((next_ifd_link >> 24) & 0xFF);

    // Update Header
    data[4] = new_ifd_offset & 0xFF;
    data[5] = (new_ifd_offset >> 8) & 0xFF;
    data[6] = (new_ifd_offset >> 16) & 0xFF;
    data[7] = (new_ifd_offset >> 24) & 0xFF;

    // Write back
    std::ofstream outfile(path, std::ios::binary | std::ios::trunc);
    outfile.write((char*)data.data(), data.size());
    outfile.close();

    LOGD("Patched DNG metadata at %s", path);
}


// --- TIFF Writer ---

void write_tiff(const char* filename, int width, int height, const std::vector<unsigned short>& data, int cropX, int cropY, int cropW, int cropH) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) return;

    // Header
    char header[8] = {'I', 'I', 42, 0, 8, 0, 0, 0}; // Little endian, offset 8
    file.write(header, 8);

    // IFD
    bool hasCrop = (cropW > 0 && cropH > 0);
    short num_entries = 10 + (hasCrop ? 2 : 0);
    file.write((char*)&num_entries, 2);

    auto write_entry = [&](short tag, short type, int count, int value_or_offset) {
        file.write((char*)&tag, 2);
        file.write((char*)&type, 2);
        file.write((char*)&count, 4);
        file.write((char*)&value_or_offset, 4);
    };

    int data_offset = 8 + 2 + num_entries * 12 + 4; // Header + Num + Entries + NextPtr
    int image_data_size = width * height * 6; // 3 channels * 2 bytes
    int bps_offset = data_offset + image_data_size;
    int crop_origin_offset = bps_offset + 6;
    int crop_size_offset = crop_origin_offset + 8;

    // 1. Width (256)
    write_entry(256, 3, 1, width); // SHORT
    // 2. Height (257)
    write_entry(257, 3, 1, height); // SHORT
    // 3. BitsPerSample (258) - R,G,B (3 values, need offset)
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
    write_entry(279, 4, 1, image_data_size);
    // 10. PlanarConfig (284) - 1 (Chunky)
    write_entry(284, 3, 1, 1);

    if (hasCrop) {
        // 11. DefaultCropOrigin (50719) - LONG (4), Count 2
        write_entry(50719, 4, 2, crop_origin_offset);
        // 12. DefaultCropSize (50720) - LONG (4), Count 2
        write_entry(50720, 4, 2, crop_size_offset);
    }

    int next_ifd = 0;
    file.write((char*)&next_ifd, 4);

    // Write Image Data
    file.write((char*)data.data(), data.size() * 2);

    // Write BitsPerSample array (16, 16, 16)
    short bps[3] = {16, 16, 16};
    file.write((char*)bps, 6);

    if (hasCrop) {
        int origin[2] = {cropX, cropY};
        int size[2] = {cropW, cropH};
        file.write((char*)origin, 8);
        file.write((char*)size, 8);
    }

    file.close();
}

void write_bmp(const char* filename, int width, int height, const std::vector<unsigned short>& data) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) return;

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
    file.close();
}

// --- Common ---
void calculateCombinedMatrix(const float* ccm, const float* wb, float* combinedMat) {
    auto matMul = [](const float* a, const float* b, float* res) {
        for (int i=0; i<3; ++i) {
            for (int j=0; j<3; ++j) {
                res[i*3+j] = 0;
                for (int k=0; k<3; ++k) res[i*3+j] += a[i*3+k] * b[k*3+j];
            }
        }
    };
    float xyzToPro[9];
    std::copy(std::begin(PROPHOTO_RGB_D50), std::end(PROPHOTO_RGB_D50), std::begin(xyzToPro));
    matMul(xyzToPro, ccm, combinedMat);
}

// --- CPU Processing ---
bool processCpu(
    uint16_t* rawData, int rawWidth, int rawHeight, int stride,
    int whiteLevel, int blackLevel, int cfaPattern,
    float* wb, float* combinedMat, int targetLog, const LUT3D& lut,
    std::vector<unsigned short>& outputImage,
    int cropX, int cropY, int cropW, int cropH, bool physicallyCrop
) {
    int outW = physicallyCrop ? cropW : rawWidth;
    int outH = physicallyCrop ? cropH : rawHeight;
    int offX = physicallyCrop ? cropX : 0;
    int offY = physicallyCrop ? cropY : 0;

    // Bayer pattern offsets for the RAW image (0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR)
    int r_x, r_y, b_x, b_y;
    if (cfaPattern == 0) { r_x=0; r_y=0; b_x=1; b_y=1; }
    else if (cfaPattern == 1) { r_x=1; r_y=0; b_x=0; b_y=1; }
    else if (cfaPattern == 2) { r_x=0; r_y=1; b_x=1; b_y=0; }
    else { r_x=1; r_y=1; b_x=0; b_y=0; }

    int stride_pixels = stride / 2;

    #pragma omp parallel for
    for (int y = 0; y < outH; y++) {
        for (int x = 0; x < outW; x++) {
            // Coordinate in RAW image
            int inX = x + offX;
            int inY = y + offY;

            // Skip edges of RAW image (demosaic needs neighbors)
            if (inX < 1 || inX >= rawWidth - 1 || inY < 1 || inY >= rawHeight - 1) continue;

            float r, g, b;
            bool is_r = ((inX & 1) == r_x) && ((inY & 1) == r_y);
            bool is_b = ((inX & 1) == b_x) && ((inY & 1) == b_y);
            bool is_g = !is_r && !is_b;

            float val = (float)rawData[inY * stride_pixels + inX];

            if (is_g) {
                g = val;
                float r_sum = 0, b_sum = 0; int r_cnt = 0, b_cnt = 0;
                // Neighbors relative to inX, inY
                float v;
                // Left
                v = rawData[inY * stride_pixels + (inX-1)];
                if (((inX-1)&1)==r_x) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; }
                // Right
                v = rawData[inY * stride_pixels + (inX+1)];
                if (((inX+1)&1)==r_x) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; }
                // Top
                v = rawData[(inY-1) * stride_pixels + inX];
                if (((inY-1)&1)==r_y) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; }
                // Bottom
                v = rawData[(inY+1) * stride_pixels + inX];
                if (((inY+1)&1)==r_y) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; }

                r = (r_cnt > 0) ? r_sum / r_cnt : 0;
                b = (b_cnt > 0) ? b_sum / b_cnt : 0;
            } else if (is_r) {
                r = val;
                float g_sum = 0, b_sum = 0; int g_cnt = 0, b_cnt = 0;
                // Cross (G)
                g_sum += rawData[inY*stride_pixels+inX-1]; g_cnt++;
                g_sum += rawData[inY*stride_pixels+inX+1]; g_cnt++;
                g_sum += rawData[(inY-1)*stride_pixels+inX]; g_cnt++;
                g_sum += rawData[(inY+1)*stride_pixels+inX]; g_cnt++;
                // Diag (B)
                b_sum += rawData[(inY-1)*stride_pixels+inX-1]; b_cnt++;
                b_sum += rawData[(inY-1)*stride_pixels+inX+1]; b_cnt++;
                b_sum += rawData[(inY+1)*stride_pixels+inX-1]; b_cnt++;
                b_sum += rawData[(inY+1)*stride_pixels+inX+1]; b_cnt++;

                g = (g_cnt>0) ? g_sum/g_cnt : 0;
                b = (b_cnt>0) ? b_sum/b_cnt : 0;
            } else { // is_b
                b = val;
                float g_sum = 0, r_sum = 0; int g_cnt = 0, r_cnt = 0;
                 // Cross (G)
                g_sum += rawData[inY*stride_pixels+inX-1]; g_cnt++;
                g_sum += rawData[inY*stride_pixels+inX+1]; g_cnt++;
                g_sum += rawData[(inY-1)*stride_pixels+inX]; g_cnt++;
                g_sum += rawData[(inY+1)*stride_pixels+inX]; g_cnt++;
                // Diag (R)
                r_sum += rawData[(inY-1)*stride_pixels+inX-1]; r_cnt++;
                r_sum += rawData[(inY-1)*stride_pixels+inX+1]; r_cnt++;
                r_sum += rawData[(inY+1)*stride_pixels+inX-1]; r_cnt++;
                r_sum += rawData[(inY+1)*stride_pixels+inX+1]; r_cnt++;

                g = (g_cnt>0) ? g_sum/g_cnt : 0;
                r = (r_cnt>0) ? r_sum/r_cnt : 0;
            }

            float range = (float)(whiteLevel - blackLevel);
            r = std::max(0.0f, (r - blackLevel) / range);
            g = std::max(0.0f, (g - blackLevel) / range);
            b = std::max(0.0f, (b - blackLevel) / range);

            float g_gain = (wb[1] + wb[2]) / 2.0f;
            r *= wb[0]; g *= g_gain; b *= wb[3];

            float X = combinedMat[0]*r + combinedMat[1]*g + combinedMat[2]*b;
            float Y = combinedMat[3]*r + combinedMat[4]*g + combinedMat[5]*b;
            float Z = combinedMat[6]*r + combinedMat[7]*g + combinedMat[8]*b;

            X = apply_log(X, targetLog);
            Y = apply_log(Y, targetLog);
            Z = apply_log(Z, targetLog);

            Vec3 res = {X, Y, Z};
            if (lut.size > 0) res = apply_lut(lut, res);

            outputImage[(y * outW + x) * 3 + 0] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.r * 65535.0f));
            outputImage[(y * outW + x) * 3 + 1] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.g * 65535.0f));
            outputImage[(y * outW + x) * 3 + 2] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.b * 65535.0f));
        }
    }
    return true;
}

// --- GPU Processing ---

const char* COMPUTE_SHADER_SRC = R"(#version 310 es
layout(local_size_x = 16, local_size_y = 16) in;

uniform mediump usampler2D uInput;
layout(rgba16ui, binding = 1) writeonly uniform mediump uimage2D uOutput;
uniform mediump sampler3D uLut;

uniform int uInputWidth;
uniform int uInputHeight;
uniform int uOutputWidth;
uniform int uOutputHeight;
uniform ivec2 uCropOrigin;

uniform float uBlackLevel;
uniform float uWhiteLevel;
uniform int uCfaPattern;
uniform vec4 uWbGains; // R, G_even, G_odd, B
uniform mat3 uCombinedMat;
uniform int uTargetLog;
uniform int uLutSize; // 0 if none

// Log Functions (simplified for GLSL)
float arri_logc3(float x) {
    if (x > 0.010591) return 0.247190 * log(5.555556 * x + 0.052272) / log(10.0) + 0.385537;
    return 5.367655 * x + 0.092809;
}
float s_log3(float x) {
    if (x >= 0.01125) return (420.0 + log((x + 0.01) / 0.19) / log(10.0) * 261.5) / 1023.0;
    return (x * 171.21029 + 95.0) / 1023.0;
}
float f_log(float x) {
    if (x >= 0.00089) return 0.344676 * log(0.555556 * x + 0.009468) / log(10.0) + 0.790453;
    return 8.52 * x + 0.0929;
}
float vlog(float x) {
    if (x >= 0.01) return 0.241514 * log(x + 0.008730) / log(10.0) + 0.598206;
    return 5.6 * x + 0.125;
}

float apply_log(float x, int type) {
    if (x < 0.0) x = 0.0;
    if (type == 1) return arri_logc3(x);
    if (type == 2 || type == 3) return f_log(x);
    if (type == 5 || type == 6) return s_log3(x);
    if (type == 7) return vlog(x);
    if (type == 0) return x;
    return pow(x, 1.0/2.2);
}

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= uOutputWidth || pos.y >= uOutputHeight) return;

    // Map to input coordinates
    ivec2 inPos = pos + uCropOrigin;
    int x = inPos.x;
    int y = inPos.y;

    // Determine CFA (0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR)
    int r_x, r_y, b_x, b_y;
    if (uCfaPattern == 0) { r_x=0; r_y=0; b_x=1; b_y=1; }
    else if (uCfaPattern == 1) { r_x=1; r_y=0; b_x=0; b_y=1; }
    else if (uCfaPattern == 2) { r_x=0; r_y=1; b_x=1; b_y=0; }
    else { r_x=1; r_y=1; b_x=0; b_y=0; }

    bool is_r = ((x & 1) == r_x) && ((y & 1) == r_y);
    bool is_b = ((x & 1) == b_x) && ((y & 1) == b_y);
    bool is_g = !is_r && !is_b;

    // Boundary Check
    if (x < 1 || x >= uInputWidth - 1 || y < 1 || y >= uInputHeight - 1) return;

    float val = float(texelFetch(uInput, inPos, 0).r);
    float r = 0.0, g = 0.0, b = 0.0;

    if (is_g) {
        g = val;
        // R neighbors
        float r_sum = 0.0; int r_cnt = 0;
        float b_sum = 0.0; int b_cnt = 0;
        // Neighbors: (x-1, y), (x+1, y), (x, y-1), (x, y+1)
        ivec2 coords[4];
        coords[0] = ivec2(x-1, y); coords[1] = ivec2(x+1, y);
        coords[2] = ivec2(x, y-1); coords[3] = ivec2(x, y+1);

        for (int i=0; i<4; i++) {
            float v = float(texelFetch(uInput, coords[i], 0).r);
            bool n_is_r = ((coords[i].x & 1) == r_x) && ((coords[i].y & 1) == r_y);
            if (n_is_r) { r_sum += v; r_cnt++; } else { b_sum += v; b_cnt++; }
        }
        r = (r_cnt > 0) ? r_sum / float(r_cnt) : 0.0;
        b = (b_cnt > 0) ? b_sum / float(b_cnt) : 0.0;
    }
    else if (is_r) {
        r = val;
        float g_sum = 0.0; int g_cnt = 0;
        float b_sum = 0.0; int b_cnt = 0;

        // G neighbors (cross)
        ivec2 c_cross[4];
        c_cross[0] = ivec2(x-1, y); c_cross[1] = ivec2(x+1, y);
        c_cross[2] = ivec2(x, y-1); c_cross[3] = ivec2(x, y+1);
        for(int i=0; i<4; i++) {
             g_sum += float(texelFetch(uInput, c_cross[i], 0).r); g_cnt++;
        }

        // B neighbors (diag)
        ivec2 c_diag[4];
        c_diag[0] = ivec2(x-1, y-1); c_diag[1] = ivec2(x+1, y-1);
        c_diag[2] = ivec2(x-1, y+1); c_diag[3] = ivec2(x+1, y+1);
        for(int i=0; i<4; i++) {
             b_sum += float(texelFetch(uInput, c_diag[i], 0).r); b_cnt++;
        }
        g = (g_cnt > 0) ? g_sum / float(g_cnt) : 0.0;
        b = (b_cnt > 0) ? b_sum / float(b_cnt) : 0.0;
    }
    else { // is_b
        b = val;
        float g_sum = 0.0; int g_cnt = 0;
        float r_sum = 0.0; int r_cnt = 0;

        // G neighbors (cross)
        ivec2 c_cross[4];
        c_cross[0] = ivec2(x-1, y); c_cross[1] = ivec2(x+1, y);
        c_cross[2] = ivec2(x, y-1); c_cross[3] = ivec2(x, y+1);
        for(int i=0; i<4; i++) {
             g_sum += float(texelFetch(uInput, c_cross[i], 0).r); g_cnt++;
        }
        // R neighbors (diag)
        ivec2 c_diag[4];
        c_diag[0] = ivec2(x-1, y-1); c_diag[1] = ivec2(x+1, y-1);
        c_diag[2] = ivec2(x-1, y+1); c_diag[3] = ivec2(x+1, y+1);
        for(int i=0; i<4; i++) {
             r_sum += float(texelFetch(uInput, c_diag[i], 0).r); r_cnt++;
        }
        g = (g_cnt > 0) ? g_sum / float(g_cnt) : 0.0;
        r = (r_cnt > 0) ? r_sum / float(r_cnt) : 0.0;
    }

    // Process
    float range = uWhiteLevel - uBlackLevel;
    r = max(0.0, (r - uBlackLevel) / range);
    g = max(0.0, (g - uBlackLevel) / range);
    b = max(0.0, (b - uBlackLevel) / range);

    float g_gain = (uWbGains.y + uWbGains.z) * 0.5;
    r *= uWbGains.x;
    g *= g_gain;
    b *= uWbGains.w;

    vec3 res = uCombinedMat * vec3(r, g, b);

    res.x = apply_log(res.x, uTargetLog);
    res.y = apply_log(res.y, uTargetLog);
    res.z = apply_log(res.z, uTargetLog);

    if (uLutSize > 0) {
        res = texture(uLut, res).rgb;
    }

    // Output uses 'pos'
    uvec4 outVal;
    outVal.r = uint(clamp(res.r * 65535.0, 0.0, 65535.0));
    outVal.g = uint(clamp(res.g * 65535.0, 0.0, 65535.0));
    outVal.b = uint(clamp(res.b * 65535.0, 0.0, 65535.0));
    outVal.a = 65535u;

    imageStore(uOutput, pos, outVal);
}
)";

GLuint createShader(GLenum type, const char* src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint len;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
        if (len > 0) {
            std::vector<char> log(len);
            glGetShaderInfoLog(shader, len, nullptr, log.data());
            LOGE("Shader compile error: %s", log.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool processGpu(
    uint16_t* rawData, int rawWidth, int rawHeight, int stride,
    int whiteLevel, int blackLevel, int cfaPattern,
    float* wb, float* combinedMat, int targetLog, const LUT3D& lut,
    std::vector<unsigned short>& outputImage,
    int cropX, int cropY, int cropW, int cropH, bool physicallyCrop
) {
    int outW = physicallyCrop ? cropW : rawWidth;
    int outH = physicallyCrop ? cropH : rawHeight;
    int offX = physicallyCrop ? cropX : 0;
    int offY = physicallyCrop ? cropY : 0;

    LOGD("Initializing GPU processing...");

    // 1. EGL Setup
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) { LOGE("EGL no display"); return false; }
    EGLint major, minor;
    if (!eglInitialize(display, &major, &minor)) { LOGE("EGL init failed"); return false; }

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(display, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        LOGE("EGL choose config failed"); return false;
    }

    const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
    if (context == EGL_NO_CONTEXT) { LOGE("EGL create context failed"); return false; }

    const EGLint pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbufferAttribs);
    if (surface == EGL_NO_SURFACE) { LOGE("EGL create surface failed"); eglDestroyContext(display, context); return false; }

    if (!eglMakeCurrent(display, surface, surface, context)) {
        LOGE("EGL make current failed");
        eglDestroySurface(display, surface); eglDestroyContext(display, context); return false;
    }

    // 2. GL Objects
    GLuint program = glCreateProgram();
    GLuint cs = createShader(GL_COMPUTE_SHADER, COMPUTE_SHADER_SRC);
    if (!cs) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display, surface); eglDestroyContext(display, context);
        return false;
    }
    glAttachShader(program, cs);
    glLinkProgram(program);
    GLint linked;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Program link failed");
        glDeleteShader(cs); glDeleteProgram(program);
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display, surface); eglDestroyContext(display, context);
        return false;
    }

    glUseProgram(program);

    // 3. Textures
    GLuint texInput, texOutput, texLut = 0;

    // Input (R16UI) - Upload FULL Raw
    glGenTextures(1, &texInput);
    glBindTexture(GL_TEXTURE_2D, texInput);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16UI, rawWidth, rawHeight);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, stride / 2);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, rawWidth, rawHeight, GL_RED_INTEGER, GL_UNSIGNED_SHORT, rawData);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    // Output (RGBA16UI) - Size is outW x outH
    glGenTextures(1, &texOutput);
    glBindTexture(GL_TEXTURE_2D, texOutput);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16UI, outW, outH);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    // LUT (3D)
    if (lut.size > 0) {
        glGenTextures(1, &texLut);
        glBindTexture(GL_TEXTURE_3D, texLut);
        glTexStorage3D(GL_TEXTURE_3D, 1, GL_RGB16F, lut.size, lut.size, lut.size);
        glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, lut.size, lut.size, lut.size, GL_RGB, GL_FLOAT, lut.data.data());
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }

    // 4. Uniforms
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texInput);
    glUniform1i(glGetUniformLocation(program, "uInput"), 0);

    glBindImageTexture(1, texOutput, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16UI);
    glUniform1i(glGetUniformLocation(program, "uOutput"), 1);

    if (texLut) {
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_3D, texLut);
        glUniform1i(glGetUniformLocation(program, "uLut"), 2);
    }

    glUniform1i(glGetUniformLocation(program, "uInputWidth"), rawWidth);
    glUniform1i(glGetUniformLocation(program, "uInputHeight"), rawHeight);
    glUniform1i(glGetUniformLocation(program, "uOutputWidth"), outW);
    glUniform1i(glGetUniformLocation(program, "uOutputHeight"), outH);
    glUniform2i(glGetUniformLocation(program, "uCropOrigin"), offX, offY);

    glUniform1f(glGetUniformLocation(program, "uWhiteLevel"), (float)whiteLevel);
    glUniform1f(glGetUniformLocation(program, "uBlackLevel"), (float)blackLevel);
    glUniform1i(glGetUniformLocation(program, "uCfaPattern"), cfaPattern);
    glUniform4f(glGetUniformLocation(program, "uWbGains"), wb[0], wb[1], wb[2], wb[3]);
    glUniformMatrix3fv(glGetUniformLocation(program, "uCombinedMat"), 1, GL_TRUE, combinedMat);
    glUniform1i(glGetUniformLocation(program, "uTargetLog"), targetLog);
    glUniform1i(glGetUniformLocation(program, "uLutSize"), lut.size);

    // 5. Dispatch - use outW, outH
    glDispatchCompute((outW + 15) / 16, (outH + 15) / 16, 1);
    glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);

    // 6. Readback
    GLuint fbo;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texOutput, 0);

    if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("FBO incomplete");
        // Cleanup...
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &texInput);
        glDeleteTextures(1, &texOutput);
        if (texLut) glDeleteTextures(1, &texLut);
        glDeleteProgram(program);
        glDeleteShader(cs);
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display, surface);
        eglDestroyContext(display, context);
        return false;
    }

    // Read pixels (RGBA) - Size outW x outH
    std::vector<unsigned short> tempBuffer(outW * outH * 4);
    glReadPixels(0, 0, outW, outH, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, tempBuffer.data());

    // Compact RGBA -> RGB
    #pragma omp parallel for
    for (int i = 0; i < outW * outH; i++) {
        outputImage[i * 3 + 0] = tempBuffer[i * 4 + 0];
        outputImage[i * 3 + 1] = tempBuffer[i * 4 + 1];
        outputImage[i * 3 + 2] = tempBuffer[i * 4 + 2];
    }

    // Cleanup
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texInput);
    glDeleteTextures(1, &texOutput);
    if (texLut) glDeleteTextures(1, &texLut);
    glDeleteProgram(program);
    glDeleteShader(cs);

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(display, surface);
    eglDestroyContext(display, context);

    LOGD("GPU processing finished");
    return true;
}


extern "C" JNIEXPORT jint JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_processRaw(
        JNIEnv* env,
        jobject /* this */,
        jobject rawBuffer,
        jint width,
        jint height,
        jint stride,
        jint whiteLevel,
        jint blackLevel,
        jint cfaPattern,
        jfloatArray wbGains,
        jfloatArray ccm,
        jint targetLog,
        jstring lutPath,
        jstring outputTiffPath,
        jstring outputJpgPath,
        jboolean useGpu,
        jint cropX, jint cropY, jint cropW, jint cropH, jboolean physicallyCrop) {

    LOGD("Native processRaw started. UseGPU: %d. Crop: %d %d %d %d, Phys: %d", useGpu, cropX, cropY, cropW, cropH, physicallyCrop);

    uint16_t* rawData = (uint16_t*)env->GetDirectBufferAddress(rawBuffer);
    if (!rawData) { LOGE("Failed to get buffer address"); return -1; }

    float* wb = env->GetFloatArrayElements(wbGains, 0);
    float* colorMat = env->GetFloatArrayElements(ccm, 0);
    if (!wb || !colorMat) {
         if (wb) env->ReleaseFloatArrayElements(wbGains, wb, 0);
         if (colorMat) env->ReleaseFloatArrayElements(ccm, colorMat, 0);
         return -1;
    }

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        LOGD("Loaded LUT size: %d", lut.size);
    }

    // Pre-calculate combined matrix
    float combinedMat[9];
    calculateCombinedMatrix(colorMat, wb, combinedMat);

    // Determine output size
    int outW = physicallyCrop ? cropW : width;
    int outH = physicallyCrop ? cropH : height;

    std::vector<unsigned short> outputImage(outW * outH * 3);

    int result = 0; // 0=Success GPU, 1=Success CPU, -1=Error

    if (useGpu) {
        bool success = processGpu(rawData, width, height, stride, whiteLevel, blackLevel, cfaPattern, wb, combinedMat, targetLog, lut, outputImage, cropX, cropY, cropW, cropH, physicallyCrop);
        if (!success) {
            LOGE("GPU processing failed, falling back to CPU");
            result = 1;
            processCpu(rawData, width, height, stride, whiteLevel, blackLevel, cfaPattern, wb, combinedMat, targetLog, lut, outputImage, cropX, cropY, cropW, cropH, physicallyCrop);
        }
    } else {
        processCpu(rawData, width, height, stride, whiteLevel, blackLevel, cfaPattern, wb, combinedMat, targetLog, lut, outputImage, cropX, cropY, cropW, cropH, physicallyCrop);
        result = 0;
    }

    // Save outputs
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;

    if (tiff_path_cstr) write_tiff(tiff_path_cstr, outW, outH, outputImage, cropX, cropY, cropW, cropH);
    if (jpg_path_cstr) write_bmp(jpg_path_cstr, outW, outH, outputImage);

    // Release
    env->ReleaseFloatArrayElements(wbGains, wb, 0);
    env->ReleaseFloatArrayElements(ccm, colorMat, 0);
    if (lutPath) env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);

    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_patchDngMetadata(
        JNIEnv* env,
        jobject /* this */,
        jstring dngPath,
        jint cropX, jint cropY, jint cropW, jint cropH) {

    const char* path = env->GetStringUTFChars(dngPath, 0);
    patch_dng(path, cropX, cropY, cropW, cropH);
    env->ReleaseStringUTFChars(dngPath, path);
}

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

struct Vec3 {
    float r, g, b;
};

// --- Color Conversion Matrices (XYZ D50 -> Target RGB D65) ---

// [New] XYZ D50 -> sRGB D65 (Bradford Adaptation baked in)
const float XYZ_TO_SRGB[9] = {
     3.1338561f, -1.6168667f, -0.4906146f,
    -0.9787684f,  1.9161415f,  0.0334540f,
     0.0719453f, -0.2289914f,  1.4052427f
};

// Generated Matrices
const float XYZ_TO_REC2020[9] = {
    1.6473375f, -0.3935676f, -0.2359959f,
    -0.6826035f, 1.6475887f, 0.0128188f,
    0.0296522f, -0.0628993f, 1.2531278f
};

const float XYZ_TO_SGAMUT3[9] = {
    1.44522710f, -0.27950688f, -0.13814838f,
    -0.53193854f, 1.37824019f, 0.16318861f,
    0.02627087f, -0.02700615f, 1.21387504f
};

const float XYZ_TO_SGAMUT3_CINE[9] = {
    1.77696988f, -0.56948493f, -0.17437322f,
    -0.45822405f, 1.27914890f, 0.19713832f,
    0.04928401f, -0.00294680f, 1.15782856f
};

const float XYZ_TO_VGAMUT[9] = {
    1.52963378f, -0.35027734f, -0.15101268f,
    -0.53193854f, 1.37824019f, 0.16318861f,
    0.02173936f, -0.01559844f, 1.20534563f
};

const float XYZ_TO_AWG[9] = {
    1.72072109f, -0.52448433f, -0.16318166f,
    -0.64854187f, 1.42105737f, 0.24754894f,
    -0.03119699f, 0.06609222f, 1.16820405f
};

const float XYZ_TO_CINEMA_GAMUT[9] = {
    1.42921321f, -0.29492358f, -0.10075391f,
    -0.47155033f, 1.28146901f, 0.20989812f,
    -0.06393960f, 0.20946929f, 1.03271223f
};

const float XYZ_TO_RED_WIDE[9] = {
    1.35314994f, -0.20875421f, -0.11629975f,
    -0.49919086f, 1.31151886f, 0.20577964f,
    -0.03513012f, 0.27598118f, 0.91844700f
};

// Log Types (Indices):
// 0: None, 1: Arri LogC3, 2: F-Log, 3: F-Log2, 4: F-Log2 C
// 5: S-Log3, 6: S-Log3.Cine, 7: V-Log, 8: Canon Log 2, 9: Canon Log 3
// 10: N-Log, 11: D-Log, 12: Log3G10

const float* get_color_matrix(int logType) {
    switch (logType) {
        case 0: return XYZ_TO_SRGB;       // None -> Standard sRGB
        case 1: return XYZ_TO_AWG;        // Arri LogC3
        // Use sRGB for F-Log/F-Log2 to match Preview pipeline (which is sRGB based)
        // and prevent gamut mismatch when using LUTs designed for sRGB/Preview.
        case 2: return XYZ_TO_SRGB;       // F-Log (Modified from Rec2020)
        case 3: return XYZ_TO_SRGB;       // F-Log2 (Modified from Rec2020)
        case 4: return XYZ_TO_SRGB;       // F-Log2 C (Modified from Rec2020)
        case 5: return XYZ_TO_SGAMUT3;    // S-Log3
        case 6: return XYZ_TO_SGAMUT3_CINE; // S-Log3.Cine
        case 7: return XYZ_TO_VGAMUT;     // V-Log
        case 8: return XYZ_TO_CINEMA_GAMUT; // Canon Log 2
        case 9: return XYZ_TO_CINEMA_GAMUT; // Canon Log 3
        case 10: return XYZ_TO_REC2020;   // N-Log
        case 11: return XYZ_TO_REC2020;   // D-Log (Assumed Wide)
        case 12: return XYZ_TO_RED_WIDE;  // Log3G10
        default: return XYZ_TO_REC2020;   // Default (None/Rec.709 fallback safe)
    }
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
float f_log2(float x) {
    // F-Log2
    const float a = 5.555556f;
    const float b = 0.064829f;
    const float c = 0.245245f;
    const float d = 0.384316f;
    const float e = 8.799461f;
    const float f = 0.092864f;
    const float cut = 0.000889f;
    if (x >= cut) return c * log10(a * x + b) + d;
    else return e * x + f;
}
float vlog(float x) {
    const float cut = 0.01f;
    const float c = 0.241514f;
    const float b = 0.008730f;
    const float d = 0.598206f;
    if (x >= cut) return c * log10(x + b) + d;
    else return 5.6f * x + 0.125f;
}
float canon_log2(float x) {
    const float cut = 0.002878f; // Approx stop
    const float a = 5.555556f;
    const float b = 0.048831f;
    const float c = 0.252994f;
    const float d = 0.381274f;
    const float e = 8.799461f;
    const float f = 0.092918f;
    if (x >= cut) return c * log10(a * x + b) + d;
    return e * x + f;
}
float canon_log3(float x) {
    const float cut = 0.01284f;
    const float a = 10.15f;
    const float b = 1.0f;
    const float c = 0.529136f;
    const float d = 0.073059f;
    const float e = 17.5756f; // Derived roughly
    const float f = 0.0929f; // Approx black
    // Official equation often simpler in lower section, but let's use approx
    if (x >= cut) return c * log10(a * x + b) + d;
    return 87.09f * x + 0.0929f;
}
float n_log(float x) {
    // N-Log (Fallback to V-Log for safety and consistency)
    // Avoids using unstable formula
    return vlog(x);
}
float d_log(float x) {
    // DJI D-Log (Simple)
    if (x <= 0.0078f) return 6.025f * x + 0.0929f;
    return 0.256663f * log10(10.0f * x + 1.0f) + 0.1f; // Common approx
}
float log3g10(float x) {
    // RED Log3G10 - Simplified
    // Official: y = 0.224282 * log10(x * 155.975327 + 1.0)
    return 0.224282f * log10(std::max(0.0f, x) * 155.975327f + 1.0f);
}

float apply_log(float x, int type) {
    if (x < 0) x = 0;
    switch (type) {
        case 1: return arri_logc3(x);
        case 2: return f_log(x);
        case 3: return f_log2(x);
        case 4: return f_log2(x); // F-Log2 C uses same curve
        case 5: return s_log3(x);
        case 6: return s_log3(x); // Same curve
        case 7: return vlog(x);
        case 8: return canon_log2(x);
        case 9: return canon_log3(x);
        case 10: return n_log(x);
        case 11: return d_log(x);
        case 12: return log3g10(x);
        case 0: return pow(x, 1.0f/2.2f); // Apply Gamma 2.2 for None (sRGB)
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

// --- TIFF/BMP Writers (Kept mostly same) ---

void write_tiff(const char* filename, int width, int height, const std::vector<unsigned short>& data) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) return;
    char header[8] = {'I', 'I', 42, 0, 8, 0, 0, 0};
    file.write(header, 8);
    short num_entries = 10;
    file.write((char*)&num_entries, 2);
    auto write_entry = [&](short tag, short type, int count, int value_or_offset) {
        file.write((char*)&tag, 2);
        file.write((char*)&type, 2);
        file.write((char*)&count, 4);
        file.write((char*)&value_or_offset, 4);
    };
    int data_offset = 8 + 2 + num_entries * 12 + 4;
    write_entry(256, 3, 1, width);
    write_entry(257, 3, 1, height);
    int bps_offset = data_offset + width * height * 6;
    write_entry(258, 3, 3, bps_offset);
    write_entry(259, 3, 1, 1);
    write_entry(262, 3, 1, 2);
    write_entry(273, 4, 1, data_offset);
    write_entry(277, 3, 1, 3);
    write_entry(278, 3, 1, height);
    write_entry(279, 4, 1, width * height * 6);
    write_entry(284, 3, 1, 1);
    int next_ifd = 0;
    file.write((char*)&next_ifd, 4);
    file.write((char*)data.data(), data.size() * 2);
    short bps[3] = {16, 16, 16};
    file.write((char*)bps, 6);
    file.close();
}

void write_bmp(const char* filename, int width, int height, const std::vector<unsigned short>& data) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) return;
    int padded_width = (width * 3 + 3) & (~3);
    int size = 54 + padded_width * height;
    unsigned char header[54] = {0};
    header[0] = 'B'; header[1] = 'M';
    *(int*)&header[2] = size;
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
            int idx = (y * width + x) * 3;
            line[x*3+0] = (unsigned char)(data[idx+2] >> 8);
            line[x*3+1] = (unsigned char)(data[idx+1] >> 8);
            line[x*3+2] = (unsigned char)(data[idx+0] >> 8);
        }
        file.write((char*)line.data(), padded_width);
    }
    file.close();
}

// --- Common ---
void calculateCombinedMatrix(const float* ccm, const float* wb, const float* xyzToTarget, float* combinedMat) {
    // combinedMat = xyzToTarget * ccm
    auto matMul = [](const float* a, const float* b, float* res) {
        for (int i=0; i<3; ++i) {
            for (int j=0; j<3; ++j) {
                res[i*3+j] = 0;
                for (int k=0; k<3; ++k) res[i*3+j] += a[i*3+k] * b[k*3+j];
            }
        }
    };
    matMul(xyzToTarget, ccm, combinedMat);
}

// --- CPU Processing ---
bool processCpu(
    uint16_t* rawData, int width, int height, int stride,
    int whiteLevel, float* blackLevels, int cfaPattern,
    float* wb, float* combinedMat, int targetLog, const LUT3D& lut,
    std::vector<unsigned short>& outputImage
) {
    int r_x, r_y, b_x, b_y;
    if (cfaPattern == 0) { r_x=0; r_y=0; b_x=1; b_y=1; }
    else if (cfaPattern == 1) { r_x=1; r_y=0; b_x=0; b_y=1; }
    else if (cfaPattern == 2) { r_x=0; r_y=1; b_x=1; b_y=0; }
    else { r_x=1; r_y=1; b_x=0; b_y=0; }

    int stride_pixels = stride / 2;

    // Helper lambda to fetch and normalize
    auto fetch_norm = [&](int px, int py) -> float {
         float v = (float)rawData[py * stride_pixels + px];
         int idx = (px & 1) + ((py & 1) << 1);
         float bl = blackLevels[idx];
         return std::max(0.0f, (v - bl) / (whiteLevel - bl));
    };

    #pragma omp parallel for
    for (int y = 0; y < height - 1; y++) {
        for (int x = 0; x < width - 1; x++) {
            float r, g, b;
            bool is_r = ((x & 1) == r_x) && ((y & 1) == r_y);
            bool is_b = ((x & 1) == b_x) && ((y & 1) == b_y);
            bool is_g = !is_r && !is_b;

            float val = fetch_norm(x, y);

            if (is_g) {
                g = val;
                float r_sum = 0, b_sum = 0; int r_cnt = 0, b_cnt = 0;
                if (x>0) { float v = fetch_norm(x-1, y); if (((x-1)&1)==r_x) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; } }
                if (x<width-1) { float v = fetch_norm(x+1, y); if (((x+1)&1)==r_x) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; } }
                if (y>0) { float v = fetch_norm(x, y-1); if (((y-1)&1)==r_y) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; } }
                if (y<height-1) { float v = fetch_norm(x, y+1); if (((y+1)&1)==r_y) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; } }
                r = (r_cnt > 0) ? r_sum / r_cnt : 0;
                b = (b_cnt > 0) ? b_sum / b_cnt : 0;
            } else if (is_r) {
                r = val;
                float g_sum = 0, b_sum = 0; int g_cnt = 0, b_cnt = 0;
                // Simplified bilinear
                if (x>0) { g_sum += fetch_norm(x-1, y); g_cnt++; }
                if (x<width-1) { g_sum += fetch_norm(x+1, y); g_cnt++; }
                if (y>0) { g_sum += fetch_norm(x, y-1); g_cnt++; }
                if (y<height-1) { g_sum += fetch_norm(x, y+1); g_cnt++; }
                if (x>0 && y>0) { b_sum += fetch_norm(x-1, y-1); b_cnt++; }
                if (x<width-1 && y>0) { b_sum += fetch_norm(x+1, y-1); b_cnt++; }
                if (x>0 && y<height-1) { b_sum += fetch_norm(x-1, y+1); b_cnt++; }
                if (x<width-1 && y<height-1) { b_sum += fetch_norm(x+1, y+1); b_cnt++; }
                g = (g_cnt>0) ? g_sum/g_cnt : 0;
                b = (b_cnt>0) ? b_sum/b_cnt : 0;
            } else { // is_b
                b = val;
                float g_sum = 0, r_sum = 0; int g_cnt = 0, r_cnt = 0;
                if (x>0) { g_sum += fetch_norm(x-1, y); g_cnt++; }
                if (x<width-1) { g_sum += fetch_norm(x+1, y); g_cnt++; }
                if (y>0) { g_sum += fetch_norm(x, y-1); g_cnt++; }
                if (y<height-1) { g_sum += fetch_norm(x, y+1); g_cnt++; }
                if (x>0 && y>0) { r_sum += fetch_norm(x-1, y-1); r_cnt++; }
                if (x<width-1 && y>0) { r_sum += fetch_norm(x+1, y-1); r_cnt++; }
                if (x>0 && y<height-1) { r_sum += fetch_norm(x-1, y+1); r_cnt++; }
                if (x<width-1 && y<height-1) { r_sum += fetch_norm(x+1, y+1); r_cnt++; }
                g = (g_cnt>0) ? g_sum/g_cnt : 0;
                r = (r_cnt>0) ? r_sum/r_cnt : 0;
            }

            // Normalization already done in fetch_norm

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

            outputImage[(y * width + x) * 3 + 0] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.r * 65535.0f));
            outputImage[(y * width + x) * 3 + 1] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.g * 65535.0f));
            outputImage[(y * width + x) * 3 + 2] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.b * 65535.0f));
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

uniform int uWidth;
uniform int uHeight;
uniform vec4 uBlackLevels;
uniform float uWhiteLevel;
uniform int uCfaPattern;
uniform vec4 uWbGains; // R, G_even, G_odd, B
uniform mat3 uCombinedMat;
uniform int uTargetLog;
uniform int uLutSize; // 0 if none

// GLSL Log Functions
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
float f_log2(float x) {
    if (x >= 0.000889) return 0.245245 * log(5.555556 * x + 0.064829) / log(10.0) + 0.384316;
    return 8.799461 * x + 0.092864;
}
float vlog(float x) {
    if (x >= 0.01) return 0.241514 * log(x + 0.008730) / log(10.0) + 0.598206;
    return 5.6 * x + 0.125;
}
float canon_log2(float x) {
    if (x >= 0.002878) return 0.252994 * log(5.555556 * x + 0.048831) / log(10.0) + 0.381274;
    return 8.799461 * x + 0.092918;
}
float canon_log3(float x) {
    if (x >= 0.01284) return 0.529136 * log(10.15 * x + 1.0) / log(10.0) + 0.073059;
    return 87.09 * x + 0.0929;
}
float d_log(float x) {
    if (x <= 0.0078) return 6.025 * x + 0.0929;
    return 0.256663 * log(10.0 * x + 1.0) / log(10.0) + 0.1;
}
float log3g10(float x) {
    return 0.224282 * log(max(0.0, x) * 155.975327 + 1.0) / log(10.0);
}

float apply_log(float x, int type) {
    if (x < 0.0) x = 0.0;
    if (type == 1) return arri_logc3(x);
    if (type == 2) return f_log(x);
    if (type == 3 || type == 4) return f_log2(x);
    if (type == 5 || type == 6) return s_log3(x);
    if (type == 7 || type == 10) return vlog(x); // N-Log fallback
    if (type == 8) return canon_log2(x);
    if (type == 9) return canon_log3(x);
    if (type == 11) return d_log(x);
    if (type == 12) return log3g10(x);
    if (type == 0) return pow(x, 1.0/2.2); // Apply Gamma 2.2 for None (sRGB)
    return pow(x, 1.0/2.2);
}

float fetch_norm(ivec2 p) {
    if (p.x < 0 || p.x >= uWidth || p.y < 0 || p.y >= uHeight) return 0.0;
    float v = float(texelFetch(uInput, p, 0).r);
    int idx = (p.x & 1) + ((p.y & 1) * 2);
    float bl = uBlackLevels[idx];
    return max(0.0, (v - bl) / (uWhiteLevel - bl));
}

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= uWidth || pos.y >= uHeight) return;

    // Bayer Decode
    int x = pos.x;
    int y = pos.y;
    int r_x, r_y, b_x, b_y;
    if (uCfaPattern == 0) { r_x=0; r_y=0; b_x=1; b_y=1; }
    else if (uCfaPattern == 1) { r_x=1; r_y=0; b_x=0; b_y=1; }
    else if (uCfaPattern == 2) { r_x=0; r_y=1; b_x=1; b_y=0; }
    else { r_x=1; r_y=1; b_x=0; b_y=0; }

    bool is_r = ((x & 1) == r_x) && ((y & 1) == r_y);
    bool is_b = ((x & 1) == b_x) && ((y & 1) == b_y);
    bool is_g = !is_r && !is_b;

    float val = fetch_norm(pos);
    float r = 0.0, g = 0.0, b = 0.0;

    if (is_g) {
        g = val;
        float r_sum = 0.0; int r_cnt = 0;
        float b_sum = 0.0; int b_cnt = 0;
        ivec2 coords[4];
        coords[0] = ivec2(x-1, y); coords[1] = ivec2(x+1, y);
        coords[2] = ivec2(x, y-1); coords[3] = ivec2(x, y+1);
        for (int i=0; i<4; i++) {
            if (coords[i].x >= 0 && coords[i].x < uWidth && coords[i].y >= 0 && coords[i].y < uHeight) {
                float v = fetch_norm(coords[i]);
                bool n_is_r = ((coords[i].x & 1) == r_x) && ((coords[i].y & 1) == r_y);
                if (n_is_r) { r_sum += v; r_cnt++; } else { b_sum += v; b_cnt++; }
            }
        }
        r = (r_cnt > 0) ? r_sum / float(r_cnt) : 0.0;
        b = (b_cnt > 0) ? b_sum / float(b_cnt) : 0.0;
    } else if (is_r) {
        r = val;
        float g_sum = 0.0; int g_cnt = 0;
        float b_sum = 0.0; int b_cnt = 0;
        ivec2 c_cross[4];
        c_cross[0] = ivec2(x-1, y); c_cross[1] = ivec2(x+1, y);
        c_cross[2] = ivec2(x, y-1); c_cross[3] = ivec2(x, y+1);
        for(int i=0; i<4; i++) {
             if (c_cross[i].x >= 0 && c_cross[i].x < uWidth && c_cross[i].y >= 0 && c_cross[i].y < uHeight) {
                 g_sum += fetch_norm(c_cross[i]); g_cnt++;
             }
        }
        ivec2 c_diag[4];
        c_diag[0] = ivec2(x-1, y-1); c_diag[1] = ivec2(x+1, y-1);
        c_diag[2] = ivec2(x-1, y+1); c_diag[3] = ivec2(x+1, y+1);
        for(int i=0; i<4; i++) {
             if (c_diag[i].x >= 0 && c_diag[i].x < uWidth && c_diag[i].y >= 0 && c_diag[i].y < uHeight) {
                 b_sum += fetch_norm(c_diag[i]); b_cnt++;
             }
        }
        g = (g_cnt > 0) ? g_sum / float(g_cnt) : 0.0;
        b = (b_cnt > 0) ? b_sum / float(b_cnt) : 0.0;
    } else {
        b = val;
        float g_sum = 0.0; int g_cnt = 0;
        float r_sum = 0.0; int r_cnt = 0;
        ivec2 c_cross[4];
        c_cross[0] = ivec2(x-1, y); c_cross[1] = ivec2(x+1, y);
        c_cross[2] = ivec2(x, y-1); c_cross[3] = ivec2(x, y+1);
        for(int i=0; i<4; i++) {
             if (c_cross[i].x >= 0 && c_cross[i].x < uWidth && c_cross[i].y >= 0 && c_cross[i].y < uHeight) {
                 g_sum += fetch_norm(c_cross[i]); g_cnt++;
             }
        }
        ivec2 c_diag[4];
        c_diag[0] = ivec2(x-1, y-1); c_diag[1] = ivec2(x+1, y-1);
        c_diag[2] = ivec2(x-1, y+1); c_diag[3] = ivec2(x+1, y+1);
        for(int i=0; i<4; i++) {
             if (c_diag[i].x >= 0 && c_diag[i].x < uWidth && c_diag[i].y >= 0 && c_diag[i].y < uHeight) {
                 r_sum += fetch_norm(c_diag[i]); r_cnt++;
             }
        }
        g = (g_cnt > 0) ? g_sum / float(g_cnt) : 0.0;
        r = (r_cnt > 0) ? r_sum / float(r_cnt) : 0.0;
    }

    // Normalization already done in fetch_norm

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
    uint16_t* rawData, int width, int height, int stride,
    int whiteLevel, float* blackLevels, int cfaPattern,
    float* wb, float* combinedMat, int targetLog, const LUT3D& lut,
    std::vector<unsigned short>& outputImage
) {
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

    GLuint texInput, texOutput, texLut = 0;
    glGenTextures(1, &texInput);
    glBindTexture(GL_TEXTURE_2D, texInput);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16UI, width, height);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, stride / 2);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, rawData);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    glGenTextures(1, &texOutput);
    glBindTexture(GL_TEXTURE_2D, texOutput);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16UI, width, height);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

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

    glUniform1i(glGetUniformLocation(program, "uWidth"), width);
    glUniform1i(glGetUniformLocation(program, "uHeight"), height);
    glUniform1f(glGetUniformLocation(program, "uWhiteLevel"), (float)whiteLevel);
    glUniform4fv(glGetUniformLocation(program, "uBlackLevels"), 1, blackLevels);
    glUniform1i(glGetUniformLocation(program, "uCfaPattern"), cfaPattern);
    glUniform4f(glGetUniformLocation(program, "uWbGains"), wb[0], wb[1], wb[2], wb[3]);
    glUniformMatrix3fv(glGetUniformLocation(program, "uCombinedMat"), 1, GL_TRUE, combinedMat);
    glUniform1i(glGetUniformLocation(program, "uTargetLog"), targetLog);
    glUniform1i(glGetUniformLocation(program, "uLutSize"), lut.size);

    glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1);
    glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);

    GLuint fbo;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texOutput, 0);

    if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("FBO incomplete");
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

    std::vector<unsigned short> tempBuffer(width * height * 4);
    glReadPixels(0, 0, width, height, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, tempBuffer.data());

    #pragma omp parallel for
    for (int i = 0; i < width * height; i++) {
        outputImage[i * 3 + 0] = tempBuffer[i * 4 + 0];
        outputImage[i * 3 + 1] = tempBuffer[i * 4 + 1];
        outputImage[i * 3 + 2] = tempBuffer[i * 4 + 2];
    }

    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texInput);
    glDeleteTextures(1, &texOutput);
    if (texLut) glDeleteTextures(1, &texLut);
    glDeleteProgram(program);
    glDeleteShader(cs);

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(display, surface);
    eglDestroyContext(display, context);

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
        jfloatArray blackLevels,
        jint cfaPattern,
        jfloatArray wbGains,
        jfloatArray ccm,
        jint targetLog,
        jstring lutPath,
        jstring outputTiffPath,
        jstring outputJpgPath,
        jboolean useGpu) {

    LOGD("Native processRaw started. UseGPU: %d, TargetLog: %d", useGpu, targetLog);

    uint16_t* rawData = (uint16_t*)env->GetDirectBufferAddress(rawBuffer);
    if (!rawData) { LOGE("Failed to get buffer address"); return -1; }

    float* wb = env->GetFloatArrayElements(wbGains, 0);
    float* colorMat = env->GetFloatArrayElements(ccm, 0);
    float* bl = env->GetFloatArrayElements(blackLevels, 0);

    if (!wb || !colorMat || !bl) {
         if (wb) env->ReleaseFloatArrayElements(wbGains, wb, 0);
         if (colorMat) env->ReleaseFloatArrayElements(ccm, colorMat, 0);
         if (bl) env->ReleaseFloatArrayElements(blackLevels, bl, 0);
         return -1;
    }

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        LOGD("Loaded LUT size: %d", lut.size);
    }

    // Determine target matrix
    const float* xyzToTarget = get_color_matrix(targetLog);

    // Pre-calculate combined matrix
    float combinedMat[9];
    calculateCombinedMatrix(colorMat, wb, xyzToTarget, combinedMat);

    std::vector<unsigned short> outputImage(width * height * 3);

    int result = 0;

    if (useGpu) {
        bool success = processGpu(rawData, width, height, stride, whiteLevel, bl, cfaPattern, wb, combinedMat, targetLog, lut, outputImage);
        if (!success) {
            LOGE("GPU processing failed, falling back to CPU");
            result = 1;
            processCpu(rawData, width, height, stride, whiteLevel, bl, cfaPattern, wb, combinedMat, targetLog, lut, outputImage);
        }
    } else {
        processCpu(rawData, width, height, stride, whiteLevel, bl, cfaPattern, wb, combinedMat, targetLog, lut, outputImage);
        result = 0;
    }

    // Save outputs
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;

    if (tiff_path_cstr) write_tiff(tiff_path_cstr, width, height, outputImage);
    if (jpg_path_cstr) write_bmp(jpg_path_cstr, width, height, outputImage);

    env->ReleaseFloatArrayElements(wbGains, wb, 0);
    env->ReleaseFloatArrayElements(ccm, colorMat, 0);
    env->ReleaseFloatArrayElements(blackLevels, bl, 0);
    if (lutPath) env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);

    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_loadLutData(
        JNIEnv* env,
        jobject /* this */,
        jstring lutPath) {
    const char* path = env->GetStringUTFChars(lutPath, 0);
    LUT3D lut = load_lut(path);
    env->ReleaseStringUTFChars(lutPath, path);
    if (lut.size == 0) return nullptr;
    std::vector<float> floatData;
    floatData.reserve(lut.data.size() * 3);
    for (const auto& vec : lut.data) {
        floatData.push_back(vec.r);
        floatData.push_back(vec.g);
        floatData.push_back(vec.b);
    }
    jfloatArray result = env->NewFloatArray(floatData.size());
    env->SetFloatArrayRegion(result, 0, floatData.size(), floatData.data());
    return result;
}

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

// --- TIFF Writer ---

void write_tiff(const char* filename, int width, int height, const std::vector<unsigned short>& data) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) return;

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
     // CCM is Cam -> XYZ. ProPhoto is XYZ -> Pro.
     // Total: Raw * WB * CCM * ProPhoto
     // Simplified to just 3x3 matmul on CPU, shader does v * M.
     // Note: Standard pipeline:
     // 1. Linearize (Raw - Black) / (White - Black)
     // 2. White Balance (Diagonal Matrix)
     // 3. CCM (Color Matrix) -> XYZ
     // 4. XYZ -> RGB (ProPhoto)
     // Combined M = ProPhoto * CCM.
     // WB is separate.

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
    uint16_t* rawData, int inputWidth, int inputHeight, int stride,
    int cropX, int cropY, int cropW, int cropH,
    int whiteLevel, int blackLevel, int cfaPattern,
    float* wb, float* combinedMat, int targetLog, const LUT3D& lut,
    std::vector<unsigned short>& outputImage
) {
    int r_x, r_y, b_x, b_y;
    if (cfaPattern == 0) { r_x=0; r_y=0; b_x=1; b_y=1; }
    else if (cfaPattern == 1) { r_x=1; r_y=0; b_x=0; b_y=1; }
    else if (cfaPattern == 2) { r_x=0; r_y=1; b_x=1; b_y=0; }
    else { r_x=1; r_y=1; b_x=0; b_y=0; }

    int stride_pixels = stride / 2;

    #pragma omp parallel for
    for (int y = 0; y < cropH; y++) {
        for (int x = 0; x < cropW; x++) {
            int srcX = cropX + x;
            int srcY = cropY + y;

            // Safety check
            if (srcX >= inputWidth || srcY >= inputHeight) continue;

            float r, g, b;
            bool is_r = ((srcX & 1) == r_x) && ((srcY & 1) == r_y);
            bool is_b = ((srcX & 1) == b_x) && ((srcY & 1) == b_y);
            bool is_g = !is_r && !is_b;

            float val = (float)rawData[srcY * stride_pixels + srcX];

            if (is_g) {
                g = val;
                float r_sum = 0, b_sum = 0; int r_cnt = 0, b_cnt = 0;
                // Check neighbors in input coordinates
                if (srcX>0) { float v = rawData[srcY * stride_pixels + (srcX-1)]; if (((srcX-1)&1)==r_x) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; } }
                if (srcX<inputWidth-1) { float v = rawData[srcY * stride_pixels + (srcX+1)]; if (((srcX+1)&1)==r_x) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; } }
                if (srcY>0) { float v = rawData[(srcY-1) * stride_pixels + srcX]; if (((srcY-1)&1)==r_y) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; } }
                if (srcY<inputHeight-1) { float v = rawData[(srcY+1) * stride_pixels + srcX]; if (((srcY+1)&1)==r_y) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; } }
                r = (r_cnt > 0) ? r_sum / r_cnt : 0;
                b = (b_cnt > 0) ? b_sum / b_cnt : 0;
            } else if (is_r) {
                r = val;
                float g_sum = 0, b_sum = 0; int g_cnt = 0, b_cnt = 0;
                if (srcX>0) { g_sum += rawData[srcY*stride_pixels+srcX-1]; g_cnt++; }
                if (srcX<inputWidth-1) { g_sum += rawData[srcY*stride_pixels+srcX+1]; g_cnt++; }
                if (srcY>0) { g_sum += rawData[(srcY-1)*stride_pixels+srcX]; g_cnt++; }
                if (srcY<inputHeight-1) { g_sum += rawData[(srcY+1)*stride_pixels+srcX]; g_cnt++; }
                if (srcX>0 && srcY>0) { b_sum += rawData[(srcY-1)*stride_pixels+srcX-1]; b_cnt++; }
                if (srcX<inputWidth-1 && srcY>0) { b_sum += rawData[(srcY-1)*stride_pixels+srcX+1]; b_cnt++; }
                if (srcX>0 && srcY<inputHeight-1) { b_sum += rawData[(srcY+1)*stride_pixels+srcX-1]; b_cnt++; }
                if (srcX<inputWidth-1 && srcY<inputHeight-1) { b_sum += rawData[(srcY+1)*stride_pixels+srcX+1]; b_cnt++; }
                g = (g_cnt>0) ? g_sum/g_cnt : 0;
                b = (b_cnt>0) ? b_sum/b_cnt : 0;
            } else { // is_b
                b = val;
                float g_sum = 0, r_sum = 0; int g_cnt = 0, r_cnt = 0;
                if (srcX>0) { g_sum += rawData[srcY*stride_pixels+srcX-1]; g_cnt++; }
                if (srcX<inputWidth-1) { g_sum += rawData[srcY*stride_pixels+srcX+1]; g_cnt++; }
                if (srcY>0) { g_sum += rawData[(srcY-1)*stride_pixels+srcX]; g_cnt++; }
                if (srcY<inputHeight-1) { g_sum += rawData[(srcY+1)*stride_pixels+srcX]; g_cnt++; }
                if (srcX>0 && srcY>0) { r_sum += rawData[(srcY-1)*stride_pixels+srcX-1]; r_cnt++; }
                if (srcX<inputWidth-1 && srcY>0) { r_sum += rawData[(srcY-1)*stride_pixels+srcX+1]; r_cnt++; }
                if (srcX>0 && srcY<inputHeight-1) { r_sum += rawData[(srcY+1)*stride_pixels+srcX-1]; r_cnt++; }
                if (srcX<inputWidth-1 && srcY<inputHeight-1) { r_sum += rawData[(srcY+1)*stride_pixels+srcX+1]; r_cnt++; }
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

            outputImage[(y * cropW + x) * 3 + 0] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.r * 65535.0f));
            outputImage[(y * cropW + x) * 3 + 1] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.g * 65535.0f));
            outputImage[(y * cropW + x) * 3 + 2] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.b * 65535.0f));
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

uniform int uOutputWidth;
uniform int uOutputHeight;
uniform int uInputWidth;
uniform int uInputHeight;
uniform ivec2 uCropOffset;

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

    // Use cropped coordinates for output, but source coordinates for input
    ivec2 srcPos = pos + uCropOffset;

    if (srcPos.x >= uInputWidth || srcPos.y >= uInputHeight) return; // Safety

    // Determine CFA based on Source Position
    // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
    int r_x, r_y, b_x, b_y;
    if (uCfaPattern == 0) { r_x=0; r_y=0; b_x=1; b_y=1; }
    else if (uCfaPattern == 1) { r_x=1; r_y=0; b_x=0; b_y=1; }
    else if (uCfaPattern == 2) { r_x=0; r_y=1; b_x=1; b_y=0; }
    else { r_x=1; r_y=1; b_x=0; b_y=0; }

    bool is_r = ((srcPos.x & 1) == r_x) && ((srcPos.y & 1) == r_y);
    bool is_b = ((srcPos.x & 1) == b_x) && ((srcPos.y & 1) == b_y);
    bool is_g = !is_r && !is_b;

    float val = float(texelFetch(uInput, srcPos, 0).r);
    float r = 0.0, g = 0.0, b = 0.0;

    if (is_g) {
        g = val;
        // R neighbors
        float r_sum = 0.0; int r_cnt = 0;
        float b_sum = 0.0; int b_cnt = 0;
        // Neighbors: (x-1, y), (x+1, y), (x, y-1), (x, y+1)
        ivec2 coords[4];
        coords[0] = ivec2(srcPos.x-1, srcPos.y); coords[1] = ivec2(srcPos.x+1, srcPos.y);
        coords[2] = ivec2(srcPos.x, srcPos.y-1); coords[3] = ivec2(srcPos.x, srcPos.y+1);

        for (int i=0; i<4; i++) {
            if (coords[i].x >= 0 && coords[i].x < uInputWidth && coords[i].y >= 0 && coords[i].y < uInputHeight) {
                float v = float(texelFetch(uInput, coords[i], 0).r);
                bool n_is_r = ((coords[i].x & 1) == r_x) && ((coords[i].y & 1) == r_y);
                if (n_is_r) { r_sum += v; r_cnt++; } else { b_sum += v; b_cnt++; }
            }
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
        c_cross[0] = ivec2(srcPos.x-1, srcPos.y); c_cross[1] = ivec2(srcPos.x+1, srcPos.y);
        c_cross[2] = ivec2(srcPos.x, srcPos.y-1); c_cross[3] = ivec2(srcPos.x, srcPos.y+1);
        for(int i=0; i<4; i++) {
             if (c_cross[i].x >= 0 && c_cross[i].x < uInputWidth && c_cross[i].y >= 0 && c_cross[i].y < uInputHeight) {
                 g_sum += float(texelFetch(uInput, c_cross[i], 0).r); g_cnt++;
             }
        }

        // B neighbors (diag)
        ivec2 c_diag[4];
        c_diag[0] = ivec2(srcPos.x-1, srcPos.y-1); c_diag[1] = ivec2(srcPos.x+1, srcPos.y-1);
        c_diag[2] = ivec2(srcPos.x-1, srcPos.y+1); c_diag[3] = ivec2(srcPos.x+1, srcPos.y+1);
        for(int i=0; i<4; i++) {
             if (c_diag[i].x >= 0 && c_diag[i].x < uInputWidth && c_diag[i].y >= 0 && c_diag[i].y < uInputHeight) {
                 b_sum += float(texelFetch(uInput, c_diag[i], 0).r); b_cnt++;
             }
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
        c_cross[0] = ivec2(srcPos.x-1, srcPos.y); c_cross[1] = ivec2(srcPos.x+1, srcPos.y);
        c_cross[2] = ivec2(srcPos.x, srcPos.y-1); c_cross[3] = ivec2(srcPos.x, srcPos.y+1);
        for(int i=0; i<4; i++) {
             if (c_cross[i].x >= 0 && c_cross[i].x < uInputWidth && c_cross[i].y >= 0 && c_cross[i].y < uInputHeight) {
                 g_sum += float(texelFetch(uInput, c_cross[i], 0).r); g_cnt++;
             }
        }
        // R neighbors (diag)
        ivec2 c_diag[4];
        c_diag[0] = ivec2(srcPos.x-1, srcPos.y-1); c_diag[1] = ivec2(srcPos.x+1, srcPos.y-1);
        c_diag[2] = ivec2(srcPos.x-1, srcPos.y+1); c_diag[3] = ivec2(srcPos.x+1, srcPos.y+1);
        for(int i=0; i<4; i++) {
             if (c_diag[i].x >= 0 && c_diag[i].x < uInputWidth && c_diag[i].y >= 0 && c_diag[i].y < uInputHeight) {
                 r_sum += float(texelFetch(uInput, c_diag[i], 0).r); r_cnt++;
             }
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
        // Texture lookup
        // 3D Texture expects normalized coords [0,1]
        // We assume LUT texture handles linear interpolation
        res = texture(uLut, res).rgb;
    }

    // Output
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
    uint16_t* rawData, int inputWidth, int inputHeight, int stride,
    int cropX, int cropY, int cropW, int cropH,
    int whiteLevel, int blackLevel, int cfaPattern,
    float* wb, float* combinedMat, int targetLog, const LUT3D& lut,
    std::vector<unsigned short>& outputImage
) {
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

    // Input (R16UI)
    glGenTextures(1, &texInput);
    glBindTexture(GL_TEXTURE_2D, texInput);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16UI, inputWidth, inputHeight);
    // Upload packed raw data (assume strict packing for GPU for simplicity, or we unpack row by row)
    // The JNI caller passes a buffer. We can't use it directly if stride != width*2.
    // We already pack it in CameraFragment?
    // "stride" param passed to JNI is bytes.
    // If stride == width*2, we can upload.
    // If not, we need to repack. But CameraFragment::copyImageToHolder handles packing!
    // So usually rawData is tightly packed.
    glPixelStorei(GL_UNPACK_ROW_LENGTH, stride / 2);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, inputWidth, inputHeight, GL_RED_INTEGER, GL_UNSIGNED_SHORT, rawData);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    // Output (RGBA16UI)
    glGenTextures(1, &texOutput);
    glBindTexture(GL_TEXTURE_2D, texOutput);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16UI, cropW, cropH);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    // LUT (3D)
    if (lut.size > 0) {
        glGenTextures(1, &texLut);
        glBindTexture(GL_TEXTURE_3D, texLut);
        // RGB32F or RGB16F. RGB32F is safer for precision.
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

    glUniform1i(glGetUniformLocation(program, "uOutputWidth"), cropW);
    glUniform1i(glGetUniformLocation(program, "uOutputHeight"), cropH);
    glUniform1i(glGetUniformLocation(program, "uInputWidth"), inputWidth);
    glUniform1i(glGetUniformLocation(program, "uInputHeight"), inputHeight);
    glUniform2i(glGetUniformLocation(program, "uCropOffset"), cropX, cropY);

    glUniform1f(glGetUniformLocation(program, "uWhiteLevel"), (float)whiteLevel);
    glUniform1f(glGetUniformLocation(program, "uBlackLevel"), (float)blackLevel);
    glUniform1i(glGetUniformLocation(program, "uCfaPattern"), cfaPattern);
    glUniform4f(glGetUniformLocation(program, "uWbGains"), wb[0], wb[1], wb[2], wb[3]);
    glUniformMatrix3fv(glGetUniformLocation(program, "uCombinedMat"), 1, GL_TRUE, combinedMat);
    glUniform1i(glGetUniformLocation(program, "uTargetLog"), targetLog);
    glUniform1i(glGetUniformLocation(program, "uLutSize"), lut.size);

    // 5. Dispatch
    glDispatchCompute((cropW + 15) / 16, (cropH + 15) / 16, 1);
    glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);

    // 6. Readback
    // Attach to FBO
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

    // Read pixels (RGBA)
    std::vector<unsigned short> tempBuffer(cropW * cropH * 4);
    glReadPixels(0, 0, cropW, cropH, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, tempBuffer.data());

    // Compact RGBA -> RGB
    // OMP here too?
    #pragma omp parallel for
    for (int i = 0; i < cropW * cropH; i++) {
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
        jint cropX,
        jint cropY,
        jint cropW,
        jint cropH,
        jboolean useGpu) {

    LOGD("Native processRaw started. UseGPU: %d. Crop: %d,%d %dx%d", useGpu, cropX, cropY, cropW, cropH);

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

    // Output size is based on Crop
    std::vector<unsigned short> outputImage(cropW * cropH * 3);

    int result = 0; // 0=Success GPU, 1=Success CPU, -1=Error

    if (useGpu) {
        bool success = processGpu(rawData, width, height, stride, cropX, cropY, cropW, cropH, whiteLevel, blackLevel, cfaPattern, wb, combinedMat, targetLog, lut, outputImage);
        if (!success) {
            LOGE("GPU processing failed, falling back to CPU");
            result = 1;
            processCpu(rawData, width, height, stride, cropX, cropY, cropW, cropH, whiteLevel, blackLevel, cfaPattern, wb, combinedMat, targetLog, lut, outputImage);
        }
    } else {
        processCpu(rawData, width, height, stride, cropX, cropY, cropW, cropH, whiteLevel, blackLevel, cfaPattern, wb, combinedMat, targetLog, lut, outputImage);
        // User requested CPU, so this is a success case that should not trigger a fallback warning.
        result = 0;
    }

    // Save outputs
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;

    if (tiff_path_cstr) write_tiff(tiff_path_cstr, cropW, cropH, outputImage);
    if (jpg_path_cstr) write_bmp(jpg_path_cstr, cropW, cropH, outputImage);

    // Release
    env->ReleaseFloatArrayElements(wbGains, wb, 0);
    env->ReleaseFloatArrayElements(ccm, colorMat, 0);
    if (lutPath) env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);

    return result;
}

// --- DNG Patcher ---

// Helper to read/write integers with specific endianness (Assuming Little Endian System for now)
// DNGs from Android are typically Little Endian (II)
struct TiffStream {
    std::fstream& fs;
    bool isLE;

    template<typename T>
    T read(uint32_t offset) {
        fs.seekg(offset);
        T val;
        fs.read((char*)&val, sizeof(T));
        return val; // Add swapping if needed
    }

    template<typename T>
    void write(uint32_t offset, T val) {
        fs.seekp(offset);
        fs.write((char*)&val, sizeof(T));
    }

    uint16_t readU16(uint32_t offset) { return read<uint16_t>(offset); }
    uint32_t readU32(uint32_t offset) { return read<uint32_t>(offset); }

    void writeU16(uint32_t offset, uint16_t val) { write<uint16_t>(offset, val); }
    void writeU32(uint32_t offset, uint32_t val) { write<uint32_t>(offset, val); }
};

struct IfdEntry {
    uint16_t tag;
    uint16_t type;
    uint32_t count;
    uint32_t value; // or offset
};

extern "C" JNIEXPORT jint JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_patchDngMetadata(
        JNIEnv* env,
        jobject /* this */,
        jstring dngPath,
        jint cropX,
        jint cropY,
        jint cropW,
        jint cropH) {
    const char* path = env->GetStringUTFChars(dngPath, 0);
    LOGD("Patching DNG at %s with crop %d,%d %dx%d", path, cropX, cropY, cropW, cropH);

    std::fstream fs(path, std::ios::in | std::ios::out | std::ios::binary);
    if (!fs.is_open()) {
        LOGE("Failed to open DNG file");
        env->ReleaseStringUTFChars(dngPath, path);
        return -1;
    }

    // 1. Check Header
    char header[4];
    fs.read(header, 4);
    bool isLE = (header[0] == 'I' && header[1] == 'I');
    if (!isLE) {
        LOGE("Only Little Endian DNGs supported for patching");
        env->ReleaseStringUTFChars(dngPath, path);
        return -2;
    }

    TiffStream ts { fs, isLE };
    uint32_t ifd0Offset = ts.readU32(4);

    // 2. Parse IFD0 to find SubIFDs (Tag 330)
    uint16_t numEntries0 = ts.readU16(ifd0Offset);
    uint32_t subIfdsOffset = 0;
    uint32_t subIfdsCount = 0;

    for (int i=0; i<numEntries0; ++i) {
        uint32_t entryOffset = ifd0Offset + 2 + i * 12;
        uint16_t tag = ts.readU16(entryOffset);
        if (tag == 330) { // SubIFDs
            uint16_t type = ts.readU16(entryOffset + 2);
            subIfdsCount = ts.readU32(entryOffset + 4);
            uint32_t val = ts.readU32(entryOffset + 8);

            if (subIfdsCount == 1 && (type == 4 || type == 3)) {
                 // Value fits in field? If Type=LONG, size=4. 1*4=4. Fits.
                 // So val IS the offset to SubIFD.
                 subIfdsOffset = val; // Single SubIFD
                 // We need to patch this single pointer?
                 // Wait, we need the location of this pointer in the file to update it later.
                 // The pointer IS the value field of THIS entry.
                 // So we can update this entry later.
            } else {
                 // Offset to array of offsets
                 subIfdsOffset = val;
            }
            break;
        }
    }

    if (subIfdsOffset == 0) {
        LOGE("No SubIFDs tag found in IFD0");
        env->ReleaseStringUTFChars(dngPath, path);
        return -3;
    }

    // 3. Collect SubIFD Offsets
    std::vector<uint32_t> subIfdOffsets;
    if (subIfdsCount == 1) {
        // subIfdsOffset is the offset to the SubIFD itself (if we optimized above)
        // Re-verify logic: If Count=1, Value=Offset to IFD.
        subIfdOffsets.push_back(subIfdsOffset);
    } else {
        // subIfdsOffset is offset to list of offsets
        for (int i=0; i<subIfdsCount; ++i) {
             subIfdOffsets.push_back(ts.readU32(subIfdsOffset + i * 4));
        }
    }

    // 4. Patch each SubIFD
    // We assume the SubIFD pointed to is the Raw Image.
    // We will create a NEW IFD at the end of file and update the pointer.

    for (int k=0; k<subIfdOffsets.size(); ++k) {
        uint32_t oldIfdOffset = subIfdOffsets[k];
        uint16_t numEntries = ts.readU16(oldIfdOffset);

        std::vector<IfdEntry> entries;
        for (int i=0; i<numEntries; ++i) {
            uint32_t off = oldIfdOffset + 2 + i * 12;
            IfdEntry e;
            e.tag = ts.readU16(off);
            e.type = ts.readU16(off + 2);
            e.count = ts.readU32(off + 4);
            e.value = ts.readU32(off + 8);

            // Filter existing crop tags to avoid duplicates
            if (e.tag != 50719 && e.tag != 50720) {
                entries.push_back(e);
            }
        }

        // Add New Tags (SHORT)
        // DefaultCropOrigin (50719)
        IfdEntry origin;
        origin.tag = 50719;
        origin.type = 3; // SHORT
        origin.count = 2;
        // Value: cropX (low), cropY (high)
        origin.value = (uint16_t)cropX | ((uint32_t)cropY << 16);
        entries.push_back(origin);

        // DefaultCropSize (50720)
        IfdEntry size;
        size.tag = 50720;
        size.type = 3; // SHORT
        size.count = 2;
        size.value = (uint16_t)cropW | ((uint32_t)cropH << 16);
        entries.push_back(size);

        // Sort
        std::sort(entries.begin(), entries.end(), [](const IfdEntry& a, const IfdEntry& b){
            return a.tag < b.tag;
        });

        // Write New IFD at EOF
        fs.seekp(0, std::ios::end);
        uint32_t newIfdOffset = (uint32_t)fs.tellp();

        uint16_t newCount = (uint16_t)entries.size();
        ts.writeU16(newIfdOffset, newCount);

        for (int i=0; i<entries.size(); ++i) {
            uint32_t off = newIfdOffset + 2 + i * 12;
            ts.writeU16(off, entries[i].tag);
            ts.writeU16(off + 2, entries[i].type);
            ts.writeU32(off + 4, entries[i].count);
            ts.writeU32(off + 8, entries[i].value);
        }

        // Write NextIFD (0)
        ts.writeU32(newIfdOffset + 2 + entries.size() * 12, 0);

        // Update Pointer to this IFD
        if (subIfdsCount == 1) {
            // Find the Tag 330 in IFD0 again and update its value
             // This is slightly inefficient but safe.
             for (int i=0; i<numEntries0; ++i) {
                uint32_t entryOffset = ifd0Offset + 2 + i * 12;
                uint16_t tag = ts.readU16(entryOffset);
                if (tag == 330) {
                     ts.writeU32(entryOffset + 8, newIfdOffset);
                     break;
                }
             }
        } else {
             // Update the array
             ts.writeU32(subIfdsOffset + k * 4, newIfdOffset);
        }
    }

    env->ReleaseStringUTFChars(dngPath, path);
    return 0;
}

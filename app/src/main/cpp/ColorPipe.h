#ifndef COLOR_PIPE_H
#define COLOR_PIPE_H

#include <vector>
#include <string>
#include <fstream>
#include <sstream>
#include <cmath>
#include <algorithm>
#include <iostream>

struct Vec3 {
    float r, g, b;
};

struct Matrix3x3 {
    float m[9]; // Row-major: m[0]*x + m[1]*y + m[2]*z
};

Vec3 multiply(const Matrix3x3& mat, const Vec3& v);
Matrix3x3 invert(const Matrix3x3& src);

// --- Log Curves ---
float arri_logc3(float x);
float s_log3(float x);
float f_log(float x);
float vlog(float x);
float apply_log(float x, int type);

// --- LUT ---
struct LUT3D {
    int size;
    std::vector<Vec3> data;
};

LUT3D load_lut(const char* path);
Vec3 apply_lut(const LUT3D& lut, Vec3 color);

// --- Shared Pipeline ---
// sourceColorSpace: 0 = ProPhoto RGB (LibRaw), 1 = Camera Native (HDR+)
// ccm: 3x3 matrix (row-major) for Camera Native -> sRGB D65 conversion (only used if sourceColorSpace == 1)
// wb: 4-element array [r, g0, g1, b] (Unused in current HDR+ pipeline as CCM includes WB compensation)
void process_and_save_image(
    const std::vector<unsigned short>& inputImage,
    int width,
    int height,
    float gain,
    int targetLog,
    const LUT3D& lut,
    const char* tiffPath,
    const char* jpgPath,
    int sourceColorSpace = 0,
    const float* ccm = nullptr,
    const float* wb = nullptr,
    int orientation = 0,
    unsigned char* out_rgb_buffer = nullptr,
    bool isPreview = false,
    int downsampleFactor = 1
);

// --- File Writers ---
bool write_tiff(const char* filename, int width, int height, const std::vector<unsigned short>& data, int orientation = 0);

// Note: wb parameter removed as it's unused in current DNG logic (CCM handles WB->sRGB mapping)
bool write_dng(const char* filename, int width, int height, const std::vector<unsigned short>& data, int whiteLevel, int iso, long exposureTime, float fNumber, float focalLength, long captureTimeMillis, const std::vector<float>& ccm, int orientation);

bool write_bmp(const char* filename, int width, int height, const std::vector<unsigned short>& data);

bool write_jpeg(const char* filename, int width, int height, const std::vector<unsigned short>& data, int quality);

#endif // COLOR_PIPE_H

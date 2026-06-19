#ifndef COLOR_PIPE_H
#define COLOR_PIPE_H

#include <vector>
#include <string>
#include <cstdint>
#include <fstream>
#include <sstream>
#include <cmath>
#include <algorithm>
#include <iostream>

// --- File Writers ---
struct ImageMetadata {
    int iso = 0;
    int64_t exposureTime = 0;
    float fNumber = 0.0f;
    float focalLength = 0.0f;
    int focalLengthIn35mmFilm = 0;
    int64_t captureTimeMillis = 0;
    int64_t digitizedTimeMillis = 0;
    std::string offsetTime;
    std::string offsetTimeOriginal;
    std::string offsetTimeDigitized;
    std::string make;
    std::string model;
    std::string uniqueCameraModel;
    std::string lensModel;
    std::string software;
    std::string imageDescription;
};

struct Vec3 {
    float r, g, b;
};

struct Matrix3x3 {
    float m[9]; // Row-major: m[0]*x + m[1]*y + m[2]*z
};

Vec3 multiply(const Matrix3x3& mat, const Vec3& v);
Matrix3x3 multiply(const Matrix3x3& a, const Matrix3x3& b);
Matrix3x3 invert(const Matrix3x3& src);

// --- Log Curves ---
float arri_logc3(float x);
float s_log3(float x);
float f_log(float x);
float vlog(float x);
float apply_log(float x, int type);

// --- LUT ---
struct LUT3D {
    int size = 0;
    std::vector<Vec3> data;
};

const LUT3D& load_lut(const char* path);
Vec3 apply_lut(const LUT3D& lut, Vec3 color);

// --- Shared Pipeline ---
bool process_and_save_image(
    const std::vector<unsigned short>& inputImage,
    int width,
    int height,
    float gain,
    int targetLog,
    const LUT3D& lut,
    float exposure = 0.0f,
    float contrast = 0.0f,
    float saturation = 0.0f,
    float highlights = 0.0f,
    float shadows = 0.0f,
    float whites = 0.0f,
    float blacks = 0.0f,
    const char* jpgPath = nullptr,
    const char* tiffPath = nullptr,
    const ImageMetadata* metadata = nullptr,
    int sourceColorSpace = 0,
    const float* ccm = nullptr,
    const float* wb = nullptr,
    int orientation = 0,
    unsigned char* out_rgb_buffer = nullptr,
    int out_width = 0,
    int out_height = 0,
    bool isPreview = false,
    int downsampleFactor = 1,
    float zoomFactor = 1.0f,
    bool mirror = false,
    long long* out_timings = nullptr,
    int ablationMask = 0
);

bool write_dng(const char* filename, int width, int height, const std::vector<unsigned short>& data, int whiteLevel, const std::vector<float>& ccm, const ImageMetadata& metadata, int orientation, bool mirror = false, float baselineExposure = 0.0f);

bool write_bmp(const char* filename, int width, int height, const std::vector<unsigned short>& data);

bool write_jpeg(const char* filename, int width, int height, const std::vector<unsigned short>& data, int quality, long long* out_timings = nullptr);

bool write_tiff(const char* filename, int width, int height, const std::vector<unsigned short>& data, const ImageMetadata* metadata = nullptr);

bool write_tiff_rgba8(const char* filename, int width, int height, const unsigned char* data, const ImageMetadata* metadata = nullptr);

int compute_preview_downsample_factor(int width, int height, int targetLongEdge);

#endif // COLOR_PIPE_H

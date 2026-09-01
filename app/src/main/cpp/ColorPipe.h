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
#include <memory>

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
float srgb_oetf(float x);
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

LUT3D load_lut(const char* path);
std::shared_ptr<LUT3D> get_cached_lut(const char* path);
void clear_lut_cache();
Vec3 apply_lut(const LUT3D& lut, Vec3 color);

// --- Color Rendering Engines ---
enum ColorEngineMode {
    COLOR_ENGINE_PBR_NEUTRAL = 0,   // Khronos PBR Neutral (Industry standard neutral 1:1 fidelity)
    COLOR_ENGINE_PURE_LUMA = 1,     // Natural Filmic / Luma Preserving (Hasselblad/Leica micro-contrast)
    COLOR_ENGINE_SONY_UCHIMURA = 2, // Sony Polyphony Digital (Gran Turismo 7)
    COLOR_ENGINE_ACES_FIT = 3       // Legacy ACES Filmic Fit (Original Darkbag default)
};

Vec3 apply_khronos_pbr_neutral(Vec3 color);
Vec3 apply_pure_luma_filmic(Vec3 c);
Vec3 apply_sony_uchimura(Vec3 c);
Vec3 apply_aces_fit(Vec3 c);

// --- Initialization ---
void init_color_pipe();

// --- Shared Pipeline ---
bool process_and_save_image(
    const unsigned short* planarData,
    int stride_x,
    int stride_y,
    int stride_c,
    const float* lensShadingVec,
    int lensShadingRows,
    int lensShadingCols,
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
    bool enableMemoryColor = false,
    int colorEngineMode = 0
);

bool write_dng(const char* filename, int width, int height, const unsigned short* planarData, int stride_x, int stride_y, int stride_c, int whiteLevel, const std::vector<float>& ccm, const ImageMetadata& metadata, int orientation, bool mirror = false, float baselineExposure = 0.0f, const float* wbVec = nullptr);

bool write_bmp(const char* filename, int width, int height, const unsigned short* planarData, int stride_x, int stride_y, int stride_c);

bool write_jpeg(const char* filename, int width, int height, const unsigned short* planarData, int stride_x, int stride_y, int stride_c, int quality);

bool write_tiff(const char* filename, int width, int height, const unsigned short* planarData, int stride_x, int stride_y, int stride_c, const ImageMetadata* metadata = nullptr);

bool write_tiff_rgba8(const char* filename, int width, int height, const unsigned char* data, const ImageMetadata* metadata = nullptr);

int compute_preview_downsample_factor(int width, int height, int targetLongEdge);

#endif // COLOR_PIPE_H

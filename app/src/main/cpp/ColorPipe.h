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

// --- File Writers ---
bool write_tiff(const char* filename, int width, int height, const std::vector<unsigned short>& data);
bool write_dng(const char* filename, int width, int height, const std::vector<unsigned short>& data, int whiteLevel, int iso, long exposureTime, float fNumber);
bool write_bmp(const char* filename, int width, int height, const std::vector<unsigned short>& data);

#endif // COLOR_PIPE_H

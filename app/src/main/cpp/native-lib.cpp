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

#define TAG "ColorProcessorNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Constants
const float PROPHOTO_RGB_D50[9] = {
    1.3459433f, -0.2556075f, -0.0511118f,
    -0.5445989f, 1.5081673f, 0.0205351f,
    0.0f, 0.0f, 1.2118128f // Simplified D50 adapted
};
// Note: Precise ProPhoto RGB (ROMM RGB) primaries to XYZ matrices are needed.
// XYZ to ProPhoto RGB (D50):
//  1.3459   -0.2556   -0.0511
// -0.5446    1.5082    0.0205
//  0.0000    0.0000    1.2118
// (Source: http://www.brucelindbloom.com/)

struct Vec3 {
    float r, g, b;
};

// --- Log Curves ---

float arri_logc3(float x) {
    // Arri LogC3 EI 800 (approximate)
    const float cut = 0.010591f;
    const float a = 5.555556f;
    const float b = 0.052272f;
    const float c = 0.247190f;
    const float d = 0.385537f;
    const float e = 5.367655f;
    const float f = 0.092809f;

    if (x > cut) {
        return c * log10(a * x + b) + d;
    } else {
        return e * x + f;
    }
}

float s_log3(float x) {
    // S-Log3
    if (x >= 0.01125000f) {
        return (420.0f + log10((x + 0.01f) / (0.18f + 0.01f)) * 261.5f) / 1023.0f;
    } else {
        return (x * 171.2102946929f + 95.0f) / 1023.0f;
    }
}

float f_log(float x) {
    // Fujifilm F-Log
    const float a = 0.555556f;
    const float b = 0.009468f;
    const float c = 0.344676f;
    const float d = 0.790453f;
    const float cut = 0.00089f;

    if (x >= cut) {
        return c * log10(a * x + b) + d;
    } else {
        return 8.52f * x + 0.0929f; // Linear slope approx
    }
}

float vlog(float x) {
    // Panasonic V-Log
    const float cut = 0.01f;
    const float c = 0.241514f;
    const float b = 0.008730f;
    const float d = 0.598206f;

    if (x >= cut) {
         return c * log10(x + b) + d;
    } else {
         return 5.6f * x + 0.125f; // Approx
    }
}

// Map index to function
float apply_log(float x, int type) {
    // 0: None, 1: Arri LogC3, 2: F-Log, 3: F-Log2, 4: F-Log2 C,
    // 5: S-Log3, 6: S-Log3.Cine, 7: V-Log, ...

    // Normalize x to 0.0-1.0 if not already (assuming linear input 0-1)
    if (x < 0) x = 0;

    switch (type) {
        case 1: return arri_logc3(x);
        case 2: return f_log(x); // F-Log
        case 3: return f_log(x); // F-Log2 (Approximating with F-Log for now)
        case 5: return s_log3(x);
        case 6: return s_log3(x); // S-Log3.Cine (Same curve, different gamut usually, handling curve here)
        case 7: return vlog(x);
        // Add others as needed, default to simple gamma or pass through
        case 0: return x; // None (Linear)
        default: return pow(x, 1.0f/2.2f); // Fallback generic gamma
    }
}

// --- LUT ---

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
        // Skip metadata lines that might be parsed as data
        if (line.rfind("TITLE", 0) == 0 ||
            line.rfind("DOMAIN", 0) == 0 ||
            line.rfind("LUT_1D", 0) == 0) continue;

        if (line.find("LUT_3D_SIZE") != std::string::npos) {
            std::stringstream ss(line);
            std::string temp;
            ss >> temp >> lut.size;
            if (lut.size > 256) {
                LOGE("LUT size %d is too large, capping to 256 or invalidating", lut.size);
                lut.size = 0; // Invalidate
            } else if (lut.size > 0) {
                lut.data.reserve(lut.size * lut.size * lut.size);
            }
            continue;
        }
        std::stringstream ss(line);
        float r, g, b;
        if (ss >> r >> g >> b) {
            lut.data.push_back({r, g, b});
        }
    }

    // Validate size
    if (lut.size > 0 && lut.data.size() != (size_t)(lut.size * lut.size * lut.size)) {
        LOGE("Invalid LUT data size. Expected %d^3, got %zu", lut.size, lut.data.size());
        lut.size = 0; // Invalidate
        lut.data.clear();
    }

    return lut;
}

Vec3 apply_lut(const LUT3D& lut, Vec3 color) {
    if (lut.size == 0) return color;

    float scale = (lut.size - 1);
    // Clamp input to [0, 1] to ensure indices are within bounds
    float r = std::max(0.0f, std::min(1.0f, color.r)) * scale;
    float g = std::max(0.0f, std::min(1.0f, color.g)) * scale;
    float b = std::max(0.0f, std::min(1.0f, color.b)) * scale;

    int r0 = (int)r; int r1 = std::min(r0 + 1, lut.size - 1);
    int g0 = (int)g; int g1 = std::min(g0 + 1, lut.size - 1);
    int b0 = (int)b; int b1 = std::min(b0 + 1, lut.size - 1);

    float dr = r - r0;
    float dg = g - g0;
    float db = b - b0;

    // Trilinear interpolation
    // c000 c100 c010 c110 c001 c101 c011 c111
    auto idx = [&](int x, int y, int z) { return x + y * lut.size + z * lut.size * lut.size; };

    // Note: Standard .cube order is R varies fastest? No, usually:
    // for B, for G, for R.
    // So Index = R + G*Size + B*Size*Size.

    Vec3 c000 = lut.data[idx(r0, g0, b0)];
    Vec3 c100 = lut.data[idx(r1, g0, b0)];
    Vec3 c010 = lut.data[idx(r0, g1, b0)];
    Vec3 c110 = lut.data[idx(r1, g1, b0)];
    Vec3 c001 = lut.data[idx(r0, g0, b1)];
    Vec3 c101 = lut.data[idx(r1, g0, b1)];
    Vec3 c011 = lut.data[idx(r0, g1, b1)];
    Vec3 c111 = lut.data[idx(r1, g1, b1)];

    Vec3 c00 = { c000.r * (1-dr) + c100.r * dr, c000.g * (1-dr) + c100.g * dr, c000.b * (1-dr) + c100.b * dr };
    Vec3 c10 = { c010.r * (1-dr) + c110.r * dr, c010.g * (1-dr) + c110.g * dr, c010.b * (1-dr) + c110.b * dr };
    Vec3 c01 = { c001.r * (1-dr) + c101.r * dr, c001.g * (1-dr) + c101.g * dr, c001.b * (1-dr) + c101.b * dr };
    Vec3 c11 = { c011.r * (1-dr) + c111.r * dr, c011.g * (1-dr) + c111.g * dr, c011.b * (1-dr) + c111.b * dr };

    Vec3 c0 = { c00.r * (1-dg) + c10.r * dg, c00.g * (1-dg) + c10.g * dg, c00.b * (1-dg) + c10.b * dg };
    Vec3 c1 = { c01.r * (1-dg) + c11.r * dg, c01.g * (1-dg) + c11.g * dg, c01.b * (1-dg) + c11.b * dg };

    Vec3 c = { c0.r * (1-db) + c1.r * db, c0.g * (1-db) + c1.g * db, c0.b * (1-db) + c1.b * db };
    return c;
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


// --- JNI Function ---

extern "C" JNIEXPORT jboolean JNICALL
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
        jstring outputJpgPath) {

    LOGD("Native processRaw started");

    // 1. Access Data
    uint16_t* rawData = (uint16_t*)env->GetDirectBufferAddress(rawBuffer);
    if (!rawData) {
        LOGE("Failed to get buffer address");
        return JNI_FALSE;
    }

    float* wb = env->GetFloatArrayElements(wbGains, 0);
    float* colorMat = env->GetFloatArrayElements(ccm, 0);

    if (!wb || !colorMat) {
        LOGE("Failed to get array elements");
        if (wb) env->ReleaseFloatArrayElements(wbGains, wb, 0);
        if (colorMat) env->ReleaseFloatArrayElements(ccm, colorMat, 0);
        return JNI_FALSE;
    }

    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;

    // Load LUT if present
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        LOGD("Loaded LUT size: %d", lut.size);
    }

    // 2. Process
    // Result buffer (Interleaved RGB 16-bit)
    std::vector<unsigned short> outputImage(width * height * 3);

    // XYZ to ProPhoto Matrix (pre-calculated or combined)
    // We have CCM: Camera -> XYZ.
    // We want Camera -> XYZ -> ProPhoto.
    // ProPhoto Matrix (XYZ -> ProPhoto D50) is approximately:
    // 1.346 -0.256 -0.051
    // -0.545 1.508  0.021
    // 0.000  0.000  1.212

    // Let's compute Combined Matrix M = ProPhotoMat * CCM * WB_Diagonal
    // Actually, WB is applied first in linear space usually.
    // RGB_cam = WB * Raw
    // XYZ = CCM * RGB_cam
    // RGB_pro = ProPhotoMat * XYZ

    // Matrix multiplication helper
    auto matMul = [](const float* a, const float* b, float* res) {
        for (int i=0; i<3; ++i) {
            for (int j=0; j<3; ++j) {
                res[i*3+j] = 0;
                for (int k=0; k<3; ++k) {
                    res[i*3+j] += a[i*3+k] * b[k*3+j];
                }
            }
        }
    };

    float xyzToPro[9];
    std::copy(std::begin(PROPHOTO_RGB_D50), std::end(PROPHOTO_RGB_D50), std::begin(xyzToPro));

    float combinedMat[9];
    matMul(xyzToPro, colorMat, combinedMat);

    // Bayer offsets
    int r_x, r_y, b_x, b_y, gr_x, gr_y, gb_x, gb_y;
    // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
    if (cfaPattern == 0) { r_x=0; r_y=0; b_x=1; b_y=1; }
    else if (cfaPattern == 1) { r_x=1; r_y=0; b_x=0; b_y=1; }
    else if (cfaPattern == 2) { r_x=0; r_y=1; b_x=1; b_y=0; }
    else { r_x=1; r_y=1; b_x=0; b_y=0; }

    // Simple Bilinear Demosaic & Processing Loop
    // Optimize: This is slow in scalar C++. In production, use Neon intrinsics.
    // For now, simple loops.

    int stride_pixels = stride / 2; // Stride is bytes, data is uint16

    #pragma omp parallel for
    for (int y = 0; y < height - 1; y++) {
        for (int x = 0; x < width - 1; x++) {
            float r, g, b;

            // Determine pixel type
            // Very basic demosaic (grab nearest neighbors)
            // AHD or Malvar-He-Cutler is better but complex for this single file.

            // Current pixel color
            bool is_r = ((x & 1) == r_x) && ((y & 1) == r_y);
            bool is_b = ((x & 1) == b_x) && ((y & 1) == b_y);
            bool is_g = !is_r && !is_b;

            float val = (float)rawData[y * stride_pixels + x];

            if (is_g) {
                g = val;
                // Average R and B
                // Check bounds properly in real code
                float r_sum = 0, b_sum = 0;
                int r_cnt = 0, b_cnt = 0;

                // Horizontal neighbors
                if (x>0) {
                    float v = rawData[y * stride_pixels + (x-1)];
                    if (((x-1)&1)==r_x) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; }
                }
                if (x<width-1) {
                    float v = rawData[y * stride_pixels + (x+1)];
                    if (((x+1)&1)==r_x) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; }
                }
                // Vertical neighbors
                if (y>0) {
                     float v = rawData[(y-1) * stride_pixels + x];
                     if (((y-1)&1)==r_y) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; }
                }
                if (y<height-1) {
                     float v = rawData[(y+1) * stride_pixels + x];
                     if (((y+1)&1)==r_y) { r_sum+=v; r_cnt++; } else { b_sum+=v; b_cnt++; }
                }

                r = (r_cnt > 0) ? r_sum / r_cnt : 0;
                b = (b_cnt > 0) ? b_sum / b_cnt : 0;

            } else if (is_r) {
                r = val;
                // Average G (4 neighbors), Average B (4 corners)
                float g_sum = 0, b_sum = 0;
                int g_cnt = 0, b_cnt = 0;

                // G is cross
                if (x>0) { g_sum += rawData[y*stride_pixels+x-1]; g_cnt++; }
                if (x<width-1) { g_sum += rawData[y*stride_pixels+x+1]; g_cnt++; }
                if (y>0) { g_sum += rawData[(y-1)*stride_pixels+x]; g_cnt++; }
                if (y<height-1) { g_sum += rawData[(y+1)*stride_pixels+x]; g_cnt++; }

                // B is diagonals
                if (x>0 && y>0) { b_sum += rawData[(y-1)*stride_pixels+x-1]; b_cnt++; }
                if (x<width-1 && y>0) { b_sum += rawData[(y-1)*stride_pixels+x+1]; b_cnt++; }
                if (x>0 && y<height-1) { b_sum += rawData[(y+1)*stride_pixels+x-1]; b_cnt++; }
                if (x<width-1 && y<height-1) { b_sum += rawData[(y+1)*stride_pixels+x+1]; b_cnt++; }

                g = (g_cnt>0) ? g_sum/g_cnt : 0;
                b = (b_cnt>0) ? b_sum/b_cnt : 0;

            } else { // is_b
                b = val;
                 // Average G (4 neighbors), Average R (4 corners)
                float g_sum = 0, r_sum = 0;
                int g_cnt = 0, r_cnt = 0;

                // G is cross
                if (x>0) { g_sum += rawData[y*stride_pixels+x-1]; g_cnt++; }
                if (x<width-1) { g_sum += rawData[y*stride_pixels+x+1]; g_cnt++; }
                if (y>0) { g_sum += rawData[(y-1)*stride_pixels+x]; g_cnt++; }
                if (y<height-1) { g_sum += rawData[(y+1)*stride_pixels+x]; g_cnt++; }

                // R is diagonals
                if (x>0 && y>0) { r_sum += rawData[(y-1)*stride_pixels+x-1]; r_cnt++; }
                if (x<width-1 && y>0) { r_sum += rawData[(y-1)*stride_pixels+x+1]; r_cnt++; }
                if (x>0 && y<height-1) { r_sum += rawData[(y+1)*stride_pixels+x-1]; r_cnt++; }
                if (x<width-1 && y<height-1) { r_sum += rawData[(y+1)*stride_pixels+x+1]; r_cnt++; }

                g = (g_cnt>0) ? g_sum/g_cnt : 0;
                r = (r_cnt>0) ? r_sum/r_cnt : 0;
            }

            // Normalization & Black Level Substraction
            float range = (float)(whiteLevel - blackLevel);
            r = std::max(0.0f, (r - blackLevel) / range);
            g = std::max(0.0f, (g - blackLevel) / range);
            b = std::max(0.0f, (b - blackLevel) / range);

            // White Balance (Gains: R, G_even, G_odd, B) -> approximated to R, G, B
            float g_gain = (wb[1] + wb[2]) / 2.0f;
            r *= wb[0];
            g *= g_gain;
            b *= wb[3];

            // Color Matrix (Camera -> ProPhoto)
            float X = combinedMat[0]*r + combinedMat[1]*g + combinedMat[2]*b;
            float Y = combinedMat[3]*r + combinedMat[4]*g + combinedMat[5]*b;
            float Z = combinedMat[6]*r + combinedMat[7]*g + combinedMat[8]*b;

            // Apply Log Curve
            X = apply_log(X, targetLog);
            Y = apply_log(Y, targetLog);
            Z = apply_log(Z, targetLog);

            // Apply LUT
            Vec3 res = {X, Y, Z};
            if (lut.size > 0) {
                res = apply_lut(lut, res);
            }

            // Output to 16-bit buffer (clamp to 0-65535)
            outputImage[(y * width + x) * 3 + 0] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.r * 65535.0f));
            outputImage[(y * width + x) * 3 + 1] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.g * 65535.0f));
            outputImage[(y * width + x) * 3 + 2] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.b * 65535.0f));
        }
    }

    // Save TIFF
    if (tiff_path_cstr) {
        write_tiff(tiff_path_cstr, width, height, outputImage);
        LOGD("Saved TIFF to %s", tiff_path_cstr);
    }

    // Save BMP (for JPG conversion)
    if (jpg_path_cstr) {
        write_bmp(jpg_path_cstr, width, height, outputImage);
        LOGD("Saved BMP to %s", jpg_path_cstr);
    }

    // Release
    env->ReleaseFloatArrayElements(wbGains, wb, 0);
    env->ReleaseFloatArrayElements(ccm, colorMat, 0);
    if (lutPath) env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);

    return JNI_TRUE;
}

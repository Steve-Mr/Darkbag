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
#include <libraw/libraw.h>

#define TAG "ColorProcessorNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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

void processLibRawOutput(
    libraw_processed_image_t* image,
    int targetLog,
    const LUT3D& lut,
    std::vector<unsigned short>& outputImage
) {
    int width = image->width;
    int height = image->height;
    unsigned short* data = (unsigned short*)image->data; // 16-bit

    outputImage.resize(width * height * 3);

    #pragma omp parallel for
    for (int i = 0; i < width * height; i++) {
        float r = data[i * 3 + 0] / 65535.0f;
        float g = data[i * 3 + 1] / 65535.0f;
        float b = data[i * 3 + 2] / 65535.0f;

        // Apply Log
        r = apply_log(r, targetLog);
        g = apply_log(g, targetLog);
        b = apply_log(b, targetLog);

        Vec3 res = {r, g, b};
        if (lut.size > 0) res = apply_lut(lut, res);

        outputImage[i * 3 + 0] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.r * 65535.0f));
        outputImage[i * 3 + 1] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.g * 65535.0f));
        outputImage[i * 3 + 2] = (unsigned short)std::max(0.0f, std::min(65535.0f, res.b * 65535.0f));
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_android_example_cameraxbasic_processor_ColorProcessor_processRaw(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray dngData,
        jint targetLog,
        jstring lutPath,
        jstring outputTiffPath,
        jstring outputJpgPath,
        jboolean useGpu // Ignored in new pipeline
) {
    LOGD("Native processRaw started using LibRaw.");

    // Get DNG Bytes
    jsize len = env->GetArrayLength(dngData);
    if (len <= 0) return -1;

    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(dngData, 0, len, (jbyte*)buf);

    // LibRaw Processing
    LibRaw RawProcessor;

    // Open buffer
    if (RawProcessor.open_buffer(buf, len) != LIBRAW_SUCCESS) {
        LOGE("LibRaw open_buffer failed");
        delete[] buf;
        return -1;
    }

    // Unpack
    if (RawProcessor.unpack() != LIBRAW_SUCCESS) {
        LOGE("LibRaw unpack failed");
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Configure params
    RawProcessor.imgdata.params.output_bps = 16;
    RawProcessor.imgdata.params.gamm[0] = 1.0;
    RawProcessor.imgdata.params.gamm[1] = 1.0;
    RawProcessor.imgdata.params.no_auto_bright = 1;
    RawProcessor.imgdata.params.use_camera_wb = 1;
    RawProcessor.imgdata.params.output_color = 4; // ProPhotoRGB

    // Process
    if (RawProcessor.dcraw_process() != LIBRAW_SUCCESS) {
        LOGE("LibRaw dcraw_process failed");
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Get Mem Image
    int ret = 0;
    libraw_processed_image_t* image = RawProcessor.dcraw_make_mem_image(&ret);
    if (!image) {
        LOGE("LibRaw make_mem_image failed: %d", ret);
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Check Format
    if (image->type != LIBRAW_IMAGE_BITMAP || image->colors != 3 || image->bits != 16) {
        LOGE("LibRaw output format mismatch: Type=%d, Colors=%d, Bits=%d", image->type, image->colors, image->bits);
        LibRaw::dcraw_clear_mem(image);
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Load LUT
    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        lut = load_lut(lut_path_cstr);
        env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    }

    // Process Output (Log + LUT)
    std::vector<unsigned short> finalImage;
    processLibRawOutput(image, targetLog, lut, finalImage);

    // Save
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;

    if (tiff_path_cstr) write_tiff(tiff_path_cstr, image->width, image->height, finalImage);
    if (jpg_path_cstr) write_bmp(jpg_path_cstr, image->width, image->height, finalImage);

    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);

    // Cleanup
    LibRaw::dcraw_clear_mem(image);
    RawProcessor.recycle();
    delete[] buf;

    return 0;
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

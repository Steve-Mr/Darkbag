#include "ColorPipe.h"

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

bool write_dng(const char* filename, int width, int height, const std::vector<unsigned short>& data, int whiteLevel) {
    std::ofstream file(filename, std::ios::binary);
    if (!file.is_open()) return false;

    // 1. DNG Header (Little Endian)
    char header[8] = {'I', 'I', 42, 0, 8, 0, 0, 0};
    file.write(header, 8);

    // 2. Calculate Offsets
    // Entry = 12 bytes.
    // 3 Mandatory Exposure Tags: ExposureTime, FNumber, ISO
    // New IFD Size:
    // Base 16 tags + 3 Exposure tags = 19 tags
    // Total IFD Size = 2 + 19*12 + 4 = 234 bytes

    // ALIGNMENT FIX:
    // data_offset must be even (TIFF spec). 8 + 234 = 242 (Even, OK)
    // However, for safety and 4-byte alignment preference in some readers:
    // 242 is not div by 4. 244 is.
    // Let's add 2 bytes of padding after IFD.

    short num_entries = 19;
    int ifd_size = 2 + num_entries * 12 + 4; // 234
    int padding = 2; // Pad to 236 -> Offset 244 (divisible by 4)

    int data_offset = 8 + ifd_size + padding;
    int img_size = width * height * 6; // 16-bit * 3 channels

    // Metadata Offsets (Placed after image data)
    int off_bps = data_offset + img_size;
    int off_mod = off_bps + 6;    // BPS is 6 bytes
    // off_mod (Camera Model) is 12 bytes
    int off_mat = off_mod + 12;
    // off_mat (ColorMatrix1) is 72 bytes
    int off_neu = off_mat + 72;
    // off_neu (AsShotNeutral) is 24 bytes
    // Total meta after image: 6 + 12 + 72 + 24 = 114 bytes

    // Helper lambda
    auto write_entry = [&](short tag, short type, int count, int value_or_offset) {
        file.write((char*)&tag, 2);
        file.write((char*)&type, 2);
        file.write((char*)&count, 4);
        file.write((char*)&value_or_offset, 4);
    };

    auto write_rational = [&](int num, int den) {
        file.write((char*)&num, 4);
        file.write((char*)&den, 4);
    };

    // 3. Write IFD Count
    file.write((char*)&num_entries, 2);

    // 4. Write Tags (Must be sorted by Tag ID)
    write_entry(256, 3, 1, width);               // Width
    write_entry(257, 3, 1, height);              // Height
    write_entry(258, 3, 3, off_bps);             // BitsPerSample (Offset)
    write_entry(259, 3, 1, 1);                   // Compression: None
    write_entry(262, 3, 1, 2);                   // Photometric: RGB (Linear Raw)
    write_entry(273, 4, 1, data_offset);         // StripOffsets
    write_entry(277, 3, 1, 3);                   // SamplesPerPixel: 3
    write_entry(278, 3, 1, height);              // RowsPerStrip
    write_entry(279, 4, 1, img_size);            // StripByteCounts
    write_entry(284, 3, 1, 1);                   // PlanarConfig: Chunky

    // EXIF Tags (Dummy values to satisfy Lightroom)
    // ExposureTime (33434) - RATIONAL - Inline? No, 8 bytes > 4. Need offset.
    // FNumber (33437) - RATIONAL - Need offset.
    // ISOSpeedRatings (34855) - SHORT - Inline OK.

    // Re-calc offsets for new RATIONALs
    int off_exp = off_neu + 24; // After AsShotNeutral
    int off_fnum = off_exp + 8; // After ExposureTime

    write_entry((short)33434, 5, 1, off_exp);    // ExposureTime (1/30)
    write_entry((short)33437, 5, 1, off_fnum);   // FNumber (f/1.8)
    write_entry((short)34855, 3, 1, 100);        // ISO (100)

    // DNG Specific Tags
    // DNGVersion (Tag 50706): 1.4.0.0
    int dng_ver_val = 1 | (4 << 8);
    write_entry((short)50706, 1, 4, dng_ver_val);

    write_entry((short)50708, 2, 12, off_mod);   // UniqueCameraModel

    // WhiteLevel (Tag 50717): Forced to 65535 (16-bit full scale)
    write_entry((short)50717, 3, 1, 65535);

    write_entry((short)50721, 10, 9, off_mat);   // ColorMatrix1
    write_entry((short)50728, 5, 3, off_neu);    // AsShotNeutral
    write_entry((short)50778, 3, 1, 21);         // CalibrationIlluminant1: D65

    // 5. Next IFD Pointer (0)
    int next_ifd = 0;
    file.write((char*)&next_ifd, 4);

    // PADDING (2 bytes)
    short pad = 0;
    file.write((char*)&pad, 2);

    // 6. Write Image Data
    file.write((char*)data.data(), img_size);

    // 7. Write Metadata Data at calculated offsets

    // BitsPerSample (3 * SHORT)
    short bps[3] = {16, 16, 16};
    file.write((char*)bps, 6);

    // UniqueCameraModel (12 bytes)
    file.write("HDR+ Linear\0", 12);

    // ColorMatrix1 (Identity) - 9 * SRATIONAL (8 bytes)
    int one[2] = {1, 1};
    int zero[2] = {0, 1};
    // Row 1
    file.write((char*)one, 8); file.write((char*)zero, 8); file.write((char*)zero, 8);
    // Row 2
    file.write((char*)zero, 8); file.write((char*)one, 8); file.write((char*)zero, 8);
    // Row 3
    file.write((char*)zero, 8); file.write((char*)zero, 8); file.write((char*)one, 8);

    // AsShotNeutral (1.0, 1.0, 1.0) - 3 * RATIONAL
    file.write((char*)one, 8); file.write((char*)one, 8); file.write((char*)one, 8);

    // ExposureTime (1, 30)
    write_rational(1, 30);

    // FNumber (18, 10) -> f/1.8
    write_rational(18, 10);

    bool result = file.good();
    file.close();
    return result;
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

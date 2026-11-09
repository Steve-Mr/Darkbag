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
#include <android/bitmap.h>
#include <libraw/libraw.h>
#include "ColorPipe.h"
#include "demosaic/RcdDemosaic.h"

#define TAG "ColorProcessorNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct CaptureMetadataFieldIDs {
    jfieldID iso;
    jfieldID exposureTime;
    jfieldID fNumber;
    jfieldID focalLength;
    jfieldID focalLengthIn35mmFilm;
    jfieldID dateTimeOriginal;
    jfieldID dateTimeDigitized;
    jfieldID offsetTime;
    jfieldID offsetTimeOriginal;
    jfieldID offsetTimeDigitized;
    jfieldID make;
    jfieldID model;
    jfieldID uniqueCameraModel;
    jfieldID lensModel;
    jfieldID software;
    jfieldID imageDescription;
} g_metadataFields;

struct BoxedMethodIDs {
    jmethodID intValue;
    jmethodID longValue;
    jmethodID floatValue;
} g_boxedMethods;

jclass g_integerClass = nullptr;
jclass g_longClass = nullptr;
jclass g_floatClass = nullptr;
jclass g_metadataClass = nullptr;

std::string getStringField(JNIEnv* env, jobject obj, jfieldID fieldID, const std::string& defaultValue) {
    jstring jstr = (jstring)env->GetObjectField(obj, fieldID);
    if (!jstr) return defaultValue;
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    std::string result = cstr ? cstr : defaultValue;
    if (cstr) env->ReleaseStringUTFChars(jstr, cstr);
    env->DeleteLocalRef(jstr);
    return result;
}

int getIntField(JNIEnv* env, jobject obj, jfieldID fieldID, int defaultValue) {
    jobject boxed = env->GetObjectField(obj, fieldID);
    if (!boxed) return defaultValue;
    int result = env->CallIntMethod(boxed, g_boxedMethods.intValue);
    env->DeleteLocalRef(boxed);
    return result;
}

int64_t getLongField(JNIEnv* env, jobject obj, jfieldID fieldID, int64_t defaultValue) {
    jobject boxed = env->GetObjectField(obj, fieldID);
    if (!boxed) return defaultValue;
    int64_t result = (int64_t)env->CallLongMethod(boxed, g_boxedMethods.longValue);
    env->DeleteLocalRef(boxed);
    return result;
}

float getFloatField(JNIEnv* env, jobject obj, jfieldID fieldID, float defaultValue) {
    jobject boxed = env->GetObjectField(obj, fieldID);
    if (!boxed) return defaultValue;
    float result = env->CallFloatMethod(boxed, g_boxedMethods.floatValue);
    env->DeleteLocalRef(boxed);
    return result;
}

void ensureIDsInitialized(JNIEnv* env) {
    static bool initialized = false;
    if (initialized) return;

    jclass integerClass = env->FindClass("java/lang/Integer");
    g_boxedMethods.intValue = env->GetMethodID(integerClass, "intValue", "()I");

    jclass longClass = env->FindClass("java/lang/Long");
    g_boxedMethods.longValue = env->GetMethodID(longClass, "longValue", "()J");

    jclass floatClass = env->FindClass("java/lang/Float");
    g_boxedMethods.floatValue = env->GetMethodID(floatClass, "floatValue", "()F");

    jclass metadataClazz = env->FindClass("top/maary/darkbag/models/CaptureMetadata");
    g_metadataFields.iso = env->GetFieldID(metadataClazz, "iso", "Ljava/lang/Integer;");
    g_metadataFields.exposureTime = env->GetFieldID(metadataClazz, "exposureTime", "Ljava/lang/Long;");
    g_metadataFields.fNumber = env->GetFieldID(metadataClazz, "fNumber", "Ljava/lang/Float;");
    g_metadataFields.focalLength = env->GetFieldID(metadataClazz, "focalLength", "Ljava/lang/Float;");
    g_metadataFields.focalLengthIn35mmFilm = env->GetFieldID(metadataClazz, "focalLengthIn35mmFilm", "Ljava/lang/Integer;");
    g_metadataFields.dateTimeOriginal = env->GetFieldID(metadataClazz, "dateTimeOriginal", "Ljava/lang/Long;");
    g_metadataFields.dateTimeDigitized = env->GetFieldID(metadataClazz, "dateTimeDigitized", "Ljava/lang/Long;");
    g_metadataFields.offsetTime = env->GetFieldID(metadataClazz, "offsetTime", "Ljava/lang/String;");
    g_metadataFields.offsetTimeOriginal = env->GetFieldID(metadataClazz, "offsetTimeOriginal", "Ljava/lang/String;");
    g_metadataFields.offsetTimeDigitized = env->GetFieldID(metadataClazz, "offsetTimeDigitized", "Ljava/lang/String;");
    g_metadataFields.make = env->GetFieldID(metadataClazz, "make", "Ljava/lang/String;");
    g_metadataFields.model = env->GetFieldID(metadataClazz, "model", "Ljava/lang/String;");
    g_metadataFields.uniqueCameraModel = env->GetFieldID(metadataClazz, "uniqueCameraModel", "Ljava/lang/String;");
    g_metadataFields.lensModel = env->GetFieldID(metadataClazz, "lensModel", "Ljava/lang/String;");
    g_metadataFields.software = env->GetFieldID(metadataClazz, "software", "Ljava/lang/String;");
    g_metadataFields.imageDescription = env->GetFieldID(metadataClazz, "imageDescription", "Ljava/lang/String;");

    initialized = true;
}

ImageMetadata metadataFromJava(JNIEnv* env, jobject metadataObj) {
    ImageMetadata meta;
    if (!metadataObj) return meta;

    ensureIDsInitialized(env);

    meta.iso = getIntField(env, metadataObj, g_metadataFields.iso, 100);
    meta.exposureTime = getLongField(env, metadataObj, g_metadataFields.exposureTime, 10000000L);
    meta.fNumber = getFloatField(env, metadataObj, g_metadataFields.fNumber, 1.8f);
    meta.focalLength = getFloatField(env, metadataObj, g_metadataFields.focalLength, 0.0f);
    meta.focalLengthIn35mmFilm = getIntField(env, metadataObj, g_metadataFields.focalLengthIn35mmFilm, 0);
    meta.captureTimeMillis = getLongField(env, metadataObj, g_metadataFields.dateTimeOriginal, 0);
    meta.digitizedTimeMillis = getLongField(env, metadataObj, g_metadataFields.dateTimeDigitized, meta.captureTimeMillis);
    meta.offsetTime = getStringField(env, metadataObj, g_metadataFields.offsetTime, "");
    meta.offsetTimeOriginal = getStringField(env, metadataObj, g_metadataFields.offsetTimeOriginal, meta.offsetTime);
    meta.offsetTimeDigitized = getStringField(env, metadataObj, g_metadataFields.offsetTimeDigitized, meta.offsetTime);
    meta.make = getStringField(env, metadataObj, g_metadataFields.make, "Unknown");
    meta.model = getStringField(env, metadataObj, g_metadataFields.model, "Unknown");
    meta.uniqueCameraModel = getStringField(env, metadataObj, g_metadataFields.uniqueCameraModel, meta.model);
    meta.lensModel = getStringField(env, metadataObj, g_metadataFields.lensModel, "");
    meta.software = getStringField(env, metadataObj, g_metadataFields.software, "Darkbag");
    meta.imageDescription = getStringField(env, metadataObj, g_metadataFields.imageDescription, "Processed by Darkbag");

    return meta;
}

static inline uint16_t read_u16_endian(const uint8_t* p, bool le) {
    return le ? (static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8))
              : ((static_cast<uint16_t>(p[0]) << 8) | static_cast<uint16_t>(p[1]));
}

static inline uint32_t read_u32_endian(const uint8_t* p, bool le) {
    return le ? (static_cast<uint32_t>(p[0]) |
                 (static_cast<uint32_t>(p[1]) << 8) |
                 (static_cast<uint32_t>(p[2]) << 16) |
                 (static_cast<uint32_t>(p[3]) << 24))
              : ((static_cast<uint32_t>(p[0]) << 24) |
                 (static_cast<uint32_t>(p[1]) << 16) |
                 (static_cast<uint32_t>(p[2]) << 8) |
                 static_cast<uint32_t>(p[3]));
}

static inline int32_t read_i32_endian(const uint8_t* p, bool le) {
    return static_cast<int32_t>(read_u32_endian(p, le));
}

static inline uint32_t swap_u32_be(uint32_t v) {
    return ((v >> 24) & 0xff) | ((v << 8) & 0xff0000) | ((v >> 8) & 0xff00) | ((v << 24) & 0xff000000);
}

static inline float swap_f32_be(uint32_t v) {
    uint32_t s = swap_u32_be(v);
    float f = 0.0f;
    std::memcpy(&f, &s, 4);
    return f;
}

bool parse_dng_baseline_exposure(const uint8_t* dngData, size_t dataLen, float& outExposure) {
    if (!dngData || dataLen < 16) return false;
    bool le = (dngData[0] == 'I' && dngData[1] == 'I');
    if (!le && !(dngData[0] == 'M' && dngData[1] == 'M')) return false;
    uint16_t magic = read_u16_endian(dngData + 2, le);
    if (magic != 42) return false;

    uint32_t ifdOffset = read_u32_endian(dngData + 4, le);
    while (ifdOffset > 0 && ifdOffset + 2 <= dataLen) {
        uint16_t numEntries = read_u16_endian(dngData + ifdOffset, le);
        size_t entryPtr = ifdOffset + 2;
        if (entryPtr + static_cast<size_t>(numEntries) * 12 > dataLen) break;

        for (uint16_t i = 0; i < numEntries; ++i) {
            uint16_t tag = read_u16_endian(dngData + entryPtr, le);
            uint16_t typ = read_u16_endian(dngData + entryPtr + 2, le);
            uint32_t count = read_u32_endian(dngData + entryPtr + 4, le);
            uint32_t valOrOffset = read_u32_endian(dngData + entryPtr + 8, le);
            entryPtr += 12;

            if (tag == 50730 && typ == 10 && count == 1) { // BaselineExposure (SRATIONAL)
                if (valOrOffset + 8 <= dataLen) {
                    int32_t num = read_i32_endian(dngData + valOrOffset, le);
                    int32_t den = read_i32_endian(dngData + valOrOffset + 4, le);
                    if (den != 0) {
                        outExposure = static_cast<float>(num) / static_cast<float>(den);
                        return true;
                    }
                }
            }
        }
        if (entryPtr + 4 <= dataLen) {
            ifdOffset = read_u32_endian(dngData + entryPtr, le);
        } else {
            break;
        }
    }
    return false;
}

bool parse_dng_gainmap_opcodes(
    const uint8_t* dngData,
    size_t dataLen,
    std::vector<float>& outLensShadingMap,
    int& outRows,
    int& outCols
) {
    if (!dngData || dataLen < 16) return false;
    bool le = (dngData[0] == 'I' && dngData[1] == 'I');
    if (!le && !(dngData[0] == 'M' && dngData[1] == 'M')) return false;
    uint16_t magic = read_u16_endian(dngData + 2, le);
    if (magic != 42) return false;

    uint32_t ifdOffset = read_u32_endian(dngData + 4, le);
    while (ifdOffset > 0 && ifdOffset + 2 <= dataLen) {
        uint16_t numEntries = read_u16_endian(dngData + ifdOffset, le);
        size_t entryPtr = ifdOffset + 2;
        if (entryPtr + static_cast<size_t>(numEntries) * 12 > dataLen) break;

        for (uint16_t i = 0; i < numEntries; ++i) {
            uint16_t tag = read_u16_endian(dngData + entryPtr, le);
            uint32_t count = read_u32_endian(dngData + entryPtr + 4, le);
            uint32_t valOrOffset = read_u32_endian(dngData + entryPtr + 8, le);
            entryPtr += 12;

            if (tag == 51009) { // TIFFTAG_OPCODELIST2
                uint32_t opOffset = valOrOffset;
                if (opOffset + count > dataLen || count < 8) return false;
                const uint8_t* opPtr = dngData + opOffset;
                const uint8_t* opEnd = opPtr + count;

                uint32_t numOpcodes = read_u32_endian(opPtr, false); // DNG Opcodes are ALWAYS Big Endian!
                opPtr += 4;

                int detectedRows = 0, detectedCols = 0;
                std::vector<float> fullMap;

                for (uint32_t opIdx = 0; opIdx < numOpcodes && opPtr + 16 <= opEnd; ++opIdx) {
                    uint32_t opId = read_u32_endian(opPtr, false);
                    uint32_t pBytes = read_u32_endian(opPtr + 12, false);
                    opPtr += 16;
                    if (opPtr + pBytes > opEnd) break;

                    if (opId == 9 && pBytes >= 40 + 32 + 4) { // GainMap
                        uint32_t rows = read_u32_endian(opPtr + 32, false);
                        uint32_t cols = read_u32_endian(opPtr + 36, false);
                        uint32_t mapPlanes = read_u32_endian(opPtr + 72, false);
                        const uint8_t* gainData = opPtr + 76;
                        uint32_t numFloats = rows * cols * mapPlanes;
                        if (gainData + static_cast<size_t>(numFloats) * 4 <= opPtr + pBytes) {
                            if (detectedRows == 0) {
                                detectedRows = static_cast<int>(rows);
                                detectedCols = static_cast<int>(cols);
                            }
                            for (uint32_t fIdx = 0; fIdx < numFloats; ++fIdx) {
                                uint32_t rawVal;
                                std::memcpy(&rawVal, gainData + fIdx * 4, 4);
                                fullMap.push_back(swap_f32_be(rawVal));
                            }
                        }
                    }
                    opPtr += pBytes;
                }

                if (!fullMap.empty() && detectedRows > 1 && detectedCols > 1 &&
                    fullMap.size() == static_cast<size_t>(4 * detectedRows * detectedCols)) {
                    outLensShadingMap = std::move(fullMap);
                    outRows = detectedRows;
                    outCols = detectedCols;
                    return true;
                }
            }
        }
        if (entryPtr + 4 <= dataLen) {
            ifdOffset = read_u32_endian(dngData + entryPtr, le);
        } else {
            break;
        }
    }
    return false;
}

struct RobertsonEntry {
    float mired;
    float u;
    float v;
    float t;
};

// 31-entry table from Wyszecki & Stiles, "Color Science", 2nd edition, page 228 (Adobe DNG SDK dng_temperature.cpp)
// Each row: (r: mired, u: CIE 1960 UCS, v: CIE 1960 UCS, t: slope)
static const RobertsonEntry ROBERTSON_TABLE[] = {
    {0.0f, 0.18006f, 0.26352f, -0.24341f},
    {10.0f, 0.18066f, 0.26589f, -0.25479f},
    {20.0f, 0.18133f, 0.26846f, -0.26876f},
    {30.0f, 0.18208f, 0.27119f, -0.28539f},
    {40.0f, 0.18293f, 0.27407f, -0.30470f},
    {50.0f, 0.18388f, 0.27709f, -0.32675f},
    {60.0f, 0.18494f, 0.28021f, -0.35156f},
    {70.0f, 0.18611f, 0.28342f, -0.37915f},
    {80.0f, 0.18740f, 0.28668f, -0.40955f},
    {90.0f, 0.18880f, 0.28997f, -0.44278f},
    {100.0f, 0.19032f, 0.29326f, -0.47888f},
    {125.0f, 0.19462f, 0.30141f, -0.58204f},
    {150.0f, 0.19962f, 0.30921f, -0.70471f},
    {175.0f, 0.20525f, 0.31647f, -0.84901f},
    {200.0f, 0.21142f, 0.32312f, -1.0182f},
    {225.0f, 0.21807f, 0.32909f, -1.2168f},
    {250.0f, 0.22511f, 0.33439f, -1.4512f},
    {275.0f, 0.23247f, 0.33904f, -1.7298f},
    {300.0f, 0.24010f, 0.34308f, -2.0637f},
    {325.0f, 0.24702f, 0.34655f, -2.4681f},
    {350.0f, 0.25591f, 0.34951f, -2.9641f},
    {375.0f, 0.26400f, 0.35200f, -3.5814f},
    {400.0f, 0.27218f, 0.35407f, -4.3633f},
    {425.0f, 0.28039f, 0.35577f, -5.3762f},
    {450.0f, 0.28863f, 0.35714f, -6.7262f},
    {475.0f, 0.29685f, 0.35821f, -8.4955f},
    {500.0f, 0.30505f, 0.35903f, -10.841f},
    {525.0f, 0.31320f, 0.35961f, -13.994f},
    {550.0f, 0.32129f, 0.35998f, -18.285f},
    {575.0f, 0.32931f, 0.36017f, -24.222f},
    {600.0f, 0.33724f, 0.36020f, -32.613f}
};

float robertson_xy_to_temp(float x, float y) {
    float denom = 1.5f - x + 6.0f * y;
    if (std::abs(denom) < 1e-6f) return 6500.0f;
    float u = 2.0f * x / denom;
    float v = 3.0f * y / denom;
    float last_dt = 0.0f;
    constexpr int table_size = sizeof(ROBERTSON_TABLE) / sizeof(ROBERTSON_TABLE[0]);
    for (int i = 1; i < table_size; ++i) {
        const auto& row = ROBERTSON_TABLE[i];
        float dv0 = row.t;
        float len = std::sqrt(1.0f + dv0 * dv0);
        float du = 1.0f / len;
        float dv = dv0 / len;
        float uu = u - row.u;
        float vv = v - row.v;
        float dt = -uu * dv + vv * du;
        if (dt <= 0.0f || i == table_size - 1) {
            if (dt > 0.0f) dt = 0.0f;
            dt = -dt;
            float f = (i == 1) ? 0.0f : (dt / std::max(last_dt + dt, 1e-6f));
            float r = ROBERTSON_TABLE[i - 1].mired * f + row.mired * (1.0f - f);
            return (r > 1e-4f) ? (1e6f / r) : 6500.0f;
        }
        last_dt = dt;
    }
    return 6500.0f;
}

bool invert_3x3(const float m[9], float inv[9]) {
    float a00 = m[0], a01 = m[1], a02 = m[2];
    float a10 = m[3], a11 = m[4], a12 = m[5];
    float a20 = m[6], a21 = m[7], a22 = m[8];

    float c00 = a11 * a22 - a12 * a21;
    float c01 = a12 * a20 - a10 * a22;
    float c02 = a10 * a21 - a11 * a20;

    float det = a00 * c00 + a01 * c01 + a02 * c02;
    if (std::abs(det) < 1e-9f) return false;
    float invDet = 1.0f / det;

    float c10 = a02 * a21 - a01 * a22;
    float c11 = a00 * a22 - a02 * a20;
    float c12 = a01 * a20 - a00 * a21;

    float c20 = a01 * a12 - a02 * a11;
    float c21 = a02 * a10 - a00 * a12;
    float c22 = a00 * a11 - a01 * a10;

    inv[0] = c00 * invDet; inv[1] = c10 * invDet; inv[2] = c20 * invDet;
    inv[3] = c01 * invDet; inv[4] = c11 * invDet; inv[5] = c21 * invDet;
    inv[6] = c02 * invDet; inv[7] = c12 * invDet; inv[8] = c22 * invDet;
    return true;
}

float illuminant_to_temperature(int illuminant) {
    switch (illuminant) {
        case 17:
        case 3:
            return 2856.0f; // Standard Light A / Tungsten
        case 21:
            return 6504.0f; // D65
        case 20:
            return 5500.0f; // D55
        case 22:
            return 7500.0f; // D75
        case 1:
        case 4:
            return 5500.0f; // Daylight / Flash
        case 2:
            return 4000.0f; // Fluorescent
        default:
            return (illuminant == 17) ? 2856.0f : 6504.0f;
    }
}

static const float M_XYZ_D50_TO_SRGB[9] = {
    3.1338561f, -1.6168667f, -0.4906146f,
    -0.9787684f,  1.9161415f,  0.0334540f,
    0.0719453f, -0.2289914f,  1.4052427f
};

struct CctWeight {
    float cct;
    float weight;
};

CctWeight estimate_cct_and_weight(
    const float* colorMatrix1,
    const float* colorMatrix2,
    int calibrationIlluminant1,
    int calibrationIlluminant2,
    const float neutral[3],
    const float wb[4]
) {
    float t1 = illuminant_to_temperature(calibrationIlluminant1);
    float t2 = illuminant_to_temperature(calibrationIlluminant2);
    float m1 = 1e6f / t1;
    float m2 = 1e6f / t2;

    if (colorMatrix1 != nullptr && colorMatrix2 != nullptr) {
        float n[3];
        if (neutral) {
            n[0] = neutral[0]; n[1] = neutral[1]; n[2] = neutral[2];
        } else if (wb) {
            n[0] = (wb[0] > 1e-4f) ? (1.0f / wb[0]) : 1.0f;
            n[1] = (wb[1] > 1e-4f) ? (1.0f / wb[1]) : 1.0f;
            n[2] = (wb[3] > 1e-4f) ? (1.0f / wb[3]) : 1.0f;
        } else {
            n[0] = 1.0f; n[1] = 1.0f; n[2] = 1.0f;
        }

        float lastX = 0.34567f;
        float lastY = 0.35850f;
        bool iterationSuccess = true;

        for (int pass = 0; pass < 10; ++pass) {
            float temp = robertson_xy_to_temp(lastX, lastY);
            float g = (std::abs(m1 - m2) > 1e-4f) ?
                std::clamp((1e6f / temp - m2) / (m1 - m2), 0.0f, 1.0f) : 1.0f;

            float cm[9];
            for (int i = 0; i < 9; ++i) {
                cm[i] = g * colorMatrix1[i] + (1.0f - g) * colorMatrix2[i];
            }

            float invCm[9];
            if (!invert_3x3(cm, invCm)) {
                iterationSuccess = false;
                break;
            }

            float xX = invCm[0] * n[0] + invCm[1] * n[1] + invCm[2] * n[2];
            float xY = invCm[3] * n[0] + invCm[4] * n[1] + invCm[5] * n[2];
            float xZ = invCm[6] * n[0] + invCm[7] * n[1] + invCm[8] * n[2];

            float sum = xX + xY + xZ;
            if (std::abs(sum) < 1e-6f) {
                iterationSuccess = false;
                break;
            }

            float rawNextX = xX / sum;
            float rawNextY = xY / sum;

            float nextX = 0.5f * lastX + 0.5f * rawNextX;
            float nextY = 0.5f * lastY + 0.5f * rawNextY;

            if (std::abs(nextX - lastX) + std::abs(nextY - lastY) < 1e-7f) {
                lastX = nextX;
                lastY = nextY;
                break;
            }
            lastX = nextX;
            lastY = nextY;
        }

        if (iterationSuccess) {
            float finalT = robertson_xy_to_temp(lastX, lastY);
            float finalWeight = (std::abs(m1 - m2) > 1e-4f) ?
                std::clamp((1e6f / finalT - m2) / (m1 - m2), 0.0f, 1.0f) : 1.0f;
            return {finalT, finalWeight};
        }
    }

    // Fallback: Reciprocal WB ratio
    float wbR = neutral ? neutral[0] : ((wb && wb[0] > 1e-4f) ? (1.0f / wb[0]) : 1.0f);
    float wbB = neutral ? neutral[2] : ((wb && wb[3] > 1e-4f) ? (1.0f / wb[3]) : 1.0f);
    float ratio = wbB / std::max(wbR, 1e-4f);
    float cct = 2000.0f + ratio * 3500.0f;
    float mCur = 1e6f / std::clamp(cct, 2000.0f, 10000.0f);
    float weight = (std::abs(m1 - m2) > 1e-4f) ?
        std::clamp((mCur - m2) / (m1 - m2), 0.0f, 1.0f) : 1.0f;
    return {cct, weight};
}

bool compute_render_ccm(
    const float fm1[9],
    const float fm2[9],
    int illuminant1,
    int illuminant2,
    const float* cm1,
    const float* cm2,
    const float neutral[3],
    const float wb[4],
    float outRenderCcm[9]
) {
    if (!fm1) return false;
    const float* p_fm2 = fm2 ? fm2 : fm1;

    CctWeight cw = estimate_cct_and_weight(
        cm1, cm2,
        illuminant1 > 0 ? illuminant1 : 21,
        illuminant2 > 0 ? illuminant2 : 17,
        neutral, wb
    );

    float interpFm[9];
    for (int i = 0; i < 9; ++i) {
        interpFm[i] = cw.weight * fm1[i] + (1.0f - cw.weight) * p_fm2[i];
    }

    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            float sum = 0.0f;
            for (int k = 0; k < 3; ++k) {
                sum += M_XYZ_D50_TO_SRGB[r * 3 + k] * interpFm[k * 3 + c];
            }
            outRenderCcm[r * 3 + c] = sum;
        }
    }
    return true;
}

} // namespace


extern "C" JNIEXPORT jint JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_processRaw(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray dngData,
        jint targetLog,
        jstring lutPath,
        jfloat exposure,
        jfloat contrast,
        jfloat saturation,
        jfloat highlights,
        jfloat shadows,
        jfloat whites,
        jfloat blacks,
        jfloat digitalGain,
        jstring outputJpgPath,
        jstring outputTiffPath,
        jboolean useGpu, // Ignored in new pipeline
        jint orientation,
        jboolean mirror,
        jobject outputBitmap,
        jint downsampleFactor,
        jfloat zoomFactor,
        jobject metadataObj,
        jboolean enableMemoryColor,
        jint colorEngineMode,
        jfloatArray lensShadingMap,
        jint lensShadingRows,
        jint lensShadingCols
) {
    LOGD("Native processRaw started using LibRaw (enableMemoryColor=%d, colorEngineMode=%d).", enableMemoryColor, colorEngineMode);

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

    // Unpack CFA data
    if (RawProcessor.unpack() != LIBRAW_SUCCESS) {
        LOGE("LibRaw unpack failed");
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Check if image has standard Bayer CFA filters
    bool isBayer = (RawProcessor.imgdata.rawdata.raw_image != nullptr &&
                    RawProcessor.imgdata.idata.filters != 0 &&
                    RawProcessor.imgdata.idata.filters != 9 &&
                    RawProcessor.imgdata.idata.colors == 3);

    // Load LUT
    const char* lut_path_cstr = (lutPath) ? env->GetStringUTFChars(lutPath, 0) : nullptr;
    LUT3D lut;
    if (lut_path_cstr) {
        auto cached = get_cached_lut(lut_path_cstr);
        if (cached) lut = *cached;
        env->ReleaseStringUTFChars(lutPath, lut_path_cstr);
    } else if (lutPath) {
        LOGE("GetStringUTFChars failed for lutPath");
        RawProcessor.recycle();
        delete[] buf;
        return -1;
    }

    // Output Paths & Bitmap
    const char* jpg_path_cstr = (outputJpgPath) ? env->GetStringUTFChars(outputJpgPath, 0) : nullptr;
    if (outputJpgPath && !jpg_path_cstr) { LOGE("GetStringUTFChars failed for outputJpgPath"); }
    const char* tiff_path_cstr = (outputTiffPath) ? env->GetStringUTFChars(outputTiffPath, 0) : nullptr;
    if (outputTiffPath && !tiff_path_cstr) { LOGE("GetStringUTFChars failed for outputTiffPath"); }

    AndroidBitmapInfo info;
    int out_w = 0, out_h = 0;
    unsigned char* bitmapPixels = nullptr;
    if (outputBitmap) {
        AndroidBitmap_getInfo(env, outputBitmap, &info);
        out_w = info.width;
        out_h = info.height;
        if (AndroidBitmap_lockPixels(env, outputBitmap, (void**)&bitmapPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            bitmapPixels = nullptr;
        }
    }

    ImageMetadata meta;
    if (metadataObj) {
        meta = metadataFromJava(env, metadataObj);
    }

    std::vector<float> lensShadingVec;
    const float* lens_shading_ptr = nullptr;
    if (lensShadingMap && lensShadingRows > 0 && lensShadingCols > 0) {
        int lsSize = env->GetArrayLength(lensShadingMap);
        int expected = 4 * lensShadingRows * lensShadingCols;
        if (lsSize >= expected) {
            lensShadingVec.resize(expected);
            env->GetFloatArrayRegion(lensShadingMap, 0, expected, lensShadingVec.data());
            lens_shading_ptr = lensShadingVec.data();
        }
    } else {
        int parsedRows = 0, parsedCols = 0;
        if (parse_dng_gainmap_opcodes(buf, len, lensShadingVec, parsedRows, parsedCols)) {
            lens_shading_ptr = lensShadingVec.data();
            lensShadingRows = parsedRows;
            lensShadingCols = parsedCols;
            LOGI("Native processRaw: recovered OpcodeList2 GainMap from DNG (%dx%d, %zu floats)",
                 lensShadingCols, lensShadingRows, lensShadingVec.size());
        }
    }

    float effectiveDigitalGain = digitalGain;
    if (effectiveDigitalGain <= 1.0f) {
        float parsedExposure = 0.0f;
        if (parse_dng_baseline_exposure(buf, len, parsedExposure) && parsedExposure != 0.0f) {
            effectiveDigitalGain = std::pow(2.0f, parsedExposure);
            LOGI("Native processRaw: recovered BaselineExposure from DNG: %.3f EV (effectiveDigitalGain=%.3f)",
                 parsedExposure, effectiveDigitalGain);
        }
    }

    std::vector<uint16_t> processedRawBuffer;
    int imgWidth = 0, imgHeight = 0;
    int stride_x = 1, stride_y = 0, stride_c = 0;
    int sourceColorSpace = 1; // 1 = Sensor RGB, 0 = ProPhoto
    const float* ccmPtr = nullptr;
    const float* wbPtr = nullptr;
    bool faithfulHighlights = false;
    float renderCcm[9] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    float wb_array[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    libraw_processed_image_t* image = nullptr;

    if (isBayer) {
        int width = RawProcessor.imgdata.sizes.width;
        int height = RawProcessor.imgdata.sizes.height;
        int raw_width = RawProcessor.imgdata.sizes.raw_width;
        int raw_height = RawProcessor.imgdata.sizes.raw_height;
        int top_margin = RawProcessor.imgdata.sizes.top_margin;
        int left_margin = RawProcessor.imgdata.sizes.left_margin;

        if (width <= 0) width = raw_width;
        if (height <= 0) height = raw_height;
        if (raw_width <= 0) raw_width = width;
        if (raw_height <= 0) raw_height = height;

        if (top_margin < 0) top_margin = 0;
        if (left_margin < 0) left_margin = 0;
        if (top_margin + height > raw_height) height = raw_height - top_margin;
        if (left_margin + width > raw_width) width = raw_width - left_margin;

        if (width <= 0 || height <= 0) {
            isBayer = false;
        } else {
            // Extract cropped Bayer CFA buffer
            std::vector<uint16_t> bayerBuf(static_cast<size_t>(width) * height);
            for (int r = 0; r < height; ++r) {
                const uint16_t* srcRow = &RawProcessor.imgdata.rawdata.raw_image[(r + top_margin) * raw_width + left_margin];
                std::memcpy(&bayerBuf[static_cast<size_t>(r) * width], srcRow, static_cast<size_t>(width) * sizeof(uint16_t));
            }

            int c00 = RawProcessor.COLOR(top_margin, left_margin);
            int c01 = RawProcessor.COLOR(top_margin, left_margin + 1);
            int cfaPattern = 0; // RGGB
            if (c00 == 0) {
                cfaPattern = 0; // RGGB
            } else if (c00 == 1 || c00 == 3) {
                cfaPattern = (c01 == 0) ? 1 : 2; // 1=GRBG, 2=GBRG
            } else if (c00 == 2) {
                cfaPattern = 3; // BGGR
            }

            uint16_t bl_array[4] = {64, 64, 64, 64};
            if (RawProcessor.imgdata.color.cblack[4] == 2 && RawProcessor.imgdata.color.cblack[5] == 2) {
                bl_array[0] = static_cast<uint16_t>(RawProcessor.imgdata.color.cblack[6]);
                bl_array[1] = static_cast<uint16_t>(RawProcessor.imgdata.color.cblack[7]);
                bl_array[2] = static_cast<uint16_t>(RawProcessor.imgdata.color.cblack[8]);
                bl_array[3] = static_cast<uint16_t>(RawProcessor.imgdata.color.cblack[9]);
            } else if (RawProcessor.imgdata.color.cblack[0] != 0 || RawProcessor.imgdata.color.cblack[1] != 0 ||
                       RawProcessor.imgdata.color.cblack[2] != 0 || RawProcessor.imgdata.color.cblack[3] != 0) {
                bl_array[0] = static_cast<uint16_t>(RawProcessor.imgdata.color.cblack[0]);
                bl_array[1] = static_cast<uint16_t>(RawProcessor.imgdata.color.cblack[1]);
                bl_array[2] = static_cast<uint16_t>(RawProcessor.imgdata.color.cblack[2]);
                bl_array[3] = static_cast<uint16_t>(RawProcessor.imgdata.color.cblack[3]);
            } else if (RawProcessor.imgdata.color.dng_levels.dng_black != 0) {
                uint16_t b = static_cast<uint16_t>(RawProcessor.imgdata.color.dng_levels.dng_black);
                bl_array[0] = b; bl_array[1] = b; bl_array[2] = b; bl_array[3] = b;
            } else if (RawProcessor.imgdata.color.black != 0) {
                uint16_t b = static_cast<uint16_t>(RawProcessor.imgdata.color.black);
                bl_array[0] = b; bl_array[1] = b; bl_array[2] = b; bl_array[3] = b;
            }

            uint16_t wl = 1023;
            if (RawProcessor.imgdata.color.dng_levels.dng_whitelevel[0] > 0) {
                wl = static_cast<uint16_t>(RawProcessor.imgdata.color.dng_levels.dng_whitelevel[0]);
            } else if (RawProcessor.imgdata.color.maximum > 0) {
                wl = static_cast<uint16_t>(RawProcessor.imgdata.color.maximum);
            } else {
                wl = 65535;
            }

            float neutral[3] = {1.0f, 1.0f, 1.0f};
            if (RawProcessor.imgdata.color.dng_levels.asshotneutral[0] > 1e-4f &&
                RawProcessor.imgdata.color.dng_levels.asshotneutral[1] > 1e-4f &&
                RawProcessor.imgdata.color.dng_levels.asshotneutral[2] > 1e-4f) {
                neutral[0] = RawProcessor.imgdata.color.dng_levels.asshotneutral[0];
                neutral[1] = RawProcessor.imgdata.color.dng_levels.asshotneutral[1];
                neutral[2] = RawProcessor.imgdata.color.dng_levels.asshotneutral[2];

                float r_gain = 1.0f / neutral[0];
                float g_gain = 1.0f / neutral[1];
                float b_gain = 1.0f / neutral[2];
                r_gain /= g_gain;
                b_gain /= g_gain;

                wb_array[0] = r_gain;
                wb_array[1] = 1.0f;
                wb_array[2] = 1.0f;
                wb_array[3] = b_gain;
            } else if (RawProcessor.imgdata.color.cam_mul[0] > 1e-4f &&
                       RawProcessor.imgdata.color.cam_mul[1] > 1e-4f &&
                       RawProcessor.imgdata.color.cam_mul[2] > 1e-4f) {
                float r_gain = RawProcessor.imgdata.color.cam_mul[0];
                float g_gain = RawProcessor.imgdata.color.cam_mul[1];
                float b_gain = RawProcessor.imgdata.color.cam_mul[2];
                r_gain /= g_gain;
                b_gain /= g_gain;

                wb_array[0] = r_gain;
                wb_array[1] = 1.0f;
                wb_array[2] = 1.0f;
                wb_array[3] = b_gain;

                neutral[0] = 1.0f / r_gain;
                neutral[1] = 1.0f;
                neutral[2] = 1.0f / b_gain;
            }

            float fm1[9] = {0.0f}, fm2[9] = {0.0f};
            float cm1[9] = {0.0f}, cm2[9] = {0.0f};
            bool hasFm1 = false, hasFm2 = false;
            bool hasCm1 = false, hasCm2 = false;

            for (int r = 0; r < 3; ++r) {
                for (int c = 0; c < 3; ++c) {
                    fm1[r * 3 + c] = RawProcessor.imgdata.color.dng_color[0].forwardmatrix[r][c];
                    fm2[r * 3 + c] = RawProcessor.imgdata.color.dng_color[1].forwardmatrix[r][c];
                    cm1[r * 3 + c] = RawProcessor.imgdata.color.dng_color[0].colormatrix[r][c];
                    cm2[r * 3 + c] = RawProcessor.imgdata.color.dng_color[1].colormatrix[r][c];
                    if (std::abs(fm1[r * 3 + c]) > 1e-6f) hasFm1 = true;
                    if (std::abs(fm2[r * 3 + c]) > 1e-6f) hasFm2 = true;
                    if (std::abs(cm1[r * 3 + c]) > 1e-6f) hasCm1 = true;
                    if (std::abs(cm2[r * 3 + c]) > 1e-6f) hasCm2 = true;
                }
            }

            int ill1 = RawProcessor.imgdata.color.dng_color[0].illuminant;
            int ill2 = RawProcessor.imgdata.color.dng_color[1].illuminant;
            if (ill1 == 0) ill1 = 21; // D65
            if (ill2 == 0) ill2 = 17; // Standard Light A

            bool ccmReady = false;
            if (hasFm1) {
                ccmReady = compute_render_ccm(
                    fm1,
                    hasFm2 ? fm2 : fm1,
                    ill1,
                    ill2,
                    hasCm1 ? cm1 : nullptr,
                    hasCm2 ? cm2 : (hasCm1 ? cm1 : nullptr),
                    neutral,
                    wb_array,
                    renderCcm
                );
            }
            if (!ccmReady && hasCm1) {
                float invCm[9];
                if (invert_3x3(cm1, invCm)) {
                    for (int r = 0; r < 3; ++r) {
                        for (int c = 0; c < 3; ++c) {
                            float sum = 0.0f;
                            for (int k = 0; k < 3; ++k) {
                                sum += M_XYZ_D50_TO_SRGB[r * 3 + k] * invCm[k * 3 + c];
                            }
                            renderCcm[r * 3 + c] = sum;
                        }
                    }
                    ccmReady = true;
                }
            }

            LOGI("Native processRaw: Running RCD demosaic (%dx%d, CFA=%d, BL=[%u,%u,%u,%u], WL=%u, WB=[%.3f,%.3f,%.3f,%.3f], ccmReady=%d)",
                 width, height, cfaPattern, bl_array[0], bl_array[1], bl_array[2], bl_array[3], wl,
                 wb_array[0], wb_array[1], wb_array[2], wb_array[3], ccmReady ? 1 : 0);

            processedRawBuffer.resize(static_cast<size_t>(width) * height * 3);
            darkbag::demosaic::rcd_demosaic(
                bayerBuf.data(),
                width,
                height,
                cfaPattern,
                bl_array,
                wl,
                wb_array,
                processedRawBuffer.data()
            );

            imgWidth = width;
            imgHeight = height;
            stride_x = 1;
            stride_y = width;
            stride_c = width * height;
            sourceColorSpace = 1; // Sensor RGB
            ccmPtr = renderCcm;
            wbPtr = wb_array;
            faithfulHighlights = true;
        }
    }

    if (!isBayer) {
        LOGI("Native processRaw: Falling back to dcraw_process for non-Bayer RAW format");
        RawProcessor.imgdata.params.output_bps = 16;
        RawProcessor.imgdata.params.gamm[0] = 1.0;
        RawProcessor.imgdata.params.gamm[1] = 1.0;
        RawProcessor.imgdata.params.no_auto_bright = 1;
        RawProcessor.imgdata.params.use_camera_wb = 1;
        RawProcessor.imgdata.params.output_color = 4; // ProPhotoRGB
        RawProcessor.imgdata.params.user_flip = 0;

        if (RawProcessor.dcraw_process() != LIBRAW_SUCCESS) {
            LOGE("LibRaw dcraw_process failed");
            if (bitmapPixels) AndroidBitmap_unlockPixels(env, outputBitmap);
            if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
            if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
            RawProcessor.recycle();
            delete[] buf;
            return -1;
        }

        int ret = 0;
        image = RawProcessor.dcraw_make_mem_image(&ret);
        if (!image || image->type != LIBRAW_IMAGE_BITMAP || image->colors != 3 || image->bits != 16) {
            LOGE("LibRaw make_mem_image failed or invalid format: ret=%d", ret);
            if (image) LibRaw::dcraw_clear_mem(image);
            if (bitmapPixels) AndroidBitmap_unlockPixels(env, outputBitmap);
            if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
            if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);
            RawProcessor.recycle();
            delete[] buf;
            return -1;
        }

        processedRawBuffer.resize(static_cast<size_t>(image->width) * image->height * 3);
        const unsigned short* src = reinterpret_cast<const unsigned short*>(image->data);
        std::copy(src, src + (static_cast<size_t>(image->width) * image->height * 3), processedRawBuffer.begin());

        imgWidth = image->width;
        imgHeight = image->height;
        stride_x = 3;
        stride_y = image->width * 3;
        stride_c = 1;
        sourceColorSpace = 0; // ProPhoto
        ccmPtr = nullptr;
        wbPtr = nullptr;
        faithfulHighlights = false;
    }

    bool saveOk = process_and_save_image(
        processedRawBuffer.data(), stride_x, stride_y, stride_c,
        lens_shading_ptr, lensShadingRows, lensShadingCols,
        imgWidth,
        imgHeight,
        effectiveDigitalGain,
        targetLog,
        lut,
        exposure, contrast, saturation, highlights, shadows, whites, blacks,
        jpg_path_cstr,
        tiff_path_cstr,
        metadataObj ? &meta : nullptr,
        sourceColorSpace,
        ccmPtr,
        wbPtr,
        (int)orientation,
        bitmapPixels,
        out_w,
        out_h,
        outputBitmap != nullptr,
        (int)downsampleFactor,
        (float)zoomFactor,
        (bool)mirror,
        (bool)enableMemoryColor,
        (int)colorEngineMode,
        faithfulHighlights
    );

    if (bitmapPixels) AndroidBitmap_unlockPixels(env, outputBitmap);
    if (outputJpgPath) env->ReleaseStringUTFChars(outputJpgPath, jpg_path_cstr);
    if (outputTiffPath) env->ReleaseStringUTFChars(outputTiffPath, tiff_path_cstr);

    if (image) LibRaw::dcraw_clear_mem(image);
    RawProcessor.recycle();
    delete[] buf;

    return saveOk ? 0 : -1;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_loadLutData(
        JNIEnv* env,
        jobject /* this */,
        jstring lutPath) {

    const char* path = env->GetStringUTFChars(lutPath, 0);
    LUT3D lut;
    if (path) {
        auto cached = get_cached_lut(path);
        if (cached) lut = *cached;
        env->ReleaseStringUTFChars(lutPath, path);
    }

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

extern "C" JNIEXPORT jboolean JNICALL
Java_top_maary_darkbag_processor_ColorProcessor_saveBitmapToTiff(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jstring outputTiffPath,
        jobject metadataObj) {

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;

    void* pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return false;

    const char* path = env->GetStringUTFChars(outputTiffPath, 0);
    bool ok = false;
    if (path) {
        ImageMetadata meta = metadataFromJava(env, metadataObj);
        ok = write_tiff_rgba8(path, info.width, info.height, (unsigned char*)pixels, &meta);
        env->ReleaseStringUTFChars(outputTiffPath, path);
    } else {
        LOGE("GetStringUTFChars failed for outputTiffPath in saveBitmapToTiff");
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    return ok;
}

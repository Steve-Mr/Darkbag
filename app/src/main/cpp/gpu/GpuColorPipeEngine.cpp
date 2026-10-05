#include "GpuColorPipeEngine.h"
#include "GpuColorPipeShader.h"
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <turbojpeg.h>
#include <unistd.h>

#define TAG "DarkbagGPU_Engine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace gpu {

namespace {

// Standard color space conversion matrices identical to ColorPipe.cpp
const Matrix3x3 M_sRGB_D65_to_XYZ = {
    0.41239080f, 0.35758434f, 0.18048079f,
    0.21263901f, 0.71516868f, 0.07219232f,
    0.01933082f, 0.11919478f, 0.95053215f
};

const Matrix3x3 M_XYZ_to_AlexaWideGamut_D65 = {
    1.78906555f, -0.48253386f, -0.20007579f,
   -0.63984866f,  1.39639996f,  0.19443229f,
   -0.04153155f,  0.08233537f,  0.87886848f
};

const Matrix3x3 M_XYZ_to_SGamut3Cine_D65 = {
    1.84677897f, -0.52598612f, -0.21054521f,
   -0.44415326f,  1.25944290f,  0.14939997f,
    0.04085542f,  0.01564089f,  0.86820725f
};

const Matrix3x3 M_XYZ_to_VGamut_D65 = {
    1.59387222f, -0.31417914f, -0.18431177f,
   -0.51815173f,  1.35539124f,  0.12587867f,
    0.01117945f,  0.00319413f,  0.90553536f
};

const Matrix3x3 M_XYZ_to_Rec2020_D65 = {
    1.71665119f, -0.35567078f, -0.25336628f,
   -0.66668435f,  1.61648124f,  0.01576855f,
    0.01763986f, -0.04277061f,  0.94210312f
};

const Matrix3x3 M_XYZ_to_Rec709_D65 = {
    3.24096994f, -1.53738318f, -0.49861076f,
   -0.96924364f,  1.87596750f,  0.04155506f,
    0.05563008f, -0.20397696f,  1.05697151f
};

bool writeJpegToFile(const char* path, int fd, const unsigned char* jpegBuf, unsigned long jpegSize) {
    if (fd >= 0) {
        int dup_fd = dup(fd);
        if (dup_fd < 0) {
            LOGE("dup(fd) failed for %d", fd);
            return false;
        }
        ftruncate(dup_fd, 0);
        FILE* file = fdopen(dup_fd, "wb");
        if (!file) {
            LOGE("fdopen failed for %d", dup_fd);
            close(dup_fd);
            return false;
        }
        size_t written = fwrite(jpegBuf, 1, jpegSize, file);
        fflush(file);
        fclose(file);
        return written == jpegSize;
    } else if (path != nullptr) {
        FILE* file = fopen(path, "wb");
        if (!file) {
            LOGE("Failed to open %s for writing", path);
            return false;
        }
        size_t written = fwrite(jpegBuf, 1, jpegSize, file);
        fclose(file);
        return written == jpegSize;
    }
    return false;
}

} // namespace

GpuColorPipeEngine& GpuColorPipeEngine::instance() {
    static GpuColorPipeEngine instance;
    return instance;
}

GpuColorPipeEngine::~GpuColorPipeEngine() {
    release();
}

bool GpuColorPipeEngine::isAvailable() {
    GpuContext& ctx = GpuContext::instance();
    if (!ctx.isInitialized()) {
        if (!ctx.initialize()) {
            return false;
        }
    }
    return ctx.supportsAHardwareBuffer();
}

bool GpuColorPipeEngine::ensureShaders() {
    if (shadersBuilt_ && program_ && program_->getProgramId() != 0) {
        return true;
    }

    if (!program_) {
        program_ = std::make_unique<GpuProgram>();
    }

    if (!program_->build(kColorPipeVertexShader, kColorPipeFragmentShader)) {
        LOGE("Failed to build GpuProgram shaders");
        return false;
    }

    if (!inputTexture_) {
        inputTexture_ = std::make_unique<GpuInputTexture>();
    }
    if (!ahbTarget_) {
        ahbTarget_ = std::make_unique<AHardwareBufferTarget>();
    }

    shadersBuilt_ = true;
    return true;
}

bool GpuColorPipeEngine::processAndSaveImage(
    const uint16_t* planarRgb,
    int width, int height,
    int stride_x, int stride_y, int stride_c,
    float digitalGain, int targetLog,
    const std::string& lutPath, const LUT3D* fallbackLut,
    float exposure, float contrast, float saturation,
    float highlights, float shadows, float whites, float blacks,
    const char* jpgPath, int outJpgFd,
    const float* ccm, const float* wbVec,
    int orientation, bool mirror, float zoomFactor,
    int colorEngineMode, bool faithfulHighlights,
    int64_t* outColorPipeMs, int64_t* outJpegEncodeMs
) {
    return executePipeline(
        width, height,
        false, 0,
        planarRgb, stride_x, stride_y, stride_c,
        digitalGain, targetLog,
        lutPath, fallbackLut,
        exposure, contrast, saturation,
        highlights, shadows, whites, blacks,
        jpgPath, outJpgFd,
        ccm, wbVec,
        orientation, mirror, zoomFactor,
        colorEngineMode, faithfulHighlights,
        outColorPipeMs, outJpegEncodeMs
    );
}

bool GpuColorPipeEngine::processAndSaveImageFromTexture(
    GLuint inputRgbTexId,
    int width, int height,
    float digitalGain, int targetLog,
    const std::string& lutPath, const LUT3D* fallbackLut,
    float exposure, float contrast, float saturation,
    float highlights, float shadows, float whites, float blacks,
    const char* jpgPath, int outJpgFd,
    const float* ccm, const float* wbVec,
    int orientation, bool mirror, float zoomFactor,
    int colorEngineMode, bool faithfulHighlights,
    int64_t* outColorPipeMs, int64_t* outJpegEncodeMs
) {
    return executePipeline(
        width, height,
        true, inputRgbTexId,
        nullptr, 0, 0, 0,
        digitalGain, targetLog,
        lutPath, fallbackLut,
        exposure, contrast, saturation,
        highlights, shadows, whites, blacks,
        jpgPath, outJpgFd,
        ccm, wbVec,
        orientation, mirror, zoomFactor,
        colorEngineMode, faithfulHighlights,
        outColorPipeMs, outJpegEncodeMs
    );
}

bool GpuColorPipeEngine::executePipeline(
    int width, int height,
    bool isTextureInput, GLuint inputRgbTexId,
    const uint16_t* planarRgb, int stride_x, int stride_y, int stride_c,
    float digitalGain, int targetLog,
    const std::string& lutPath, const LUT3D* fallbackLut,
    float exposure, float contrast, float saturation,
    float highlights, float shadows, float whites, float blacks,
    const char* jpgPath, int outJpgFd,
    const float* ccm, const float* wbVec,
    int orientation, bool mirror, float zoomFactor,
    int colorEngineMode, bool faithfulHighlights,
    int64_t* outColorPipeMs, int64_t* outJpegEncodeMs
) {
    if (width <= 0 || height <= 0) {
        LOGE("Invalid dimensions (%dx%d) for GpuColorPipeEngine", width, height);
        return false;
    }
    if (isTextureInput) {
        if (inputRgbTexId == 0) {
            LOGE("Invalid input texture ID 0 provided to GpuColorPipeEngine");
            return false;
        }
    } else {
        if (!planarRgb) {
            LOGE("Null planar RGB buffer provided to GpuColorPipeEngine");
            return false;
        }
    }

    auto renderStartTime = std::chrono::high_resolution_clock::now();
    std::lock_guard<std::mutex> lock(engineMutex_);

    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("Failed to acquire offscreen EGL context");
        return false;
    }

    if (!ensureShaders()) {
        LOGE("Shaders not ready");
        return false;
    }

    // 1. Calculate output dimensions based on orientation and zoom crop
    bool swapDims = (orientation == 90 || orientation == 270);
    int cropW = (zoomFactor > 1.001f) ? static_cast<int>(width / zoomFactor) : width;
    int cropH = (zoomFactor > 1.001f) ? static_cast<int>(height / zoomFactor) : height;
    int finalW = swapDims ? cropH : cropW;
    int finalH = swapDims ? cropW : cropH;

    // 2. Prepare AHardwareBuffer and bind offscreen FBO
    if (!ahbTarget_->prepare(finalW, finalH)) {
        LOGE("Failed to prepare AHardwareBuffer target (%dx%d)", finalW, finalH);
        return false;
    }

    if (!ahbTarget_->bindFbo()) {
        LOGE("Failed to bind offscreen FBO");
        return false;
    }

    // 3. Setup input texture layout and bind program
    program_->use();
    const ColorPipeUniforms& u = program_->uniforms();

    if (isTextureInput) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, inputRgbTexId);
        glUniform1i(u.uTexUnifiedRgb, 0);
        glUniform1i(u.uInputLayout, 1);
        if (u.uTexR >= 0) glUniform1i(u.uTexR, 1);
        if (u.uTexG >= 0) glUniform1i(u.uTexG, 2);
        if (u.uTexB >= 0) glUniform1i(u.uTexB, 4);
    } else {
        // Upload 16-bit planar sensor RGB textures
        if (!inputTexture_->uploadPlanarRgb(planarRgb, width, height, stride_x, stride_y, stride_c)) {
            LOGE("Failed to upload input planar RGB textures");
            return false;
        }
        // Bind planar R, G, B textures to Texture Units 0, 1, 2
        inputTexture_->bind(u.uTexR, u.uTexG, u.uTexB, 0);
        glUniform1i(u.uInputLayout, 0);
        if (u.uTexUnifiedRgb >= 0) glUniform1i(u.uTexUnifiedRgb, 4);
    }

    // 4. 3D LUT Texture handling
    GLuint lutTexId = 0;
    int lutSize = 0;
    if (!lutPath.empty() || (fallbackLut && fallbackLut->size > 1)) {
        lutTexId = GpuLutTextureManager::instance().getOrCreateLutTexture(lutPath, fallbackLut, &lutSize);
        if (lutSize <= 0) {
            lutSize = GpuLutTextureManager::instance().getCurrentLutSize();
        }
        if (lutSize <= 0 && fallbackLut) {
            lutSize = fallbackLut->size;
        }
    }

    LOGD("GPU ColorPipe LUT status: path='%s', fallbackSize=%d, lutTexId=%u, lutSize=%d, hasLut=%d, layout=%s",
         lutPath.c_str(), fallbackLut ? fallbackLut->size : 0, lutTexId, lutSize,
         (lutTexId != 0 && lutSize > 1) ? 1 : 0,
         isTextureInput ? "unified_texture" : "planar_cpu");

    if (lutTexId != 0 && lutSize > 1) {
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_3D, lutTexId);
        glUniform1i(u.uLut3D, 3);
        glUniform1i(u.uHasLut, 1);
        glUniform1f(u.uLutSize, static_cast<float>(lutSize));
    } else {
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_3D, 0);
        glUniform1i(u.uLut3D, 3);
        glUniform1i(u.uHasLut, 0);
        glUniform1f(u.uLutSize, 0.0f);
    }

    // 5. Compute Color Transformation Matrix
    // Sensor CCM -> sRGB D65 -> CIE XYZ D65 -> Target Wide Gamut / Rec709
    Matrix3x3 effective_CCM = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};
    if (ccm) {
        std::copy(ccm, ccm + 9, effective_CCM.m);
    }

    Matrix3x3 M_XYZ_to_Target;
    switch (targetLog) {
        case 1: M_XYZ_to_Target = M_XYZ_to_AlexaWideGamut_D65; break;
        case 2:
        case 3:
        case 4:
        case 8:
        case 9:
        case 10:
        case 11:
        case 12: M_XYZ_to_Target = M_XYZ_to_Rec2020_D65; break;
        case 5:
        case 6: M_XYZ_to_Target = M_XYZ_to_SGamut3Cine_D65; break;
        case 7: M_XYZ_to_Target = M_XYZ_to_VGamut_D65; break;
        default: M_XYZ_to_Target = M_XYZ_to_Rec709_D65; break;
    }

    Matrix3x3 M_srgb_to_target = multiply(M_XYZ_to_Target, M_sRGB_D65_to_XYZ);
    Matrix3x3 M_final = multiply(M_srgb_to_target, effective_CCM);

    // In GLES 3.0, pass GL_TRUE for transpose to convert C++ row-major Matrix3x3 to GLSL column-major mat3
    glUniformMatrix3fv(u.uColorTransform, 1, GL_TRUE, M_final.m);

    // 6. White balance gains & combined exposure / digital gain
    float wbR = 1.0f, wbG = 1.0f, wbB = 1.0f;
    if (wbVec) {
        wbR = wbVec[0];
        wbG = (wbVec[1] > 0.0f) ? wbVec[1] : 1.0f;
        wbB = wbVec[3];
    }
    glUniform3f(u.uWbGain, wbR, wbG, wbB);

    const float expGain = std::pow(2.0f, exposure);
    const float baseGain = (digitalGain > 0.0f) ? digitalGain : 1.0f;
    const float totalGain = baseGain * expGain;
    glUniform1f(u.uDigitalGain, totalGain);

    // 7. Color Engine & Log mode
    glUniform1i(u.uTargetLog, targetLog);
    glUniform1i(u.uColorEngineMode, colorEngineMode);

    // 8. Contrast & Saturation
    glUniform1f(u.uContrast, contrast);
    glUniform1f(u.uSaturation, saturation);

    // 9. Highlights / Shadows / Whites / Blacks
    bool hasHswb = (highlights != 0.0f || shadows != 0.0f || whites != 0.0f || blacks != 0.0f);
    glUniform1i(u.uHasHswb, hasHswb ? 1 : 0);
    glUniform1f(u.uHighlights, highlights);
    glUniform1f(u.uShadows, shadows);
    glUniform1f(u.uWhites, whites);
    glUniform1f(u.uBlacks, blacks);

    // 10. Geometry: Orientation, Mirror, Zoom Factor
    glUniform1i(u.uOrientation, orientation);
    glUniform1i(u.uMirror, mirror ? 1 : 0);
    glUniform1f(u.uZoomFactor, zoomFactor > 1.001f ? zoomFactor : 1.0f);

    // 11. Execute offscreen GPU Draw Call
    program_->drawQuad();

    if (isTextureInput) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
    } else {
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    glActiveTexture(GL_TEXTURE3);
    glBindTexture(GL_TEXTURE_3D, 0);
    glActiveTexture(GL_TEXTURE0);

    // 12. Sync fence wait
    if (!ahbTarget_->waitGpuFinish()) {
        LOGW("waitGpuFinish encountered fallback or warning");
    }

    if (outColorPipeMs) {
        *outColorPipeMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - renderStartTime
        ).count();
    }

    // 13. Zero-Copy CPU direct memory mapping & libjpeg-turbo encoding
    auto encodeStartTime = std::chrono::high_resolution_clock::now();

    void* virtualAddr = nullptr;
    int stridePixels = 0;
    if (!ahbTarget_->lockRead(&virtualAddr, &stridePixels)) {
        LOGE("Failed to lock AHardwareBuffer for CPU reading");
        return false;
    }

    tjhandle compressor = tjInitCompress();
    if (!compressor) {
        LOGE("tjInitCompress failed");
        ahbTarget_->unlock();
        return false;
    }

    unsigned char* jpegBuf = nullptr;
    unsigned long jpegSize = 0;
    int pitch = stridePixels * 4; // 4 bytes per pixel (RGBA)

    int tj_stat = tjCompress2(
        compressor,
        reinterpret_cast<const unsigned char*>(virtualAddr),
        finalW, pitch, finalH,
        TJPF_RGBA,
        &jpegBuf, &jpegSize,
        TJSAMP_422, 95,
        TJFLAG_FASTDCT
    );

    ahbTarget_->unlock();

    if (tj_stat != 0 || !jpegBuf || jpegSize == 0) {
        LOGE("tjCompress2 failed: %s", tjGetErrorStr());
        tjDestroy(compressor);
        if (jpegBuf) tjFree(jpegBuf);
        return false;
    }

    bool writeOk = writeJpegToFile(jpgPath, outJpgFd, jpegBuf, jpegSize);
    tjDestroy(compressor);
    tjFree(jpegBuf);

    if (outJpegEncodeMs) {
        *outJpegEncodeMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - encodeStartTime
        ).count();
    }

    LOGD("GpuColorPipe completed (%s): %dx%d, render=%lld ms, encode=%lld ms, writeOk=%d",
         isTextureInput ? "texture" : "planar",
         finalW, finalH,
         outColorPipeMs ? (long long)*outColorPipeMs : 0LL,
         outJpegEncodeMs ? (long long)*outJpegEncodeMs : 0LL,
         writeOk);

    return writeOk;
}

void GpuColorPipeEngine::release() {
    std::lock_guard<std::mutex> lock(engineMutex_);
    GpuLutTextureManager::instance().clearCache();
    if (ahbTarget_) {
        ahbTarget_->release();
        ahbTarget_.reset();
    }
    if (inputTexture_) {
        inputTexture_->release();
        inputTexture_.reset();
    }
    if (program_) {
        program_->release();
        program_.reset();
    }
    shadersBuilt_ = false;
    LOGD("GpuColorPipeEngine released");
}

} // namespace gpu
} // namespace darkbag

#include "GpuRcdComputeEngine.h"
#include "GpuRcdShader.h"
#include "GpuContext.h"
#include <android/log.h>
#include <chrono>
#include <algorithm>
#include <vector>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

#define TAG "DarkbagGPU_RCD"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace gpu {

namespace {

GLuint compileComputeShader(const char* source) {
    GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
    if (!shader) {
        LOGE("glCreateShader(GL_COMPUTE_SHADER) failed: 0x%x", glGetError());
        return 0;
    }

    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
            LOGE("Compute shader compilation error:\n%s", infoLog.data());
        }
        glDeleteShader(shader);
        return 0;
    }

    GLuint program = glCreateProgram();
    if (!program) {
        LOGE("glCreateProgram failed: 0x%x", glGetError());
        glDeleteShader(shader);
        return 0;
    }

    glAttachShader(program, shader);
    glLinkProgram(program);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(infoLen);
            glGetProgramInfoLog(program, infoLen, nullptr, infoLog.data());
            LOGE("Compute program linking error:\n%s", infoLog.data());
        }
        glDeleteProgram(program);
        glDeleteShader(shader);
        return 0;
    }

    glDeleteShader(shader); // Marked for deletion upon program release
    return program;
}

} // namespace

GpuRcdComputeEngine& GpuRcdComputeEngine::instance() {
    static GpuRcdComputeEngine s_instance;
    return s_instance;
}

GpuRcdComputeEngine::~GpuRcdComputeEngine() {
    release();
}

bool GpuRcdComputeEngine::isAvailable() {
    GpuContext& ctx = GpuContext::instance();
    if (!ctx.isInitialized()) {
        if (!ctx.initialize()) {
            return false;
        }
    }
    return ctx.supportsComputeShader();
}

bool GpuRcdComputeEngine::ensureShaders() {
    if (shadersBuilt_ && programPassA_ != 0 && programPassB_ != 0) {
        return true;
    }

    LOGD("Compiling RCD Compute Shader Pass A & Pass B...");
    programPassA_ = compileComputeShader(kRcdPassAComputeShader);
    if (programPassA_ == 0) {
        LOGE("Failed to compile RCD Pass A Compute Shader");
        return false;
    }

    programPassB_ = compileComputeShader(kRcdPassBComputeShader);
    if (programPassB_ == 0) {
        LOGE("Failed to compile RCD Pass B Compute Shader");
        glDeleteProgram(programPassA_);
        programPassA_ = 0;
        return false;
    }

    shadersBuilt_ = true;
    LOGD("RCD Compute Shaders compiled and linked successfully (PassA=%u, PassB=%u)",
         programPassA_, programPassB_);
    return true;
}

void GpuRcdComputeEngine::releaseTextures() {
    if (bayerInputTex_ != 0) {
        glDeleteTextures(1, &bayerInputTex_);
        bayerInputTex_ = 0;
    }
    if (greenIntermTex_ != 0) {
        glDeleteTextures(1, &greenIntermTex_);
        greenIntermTex_ = 0;
    }
    if (outputTexRgb_ != 0) {
        glDeleteTextures(1, &outputTexRgb_);
        outputTexRgb_ = 0;
    }
    currentWidth_ = 0;
    currentHeight_ = 0;
}

bool GpuRcdComputeEngine::prepareTextures(int width, int height) {
    if (width <= 0 || height <= 0) return false;

    if (currentWidth_ != width || currentHeight_ != height) {
        releaseTextures(); // Only release textures when dimensions change
        currentWidth_ = width;
        currentHeight_ = height;
    }

    auto createTex = [&](GLuint& tex, GLenum internalFormat, GLenum format, GLenum type) -> bool {
        if (tex != 0) return true;
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, width, height);
        GLenum err = glGetError();
        if (err != GL_NO_ERROR) {
            LOGW("glTexStorage2D(0x%x) failed with 0x%x, fallback to glTexImage2D", internalFormat, err);
            glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, nullptr);
            err = glGetError();
            if (err != GL_NO_ERROR) {
                LOGE("glTexImage2D(0x%x) failed with 0x%x", internalFormat, err);
                return false;
            }
        }
        return true;
    };

    if (!createTex(bayerInputTex_, GL_R16UI, GL_RED_INTEGER, GL_UNSIGNED_SHORT) ||
        !createTex(greenIntermTex_, GL_R32F, GL_RED, GL_FLOAT) ||
        !createTex(outputTexRgb_, GL_RGBA16UI, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT)) {
        LOGE("Failed to allocate RCD GPU textures (%dx%d)", width, height);
        releaseTextures();
        return false;
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    LOGD("Allocated/Prepared RCD textures (%dx%d): Bayer=%u, Green=%u, OutRGB=%u",
         width, height, bayerInputTex_, greenIntermTex_, outputTexRgb_);
    return true;
}

bool GpuRcdComputeEngine::demosaicToRgbTextureLocked(
    const uint16_t* bayerData,
    int width, int height,
    int cfaPattern,
    const uint16_t* blackLevel,
    uint16_t whiteLevel,
    const float* whiteBalance,
    GLuint* outTexRgb,
    int64_t* outComputeMs
) {
    auto start = std::chrono::high_resolution_clock::now();

    if (!ensureShaders()) {
        return false;
    }

    if (!prepareTextures(width, height)) {
        return false;
    }

    // 1. Upload raw Bayer CFA to bayerInputTex_
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glBindTexture(GL_TEXTURE_2D, bayerInputTex_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, bayerData);
    glBindTexture(GL_TEXTURE_2D, 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

    float blR  = blackLevel ? static_cast<float>(blackLevel[0]) : 64.0f;
    float blG0 = blackLevel ? static_cast<float>(blackLevel[1]) : 64.0f;
    float blG1 = blackLevel ? static_cast<float>(blackLevel[2]) : 64.0f;
    float blB  = blackLevel ? static_cast<float>(blackLevel[3]) : 64.0f;
    float wl   = static_cast<float>(whiteLevel > 0 ? whiteLevel : 1023);

    int numGroupsX = (width + kRcdWorkgroupSizeX - 1) / kRcdWorkgroupSizeX;
    int numGroupsY = (height + kRcdWorkgroupSizeY - 1) / kRcdWorkgroupSizeY;

    // 2. Dispatch Pass A (Directional Gradients + Green Reconstruction)
    glUseProgram(programPassA_);
    glUniform1i(glGetUniformLocation(programPassA_, "uWidth"), width);
    glUniform1i(glGetUniformLocation(programPassA_, "uHeight"), height);
    glUniform1i(glGetUniformLocation(programPassA_, "uCfaPattern"), cfaPattern);
    glUniform4f(glGetUniformLocation(programPassA_, "uBlackLevel"), blR, blG0, blG1, blB);
    glUniform1f(glGetUniformLocation(programPassA_, "uWhiteLevel"), wl);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, bayerInputTex_);
    glUniform1i(glGetUniformLocation(programPassA_, "uBayerTex"), 0);

    // Bind greenIntermTex_ to image binding 0 (layout(r32f))
    glBindImageTexture(0, greenIntermTex_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);

    glDispatchCompute(numGroupsX, numGroupsY, 1);

    // Memory barrier: ensure Pass A image writes are visible for Pass B texture sampling
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

    // 3. Dispatch Pass B (Color Ratios + Red/Blue Recovery + RGBA16UI Output)
    glUseProgram(programPassB_);
    glUniform1i(glGetUniformLocation(programPassB_, "uWidth"), width);
    glUniform1i(glGetUniformLocation(programPassB_, "uHeight"), height);
    glUniform1i(glGetUniformLocation(programPassB_, "uCfaPattern"), cfaPattern);
    glUniform4f(glGetUniformLocation(programPassB_, "uBlackLevel"), blR, blG0, blG1, blB);
    glUniform1f(glGetUniformLocation(programPassB_, "uWhiteLevel"), wl);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, bayerInputTex_);
    glUniform1i(glGetUniformLocation(programPassB_, "uBayerTex"), 0);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, greenIntermTex_);
    glUniform1i(glGetUniformLocation(programPassB_, "uGreenTex"), 1);

    // Bind outputTexRgb_ to image binding 0 (layout(rgba16ui))
    glBindImageTexture(0, outputTexRgb_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16UI);

    glDispatchCompute(numGroupsX, numGroupsY, 1);

    // Memory barrier: ensure writes are visible to texture fetches AND framebuffer readback
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT);

    if (outTexRgb) {
        *outTexRgb = outputTexRgb_;
    }

    if (outComputeMs) {
        *outComputeMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - start
        ).count();
    }

    return true;
}

bool GpuRcdComputeEngine::demosaicToTextures(
    const uint16_t* bayerData,
    int width, int height,
    int cfaPattern,
    const uint16_t* blackLevel,
    uint16_t whiteLevel,
    const float* whiteBalance,
    GLuint* outTexR, GLuint* outTexG, GLuint* outTexB,
    int64_t* outComputeMs
) {
    if (!bayerData || width <= 0 || height <= 0 || !outTexR || !outTexG || !outTexB) {
        LOGE("Invalid arguments for demosaicToTextures");
        return false;
    }

    std::lock_guard<std::mutex> lock(engineMutex_);

    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("Failed to acquire EGL context for RCD Compute");
        return false;
    }

    if (!ctx.supportsComputeShader()) {
        LOGE("GLES Compute Shader is not supported on this device");
        return false;
    }

    GLuint texRgb = 0;
    bool ok = demosaicToRgbTextureLocked(
        bayerData, width, height, cfaPattern, blackLevel, whiteLevel, whiteBalance,
        &texRgb, outComputeMs
    );
    if (ok) {
        *outTexR = texRgb;
        *outTexG = texRgb;
        *outTexB = texRgb;
    }
    return ok;
}

bool GpuRcdComputeEngine::demosaicToRgbTexture(
    const uint16_t* bayerData,
    int width, int height,
    int cfaPattern,
    const uint16_t* blackLevel,
    uint16_t whiteLevel,
    const float* whiteBalance,
    GLuint* outTexRgb,
    int64_t* outComputeMs
) {
    if (!bayerData || width <= 0 || height <= 0 || !outTexRgb) {
        LOGE("Invalid arguments for demosaicToRgbTexture");
        return false;
    }

    std::lock_guard<std::mutex> lock(engineMutex_);

    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("Failed to acquire EGL context for RCD Compute");
        return false;
    }

    if (!ctx.supportsComputeShader()) {
        LOGE("GLES Compute Shader is not supported on this device");
        return false;
    }

    return demosaicToRgbTextureLocked(
        bayerData, width, height, cfaPattern, blackLevel, whiteLevel, whiteBalance,
        outTexRgb, outComputeMs
    );
}

GLuint GpuRcdComputeEngine::transferOutputTexture() {
    std::lock_guard<std::mutex> lock(engineMutex_);
    GLuint tex = outputTexRgb_;
    outputTexRgb_ = 0;
    return tex;
}

void GpuRcdComputeEngine::releaseTexture(GLuint texId) {
    if (texId == 0) return;
    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (ctxScope.isAcquired()) {
        glDeleteTextures(1, &texId);
        LOGD("GpuRcdComputeEngine::releaseTexture: deleted texture %u", texId);
    } else {
        LOGE("GpuRcdComputeEngine::releaseTexture: failed to acquire context for texture %u", texId);
    }
}

bool GpuRcdComputeEngine::readbackRgbTextureToCpu(
    GLuint texId,
    int width, int height,
    uint16_t* rgbOutput
) {
    if (texId == 0 || !rgbOutput || width <= 0 || height <= 0) {
        LOGE("Invalid arguments for readbackRgbTextureToCpu");
        return false;
    }

    std::lock_guard<std::mutex> lock(engineMutex_);

    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("Failed to acquire EGL context for texture readback");
        return false;
    }

    return readbackRgbTextureToCpuLocked(texId, width, height, rgbOutput);
}

bool GpuRcdComputeEngine::readbackRgbTextureToCpuLocked(
    GLuint texId,
    int width, int height,
    uint16_t* rgbOutput
) {
    const size_t numPixels = static_cast<size_t>(width) * height;

    // Attach unified RGBA16UI texture to FBO for single-pass fast readback
    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texId, 0);

    GLenum fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("FBO attachment incomplete for RGBA16UI: 0x%x", fboStatus);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
        return false;
    }

    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    std::vector<uint16_t> rgbaBuf(numPixels * 4);
    glReadPixels(0, 0, width, height, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, rgbaBuf.data());
    glPixelStorei(GL_PACK_ALIGNMENT, 4);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);

    // Deinterleave RGBA16 to planar [Plane R, Plane G, Plane B] with ARM NEON SIMD
    uint16_t* dstR = rgbOutput;
    uint16_t* dstG = rgbOutput + numPixels;
    uint16_t* dstB = rgbOutput + 2 * numPixels;
    const uint16_t* src = rgbaBuf.data();

#if defined(__ARM_NEON) || defined(__aarch64__)
    size_t i = 0;
    for (; i + 8 <= numPixels; i += 8) {
        uint16x8x4_t rgba = vld4q_u16(src + i * 4);
        vst1q_u16(dstR + i, rgba.val[0]);
        vst1q_u16(dstG + i, rgba.val[1]);
        vst1q_u16(dstB + i, rgba.val[2]);
    }
    for (; i < numPixels; ++i) {
        dstR[i] = src[i * 4 + 0];
        dstG[i] = src[i * 4 + 1];
        dstB[i] = src[i * 4 + 2];
    }
#else
    for (size_t i = 0; i < numPixels; ++i) {
        dstR[i] = src[i * 4 + 0];
        dstG[i] = src[i * 4 + 1];
        dstB[i] = src[i * 4 + 2];
    }
#endif

    return true;
}

bool GpuRcdComputeEngine::demosaicToCpuBuffer(
    const uint16_t* bayerData,
    int width, int height,
    int cfaPattern,
    const uint16_t* blackLevel,
    uint16_t whiteLevel,
    const float* whiteBalance,
    uint16_t* rgbOutput,
    int64_t* outComputeMs
) {
    if (!bayerData || !rgbOutput || width <= 0 || height <= 0) {
        LOGE("Invalid arguments for demosaicToCpuBuffer");
        return false;
    }

    std::lock_guard<std::mutex> lock(engineMutex_);

    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("Failed to acquire EGL context for RCD Compute readback");
        return false;
    }

    if (!ctx.supportsComputeShader()) {
        LOGE("GLES Compute Shader is not supported on this device");
        return false;
    }

    GLuint texRgb = 0;
    if (!demosaicToRgbTextureLocked(
            bayerData, width, height, cfaPattern, blackLevel, whiteLevel, whiteBalance,
            &texRgb, outComputeMs)) {
        return false;
    }

    return readbackRgbTextureToCpuLocked(texRgb, width, height, rgbOutput);
}

void GpuRcdComputeEngine::release() {
    releaseTextures();
    if (programPassA_ != 0) {
        glDeleteProgram(programPassA_);
        programPassA_ = 0;
    }
    if (programPassB_ != 0) {
        glDeleteProgram(programPassB_);
        programPassB_ = 0;
    }
    shadersBuilt_ = false;
    LOGD("GpuRcdComputeEngine released");
}

} // namespace gpu
} // namespace darkbag

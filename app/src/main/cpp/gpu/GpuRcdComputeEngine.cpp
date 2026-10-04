#include "GpuRcdComputeEngine.h"
#include "GpuRcdShader.h"
#include "GpuContext.h"
#include <android/log.h>
#include <chrono>
#include <algorithm>
#include <vector>

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
    if (outputTexR_ != 0) {
        glDeleteTextures(1, &outputTexR_);
        outputTexR_ = 0;
    }
    if (outputTexG_ != 0) {
        glDeleteTextures(1, &outputTexG_);
        outputTexG_ = 0;
    }
    if (outputTexB_ != 0) {
        glDeleteTextures(1, &outputTexB_);
        outputTexB_ = 0;
    }
    currentWidth_ = 0;
    currentHeight_ = 0;
}

bool GpuRcdComputeEngine::prepareTextures(int width, int height) {
    if (width <= 0 || height <= 0) return false;

    if (currentWidth_ == width && currentHeight_ == height &&
        bayerInputTex_ != 0 && greenIntermTex_ != 0 &&
        outputTexR_ != 0 && outputTexG_ != 0 && outputTexB_ != 0) {
        return true;
    }

    releaseTextures(); // Only release textures, preserve compiled compute shaders!

    currentWidth_ = width;
    currentHeight_ = height;

    auto createTex16UI = [&](GLuint& tex) -> bool {
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16UI, width, height);
        GLenum err = glGetError();
        if (err != GL_NO_ERROR) {
            LOGE("glTexStorage2D(GL_R16UI) failed with 0x%x, fallback to glTexImage2D", err);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R16UI, width, height, 0, GL_RED_INTEGER, GL_UNSIGNED_SHORT, nullptr);
            err = glGetError();
            if (err != GL_NO_ERROR) {
                LOGE("glTexImage2D(GL_R16UI) failed with 0x%x", err);
                return false;
            }
        }
        return true;
    };

    if (!createTex16UI(bayerInputTex_) ||
        !createTex16UI(greenIntermTex_) ||
        !createTex16UI(outputTexR_) ||
        !createTex16UI(outputTexG_) ||
        !createTex16UI(outputTexB_)) {
        LOGE("Failed to allocate RCD GPU textures (%dx%d)", width, height);
        releaseTextures();
        return false;
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    LOGD("Allocated RCD textures (%dx%d): Bayer=%u, Green=%u, OutRGB=[%u, %u, %u]",
         width, height, bayerInputTex_, greenIntermTex_, outputTexR_, outputTexG_, outputTexB_);
    return true;
}

bool GpuRcdComputeEngine::demosaicToTexturesLocked(
    const uint16_t* bayerData,
    int width, int height,
    int cfaPattern,
    const uint16_t* blackLevel,
    uint16_t whiteLevel,
    const float* whiteBalance,
    GLuint* outTexR, GLuint* outTexG, GLuint* outTexB,
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

    // Bind greenIntermTex_ to image binding 0
    glBindImageTexture(0, greenIntermTex_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R16UI);

    glDispatchCompute(numGroupsX, numGroupsY, 1);

    // Memory barrier: ensure Pass A image writes are visible for Pass B texture sampling
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

    // 3. Dispatch Pass B (Color Ratios + Red/Blue Recovery + Planar Outputs)
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

    // Bind 3 planar outputs to image bindings 0, 1, 2
    glBindImageTexture(0, outputTexR_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R16UI);
    glBindImageTexture(1, outputTexG_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R16UI);
    glBindImageTexture(2, outputTexB_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R16UI);

    glDispatchCompute(numGroupsX, numGroupsY, 1);

    // Memory barrier: ensure writes are visible to texture fetches AND framebuffer readback
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT);

    *outTexR = outputTexR_;
    *outTexG = outputTexG_;
    *outTexB = outputTexB_;

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

    return demosaicToTexturesLocked(
        bayerData, width, height, cfaPattern, blackLevel, whiteLevel, whiteBalance,
        outTexR, outTexG, outTexB, outComputeMs
    );
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

    GLuint texR = 0, texG = 0, texB = 0;
    if (!demosaicToTexturesLocked(
            bayerData, width, height, cfaPattern, blackLevel, whiteLevel, whiteBalance,
            &texR, &texG, &texB, outComputeMs)) {
        return false;
    }

    // Context is active and engineMutex_ is held throughout readback!
    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);

    size_t planePixels = static_cast<size_t>(width) * height;

    // Read Red channel
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texR, 0);
    glReadPixels(0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, rgbOutput);

    // Read Green channel
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texG, 0);
    glReadPixels(0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, rgbOutput + planePixels);

    // Read Blue channel
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texB, 0);
    glReadPixels(0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, rgbOutput + 2 * planePixels);

    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);

    return true;
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

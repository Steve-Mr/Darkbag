#include "GpuSabreEngine.h"
#include "GpuSabreShader.h"
#include "gpu/GpuContext.h"
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <vector>

#define TAG "DarkbagGPU_Sabre"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace sabre {

namespace {

GLuint compileComputeShader(const char* source) {
    GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
    if (!shader) {
        LOGE("glCreateShader failed: 0x%x", glGetError());
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
            LOGE("Compute shader compile error:\n%s", infoLog.data());
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
            LOGE("Compute program link error:\n%s", infoLog.data());
        }
        glDeleteProgram(program);
        glDeleteShader(shader);
        return 0;
    }

    glDeleteShader(shader);
    return program;
}

bool createTexStorage(GLuint& tex, GLenum internalFormat, int w, int h) {
    if (tex == 0) {
        glGenTextures(1, &tex);
    }
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, w, h);
    glBindTexture(GL_TEXTURE_2D, 0);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("glTexStorage2D failed (format=0x%x, %dx%d): 0x%x", internalFormat, w, h, err);
        return false;
    }
    return true;
}

} // namespace

GpuSabreEngine& GpuSabreEngine::instance() {
    static GpuSabreEngine s_instance;
    return s_instance;
}

GpuSabreEngine::~GpuSabreEngine() {
    releaseSession();
    releaseShaders();
}

bool GpuSabreEngine::isAvailable() const {
    darkbag::gpu::GpuContext& ctx = darkbag::gpu::GpuContext::instance();
    if (!ctx.isInitialized()) {
        if (!const_cast<darkbag::gpu::GpuContext&>(ctx).initialize()) {
            return false;
        }
    }
    return ctx.supportsComputeShader();
}

int GpuSabreEngine::framesAccumulated() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return framesAccumulated_;
}

bool GpuSabreEngine::isSessionActive() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return sessionActive_;
}

bool GpuSabreEngine::ensureShaders() {
    if (shadersBuilt_) return true;

    programStructureTensor_ = compileComputeShader(kSabreStructureTensorComputeShader);
    programAccumulate_ = compileComputeShader(kSabreAccumulateComputeShader);
    programResolve_ = compileComputeShader(kSabreResolveComputeShader);

    if (!programStructureTensor_ || !programAccumulate_ || !programResolve_) {
        LOGE("GpuSabreEngine: failed to build one or more compute shaders");
        releaseShaders();
        return false;
    }

    shadersBuilt_ = true;
    LOGD("GpuSabreEngine: all 3 compute shaders built successfully");
    return true;
}

void GpuSabreEngine::releaseShaders() {
    darkbag::gpu::GpuContext& ctx = darkbag::gpu::GpuContext::instance();
    darkbag::gpu::GpuContextScope ctxScope(ctx);

    if (programStructureTensor_ != 0) {
        glDeleteProgram(programStructureTensor_);
        programStructureTensor_ = 0;
    }
    if (programAccumulate_ != 0) {
        glDeleteProgram(programAccumulate_);
        programAccumulate_ = 0;
    }
    if (programResolve_ != 0) {
        glDeleteProgram(programResolve_);
        programResolve_ = 0;
    }
    shadersBuilt_ = false;
}

bool GpuSabreEngine::prepareTextures(int width, int height) {
    if (width == width_ && height == height_ && refBayerTex_ != 0) {
        return true;
    }

    releaseTextures();

    width_ = width;
    height_ = height;
    quadWidth_ = width / 2;
    quadHeight_ = height / 2;

    if (!createTexStorage(refBayerTex_, GL_R16UI, width_, height_) ||
        !createTexStorage(candBayerTex_, GL_R16UI, width_, height_) ||
        !createTexStorage(covTex_, GL_RGBA16F, quadWidth_, quadHeight_) ||
        !createTexStorage(accumTex_[0], GL_RGBA32F, width_, height_) ||
        !createTexStorage(accumTex_[1], GL_RGBA32F, width_, height_) ||
        !createTexStorage(weightTex_[0], GL_RGBA32F, width_, height_) ||
        !createTexStorage(weightTex_[1], GL_RGBA32F, width_, height_) ||
        !createTexStorage(outputRgbTex_, GL_RGBA16F, width_, height_)) {
        LOGE("GpuSabreEngine: failed to allocate storage textures");
        releaseTextures();
        return false;
    }

    // Allocate dummy 1x1 flow texture
    glGenTextures(1, &flowTex_);
    glBindTexture(GL_TEXTURE_2D, flowTex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RG32F, 1, 1);
    float zeroFlow[2] = {0.0f, 0.0f};
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RG, GL_FLOAT, zeroFlow);
    glBindTexture(GL_TEXTURE_2D, 0);
    flowTexWidth_ = 1;
    flowTexHeight_ = 1;

    LOGD("GpuSabreEngine: textures allocated successfully (%dx%d, quad=%dx%d)",
         width_, height_, quadWidth_, quadHeight_);
    return true;
}

void GpuSabreEngine::releaseTextures() {
    darkbag::gpu::GpuContext& ctx = darkbag::gpu::GpuContext::instance();
    darkbag::gpu::GpuContextScope ctxScope(ctx);

    if (refBayerTex_ != 0) { glDeleteTextures(1, &refBayerTex_); refBayerTex_ = 0; }
    if (candBayerTex_ != 0) { glDeleteTextures(1, &candBayerTex_); candBayerTex_ = 0; }
    if (covTex_ != 0) { glDeleteTextures(1, &covTex_); covTex_ = 0; }
    if (flowTex_ != 0) { glDeleteTextures(1, &flowTex_); flowTex_ = 0; }
    if (accumTex_[0] != 0) { glDeleteTextures(1, &accumTex_[0]); accumTex_[0] = 0; }
    if (accumTex_[1] != 0) { glDeleteTextures(1, &accumTex_[1]); accumTex_[1] = 0; }
    if (weightTex_[0] != 0) { glDeleteTextures(1, &weightTex_[0]); weightTex_[0] = 0; }
    if (weightTex_[1] != 0) { glDeleteTextures(1, &weightTex_[1]); weightTex_[1] = 0; }
    if (outputRgbTex_ != 0) { glDeleteTextures(1, &outputRgbTex_); outputRgbTex_ = 0; }

    flowTexWidth_ = 0;
    flowTexHeight_ = 0;
}

bool GpuSabreEngine::initSession(const SabreConfig& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!isAvailable()) {
        LOGE("GpuSabreEngine::initSession: GPU compute shaders not supported");
        return false;
    }

    darkbag::gpu::GpuContext& ctx = darkbag::gpu::GpuContext::instance();
    darkbag::gpu::GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("GpuSabreEngine::initSession: failed to acquire GPU context");
        return false;
    }

    if (!ensureShaders()) {
        LOGE("GpuSabreEngine::initSession: failed to build shaders");
        return false;
    }

    config_ = config;
    float z = std::max(1.0f, config_.zoomFactor);
    config_.zoomFactor = z;

    width_ = config.width;
    height_ = config.height;
    quadWidth_ = width_ / 2;
    quadHeight_ = height_ / 2;

    cropWidth_ = static_cast<float>(width_) / z;
    cropHeight_ = static_cast<float>(height_) / z;
    cropXStart_ = (static_cast<float>(width_) - cropWidth_) * 0.5f;
    cropYStart_ = (static_cast<float>(height_) - cropHeight_) * 0.5f;

    if (!prepareTextures(width_, height_)) {
        LOGE("GpuSabreEngine::initSession: failed to prepare textures");
        return false;
    }

    framesAccumulated_ = 0;
    accumIdx_ = 0;
    sessionActive_ = true;

    LOGD("GpuSabreEngine session initialized: %dx%d, CFA=%d, Zoom=%.2fx (crop: %.1fx%.1f at (%.1f, %.1f))",
         width_, height_, static_cast<int>(config_.cfa), z,
         cropWidth_, cropHeight_, cropXStart_, cropYStart_);
    return true;
}

bool GpuSabreEngine::setReferenceFrame(const uint16_t* refBayer) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!sessionActive_ || !refBayer) {
        LOGE("GpuSabreEngine::setReferenceFrame: session inactive or refBayer null");
        return false;
    }

    darkbag::gpu::GpuContext& ctx = darkbag::gpu::GpuContext::instance();
    darkbag::gpu::GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("GpuSabreEngine::setReferenceFrame: failed to acquire GPU context");
        return false;
    }

    // 1. Upload reference Bayer image to refBayerTex_
    glBindTexture(GL_TEXTURE_2D, refBayerTex_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, GL_RED_INTEGER, GL_UNSIGNED_SHORT, refBayer);
    glBindTexture(GL_TEXTURE_2D, 0);

    // 2. Dispatch Pass 1: Structure Tensor & Steering Covariance
    glUseProgram(programStructureTensor_);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, refBayerTex_);
    glUniform1i(glGetUniformLocation(programStructureTensor_, "uRefBayer"), 0);

    glBindImageTexture(0, covTex_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glUniform1i(glGetUniformLocation(programStructureTensor_, "uCovImg"), 0);

    glUniform1i(glGetUniformLocation(programStructureTensor_, "uQuadWidth"), quadWidth_);
    glUniform1i(glGetUniformLocation(programStructureTensor_, "uQuadHeight"), quadHeight_);
    glUniform1i(glGetUniformLocation(programStructureTensor_, "uCfaPattern"), static_cast<int>(config_.cfa));

    GLuint numGroupsX = (quadWidth_ + 15) / 16;
    GLuint numGroupsY = (quadHeight_ + 15) / 16;
    glDispatchCompute(numGroupsX, numGroupsY, 1);

    // Ensure covariance texture writes are visible to Pass 2
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

    // Explicitly unbind image and texture units
    glBindImageTexture(0, 0, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);

    // 3. Accumulate Frame 0 (zero optical flow)
    bool ok = accumulateFrameLocked(refBayer, nullptr, nullptr, 0, 0, /*isRef=*/true);
    LOGD("GpuSabreEngine: reference frame 0 set and accumulated (ok=%d)", ok);
    return ok;
}

bool GpuSabreEngine::accumulateFrame(
    const uint16_t* altBayer,
    const float* flowX,
    const float* flowY,
    int flowWidth,
    int flowHeight
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!sessionActive_ || !altBayer) {
        LOGE("GpuSabreEngine::accumulateFrame: session inactive or altBayer null");
        return false;
    }

    darkbag::gpu::GpuContext& ctx = darkbag::gpu::GpuContext::instance();
    darkbag::gpu::GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("GpuSabreEngine::accumulateFrame: failed to acquire GPU context");
        return false;
    }

    return accumulateFrameLocked(altBayer, flowX, flowY, flowWidth, flowHeight, /*isRef=*/false);
}

void GpuSabreEngine::updateFlowTexture(const float* flowX, const float* flowY, int flowWidth, int flowHeight) {
    if (!flowX || !flowY || flowWidth <= 0 || flowHeight <= 0) return;

    size_t count = static_cast<size_t>(flowWidth) * flowHeight;
    std::vector<float> flowBuf(count * 2);
    for (size_t i = 0; i < count; ++i) {
        flowBuf[i * 2 + 0] = flowX[i];
        flowBuf[i * 2 + 1] = flowY[i];
    }

    if (flowTex_ == 0 || flowTexWidth_ != flowWidth || flowTexHeight_ != flowHeight) {
        if (flowTex_ != 0) {
            glDeleteTextures(1, &flowTex_);
            flowTex_ = 0;
        }
        glGenTextures(1, &flowTex_);
        glBindTexture(GL_TEXTURE_2D, flowTex_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RG32F, flowWidth, flowHeight);
        flowTexWidth_ = flowWidth;
        flowTexHeight_ = flowHeight;
    } else {
        glBindTexture(GL_TEXTURE_2D, flowTex_);
    }

    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, flowWidth, flowHeight, GL_RG, GL_FLOAT, flowBuf.data());
    glBindTexture(GL_TEXTURE_2D, 0);
}

bool GpuSabreEngine::accumulateFrameLocked(
    const uint16_t* altBayer,
    const float* flowX,
    const float* flowY,
    int flowWidth,
    int flowHeight,
    bool isRef
) {
    // 1. Upload candidate Bayer data to candBayerTex_
    glBindTexture(GL_TEXTURE_2D, candBayerTex_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, GL_RED_INTEGER, GL_UNSIGNED_SHORT, altBayer);
    glBindTexture(GL_TEXTURE_2D, 0);

    // 2. Upload flow vectors if available
    bool hasFlow = (!isRef && flowX && flowY && flowWidth > 0 && flowHeight > 0);
    if (hasFlow) {
        updateFlowTexture(flowX, flowY, flowWidth, flowHeight);
    }

    // 3. Setup ping-pong texture targets (avoid aliasing on Frame 0)
    int inIdx = (framesAccumulated_ == 0) ? 1 : accumIdx_;
    int outIdx = (framesAccumulated_ == 0) ? 0 : (1 - accumIdx_);

    glUseProgram(programAccumulate_);

    // Texture unit 0: Reference Bayer
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, refBayerTex_);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uRefBayer"), 0);

    // Texture unit 1: Candidate Bayer
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, candBayerTex_);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uCandBayer"), 1);

    // Texture unit 2: Optical Flow
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, flowTex_);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uFlowTex"), 2);

    // Image unit 0: Steering Covariance (readonly, RGBA16F)
    glBindImageTexture(0, covTex_, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA16F);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uCovImg"), 0);

    // Image unit 1: Input Accumulation (readonly, RGBA32F)
    glBindImageTexture(1, accumTex_[inIdx], 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA32F);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uInAccumImg"), 1);

    // Image unit 2: Input Weight (readonly, RGBA32F)
    glBindImageTexture(2, weightTex_[inIdx], 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA32F);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uInWeightImg"), 2);

    // Image unit 3: Output Accumulation (writeonly, RGBA32F)
    glBindImageTexture(3, accumTex_[outIdx], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA32F);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uOutAccumImg"), 3);

    // Image unit 4: Output Weight (writeonly, RGBA32F)
    glBindImageTexture(4, weightTex_[outIdx], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA32F);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uOutWeightImg"), 4);

    // Set uniforms
    glUniform1i(glGetUniformLocation(programAccumulate_, "uWidth"), width_);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uHeight"), height_);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uSensorWidth"), width_);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uSensorHeight"), height_);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uQuadWidth"), quadWidth_);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uQuadHeight"), quadHeight_);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uCfaPattern"), static_cast<int>(config_.cfa));
    glUniform1f(glGetUniformLocation(programAccumulate_, "uZoomFactor"), config_.zoomFactor);
    glUniform1f(glGetUniformLocation(programAccumulate_, "uCropXStart"), cropXStart_);
    glUniform1f(glGetUniformLocation(programAccumulate_, "uCropYStart"), cropYStart_);
    glUniform2f(glGetUniformLocation(programAccumulate_, "uNoiseModel"), config_.noiseModelS, config_.noiseModelO);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uIsRef"), isRef ? 1 : 0);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uIsFirstFrame"), (framesAccumulated_ == 0) ? 1 : 0);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uHasFlow"), hasFlow ? 1 : 0);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uFlowWidth"), flowWidth);
    glUniform1i(glGetUniformLocation(programAccumulate_, "uFlowHeight"), flowHeight);

    GLuint numGroupsX = (width_ + 15) / 16;
    GLuint numGroupsY = (height_ + 15) / 16;
    glDispatchCompute(numGroupsX, numGroupsY, 1);

    // Ensure accumulation writes are finished before next step
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

    // Explicitly unbind all image units
    for (GLuint i = 0; i < 5; ++i) {
        glBindImageTexture(i, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA32F);
    }

    // Explicitly unbind all texture units
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);

    accumIdx_ = outIdx;
    framesAccumulated_++;
    LOGD("GpuSabreEngine: accumulated frame %d (isRef=%d, hasFlow=%d, outIdx=%d)",
         framesAccumulated_, isRef ? 1 : 0, hasFlow ? 1 : 0, outIdx);
    return true;
}

bool GpuSabreEngine::resolve(
    GLuint* outRgbTex,
    uint16_t* outCpuRgb,
    uint16_t* outCpuBayer,
    int64_t* outComputeMs
) {
    auto startTime = std::chrono::high_resolution_clock::now();
    std::lock_guard<std::mutex> lock(mutex_);
    if (!sessionActive_ || framesAccumulated_ == 0) {
        LOGE("GpuSabreEngine::resolve: inactive session or 0 frames accumulated");
        return false;
    }

    darkbag::gpu::GpuContext& ctx = darkbag::gpu::GpuContext::instance();
    darkbag::gpu::GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("GpuSabreEngine::resolve: failed to acquire GPU context");
        return false;
    }

    // Dispatch Pass 3: Resolve & Guided Color Difference Recovery
    glUseProgram(programResolve_);

    // Image unit 0: Input Accumulation (readonly, RGBA32F)
    glBindImageTexture(0, accumTex_[accumIdx_], 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA32F);
    glUniform1i(glGetUniformLocation(programResolve_, "uInAccumImg"), 0);

    // Image unit 1: Input Weight (readonly, RGBA32F)
    glBindImageTexture(1, weightTex_[accumIdx_], 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA32F);
    glUniform1i(glGetUniformLocation(programResolve_, "uInWeightImg"), 1);

    // Image unit 2: Output RGB Texture (writeonly, RGBA16F)
    glBindImageTexture(2, outputRgbTex_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glUniform1i(glGetUniformLocation(programResolve_, "uOutRgbImg"), 2);

    const float wl = static_cast<float>(config_.whiteLevel);
    const float bl_r = static_cast<float>(config_.blackLevel[0]);
    const float bl_g = 0.5f * (static_cast<float>(config_.blackLevel[1]) + static_cast<float>(config_.blackLevel[2]));
    const float bl_b = static_cast<float>(config_.blackLevel[3]);

    glUniform1i(glGetUniformLocation(programResolve_, "uWidth"), width_);
    glUniform1i(glGetUniformLocation(programResolve_, "uHeight"), height_);
    glUniform1f(glGetUniformLocation(programResolve_, "uWhiteLevel"), wl);
    glUniform4f(glGetUniformLocation(programResolve_, "uBlackLevel"), bl_r, bl_g, bl_b, 0.0f);

    GLuint numGroupsX = (width_ + 15) / 16;
    GLuint numGroupsY = (height_ + 15) / 16;
    glDispatchCompute(numGroupsX, numGroupsY, 1);

    // Memory barrier: texture fetch and framebuffer attachment barrier
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT);

    // Explicitly unbind image units
    glBindImageTexture(0, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA32F);
    glBindImageTexture(1, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA32F);
    glBindImageTexture(2, 0, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glUseProgram(0);

    if (outRgbTex) {
        *outRgbTex = outputRgbTex_;
    }

    // Optional CPU Readback
    if (outCpuRgb != nullptr || outCpuBayer != nullptr) {
        readbackRgbTextureLocked(outputRgbTex_, width_, height_, outCpuRgb, outCpuBayer);
    }

    if (outComputeMs) {
        *outComputeMs = static_cast<int64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - startTime
        ).count());
    }

    LOGD("GpuSabreEngine::resolve succeeded in %lld ms (%d frames, tex=%u, cpuRgb=%p, cpuBayer=%p)",
         outComputeMs ? (long long)*outComputeMs : 0LL, framesAccumulated_, outputRgbTex_, outCpuRgb, outCpuBayer);
    return true;
}

bool GpuSabreEngine::readbackRgbTextureLocked(
    GLuint texId,
    int width,
    int height,
    uint16_t* outCpuRgb,
    uint16_t* outCpuBayer
) {
    const size_t numPixels = static_cast<size_t>(width) * height;

    // Attach unified RGBA16F texture to FBO for single-pass readback
    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texId, 0);

    GLenum fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("GpuSabreEngine: FBO attachment incomplete for RGBA16F: 0x%x", fboStatus);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
        return false;
    }

    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    std::vector<float> rgbaBuf(numPixels * 4);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, rgbaBuf.data());
    glPixelStorei(GL_PACK_ALIGNMENT, 4);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);

    const float* src = rgbaBuf.data();

    // 1. Pack planar RGB (Channel 0: R, Channel 1: G, Channel 2: B) in [0, 65535]
    if (outCpuRgb) {
        uint16_t* dstR = outCpuRgb;
        uint16_t* dstG = outCpuRgb + numPixels;
        uint16_t* dstB = outCpuRgb + 2 * numPixels;

        #pragma omp parallel for schedule(static)
        for (size_t i = 0; i < numPixels; ++i) {
            dstR[i] = static_cast<uint16_t>(std::clamp(src[i * 4 + 0] * 65535.0f + 0.5f, 0.0f, 65535.0f));
            dstG[i] = static_cast<uint16_t>(std::clamp(src[i * 4 + 1] * 65535.0f + 0.5f, 0.0f, 65535.0f));
            dstB[i] = static_cast<uint16_t>(std::clamp(src[i * 4 + 2] * 65535.0f + 0.5f, 0.0f, 65535.0f));
        }
    }

    // 2. Synthesize Bayer CFA in original raw sensor range [0, whiteLevel]
    if (outCpuBayer) {
        const float wl = static_cast<float>(config_.whiteLevel);
        const float bl_r = static_cast<float>(config_.blackLevel[0]);
        const float bl_g = 0.5f * (static_cast<float>(config_.blackLevel[1]) + static_cast<float>(config_.blackLevel[2]));
        const float bl_b = static_cast<float>(config_.blackLevel[3]);

        #pragma omp parallel for schedule(static)
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                size_t idx = static_cast<size_t>(y) * width + x;
                int c = getBayerChannel(x, y);
                float normVal = (c == 0) ? src[idx * 4 + 0] : ((c == 1) ? src[idx * 4 + 1] : src[idx * 4 + 2]);
                float bl = (c == 0) ? bl_r : ((c == 1) ? bl_g : bl_b);
                float rawVal = normVal * (wl - bl) + bl;
                outCpuBayer[idx] = static_cast<uint16_t>(std::clamp(rawVal + 0.5f, 0.0f, wl));
            }
        }
    }

    return true;
}

int GpuSabreEngine::getBayerChannel(int x, int y) const {
    int px = x & 1;
    int py = y & 1;
    switch (config_.cfa) {
        case CfaPattern::RGGB:
            if (py == 0) return (px == 0) ? 0 : 1; // R, G
            else         return (px == 0) ? 1 : 2; // G, B
        case CfaPattern::GRBG:
            if (py == 0) return (px == 0) ? 1 : 0; // G, R
            else         return (px == 0) ? 2 : 1; // B, G
        case CfaPattern::GBRG:
            if (py == 0) return (px == 0) ? 1 : 2; // G, B
            else         return (px == 0) ? 0 : 1; // R, G
        case CfaPattern::BGGR:
            if (py == 0) return (px == 0) ? 2 : 1; // B, G
            else         return (px == 0) ? 1 : 0; // G, R
    }
    return 1;
}

GLuint GpuSabreEngine::transferOutputTexture() {
    std::lock_guard<std::mutex> lock(mutex_);
    GLuint tex = outputRgbTex_;
    outputRgbTex_ = 0;
    return tex;
}

void GpuSabreEngine::releaseTexture(GLuint texId) {
    if (texId == 0) return;
    darkbag::gpu::GpuContext& ctx = darkbag::gpu::GpuContext::instance();
    darkbag::gpu::GpuContextScope ctxScope(ctx);
    if (ctxScope.isAcquired()) {
        glDeleteTextures(1, &texId);
        LOGD("GpuSabreEngine::releaseTexture: deleted texture %u", texId);
    }
}

void GpuSabreEngine::releaseSession() {
    std::lock_guard<std::mutex> lock(mutex_);
    darkbag::gpu::GpuContext& ctx = darkbag::gpu::GpuContext::instance();
    darkbag::gpu::GpuContextScope ctxScope(ctx);
    releaseTextures();
    framesAccumulated_ = 0;
    sessionActive_ = false;
    LOGD("GpuSabreEngine: session released");
}

} // namespace sabre
} // namespace darkbag

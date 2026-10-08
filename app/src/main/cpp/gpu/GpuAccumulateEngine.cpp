#include "GpuAccumulateEngine.h"
#include "GpuAccumulateShader.h"
#include "GpuContext.h"
#include <android/log.h>
#include <chrono>
#include <algorithm>
#include <vector>

#define TAG "DarkbagGPU_Accum"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace gpu {

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

GpuAccumulateEngine& GpuAccumulateEngine::instance() {
    static GpuAccumulateEngine s_instance;
    return s_instance;
}

GpuAccumulateEngine::~GpuAccumulateEngine() {
    release();
}

bool GpuAccumulateEngine::isAvailable() {
    GpuContext& ctx = GpuContext::instance();
    if (!ctx.isInitialized()) {
        if (!ctx.initialize()) {
            return false;
        }
    }
    return ctx.supportsComputeShader();
}

bool GpuAccumulateEngine::ensureShaders() {
    if (shadersBuilt_) return true;

    programInit_ = compileComputeShader(kInitAccumulateComputeShader);
    programPyramid_ = compileComputeShader(kPyramidDownsampleComputeShader);
    programBlockMatching_ = compileComputeShader(kBlockMatchingComputeShader);
    programAccumulate_ = compileComputeShader(kTemporalAccumulateComputeShader);
    programNormalize_ = compileComputeShader(kAccumNormalizeComputeShader);

    if (!programInit_ || !programPyramid_ || !programBlockMatching_ ||
        !programAccumulate_ || !programNormalize_) {
        LOGE("Failed to build GpuAccumulateEngine compute shaders");
        return false;
    }

    shadersBuilt_ = true;
    LOGD("GpuAccumulateEngine: all 5 compute shaders compiled and linked successfully");
    return true;
}

bool GpuAccumulateEngine::prepareTextures(int width, int height) {
    if (width == width_ && height == height_ && refBayerTex_ != 0) {
        return true;
    }

    releaseTextures();

    width_ = width;
    height_ = height;
    pyrWidth_ = width / 2;
    pyrHeight_ = height / 2;
    mvWidth_ = (pyrWidth_ + 7) / 8;
    mvHeight_ = (pyrHeight_ + 7) / 8;

    if (!createTexStorage(refBayerTex_, GL_R16UI, width_, height_) ||
        !createTexStorage(candBayerTex_, GL_R16UI, width_, height_) ||
        !createTexStorage(refPyramidTex_, GL_R32UI, pyrWidth_, pyrHeight_) ||
        !createTexStorage(candPyramidTex_, GL_R32UI, pyrWidth_, pyrHeight_) ||
        !createTexStorage(motionVectorTex_, GL_R32I, mvWidth_, mvHeight_) ||
        !createTexStorage(accumValTex_[0], GL_R32F, width_, height_) ||
        !createTexStorage(accumValTex_[1], GL_R32F, width_, height_) ||
        !createTexStorage(accumWeightTex_[0], GL_R32F, width_, height_) ||
        !createTexStorage(accumWeightTex_[1], GL_R32F, width_, height_) ||
        !createTexStorage(normalizedBayerTex_, GL_R32UI, width_, height_)) {
        LOGE("Failed to allocate GPU accumulation textures (%dx%d)", width, height);
        releaseTextures();
        return false;
    }

    LOGD("GpuAccumulateEngine: prepared textures (%dx%d): pyr=%dx%d, mv=%dx%d",
         width_, height_, pyrWidth_, pyrHeight_, mvWidth_, mvHeight_);
    return true;
}

void GpuAccumulateEngine::releaseTextures() {
    releaseTempTextures();
    if (normalizedBayerTex_ != 0) {
        glDeleteTextures(1, &normalizedBayerTex_);
        normalizedBayerTex_ = 0;
    }
    width_ = height_ = 0;
}

void GpuAccumulateEngine::releaseTempTextures() {
    GLuint texs[] = {
        refBayerTex_, candBayerTex_, refPyramidTex_, candPyramidTex_,
        motionVectorTex_, accumValTex_[0], accumValTex_[1],
        accumWeightTex_[0], accumWeightTex_[1]
    };
    for (GLuint t : texs) {
        if (t != 0) glDeleteTextures(1, &t);
    }
    refBayerTex_ = candBayerTex_ = refPyramidTex_ = candPyramidTex_ = 0;
    motionVectorTex_ = accumValTex_[0] = accumValTex_[1] = 0;
    accumWeightTex_[0] = accumWeightTex_[1] = 0;
    LOGD("GpuAccumulateEngine: released temporary accumulation textures");
}

bool GpuAccumulateEngine::startSession(
    int width, int height,
    int cfaPattern,
    float noiseScale, float noiseOffset
) {
    std::lock_guard<std::mutex> lock(engineMutex_);

    if (sessionActive_) {
        LOGW("startSession: GPU accumulation session already active, rejecting new session");
        return false;
    }

    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired() || !ctx.supportsComputeShader()) {
        LOGE("startSession: cannot acquire EGL context or compute unsupported");
        return false;
    }

    if (!ensureShaders()) {
        return false;
    }

    if (!prepareTextures(width, height)) {
        return false;
    }

    cfaPattern_ = cfaPattern;
    noiseScale_ = noiseScale;
    noiseOffset_ = noiseOffset;
    framesPushed_ = 0;
    accumIdx_ = 0;
    sessionActive_ = true;

    LOGD("startSession: initialized GPU accumulation session (%dx%d, CFA=%d, noise=(%.5f, %.5f))",
         width, height, cfaPattern, noiseScale, noiseOffset);
    return true;
}

bool GpuAccumulateEngine::pushFrame(const uint16_t* rawData, size_t numPixels, int64_t* outPushMs) {
    if (!rawData || numPixels < static_cast<size_t>(width_) * height_) {
        LOGE("pushFrame: invalid raw buffer");
        return false;
    }

    auto start = std::chrono::high_resolution_clock::now();
    std::lock_guard<std::mutex> lock(engineMutex_);

    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("pushFrame: failed to acquire EGL context");
        return false;
    }

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    if (framesPushed_ == 0) {
        // Frame 0: Reference Frame
        glBindTexture(GL_TEXTURE_2D, refBayerTex_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, GL_RED_INTEGER, GL_UNSIGNED_SHORT, rawData);
        glBindTexture(GL_TEXTURE_2D, 0);

        // Pass 1: Downsample reference frame to pyramid
        glUseProgram(programPyramid_);
        glUniform1i(glGetUniformLocation(programPyramid_, "uSrcWidth"), width_);
        glUniform1i(glGetUniformLocation(programPyramid_, "uSrcHeight"), height_);
        glUniform1i(glGetUniformLocation(programPyramid_, "uCfaPattern"), cfaPattern_);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, refBayerTex_);
        glUniform1i(glGetUniformLocation(programPyramid_, "uBayerInput"), 0);

        glBindImageTexture(0, refPyramidTex_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32UI);

        glDispatchCompute((pyrWidth_ + 15) / 16, (pyrHeight_ + 15) / 16, 1);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Pass 0: Initialize accumulation buffer [0] with reference frame values and weight 1.0
        glUseProgram(programInit_);
        glUniform1i(glGetUniformLocation(programInit_, "uWidth"), width_);
        glUniform1i(glGetUniformLocation(programInit_, "uHeight"), height_);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, refBayerTex_);
        glUniform1i(glGetUniformLocation(programInit_, "uRefBayer"), 0);

        glBindImageTexture(0, accumValTex_[0], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        glBindImageTexture(1, accumWeightTex_[0], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);

        glDispatchCompute((width_ + 15) / 16, (height_ + 15) / 16, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Unbind image units
        glBindImageTexture(0, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32UI);
        glBindImageTexture(1, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);

        framesPushed_ = 1;
        accumIdx_ = 0;
    } else {
        // Frame 1..N-1: Candidate Alternate Frames
        glBindTexture(GL_TEXTURE_2D, candBayerTex_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, GL_RED_INTEGER, GL_UNSIGNED_SHORT, rawData);
        glBindTexture(GL_TEXTURE_2D, 0);

        // Pass 1: Downsample candidate frame to candidate pyramid
        glUseProgram(programPyramid_);
        glUniform1i(glGetUniformLocation(programPyramid_, "uSrcWidth"), width_);
        glUniform1i(glGetUniformLocation(programPyramid_, "uSrcHeight"), height_);
        glUniform1i(glGetUniformLocation(programPyramid_, "uCfaPattern"), cfaPattern_);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, candBayerTex_);
        glUniform1i(glGetUniformLocation(programPyramid_, "uBayerInput"), 0);

        glBindImageTexture(0, candPyramidTex_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32UI);

        glDispatchCompute((pyrWidth_ + 15) / 16, (pyrHeight_ + 15) / 16, 1);
        glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);

        // Pass 2: Block Matching Optical Flow
        glUseProgram(programBlockMatching_);
        glUniform1i(glGetUniformLocation(programBlockMatching_, "uWidth"), pyrWidth_);
        glUniform1i(glGetUniformLocation(programBlockMatching_, "uHeight"), pyrHeight_);
        glUniform1i(glGetUniformLocation(programBlockMatching_, "uSearchRadius"), 4);
        glUniform1f(glGetUniformLocation(programBlockMatching_, "uLambda"), 2.0f);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, refPyramidTex_);
        glUniform1i(glGetUniformLocation(programBlockMatching_, "uRefPyramid"), 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, candPyramidTex_);
        glUniform1i(glGetUniformLocation(programBlockMatching_, "uCandPyramid"), 1);

        glBindImageTexture(0, motionVectorTex_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32I);

        glDispatchCompute((mvWidth_ + 7) / 8, (mvHeight_ + 7) / 8, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Pass 3: Temporal Motion-Adaptive Accumulation
        int inIdx = accumIdx_;
        int outIdx = 1 - accumIdx_;

        glUseProgram(programAccumulate_);
        glUniform1i(glGetUniformLocation(programAccumulate_, "uWidth"), width_);
        glUniform1i(glGetUniformLocation(programAccumulate_, "uHeight"), height_);
        glUniform2f(glGetUniformLocation(programAccumulate_, "uNoiseModel"), noiseScale_, noiseOffset_);
        glUniform1f(glGetUniformLocation(programAccumulate_, "uGhostingSensitivity"), 1.0f);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, candBayerTex_);
        glUniform1i(glGetUniformLocation(programAccumulate_, "uCandBayer"), 0);

        glBindImageTexture(0, accumValTex_[inIdx], 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
        glBindImageTexture(1, accumWeightTex_[inIdx], 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
        glBindImageTexture(2, motionVectorTex_, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32I);
        glBindImageTexture(3, accumValTex_[outIdx], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
        glBindImageTexture(4, accumWeightTex_[outIdx], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);

        glDispatchCompute((width_ + 15) / 16, (height_ + 15) / 16, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Unbind image units
        for (int i = 0; i <= 4; ++i) {
            glBindImageTexture(i, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, 0);

        accumIdx_ = outIdx;
        framesPushed_++;
    }

    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glFlush();

    if (outPushMs) {
        *outPushMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - start
        ).count();
    }

    return true;
}

bool GpuAccumulateEngine::finish(
    uint16_t* outBayerCpu,
    GLuint* outNormalizedBayerTex,
    int64_t* outNormalizeMs
) {
    if (framesPushed_ <= 0) {
        LOGE("finish: no frames pushed");
        return false;
    }

    auto start = std::chrono::high_resolution_clock::now();
    std::lock_guard<std::mutex> lock(engineMutex_);

    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) {
        LOGE("finish: failed to acquire EGL context");
        return false;
    }

    // Pass 4: Normalization kernel
    glUseProgram(programNormalize_);
    glUniform1i(glGetUniformLocation(programNormalize_, "uWidth"), width_);
    glUniform1i(glGetUniformLocation(programNormalize_, "uHeight"), height_);

    glBindImageTexture(0, accumValTex_[accumIdx_], 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
    glBindImageTexture(1, accumWeightTex_[accumIdx_], 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
    glBindImageTexture(2, normalizedBayerTex_, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32UI);

    glDispatchCompute((width_ + 15) / 16, (height_ + 15) / 16, 1);
    glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT);

    // Unbind image units
    glBindImageTexture(0, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
    glBindImageTexture(1, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32F);
    glBindImageTexture(2, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32UI);

    if (outNormalizedBayerTex) {
        *outNormalizedBayerTex = normalizedBayerTex_;
    }

    // Optional CPU Readback if caller requested CPU Bayer buffer
    if (outBayerCpu) {
        GLuint fbo = 0;
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, normalizedBayerTex_, 0);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            std::vector<uint32_t> temp32(static_cast<size_t>(width_) * height_);
            glReadPixels(0, 0, width_, height_, GL_RED_INTEGER, GL_UNSIGNED_INT, temp32.data());
            const size_t numPixels = static_cast<size_t>(width_) * height_;
            for (size_t i = 0; i < numPixels; ++i) {
                outBayerCpu[i] = static_cast<uint16_t>(std::min(temp32[i], 65535u));
            }
        } else {
            LOGW("finish: FBO incomplete for CPU Bayer readback, skipping readback");
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
    }

    // Crucial: Release temporary accumulation buffers (~280MB VRAM) immediately
    // so Stage 1 RCD demosaic has ample GPU memory headroom!
    releaseTempTextures();

    glFlush();

    if (outNormalizeMs) {
        *outNormalizeMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now() - start
        ).count();
    }

    LOGD("GpuAccumulateEngine finish: normalized %d accumulated frames into texture %u (%lld ms)",
         framesPushed_, normalizedBayerTex_,
         outNormalizeMs ? (long long)*outNormalizeMs : 0LL);
    return true;
}

void GpuAccumulateEngine::endSession() {
    std::lock_guard<std::mutex> lock(engineMutex_);
    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    releaseTextures();
    sessionActive_ = false;
    framesPushed_ = 0;
    accumIdx_ = 0;
    LOGD("GpuAccumulateEngine: session ended, all textures released");
}

bool GpuAccumulateEngine::isSessionActive() const {
    return sessionActive_;
}

void GpuAccumulateEngine::reset() {
    std::lock_guard<std::mutex> lock(engineMutex_);
    sessionActive_ = false;
    framesPushed_ = 0;
    accumIdx_ = 0;
}

void GpuAccumulateEngine::release() {
    std::lock_guard<std::mutex> lock(engineMutex_);
    sessionActive_ = false;
    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (ctxScope.isAcquired()) {
        releaseTextures();
        if (programInit_ != 0) glDeleteProgram(programInit_);
        if (programPyramid_ != 0) glDeleteProgram(programPyramid_);
        if (programBlockMatching_ != 0) glDeleteProgram(programBlockMatching_);
        if (programAccumulate_ != 0) glDeleteProgram(programAccumulate_);
        if (programNormalize_ != 0) glDeleteProgram(programNormalize_);
        programInit_ = programPyramid_ = programBlockMatching_ = programAccumulate_ = programNormalize_ = 0;
        shadersBuilt_ = false;
    }
}

} // namespace gpu
} // namespace darkbag

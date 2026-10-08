#pragma once

#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <mutex>
#include <memory>
#include <cstdint>
#include <vector>
#include "SabreEngine.h"

namespace darkbag {
namespace sabre {

/**
 * GpuSabreEngine -- GPU-Accelerated Handheld Multi-Frame Super-Resolution Engine.
 *
 * Migrates continuous subpixel scatter accumulation and green-guided resolve
 * to OpenGL ES 3.1+ compute shaders operating in gather mode.
 * Accelerates 12MP full-frame processing from 2.5s+ on CPU down to <50ms on mobile GPUs.
 */
class GpuSabreEngine {
public:
    // Query whether GPU compute shaders and EGL context are available
    static bool isAvailable();

    explicit GpuSabreEngine(const SabreConfig& config);
    ~GpuSabreEngine();

    GpuSabreEngine(const GpuSabreEngine&) = delete;
    GpuSabreEngine& operator=(const GpuSabreEngine&) = delete;

    // Set and accumulate Reference Frame 0 (computes Structure Tensor and Steering Covariance)
    bool setReferenceFrame(const uint16_t* refBayer);

    // Accumulate an alternate frame (Frames 1..N-1) with optical flow displacement vectors
    bool accumulateFrame(
        const uint16_t* altBayer,
        const float* flowX = nullptr,
        const float* flowY = nullptr,
        int flowWidth = 0,
        int flowHeight = 0
    );

    // Resolve accumulated samples into normalized linear RGB texture (GL_RGBA16F).
    // Optionally downloads to CPU planar RGB and synthesized Bayer buffers.
    bool resolve(
        GLuint* outRgbTex,
        uint16_t* outCpuRgb = nullptr,
        uint16_t* outCpuBayer = nullptr,
        int64_t* outComputeMs = nullptr
    );

    // End session and release GPU textures owned by this instance
    void releaseSession();

    // Transfer ownership of output RGB texture to caller (for zero-copy Handover to GpuColorPipeEngine)
    GLuint transferOutputTexture();

    // Safely delete a GPU texture inside an active EGL context
    static void releaseTexture(GLuint texId);

    // Release cached intermediate textures in the texture pool
    static void clearTexturePool();

    int framesAccumulated() const;
    bool isSessionActive() const;

private:
    static bool ensureShaders();
    static void releaseShaders();

    bool prepareTextures(int width, int height);
    void releaseTextures();

    bool accumulateFrameLocked(
        const uint16_t* altBayer,
        const float* flowX,
        const float* flowY,
        int flowWidth,
        int flowHeight,
        bool isRef
    );

    void updateFlowTexture(const float* flowX, const float* flowY, int flowWidth, int flowHeight);

    bool readbackRgbTextureLocked(
        GLuint texId,
        int width,
        int height,
        uint16_t* outCpuRgb,
        uint16_t* outCpuBayer
    );

    int getBayerChannel(int x, int y) const;

    mutable std::mutex mutex_;
    SabreConfig config_;
    bool sessionActive_ = false;
    bool shadersAcquired_ = false;

    int width_ = 0;
    int height_ = 0;
    int quadWidth_ = 0;
    int quadHeight_ = 0;
    int framesAccumulated_ = 0;
    int accumIdx_ = 0;

    float cropXStart_ = 0.0f;
    float cropYStart_ = 0.0f;
    float cropWidth_ = 0.0f;
    float cropHeight_ = 0.0f;

    // Textures
    GLuint refBayerTex_ = 0;
    GLuint candBayerTex_ = 0;
    GLuint covTex_ = 0;
    GLuint flowTex_ = 0;
    int flowTexWidth_ = 0;
    int flowTexHeight_ = 0;
    GLuint accumTex_[2] = {0, 0};
    GLuint weightTex_[2] = {0, 0};
    GLuint outputRgbTex_ = 0;
    bool reusedFromCache_ = false;
};

} // namespace sabre
} // namespace darkbag

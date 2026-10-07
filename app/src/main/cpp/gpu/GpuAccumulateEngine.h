#pragma once

#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <mutex>
#include <memory>
#include <cstdint>
#include <vector>

namespace darkbag {
namespace gpu {

class GpuAccumulateEngine {
public:
    static GpuAccumulateEngine& instance();

    bool isAvailable();

    /**
     * Initializes a new streaming accumulation session.
     * Allocates or resizes GPU textures if dimensions changed.
     * Returns false if another GPU accumulation session is already active.
     */
    bool startSession(
        int width, int height,
        int cfaPattern,
        float noiseScale = 0.0001f,
        float noiseOffset = 0.00001f
    );

    /**
     * Ends the current streaming accumulation session and unlocks the engine.
     */
    void endSession();

    bool isSessionActive() const;

    /**
     * Pushes a raw 16-bit Bayer CFA frame into the accumulation pipeline.
     * Frame 0: uploaded as reference and initializes accumulation state.
     * Frame 1..N-1: aligned via optical flow and accumulated on GPU.
     */
    bool pushFrame(const uint16_t* rawData, size_t numPixels, int64_t* outPushMs = nullptr);

    /**
     * Normalizes accumulated values into normalized Bayer CFA texture.
     * Optionally reads back to CPU buffer if outBayerCpu is non-null.
     * Provides outNormalizedBayerTex for zero-copy demosaicing on GPU.
     */
    bool finish(
        uint16_t* outBayerCpu = nullptr,
        GLuint* outNormalizedBayerTex = nullptr,
        int64_t* outNormalizeMs = nullptr
    );

    /**
     * Resets current session state.
     */
    void reset();

    /**
     * Fully releases all GL programs and textures.
     */
    void release();

    int framesPushed() const { return framesPushed_; }
    int width() const { return width_; }
    int height() const { return height_; }
    int cfaPattern() const { return cfaPattern_; }

private:
    GpuAccumulateEngine() = default;
    ~GpuAccumulateEngine();
    GpuAccumulateEngine(const GpuAccumulateEngine&) = delete;
    GpuAccumulateEngine& operator=(const GpuAccumulateEngine&) = delete;

    bool ensureShaders();
    bool prepareTextures(int width, int height);
    void releaseTextures();
    void releaseTempTextures();

    std::mutex engineMutex_;
    bool shadersBuilt_ = false;

    // Shader Programs
    GLuint programInit_ = 0;
    GLuint programPyramid_ = 0;
    GLuint programBlockMatching_ = 0;
    GLuint programAccumulate_ = 0;
    GLuint programNormalize_ = 0;

    // Textures
    GLuint refBayerTex_ = 0;
    GLuint candBayerTex_ = 0;
    GLuint refPyramidTex_ = 0;
    GLuint candPyramidTex_ = 0;
    GLuint motionVectorTex_ = 0;
    GLuint accumValTex_[2] = {0, 0};
    GLuint accumWeightTex_[2] = {0, 0};
    GLuint normalizedBayerTex_ = 0;

    // Dimensions & Config
    int width_ = 0;
    int height_ = 0;
    int pyrWidth_ = 0;
    int pyrHeight_ = 0;
    int mvWidth_ = 0;
    int mvHeight_ = 0;
    int cfaPattern_ = 0;
    float noiseScale_ = 0.0001f;
    float noiseOffset_ = 0.00001f;

    int framesPushed_ = 0;
    int accumIdx_ = 0;
    bool sessionActive_ = false;
};

} // namespace gpu
} // namespace darkbag

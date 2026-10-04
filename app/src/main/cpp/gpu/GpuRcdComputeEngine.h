#pragma once

#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <mutex>
#include <memory>
#include <cstdint>

namespace darkbag {
namespace gpu {

class GpuRcdComputeEngine {
public:
    static GpuRcdComputeEngine& instance();

    bool isAvailable();

    /**
     * Executes GPU Compute RCD Demosaicing.
     * 
     * @param bayerData     Input 16-bit single-channel Bayer raw data
     * @param width         Width of the image in pixels
     * @param height        Height of the image in pixels
     * @param cfaPattern    0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
     * @param blackLevel    Black level array [r, g0, g1, b]
     * @param whiteLevel    White level
     * @param whiteBalance  White balance array [r, g0, g1, b] (or nullptr)
     * @param outTexR       Output GL_R16UI texture id for Red
     * @param outTexG       Output GL_R16UI texture id for Green
     * @param outTexB       Output GL_R16UI texture id for Blue
     * @param outComputeMs  Output elapsed time in milliseconds
     * @return true if demosaicing succeeded on GPU
     */
    bool demosaicToTextures(
        const uint16_t* bayerData,
        int width, int height,
        int cfaPattern,
        const uint16_t* blackLevel,
        uint16_t whiteLevel,
        const float* whiteBalance,
        GLuint* outTexR, GLuint* outTexG, GLuint* outTexB,
        int64_t* outComputeMs = nullptr
    );

    /**
     * Backwards-compatible overload: demosaics and downloads planar 16-bit RGB to CPU memory.
     */
    bool demosaicToCpuBuffer(
        const uint16_t* bayerData,
        int width, int height,
        int cfaPattern,
        const uint16_t* blackLevel,
        uint16_t whiteLevel,
        const float* whiteBalance,
        uint16_t* rgbOutput,
        int64_t* outComputeMs = nullptr
    );

    void release();

private:
    GpuRcdComputeEngine() = default;
    ~GpuRcdComputeEngine();
    GpuRcdComputeEngine(const GpuRcdComputeEngine&) = delete;
    GpuRcdComputeEngine& operator=(const GpuRcdComputeEngine&) = delete;

    bool ensureShaders();
    bool prepareTextures(int width, int height);

    std::mutex engineMutex_;
    bool shadersBuilt_ = false;

    GLuint programPassA_ = 0;
    GLuint programPassB_ = 0;

    GLuint bayerInputTex_ = 0;
    GLuint greenIntermTex_ = 0;
    GLuint outputTexR_ = 0;
    GLuint outputTexG_ = 0;
    GLuint outputTexB_ = 0;

    int currentWidth_ = 0;
    int currentHeight_ = 0;
};

} // namespace gpu
} // namespace darkbag

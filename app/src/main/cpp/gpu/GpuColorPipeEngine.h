#pragma once

#include <cstdint>
#include <string>
#include <memory>
#include <mutex>
#include "GpuContext.h"
#include "GpuProgram.h"
#include "GpuLutTextureManager.h"
#include "GpuInputTexture.h"
#include "AHardwareBufferManager.h"
#include "ColorPipe.h"

namespace darkbag {
namespace gpu {

class GpuColorPipeEngine {
public:
    static GpuColorPipeEngine& instance();

    // Query whether GPU offscreen acceleration is fully supported on this device
    bool isAvailable();

    // Main entry point for GPU color grading, 3D LUT simulation, and zero-copy JPEG encoding (Planar RGB)
    bool processAndSaveImage(
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
        int64_t* outColorPipeMs, int64_t* outJpegEncodeMs,
        const float* lensShadingMap = nullptr,
        int lensShadingRows = 0,
        int lensShadingCols = 0
    );

    // Direct zero-copy GPU-to-GPU entry point from unified GL_RGBA16UI texture
    bool processAndSaveImageFromTexture(
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
        int64_t* outColorPipeMs, int64_t* outJpegEncodeMs,
        const float* lensShadingMap = nullptr,
        int lensShadingRows = 0,
        int lensShadingCols = 0
    );

    void release();

private:
    GpuColorPipeEngine() = default;
    ~GpuColorPipeEngine();
    GpuColorPipeEngine(const GpuColorPipeEngine&) = delete;
    GpuColorPipeEngine& operator=(const GpuColorPipeEngine&) = delete;

    bool ensureShaders();

    bool executePipeline(
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
        int64_t* outColorPipeMs, int64_t* outJpegEncodeMs,
        const float* lensShadingMap = nullptr,
        int lensShadingRows = 0,
        int lensShadingCols = 0
    );

    std::mutex engineMutex_;
    std::unique_ptr<GpuProgram> program_;
    std::unique_ptr<GpuInputTexture> inputTexture_;
    std::unique_ptr<AHardwareBufferTarget> ahbTarget_;
    GLuint lscTexId_ = 0;
    bool shadersBuilt_ = false;
};

} // namespace gpu
} // namespace darkbag

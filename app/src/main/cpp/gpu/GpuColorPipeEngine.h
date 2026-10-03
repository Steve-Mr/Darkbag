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

    // Main entry point for GPU color grading, 3D LUT simulation, and zero-copy JPEG encoding
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
        int64_t* outColorPipeMs, int64_t* outJpegEncodeMs
    );

    void release();

private:
    GpuColorPipeEngine() = default;
    ~GpuColorPipeEngine();
    GpuColorPipeEngine(const GpuColorPipeEngine&) = delete;
    GpuColorPipeEngine& operator=(const GpuColorPipeEngine&) = delete;

    bool ensureShaders();

    std::mutex engineMutex_;
    std::unique_ptr<GpuProgram> program_;
    std::unique_ptr<GpuInputTexture> inputTexture_;
    std::unique_ptr<AHardwareBufferTarget> ahbTarget_;
    bool shadersBuilt_ = false;
};

} // namespace gpu
} // namespace darkbag

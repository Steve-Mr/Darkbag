#pragma once

#include <GLES3/gl3.h>
#include <string>
#include <unordered_map>
#include <memory>
#include "ColorPipe.h"

namespace darkbag {
namespace gpu {

class GpuLutTextureManager {
public:
    static GpuLutTextureManager& instance();

    // Fetches or creates a 3D texture for the specified LUT path or data.
    // If outLutSize is provided, it is populated with the cube size (e.g. 33).
    GLuint getOrCreateLutTexture(const std::string& lutPath, const LUT3D* fallbackLut = nullptr, int* outLutSize = nullptr);

    int getCurrentLutSize() const { return currentLutSize_; }

    void clearCache();

private:
    GpuLutTextureManager() = default;
    ~GpuLutTextureManager();
    GpuLutTextureManager(const GpuLutTextureManager&) = delete;
    GpuLutTextureManager& operator=(const GpuLutTextureManager&) = delete;

    GLuint uploadLut3D(const LUT3D& lut);

    struct CachedTexture {
        GLuint texId = 0;
        int lutSize = 0;
    };

    std::unordered_map<std::string, CachedTexture> cache_;
    std::string currentLutPath_;
    GLuint currentTexId_ = 0;
    int currentLutSize_ = 0;
};

} // namespace gpu
} // namespace darkbag

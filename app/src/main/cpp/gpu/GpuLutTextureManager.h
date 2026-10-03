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

    // Fetches or creates a 3D texture for the specified LUT path or data
    GLuint getOrCreateLutTexture(const std::string& lutPath, const LUT3D* fallbackLut = nullptr);

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

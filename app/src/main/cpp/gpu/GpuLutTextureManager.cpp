#include "GpuLutTextureManager.h"
#include <android/log.h>

#define TAG "DarkbagGPU_LUT"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace gpu {

GpuLutTextureManager& GpuLutTextureManager::instance() {
    static GpuLutTextureManager instance;
    return instance;
}

GpuLutTextureManager::~GpuLutTextureManager() {
    clearCache();
}

GLuint GpuLutTextureManager::uploadLut3D(const LUT3D& lut) {
    if (lut.size <= 1 || lut.data.empty()) {
        return 0;
    }

    size_t expectedCount = static_cast<size_t>(lut.size) * lut.size * lut.size;
    if (lut.data.size() < expectedCount) {
        LOGE("LUT3D data size mismatch: expected %zu, got %zu", expectedCount, lut.data.size());
        return 0;
    }

    GLuint texId = 0;
    glGenTextures(1, &texId);
    glBindTexture(GL_TEXTURE_3D, texId);

    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

    // Upload as GL_RGB16F or GL_RGB (3-channel float)
    glTexImage3D(
        GL_TEXTURE_3D,
        0,
        GL_RGB16F,
        lut.size,
        lut.size,
        lut.size,
        0,
        GL_RGB,
        GL_FLOAT,
        lut.data.data()
    );

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGW("glTexImage3D with GL_RGB16F failed (0x%x), attempting fallback to GL_RGB8", err);
        // Fallback: convert to 8-bit RGB if 16F texture is not supported on driver
        std::vector<uint8_t> rgb8Data(expectedCount * 3);
        for (size_t i = 0; i < expectedCount; ++i) {
            rgb8Data[i * 3 + 0] = static_cast<uint8_t>(std::clamp(lut.data[i].r * 255.0f + 0.5f, 0.0f, 255.0f));
            rgb8Data[i * 3 + 1] = static_cast<uint8_t>(std::clamp(lut.data[i].g * 255.0f + 0.5f, 0.0f, 255.0f));
            rgb8Data[i * 3 + 2] = static_cast<uint8_t>(std::clamp(lut.data[i].b * 255.0f + 0.5f, 0.0f, 255.0f));
        }
        glTexImage3D(
            GL_TEXTURE_3D,
            0,
            GL_RGB8,
            lut.size,
            lut.size,
            lut.size,
            0,
            GL_RGB,
            GL_UNSIGNED_BYTE,
            rgb8Data.data()
        );
    }

    glBindTexture(GL_TEXTURE_3D, 0);
    LOGD("Uploaded 3D LUT texture id=%u, size=%d^3", texId, lut.size);
    return texId;
}

GLuint GpuLutTextureManager::getOrCreateLutTexture(const std::string& lutPath, const LUT3D* fallbackLut) {
    if (lutPath.empty() && (!fallbackLut || fallbackLut->size <= 1)) {
        return 0;
    }

    // Fast path: if requesting the current active LUT
    if (!lutPath.empty() && lutPath == currentLutPath_ && currentTexId_ != 0) {
        return currentTexId_;
    }

    // Cache check
    if (!lutPath.empty()) {
        auto it = cache_.find(lutPath);
        if (it != cache_.end() && it->second.texId != 0) {
            currentLutPath_ = lutPath;
            currentTexId_ = it->second.texId;
            currentLutSize_ = it->second.lutSize;
            return currentTexId_;
        }
    }

    // Load or resolve LUT data
    std::shared_ptr<LUT3D> resolvedLut;
    if (!lutPath.empty()) {
        resolvedLut = get_cached_lut(lutPath.c_str());
    }

    const LUT3D* targetLut = resolvedLut ? resolvedLut.get() : fallbackLut;
    if (!targetLut || targetLut->size <= 1 || targetLut->data.empty()) {
        return 0;
    }

    GLuint texId = uploadLut3D(*targetLut);
    if (texId != 0 && !lutPath.empty()) {
        cache_[lutPath] = {texId, targetLut->size};
        currentLutPath_ = lutPath;
        currentTexId_ = texId;
        currentLutSize_ = targetLut->size;
    }
    return texId;
}

void GpuLutTextureManager::clearCache() {
    for (auto& pair : cache_) {
        if (pair.second.texId != 0) {
            glDeleteTextures(1, &pair.second.texId);
        }
    }
    cache_.clear();
    currentLutPath_.clear();
    currentTexId_ = 0;
    currentLutSize_ = 0;
    LOGD("Cleared 3D LUT texture cache");
}

} // namespace gpu
} // namespace darkbag

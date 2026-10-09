#include "GpuInputTexture.h"
#include "GpuContext.h"
#include <android/log.h>
#include <vector>

#define TAG "DarkbagGPU_InputTex"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace gpu {

namespace {

void createOrResizeTexture(GLuint& texId, int width, int height, bool resize) {
    if (texId == 0) {
        glGenTextures(1, &texId);
    }
    glBindTexture(GL_TEXTURE_2D, texId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    if (resize) {
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_R16UI,
            width,
            height,
            0,
            GL_RED_INTEGER,
            GL_UNSIGNED_SHORT,
            nullptr
        );
    }
}

} // namespace

GpuInputTexture::GpuInputTexture() = default;

GpuInputTexture::~GpuInputTexture() {
    release();
}

bool GpuInputTexture::uploadPlanarRgb(
    const uint16_t* planarRgb,
    int width,
    int height,
    int stride_x,
    int stride_y,
    int stride_c
) {
    if (!planarRgb || width <= 0 || height <= 0) {
        LOGE("Invalid arguments for uploadPlanarRgb");
        return false;
    }

    if (stride_y == 0) stride_y = width;
    if (stride_c == 0) stride_c = width * height;

    bool needsResize = (width_ != width || height_ != height);
    if (needsResize) {
        createOrResizeTexture(texR_, width, height, true);
        createOrResizeTexture(texG_, width, height, true);
        createOrResizeTexture(texB_, width, height, true);
        width_ = width;
        height_ = height;
    }

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    // Fast path: fully contiguous planar layout (stride_x == 1 && stride_y == width)
    if (stride_x == 1 && stride_y == width) {
        const uint16_t* ptrR = planarRgb;
        const uint16_t* ptrG = planarRgb + stride_c;
        const uint16_t* ptrB = planarRgb + 2 * stride_c;

        glBindTexture(GL_TEXTURE_2D, texR_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, ptrR);

        glBindTexture(GL_TEXTURE_2D, texG_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, ptrG);

        glBindTexture(GL_TEXTURE_2D, texB_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, ptrB);
    } else {
        // Slow path: pack strided data into contiguous channel buffers
        size_t total = static_cast<size_t>(width) * height;
        std::vector<uint16_t> bufR(total), bufG(total), bufB(total);

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                size_t srcIdx = static_cast<size_t>(y) * stride_y + static_cast<size_t>(x) * stride_x;
                size_t dstIdx = static_cast<size_t>(y) * width + x;
                bufR[dstIdx] = planarRgb[srcIdx];
                bufG[dstIdx] = planarRgb[srcIdx + stride_c];
                bufB[dstIdx] = planarRgb[srcIdx + 2 * stride_c];
            }
        }

        glBindTexture(GL_TEXTURE_2D, texR_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, bufR.data());

        glBindTexture(GL_TEXTURE_2D, texG_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, bufG.data());

        glBindTexture(GL_TEXTURE_2D, texB_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RED_INTEGER, GL_UNSIGNED_SHORT, bufB.data());
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

void GpuInputTexture::bind(GLint locR, GLint locG, GLint locB, int baseUnit) {
    if (locR >= 0 && texR_ != 0) {
        glActiveTexture(GL_TEXTURE0 + baseUnit);
        glBindTexture(GL_TEXTURE_2D, texR_);
        glUniform1i(locR, baseUnit);
    }
    if (locG >= 0 && texG_ != 0) {
        glActiveTexture(GL_TEXTURE0 + baseUnit + 1);
        glBindTexture(GL_TEXTURE_2D, texG_);
        glUniform1i(locG, baseUnit + 1);
    }
    if (locB >= 0 && texB_ != 0) {
        glActiveTexture(GL_TEXTURE0 + baseUnit + 2);
        glBindTexture(GL_TEXTURE_2D, texB_);
        glUniform1i(locB, baseUnit + 2);
    }
}

void GpuInputTexture::release() {
    GpuContext& ctx = GpuContext::instance();
    GpuContextScope ctxScope(ctx);
    if (!ctxScope.isAcquired()) return;
    if (texR_ != 0) { glDeleteTextures(1, &texR_); texR_ = 0; }
    if (texG_ != 0) { glDeleteTextures(1, &texG_); texG_ = 0; }
    if (texB_ != 0) { glDeleteTextures(1, &texB_); texB_ = 0; }
    width_ = 0;
    height_ = 0;
}

} // namespace gpu
} // namespace darkbag

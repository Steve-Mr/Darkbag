#include "AHardwareBufferManager.h"
#include "GpuContext.h"
#include <android/log.h>

#define TAG "DarkbagGPU_AHB"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace gpu {

AHardwareBufferTarget::AHardwareBufferTarget() = default;

AHardwareBufferTarget::~AHardwareBufferTarget() {
    release();
}

void AHardwareBufferTarget::release() {
    if (isLocked_ && buffer_) {
        AHardwareBuffer_unlock(buffer_, nullptr);
        isLocked_ = false;
    }

    GpuContext& ctx = GpuContext::instance();
    if (eglImage_ != EGL_NO_IMAGE_KHR) {
        if (ctx.fnDestroyImageKHR) {
            ctx.fnDestroyImageKHR(ctx.getDisplay(), eglImage_);
        }
        eglImage_ = EGL_NO_IMAGE_KHR;
    }

    if (texture_ != 0) {
        glDeleteTextures(1, &texture_);
        texture_ = 0;
    }

    if (fbo_ != 0) {
        glDeleteFramebuffers(1, &fbo_);
        fbo_ = 0;
    }

    if (buffer_ != nullptr) {
        AHardwareBuffer_release(buffer_);
        buffer_ = nullptr;
    }

    width_ = 0;
    height_ = 0;
    strideInPixels_ = 0;
}

bool AHardwareBufferTarget::prepare(int width, int height) {
    if (width <= 0 || height <= 0) {
        LOGE("Invalid dimensions for AHardwareBuffer: %dx%d", width, height);
        return false;
    }

    // Reuse existing buffer and FBO if dimensions already match
    if (buffer_ != nullptr && width_ == width && height_ == height && fbo_ != 0) {
        return true;
    }

    release();

    GpuContext& ctx = GpuContext::instance();
    if (!ctx.supportsAHardwareBuffer()) {
        LOGE("AHardwareBuffer extensions are not available on this device");
        return false;
    }

    AHardwareBuffer_Desc desc = {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;

    int status = AHardwareBuffer_allocate(&desc, &buffer_);
    if (status != 0 || buffer_ == nullptr) {
        LOGE("AHardwareBuffer_allocate failed with status %d", status);
        return false;
    }

    AHardwareBuffer_describe(buffer_, &desc);
    strideInPixels_ = static_cast<int>(desc.stride);
    width_ = width;
    height_ = height;

    LOGD("Allocated AHardwareBuffer: %dx%d (stride=%d pixels)", width_, height_, strideInPixels_);

    EGLClientBuffer clientBuf = ctx.fnGetNativeClientBufferANDROID(buffer_);
    if (!clientBuf) {
        LOGE("eglGetNativeClientBufferANDROID returned nullptr");
        release();
        return false;
    }

    const EGLint eglImgAttrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };

    eglImage_ = ctx.fnCreateImageKHR(
        ctx.getDisplay(),
        EGL_NO_CONTEXT,
        EGL_NATIVE_BUFFER_ANDROID,
        clientBuf,
        eglImgAttrs
    );

    if (eglImage_ == EGL_NO_IMAGE_KHR) {
        LOGE("eglCreateImageKHR failed: 0x%x", eglGetError());
        release();
        return false;
    }

    // Create FBO & color texture attachment
    glGenTextures(1, &texture_);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    ctx.fnGlEGLImageTargetTexture2DOES(GL_TEXTURE_2D, eglImage_);

    glGenFramebuffers(1, &fbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture_, 0);

    GLenum fboStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (fboStatus != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("glCheckFramebufferStatus failed: 0x%x", fboStatus);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        release();
        return false;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

bool AHardwareBufferTarget::bindFbo() {
    if (fbo_ == 0) return false;
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glViewport(0, 0, width_, height_);
    return true;
}

bool AHardwareBufferTarget::waitGpuFinish() {
    GpuContext& ctx = GpuContext::instance();
    glFlush();

    if (ctx.fnCreateSyncKHR && ctx.fnClientWaitSyncKHR && ctx.fnDestroySyncKHR) {
        EGLSyncKHR sync = ctx.fnCreateSyncKHR(ctx.getDisplay(), EGL_SYNC_FENCE_KHR, nullptr);
        if (sync != EGL_NO_SYNC_KHR) {
            // Wait up to 1 second for GPU to finish rendering
            EGLint waitResult = ctx.fnClientWaitSyncKHR(
                ctx.getDisplay(),
                sync,
                EGL_SYNC_FLUSH_COMMANDS_BIT_KHR,
                1000000000ULL // 1 sec timeout
            );
            ctx.fnDestroySyncKHR(ctx.getDisplay(), sync);
            if (waitResult == EGL_CONDITION_SATISFIED_KHR) {
                return true;
            }
            LOGW("fnClientWaitSyncKHR timeout or error: 0x%x, falling back to glFinish", waitResult);
        }
    }

    // Safe fallback to synchronous wait
    glFinish();
    return true;
}

bool AHardwareBufferTarget::lockRead(void** outVirtualAddr, int* outStrideInPixels) {
    if (!buffer_ || !outVirtualAddr) return false;

    if (isLocked_) {
        AHardwareBuffer_unlock(buffer_, nullptr);
        isLocked_ = false;
    }

    int status = AHardwareBuffer_lock(
        buffer_,
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
        -1, // No fence, already synchronized via EGLSyncKHR
        nullptr,
        outVirtualAddr
    );

    if (status != 0 || *outVirtualAddr == nullptr) {
        LOGE("AHardwareBuffer_lock failed with status %d", status);
        return false;
    }

    isLocked_ = true;
    if (outStrideInPixels) {
        *outStrideInPixels = strideInPixels_;
    }
    return true;
}

void AHardwareBufferTarget::unlock() {
    if (isLocked_ && buffer_) {
        AHardwareBuffer_unlock(buffer_, nullptr);
        isLocked_ = false;
    }
}

} // namespace gpu
} // namespace darkbag

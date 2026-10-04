#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <GLES3/gl3ext.h>
#include <GLES2/gl2ext.h>
#include <mutex>
#include <memory>

namespace darkbag {
namespace gpu {

// Function pointer types for Android & EGL KHR extensions
using PFNEglGetNativeClientBufferANDROID = EGLClientBuffer (*)(const struct AHardwareBuffer*);
using PFNEglCreateImageKHR = EGLImageKHR (*)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint*);
using PFNEglDestroyImageKHR = EGLBoolean (*)(EGLDisplay, EGLImageKHR);
using PFNGlEGLImageTargetTexture2DOES = void (*)(GLenum, GLeglImageOES);
using PFNEglCreateSyncKHR = EGLSyncKHR (*)(EGLDisplay, EGLenum, const EGLint*);
using PFNEglDestroySyncKHR = EGLBoolean (*)(EGLDisplay, EGLSyncKHR);
using PFNEglClientWaitSyncKHR = EGLint (*)(EGLDisplay, EGLSyncKHR, EGLint, EGLTimeKHR);

class GpuContext {
public:
    static GpuContext& instance();

    bool initialize();
    bool makeCurrent();
    void doneCurrent();
    void release();

    bool isInitialized() const { return initialized_; }
    bool supportsAHardwareBuffer() const { return hasAhbSupport_; }
    bool supportsComputeShader() const { return hasComputeSupport_; }

    int getGlMajorVersion() const { return glMajorVersion_; }
    int getGlMinorVersion() const { return glMinorVersion_; }
    int getMaxWorkGroupInvocations() const { return maxWorkGroupInvocations_; }

    EGLDisplay getDisplay() const { return eglDisplay_; }
    EGLContext getContext() const { return eglContext_; }

    // Extension entry points
    PFNEglGetNativeClientBufferANDROID fnGetNativeClientBufferANDROID = nullptr;
    PFNEglCreateImageKHR fnCreateImageKHR = nullptr;
    PFNEglDestroyImageKHR fnDestroyImageKHR = nullptr;
    PFNGlEGLImageTargetTexture2DOES fnGlEGLImageTargetTexture2DOES = nullptr;
    PFNEglCreateSyncKHR fnCreateSyncKHR = nullptr;
    PFNEglDestroySyncKHR fnDestroySyncKHR = nullptr;
    PFNEglClientWaitSyncKHR fnClientWaitSyncKHR = nullptr;

    std::recursive_mutex& getMutex() { return contextMutex_; }

private:
    GpuContext() = default;
    ~GpuContext();
    GpuContext(const GpuContext&) = delete;
    GpuContext& operator=(const GpuContext&) = delete;

    bool initExtensions();

    std::recursive_mutex contextMutex_;
    bool initialized_ = false;
    bool hasAhbSupport_ = false;
    bool hasComputeSupport_ = false;
    int glMajorVersion_ = 0;
    int glMinorVersion_ = 0;
    int maxWorkGroupInvocations_ = 0;

    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLConfig  eglConfig_  = nullptr;
};

// RAII Scope Lock for GpuContext activation
class GpuContextScope {
public:
    explicit GpuContextScope(GpuContext& ctx)
        : ctx_(ctx), lock_(ctx.getMutex()), locked_(false) {
        if (ctx_.makeCurrent()) {
            locked_ = true;
        }
    }

    ~GpuContextScope() {
        if (locked_) {
            ctx_.doneCurrent();
        }
    }

    bool isAcquired() const { return locked_; }

private:
    GpuContext& ctx_;
    std::unique_lock<std::recursive_mutex> lock_;
    bool locked_;
};

} // namespace gpu
} // namespace darkbag

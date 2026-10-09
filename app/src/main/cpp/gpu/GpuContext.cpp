#include "GpuContext.h"
#include <android/log.h>

#define TAG "DarkbagGPU_Context"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace gpu {

GpuContext& GpuContext::instance() {
    static GpuContext instance;
    return instance;
}

GpuContext::~GpuContext() {
    release();
}

bool GpuContext::initialize() {
    std::lock_guard<std::recursive_mutex> lock(contextMutex_);
    if (initialized_) {
        return true;
    }

    LOGD("Initializing headless EGL display & GLES3 context...");

    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed: 0x%x", eglGetError());
        return false;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(eglDisplay_, &major, &minor)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        eglDisplay_ = EGL_NO_DISPLAY;
        return false;
    }
    LOGD("EGL initialized: version %d.%d", major, minor);

    // Config for off-screen rendering
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    if (!eglChooseConfig(eglDisplay_, configAttribs, &eglConfig_, 1, &numConfigs) || numConfigs <= 0) {
        LOGE("eglChooseConfig failed: 0x%x, numConfigs: %d", eglGetError(), numConfigs);
        release();
        return false;
    }

    // Create 1x1 Pbuffer surface as anchor for off-screen rendering
    const EGLint pbufferAttribs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    eglSurface_ = eglCreatePbufferSurface(eglDisplay_, eglConfig_, pbufferAttribs);
    if (eglSurface_ == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
        release();
        return false;
    }

    // Request OpenGL ES 3.1 context for Compute Shader support, with ES 3.0 fallback
    const EGLint contextAttribs31[] = {
        EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
        EGL_CONTEXT_MINOR_VERSION_KHR, 1,
        EGL_NONE
    };
    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs31);
    if (eglContext_ == EGL_NO_CONTEXT) {
        const EGLint contextAttribs30[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
        };
        eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs30);
    }
    if (eglContext_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        release();
        return false;
    }

    // Temporarily make current to query extensions and GL strings
    if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
        LOGE("eglMakeCurrent failed during initialization: 0x%x", eglGetError());
        release();
        return false;
    }

    const char* glVendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    const char* glRenderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* glVersion = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    LOGD("GL Context successfully created. Vendor: %s, Renderer: %s, Version: %s",
         glVendor ? glVendor : "Unknown",
         glRenderer ? glRenderer : "Unknown",
         glVersion ? glVersion : "Unknown");

    initExtensions();

    // Release context from current thread until explicit use
    eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    initialized_ = true;
    return true;
}

bool GpuContext::initExtensions() {
    fnGetNativeClientBufferANDROID = reinterpret_cast<PFNEglGetNativeClientBufferANDROID>(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    fnCreateImageKHR = reinterpret_cast<PFNEglCreateImageKHR>(
        eglGetProcAddress("eglCreateImageKHR"));
    fnDestroyImageKHR = reinterpret_cast<PFNEglDestroyImageKHR>(
        eglGetProcAddress("eglDestroyImageKHR"));
    fnGlEGLImageTargetTexture2DOES = reinterpret_cast<PFNGlEGLImageTargetTexture2DOES>(
        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    fnCreateSyncKHR = reinterpret_cast<PFNEglCreateSyncKHR>(
        eglGetProcAddress("eglCreateSyncKHR"));
    fnDestroySyncKHR = reinterpret_cast<PFNEglDestroySyncKHR>(
        eglGetProcAddress("eglDestroySyncKHR"));
    fnClientWaitSyncKHR = reinterpret_cast<PFNEglClientWaitSyncKHR>(
        eglGetProcAddress("eglClientWaitSyncKHR"));

    hasAhbSupport_ = (fnGetNativeClientBufferANDROID != nullptr &&
                      fnCreateImageKHR != nullptr &&
                      fnDestroyImageKHR != nullptr &&
                      fnGlEGLImageTargetTexture2DOES != nullptr);

    GLint major = 0, minor = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    glGetIntegerv(GL_MINOR_VERSION, &minor);
    glMajorVersion_ = major;
    glMinorVersion_ = minor;
    bool glesCompute = (major > 3 || (major == 3 && minor >= 1));
    if (glesCompute) {
        glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, &maxWorkGroupInvocations_);
        hasComputeSupport_ = (maxWorkGroupInvocations_ >= 256);
    } else {
        hasComputeSupport_ = false;
    }

    LOGD("EGL & AHB Extension Status: AHB Direct EGLImage=%s, SyncFence=%s",
         hasAhbSupport_ ? "AVAILABLE" : "UNAVAILABLE",
         (fnCreateSyncKHR && fnClientWaitSyncKHR) ? "AVAILABLE" : "UNAVAILABLE");
    LOGD("GLES Capabilities: Version=%d.%d, ComputeShader=%s, MaxWorkGroupInvocations=%d",
         major, minor,
         hasComputeSupport_ ? "AVAILABLE" : "UNAVAILABLE",
         maxWorkGroupInvocations_);

    return true;
}

bool GpuContext::makeCurrent() {
    if (!initialized_) {
        if (!initialize()) {
            return false;
        }
    }

    if (eglDisplay_ == EGL_NO_DISPLAY || eglContext_ == EGL_NO_CONTEXT) {
        return false;
    }

    if (attachDepth_ == 0) {
        if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
            LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
            return false;
        }
    }
    attachDepth_++;
    return true;
}

void GpuContext::doneCurrent() {
    if (attachDepth_ > 0) {
        attachDepth_--;
        if (attachDepth_ == 0 && eglDisplay_ != EGL_NO_DISPLAY) {
            glFlush();
            eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
    }
}

void GpuContext::release() {
    attachDepth_ = 0;
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglSurface_ != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay_, eglSurface_);
            eglSurface_ = EGL_NO_SURFACE;
        }
        if (eglContext_ != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay_, eglContext_);
            eglContext_ = EGL_NO_CONTEXT;
        }
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
    }
    initialized_ = false;
    hasAhbSupport_ = false;
    LOGD("GpuContext released");
}

} // namespace gpu
} // namespace darkbag

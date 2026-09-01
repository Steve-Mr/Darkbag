#ifndef RAW_VIDEO_GL_RENDERER_H
#define RAW_VIDEO_GL_RENDERER_H

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <cstdint>
#include <string>
#include <memory>
#include "../ColorPipe.h"

namespace darkbag {
namespace rawvideo {

class RawVideoGLRenderer {
public:
    RawVideoGLRenderer();
    ~RawVideoGLRenderer();

    bool setSurface(ANativeWindow* window);
    void releaseSurface();

    bool renderFrame(
        const uint8_t* bayerData,
        int width,
        int height,
        int orientation,
        int cfaPattern,
        int whiteLevel,
        float blackLevel,
        const float* neutralPoint,
        int targetLog,
        const char* lutPath,
        float exposure,
        float contrast,
        float saturation
    );

private:
    bool initEGL();
    void terminateEGL();
    bool initGL();
    void cleanupGL();
    void updateLutTexture(const char* lutPath);

    ANativeWindow* window_ = nullptr;
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLConfig eglConfig_ = nullptr;

    GLuint program_ = 0;
    GLuint bayerTexture_ = 0;
    GLuint lutTexture_ = 0;
    int bayerTexWidth_ = 0;
    int bayerTexHeight_ = 0;
    std::string currentLutPath_;

    // Uniform locations
    GLint uRawBayerTexLoc_ = -1;
    GLint uLut3DTexLoc_ = -1;
    GLint uHasLutLoc_ = -1;
    GLint uLutSizeLoc_ = -1;
    GLint uCfaPatternLoc_ = -1;
    GLint uOrientationLoc_ = -1;
    GLint uWhiteLevelLoc_ = -1;
    GLint uBlackLevelLoc_ = -1;
    GLint uWbGainsLoc_ = -1;
    GLint uExposureMultLoc_ = -1;
    GLint uContrastLoc_ = -1;
    GLint uSaturationLoc_ = -1;
    GLint uTargetLogLoc_ = -1;
    GLint uImageSizeLoc_ = -1;
};

} // namespace rawvideo
} // namespace darkbag

#endif // RAW_VIDEO_GL_RENDERER_H

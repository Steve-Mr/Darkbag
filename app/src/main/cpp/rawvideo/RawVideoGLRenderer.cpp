#include "RawVideoGLRenderer.h"
#include <android/log.h>
#include <cmath>
#include <algorithm>

#define TAG "RawVideoGLRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace rawvideo {

namespace {

const char* VERTEX_SHADER = R"glsl(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

uniform int uOrientation;
out vec2 vTexCoord;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vec2 tc = aTexCoord;
    if (uOrientation == 90) {
        vTexCoord = vec2(tc.y, 1.0 - tc.x);
    } else if (uOrientation == 180) {
        vTexCoord = vec2(1.0 - tc.x, 1.0 - tc.y);
    } else if (uOrientation == 270) {
        vTexCoord = vec2(1.0 - tc.y, tc.x);
    } else {
        vTexCoord = tc;
    }
}
)glsl";

const char* FRAGMENT_SHADER = R"glsl(#version 300 es
precision highp float;
precision highp usampler2D;
precision mediump sampler3D;

uniform highp usampler2D uRawBayerTexture;
uniform sampler3D uLut3DTexture;
uniform int uHasLut;
uniform float uLutSize;
uniform int uCfaPattern;
uniform float uWhiteLevel;
uniform float uBlackLevel;
uniform vec3 uWbGains;
uniform float uExposureMult;
uniform float uContrast;
uniform float uSaturation;
uniform int uTargetLog;
uniform ivec2 uImageSize;

in vec2 vTexCoord;
out vec4 fragColor;

float apply_flog2(float x) {
    return (x >= 0.000889) ? (0.245281 * log(5.555556 * x + 0.064829) / log(10.0) + 0.384316)
                           : (8.799461 * x + 0.092864);
}

float apply_slog3(float x) {
    return (x >= 0.011250) ? (420.0 + log((x + 0.01) / (0.18 + 0.01)) / log(10.0) * 261.5) / 1023.0
                           : (x * (171.2102946929 - 95.0) / 0.011250 + 95.0) / 1023.0;
}

float apply_logc3(float x) {
    const float cut = 0.010591;
    const float a = 5.555556;
    const float b = 0.052272;
    const float c = 0.247190;
    const float d = 0.385537;
    const float e = 5.367655;
    const float f = 0.092809;
    return (x > cut) ? (c * log(a * x + b) / log(10.0) + d) : (e * x + f);
}

float srgb_oetf(float x) {
    x = max(0.0, x);
    if (x <= 0.0031308) return 12.92 * x;
    return 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}

vec3 applyLog(vec3 c, int type) {
    if (type == 1) return vec3(apply_logc3(c.r), apply_logc3(c.g), apply_logc3(c.b));
    if (type == 2 || type == 3 || type == 4) return vec3(apply_flog2(c.r), apply_flog2(c.g), apply_flog2(c.b));
    if (type == 5 || type == 6) return vec3(apply_slog3(c.r), apply_slog3(c.g), apply_slog3(c.b));
    return c;
}

vec3 sampleLut(vec3 c) {
    vec3 scale = vec3((uLutSize - 1.0) / uLutSize);
    vec3 offset = vec3(0.5 / uLutSize);
    return texture(uLut3DTexture, c * scale + offset).rgb;
}

void main() {
    ivec2 coord = ivec2(vTexCoord * vec2(uImageSize));
    coord = clamp(coord, ivec2(0), uImageSize - 2);
    coord = coord & ~1;

    uint p00 = texelFetch(uRawBayerTexture, coord, 0).r;
    uint p01 = texelFetch(uRawBayerTexture, coord + ivec2(1, 0), 0).r;
    uint p10 = texelFetch(uRawBayerTexture, coord + ivec2(0, 1), 0).r;
    uint p11 = texelFetch(uRawBayerTexture, coord + ivec2(1, 1), 0).r;

    float r = 0.0, g = 0.0, b = 0.0;
    if (uCfaPattern == 0) { // RGGB
        r = float(p00);
        g = float(p01 + p10) * 0.5;
        b = float(p11);
    } else if (uCfaPattern == 1) { // GRBG
        g = float(p00 + p11) * 0.5;
        r = float(p01);
        b = float(p10);
    } else if (uCfaPattern == 2) { // GBRG
        g = float(p00 + p11) * 0.5;
        b = float(p01);
        r = float(p10);
    } else { // BGGR (3)
        b = float(p00);
        g = float(p01 + p10) * 0.5;
        r = float(p11);
    }

    float norm = (1.0 / max(1.0, uWhiteLevel - uBlackLevel)) * uExposureMult;
    r = max(0.0, (r - uBlackLevel) * norm * uWbGains.r);
    g = max(0.0, (g - uBlackLevel) * norm * uWbGains.g);
    b = max(0.0, (b - uBlackLevel) * norm * uWbGains.b);

    if (uContrast != 0.0) {
        float cf = 1.0 + uContrast;
        r = max(0.0, (r - 0.18) * cf + 0.18);
        g = max(0.0, (g - 0.18) * cf + 0.18);
        b = max(0.0, (b - 0.18) * cf + 0.18);
    }

    vec3 col = vec3(r, g, b);
    if (uTargetLog >= 0) {
        col = applyLog(col, uTargetLog);
    } else {
        col = vec3(srgb_oetf(col.r), srgb_oetf(col.g), srgb_oetf(col.b));
    }

    if (uHasLut == 1 && uLutSize > 1.0) {
        col = sampleLut(clamp(col, 0.0, 1.0));
    }

    if (uSaturation != 0.0) {
        float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
        col = max(vec3(0.0), luma + (col - luma) * (1.0 + uSaturation));
    }

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
)glsl";

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
            LOGE("Shader compilation error: %s", infoLog.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

} // namespace

RawVideoGLRenderer::RawVideoGLRenderer() = default;

RawVideoGLRenderer::~RawVideoGLRenderer() {
    releaseSurface();
    terminateEGL();
}

bool RawVideoGLRenderer::initEGL() {
    if (eglDisplay_ != EGL_NO_DISPLAY) return true;

    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(eglDisplay_, &major, &minor)) {
        LOGE("eglInitialize failed");
        return false;
    }

    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    if (!eglChooseConfig(eglDisplay_, attribs, &eglConfig_, 1, &numConfigs) || numConfigs == 0) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return false;
    }

    return true;
}

void RawVideoGLRenderer::terminateEGL() {
    cleanupGL();
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
    std::lock_guard<std::mutex> lock(surfaceMutex_);
    if (pendingWindow_) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
    if (currentWindow_) {
        ANativeWindow_release(currentWindow_);
        currentWindow_ = nullptr;
    }
}

void RawVideoGLRenderer::setSurface(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(surfaceMutex_);
    if (pendingWindow_) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
    if (window) {
        ANativeWindow_acquire(window);
        pendingWindow_ = window;
    }
    windowChanged_ = true;
}

void RawVideoGLRenderer::releaseSurface() {
    std::lock_guard<std::mutex> lock(surfaceMutex_);
    if (pendingWindow_) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
    windowChanged_ = true;
}

bool RawVideoGLRenderer::ensureEGLAndSurface() {
    {
        std::lock_guard<std::mutex> lock(surfaceMutex_);
        if (windowChanged_) {
            windowChanged_ = false;
            if (currentWindow_) {
                if (eglDisplay_ != EGL_NO_DISPLAY && eglSurface_ != EGL_NO_SURFACE) {
                    eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                    eglDestroySurface(eglDisplay_, eglSurface_);
                    eglSurface_ = EGL_NO_SURFACE;
                }
                ANativeWindow_release(currentWindow_);
                currentWindow_ = nullptr;
            }
            if (pendingWindow_) {
                currentWindow_ = pendingWindow_;
                ANativeWindow_acquire(currentWindow_);
            }
        }
    }

    if (!currentWindow_) {
        return false;
    }

    if (!initEGL()) {
        return false;
    }

    if (eglSurface_ == EGL_NO_SURFACE) {
        eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, currentWindow_, nullptr);
        if (eglSurface_ == EGL_NO_SURFACE) {
            LOGE("eglCreateWindowSurface failed (0x%x)", eglGetError());
            return false;
        }
    }

    if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
        LOGE("eglMakeCurrent failed (0x%x)", eglGetError());
        return false;
    }

    if (!initGL()) {
        return false;
    }

    return true;
}

bool RawVideoGLRenderer::initGL() {
    if (program_ != 0) return true;

    GLuint vert = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
    GLuint frag = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
    if (!vert || !frag) {
        if (vert) glDeleteShader(vert);
        if (frag) glDeleteShader(frag);
        return false;
    }

    program_ = glCreateProgram();
    glAttachShader(program_, vert);
    glAttachShader(program_, frag);
    glLinkProgram(program_);

    glDeleteShader(vert);
    glDeleteShader(frag);

    GLint linked = 0;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Failed to link GL program");
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }

    uRawBayerTexLoc_ = glGetUniformLocation(program_, "uRawBayerTexture");
    uLut3DTexLoc_ = glGetUniformLocation(program_, "uLut3DTexture");
    uHasLutLoc_ = glGetUniformLocation(program_, "uHasLut");
    uLutSizeLoc_ = glGetUniformLocation(program_, "uLutSize");
    uCfaPatternLoc_ = glGetUniformLocation(program_, "uCfaPattern");
    uOrientationLoc_ = glGetUniformLocation(program_, "uOrientation");
    uWhiteLevelLoc_ = glGetUniformLocation(program_, "uWhiteLevel");
    uBlackLevelLoc_ = glGetUniformLocation(program_, "uBlackLevel");
    uWbGainsLoc_ = glGetUniformLocation(program_, "uWbGains");
    uExposureMultLoc_ = glGetUniformLocation(program_, "uExposureMult");
    uContrastLoc_ = glGetUniformLocation(program_, "uContrast");
    uSaturationLoc_ = glGetUniformLocation(program_, "uSaturation");
    uTargetLogLoc_ = glGetUniformLocation(program_, "uTargetLog");
    uImageSizeLoc_ = glGetUniformLocation(program_, "uImageSize");

    glGenTextures(1, &bayerTexture_);
    glBindTexture(GL_TEXTURE_2D, bayerTexture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    return true;
}

void RawVideoGLRenderer::cleanupGL() {
    if (bayerTexture_ != 0) {
        glDeleteTextures(1, &bayerTexture_);
        bayerTexture_ = 0;
    }
    if (lutTexture_ != 0) {
        glDeleteTextures(1, &lutTexture_);
        lutTexture_ = 0;
    }
    if (program_ != 0) {
        glDeleteProgram(program_);
        program_ = 0;
    }
    bayerTexWidth_ = 0;
    bayerTexHeight_ = 0;
    currentLutPath_.clear();
}

void RawVideoGLRenderer::updateLutTexture(const char* lutPath) {
    if (!lutPath || lutPath[0] == '\0') {
        if (lutTexture_ != 0) {
            glDeleteTextures(1, &lutTexture_);
            lutTexture_ = 0;
        }
        currentLutPath_.clear();
        return;
    }

    if (currentLutPath_ == lutPath && lutTexture_ != 0) {
        return;
    }

    auto lut = get_cached_lut(lutPath);
    if (!lut || lut->size <= 0 || lut->data.empty()) {
        return;
    }

    if (lutTexture_ == 0) {
        glGenTextures(1, &lutTexture_);
    }

    glBindTexture(GL_TEXTURE_3D, lutTexture_);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

    glTexImage3D(
        GL_TEXTURE_3D,
        0,
        GL_RGB16F,
        lut->size,
        lut->size,
        lut->size,
        0,
        GL_RGB,
        GL_FLOAT,
        lut->data.data()
    );

    currentLutPath_ = lutPath;
}

bool RawVideoGLRenderer::renderFrame(
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
) {
    if (!bayerData || width <= 0 || height <= 0) {
        return false;
    }

    if (!ensureEGLAndSurface()) {
        return false;
    }

    int winWidth = ANativeWindow_getWidth(currentWindow_);
    int winHeight = ANativeWindow_getHeight(currentWindow_);
    glViewport(0, 0, winWidth, winHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(program_);

    // 1. Upload or update Bayer Texture
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, bayerTexture_);

    if (bayerTexWidth_ != width || bayerTexHeight_ != height) {
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_R16UI,
            width,
            height,
            0,
            GL_RED_INTEGER,
            GL_UNSIGNED_SHORT,
            bayerData
        );
        bayerTexWidth_ = width;
        bayerTexHeight_ = height;
    } else {
        glTexSubImage2D(
            GL_TEXTURE_2D,
            0,
            0,
            0,
            width,
            height,
            GL_RED_INTEGER,
            GL_UNSIGNED_SHORT,
            bayerData
        );
    }
    glUniform1i(uRawBayerTexLoc_, 0);

    // 2. 3D LUT Texture
    updateLutTexture(lutPath);
    if (lutTexture_ != 0) {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_3D, lutTexture_);
        glUniform1i(uLut3DTexLoc_, 1);
        glUniform1i(uHasLutLoc_, 1);
        auto lut = get_cached_lut(lutPath);
        glUniform1f(uLutSizeLoc_, lut ? static_cast<float>(lut->size) : 0.0f);
    } else {
        glUniform1i(uHasLutLoc_, 0);
        glUniform1f(uLutSizeLoc_, 0.0f);
    }

    // 3. White Balance
    float wbR = 2.0f, wbG = 1.0f, wbB = 1.6f;
    if (neutralPoint) {
        if (neutralPoint[0] > 0.001f && neutralPoint[1] > 0.001f && neutralPoint[2] > 0.001f) {
            if (std::abs(neutralPoint[0] - 1.0f) > 0.001f || std::abs(neutralPoint[2] - 1.0f) > 0.001f) {
                wbR = 1.0f / neutralPoint[0];
                wbG = 1.0f / neutralPoint[1];
                wbB = 1.0f / neutralPoint[2];
                wbR /= wbG;
                wbB /= wbG;
                wbG = 1.0f;
            }
        }
    }
    glUniform3f(uWbGainsLoc_, wbR, wbG, wbB);

    // 4. Uniforms
    glUniform1i(uCfaPatternLoc_, cfaPattern);
    glUniform1i(uOrientationLoc_, orientation);
    glUniform1f(uWhiteLevelLoc_, static_cast<float>(whiteLevel));
    glUniform1f(uBlackLevelLoc_, blackLevel);
    glUniform1f(uExposureMultLoc_, std::pow(2.0f, exposure));
    glUniform1f(uContrastLoc_, contrast);
    glUniform1f(uSaturationLoc_, saturation);
    glUniform1i(uTargetLogLoc_, targetLog);
    glUniform2i(uImageSizeLoc_, width, height);

    // 5. Draw Quad
    static const float quadVertices[] = {
        // Pos(x, y), Tex(u, v)
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 1.0f, 0.0f,
    };

    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), quadVertices);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), quadVertices + 2);
    glEnableVertexAttribArray(1);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);

    eglSwapBuffers(eglDisplay_, eglSurface_);
    return true;
}

} // namespace rawvideo
} // namespace darkbag

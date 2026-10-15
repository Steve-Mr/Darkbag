#pragma once

#include <GLES3/gl3.h>
#include <string>

namespace darkbag {
namespace gpu {

struct ColorPipeUniforms {
    GLint uTexR = -1;
    GLint uTexG = -1;
    GLint uTexB = -1;
    GLint uTexUnifiedRgb = -1;
    GLint uTexLsc = -1;
    GLint uHasLsc = -1;
    GLint uInputLayout = -1;
    GLint uLut3D = -1;
    GLint uHasLut = -1;
    GLint uLutSize = -1;
    GLint uWbGain = -1;
    GLint uDigitalGain = -1;
    GLint uColorTransform = -1;
    GLint uTargetLog = -1;
    GLint uColorEngineMode = -1;
    GLint uContrast = -1;
    GLint uSaturation = -1;
    GLint uHasHswb = -1;
    GLint uHighlights = -1;
    GLint uShadows = -1;
    GLint uWhites = -1;
    GLint uBlacks = -1;
    GLint uOrientation = -1;
    GLint uMirror = -1;
    GLint uZoomFactor = -1;
    GLint uPhysicalZoomFactor = -1;
};

class GpuProgram {
public:
    GpuProgram();
    ~GpuProgram();

    bool build(const char* vertexSrc, const char* fragmentSrc);
    void use();
    void release();

    GLuint getProgramId() const { return programId_; }
    const ColorPipeUniforms& uniforms() const { return uniforms_; }

    void drawQuad();

private:
    GLuint compileShader(GLenum type, const char* src);

    GLuint programId_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    ColorPipeUniforms uniforms_;
};

} // namespace gpu
} // namespace darkbag

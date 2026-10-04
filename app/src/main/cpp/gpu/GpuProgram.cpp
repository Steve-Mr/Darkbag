#include "GpuProgram.h"
#include <android/log.h>
#include <vector>

#define TAG "DarkbagGPU_Program"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace darkbag {
namespace gpu {

namespace {

const float kQuadVertices[] = {
    // Pos(x, y),   Tex(u, v)
    -1.0f, -1.0f,   0.0f, 0.0f,
     1.0f, -1.0f,   1.0f, 0.0f,
    -1.0f,  1.0f,   0.0f, 1.0f,
     1.0f,  1.0f,   1.0f, 1.0f,
};

} // namespace

GpuProgram::GpuProgram() = default;

GpuProgram::~GpuProgram() {
    release();
}

GLuint GpuProgram::compileShader(GLenum type, const char* src) {
    if (!src) return 0;
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
            LOGE("Shader compilation failed (%s): %s",
                 (type == GL_VERTEX_SHADER ? "VERTEX" : "FRAGMENT"), infoLog.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool GpuProgram::build(const char* vertexSrc, const char* fragmentSrc) {
    release();

    GLuint vs = compileShader(GL_VERTEX_SHADER, vertexSrc);
    if (!vs) return false;

    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fragmentSrc);
    if (!fs) {
        glDeleteShader(vs);
        return false;
    }

    programId_ = glCreateProgram();
    glAttachShader(programId_, vs);
    glAttachShader(programId_, fs);
    glLinkProgram(programId_);

    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint linked = 0;
    glGetProgramiv(programId_, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(programId_, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(infoLen);
            glGetProgramInfoLog(programId_, infoLen, nullptr, infoLog.data());
            LOGE("Program link failed: %s", infoLog.data());
        }
        glDeleteProgram(programId_);
        programId_ = 0;
        return false;
    }

    // Cache Uniform Locations
    uniforms_.uTexR = glGetUniformLocation(programId_, "uTexR");
    uniforms_.uTexG = glGetUniformLocation(programId_, "uTexG");
    uniforms_.uTexB = glGetUniformLocation(programId_, "uTexB");
    uniforms_.uTexUnifiedRgb = glGetUniformLocation(programId_, "uTexUnifiedRgb");
    uniforms_.uInputLayout = glGetUniformLocation(programId_, "uInputLayout");
    uniforms_.uLut3D = glGetUniformLocation(programId_, "uLut3D");
    uniforms_.uHasLut = glGetUniformLocation(programId_, "uHasLut");
    uniforms_.uLutSize = glGetUniformLocation(programId_, "uLutSize");
    uniforms_.uWbGain = glGetUniformLocation(programId_, "uWbGain");
    uniforms_.uDigitalGain = glGetUniformLocation(programId_, "uDigitalGain");
    uniforms_.uColorTransform = glGetUniformLocation(programId_, "uColorTransform");
    uniforms_.uTargetLog = glGetUniformLocation(programId_, "uTargetLog");
    uniforms_.uColorEngineMode = glGetUniformLocation(programId_, "uColorEngineMode");
    uniforms_.uContrast = glGetUniformLocation(programId_, "uContrast");
    uniforms_.uSaturation = glGetUniformLocation(programId_, "uSaturation");
    uniforms_.uHasHswb = glGetUniformLocation(programId_, "uHasHswb");
    uniforms_.uHighlights = glGetUniformLocation(programId_, "uHighlights");
    uniforms_.uShadows = glGetUniformLocation(programId_, "uShadows");
    uniforms_.uWhites = glGetUniformLocation(programId_, "uWhites");
    uniforms_.uBlacks = glGetUniformLocation(programId_, "uBlacks");
    uniforms_.uOrientation = glGetUniformLocation(programId_, "uOrientation");
    uniforms_.uMirror = glGetUniformLocation(programId_, "uMirror");
    uniforms_.uZoomFactor = glGetUniformLocation(programId_, "uZoomFactor");

    // Create VAO / VBO
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);

    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVertices), kQuadVertices, GL_STATIC_DRAW);

    // aPosition (location = 0)
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(0);

    // aTexCoord (location = 1)
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<void*>(2 * sizeof(float)));
    glEnableVertexAttribArray(1);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);

    LOGD("GpuProgram built successfully. Program ID: %u, VAO: %u", programId_, vao_);
    return true;
}

void GpuProgram::use() {
    if (programId_ != 0) {
        glUseProgram(programId_);
    }
}

void GpuProgram::drawQuad() {
    if (vao_ != 0) {
        glBindVertexArray(vao_);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
    }
}

void GpuProgram::release() {
    if (vbo_ != 0) {
        glDeleteBuffers(1, &vbo_);
        vbo_ = 0;
    }
    if (vao_ != 0) {
        glDeleteVertexArrays(1, &vao_);
        vao_ = 0;
    }
    if (programId_ != 0) {
        glDeleteProgram(programId_);
        programId_ = 0;
    }
}

} // namespace gpu
} // namespace darkbag

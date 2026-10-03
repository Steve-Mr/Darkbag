#pragma once

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <memory>
#include <cstdint>

namespace darkbag {
namespace gpu {

class AHardwareBufferTarget {
public:
    AHardwareBufferTarget();
    ~AHardwareBufferTarget();

    // Allocates or reuses a hardware buffer and binds it to FBO
    bool prepare(int width, int height);

    // Binds the offscreen FBO for rendering and sets viewport
    bool bindFbo();

    // Inserts sync fence and waits until GPU rendering finishes
    bool waitGpuFinish();

    // Locks the buffer to direct CPU virtual memory address
    // outStrideInPixels gives the row pitch in pixels (for pitch = stride * 4 in tjCompress2)
    bool lockRead(void** outVirtualAddr, int* outStrideInPixels);

    // Unlocks after reading
    void unlock();

    void release();

    int getWidth() const { return width_; }
    int getHeight() const { return height_; }
    int getStride() const { return strideInPixels_; }
    GLuint getFbo() const { return fbo_; }

private:
    AHardwareBuffer* buffer_ = nullptr;
    EGLImageKHR eglImage_ = EGL_NO_IMAGE_KHR;
    GLuint fbo_ = 0;
    GLuint texture_ = 0;

    int width_ = 0;
    int height_ = 0;
    int strideInPixels_ = 0;
    bool isLocked_ = false;
};

} // namespace gpu
} // namespace darkbag

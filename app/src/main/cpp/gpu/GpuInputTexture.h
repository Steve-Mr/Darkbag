#pragma once

#include <GLES3/gl3.h>
#include <cstdint>
#include <cstddef>

namespace darkbag {
namespace gpu {

class GpuInputTexture {
public:
    GpuInputTexture();
    ~GpuInputTexture();

    // Uploads 16-bit planar RGB data to GPU textures
    bool uploadPlanarRgb(
        const uint16_t* planarRgb,
        int width,
        int height,
        int stride_x = 1,
        int stride_y = 0,
        int stride_c = 0
    );

    // Binds the R, G, B textures to specified texture units and updates shader uniforms
    void bind(GLint locR, GLint locG, GLint locB, int baseUnit = 0);

    void release();

    int getWidth() const { return width_; }
    int getHeight() const { return height_; }

private:
    GLuint texR_ = 0;
    GLuint texG_ = 0;
    GLuint texB_ = 0;

    int width_ = 0;
    int height_ = 0;
};

} // namespace gpu
} // namespace darkbag

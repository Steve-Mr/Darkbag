#pragma once

#include <cstdint>
#include <cstddef>

namespace darkbag {
namespace demosaic {

/**
 * High-Performance C++ Native RCD (Ratio-Corrected Demosaicing) Engine
 *
 * @param bayer_input   16-bit single-channel Bayer CFA input buffer
 * @param width         Width of the image in pixels
 * @param height        Height of the image in pixels
 * @param cfa_pattern   Bayer pattern: 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
 * @param black_level   Black level array [r, g0, g1, b] to subtract
 * @param white_level   White level for normalization
 * @param white_balance Optional white balance gains [r, g0, g1, b] (nullptr for neutral)
 * @param rgb_output    Output buffer for planar 16-bit RGB (width * height * 3)
 */
void rcd_demosaic(const uint16_t* bayer_input,
                  int width,
                  int height,
                  int cfa_pattern,
                  const uint16_t* black_level,
                  uint16_t white_level,
                  const float* white_balance,
                  uint16_t* rgb_output);

} // namespace demosaic
} // namespace darkbag

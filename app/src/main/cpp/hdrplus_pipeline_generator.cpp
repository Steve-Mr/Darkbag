#include <Halide.h>

#include "align.h"
#include "finish.h"
#include "merge.h"
#include "util.h"

using namespace Halide;
using namespace Halide::ConciseCasts;

namespace {

constexpr int kVec = 8;
constexpr int kTileX = 128;
constexpr int kTileY = 32;

// --- Local Helpers to ensure compilation ---

Func local_box_down2(Func input, std::string name) {
  Func output(name);
  Var x("x"), y("y"), n("n");
  RDom r(0, 2, 0, 2);
  output(x, y, n) = u16(sum(u32(input(2 * x + r.x, 2 * y + r.y, n))) / 4);
  output.compute_root().parallel(y).vectorize(x, kVec);
  return output;
}

// --- Optimized Stage: Pre-processing (Pointwise) ---

Func apply_white_balance(Func input, Expr bp, Expr wp, const CompiletimeWhiteBalance &wb) {
  Func output("white_balance_output");
  Var x("x"), y("y");

  // Constants for Highlight Dampening
  float saturation_point = 16383.0f;
  float knee_point = 15000.0f;

  // Reserve headroom (0.25x)
  Expr white_factor = (65535.f / max(1.0f, f32(wp - bp))) * 0.25f;

  Expr val = u16_sat((i32(input(x, y)) - bp) * white_factor);
  Expr f_val = f32(val);

  Expr is_r  = (x % 2 == 0) && (y % 2 == 0);
  Expr is_g0 = (x % 2 == 1) && (y % 2 == 0);
  Expr is_g1 = (x % 2 == 0) && (y % 2 == 1);
  Expr gain = select(is_r, wb.r, is_g0, wb.g0, is_g1, wb.g1, wb.b);

  // Alpha: 1.0 (Safe) -> 0.0 (Saturated)
  Expr alpha = 1.0f - clamp((f_val - knee_point) / (saturation_point - knee_point), 0.0f, 1.0f);
  Expr final_gain = gain * alpha + 1.0f * (1.0f - alpha);

  output(x, y) = u16_sat(final_gain * f_val);
  return output;
}

// --- Optimized Stage: Demosaic ---

Func demosaic(Func input, Expr width, Expr height) {
  Var x("x"), y("y"), c("c");

  Func input_mirror = BoundaryConditions::mirror_interior(
      input, {Range(0, width), Range(0, height)});

  auto apply_filter = [&](const std::vector<std::pair<std::pair<int, int>, int>>& weights, int sum_val) {
      Expr res = i32(0);
      for (auto& w : weights) {
          res += i32(input_mirror(x + w.first.first, y + w.first.second)) * w.second;
      }
      return u16_sat(res / sum_val);
  };

  Func d0("d0"), d1("d1"), d2("d2"), d3("d3");
  d0(x, y) = apply_filter({{{0, -2}, -1}, {{0, -1}, 2}, {{-2, 0}, -1}, {{-1, 0}, 2}, {{0, 0}, 4}, {{1, 0}, 2}, {{2, 0}, -1}, {{0, 1}, 2}, {{0, 2}, -1}}, 8);
  d1(x, y) = apply_filter({{{0, -2}, 1}, {{-1, -1}, -2}, {{1, -1}, -2}, {{-2, 0}, -2}, {{-1, 0}, 8}, {{0, 0}, 10}, {{1, 0}, 8}, {{2, 0}, -2}, {{-1, 1}, -2}, {{1, 1}, -2}, {{0, 2}, 1}}, 16);
  d2(x, y) = apply_filter({{{0, -2}, -2}, {{-1, -1}, -2}, {{0, -1}, 8}, {{1, -1}, -2}, {{-2, 0}, 1}, {{0, 0}, 10}, {{2, 0}, 1}, {{-1, 1}, -2}, {{0, 1}, 8}, {{1, 1}, -2}, {{0, 2}, -2}}, 16);
  d3(x, y) = apply_filter({{{0, -2}, -3}, {{-1, -1}, 4}, {{1, -1}, 4}, {{-2, 0}, -3}, {{0, 0}, 12}, {{2, 0}, -3}, {{-1, 1}, 4}, {{1, 1}, 4}, {{0, 2}, -3}}, 16);

  Expr R_row = y % 2 == 0;
  Expr B_row = !R_row;
  Expr R_col = x % 2 == 0;
  Expr B_col = !R_col;
  Expr at_R = c == 0;
  Expr at_G = c == 1;
  Expr at_B = c == 2;

  Func output("demosaic_output");
  output(x, y, c) =
      select(at_R && R_row && B_col, d1(x, y), at_R && B_row && R_col, d2(x, y),
             at_R && B_row && B_col, d3(x, y), at_G && R_row && R_col, d0(x, y),
             at_G && B_row && B_col, d0(x, y), at_B && B_row && R_col, d1(x, y),
             at_B && R_row && B_col, d2(x, y), at_B && R_row && R_col, d3(x, y),
             input(x, y));

  Var xo("xo"), yo("yo"), xi("xi"), yi("yi");

  output.compute_root()
      .tile(x, y, xo, yo, xi, yi, kTileX, kTileY)
      .reorder(c, xi, yi, xo, yo)
      .parallel(yo)
      .vectorize(xi, kVec);

  d0.compute_at(output, yi).vectorize(x, kVec);
  d1.compute_at(output, yi).vectorize(x, kVec);
  d2.compute_at(output, yi).vectorize(x, kVec);
  d3.compute_at(output, yi).vectorize(x, kVec);
  return output;
}

// --- Optimized Stage: Denoise (with Downsampling) ---

Func fast_bilateral_filter(Func input, Expr width, Expr height) {
  Var x("x"), y("y"), c("c"), dx("dx"), dy("dy");

  // 1. Downsample for Chroma Filtering
  Func down = local_box_down2(input, "denoise_downsampled");

  // 2. Bilateral on Downsampled U/V
  Func weights("bilateral_weights");
  Func total_weights("bilateral_total_weights");
  Func bilateral("bilateral");
  RDom r(-3, 7, -3, 7);

  // Gaussian Kernel for Bilateral
  float k[7][7] = {
      {0.000690f, 0.002646f, 0.005923f, 0.007748f, 0.005923f, 0.002646f, 0.000690f},
      {0.002646f, 0.010149f, 0.022718f, 0.029715f, 0.022718f, 0.010149f, 0.002646f},
      {0.005923f, 0.022718f, 0.050855f, 0.066517f, 0.050855f, 0.022718f, 0.005923f},
      {0.007748f, 0.029715f, 0.066517f, 0.087001f, 0.066517f, 0.029715f, 0.007748f},
      {0.005923f, 0.022718f, 0.050855f, 0.066517f, 0.050855f, 0.022718f, 0.005923f},
      {0.002646f, 0.010149f, 0.022718f, 0.029715f, 0.022718f, 0.010149f, 0.002646f},
      {0.000690f, 0.002646f, 0.005923f, 0.007748f, 0.005923f, 0.002646f, 0.000690f}
  };
  Buffer<float> kernel(7, 7);
  for(int i=0; i<7; ++i) for(int j=0; j<7; ++j) kernel(i,j) = k[i][j];
  kernel.translate({-3, -3});

  Func down_mirror = BoundaryConditions::mirror_interior(down, {Range(0, width/2), Range(0, height/2)});
  Expr dist = f32(down_mirror(x, y, c)) - f32(down_mirror(x + dx, y + dy, c));
  float sig2 = 100.f;
  Expr score = exp(-dist * dist / sig2);
  weights(dx, dy, x, y, c) = kernel(dx, dy) * score;
  total_weights(x, y, c) = sum(weights(r.x, r.y, x, y, c));
  bilateral(x, y, c) = sum(down_mirror(x + r.x, y + r.y, c) * weights(r.x, r.y, x, y, c)) / max(0.0001f, total_weights(x, y, c));

  // 3. Upsample and Merge
  Func output("bilateral_filter_output");
  output(x, y, c) = input(x, y, c);
  output(x, y, 1) = bilateral(x / 2, y / 2, 1);
  output(x, y, 2) = bilateral(x / 2, y / 2, 2);

  bilateral.compute_root().parallel(y).vectorize(x, kVec);
  output.compute_root().parallel(y).vectorize(x, kVec);
  return output;
}

Func desaturate_noise(Func input, Expr width, Expr height) {
  Func output("desaturate_noise_output");
  Var x("x"), y("y"), c("c");
  Func input_mirror = BoundaryConditions::mirror_image(input, {Range(0, width), Range(0, height)});
  Func blur = gauss_15x15(gauss_15x15(input_mirror, "desaturate_noise_blur1"), "desaturate_noise_blur2");
  float factor = 1.4f;
  float threshold = 25000.f;
  output(x, y, c) = input(x, y, c);
  output(x, y, 1) = select((abs(blur(x, y, 1)) / max(1.f, abs(input(x, y, 1))) < factor) && (abs(input(x, y, 1)) < threshold), .7f * blur(x, y, 1) + .3f * input(x, y, 1), input(x, y, 1));
  output(x, y, 2) = select((abs(blur(x, y, 2)) / max(1.f, abs(input(x, y, 2))) < factor) && (abs(input(x, y, 2)) < threshold), .7f * blur(x, y, 2) + .3f * input(x, y, 2), input(x, y, 2));
  output.compute_root().parallel(y).vectorize(x, kVec);
  return output;
}

Func chroma_denoise(Func input, Expr width, Expr height, int num_passes) {
  Func output = rgb_to_yuv(input);
  if (num_passes > 0) output = fast_bilateral_filter(output, width, height);
  if (num_passes > 1) output = desaturate_noise(output, width, height);
  return yuv_to_rgb(output);
}

Func srgb(Func input, Func srgb_matrix, Expr gain) {
  Func output("srgb_output");
  Var x("x"), y("y"), c("c");
  RDom r(0, 3);
  // Apply CCM, Gain, and Rescale (4.0x to bring back 0.25x headroom)
  output(x, y, c) = u16_sat(sum(srgb_matrix(r, c) * input(x, y, r)) * gain * 4.0f);
  output.compute_root().parallel(y).vectorize(x, kVec);
  return output;
}

Func shift_bayer_to_rggb(Func input, const Expr cfa_pattern) {
  Func output("rggb_input");
  Var x("x"), y("y");
  output(x, y) = select(cfa_pattern == int(CfaPattern::CFA_RGGB), input(x, y),
                        cfa_pattern == int(CfaPattern::CFA_GRBG), input(x + 1, y),
                        cfa_pattern == int(CfaPattern::CFA_GBRG), input(x, y + 1),
                        cfa_pattern == int(CfaPattern::CFA_BGGR), input(x + 1, y + 1), 0);
  return output;
}

class HdrPlusRawPipeline : public Generator<HdrPlusRawPipeline> {
public:
  Input<Buffer<uint16_t>> inputs{"inputs", 3};
  Input<uint16_t> black_point{"black_point"};
  Input<uint16_t> white_point{"white_point"};
  Input<float> white_balance_r{"white_balance_r"};
  Input<float> white_balance_g0{"white_balance_g0"};
  Input<float> white_balance_g1{"white_balance_g1"};
  Input<float> white_balance_b{"white_balance_b"};
  Input<int> cfa_pattern{"cfa_pattern"};
  Input<Buffer<float>> ccm{"ccm", 2};

  Input<float> compression{"compression"};
  Input<float> gain{"gain"};

  // 16-bit Linear RGB output
  Output<Buffer<uint16_t>> output{"output", 3};

  void generate() {
    Func alignment = align(inputs, inputs.width(), inputs.height());
    Func merged = merge(inputs, inputs.width(), inputs.height(),
                        inputs.dim(2).extent(), alignment);
    CompiletimeWhiteBalance wb{white_balance_r, white_balance_g0,
                               white_balance_g1, white_balance_b};

    Func bayer_shifted = shift_bayer_to_rggb(merged, cfa_pattern);
    Func pre_processed = apply_white_balance(bayer_shifted, black_point, white_point, wb);
    Func demosaiced = demosaic(pre_processed, inputs.width(), inputs.height());

    int denoise_passes = 1;
    Func denoised = chroma_denoise(demosaiced, inputs.width(), inputs.height(), denoise_passes);

    output = srgb(denoised, ccm, gain);
  }
};

} // namespace

HALIDE_REGISTER_GENERATOR(HdrPlusRawPipeline, hdrplus_raw_pipeline)

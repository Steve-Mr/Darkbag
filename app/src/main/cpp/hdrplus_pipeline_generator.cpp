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

// --- Inlined Helper Functions from finish.cpp ---

Func black_white_level(Func input, const Expr bp, const Expr wp) {
  Func output("black_white_level_output");
  Var x, y;
  // Reserve headroom (0.25x) for White Balance to prevent clipping
  Expr white_factor = (65535.f / (wp - bp)) * 0.25f;
  output(x, y) = u16_sat((i32(input(x, y)) - bp) * white_factor);
  return output;
}

Func white_balance(Func input, Expr width, Expr height,
                   const CompiletimeWhiteBalance &wb) {
  Func output("white_balance_output");
  Var x, y;
  RDom r(0, width / 2, 0, height / 2);

  // Proposal A: Highlight Dampening
  // Saturation point is where 0.25x headroom hits max u16 (approx 16383)
  float saturation_point = 16383.0f;
  // Knee point where we start fading out the WB gain
  float knee_point = 15000.0f;

  auto apply_wb_safe = [&](Expr val, Expr gain) {
      Expr f_val = f32(val);
      // Alpha: 1.0 (Safe) -> 0.0 (Saturated)
      Expr alpha = 1.0f - clamp((f_val - knee_point) / (saturation_point - knee_point), 0.0f, 1.0f);
      // Interpolate Gain: Original -> 1.0
      Expr final_gain = gain * alpha + 1.0f * (1.0f - alpha);
      return u16_sat(final_gain * f_val);
  };

  output(x, y) = u16(0);
  output(r.x * 2, r.y * 2) = apply_wb_safe(input(r.x * 2, r.y * 2), wb.r);
  output(r.x * 2 + 1, r.y * 2) = apply_wb_safe(input(r.x * 2 + 1, r.y * 2), wb.g0);
  output(r.x * 2, r.y * 2 + 1) = apply_wb_safe(input(r.x * 2, r.y * 2 + 1), wb.g1);
  output(r.x * 2 + 1, r.y * 2 + 1) = apply_wb_safe(input(r.x * 2 + 1, r.y * 2 + 1), wb.b);

  Var yo("yo"), yi("yi");
  output.compute_root()
      .split(y, yo, yi, kTileY)
      .parallel(yo)
      .vectorize(x, kVec);
  output.update(0).split(r.y, yo, yi, kTileY).parallel(yo);
  output.update(1).split(r.y, yo, yi, kTileY).parallel(yo);
  output.update(2).split(r.y, yo, yi, kTileY).parallel(yo);
  output.update(3).split(r.y, yo, yi, kTileY).parallel(yo);
  return output;
}

Func demosaic(Func input, Expr width, Expr height) {
  Buffer<int32_t> f0(5, 5, "demosaic_f0");
  Buffer<int32_t> f1(5, 5, "demosaic_f1");
  Buffer<int32_t> f2(5, 5, "demosaic_f2");
  Buffer<int32_t> f3(5, 5, "demosaic_f3");

  f0.translate({-2, -2});
  f1.translate({-2, -2});
  f2.translate({-2, -2});
  f3.translate({-2, -2});

  Func d0("demosaic_0");
  Func d1("demosaic_1");
  Func d2("demosaic_2");
  Func d3("demosaic_3");
  Func output("demosaic_output");

  Var x, y, c;
  RDom r0(-2, 5, -2, 5);

  Func input_mirror = BoundaryConditions::mirror_interior(
      input, {Range(0, width), Range(0, height)});

  f0.fill(0); f1.fill(0); f2.fill(0); f3.fill(0);
  int f0_sum = 8; int f1_sum = 16; int f2_sum = 16; int f3_sum = 16;

  f0(0, -2) = -1; f0(0, -1) = 2; f0(-2, 0) = -1; f0(-1, 0) = 2; f0(0, 0) = 4; f0(1, 0) = 2; f0(2, 0) = -1; f0(0, 1) = 2; f0(0, 2) = -1;
  f1(0, -2) = 1; f1(-1, -1) = -2; f1(1, -1) = -2; f1(-2, 0) = -2; f1(-1, 0) = 8; f1(0, 0) = 10; f1(1, 0) = 8; f1(2, 0) = -2; f1(-1, 1) = -2; f1(1, 1) = -2; f1(0, 2) = 1;
  f2(0, -2) = -2; f2(-1, -1) = -2; f2(0, -1) = 8; f2(1, -1) = -2; f2(-2, 0) = 1; f2(0, 0) = 10; f2(2, 0) = 1; f2(-1, 1) = -2; f2(0, 1) = 8; f2(1, 1) = -2; f2(0, 2) = -2;
  f3(0, -2) = -3; f3(-1, -1) = 4; f3(1, -1) = 4; f3(-2, 0) = -3; f3(0, 0) = 12; f3(2, 0) = -3; f3(-1, 1) = 4; f3(1, 1) = 4; f3(0, 2) = -3;

  d0(x, y) = u16_sat(sum(i32(input_mirror(x + r0.x, y + r0.y)) * f0(r0.x, r0.y)) / f0_sum);
  d1(x, y) = u16_sat(sum(i32(input_mirror(x + r0.x, y + r0.y)) * f1(r0.x, r0.y)) / f1_sum);
  d2(x, y) = u16_sat(sum(i32(input_mirror(x + r0.x, y + r0.y)) * f2(r0.x, r0.y)) / f2_sum);
  d3(x, y) = u16_sat(sum(i32(input_mirror(x + r0.x, y + r0.y)) * f3(r0.x, r0.y)) / f3_sum);

  Expr R_row = y % 2 == 0;
  Expr B_row = !R_row;
  Expr R_col = x % 2 == 0;
  Expr B_col = !R_col;
  Expr at_R = c == 0;
  Expr at_G = c == 1;
  Expr at_B = c == 2;

  output(x, y, c) =
      select(at_R && R_row && B_col, d1(x, y), at_R && B_row && R_col, d2(x, y),
             at_R && B_row && B_col, d3(x, y), at_G && R_row && R_col, d0(x, y),
             at_G && B_row && B_col, d0(x, y), at_B && B_row && R_col, d1(x, y),
             at_B && R_row && B_col, d2(x, y), at_B && R_row && R_col, d3(x, y),
             input(x, y));

  Var xo("xo"), yo("yo"), xi("xi"), yi("yi");
  d0.compute_root().tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
  d1.compute_root().tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
  d2.compute_root().tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
  d3.compute_root().tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);

  output.compute_root()
      .tile(x, y, xo, yo, xi, yi, kTileX, kTileY)
      .reorder(c, xi, yi, xo, yo)
      .parallel(yo)
      .vectorize(xi, kVec)
      .align_bounds(x, 2)
      .align_bounds(y, 2);
  return output;
}

Func bilateral_filter(Func input, Expr width, Expr height) {
  Buffer<float> k(7, 7, "gauss_kernel");
  k.translate({-3, -3});
  Func weights("bilateral_weights");
  Func total_weights("bilateral_total_weights");
  Func bilateral("bilateral");
  Func output("bilateral_filter_output");
  Var x, y, dx, dy, c;
  RDom r(-3, 7, -3, 7);

  k.fill(0.f);
  k(-3, -3) = 0.000690f; k(-2, -3) = 0.002646f; k(-1, -3) = 0.005923f; k(0, -3) = 0.007748f; k(1, -3) = 0.005923f; k(2, -3) = 0.002646f; k(3, -3) = 0.000690f;
  k(-3, -2) = 0.002646f; k(-2, -2) = 0.010149f; k(-1, -2) = 0.022718f; k(0, -2) = 0.029715f; k(1, -2) = 0.022718f; k(2, -2) = 0.010149f; k(3, -2) = 0.002646f;
  k(-3, -1) = 0.005923f; k(-2, -1) = 0.022718f; k(-1, -1) = 0.050855f; k(0, -1) = 0.066517f; k(1, -1) = 0.050855f; k(2, -1) = 0.022718f; k(3, -1) = 0.005923f;
  k(-3, 0) = 0.007748f; k(-2, 0) = 0.029715f; k(-1, 0) = 0.066517f; k(0, 0) = 0.087001f; k(1, 0) = 0.066517f; k(2, 0) = 0.029715f; k(3, 0) = 0.007748f;
  k(-3, 1) = 0.005923f; k(-2, 1) = 0.022718f; k(-1, 1) = 0.050855f; k(0, 1) = 0.066517f; k(1, 1) = 0.050855f; k(2, 1) = 0.022718f; k(3, 1) = 0.005923f;
  k(-3, 2) = 0.002646f; k(-2, 2) = 0.010149f; k(-1, 2) = 0.022718f; k(0, 2) = 0.029715f; k(1, 2) = 0.022718f; k(2, 2) = 0.010149f; k(3, 2) = 0.002646f;
  k(-3, 3) = 0.000690f; k(-2, 3) = 0.002646f; k(-1, 3) = 0.005923f; k(0, 3) = 0.007748f; k(1, 3) = 0.005923f; k(2, 3) = 0.002646f; k(3, 3) = 0.000690f;

  Func input_mirror = BoundaryConditions::mirror_interior(input, {Range(0, width), Range(0, height)});
  Expr dist = f32(i32(input_mirror(x, y, c)) - i32(input_mirror(x + dx, y + dy, c)));
  float sig2 = 100.f;
  float threshold = 25000.f;
  Expr score = select(abs(input_mirror(x + dx, y + dy, c)) > threshold, 0.f, exp(-dist * dist / sig2));
  weights(dx, dy, x, y, c) = k(dx, dy) * score;
  total_weights(x, y, c) = sum(weights(r.x, r.y, x, y, c));
  bilateral(x, y, c) = sum(input_mirror(x + r.x, y + r.y, c) * weights(r.x, r.y, x, y, c)) / total_weights(x, y, c);
  output(x, y, c) = f32(input(x, y, c));
  output(x, y, 1) = bilateral(x, y, 1);
  output(x, y, 2) = bilateral(x, y, 2);

  Var xo("xo"), yo("yo"), xi("xi"), yi("yi");
  weights.compute_at(output, yo).vectorize(x, kVec);
  output.compute_root()
      .tile(x, y, xo, yo, xi, yi, kTileX, kTileY)
      .parallel(yo)
      .vectorize(xi, kVec);
  output.update(0).tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
  output.update(1).tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
  return output;
}

Func desaturate_noise(Func input, Expr width, Expr height) {
  Func output("desaturate_noise_output");
  Var x, y, c;
  Func input_mirror = BoundaryConditions::mirror_image(input, {Range(0, width), Range(0, height)});
  Func blur = gauss_15x15(gauss_15x15(input_mirror, "desaturate_noise_blur1"), "desaturate_noise_blur2");
  float factor = 1.4f;
  float threshold = 25000.f;
  output(x, y, c) = input(x, y, c);
  output(x, y, 1) = select((abs(blur(x, y, 1)) / abs(input(x, y, 1)) < factor) && (abs(input(x, y, 1)) < threshold) && (abs(blur(x, y, 1)) < threshold), .7f * blur(x, y, 1) + .3f * input(x, y, 1), input(x, y, 1));
  output(x, y, 2) = select((abs(blur(x, y, 2)) / abs(input(x, y, 2)) < factor) && (abs(input(x, y, 2)) < threshold) && (abs(blur(x, y, 2)) < threshold), .7f * blur(x, y, 2) + .3f * input(x, y, 2), input(x, y, 2));
  Var xo("xo"), yo("yo"), xi("xi"), yi("yi");
  output.compute_root().tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
  return output;
}

Func increase_saturation(Func input, float strength) {
  Func output("increase_saturation_output");
  Var x, y, c;
  output(x, y, c) = strength * input(x, y, c);
  output(x, y, 0) = input(x, y, 0);
  Var xo("xo"), yo("yo"), xi("xi"), yi("yi");
  output.compute_root().tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
  return output;
}

Func chroma_denoise(Func input, Expr width, Expr height, int num_passes) {
  Func output = rgb_to_yuv(input);
  int pass = 0;
  if (num_passes > 0) output = bilateral_filter(output, width, height);
  pass++;
  while (pass < num_passes) {
    output = desaturate_noise(output, width, height);
    pass++;
  }
  if (num_passes > 2) output = increase_saturation(output, 1.1f);
  return yuv_to_rgb(output);
}

Func srgb(Func input, Func srgb_matrix) {
  Func output("srgb_output");
  Var x, y, c;
  RDom r(0, 3);
  output(x, y, c) = u16_sat(sum(srgb_matrix(r, c) * input(x, y, r)));
  return output;
}

Func shift_bayer_to_rggb(Func input, const Expr cfa_pattern) {
  Func output("rggb_input");
  Var x, y, c;
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
    Func black_white_level_output = black_white_level(bayer_shifted, black_point, white_point);
    Func white_balance_output = white_balance(black_white_level_output, inputs.width(), inputs.height(), wb);
    Func demosaic_output = demosaic(white_balance_output, inputs.width(), inputs.height());

    int denoise_passes = 1;
    Func chroma_denoised_output = chroma_denoise(demosaic_output, inputs.width(), inputs.height(), denoise_passes);

    Func linear_rgb_output = srgb(chroma_denoised_output, ccm);
    output = linear_rgb_output;
  }
};

} // namespace

HALIDE_REGISTER_GENERATOR(HdrPlusRawPipeline, hdrplus_raw_pipeline)

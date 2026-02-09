#include <Halide.h>

#include "align.h"
#include "finish.h"
#include "merge.h"
#include "util.h"

using namespace Halide;
using namespace Halide::ConciseCasts;

namespace {

// --- Post-Processing Helper Functions ---

Expr apply_srgb_oetf(Expr x) {
    return select(x <= 0.0031308f, 12.92f * x, 1.055f * pow(x, 1.0f / 2.4f) - 0.055f);
}

Expr apply_arri_logc3(Expr x) {
    float cut = 0.010591f;
    float a = 5.555556f;
    float b = 0.052272f;
    float c = 0.247190f;
    float d = 0.385537f;
    float e = 5.367655f;
    float f = 0.092809f;
    Expr log10_val = log(a * x + b) / 2.302585f;
    return select(x > cut, c * log10_val + d, e * x + f);
}

Expr apply_s_log3(Expr x) {
    Expr log10_val = log((x + 0.01f) / (0.18f + 0.01f)) / 2.302585f;
    return select(x >= 0.01125000f, (420.0f + log10_val * 261.5f) / 1023.0f,
                  (x * 171.2102946929f + 95.0f) / 1023.0f);
}

Expr apply_f_log(Expr x) {
    float a = 0.555556f;
    float b = 0.009468f;
    float c = 0.344676f;
    float d = 0.790453f;
    float cut = 0.00089f;
    Expr log10_val = log(a * x + b) / 2.302585f;
    return select(x >= cut, c * log10_val + d, 8.52f * x + 0.0929f);
}

Expr apply_vlog(Expr x) {
    float cut = 0.01f;
    float c = 0.241514f;
    float b = 0.008730f;
    float d = 0.598206f;
    Expr log10_val = log(x + b) / 2.302585f;
    return select(x >= cut, c * log10_val + d, 5.6f * x + 0.125f);
}

Expr apply_log(Expr x, Expr type) {
    Expr val = max(0.0f, x);
    return select(type == 1, apply_arri_logc3(val),
                  type == 2 || type == 3, apply_f_log(val),
                  type == 5 || type == 6, apply_s_log3(val),
                  type == 7, apply_vlog(val),
                  apply_srgb_oetf(val));
}

// Matrix multiplication helper
Func multiply_3x3(Func input, Func mat, std::string name, Var x, Var y, Var c) {
    Func output(name);
    output(x, y, c) = mat(0, c) * input(x, y, 0) +
                      mat(1, c) * input(x, y, 1) +
                      mat(2, c) * input(x, y, 2);
    return output;
}

// --- Inlined Helper Functions from finish.cpp ---

Func black_white_level(Func input, const Expr bp, const Expr wp, Var x, Var y) {
  Func output("black_white_level_output");
  // Reserve headroom (0.25x) for White Balance to prevent clipping
  Expr white_factor = (65535.f / (wp - bp)) * 0.25f;
  output(x, y) = u16_sat((i32(input(x, y)) - bp) * white_factor);
  return output;
}

Func white_balance(Func input, Expr width, Expr height,
                   const CompiletimeWhiteBalance &wb, Var x, Var y) {
  Func output("white_balance_output");
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

  output.compute_root().parallel(y).vectorize(x, 16);
  output.update(0).parallel(r.y);
  output.update(1).parallel(r.y);
  output.update(2).parallel(r.y);
  output.update(3).parallel(r.y);
  return output;
}

Func demosaic(Func input, Expr width, Expr height, Var x, Var y, Var c) {
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

  d0.compute_root().parallel(y).vectorize(x, 16);
  d1.compute_root().parallel(y).vectorize(x, 16);
  d2.compute_root().parallel(y).vectorize(x, 16);
  d3.compute_root().parallel(y).vectorize(x, 16);

  output.compute_root().parallel(y).align_bounds(x, 2).unroll(x, 2).align_bounds(y, 2).unroll(y, 2).vectorize(x, 16);
  return output;
}

Func bilateral_filter(Func input, Expr width, Expr height, Var x, Var y, Var c) {
  Buffer<float> k(7, 7, "gauss_kernel");
  k.translate({-3, -3});
  Func weights("bilateral_weights");
  Func total_weights("bilateral_total_weights");
  Func bilateral("bilateral");
  Func output("bilateral_filter_output");
  Var dx, dy;
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

  weights.compute_at(output, y).vectorize(x, 16);
  output.compute_root().parallel(y).vectorize(x, 16);
  output.update(0).parallel(y).vectorize(x, 16);
  output.update(1).parallel(y).vectorize(x, 16);
  return output;
}

Func desaturate_noise(Func input, Expr width, Expr height, Var x, Var y, Var c) {
  Func output("desaturate_noise_output");
  Func input_mirror = BoundaryConditions::mirror_image(input, {Range(0, width), Range(0, height)});
  Func blur = gauss_15x15(gauss_15x15(input_mirror, "desaturate_noise_blur1"), "desaturate_noise_blur2");
  float factor = 1.4f;
  float threshold = 25000.f;
  output(x, y, c) = input(x, y, c);
  output(x, y, 1) = select((abs(blur(x, y, 1)) / abs(input(x, y, 1)) < factor) && (abs(input(x, y, 1)) < threshold) && (abs(blur(x, y, 1)) < threshold), .7f * blur(x, y, 1) + .3f * input(x, y, 1), input(x, y, 1));
  output(x, y, 2) = select((abs(blur(x, y, 2)) / abs(input(x, y, 2)) < factor) && (abs(input(x, y, 2)) < threshold) && (abs(blur(x, y, 2)) < threshold), .7f * blur(x, y, 2) + .3f * input(x, y, 2), input(x, y, 2));
  output.compute_root().parallel(y).vectorize(x, 16);
  return output;
}

Func increase_saturation(Func input, float strength, Var x, Var y, Var c) {
  Func output("increase_saturation_output");
  output(x, y, c) = strength * input(x, y, c);
  output(x, y, 0) = input(x, y, 0);
  output.compute_root().parallel(y).vectorize(x, 16);
  return output;
}

Func chroma_denoise(Func input, Expr width, Expr height, int num_passes, Var x, Var y, Var c) {
  Func output = rgb_to_yuv(input);
  int pass = 0;
  if (num_passes > 0) output = bilateral_filter(output, width, height, x, y, c);
  pass++;
  while (pass < num_passes) {
    output = desaturate_noise(output, width, height, x, y, c);
    pass++;
  }
  if (num_passes > 2) output = increase_saturation(output, 1.1f, x, y, c);
  return yuv_to_rgb(output);
}

Func srgb_func(Func input, Func srgb_matrix, Var x, Var y, Var c) {
  Func output("srgb_output");
  RDom r(0, 3);
  output(x, y, c) = u16_sat(sum(srgb_matrix(r, c) * input(x, y, r)));
  return output;
}

Func shift_bayer_to_rggb(Func input, const Expr cfa_pattern, Var x, Var y) {
  Func output("rggb_input");
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

  // Post-processing inputs
  Input<float> digital_gain{"digital_gain"};
  Input<int> target_log{"target_log"};
  Input<Buffer<float>> m_srgb_to_xyz{"m_srgb_to_xyz", 2};
  Input<Buffer<float>> m_xyz_to_target{"m_xyz_to_target", 2};
  Input<Buffer<float>> lut{"lut", 4}; // (c, r, g, b)
  Input<int> lut_size{"lut_size"};
  Input<bool> has_lut{"has_lut"};

  // Outputs
  Output<Buffer<uint16_t>> output_linear{"output_linear", 3};
  Output<Buffer<uint16_t>> output_final{"output_final", 3};

  void generate() {
    Var x("x"), y("y"), c("c");

    // 1. Raw Pipeline
    Func alignment = align(inputs, inputs.width(), inputs.height());
    Func merged = merge(inputs, inputs.width(), inputs.height(),
                        inputs.dim(2).extent(), alignment);
    CompiletimeWhiteBalance wb{white_balance_r, white_balance_g0,
                               white_balance_g1, white_balance_b};

    Func bayer_shifted = shift_bayer_to_rggb(merged, cfa_pattern, x, y);
    Func black_white_level_output = black_white_level(bayer_shifted, black_point, white_point, x, y);
    Func white_balance_output = white_balance(black_white_level_output, inputs.width(), inputs.height(), wb, x, y);
    Func demosaic_output = demosaic(white_balance_output, inputs.width(), inputs.height(), x, y, c);

    int denoise_passes = 1;
    Func chroma_denoised_output = chroma_denoise(demosaic_output, inputs.width(), inputs.height(), denoise_passes, x, y, c);

    // Linear RGB (Sensor_WB -> sRGB)
    Func linear_srgb = srgb_func(chroma_denoised_output, ccm, x, y, c);

    // Output 1: Linear Raw for DNG
    output_linear(x, y, c) = linear_srgb(x, y, c);

    // 2. Post-processing Pipeline
    // 2a. Digital Gain & Normalization
    Func normalized("normalized");
    normalized(x, y, c) = (f32(linear_srgb(x, y, c)) / 65535.0f) * digital_gain;

    // 2b. Color Space Conversion: sRGB -> XYZ -> Target Gamut
    Func xyz = multiply_3x3(normalized, m_srgb_to_xyz, "xyz", x, y, c);
    Func target_gamut = multiply_3x3(xyz, m_xyz_to_target, "target_gamut", x, y, c);

    // 2c. Log Curve
    Func logged("logged");
    logged(x, y, c) = apply_log(target_gamut(x, y, c), target_log);

    // 2d. 3D LUT (Trilinear Interpolation)
    Func final_color("final_color");

    Expr scale = f32(lut_size - 1);
    Expr r_v = clamp(logged(x, y, 0), 0.0f, 1.0f) * scale;
    Expr g_v = clamp(logged(x, y, 1), 0.0f, 1.0f) * scale;
    Expr b_v = clamp(logged(x, y, 2), 0.0f, 1.0f) * scale;

    Expr r0 = cast<int>(r_v); Expr r1 = min(r0 + 1, lut_size - 1);
    Expr g0 = cast<int>(g_v); Expr g1 = min(g0 + 1, lut_size - 1);
    Expr b0 = cast<int>(b_v); Expr b1 = min(b0 + 1, lut_size - 1);

    Expr dr = r_v - r0; Expr dg = g_v - g0; Expr db = b_v - b0;

    auto lookup = [&](Expr ri, Expr gi, Expr bi, Expr ci) {
        return lut(ci, ri, gi, bi);
    };

    Expr c000 = lookup(r0, g0, b0, c); Expr c100 = lookup(r1, g0, b0, c);
    Expr c010 = lookup(r0, g1, b0, c); Expr c110 = lookup(r1, g1, b0, c);
    Expr c001 = lookup(r0, g0, b1, c); Expr c101 = lookup(r1, g0, b1, c);
    Expr c011 = lookup(r0, g1, b1, c); Expr c111 = lookup(r1, g1, b1, c);

    Expr c00 = c000 * (1.0f - dr) + c100 * dr;
    Expr c10 = c010 * (1.0f - dr) + c110 * dr;
    Expr c01 = c001 * (1.0f - dr) + c101 * dr;
    Expr c11 = c011 * (1.0f - dr) + c111 * dr;

    Expr c0 = c00 * (1.0f - dg) + c10 * dg;
    Expr c1 = c01 * (1.0f - dg) + c11 * dg;

    Expr lut_res = c0 * (1.0f - db) + c1 * db;
    final_color(x, y, c) = select(has_lut, lut_res, logged(x, y, c));

    // Output 2: Final Processed Image
    output_final(x, y, c) = u16_sat(final_color(x, y, c) * 65535.0f);

    // --- Scheduling ---
    Target target = get_target();
    if (target.has_gpu_feature()) {
        Var tx("tx"), ty("ty");
        output_linear.gpu_tile(x, y, tx, ty, 16, 16);
        output_final.gpu_tile(x, y, tx, ty, 16, 16);
    } else {
        output_linear.compute_root().parallel(y).vectorize(x, 16);
        output_final.compute_root().parallel(y).vectorize(x, 16);
        linear_srgb.compute_root().parallel(y).vectorize(x, 16);
        normalized.compute_at(output_final, y).vectorize(x, 16);
        xyz.compute_at(output_final, y).vectorize(x, 16);
        target_gamut.compute_at(output_final, y).vectorize(x, 16);
        logged.compute_at(output_final, y).vectorize(x, 16);
    }
  }
};

} // namespace

HALIDE_REGISTER_GENERATOR(HdrPlusRawPipeline, hdrplus_raw_pipeline)

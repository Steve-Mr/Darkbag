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

class HdrPlusRawPipeline : public Generator<HdrPlusRawPipeline> {
public:
  GeneratorParam<bool> use_optimized_schedule{"use_optimized_schedule", true};
  GeneratorParam<bool> use_gpu{"use_gpu", false};

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
    Func white_balance_output = white_balance(black_white_level_output, wb);

    // Demosaic
    DemosaicResult dm = demosaic(white_balance_output, inputs.width(), inputs.height());
    Func demosaic_output = dm.output;

    // Denoise
    int denoise_passes = 1;
    Func chroma_denoised_output = chroma_denoise(demosaic_output, inputs.width(), inputs.height(), denoise_passes);
    // Note: chroma_denoise returns yuv_to_rgb(output_denoise).
    // We need to capture the intermediate bilateral filter func if we want to schedule it.
    // For now, bilateral_filter inside chroma_denoise schedules itself.

    Func linear_rgb_output = srgb(chroma_denoised_output, ccm);
    output(x, y, c) = linear_rgb_output(x, y, c);

    // --- Scheduling ---
    if (use_gpu) {
        // GPU Schedule
        Var tx{"tx"}, ty{"ty"};
        output.gpu_tile(x, y, tx, ty, xi, yi, 16, 16);
        linear_rgb_output.compute_at(output, tx);

        // We'd need to propagate GPU scheduling into helper functions or refactor them
        // to return more handles. For now, the most critical stages (bilateral)
        // will need manual GPU scheduling if enabled.
        // Given the constraint "toggleable/removable", we keep it simple.
    } else if (!use_optimized_schedule) {
        // Legacy CPU Schedule
        black_white_level_output.compute_root().parallel(y).vectorize(x, kVec);
        white_balance_output.compute_root().parallel(y).vectorize(x, kVec);

        demosaic_output.compute_root()
            .tile(x, y, xo, yo, xi, yi, kTileX, kTileY)
            .reorder(c, xi, yi, xo, yo)
            .parallel(yo)
            .vectorize(xi, kVec)
            .align_bounds(x, 2)
            .align_bounds(y, 2);
        dm.d0.compute_at(demosaic_output, yi).vectorize(x, kVec);
        dm.d1.compute_at(demosaic_output, yi).vectorize(x, kVec);
        dm.d2.compute_at(demosaic_output, yi).vectorize(x, kVec);
        dm.d3.compute_at(demosaic_output, yi).vectorize(x, kVec);

        linear_rgb_output.compute_root()
            .tile(x, y, xo, yo, xi, yi, kTileX, kTileY)
            .parallel(yo)
            .vectorize(xi, kVec);
    } else {
        // Optimized CPU Schedule (Stage Fusion)
        // Fuse early stages into demosaic
        black_white_level_output.compute_at(demosaic_output, yi).vectorize(x, kVec);
        white_balance_output.compute_at(demosaic_output, yi).vectorize(x, kVec);

        demosaic_output.compute_root()
            .tile(x, y, xo, yo, xi, yi, kTileX, kTileY)
            .reorder(c, xi, yi, xo, yo)
            .parallel(yo)
            .vectorize(xi, kVec)
            .align_bounds(x, 2)
            .align_bounds(y, 2);

        dm.d0.compute_at(demosaic_output, yi).vectorize(x, kVec);
        dm.d1.compute_at(demosaic_output, yi).vectorize(x, kVec);
        dm.d2.compute_at(demosaic_output, yi).vectorize(x, kVec);
        dm.d3.compute_at(demosaic_output, yi).vectorize(x, kVec);

        // Fuse sRGB into output
        linear_rgb_output.compute_at(output, yi).vectorize(x, kVec);
        output.compute_root()
            .tile(x, y, xo, yo, xi, yi, kTileX, kTileY)
            .parallel(yo)
            .vectorize(xi, kVec);
    }
  }

private:
  Var x{"x"}, y{"y"}, c{"c"}, xo{"xo"}, yo{"yo"}, xi{"xi"}, yi{"yi"};

  Func black_white_level(Func input, const Expr bp, const Expr wp) {
    Func output("black_white_level_output");
    // Reserve headroom (0.25x) for White Balance to prevent clipping
    Expr white_factor = (65535.f / max(1.f, f32(wp) - f32(bp))) * 0.25f;
    output(x, y) = u16_sat((i32(input(x, y)) - bp) * white_factor);
    return output;
  }

  Func white_balance(Func input, const CompiletimeWhiteBalance &wb) {
    Func output("white_balance_output");
    // Highlight Dampening logic
    float saturation_point = 16383.0f;
    float knee_point = 15000.0f;
    auto apply_wb_safe = [&](Expr val, Expr gain) {
        Expr f_val = f32(val);
        Expr alpha = 1.0f - clamp((f_val - knee_point) / (saturation_point - knee_point), 0.0f, 1.0f);
        Expr final_gain = gain * alpha + 1.0f * (1.0f - alpha);
        return u16_sat(final_gain * f_val);
    };
    Expr gain = select(y % 2 == 0,
                       select(x % 2 == 0, wb.r, wb.g0),
                       select(x % 2 == 0, wb.g1, wb.b));
    output(x, y) = apply_wb_safe(input(x, y), gain);
    return output;
  }

  struct DemosaicResult {
      Func output;
      Func d0, d1, d2, d3;
  };

  DemosaicResult demosaic(Func input, Expr width, Expr height) {
    Buffer<int32_t> f0(5, 5, "demosaic_f0");
    Buffer<int32_t> f1(5, 5, "demosaic_f1");
    Buffer<int32_t> f2(5, 5, "demosaic_f2");
    Buffer<int32_t> f3(5, 5, "demosaic_f3");
    f0.translate({-2, -2}); f1.translate({-2, -2}); f2.translate({-2, -2}); f3.translate({-2, -2});

    Func d0("demosaic_0"), d1("demosaic_1"), d2("demosaic_2"), d3("demosaic_3");
    Func output_dm("demosaic_output");
    RDom r0(-2, 5, -2, 5);

    Func input_mirror = BoundaryConditions::mirror_interior(input, {Range(0, width), Range(0, height)});

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

    Expr R_row = y % 2 == 0; Expr B_row = !R_row; Expr R_col = x % 2 == 0; Expr B_col = !R_col;
    Expr at_R = c == 0; Expr at_G = c == 1; Expr at_B = c == 2;
    output_dm(x, y, c) = select(at_R && R_row && B_col, d1(x, y), at_R && B_row && R_col, d2(x, y),
                             at_R && B_row && B_col, d3(x, y), at_G && R_row && R_col, d0(x, y),
                             at_G && B_row && B_col, d0(x, y), at_B && B_row && R_col, d1(x, y),
                             at_B && R_row && B_col, d2(x, y), at_B && R_row && R_col, d3(x, y),
                             input(x, y));
    return {output_dm, d0, d1, d2, d3};
  }

  Func bilateral_filter(Func input, Expr width, Expr height) {
    Buffer<float> k(7, 7, "gauss_kernel");
    k.translate({-3, -3});
    Func weights("bilateral_weights"), total_weights("bilateral_total_weights"), bilateral("bilateral"), output_bf("bilateral_filter_output");
    Var dx, dy; RDom r(-3, 7, -3, 7);
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
    float sig2 = 100.f; float threshold = 25000.f;
    Expr score = select(abs(input_mirror(x + dx, y + dy, c)) > threshold, 0.f, fast_exp(-dist * dist / sig2));
    weights(dx, dy, x, y, c) = k(dx, dy) * score;
    total_weights(x, y, c) = sum(weights(r.x, r.y, x, y, c));
    bilateral(x, y, c) = select(total_weights(x, y, c) > 0.f,
                                sum(input_mirror(x + r.x, y + r.y, c) * weights(r.x, r.y, x, y, c)) / total_weights(x, y, c),
                                f32(input(x, y, c)));
    output_bf(x, y, c) = f32(input(x, y, c));
    output_bf(x, y, 1) = bilateral(x, y, 1);
    output_bf(x, y, 2) = bilateral(x, y, 2);

    if (use_gpu) {
        Var tx{"tx"}, ty{"ty"};
        output_bf.gpu_tile(x, y, tx, ty, xi, yi, 16, 16);
        output_bf.update(0).gpu_tile(x, y, tx, ty, xi, yi, 16, 16);
        output_bf.update(1).gpu_tile(x, y, tx, ty, xi, yi, 16, 16);
    } else {
        weights.compute_at(output_bf, yo).vectorize(x, kVec);
        output_bf.compute_root().tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
        output_bf.update(0).tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
        output_bf.update(1).tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
    }
    return output_bf;
  }

  Func desaturate_noise(Func input, Expr width, Expr height) {
    Func output_dn("desaturate_noise_output");
    Func input_mirror = BoundaryConditions::mirror_image(input, {Range(0, width), Range(0, height)});
    Func blur = gauss_15x15(gauss_15x15(input_mirror, "desaturate_noise_blur1"), "desaturate_noise_blur2");
    float factor = 1.4f; float threshold = 25000.f;
    output_dn(x, y, c) = input(x, y, c);
    output_dn(x, y, 1) = select((abs(blur(x, y, 1)) < factor * abs(input(x, y, 1))) && (abs(input(x, y, 1)) < threshold) && (abs(blur(x, y, 1)) < threshold), .7f * blur(x, y, 1) + .3f * input(x, y, 1), input(x, y, 1));
    output_dn(x, y, 2) = select((abs(blur(x, y, 2)) < factor * abs(input(x, y, 2))) && (abs(input(x, y, 2)) < threshold) && (abs(blur(x, y, 2)) < threshold), .7f * blur(x, y, 2) + .3f * input(x, y, 2), input(x, y, 2));

    if (use_gpu) {
        Var tx{"tx"}, ty{"ty"};
        output_dn.gpu_tile(x, y, tx, ty, xi, yi, 16, 16);
    } else {
        output_dn.compute_root().tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
    }
    return output_dn;
  }

  Func increase_saturation(Func input, float strength) {
    Func output_is("increase_saturation_output");
    output_is(x, y, c) = strength * input(x, y, c);
    output_is(x, y, 0) = input(x, y, 0);
    if (use_gpu) {
        Var tx{"tx"}, ty{"ty"};
        output_is.gpu_tile(x, y, tx, ty, xi, yi, 16, 16);
    } else {
        output_is.compute_root().tile(x, y, xo, yo, xi, yi, kTileX, kTileY).parallel(yo).vectorize(xi, kVec);
    }
    return output_is;
  }

  Func chroma_denoise(Func input, Expr width, Expr height, int num_passes) {
    Func output_denoise = rgb_to_yuv(input);
    int pass = 0;
    if (num_passes > 0) output_denoise = bilateral_filter(output_denoise, width, height);
    pass++;
    while (pass < num_passes) {
      output_denoise = desaturate_noise(output_denoise, width, height);
      pass++;
    }
    if (num_passes > 2) output_denoise = increase_saturation(output_denoise, 1.1f);
    return yuv_to_rgb(output_denoise);
  }

  Func srgb(Func input, Func srgb_matrix) {
    Func output_srgb("srgb_output");
    RDom r(0, 3);
    output_srgb(x, y, c) = u16_sat(sum(srgb_matrix(r, c) * input(x, y, r)));
    return output_srgb;
  }

  Func shift_bayer_to_rggb(Func input, const Expr cfa_pattern) {
    Func output_bayer("rggb_input");
    output_bayer(x, y) = select(cfa_pattern == int(CfaPattern::CFA_RGGB), input(x, y),
                          cfa_pattern == int(CfaPattern::CFA_GRBG), input(x + 1, y),
                          cfa_pattern == int(CfaPattern::CFA_GBRG), input(x, y + 1),
                          cfa_pattern == int(CfaPattern::CFA_BGGR), input(x + 1, y + 1), 0);
    return output_bayer;
  }
};

} // namespace

HALIDE_REGISTER_GENERATOR(HdrPlusRawPipeline, hdrplus_raw_pipeline)

#include <Halide.h>

using namespace Halide;
using namespace Halide::ConciseCasts;

namespace {

enum class CfaPattern : int {
  CFA_UNKNOWN = 0,
  CFA_RGGB = 1,
  CFA_GRBG = 2,
  CFA_BGGR = 3,
  CFA_GBRG = 4
};

template <class T = float> struct TypedWhiteBalance {
  TypedWhiteBalance(T r, T g0, T g1, T b) : r(r), g0(g0), g1(g1), b(b) {}
  T r;
  T g0;
  T g1;
  T b;
};

using CompiletimeWhiteBalance = TypedWhiteBalance<Halide::Expr>;

class SingleFrameRawPipeline : public Generator<SingleFrameRawPipeline> {
public:
  GeneratorParam<bool> use_optimized_schedule{"use_optimized_schedule", true};
  GeneratorParam<bool> use_gpu{"use_gpu", false};

  // Only one input frame
  Input<Buffer<uint16_t>> input{"input", 2};
  Input<uint16_t> black_point_r{"black_point_r"};
  Input<uint16_t> black_point_g0{"black_point_g0"};
  Input<uint16_t> black_point_g1{"black_point_g1"};
  Input<uint16_t> black_point_b{"black_point_b"};
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
    CompiletimeWhiteBalance wb{white_balance_r, white_balance_g0,
                               white_balance_g1, white_balance_b};

    Func bayer_shifted = shift_bayer_to_rggb(input, cfa_pattern);
    Func black_white_level_output = black_white_level(bayer_shifted, black_point_r, black_point_g0, black_point_g1, black_point_b, white_point);
    Func white_balance_output = white_balance(black_white_level_output, wb);

    // Demosaic
    DemosaicResult dm = demosaic(white_balance_output, input.width(), input.height());
    Func demosaic_output = dm.output;

    // Direct to sRGB (skip denoise)
    Func linear_rgb_output = srgb(demosaic_output, ccm);
    output(x, y, c) = linear_rgb_output(x, y, c);

    // --- Scheduling ---
    if (use_gpu) {
       // CPU only for now
    } else if (use_optimized_schedule) {
      const int vec = 8;
      output.compute_root().vectorize(x, vec).parallel(y);
      linear_rgb_output.compute_at(output, y).vectorize(x, vec);
      demosaic_output.compute_at(output, y).vectorize(x, vec);
      white_balance_output.compute_root().vectorize(x, vec).parallel(y);
      black_white_level_output.compute_root().vectorize(x, vec).parallel(y);
      bayer_shifted.compute_root().vectorize(x, vec).parallel(y);
    }
  }

private:
  Var x{"x"}, y{"y"}, c{"c"};

  Func shift_bayer_to_rggb(Func input, const Expr cfa_pattern) {
    Func output_bayer("rggb_input");
    output_bayer(x, y) = select(cfa_pattern == int(CfaPattern::CFA_RGGB), input(x, y),
                          cfa_pattern == int(CfaPattern::CFA_GRBG), input(x + 1, y),
                          cfa_pattern == int(CfaPattern::CFA_GBRG), input(x, y + 1),
                          cfa_pattern == int(CfaPattern::CFA_BGGR), input(x + 1, y + 1), 0);
    return output_bayer;
  }

  Func black_white_level(Func input, const Expr bp_r, const Expr bp_g0, const Expr bp_g1, const Expr bp_b, const Expr wp) {
    Func output("black_white_level_output");
    Expr bp = select(y % 2 == 0,
                     select(x % 2 == 0, bp_r, bp_g0),
                     select(x % 2 == 0, bp_g1, bp_b));
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
  };

  DemosaicResult demosaic(Func input, Expr width, Expr height) {
    // Malvar-He-Cutler demosaicing (borrowed from hdrplus_pipeline_generator.cpp)
    Func output("demosaic_output");

    Func clamped_input("clamped_input_demosaic");
    clamped_input(x, y) = input(clamp(x, 0, width - 1), clamp(y, 0, height - 1));

    // Green interpolation
    Func g("g");
    g(x, y) = select(
        (x % 2 == 0) && (y % 2 == 0),
        clamp(
            i32(clamped_input(x, y)) * 4 +
                i32(clamped_input(x - 1, y) + clamped_input(x + 1, y) +
                    clamped_input(x, y - 1) + clamped_input(x, y + 1)) * 2 -
                i32(clamped_input(x, y - 2) + clamped_input(x, y + 2) +
                    clamped_input(x - 2, y) + clamped_input(x + 2, y)),
            0, 65535 * 8) / 8,
        (x % 2 == 1) && (y % 2 == 1),
        clamp(
            i32(clamped_input(x, y)) * 4 +
                i32(clamped_input(x - 1, y) + clamped_input(x + 1, y) +
                    clamped_input(x, y - 1) + clamped_input(x, y + 1)) * 2 -
                i32(clamped_input(x, y - 2) + clamped_input(x, y + 2) +
                    clamped_input(x - 2, y) + clamped_input(x + 2, y)),
            0, 65535 * 8) / 8,
        i32(clamped_input(x, y)));

    // Red and blue interpolation
    Func r("r"), b("b");
    r(x, y) = select(
        (x % 2 == 1) && (y % 2 == 0),
        clamp(
            i32(clamped_input(x - 1, y) + clamped_input(x + 1, y)) * 4 +
                i32(clamped_input(x, y)) * 5 -
                i32(clamped_input(x, y - 2) + clamped_input(x, y + 2)) * 1 -
                i32(clamped_input(x - 1, y - 1) + clamped_input(x + 1, y - 1) +
                    clamped_input(x - 1, y + 1) + clamped_input(x + 1, y + 1)) * 1 +
                i32(clamped_input(x - 2, y) + clamped_input(x + 2, y)) * 1 / 2,
            0, 65535 * 8) / 8,
        (x % 2 == 0) && (y % 2 == 1),
        clamp(
            i32(clamped_input(x, y - 1) + clamped_input(x, y + 1)) * 4 +
                i32(clamped_input(x, y)) * 5 -
                i32(clamped_input(x - 2, y) + clamped_input(x + 2, y)) * 1 -
                i32(clamped_input(x - 1, y - 1) + clamped_input(x + 1, y - 1) +
                    clamped_input(x - 1, y + 1) + clamped_input(x + 1, y + 1)) * 1 +
                i32(clamped_input(x, y - 2) + clamped_input(x, y + 2)) * 1 / 2,
            0, 65535 * 8) / 8,
        (x % 2 == 1) && (y % 2 == 1),
        clamp(
            i32(clamped_input(x - 1, y - 1) + clamped_input(x + 1, y - 1) +
                clamped_input(x - 1, y + 1) + clamped_input(x + 1, y + 1)) * 2 +
                i32(clamped_input(x, y)) * 6 -
                i32(clamped_input(x - 2, y) + clamped_input(x + 2, y) +
                    clamped_input(x, y - 2) + clamped_input(x, y + 2)) * 3 / 2,
            0, 65535 * 8) / 8,
        i32(clamped_input(x, y)));

    b(x, y) = select(
        (x % 2 == 0) && (y % 2 == 1),
        clamp(
            i32(clamped_input(x - 1, y) + clamped_input(x + 1, y)) * 4 +
                i32(clamped_input(x, y)) * 5 -
                i32(clamped_input(x, y - 2) + clamped_input(x, y + 2)) * 1 -
                i32(clamped_input(x - 1, y - 1) + clamped_input(x + 1, y - 1) +
                    clamped_input(x - 1, y + 1) + clamped_input(x + 1, y + 1)) * 1 +
                i32(clamped_input(x - 2, y) + clamped_input(x + 2, y)) * 1 / 2,
            0, 65535 * 8) / 8,
        (x % 2 == 1) && (y % 2 == 0),
        clamp(
            i32(clamped_input(x, y - 1) + clamped_input(x, y + 1)) * 4 +
                i32(clamped_input(x, y)) * 5 -
                i32(clamped_input(x - 2, y) + clamped_input(x + 2, y)) * 1 -
                i32(clamped_input(x - 1, y - 1) + clamped_input(x + 1, y - 1) +
                    clamped_input(x - 1, y + 1) + clamped_input(x + 1, y + 1)) * 1 +
                i32(clamped_input(x, y - 2) + clamped_input(x, y + 2)) * 1 / 2,
            0, 65535 * 8) / 8,
        (x % 2 == 0) && (y % 2 == 0),
        clamp(
            i32(clamped_input(x - 1, y - 1) + clamped_input(x + 1, y - 1) +
                clamped_input(x - 1, y + 1) + clamped_input(x + 1, y + 1)) * 2 +
                i32(clamped_input(x, y)) * 6 -
                i32(clamped_input(x - 2, y) + clamped_input(x + 2, y) +
                    clamped_input(x, y - 2) + clamped_input(x, y + 2)) * 3 / 2,
            0, 65535 * 8) / 8,
        i32(clamped_input(x, y)));

    output(x, y, c) = u16_sat(select(c == 0, r(x, y), c == 1, g(x, y), b(x, y)));
    return {output};
  }

  Func srgb(Func input, const Input<Buffer<float>>& ccm) {
    Func output("srgb_output");
    Expr r = f32(input(x, y, 0));
    Expr g = f32(input(x, y, 1));
    Expr b = f32(input(x, y, 2));

    Expr out_r = r * ccm(0, 0) + g * ccm(1, 0) + b * ccm(2, 0);
    Expr out_g = r * ccm(0, 1) + g * ccm(1, 1) + b * ccm(2, 1);
    Expr out_b = r * ccm(0, 2) + g * ccm(1, 2) + b * ccm(2, 2);

    output(x, y, c) = u16_sat(select(c == 0, out_r, c == 1, out_g, out_b));
    return output;
  }
};

} // namespace

HALIDE_REGISTER_GENERATOR(SingleFrameRawPipeline, single_frame_raw_pipeline)

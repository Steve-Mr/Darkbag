#pragma once

namespace darkbag {
namespace gpu {

static const char* kColorPipeVertexShader = R"glsl(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

uniform int uOrientation;
uniform int uMirror;
uniform float uZoomFactor;

out vec2 vTexCoord;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vec2 tc = aTexCoord;

    // Apply mirror
    if (uMirror != 0) {
        tc.x = 1.0 - tc.x;
    }

    // Apply rotation
    if (uOrientation == 90) {
        tc = vec2(tc.y, 1.0 - tc.x);
    } else if (uOrientation == 180) {
        tc = vec2(1.0 - tc.x, 1.0 - tc.y);
    } else if (uOrientation == 270) {
        tc = vec2(1.0 - tc.y, tc.x);
    }

    // Apply zoom crop around center
    if (uZoomFactor > 1.001) {
        tc = (tc - 0.5) / uZoomFactor + 0.5;
    }

    vTexCoord = tc;
}
)glsl";

static const char* kColorPipeFragmentShader = R"glsl(#version 300 es
precision highp float;
precision highp usampler2D;
precision mediump sampler3D;

uniform highp usampler2D uTexR;
uniform highp usampler2D uTexG;
uniform highp usampler2D uTexB;

uniform sampler3D uLut3D;
uniform int uHasLut;
uniform float uLutSize;

uniform vec3 uWbGain;
uniform float uDigitalGain;
uniform mat3 uColorTransform; // Combined: WideGamut * sRGB2XYZ * SensorCCM
uniform int uTargetLog;
uniform int uColorEngineMode;

uniform float uContrast;
uniform float uSaturation;

uniform int uHasHswb;
uniform float uHighlights;
uniform float uShadows;
uniform float uWhites;
uniform float uBlacks;

in vec2 vTexCoord;
out vec4 fragColor;

// 1. High-precision base-10 log helper
float log10_f(float x) {
    return log(max(x, 1e-7)) * 0.4342944819;
}

// 2. Linear to sRGB OETF
float linearToSrgb(float c) {
    float low = 12.92 * max(c, 0.0);
    float high = 1.055 * pow(max(c, 0.0), 1.0 / 2.4) - 0.055;
    return mix(low, high, step(0.0031308, c));
}

vec3 linearToSrgb(vec3 c) {
    vec3 low = 12.92 * max(c, vec3(0.0));
    vec3 high = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - vec3(0.055);
    return mix(low, high, step(vec3(0.0031308), c));
}

// 3. Khronos PBR Neutral Tone Mapper
vec3 applyPbrNeutral(vec3 color) {
    const float startCompression = 0.8 - 0.04;
    const float desaturation = 0.15;

    float x = min(color.r, min(color.g, color.b));
    float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
    color -= offset;

    float peak = max(color.r, max(color.g, color.b));
    if (peak < startCompression) return max(vec3(0.0), color);

    const float d = 1.0 - startCompression;
    float newPeak = 1.0 - d * d / (peak + d - startCompression);
    color *= newPeak / peak;

    float g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0);
    return clamp(mix(color, vec3(newPeak), g), 0.0, 1.0);
}

// 4. Pure Luma Filmic Tone Mapper
float filmicLumaCurve(float Y) {
    if (Y <= 0.0) return 0.0;
    const float A = 2.35;
    const float B = 0.02;
    const float C = 2.35;
    const float D = 0.70;
    const float E = 0.12;
    return clamp((Y * (A * Y + B)) / (Y * (C * Y + D) + E), 0.0, 1.0);
}

vec3 applyPureLuma(vec3 c) {
    float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
    if (luma <= 0.00001) return vec3(0.0);

    float mappedLuma = filmicLumaCurve(luma);
    float scale = mappedLuma / luma;
    vec3 outCol = c * scale;

    if (mappedLuma > 0.85) {
        float t = (mappedLuma - 0.85) / 0.15;
        float factor = 1.0 - (t * t * (3.0 - 2.0 * t)) * 0.5;
        outCol = vec3(mappedLuma) + (outCol - vec3(mappedLuma)) * factor;
    }
    return clamp(outCol, 0.0, 1.0);
}

// 5. Sony Uchimura Tone Mapper
float uchimuraScalar(float x) {
    if (x <= 0.0) return 0.0;
    const float P = 1.0;
    const float a = 1.25;
    const float m = 0.22;
    const float l = 0.40;
    const float c = 1.33;
    const float b = 0.0;

    const float l0 = ((P - m) * l) / a;
    const float S0 = m + l0;
    const float S1 = m + a * l0;
    const float C2 = (a * P) / (P - S1);
    const float CP = -C2 / P;

    if (x <= m) {
        return m * pow(x / m, c) + b;
    } else if (x < m + l0) {
        return m + a * (x - m);
    } else {
        return P - (P - S1) * exp(CP * (x - S0));
    }
}

vec3 applySonyUchimura(vec3 c) {
    float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
    if (luma <= 0.00001) return vec3(0.0);

    float mappedLuma = uchimuraScalar(luma);
    float scale = mappedLuma / luma;
    vec3 outCol = c * scale;

    if (mappedLuma > 0.85) {
        float t = (mappedLuma - 0.85) / 0.15;
        float factor = 1.0 - (t * t * (3.0 - 2.0 * t)) * 0.5;
        outCol = vec3(mappedLuma) + (outCol - vec3(mappedLuma)) * factor;
    }
    return clamp(outCol, 0.0, 1.0);
}

// 6. ACES Fit Tone Mapper
vec3 applyAcesFit(vec3 v) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((v * (a * v + b)) / (v * (c * v + d) + e), 0.0, 1.0);
}

// 7. Analytic Camera Log Curves (Identical to ColorPipe.cpp)
float applyLogCurve(float x, int type) {
    if (type != 12) {
        x = max(x, 0.0);
    }
    if (type == 1) { // Arri LogC3
        if (x > 0.010591) return 0.247190 * log10_f(5.555556 * x + 0.052272) + 0.385537;
        else return 5.367655 * x + 0.092809;
    } else if (type == 2) { // F-Log
        if (x >= 0.00089) return 0.344676 * log10_f(0.555556 * x + 0.009468) + 0.790453;
        else return 8.52 * x + 0.0929;
    } else if (type == 3 || type == 4) { // F-Log2 / F-Log2 C
        if (x >= 0.000889) return 0.245281 * log10_f(5.555556 * x + 0.064829) + 0.384316;
        else return 8.799461 * x + 0.092864;
    } else if (type == 5 || type == 6) { // S-Log3 / S-Log3.Cine
        if (x >= 0.011250) return (420.0 + log10_f((x + 0.01) / 0.19) * 261.5) / 1023.0;
        else return (x * 171.2102946929 + 95.0) / 1023.0;
    } else if (type == 7) { // V-Log
        if (x >= 0.01) return 0.241514 * log10_f(x + 0.008730) + 0.598206;
        else return 5.6 * x + 0.125;
    } else if (type == 8) { // Canon Log 2
        float xr = max(0.0, x / 0.9);
        return 0.24136077 * log10_f(xr * 87.09937546 + 1.0) + 0.092864125;
    } else if (type == 9) { // Canon Log 3
        float xr = max(0.0, x / 0.9);
        if (xr <= 0.014) return 1.9754798 * xr + 0.12512219;
        else return 0.36726845 * log10_f(xr * 14.98325 + 1.0) + 0.12240537;
    } else if (type == 10) { // N-Log
        if (x < 0.328) return (650.0 * pow(max(0.0, x + 0.0075), 1.0 / 3.0)) / 1023.0;
        else return (150.0 * log(max(x, 1e-7)) + 619.0) / 1023.0;
    } else if (type == 11) { // D-Log
        if (x <= 0.0078) return 6.025 * x + 0.0929;
        else return log10_f(x * 0.9892 + 0.0108) * 0.256663 + 0.584555;
    } else if (type == 12) { // Log3G10
        const float a = 0.224282;
        const float b = 155.975327;
        const float c = 0.01;
        if (x >= 0.0) return a * log10_f(x * b + 1.0) + c;
        else return -a * log10_f(-x * b + 1.0) + c;
    } else { // Default sRGB: Standard sRGB OETF transfer curve
        return linearToSrgb(x);
    }
}

// 8. True Triangular PDF (TPDF) dithering (+-1.0 LSB amplitude)
vec3 triangularDither(vec3 color, vec2 coord) {
    vec3 n1 = fract(sin(vec3(
        dot(coord, vec2(12.9898, 78.233)),
        dot(coord, vec2(63.7264, 10.873)),
        dot(coord, vec2(78.2330, 12.989))
    )) * 43758.5453);
    vec3 n2 = fract(sin(vec3(
        dot(coord, vec2(93.9898, 67.345)),
        dot(coord, vec2(41.1234, 39.876)),
        dot(coord, vec2(27.8192, 84.192))
    )) * 24634.6345);
    vec3 dither = (n1 - n2) / 255.0;
    return clamp(color + dither, 0.0, 1.0);
}

// 9. Highlights, Shadows, Whites, Blacks (HSWB in Log space)
float applyHswb(float v) {
    if (uHighlights != 0.0) {
        float cv = clamp(v, 0.0, 1.0);
        v += uHighlights * (cv * cv) * 0.2;
    }
    if (uShadows != 0.0) {
        float sv = 1.0 - clamp(v, 0.0, 1.0);
        v += uShadows * (sv * sv) * 0.2;
    }
    if (uWhites != 0.0) {
        float weight = clamp((v - 0.5) * 2.0, 0.0, 1.0);
        v += uWhites * weight * 0.2;
    }
    if (uBlacks != 0.0) {
        float weight = clamp((0.5 - v) * 2.0, 0.0, 1.0);
        v += uBlacks * weight * 0.2;
    }
    return max(0.0, v);
}

void main() {
    // 1. Fetch 16-bit linear sensor RGB from planar textures
    // Note: All planar textures are GL_R16UI (single-channel red integer), so each channel data resides in .r
    float rawR = float(texture(uTexR, vTexCoord).r) / 65535.0;
    float rawG = float(texture(uTexG, vTexCoord).r) / 65535.0;
    float rawB = float(texture(uTexB, vTexCoord).r) / 65535.0;
    float rawMax = max(rawR, max(rawG, rawB));

    // 2. White Balance Gains & Digital Gain
    float r = rawR * uWbGain.r * uDigitalGain;
    float g = rawG * uWbGain.g * uDigitalGain;
    float b = rawB * uWbGain.b * uDigitalGain;

    // 3. Combined Color Matrix Transformation (Sensor -> Target Wide Gamut / Rec709)
    vec3 color = max(vec3(0.0), uColorTransform * vec3(r, g, b));

    // Highlight desaturation protection for blown sensor pixels to prevent magenta/pink clipping fringes
    if (rawMax > 0.98) {
        float blendFactor = smoothstep(0.98, 1.0, rawMax);
        float peakLuma = max(color.r, max(color.g, color.b));
        color = mix(color, vec3(peakLuma), blendFactor);
    }

    // 4. Tone Mapping & Log / OETF Transfer
    if (uTargetLog == 0 && uHasLut == 0) {
        // Natural Multi-Engine Pipeline
        if (uColorEngineMode == 0) {
            color = applyPbrNeutral(color);
        } else if (uColorEngineMode == 1) {
            color = applyPureLuma(color);
        } else if (uColorEngineMode == 2) {
            color = applySonyUchimura(color);
        } else {
            color = applyAcesFit(color);
        }
        color = linearToSrgb(color);
    } else {
        // Standard Log / LUT pipeline
        color = vec3(
            applyLogCurve(color.r, uTargetLog),
            applyLogCurve(color.g, uTargetLog),
            applyLogCurve(color.b, uTargetLog)
        );
    }

    // 5. Contrast & Saturation (in Log / Gamma Domain)
    if (uContrast != 0.0) {
        color = max(vec3(0.0), (color - 0.5) * (uContrast + 1.0) + 0.5);
    }
    if (uSaturation != 0.0) {
        float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
        color = max(vec3(0.0), luma + (color - luma) * (uSaturation + 1.0));
    }

    // 6. Highlights / Shadows / Whites / Blacks (HSWB)
    if (uHasHswb != 0) {
        color = vec3(applyHswb(color.r), applyHswb(color.g), applyHswb(color.b));
    }

    // 7. 3D LUT Sampling with Half-Texel Correction
    if (uHasLut != 0) {
        float lutSizeFloat = float(uLutSize);
        vec3 clampedColor = clamp(color, 0.0, 1.0);
        vec3 lutCoord = clampedColor * ((lutSizeFloat - 1.0) / lutSizeFloat) + vec3(0.5 / lutSizeFloat);
        color = texture(uLut3D, lutCoord).rgb;
    }

    // 8. TPDF Spatial Dithering to eliminate 8-bit banding
    color = triangularDither(color, gl_FragCoord.xy);

    fragColor = vec4(color, 1.0);
}
)glsl";

} // namespace gpu
} // namespace darkbag

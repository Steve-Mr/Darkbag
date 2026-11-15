package top.maary.darkbag.processor

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * OpenGL ES 3.0 shader pipeline for real-time camera preview & encoder rendering.
 *
 * Encapsulates:
 * - CIE XYZ wide-gamut conversion matrices matching native `ColorPipe.cpp`
 * - Vertex & fragment shader sources (Log curves, 3D LUT sampling, Color Engines, TPDF dithering, Focus Peaking)
 * - Program compilation/linking, 3D LUT `.cube` texture upload, and fullscreen quad drawing
 */
internal class LutGlShaderPipeline {

    private var program = 0
    private var lutTextureId = 0
    private var dummyLutTextureId = 0

    private var colorEngineModeLoc = -1
    private var scaleLoc = -1
    private var textureLoc = -1
    private var textureMatrixLoc = -1
    private var lutLoc = -1
    private var gamutMatrixLoc = -1
    private var logTypeLoc = -1
    private var lutSizeLoc = -1
    private var focusPeakingLoc = -1
    private var texelSizeLoc = -1
    private var posHandle = -1
    private var texHandle = -1

    private val vertexData = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(vertexData).also { it.position(0) }

    fun initProgramAndDummyLut() {
        createProgram()
        createDummyLut()
    }

    fun uploadLutTexture(lutData: FloatArray, size: Int) {
        if (lutTextureId == 0) {
            val texs = IntArray(1)
            GLES30.glGenTextures(1, texs, 0)
            lutTextureId = texs[0]
        }
        configure3dTexture(lutTextureId, GLES30.GL_LINEAR)
        val buffer = ByteBuffer.allocateDirect(lutData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(lutData)
        buffer.position(0)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, size, size, size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buffer)
    }

    fun drawFrame(
        targetWidth: Int,
        targetHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        inputTextureId: Int,
        transformMatrix: FloatArray,
        currentLutSize: Int,
        currentLogType: Int,
        currentGamutMatrix: FloatArray,
        currentColorEngineMode: Int,
        focusPeakingEnabled: Boolean
    ) {
        GLES30.glViewport(0, 0, targetWidth, targetHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val (scaleX, scaleY) = computeAspectScale(targetWidth, targetHeight, inputWidth, inputHeight, transformMatrix)
        if (scaleLoc >= 0) GLES30.glUniform2f(scaleLoc, scaleX, scaleY)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        if (textureLoc >= 0) GLES30.glUniform1i(textureLoc, 0)
        if (textureMatrixLoc >= 0) GLES30.glUniformMatrix4fv(textureMatrixLoc, 1, false, transformMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        val activeLutTex = if (currentLutSize > 0 && lutTextureId != 0) lutTextureId else dummyLutTextureId
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, activeLutTex)
        if (lutLoc >= 0) GLES30.glUniform1i(lutLoc, 1)

        if (gamutMatrixLoc >= 0) GLES30.glUniformMatrix3fv(gamutMatrixLoc, 1, false, currentGamutMatrix, 0)
        if (logTypeLoc >= 0) GLES30.glUniform1i(logTypeLoc, currentLogType)
        if (lutSizeLoc >= 0) GLES30.glUniform1i(lutSizeLoc, currentLutSize)
        if (colorEngineModeLoc >= 0) GLES30.glUniform1i(colorEngineModeLoc, currentColorEngineMode)
        if (focusPeakingLoc >= 0) GLES30.glUniform1i(focusPeakingLoc, if (focusPeakingEnabled) 1 else 0)
        if (focusPeakingEnabled && texelSizeLoc >= 0) {
            val tw = if (inputWidth > 0) 1.0f / inputWidth.toFloat() else 1.0f / 1080f
            val th = if (inputHeight > 0) 1.0f / inputHeight.toFloat() else 1.0f / 1920f
            GLES30.glUniform2f(texelSizeLoc, tw, th)
        }

        if (posHandle >= 0) {
            vertexBuffer.position(0)
            GLES30.glVertexAttribPointer(posHandle, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES30.glEnableVertexAttribArray(posHandle)
        }
        if (texHandle >= 0) {
            vertexBuffer.position(2)
            GLES30.glVertexAttribPointer(texHandle, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES30.glEnableVertexAttribArray(texHandle)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        if (posHandle >= 0) GLES30.glDisableVertexAttribArray(posHandle)
        if (texHandle >= 0) GLES30.glDisableVertexAttribArray(texHandle)
    }

    fun releaseGlResources() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        val textures = IntArray(2)
        var count = 0
        if (lutTextureId != 0) textures[count++] = lutTextureId
        if (dummyLutTextureId != 0) textures[count++] = dummyLutTextureId
        if (count > 0) GLES30.glDeleteTextures(count, textures, 0)
        lutTextureId = 0
        dummyLutTextureId = 0
    }

    private fun configure3dTexture(textureId: Int, filter: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun createDummyLut() {
        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        dummyLutTextureId = texs[0]
        configure3dTexture(dummyLutTextureId, GLES30.GL_NEAREST)
        val size = 2
        val buffer = ByteBuffer.allocateDirect(size * size * size * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until size * size * size * 3) buffer.put(0f)
        buffer.position(0)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, size, size, size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buffer)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
    }

    private fun createProgram() {
        val vShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        val fShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vShader)
        GLES30.glAttachShader(program, fShader)
        GLES30.glLinkProgram(program)

        scaleLoc = GLES30.glGetUniformLocation(program, "uScale")
        textureLoc = GLES30.glGetUniformLocation(program, "uTexture")
        textureMatrixLoc = GLES30.glGetUniformLocation(program, "uTextureMatrix")
        lutLoc = GLES30.glGetUniformLocation(program, "uLut")
        gamutMatrixLoc = GLES30.glGetUniformLocation(program, "uGamutMatrix")
        logTypeLoc = GLES30.glGetUniformLocation(program, "uLogType")
        lutSizeLoc = GLES30.glGetUniformLocation(program, "uLutSize")
        colorEngineModeLoc = GLES30.glGetUniformLocation(program, "uColorEngineMode")
        focusPeakingLoc = GLES30.glGetUniformLocation(program, "uFocusPeakingEnabled")
        texelSizeLoc = GLES30.glGetUniformLocation(program, "uTexelSize")

        posHandle = GLES30.glGetAttribLocation(program, "aPosition")
        texHandle = GLES30.glGetAttribLocation(program, "aTexCoord")
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("LutSurfaceProcessor", "Shader Error: " + GLES30.glGetShaderInfoLog(shader))
            return 0
        }
        return shader
    }

    companion object {
        // Standard CIE XYZ and Gamut Matrices (Row-Major definitions matching ColorPipe.cpp)
        private val M_sRGB_D65_to_XYZ = floatArrayOf(0.41239080f, 0.35758434f, 0.18048079f, 0.21263901f, 0.71516868f, 0.07219232f, 0.01933082f, 0.11919478f, 0.95053215f)
        private val M_XYZ_to_AlexaWideGamut_D65 = floatArrayOf(1.78906555f, -0.48253386f, -0.20007579f, -0.63984866f, 1.39639996f, 0.19443229f, -0.04153155f, 0.08233537f, 0.87886848f)
        private val M_XYZ_to_Rec2020_D65 = floatArrayOf(1.71665119f, -0.35567078f, -0.25336628f, -0.66668435f, 1.61648124f, 0.01576855f, 0.01763986f, -0.04277061f, 0.94210312f)
        private val M_XYZ_to_SGamut3Cine_D65 = floatArrayOf(1.84677897f, -0.52598612f, -0.21054521f, -0.44415326f, 1.25944290f, 0.14939997f, 0.04085542f, 0.01564089f, 0.86820725f)
        private val M_XYZ_to_VGamut_D65 = floatArrayOf(1.59387222f, -0.31417914f, -0.18431177f, -0.51815173f, 1.35539124f, 0.12587867f, 0.01117945f, 0.00319413f, 0.90553536f)
        private val M_XYZ_to_Rec709_D65 = floatArrayOf(3.24096994f, -1.53738318f, -0.49861076f, -0.96924364f, 1.87596750f, 0.04155506f, 0.05563008f, -0.20397696f, 1.05697151f)

        private fun multiply3x3ToColMajor(aRowMajor: FloatArray, bRowMajor: FloatArray): FloatArray {
            val colMajor = FloatArray(9)
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    colMajor[c * 3 + r] = aRowMajor[r * 3] * bRowMajor[c] +
                        aRowMajor[r * 3 + 1] * bRowMajor[3 + c] +
                        aRowMajor[r * 3 + 2] * bRowMajor[6 + c]
                }
            }
            return colMajor
        }

        fun getGamutMatrix(targetLog: Int): FloatArray {
            val targetM = when (targetLog) {
                1 -> M_XYZ_to_AlexaWideGamut_D65
                2, 3, 4, 8, 9, 10, 11, 12 -> M_XYZ_to_Rec2020_D65
                5, 6 -> M_XYZ_to_SGamut3Cine_D65
                7 -> M_XYZ_to_VGamut_D65
                else -> M_XYZ_to_Rec709_D65
            }
            return multiply3x3ToColMajor(targetM, M_sRGB_D65_to_XYZ)
        }

        internal fun computeAspectScale(
            targetWidth: Int,
            targetHeight: Int,
            inputWidth: Int,
            inputHeight: Int,
            transformMatrix: FloatArray
        ): Pair<Float, Float> {
            var scaleX = 1f
            var scaleY = 1f
            if (targetWidth > 0 && targetHeight > 0 && inputWidth > 0 && inputHeight > 0) {
                val rotated = abs(transformMatrix[1]) > abs(transformMatrix[0])
                val inW = if (rotated) inputHeight.toFloat() else inputWidth.toFloat()
                val inH = if (rotated) inputWidth.toFloat() else inputHeight.toFloat()
                val inAspect = inW / inH
                val outAspect = targetWidth.toFloat() / targetHeight.toFloat()
                if (inAspect > outAspect) scaleX = inAspect / outAspect else scaleY = outAspect / inAspect
            }
            return scaleX to scaleY
        }

        internal val VERTEX_SHADER_SRC = """
            #version 300 es
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            uniform vec2 uScale;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.x * uScale.x, aPosition.y * uScale.y, 0.0, 1.0);
                vTexCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        internal val FRAGMENT_SHADER_SRC = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp sampler3D;
            uniform samplerExternalOES uTexture;
            uniform sampler3D uLut;
            uniform mat3 uGamutMatrix;
            uniform int uLogType;
            uniform int uLutSize;
            uniform int uColorEngineMode;
            uniform int uFocusPeakingEnabled;
            uniform vec2 uTexelSize;
            in vec2 vTexCoord;
            out vec4 outColor;

            vec3 srgbToLinear(vec3 c) {
                return mix(c / 12.92, pow((c + vec3(0.055)) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
            }
            float linearToSrgb(float c) {
                return mix(12.92 * max(c, 0.0), 1.055 * pow(max(c, 0.0), 1.0 / 2.4) - 0.055, step(0.0031308, c));
            }
            vec3 linearToSrgb(vec3 c) {
                return mix(12.92 * max(c, vec3(0.0)), 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - vec3(0.055), step(vec3(0.0031308), c));
            }
            float log10_f(float x) { return log(max(x, 1e-7)) * 0.4342944819; }

            vec3 applyPbrNeutral(vec3 color) {
                const float startCompression = 0.8 - 0.04;
                const float desaturation = 0.15;
                float x = min(color.r, min(color.g, color.b));
                color -= (x < 0.08 ? x - 6.25 * x * x : 0.04);
                float peak = max(color.r, max(color.g, color.b));
                if (peak < startCompression) return max(vec3(0.0), color);
                const float d = 1.0 - startCompression;
                float newPeak = 1.0 - d * d / (peak + d - startCompression);
                color *= newPeak / peak;
                float g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0);
                return clamp(mix(color, vec3(newPeak), g), 0.0, 1.0);
            }

            float filmicLumaCurve(float Y) {
                if (Y <= 0.0) return 0.0;
                return clamp((Y * (2.35 * Y + 0.02)) / (Y * (2.35 * Y + 0.70) + 0.12), 0.0, 1.0);
            }

            vec3 applyPureLuma(vec3 c) {
                float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
                if (luma <= 0.00001) return vec3(0.0);
                float mappedLuma = filmicLumaCurve(luma);
                vec3 outCol = c * (mappedLuma / luma);
                if (mappedLuma > 0.85) {
                    float t = (mappedLuma - 0.85) / 0.15;
                    outCol = vec3(mappedLuma) + (outCol - vec3(mappedLuma)) * (1.0 - (t * t * (3.0 - 2.0 * t)) * 0.5);
                }
                return clamp(outCol, 0.0, 1.0);
            }

            float uchimuraScalar(float x) {
                if (x <= 0.0) return 0.0;
                const float P = 1.0, a = 1.25, m = 0.22, l = 0.40, c = 1.33, b = 0.0;
                const float l0 = ((P - m) * l) / a, S0 = m + l0, S1 = m + a * l0;
                const float CP = -((a * P) / (P - S1)) / P;
                if (x <= m) return m * pow(x / m, c) + b;
                if (x < m + l0) return m + a * (x - m);
                return P - (P - S1) * exp(CP * (x - S0));
            }

            vec3 applySonyUchimura(vec3 c) {
                float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
                if (luma <= 0.00001) return vec3(0.0);
                float mappedLuma = uchimuraScalar(luma);
                vec3 outCol = c * (mappedLuma / luma);
                if (mappedLuma > 0.85) {
                    float t = (mappedLuma - 0.85) / 0.15;
                    outCol = vec3(mappedLuma) + (outCol - vec3(mappedLuma)) * (1.0 - (t * t * (3.0 - 2.0 * t)) * 0.5);
                }
                return clamp(outCol, 0.0, 1.0);
            }

            vec3 applyAcesFit(vec3 v) {
                return clamp((v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14), 0.0, 1.0);
            }

            float applyLogCurve(float x, int type) {
                if (type != 12) x = max(x, 0.0);
                if (type == 1) return x > 0.010591 ? 0.247190 * log10_f(5.555556 * x + 0.052272) + 0.385537 : 5.367655 * x + 0.092809;
                if (type == 2) return x >= 0.00089 ? 0.344676 * log10_f(0.555556 * x + 0.009468) + 0.790453 : 8.52 * x + 0.0929;
                if (type == 3 || type == 4) return x >= 0.000889 ? 0.245281 * log10_f(5.555556 * x + 0.064829) + 0.384316 : 8.799461 * x + 0.092864;
                if (type == 5 || type == 6) return x >= 0.011250 ? (420.0 + log10_f((x + 0.01) / 0.19) * 261.5) / 1023.0 : (x * 171.2102946929 + 95.0) / 1023.0;
                if (type == 7) return x >= 0.01 ? 0.241514 * log10_f(x + 0.008730) + 0.598206 : 5.6 * x + 0.125;
                if (type == 8) return 0.24136077 * log10_f(max(0.0, x / 0.9) * 87.09937546 + 1.0) + 0.092864125;
                if (type == 9) {
                    float xr = max(0.0, x / 0.9);
                    return xr <= 0.014 ? 1.9754798 * xr + 0.12512219 : 0.36726845 * log10_f(xr * 14.98325 + 1.0) + 0.12240537;
                }
                if (type == 10) return x < 0.328 ? (650.0 * pow(max(0.0, x + 0.0075), 1.0 / 3.0)) / 1023.0 : (150.0 * log(max(x, 1e-7)) + 619.0) / 1023.0;
                if (type == 11) return x <= 0.0078 ? 6.025 * x + 0.0929 : log10_f(x * 0.9892 + 0.0108) * 0.256663 + 0.584555;
                if (type == 12) {
                    const float a = 0.224282, b = 155.975327, c = 0.01;
                    return x >= 0.0 ? a * log10_f(x * b + 1.0) + c : -a * log10_f(-x * b + 1.0) + c;
                }
                return linearToSrgb(x);
            }

            vec3 triangularDither(vec3 color, vec2 coord) {
                vec3 n1 = fract(sin(vec3(dot(coord, vec2(12.9898, 78.233)), dot(coord, vec2(63.7264, 10.873)), dot(coord, vec2(78.2330, 12.989)))) * 43758.5453);
                vec3 n2 = fract(sin(vec3(dot(coord, vec2(93.9898, 67.345)), dot(coord, vec2(41.1234, 39.876)), dot(coord, vec2(27.8192, 84.192)))) * 24634.6345);
                return clamp(color + (n1 - n2) / 255.0, 0.0, 1.0);
            }

            void main() {
                vec4 src = texture(uTexture, vTexCoord);
                vec3 finalRgb;
                if (uLutSize > 0) {
                    vec3 wideGamutRgb = max(vec3(0.0), uGamutMatrix * srgbToLinear(src.rgb));
                    vec3 logRgb = vec3(applyLogCurve(wideGamutRgb.r, uLogType), applyLogCurve(wideGamutRgb.g, uLogType), applyLogCurve(wideGamutRgb.b, uLogType));
                    float lutSizeFloat = float(uLutSize);
                    vec3 lutCoord = logRgb * ((lutSizeFloat - 1.0) / lutSizeFloat) + vec3(0.5 / lutSizeFloat);
                    finalRgb = triangularDither(texture(uLut, lutCoord).rgb, gl_FragCoord.xy);
                } else if (uLogType > 0) {
                    vec3 wideGamutRgb = max(vec3(0.0), uGamutMatrix * srgbToLinear(src.rgb));
                    vec3 logRgb = vec3(applyLogCurve(wideGamutRgb.r, uLogType), applyLogCurve(wideGamutRgb.g, uLogType), applyLogCurve(wideGamutRgb.b, uLogType));
                    finalRgb = triangularDither(logRgb, gl_FragCoord.xy);
                } else {
                    vec3 linearRgb = srgbToLinear(src.rgb);
                    vec3 mapped = uColorEngineMode == 0 ? applyPbrNeutral(linearRgb)
                        : (uColorEngineMode == 1 ? applyPureLuma(linearRgb)
                        : (uColorEngineMode == 2 ? applySonyUchimura(linearRgb) : applyAcesFit(linearRgb)));
                    finalRgb = triangularDither(linearToSrgb(mapped), gl_FragCoord.xy);
                }
                if (uFocusPeakingEnabled == 1) {
                    float lumN = dot(texture(uTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                    float lumS = dot(texture(uTexture, vTexCoord - vec2(0.0, uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                    float lumE = dot(texture(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                    float lumW = dot(texture(uTexture, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                    if (abs(lumN - lumS) + abs(lumE - lumW) > 0.16) {
                        finalRgb = mix(finalRgb, vec3(0.0, 1.0, 0.3), 0.85);
                    }
                }
                outColor = vec4(finalRgb, 1.0);
            }
        """.trimIndent()
    }
}

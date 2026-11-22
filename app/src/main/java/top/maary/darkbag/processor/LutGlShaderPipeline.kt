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
 * - Decoupled Preview Shader Program (OES preview stream) and RAW Shader Program (2x2 superpixel Bayer GLES3)
 */
internal class LutGlShaderPipeline {

    private var previewProgram = 0
    private var rawProgram = 0
    private var lutTextureId = 0
    private var dummyLutTextureId = 0

    // Preview Program Locations
    private var previewScaleLoc = -1
    private var previewTextureLoc = -1
    private var previewTextureMatrixLoc = -1
    private var previewLutLoc = -1
    private var previewGamutMatrixLoc = -1
    private var previewLogTypeLoc = -1
    private var previewLutSizeLoc = -1
    private var previewColorEngineModeLoc = -1
    private var previewFocusPeakingLoc = -1
    private var previewTexelSizeLoc = -1
    private var previewGainLoc = -1
    private var previewWbPreviewMatrixLoc = -1
    private var previewInputDomainLoc = -1
    private var previewPosHandle = -1
    private var previewTexHandle = -1

    // RAW Bayer Program Locations
    private var rawScaleLoc = -1
    private var rawTextureMatrixLoc = -1
    private var rawBayerTextureLoc = -1
    private var rawLutLoc = -1
    private var rawGamutMatrixLoc = -1
    private var rawLogTypeLoc = -1
    private var rawLutSizeLoc = -1
    private var rawColorEngineModeLoc = -1
    private var rawPreviewGainLoc = -1
    private var rawImageSizeLoc = -1
    private var rawCfaPatternLoc = -1
    private var rawBlackLevelLoc = -1
    private var rawWhiteLevelLoc = -1
    private var rawWbGainsLoc = -1
    private var rawCcmLoc = -1
    private var rawPosHandle = -1
    private var rawTexHandle = -1

    @Volatile private var previewGain = 1.0f

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
        createPreviewProgram()
        createRawProgram()
        createDummyLut()
    }

    fun setPreviewGain(gain: Float) {
        previewGain = gain.coerceIn(1.0f, 4.0f)
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
        focusPeakingEnabled: Boolean,
        currentWbPreviewMatrix: FloatArray? = null,
        previewInputDomain: Int = 0
    ) {
        if (previewProgram == 0) return
        GLES30.glViewport(0, 0, targetWidth, targetHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(previewProgram)

        val (scaleX, scaleY) = computeAspectScale(targetWidth, targetHeight, inputWidth, inputHeight, transformMatrix)
        if (previewScaleLoc >= 0) GLES30.glUniform2f(previewScaleLoc, scaleX, scaleY)

        val activeWbMatrix = currentWbPreviewMatrix ?: IDENTITY_MATRIX_3X3
        if (previewWbPreviewMatrixLoc >= 0) GLES30.glUniformMatrix3fv(previewWbPreviewMatrixLoc, 1, false, activeWbMatrix, 0)

        if (previewInputDomainLoc >= 0) GLES30.glUniform1i(previewInputDomainLoc, previewInputDomain)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        if (previewTextureLoc >= 0) GLES30.glUniform1i(previewTextureLoc, 0)
        if (previewTextureMatrixLoc >= 0) GLES30.glUniformMatrix4fv(previewTextureMatrixLoc, 1, false, transformMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        val activeLutTex = if (currentLutSize > 0 && lutTextureId != 0) lutTextureId else dummyLutTextureId
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, activeLutTex)
        if (previewLutLoc >= 0) GLES30.glUniform1i(previewLutLoc, 1)

        if (previewGamutMatrixLoc >= 0) GLES30.glUniformMatrix3fv(previewGamutMatrixLoc, 1, false, currentGamutMatrix, 0)
        if (previewLogTypeLoc >= 0) GLES30.glUniform1i(previewLogTypeLoc, currentLogType)
        if (previewLutSizeLoc >= 0) GLES30.glUniform1i(previewLutSizeLoc, currentLutSize)
        if (previewColorEngineModeLoc >= 0) GLES30.glUniform1i(previewColorEngineModeLoc, currentColorEngineMode)
        if (previewFocusPeakingLoc >= 0) GLES30.glUniform1i(previewFocusPeakingLoc, if (focusPeakingEnabled) 1 else 0)
        if (previewGainLoc >= 0) GLES30.glUniform1f(previewGainLoc, previewGain)
        if (focusPeakingEnabled && previewTexelSizeLoc >= 0) {
            val tw = if (inputWidth > 0) 1.0f / inputWidth.toFloat() else 1.0f / 1080f
            val th = if (inputHeight > 0) 1.0f / inputHeight.toFloat() else 1.0f / 1920f
            GLES30.glUniform2f(previewTexelSizeLoc, tw, th)
        }

        if (previewPosHandle >= 0) {
            vertexBuffer.position(0)
            GLES30.glVertexAttribPointer(previewPosHandle, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES30.glEnableVertexAttribArray(previewPosHandle)
        }
        if (previewTexHandle >= 0) {
            vertexBuffer.position(2)
            GLES30.glVertexAttribPointer(previewTexHandle, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES30.glEnableVertexAttribArray(previewTexHandle)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        if (previewPosHandle >= 0) GLES30.glDisableVertexAttribArray(previewPosHandle)
        if (previewTexHandle >= 0) GLES30.glDisableVertexAttribArray(previewTexHandle)
    }

    fun drawRawFrame(
        targetWidth: Int,
        targetHeight: Int,
        rawWidth: Int,
        rawHeight: Int,
        rawBayerTextureId: Int,
        cfaPattern: Int,
        blackLevel: FloatArray,
        whiteLevel: Float,
        wbGains: FloatArray,
        dngCcm: FloatArray,
        currentLutSize: Int,
        currentLogType: Int,
        currentGamutMatrix: FloatArray,
        currentColorEngineMode: Int
    ) {
        if (rawProgram == 0) return
        GLES30.glViewport(0, 0, targetWidth, targetHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(rawProgram)

        val (scaleX, scaleY) = computeAspectScale(targetWidth, targetHeight, rawWidth, rawHeight, IDENTITY_MATRIX_4X4)
        if (rawScaleLoc >= 0) GLES30.glUniform2f(rawScaleLoc, scaleX, scaleY)

        if (rawTextureMatrixLoc >= 0) GLES30.glUniformMatrix4fv(rawTextureMatrixLoc, 1, false, IDENTITY_MATRIX_4X4, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawBayerTextureId)
        if (rawBayerTextureLoc >= 0) GLES30.glUniform1i(rawBayerTextureLoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        val activeLutTex = if (currentLutSize > 0 && lutTextureId != 0) lutTextureId else dummyLutTextureId
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, activeLutTex)
        if (rawLutLoc >= 0) GLES30.glUniform1i(rawLutLoc, 1)

        if (rawImageSizeLoc >= 0) GLES30.glUniform2i(rawImageSizeLoc, rawWidth, rawHeight)
        if (rawCfaPatternLoc >= 0) GLES30.glUniform1i(rawCfaPatternLoc, cfaPattern)
        if (rawBlackLevelLoc >= 0) {
            val bl0 = if (blackLevel.isNotEmpty()) blackLevel[0] else 64f
            val bl1 = if (blackLevel.size > 1) blackLevel[1] else bl0
            val bl2 = if (blackLevel.size > 2) blackLevel[2] else bl0
            val bl3 = if (blackLevel.size > 3) blackLevel[3] else bl1
            GLES30.glUniform4f(rawBlackLevelLoc, bl0, bl1, bl2, bl3)
        }
        if (rawWhiteLevelLoc >= 0) GLES30.glUniform1f(rawWhiteLevelLoc, whiteLevel)
        if (rawWbGainsLoc >= 0) {
            val gR = if (wbGains.isNotEmpty()) wbGains[0] else 1f
            val gG = if (wbGains.size > 1) wbGains[1] else 1f
            val gB = if (wbGains.size > 2) wbGains[2] else 1f
            GLES30.glUniform3f(rawWbGainsLoc, gR, gG, gB)
        }
        if (rawCcmLoc >= 0) GLES30.glUniformMatrix3fv(rawCcmLoc, 1, false, dngCcm, 0)

        if (rawGamutMatrixLoc >= 0) GLES30.glUniformMatrix3fv(rawGamutMatrixLoc, 1, false, currentGamutMatrix, 0)
        if (rawLogTypeLoc >= 0) GLES30.glUniform1i(rawLogTypeLoc, currentLogType)
        if (rawLutSizeLoc >= 0) GLES30.glUniform1i(rawLutSizeLoc, currentLutSize)
        if (rawColorEngineModeLoc >= 0) GLES30.glUniform1i(rawColorEngineModeLoc, currentColorEngineMode)
        if (rawPreviewGainLoc >= 0) GLES30.glUniform1f(rawPreviewGainLoc, previewGain)

        if (rawPosHandle >= 0) {
            vertexBuffer.position(0)
            GLES30.glVertexAttribPointer(rawPosHandle, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES30.glEnableVertexAttribArray(rawPosHandle)
        }
        if (rawTexHandle >= 0) {
            vertexBuffer.position(2)
            GLES30.glVertexAttribPointer(rawTexHandle, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
            GLES30.glEnableVertexAttribArray(rawTexHandle)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        if (rawPosHandle >= 0) GLES30.glDisableVertexAttribArray(rawPosHandle)
        if (rawTexHandle >= 0) GLES30.glDisableVertexAttribArray(rawTexHandle)
    }

    fun releaseGlResources() {
        if (previewProgram != 0) {
            GLES30.glDeleteProgram(previewProgram)
            previewProgram = 0
        }
        if (rawProgram != 0) {
            GLES30.glDeleteProgram(rawProgram)
            rawProgram = 0
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

    private fun createPreviewProgram() {
        val vShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        val fShader = loadShader(GLES30.GL_FRAGMENT_SHADER, PREVIEW_FRAGMENT_SHADER_SRC)

        previewProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(previewProgram, vShader)
        GLES30.glAttachShader(previewProgram, fShader)
        GLES30.glLinkProgram(previewProgram)

        previewScaleLoc = GLES30.glGetUniformLocation(previewProgram, "uScale")
        previewTextureLoc = GLES30.glGetUniformLocation(previewProgram, "uTexture")
        previewTextureMatrixLoc = GLES30.glGetUniformLocation(previewProgram, "uTextureMatrix")
        previewLutLoc = GLES30.glGetUniformLocation(previewProgram, "uLut")
        previewGamutMatrixLoc = GLES30.glGetUniformLocation(previewProgram, "uGamutMatrix")
        previewLogTypeLoc = GLES30.glGetUniformLocation(previewProgram, "uLogType")
        previewLutSizeLoc = GLES30.glGetUniformLocation(previewProgram, "uLutSize")
        previewColorEngineModeLoc = GLES30.glGetUniformLocation(previewProgram, "uColorEngineMode")
        previewFocusPeakingLoc = GLES30.glGetUniformLocation(previewProgram, "uFocusPeakingEnabled")
        previewTexelSizeLoc = GLES30.glGetUniformLocation(previewProgram, "uTexelSize")
        previewGainLoc = GLES30.glGetUniformLocation(previewProgram, "uPreviewGain")
        previewWbPreviewMatrixLoc = GLES30.glGetUniformLocation(previewProgram, "uWbPreviewMatrix")
        previewInputDomainLoc = GLES30.glGetUniformLocation(previewProgram, "uPreviewInputDomain")

        previewPosHandle = GLES30.glGetAttribLocation(previewProgram, "aPosition")
        previewTexHandle = GLES30.glGetAttribLocation(previewProgram, "aTexCoord")

        GLES30.glUseProgram(previewProgram)
        if (previewTextureLoc >= 0) GLES30.glUniform1i(previewTextureLoc, 0)
        if (previewLutLoc >= 0) GLES30.glUniform1i(previewLutLoc, 1)
        GLES30.glUseProgram(0)
    }

    private fun createRawProgram() {
        val vShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        val fShader = loadShader(GLES30.GL_FRAGMENT_SHADER, RAW_FRAGMENT_SHADER_SRC)

        rawProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(rawProgram, vShader)
        GLES30.glAttachShader(rawProgram, fShader)
        GLES30.glLinkProgram(rawProgram)

        rawScaleLoc = GLES30.glGetUniformLocation(rawProgram, "uScale")
        rawTextureMatrixLoc = GLES30.glGetUniformLocation(rawProgram, "uTextureMatrix")
        rawBayerTextureLoc = GLES30.glGetUniformLocation(rawProgram, "uRawBayerTexture")
        rawLutLoc = GLES30.glGetUniformLocation(rawProgram, "uLut")
        rawGamutMatrixLoc = GLES30.glGetUniformLocation(rawProgram, "uGamutMatrix")
        rawLogTypeLoc = GLES30.glGetUniformLocation(rawProgram, "uLogType")
        rawLutSizeLoc = GLES30.glGetUniformLocation(rawProgram, "uLutSize")
        rawColorEngineModeLoc = GLES30.glGetUniformLocation(rawProgram, "uColorEngineMode")
        rawPreviewGainLoc = GLES30.glGetUniformLocation(rawProgram, "uPreviewGain")
        rawImageSizeLoc = GLES30.glGetUniformLocation(rawProgram, "uRawImageSize")
        rawCfaPatternLoc = GLES30.glGetUniformLocation(rawProgram, "uRawCfaPattern")
        rawBlackLevelLoc = GLES30.glGetUniformLocation(rawProgram, "uRawBlackLevel")
        rawWhiteLevelLoc = GLES30.glGetUniformLocation(rawProgram, "uRawWhiteLevel")
        rawWbGainsLoc = GLES30.glGetUniformLocation(rawProgram, "uRawWbGains")
        rawCcmLoc = GLES30.glGetUniformLocation(rawProgram, "uRawCcm")

        rawPosHandle = GLES30.glGetAttribLocation(rawProgram, "aPosition")
        rawTexHandle = GLES30.glGetAttribLocation(rawProgram, "aTexCoord")

        GLES30.glUseProgram(rawProgram)
        if (rawBayerTextureLoc >= 0) GLES30.glUniform1i(rawBayerTextureLoc, 0)
        if (rawLutLoc >= 0) GLES30.glUniform1i(rawLutLoc, 1)
        GLES30.glUseProgram(0)
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
        val IDENTITY_MATRIX_3X3 = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        val IDENTITY_MATRIX_4X4 = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        /**
         * Computes OpenGL sRGB CAT16 incremental preview matrix (Section 4.3.2):
         * M_preview_wb = M_XYZ_to_sRGB_D65 * M_CAT16_adapt(W_target -> W_live)_luma_norm * M_sRGB_D65_to_XYZ
         * Returns column-major 3x3 matrix for glUniformMatrix3fv.
         */
        fun computeWbPreviewMatrix(
            liveTempK: Float,
            liveTint: Float,
            targetTempK: Float,
            targetTint: Float
        ): FloatArray {
            if (kotlin.math.abs(liveTempK - targetTempK) < 1.0f && kotlin.math.abs(liveTint - targetTint) < 0.1f) {
                return IDENTITY_MATRIX_3X3.copyOf()
            }

            val (xTarget, yTarget) = SensorCalibrationHelper.tempTintToXy(targetTempK, targetTint)
            val wTarget = SensorCalibrationHelper.xyToXyz(xTarget, yTarget)

            val (xLive, yLive) = SensorCalibrationHelper.tempTintToXy(liveTempK, liveTint)
            val wLive = SensorCalibrationHelper.xyToXyz(xLive, yLive)

            val mCat = SensorCalibrationHelper.cat16AdaptMatrix(wLive, wTarget)
            val yOut = mCat[3] * wLive[0] + mCat[4] * wLive[1] + mCat[5] * wLive[2]
            val lumaScale = 1.0f / maxOf(0.1f, yOut)
            val mCatNorm = FloatArray(9) { i -> mCat[i] * lumaScale }

            val mInter = SensorCalibrationHelper.multiply3x3(M_XYZ_to_Rec709_D65, mCatNorm)
            val mRowMajor = SensorCalibrationHelper.multiply3x3(mInter, M_sRGB_D65_to_XYZ)

            val mColMajor = FloatArray(9)
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    mColMajor[c * 3 + r] = mRowMajor[r * 3 + c]
                }
            }
            return mColMajor
        }

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

        private val COMMON_SHADER_MATH = """
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

            vec3 applyInverseIspCurve(vec3 c) {
                const float m = 0.22;
                const float invToePower = 0.7518797; // 1.0 / 1.33
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                if (luma <= 1e-5) return vec3(0.0);
                float targetLuma = luma;
                if (luma < m) {
                    float gate = smoothstep(0.0002, 0.0020, luma);
                    float lifted = m * pow(luma / m, invToePower);
                    targetLuma = mix(luma, lifted, gate);
                } else if (luma > 0.50) {
                    float excess = min(luma, 1.0) - 0.50;
                    targetLuma = luma + 0.30 * (excess * excess) / (1.08 - min(luma, 1.0));
                }
                return c * (targetLuma / luma);
            }

            vec3 applyPbrNeutral(vec3 color) {
                const float kBlackOffset = 0.003;
                const float kBlackThreshold = 4.0 * kBlackOffset;
                const float startCompression = 0.8 - kBlackOffset;
                const float desaturation = 0.15;
                float x = min(color.r, min(color.g, color.b));
                color -= (x < kBlackThreshold ? 0.5 * x - 20.8333 * x * x : kBlackOffset);
                float peak = max(color.r, max(color.g, color.b));
                if (peak < startCompression) return max(vec3(0.0), color);
                const float d = 1.0 - startCompression;
                float newPeak = 1.0 - d * d / (peak + d - startCompression);
                color *= newPeak / peak;
                float g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0);
                return clamp(mix(color, vec3(newPeak), g), 0.0, 1.0);
            }

            vec3 applyNaturalFilmic(vec3 c) {
                mat3 inset = mat3(
                    vec3(0.8566271533, 0.1373189729, 0.1118982130),
                    vec3(0.0951212405, 0.7612419906, 0.0767994186),
                    vec3(0.0482516061, 0.1014390365, 0.8113023684));
                mat3 outset = mat3(
                    vec3(1.1974410769, -0.1964746263, -0.1465574171),
                    vec3(-0.1442615127, 1.3540951315, -0.1082840588),
                    vec3(-0.0531795642, -0.1576205051, 1.2548414759));
                vec3 v = inset * max(c, vec3(0.0));
                vec3 n = v * (2.3134378 * v + 0.8030934);
                vec3 mapped = n / (n + vec3(1.0));
                vec3 outRgb = outset * mapped;
                float peak = max(outRgb.r, max(outRgb.g, outRgb.b));
                if (peak > 1.0) {
                    outRgb *= 1.0 / peak;
                }
                return clamp(outRgb, 0.0, 1.0);
            }

            float uchimuraScalar(float x, float gain) {
                if (x <= 0.0) return 0.0;
                const float P = 1.0, a = 1.25, m = 0.22, l = 0.40, c = 1.33, b = 0.0;
                const float l0 = ((P - m) * l) / a, S0 = m + l0, S1 = m + a * l0;
                float CP = -((a * P) / (P - S1)) / (P * max(1.0, gain * 0.75));
                if (x <= m) return m * pow(x / m, c) + b;
                if (x < m + l0) return m + a * (x - m);
                return P - (P - S1) * exp(CP * (x - S0));
            }

            vec3 applySonyUchimura(vec3 c, float gain) {
                float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
                float peak = max(c.r, max(c.g, c.b));
                if (peak <= 0.00001) return vec3(0.0);
                float mappedLuma = uchimuraScalar(luma, gain);
                float mappedPeak = uchimuraScalar(peak, gain);
                float lumaScale = (luma > 1e-5) ? (mappedLuma / luma) : 1.0;
                float peakScale = mappedPeak / peak;
                vec3 outCol = c * min(lumaScale, peakScale);
                float compression = (peak > 0.22) ? max(0.0, peak - mappedPeak) : 0.0;
                float desat = 1.0 - 1.0 / (0.20 * compression + 1.0);
                float outLuma = 0.2126 * outCol.r + 0.7152 * outCol.g + 0.0722 * outCol.b;
                float targetWhite = max(outLuma, mappedPeak);
                return clamp(mix(outCol, vec3(targetWhite), desat), 0.0, 1.0);
            }

            vec3 applyAcesFit(vec3 v) {
                return clamp((v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14), 0.0, 1.0);
            }

            const float kColorDensityReduction = 0.0;
            vec3 applySubtractiveDensity(vec3 c, float reduction) {
                float peak = max(c.r, max(c.g, c.b));
                float trough = min(c.r, min(c.g, c.b));
                if (peak <= 1e-4) return c;
                float chroma = (peak - trough) / peak;
                return c * (1.0 - reduction * chroma * chroma);
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
        """.trimIndent()

        internal val PREVIEW_FRAGMENT_SHADER_SRC = """
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
            uniform float uPreviewGain;
            uniform mat3 uWbPreviewMatrix;
            uniform int uPreviewInputDomain;

            in vec2 vTexCoord;
            out vec4 outColor;

            $COMMON_SHADER_MATH

            void main() {
                vec4 src = texture(uTexture, vTexCoord);
                vec3 srcLinear;
                if (uPreviewInputDomain == 2) {
                    srcLinear = pow(max(src.rgb, vec3(0.0)), vec3(2.2));
                } else if (uPreviewInputDomain == 1) {
                    srcLinear = applyInverseIspCurve(srgbToLinear(src.rgb));
                } else {
                    srcLinear = srgbToLinear(src.rgb);
                }
                srcLinear *= uPreviewGain;
                vec3 linearRgb = max(vec3(0.0), uWbPreviewMatrix * srcLinear);

                vec3 finalRgb;
                if (uLutSize > 0) {
                    vec3 wideGamutRgb = max(vec3(0.0), uGamutMatrix * linearRgb);
                    vec3 logRgb = vec3(applyLogCurve(wideGamutRgb.r, uLogType), applyLogCurve(wideGamutRgb.g, uLogType), applyLogCurve(wideGamutRgb.b, uLogType));
                    float lutSizeFloat = float(uLutSize);
                    vec3 lutCoord = logRgb * ((lutSizeFloat - 1.0) / lutSizeFloat) + vec3(0.5 / lutSizeFloat);
                    finalRgb = triangularDither(texture(uLut, lutCoord).rgb, gl_FragCoord.xy);
                } else if (uLogType > 0) {
                    vec3 wideGamutRgb = max(vec3(0.0), uGamutMatrix * linearRgb);
                    vec3 logRgb = vec3(applyLogCurve(wideGamutRgb.r, uLogType), applyLogCurve(wideGamutRgb.g, uLogType), applyLogCurve(wideGamutRgb.b, uLogType));
                    finalRgb = triangularDither(logRgb, gl_FragCoord.xy);
                } else {
                    vec3 mapped = uColorEngineMode == 0 ? applyPbrNeutral(linearRgb)
                        : (uColorEngineMode == 1 ? applyNaturalFilmic(linearRgb)
                        : (uColorEngineMode == 2 ? applySonyUchimura(linearRgb, uPreviewGain) : applyAcesFit(linearRgb)));
                    if (uColorEngineMode == 2 && kColorDensityReduction > 0.0) {
                        mapped = applySubtractiveDensity(mapped, kColorDensityReduction);
                    }
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

        internal val FRAGMENT_SHADER_SRC = PREVIEW_FRAGMENT_SHADER_SRC

        internal val RAW_FRAGMENT_SHADER_SRC = """
            #version 300 es
            precision highp float;
            precision highp usampler2D;
            precision highp sampler3D;

            uniform highp usampler2D uRawBayerTexture;
            uniform sampler3D uLut;
            uniform ivec2 uRawImageSize;
            uniform int uRawCfaPattern;
            uniform vec4 uRawBlackLevel;
            uniform float uRawWhiteLevel;
            uniform vec3 uRawWbGains;
            uniform mat3 uRawCcm;
            uniform mat3 uGamutMatrix;
            uniform int uLogType;
            uniform int uLutSize;
            uniform int uColorEngineMode;
            uniform float uPreviewGain;

            in vec2 vTexCoord;
            out vec4 outColor;

            $COMMON_SHADER_MATH

            void main() {
                ivec2 coord = ivec2(vTexCoord * vec2(uRawImageSize));
                coord = clamp(coord, ivec2(0), uRawImageSize - 2);
                coord = coord & ~1;

                uint p00 = texelFetch(uRawBayerTexture, coord, 0).r;
                uint p01 = texelFetch(uRawBayerTexture, coord + ivec2(1, 0), 0).r;
                uint p10 = texelFetch(uRawBayerTexture, coord + ivec2(0, 1), 0).r;
                uint p11 = texelFetch(uRawBayerTexture, coord + ivec2(1, 1), 0).r;

                float r = 0.0, g = 0.0, b = 0.0;
                float blR = uRawBlackLevel.r, blGr = uRawBlackLevel.g, blGb = uRawBlackLevel.b, blB = uRawBlackLevel.a;

                if (uRawCfaPattern == 0) { // RGGB
                    r = clamp((float(p00) - blR) / max(1.0, uRawWhiteLevel - blR), 0.0, 1.0);
                    g = clamp(((float(p01) - blGr) / max(1.0, uRawWhiteLevel - blGr) + (float(p10) - blGb) / max(1.0, uRawWhiteLevel - blGb)) * 0.5, 0.0, 1.0);
                    b = clamp((float(p11) - blB) / max(1.0, uRawWhiteLevel - blB), 0.0, 1.0);
                } else if (uRawCfaPattern == 1) { // GRBG
                    g = clamp(((float(p00) - blGr) / max(1.0, uRawWhiteLevel - blGr) + (float(p11) - blGb) / max(1.0, uRawWhiteLevel - blGb)) * 0.5, 0.0, 1.0);
                    r = clamp((float(p01) - blR) / max(1.0, uRawWhiteLevel - blR), 0.0, 1.0);
                    b = clamp((float(p10) - blB) / max(1.0, uRawWhiteLevel - blB), 0.0, 1.0);
                } else if (uRawCfaPattern == 2) { // GBRG
                    g = clamp(((float(p00) - blGb) / max(1.0, uRawWhiteLevel - blGb) + (float(p11) - blGr) / max(1.0, uRawWhiteLevel - blGr)) * 0.5, 0.0, 1.0);
                    b = clamp((float(p01) - blB) / max(1.0, uRawWhiteLevel - blB), 0.0, 1.0);
                    r = clamp((float(p10) - blR) / max(1.0, uRawWhiteLevel - blR), 0.0, 1.0);
                } else { // 3: BGGR
                    b = clamp((float(p00) - blB) / max(1.0, uRawWhiteLevel - blB), 0.0, 1.0);
                    g = clamp(((float(p01) - blGr) / max(1.0, uRawWhiteLevel - blGr) + (float(p10) - blGb) / max(1.0, uRawWhiteLevel - blGb)) * 0.5, 0.0, 1.0);
                    r = clamp((float(p11) - blR) / max(1.0, uRawWhiteLevel - blR), 0.0, 1.0);
                }

                vec3 wbColor = vec3(r, g, b) * uRawWbGains * uPreviewGain;
                vec3 sensorLinear = max(vec3(0.0), uRawCcm * wbColor);

                float maxRaw = max(r, max(g, b));
                if (maxRaw > 0.95) {
                    float t = clamp((maxRaw - 0.95) / (1.0 - 0.95), 0.0, 1.0);
                    float blendFactor = t * t * (3.0 - 2.0 * t);
                    float peakLuma = max(sensorLinear.r, max(sensorLinear.g, sensorLinear.b));
                    sensorLinear = mix(sensorLinear, vec3(peakLuma), blendFactor);
                }
                vec3 linearRgb = sensorLinear;

                vec3 finalRgb;
                if (uLutSize > 0) {
                    vec3 wideGamutRgb = max(vec3(0.0), uGamutMatrix * linearRgb);
                    vec3 logRgb = vec3(applyLogCurve(wideGamutRgb.r, uLogType), applyLogCurve(wideGamutRgb.g, uLogType), applyLogCurve(wideGamutRgb.b, uLogType));
                    float lutSizeFloat = float(uLutSize);
                    vec3 lutCoord = logRgb * ((lutSizeFloat - 1.0) / lutSizeFloat) + vec3(0.5 / lutSizeFloat);
                    finalRgb = triangularDither(texture(uLut, lutCoord).rgb, gl_FragCoord.xy);
                } else if (uLogType > 0) {
                    vec3 wideGamutRgb = max(vec3(0.0), uGamutMatrix * linearRgb);
                    vec3 logRgb = vec3(applyLogCurve(wideGamutRgb.r, uLogType), applyLogCurve(wideGamutRgb.g, uLogType), applyLogCurve(wideGamutRgb.b, uLogType));
                    finalRgb = triangularDither(logRgb, gl_FragCoord.xy);
                } else {
                    vec3 mapped = uColorEngineMode == 0 ? applyPbrNeutral(linearRgb)
                        : (uColorEngineMode == 1 ? applyNaturalFilmic(linearRgb)
                        : (uColorEngineMode == 2 ? applySonyUchimura(linearRgb, uPreviewGain) : applyAcesFit(linearRgb)));
                    if (uColorEngineMode == 2 && kColorDensityReduction > 0.0) {
                        mapped = applySubtractiveDensity(mapped, kColorDensityReduction);
                    }
                    finalRgb = triangularDither(linearToSrgb(mapped), gl_FragCoord.xy);
                }
                outColor = vec4(finalRgb, 1.0);
            }
        """.trimIndent()
    }
}

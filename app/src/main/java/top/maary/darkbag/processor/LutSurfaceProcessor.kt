package top.maary.darkbag.processor

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * EGL lifecycle, background GL thread, and dual-surface (Viewfinder + MediaCodec Encoder)
 * frame scheduler for live 3D LUT / Log / Focus Peaking preview.
 *
 * Delegates shader compilation, uniform updates, 3D LUT uploads, and quad drawing to
 * [LutGlShaderPipeline].
 */
class LutSurfaceProcessor {

    private val thread = HandlerThread("GLThread")
    private val handler: Handler
    private val shaderPipeline = LutGlShaderPipeline()

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputTextureId = 0
    private var outputSurface: Surface? = null
    private var width = 0
    private var height = 0

    private var encoderEglSurface = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0
    @Volatile private var isEncoderActive = false

    private var currentLutSize = 0
    private var currentLogType = 0
    private var currentGamutMatrix = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    private var inputWidth = 0
    private var inputHeight = 0

    private val transformMatrix = FloatArray(16)
    private var currentColorEngineMode = 2
    private var currentWbPreviewMatrix = LutGlShaderPipeline.IDENTITY_MATRIX_3X3.copyOf()

    @Volatile private var isFocusPeakingEnabled = false
    @Volatile private var previewGain = 1.0f
    @Volatile private var previewInputDomain = 0

    @Volatile private var rawMotionEnabled = false
    @Volatile private var lastRawFrameNs = 0L

    private var rawBayerTextureId = 0
    private var currentRawTexWidth = 0
    private var currentRawTexHeight = 0

    init {
        thread.start()
        handler = Handler(thread.looper)
        handler.post { initGl() }
    }

    fun setFocusPeakingEnabled(enabled: Boolean) {
        handler.post {
            isFocusPeakingEnabled = enabled
        }
    }

    /** Viewfinder-only gain used to compensate HDR+ ZSL underexposure. */
    fun setPreviewGain(gain: Float) {
        previewGain = gain.coerceIn(1.0f, 4.0f)
    }

    fun setPreviewInputDomain(domain: Int) {
        handler.post {
            previewInputDomain = domain
        }
    }

    fun setRawMotionEnabled(enabled: Boolean) {
        rawMotionEnabled = enabled
    }

    fun updateColorEngineMode(engineMode: Int) {
        handler.post {
            currentColorEngineMode = engineMode
        }
    }

    fun getInputSurface(w: Int, h: Int, onSurfaceReady: (Surface) -> Unit) {
        handler.post {
            inputWidth = w
            inputHeight = h

            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            val textureId = textures[0]

            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture.setDefaultBufferSize(w, h)
            surfaceTexture.setOnFrameAvailableListener({
                handler.post { drawFrame() }
            }, handler)

            val surface = Surface(surfaceTexture)

            this.inputTextureId = textureId
            this.inputSurfaceTexture = surfaceTexture

            onSurfaceReady(surface)
        }
    }

    fun releaseInputSurface() {
        handler.post {
            inputSurfaceTexture?.release()
            inputSurfaceTexture = null
            if (inputTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
                inputTextureId = 0
            }
        }
    }

    fun setOutputSurface(surface: Surface?, w: Int, h: Int) {
        handler.post {
            if (surface == null) {
                outputSurface = null
                releaseEglSurface()
            } else {
                setOutputSurfaceInternal(surface, w, h)
            }
        }
    }

    private fun setOutputSurfaceInternal(surface: Surface, w: Int, h: Int) {
        outputSurface = surface
        width = w
        height = h
        createEglSurface(surface)
    }

    fun setEncoderSurface(surface: Surface?, w: Int, h: Int) {
        handler.post {
            if (surface == null) {
                isEncoderActive = false
                releaseEncoderEglSurface()
            } else {
                encoderWidth = w
                encoderHeight = h
                createEncoderEglSurface(surface)
                isEncoderActive = true
            }
        }
    }

    private fun createEncoderEglSurface(surface: Surface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        encoderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        if (encoderEglSurface == null || encoderEglSurface == EGL14.EGL_NO_SURFACE) {
            val error = EGL14.eglGetError()
            Log.e("LutProcessor", "createEncoderEglSurface failed: $error")
        }
    }

    private fun releaseEncoderEglSurface() {
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    fun updateLut(lutData: FloatArray?, size: Int, logType: Int) {
        handler.post {
            currentLogType = logType
            currentGamutMatrix = LutGlShaderPipeline.getGamutMatrix(logType)
            if (lutData != null && size > 0) {
                currentLutSize = size
                shaderPipeline.uploadLutTexture(lutData, size)
            } else {
                currentLutSize = 0
            }
        }
    }

    fun setWbPreviewMatrix(matrix: FloatArray) {
        handler.post {
            currentWbPreviewMatrix = matrix.copyOf()
        }
    }

    fun release() {
        handler.post {
            releaseGl()
            thread.quitSafely()
        }
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        shaderPipeline.initProgramAndDummyLut()
    }

    private fun createEglSurface(surface: Surface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            val error = EGL14.eglGetError()
            Log.e("LutProcessor", "eglCreateWindowSurface failed: $error")
            return
        }
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun releaseEglSurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun uploadRawBayerTexture(buffer: ByteBuffer, w: Int, h: Int) {
        if (rawBayerTextureId == 0) {
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            rawBayerTextureId = textures[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawBayerTextureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawBayerTextureId)
        }

        buffer.position(0)
        if (currentRawTexWidth != w || currentRawTexHeight != h) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16UI,
                w, h, 0,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, buffer
            )
            currentRawTexWidth = w
            currentRawTexHeight = h
        } else {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0,
                w, h,
                GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, buffer
            )
        }
    }

    fun submitRawMotionFrame(
        bayerBuffer: ByteBuffer,
        width: Int,
        height: Int,
        cfaPattern: Int,
        blackLevel: FloatArray,
        whiteLevel: Float,
        wbGains: FloatArray,
        dngCcm: FloatArray,
        timestampNs: Long
    ) {
        if (!rawMotionEnabled) return
        handler.post {
            if (!rawMotionEnabled) return@post
            if (isEncoderActive && encoderEglSurface != EGL14.EGL_NO_SURFACE && encoderWidth > 0 && encoderHeight > 0) {
                uploadRawBayerTexture(bayerBuffer, width, height)
                EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
                shaderPipeline.setPreviewGain(previewGain)
                shaderPipeline.drawRawFrame(
                    targetWidth = encoderWidth,
                    targetHeight = encoderHeight,
                    rawWidth = width,
                    rawHeight = height,
                    rawBayerTextureId = rawBayerTextureId,
                    cfaPattern = cfaPattern,
                    blackLevel = blackLevel,
                    whiteLevel = whiteLevel,
                    wbGains = wbGains,
                    dngCcm = dngCcm,
                    currentLutSize = currentLutSize,
                    currentLogType = currentLogType,
                    currentGamutMatrix = currentGamutMatrix,
                    currentColorEngineMode = currentColorEngineMode
                )
                EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, timestampNs)
                EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
                lastRawFrameNs = System.nanoTime()

                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                }
            }
        }
    }

    private fun drawFrame() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || inputSurfaceTexture == null) return

        inputSurfaceTexture?.updateTexImage()
        inputSurfaceTexture?.getTransformMatrix(transformMatrix)

        shaderPipeline.setPreviewGain(previewGain)
        shaderPipeline.drawFrame(
            targetWidth = width,
            targetHeight = height,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            inputTextureId = inputTextureId,
            transformMatrix = transformMatrix,
            currentLutSize = currentLutSize,
            currentLogType = currentLogType,
            currentGamutMatrix = currentGamutMatrix,
            currentColorEngineMode = currentColorEngineMode,
            focusPeakingEnabled = isFocusPeakingEnabled,
            currentWbPreviewMatrix = currentWbPreviewMatrix,
            previewInputDomain = previewInputDomain
        )
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        // Branch 2: Render to MediaCodec Encoder Surface for Motion Photo (never bake focus peaking lines)
        val isRawActive = rawMotionEnabled && ((System.nanoTime() - lastRawFrameNs) < 1_500_000_000L)
        if (!isRawActive && isEncoderActive && encoderEglSurface != EGL14.EGL_NO_SURFACE && encoderWidth > 0 && encoderHeight > 0) {
            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
            // Match the viewfinder gain: HDR+ ZSL physically underexposes the sensor, so a
            // 1.0 encoder gain would record a dark Motion Photo. Focus peaking stays off.
            shaderPipeline.setPreviewGain(previewGain)
            shaderPipeline.drawFrame(
                targetWidth = encoderWidth,
                targetHeight = encoderHeight,
                inputWidth = inputWidth,
                inputHeight = inputHeight,
                inputTextureId = inputTextureId,
                transformMatrix = transformMatrix,
                currentLutSize = currentLutSize,
                currentLogType = currentLogType,
                currentGamutMatrix = currentGamutMatrix,
                currentColorEngineMode = currentColorEngineMode,
                focusPeakingEnabled = false,
                currentWbPreviewMatrix = currentWbPreviewMatrix,
                previewInputDomain = previewInputDomain
            )
            val frameTimestampNs = System.nanoTime()
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, frameTimestampNs)
            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)

            // Rebind Viewfinder display surface
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            shaderPipeline.setPreviewGain(previewGain)
        }
    }

    private fun releaseGl() {
        try {
            inputSurfaceTexture?.release()
        } catch (_: Exception) {
        }
        inputSurfaceTexture = null
        shaderPipeline.releaseGlResources()

        if (inputTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
            inputTextureId = 0
        }

        if (rawBayerTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(rawBayerTextureId), 0)
            rawBayerTextureId = 0
        }
        currentRawTexWidth = 0
        currentRawTexHeight = 0

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        encoderEglSurface = EGL14.EGL_NO_SURFACE
        isEncoderActive = false
        rawMotionEnabled = false
    }

    companion object {
        fun getGamutMatrix(targetLog: Int): FloatArray = LutGlShaderPipeline.getGamutMatrix(targetLog)
    }
}

package top.maary.darkbag.processor

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

class LutSurfaceProcessor : SurfaceProcessor {

    private val thread = HandlerThread("GLThread")
    private val handler: Handler
    // Executor that runs tasks on the GL thread via the handler.
    // This is safer as it won't throw RejectedExecutionException if shut down.
    private val handlerExecutor = java.util.concurrent.Executor { runnable -> handler.post(runnable) }

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputTextureId = 0
    private var lutTextureId = 0
    private var dummyLutTextureId = 0
    private var program = 0
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

    @Volatile private var isFocusPeakingEnabled = false

    fun setFocusPeakingEnabled(enabled: Boolean) {
        handler.post {
            isFocusPeakingEnabled = enabled
        }
    }

    companion object {
        // --- Standard CIE XYZ and Gamut Matrices (Row-Major definitions matching ColorPipe.cpp) ---
        private val M_sRGB_D65_to_XYZ = floatArrayOf(
            0.41239080f, 0.35758434f, 0.18048079f,
            0.21263901f, 0.71516868f, 0.07219232f,
            0.01933082f, 0.11919478f, 0.95053215f
        )

        private val M_XYZ_to_AlexaWideGamut_D65 = floatArrayOf(
            1.99234198f, -0.57196805f, -0.29536100f,
            -0.79989925f, 1.74791391f, 0.01134474f,
            0.00760860f, -0.02558954f, 0.93508164f
        )

        private val M_XYZ_to_Rec2020_D65 = floatArrayOf(
            1.71665119f, -0.35567078f, -0.25336628f,
            -0.66668435f, 1.61648124f, 0.01576855f,
            0.01763986f, -0.04277061f, 0.94210312f
        )

        private val M_XYZ_to_SGamut3Cine_D65 = floatArrayOf(
            1.84677897f, -0.52598612f, -0.21054521f,
            -0.44415326f, 1.25944290f, 0.14939997f,
            0.04085542f, 0.01564089f, 0.86820725f
        )

        private val M_XYZ_to_VGamut_D65 = floatArrayOf(
            1.59387222f, -0.31417914f, -0.18431177f,
            -0.51815173f, 1.35539124f, 0.12587867f,
            0.01117945f, 0.00319413f, 0.90553536f
        )

        private val M_XYZ_to_Rec709_D65 = floatArrayOf(
            3.24096994f, -1.53738318f, -0.49861076f,
            -0.96924364f, 1.87596750f, 0.04155506f,
            0.05563008f, -0.20397696f, 1.05697151f
        )

        private val M_Identity_ColMajor = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        /**
         * Multiplies two 3x3 matrices defined in Row-Major order (C = A * B)
         * and returns result in Column-Major order for OpenGL ES glUniformMatrix3fv.
         */
        private fun multiply3x3ToColMajor(aRowMajor: FloatArray, bRowMajor: FloatArray): FloatArray {
            val colMajor = FloatArray(9)
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    val v = aRowMajor[r * 3 + 0] * bRowMajor[0 * 3 + c] +
                            aRowMajor[r * 3 + 1] * bRowMajor[1 * 3 + c] +
                            aRowMajor[r * 3 + 2] * bRowMajor[2 * 3 + c]
                    colMajor[c * 3 + r] = v
                }
            }
            return colMajor
        }

        fun getGamutMatrix(targetLog: Int): FloatArray {
            val targetM = when (targetLog) {
                1 -> M_XYZ_to_AlexaWideGamut_D65 // Arri LogC3
                2, 3, 4, 10, 11 -> M_XYZ_to_Rec2020_D65  // F-Log, F-Log2, F-Log2 C, N-Log, D-Log
                5, 6 -> M_XYZ_to_SGamut3Cine_D65 // S-Log3, S-Log3.Cine
                7 -> M_XYZ_to_VGamut_D65         // V-Log
                else -> M_XYZ_to_Rec709_D65      // Default sRGB / Rec.709
            }
            return multiply3x3ToColMajor(targetM, M_sRGB_D65_to_XYZ)
        }
    }

    // Full screen quad
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

    init {
        thread.start()
        handler = Handler(thread.looper)
        handler.post { initGl() }
    }

    // CameraX SurfaceProcessor interface
    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            inputWidth = request.resolution.width
            inputHeight = request.resolution.height

            // Always create a new texture for each request to avoid race conditions
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            val textureId = textures[0]

            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            surfaceTexture.setOnFrameAvailableListener({
                handler.post { drawFrame() }
            }, handler)

            val surface = Surface(surfaceTexture)

            // Update member variables for drawFrame to use
            this.inputTextureId = textureId
            this.inputSurfaceTexture = surfaceTexture

            request.provideSurface(surface, handlerExecutor) { result ->
                // Clean up ONLY the resources created for THIS request
                surface.release()

                handler.post {
                    surfaceTexture.release()
                    GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)

                    // Only clear the member variables if they still point to this request's resources
                    if (this.inputTextureId == textureId) {
                        this.inputTextureId = 0
                    }
                    if (this.inputSurfaceTexture === surfaceTexture) {
                        this.inputSurfaceTexture = null
                    }
                }
            }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        handler.post {
            val s = output.getSurface(handlerExecutor) {
                // Handle close request if needed, though we manage EGL surface based on outputSurface var
                if (outputSurface != null) {
                    outputSurface = null
                    releaseEglSurface()
                }
            }
            setOutputSurfaceInternal(s, output.size.width, output.size.height)
        }
    }

    // Direct Surface binding (TextureView)
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

    // Direct Surface binding (TextureView)
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
            currentGamutMatrix = getGamutMatrix(logType)
            currentLutSize = size

            if (lutData != null && size > 0) {
                if (lutTextureId == 0) {
                    val texs = IntArray(1)
                    GLES30.glGenTextures(1, texs, 0)
                    lutTextureId = texs[0]
                }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

                val buffer = ByteBuffer.allocateDirect(lutData.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .put(lutData)
                buffer.position(0)

                GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, size, size, size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buffer)
            } else {
                currentLutSize = 0
            }
        }
    }

    fun release() {
        handler.post {
             releaseGl()
             thread.quitSafely()
        }
        // No need to shutdown handlerExecutor as it's just a wrapper
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

        createProgram()
        createDummyLut()
    }

    private fun createDummyLut() {
        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        dummyLutTextureId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, dummyLutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        // 2x2x2 black volume
        val size = 2
        val buffer = ByteBuffer.allocateDirect(size * size * size * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until size*size*size*3) buffer.put(0f)
        buffer.position(0)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, size, size, size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buffer)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
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

    private fun drawFrame() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || inputSurfaceTexture == null) return

        inputSurfaceTexture?.updateTexImage()
        inputSurfaceTexture?.getTransformMatrix(transformMatrix)

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Calculate aspect ratio correction
        var scaleX = 1f
        var scaleY = 1f
        if (width > 0 && height > 0 && inputWidth > 0 && inputHeight > 0) {
            // Check rotation (if X-axis maps to Y-axis)
            val rotated = kotlin.math.abs(transformMatrix[1]) > kotlin.math.abs(transformMatrix[0])
            val inW = if (rotated) inputHeight.toFloat() else inputWidth.toFloat()
            val inH = if (rotated) inputWidth.toFloat() else inputHeight.toFloat()

            val inAspect = inW / inH
            val outAspect = width.toFloat() / height.toFloat()

            if (inAspect > outAspect) {
                // Input is wider, crop width
                scaleX = inAspect / outAspect
            } else {
                // Input is taller, crop height
                scaleY = outAspect / inAspect
            }
        }

        if (scaleLoc >= 0) GLES30.glUniform2f(scaleLoc, scaleX, scaleY)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        if (textureLoc >= 0) GLES30.glUniform1i(textureLoc, 0)

        if (textureMatrixLoc >= 0) GLES30.glUniformMatrix4fv(textureMatrixLoc, 1, false, transformMatrix, 0)

        if (currentLutSize > 0 && lutTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            if (lutLoc >= 0) GLES30.glUniform1i(lutLoc, 1)
        } else {
             // Bind dummy to avoid warnings
             GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
             GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, dummyLutTextureId)
             if (lutLoc >= 0) GLES30.glUniform1i(lutLoc, 1)
        }

        if (gamutMatrixLoc >= 0) GLES30.glUniformMatrix3fv(gamutMatrixLoc, 1, false, currentGamutMatrix, 0)
        if (logTypeLoc >= 0) GLES30.glUniform1i(logTypeLoc, currentLogType)
        if (lutSizeLoc >= 0) GLES30.glUniform1i(lutSizeLoc, currentLutSize)
        if (focusPeakingLoc >= 0) GLES30.glUniform1i(focusPeakingLoc, if (isFocusPeakingEnabled) 1 else 0)
        if (texelSizeLoc >= 0) {
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

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        // Branch 2: Render to MediaCodec Encoder Surface for Motion Photo
        if (isEncoderActive && encoderEglSurface != EGL14.EGL_NO_SURFACE && encoderWidth > 0 && encoderHeight > 0) {
            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)

            GLES30.glViewport(0, 0, encoderWidth, encoderHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(program)

            var encScaleX = 1f
            var encScaleY = 1f
            if (inputWidth > 0 && inputHeight > 0) {
                val rotated = kotlin.math.abs(transformMatrix[1]) > kotlin.math.abs(transformMatrix[0])
                val inW = if (rotated) inputHeight.toFloat() else inputWidth.toFloat()
                val inH = if (rotated) inputWidth.toFloat() else inputHeight.toFloat()
                val inAspect = inW / inH
                val outAspect = encoderWidth.toFloat() / encoderHeight.toFloat()
                if (inAspect > outAspect) {
                    encScaleX = inAspect / outAspect
                } else {
                    encScaleY = outAspect / inAspect
                }
            }

            if (scaleLoc >= 0) GLES30.glUniform2f(scaleLoc, encScaleX, encScaleY)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
            if (textureLoc >= 0) GLES30.glUniform1i(textureLoc, 0)
            if (textureMatrixLoc >= 0) GLES30.glUniformMatrix4fv(textureMatrixLoc, 1, false, transformMatrix, 0)

            if (currentLutSize > 0 && lutTextureId != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
                if (lutLoc >= 0) GLES30.glUniform1i(lutLoc, 1)
            } else {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, dummyLutTextureId)
                if (lutLoc >= 0) GLES30.glUniform1i(lutLoc, 1)
            }

            if (gamutMatrixLoc >= 0) GLES30.glUniformMatrix3fv(gamutMatrixLoc, 1, false, currentGamutMatrix, 0)
            if (logTypeLoc >= 0) GLES30.glUniform1i(logTypeLoc, currentLogType)
            if (lutSizeLoc >= 0) GLES30.glUniform1i(lutSizeLoc, currentLutSize)

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

            val frameTimestampNs = System.nanoTime()
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, frameTimestampNs)
            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)

            // Rebind Viewfinder display surface
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }
    }

    private fun createProgram() {
        val vs = """
            #version 300 es
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            uniform vec2 uScale;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.x * uScale.x, aPosition.y * uScale.y, 0.0, 1.0);
                // Apply transform matrix from SurfaceTexture
                vTexCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        val fs = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp sampler3D;

            uniform samplerExternalOES uTexture;
            uniform sampler3D uLut;
            uniform mat3 uGamutMatrix;
            uniform int uLogType;
            uniform int uLutSize;
            uniform int uFocusPeakingEnabled;
            uniform vec2 uTexelSize;

            in vec2 vTexCoord;
            out vec4 outColor;

            // 1. Precise sRGB EOTF (Inverse OETF to Linear)
            vec3 srgbToLinear(vec3 c) {
                vec3 linearLow = c / 12.92;
                vec3 linearHigh = pow((c + vec3(0.055)) / 1.055, vec3(2.4));
                return mix(linearLow, linearHigh, step(vec3(0.04045), c));
            }

            // 2. High-precision base-10 log helper
            float log10_f(float x) {
                return log(max(x, 1e-7)) * 0.4342944819;
            }

            // 3. GLSL Analytic Log Curves (Identical to ColorPipe.cpp)
            float applyLogCurve(float x, int type) {
                x = max(x, 0.0);
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
                } else { // Default sRGB: ACES Filmic Tone Mapping + sRGB OETF
                    float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
                    float fitted = clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
                    if (fitted <= 0.0031308) return 12.92 * fitted;
                    else return 1.055 * pow(fitted, 1.0 / 2.4) - 0.055;
                }
            }

            // 4. True Triangular PDF (TPDF) dithering (+-1.0 LSB amplitude) to eliminate 8-bit quantization banding
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

            void main() {
                vec4 src = texture(uTexture, vTexCoord);
                vec3 finalRgb;

                if (uLutSize > 0) {
                    // Step A: Inverse sRGB EOTF -> Scene Linear RGB
                    vec3 linearRgb = srgbToLinear(src.rgb);

                    // Step B: Color Gamut Matrix Transform -> Target Wide Gamut (AWG / Rec2020 / SGamut / etc.)
                    vec3 wideGamutRgb = max(vec3(0.0), uGamutMatrix * linearRgb);

                    // Step C: Apply Target Log / OETF Transfer Curve
                    vec3 logRgb = vec3(
                        applyLogCurve(wideGamutRgb.r, uLogType),
                        applyLogCurve(wideGamutRgb.g, uLogType),
                        applyLogCurve(wideGamutRgb.b, uLogType)
                    );

                    // Step D: Half-texel correction for exact alignment with 3D LUT voxel centers:
                    float lutSizeFloat = float(uLutSize);
                    vec3 lutCoord = logRgb * ((lutSizeFloat - 1.0) / lutSizeFloat) + vec3(0.5 / lutSizeFloat);

                    // Step E: Sample 3D LUT in target wide-gamut log space
                    vec3 graded = texture(uLut, lutCoord).rgb;

                    // Step F: Apply high-frequency TPDF dithering to eliminate 8-bit quantization banding
                    finalRgb = triangularDither(graded, gl_FragCoord.xy);
                } else {
                    finalRgb = src.rgb;
                }

                // Focus Peaking (High-pass Sobel / edge detection in OES texture coordinates)
                if (uFocusPeakingEnabled == 1) {
                    float lumC = dot(src.rgb, vec3(0.299, 0.587, 0.114));
                    float lumN = dot(texture(uTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                    float lumS = dot(texture(uTexture, vTexCoord - vec2(0.0, uTexelSize.y)).rgb, vec3(0.299, 0.587, 0.114));
                    float lumE = dot(texture(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                    float lumW = dot(texture(uTexture, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
                    float edge = abs(lumN - lumS) + abs(lumE - lumW);
                    if (edge > 0.16) {
                        finalRgb = mix(finalRgb, vec3(0.0, 1.0, 0.3), 0.85); // Peaking highlight
                    }
                }

                outColor = vec4(finalRgb, 1.0);
            }
        """.trimIndent()

        val vShader = loadShader(GLES30.GL_VERTEX_SHADER, vs)
        val fShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fs)

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vShader)
        GLES30.glAttachShader(program, fShader)
        GLES30.glLinkProgram(program)

        // Cache uniform and attribute locations to avoid per-frame lookups
        scaleLoc = GLES30.glGetUniformLocation(program, "uScale")
        textureLoc = GLES30.glGetUniformLocation(program, "uTexture")
        textureMatrixLoc = GLES30.glGetUniformLocation(program, "uTextureMatrix")
        lutLoc = GLES30.glGetUniformLocation(program, "uLut")
        gamutMatrixLoc = GLES30.glGetUniformLocation(program, "uGamutMatrix")
        logTypeLoc = GLES30.glGetUniformLocation(program, "uLogType")
        lutSizeLoc = GLES30.glGetUniformLocation(program, "uLutSize")
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

    private fun releaseGl() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
        val textures = IntArray(3)
        var count = 0
        if (inputTextureId != 0) textures[count++] = inputTextureId
        if (lutTextureId != 0) textures[count++] = lutTextureId
        if (dummyLutTextureId != 0) textures[count++] = dummyLutTextureId

        if (count > 0) {
            GLES30.glDeleteTextures(count, textures, 0)
        }
        inputTextureId = 0
        lutTextureId = 0
        dummyLutTextureId = 0

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
    }
}


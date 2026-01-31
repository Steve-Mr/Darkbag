package com.android.example.cameraxbasic.processor

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
    private val executor = Executors.newSingleThreadExecutor()

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputTextureId = 0
    private var lutTextureId = 0
    private var program = 0
    private var outputSurface: Surface? = null
    private var width = 0
    private var height = 0

    private var currentLutSize = 0
    private var currentLogType = 0

    private val transformMatrix = FloatArray(16)

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
            // Create texture if not exists
            if (inputTextureId == 0) {
                val textures = IntArray(1)
                GLES30.glGenTextures(1, textures, 0)
                inputTextureId = textures[0]

                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            }

            // If we already have a SurfaceTexture, release it?
            // CameraX creates a new request typically when stream changes.
            inputSurfaceTexture?.release()
            inputSurfaceTexture = SurfaceTexture(inputTextureId)
            inputSurfaceTexture?.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            inputSurfaceTexture?.setOnFrameAvailableListener({
                handler.post { drawFrame() }
            }, handler)

            val surface = Surface(inputSurfaceTexture)
            request.provideSurface(surface, executor) { result ->
                surface.release()
                inputSurfaceTexture?.release()
                inputSurfaceTexture = null
            }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        handler.post {
            val s = output.getSurface(executor) {
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

    fun updateLut(lutData: FloatArray?, size: Int, logType: Int) {
        handler.post {
            currentLogType = logType
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
        executor.shutdown()
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

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)

        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTextureMatrix"), 1, false, transformMatrix, 0)

        if (currentLutSize > 0 && lutTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
        }
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLutSize"), currentLutSize)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLogType"), currentLogType)

        val posHandle = GLES30.glGetAttribLocation(program, "aPosition")
        val texHandle = GLES30.glGetAttribLocation(program, "aTexCoord")

        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(posHandle, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(posHandle)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(texHandle, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(texHandle)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(posHandle)
        GLES30.glDisableVertexAttribArray(texHandle)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun createProgram() {
        val vs = """
            #version 300 es
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                // Apply transform matrix from SurfaceTexture
                vTexCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        val fs = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            precision mediump sampler3D;

            uniform samplerExternalOES uTexture;
            uniform sampler3D uLut;
            uniform int uLutSize;
            uniform int uLogType;

            in vec2 vTexCoord;
            out vec4 outColor;

            float apply_log(float x, int type) {
                if (x < 0.0) x = 0.0;
                if (type == 1) { // Arri LogC3
                     if (x > 0.010591) return 0.247190 * (log(5.555556 * x + 0.052272) / log(10.0)) + 0.385537;
                     return 5.367655 * x + 0.092809;
                }
                if (type == 2 || type == 3) { // F-Log
                     if (x >= 0.00089) return 0.344676 * (log(0.555556 * x + 0.009468) / log(10.0)) + 0.790453;
                     return 8.52 * x + 0.0929;
                }
                if (type == 5 || type == 6) { // S-Log3
                     if (x >= 0.01125) return (420.0 + log((x + 0.01) / 0.19) / log(10.0) * 261.5) / 1023.0;
                     return (x * 171.21029 + 95.0) / 1023.0;
                }
                if (type == 7) { // V-Log
                     if (x >= 0.01) return 0.241514 * (log(x + 0.008730) / log(10.0)) + 0.598206;
                     return 5.6 * x + 0.125;
                }
                return pow(x, 1.0/2.2);
            }

            void main() {
                vec4 color = texture(uTexture, vTexCoord);

                vec3 linear = pow(color.rgb, vec3(2.2));

                vec3 logColor;
                logColor.r = apply_log(linear.r, uLogType);
                logColor.g = apply_log(linear.g, uLogType);
                logColor.b = apply_log(linear.b, uLogType);

                if (uLutSize > 0) {
                     outColor = vec4(texture(uLut, logColor).rgb, 1.0);
                } else {
                     outColor = color;
                }
            }
        """.trimIndent()

        val vShader = loadShader(GLES30.GL_VERTEX_SHADER, vs)
        val fShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fs)

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vShader)
        GLES30.glAttachShader(program, fShader)
        GLES30.glLinkProgram(program)
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
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
             EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
             if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
             if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
             EGL14.eglTerminate(eglDisplay)
        }
    }
}

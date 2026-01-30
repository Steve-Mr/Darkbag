package com.android.example.cameraxbasic.processor

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

class LutSurfaceProcessor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private val TAG = "LutSurfaceProcessor"
    private val glThread = HandlerThread("GLThread")
    private val glHandler: Handler
    private val executor = Executors.newSingleThreadExecutor()

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    // Input
    private var inputTextureId = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    // Output
    private val outputSurfaces = mutableMapOf<SurfaceOutput, EGLSurface>()

    // Shader
    private var programId = 0
    private var lutTextureId = 0
    private var lutSize = 0
    private var targetLogIndex = 0

    // Geometry
    private val vertexBuffer: FloatBuffer
    private val textureBuffer: FloatBuffer

    // Transform
    private val textureMatrix = FloatArray(16)

    init {
        glThread.start()
        glHandler = Handler(glThread.looper)

        // Quad Geometry
        val coords = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
             1f, -1f, 0f, 1f, 0f,
            -1f,  1f, 0f, 0f, 1f,
             1f,  1f, 0f, 1f, 1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(coords)
        vertexBuffer.position(0)

        textureBuffer = ByteBuffer.allocateDirect(0).asFloatBuffer() // Placeholder

        initGl()
    }

    private fun initGl() {
        glHandler.post {
            // EGL Setup
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, // Request ES 3 via Context
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

            // Dummy surface for context current
            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val dummySurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
            EGL14.eglMakeCurrent(eglDisplay, dummySurface, dummySurface, eglContext)

            // Programs
            createProgram()

            // Input Texture
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            inputTextureId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            inputSurfaceTexture = SurfaceTexture(inputTextureId)
            inputSurfaceTexture?.setOnFrameAvailableListener(this, glHandler)
            inputSurface = Surface(inputSurfaceTexture)
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        glHandler.post {
            inputSurfaceTexture?.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            request.provideSurface(inputSurface!!, executor) { result ->
                // Cleanup if needed
                Log.d(TAG, "Input surface result: ${result.resultCode}")
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glHandler.post {
            val surface = surfaceOutput.getSurface(executor) {
                glHandler.post {
                    val eglSurf = outputSurfaces.remove(surfaceOutput)
                    if (eglSurf != null) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurf)
                    }
                }
            }

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfAttribs, 0)
            outputSurfaces[surfaceOutput] = eglSurf
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        glHandler.post {
            try {
                inputSurfaceTexture?.updateTexImage()
                inputSurfaceTexture?.getTransformMatrix(textureMatrix)

                // Draw to all outputs
                for ((output, eglSurf) in outputSurfaces) {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglContext)

                    GLES20.glViewport(0, 0, output.size.width, output.size.height)
                    draw()

                    // Timestamp
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurf, inputSurfaceTexture!!.timestamp)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurf)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Draw failed", e)
            }
        }
    }

    private fun createProgram() {
        val vertexSrc = """
            attribute vec4 aPosition;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                // aPosition.z/w contains tex coords 0..1 from quad definition
                // Actually I packed 0f, 0f, 1f, 0f etc in vertexBuffer
                vec2 rawCoord = vec2(aPosition.z, aPosition.w);
                vTexCoord = (uTexMatrix * vec4(rawCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val fragmentSrc = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            precision mediump sampler3D;

            varying vec2 vTexCoord;
            uniform samplerExternalOES uInput;
            uniform sampler3D uLut;
            uniform int uLutSize;
            uniform int uTargetLog;

            // Log functions match native
            float arri_logc3(float x) {
                if (x > 0.010591) return 0.247190 * log(5.555556 * x + 0.052272) / log(10.0) + 0.385537;
                return 5.367655 * x + 0.092809;
            }
            float s_log3(float x) {
                if (x >= 0.01125) return (420.0 + log((x + 0.01) / 0.19) / log(10.0) * 261.5) / 1023.0;
                return (x * 171.21029 + 95.0) / 1023.0;
            }
            float f_log(float x) {
                 return 0.344676 * log(0.555556 * x + 0.009468) / log(10.0) + 0.790453;
            }
            float vlog(float x) {
                 return 0.241514 * log(x + 0.008730) / log(10.0) + 0.598206;
            }

            float apply_log(float x, int type) {
                if (x < 0.0) x = 0.0;
                if (type == 1) return arri_logc3(x);
                if (type == 2 || type == 3) return f_log(x); // F-Log
                if (type == 5 || type == 6) return s_log3(x); // S-Log3
                if (type == 7) return vlog(x); // V-Log
                return x;
            }

            void main() {
                vec4 color = texture2D(uInput, vTexCoord);

                if (uLutSize > 0) {
                     // 1. Linearize approximation (sRGB to Linear)
                     // Simple power function usually enough for visual preview
                     vec3 linear = pow(color.rgb, vec3(2.2));

                     // 2. Apply Log Curve (to mimic what LUT expects from Raw)
                     vec3 logC;
                     logC.r = apply_log(linear.r, uTargetLog);
                     logC.g = apply_log(linear.g, uTargetLog);
                     logC.b = apply_log(linear.b, uTargetLog);

                     // 3. LUT
                     // 3D Texture coords are 0..1
                     vec3 lutScale = vec3((float(uLutSize) - 1.0) / float(uLutSize));
                     vec3 lutOffset = vec3(1.0 / (2.0 * float(uLutSize)));
                     vec3 coord = logC * lutScale + lutOffset;

                     color.rgb = texture(uLut, coord).rgb;
                }

                gl_FragColor = color;
            }
        """.trimIndent()

        programId = loadProgram(vertexSrc, fragmentSrc)
    }

    private fun draw() {
        if (programId == 0) return

        GLES20.glUseProgram(programId)

        val posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        val mtxHandle = GLES20.glGetUniformLocation(programId, "uTexMatrix")
        val texHandle = GLES20.glGetUniformLocation(programId, "uInput")
        val lutHandle = GLES20.glGetUniformLocation(programId, "uLut")
        val lutSizeHandle = GLES20.glGetUniformLocation(programId, "uLutSize")
        val logHandle = GLES20.glGetUniformLocation(programId, "uTargetLog")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glUniform1i(texHandle, 0)

        if (lutTextureId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES20.glUniform1i(lutHandle, 1)
            GLES20.glUniform1i(lutSizeHandle, lutSize)
        } else {
            GLES20.glUniform1i(lutSizeHandle, 0)
        }

        GLES20.glUniform1i(logHandle, targetLogIndex)
        GLES20.glUniformMatrix4fv(mtxHandle, 1, false, textureMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 4, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
    }

    fun updateLut(path: String?) {
        executor.execute {
            val data = if (path != null) ColorProcessor.loadLutNative(path) else null
            glHandler.post {
                if (lutTextureId != 0) {
                    val ids = intArrayOf(lutTextureId)
                    GLES20.glDeleteTextures(1, ids, 0)
                    lutTextureId = 0
                }

                if (data != null && data.isNotEmpty()) {
                    lutSize = data[0].toInt()
                    // Create Texture 3D
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    lutTextureId = ids[0]

                    GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
                    GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)

                    // Skip first element (size)
                    val buffer = ByteBuffer.allocateDirect((data.size - 1) * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()

                    buffer.put(data, 1, data.size - 1)
                    buffer.position(0)

                    GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, lutSize, lutSize, lutSize, 0, GLES20.GL_RGB, GLES20.GL_FLOAT, buffer)
                } else {
                    lutSize = 0
                }
            }
        }
    }

    fun setTargetLog(index: Int) {
        glHandler.post {
            targetLogIndex = index
        }
    }

    fun release() {
        glHandler.post {
            // Delete Program
            if (programId != 0) {
                 GLES20.glDeleteProgram(programId)
                 programId = 0
            }
            // Delete Textures
            val texIds = IntArray(2)
            var count = 0
            if (inputTextureId != 0) texIds[count++] = inputTextureId
            if (lutTextureId != 0) texIds[count++] = lutTextureId
            if (count > 0) GLES20.glDeleteTextures(count, texIds, 0)

            // Cleanup Surfaces
            outputSurfaces.values.forEach {
                EGL14.eglDestroySurface(eglDisplay, it)
            }
            outputSurfaces.clear()

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }

            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglConfig = null

            inputSurfaceTexture?.release()
            inputSurface?.release()

            glThread.quitSafely()
        }
    }

    private fun loadProgram(vSource: String, fSource: String): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vSource)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}

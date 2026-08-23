package top.maary.darkbag.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@RequiresApi(Build.VERSION_CODES.R)
class ConcurrentFrontCameraManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConcurrentFrontCamMgr"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val executor: Executor = Executors.newSingleThreadExecutor()

    private var frontThread: HandlerThread? = null
    private var frontHandler: Handler? = null

    private var frontDevice: CameraDevice? = null
    private var frontSession: CameraCaptureSession? = null
    private var frontImageReader: ImageReader? = null
    private var frontSurface: Surface? = null

    private val lock = Mutex()
    var isStreaming = false
        private set

    private fun ensureThread() {
        if (frontThread == null) {
            frontThread = HandlerThread("ConcurrentFrontThread").apply { start() }
            frontHandler = Handler(frontThread!!.looper)
        }
    }

    fun getFrontCameraId(): String? {
        return try {
            cameraManager.cameraIdList.find { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun startFrontPreview(textureView: TextureView) = lock.withLock {
        stopFrontInternal()
        val frontId = getFrontCameraId() ?: return@withLock
        ensureThread()

        val surfaceTexture = textureView.surfaceTexture ?: return@withLock
        surfaceTexture.setDefaultBufferSize(640, 480)
        val previewSurface = Surface(surfaceTexture)
        this.frontSurface = previewSurface

        val jpegReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
        this.frontImageReader = jpegReader

        val openDeferred = CompletableDeferred<CameraDevice>()
        try {
            cameraManager.openCamera(frontId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    frontDevice = camera
                    openDeferred.complete(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    scope.launch { stop() }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Front camera error: $error")
                    if (!openDeferred.isCompleted) {
                        openDeferred.completeExceptionally(RuntimeException("Open error: $error"))
                    }
                    scope.launch { stop() }
                }
            })

            val device = openDeferred.await()
            val outputConfigs = listOf(
                OutputConfiguration(previewSurface),
                OutputConfiguration(jpegReader.surface)
            )

            val sessionDeferred = CompletableDeferred<CameraCaptureSession>()
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        frontSession = session
                        sessionDeferred.complete(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionDeferred.completeExceptionally(RuntimeException("Session configuration failed"))
                    }
                }
            )

            device.createCaptureSession(sessionConfig)
            val session = sessionDeferred.await()

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            session.setRepeatingRequest(requestBuilder.build(), null, frontHandler)
            isStreaming = true
            Log.i(TAG, "Front PiP camera preview running successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start front PiP camera", e)
            stopFrontInternal()
        }
    }

    fun captureFrontJpeg(orientationDegrees: Int, onCaptured: (ByteArray) -> Unit) {
        val dev = frontDevice ?: return
        val session = frontSession ?: return
        val reader = frontImageReader ?: return

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = img.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                onCaptured(bytes)
            } finally {
                img.close()
            }
        }, frontHandler)

        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, orientationDegrees)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            session.capture(req.build(), null, frontHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture front PiP JPEG", e)
        }
    }

    suspend fun stop() = lock.withLock {
        stopFrontInternal()
    }

    private fun stopFrontInternal() {
        try {
            frontSession?.close()
            frontSession = null
            frontDevice?.close()
            frontDevice = null
            frontImageReader?.close()
            frontImageReader = null
            frontSurface?.release()
            frontSurface = null
            frontThread?.quitSafely()
            frontThread = null
            frontHandler = null
            isStreaming = false
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping front camera", e)
        }
    }
}

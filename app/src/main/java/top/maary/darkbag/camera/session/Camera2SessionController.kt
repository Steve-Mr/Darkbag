package top.maary.darkbag.camera.session

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 硬件会话控制器。
 * 负责底层 Camera2 硬件生命周期的安全状态流转、并发互斥与专用后台线程管理。
 */
class Camera2SessionController(
    private val threadName: String = "Camera2Background"
) {

    companion object {
        private const val TAG = "Camera2SessionCtrl"
        private const val LOCK_TIMEOUT_MS = 2500L
    }

    private val _sessionState = MutableStateFlow<CameraSessionState>(CameraSessionState.Closed)
    val sessionState: StateFlow<CameraSessionState> = _sessionState.asStateFlow()

    private val openCloseLock = Semaphore(1)

    private var backgroundThread: HandlerThread? = null
    var backgroundHandler: Handler? = null
        private set

    @Volatile
    var cameraDevice: CameraDevice? = null
        private set

    @Volatile
    var captureSession: CameraCaptureSession? = null
        private set

    val isSessionActive: Boolean
        get() = captureSession != null && _sessionState.value is CameraSessionState.Active

    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread(threadName).apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    internal val availableLockPermits: Int
        get() = openCloseLock.availablePermits()

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error joining background camera thread", e)
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    fun openCamera(
        cameraManager: CameraManager,
        cameraId: String,
        stateCallback: CameraDevice.StateCallback
    ): Boolean {
        startBackgroundThread()
        val handler = backgroundHandler ?: run {
            Log.e(TAG, "Cannot open camera without valid background handler")
            return false
        }

        val openLockReleased = AtomicBoolean(false)
        fun releaseOpenLockOnce() {
            if (openLockReleased.compareAndSet(false, true)) {
                openCloseLock.release()
            }
        }

        try {
            if (!openCloseLock.tryAcquire(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Timeout acquiring camera lock for cameraId: $cameraId")
                return false
            }

            _sessionState.value = CameraSessionState.Opening(cameraId)

            val wrappedCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    _sessionState.value = CameraSessionState.Opened(cameraId, device)
                    releaseOpenLockOnce()
                    stateCallback.onOpened(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    releaseOpenLockOnce()
                    _sessionState.value = CameraSessionState.Closed
                    cameraDevice = null
                    captureSession = null
                    device.close()
                    stateCallback.onDisconnected(device)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    releaseOpenLockOnce()
                    _sessionState.value = CameraSessionState.Error(cameraId, error, "CameraDevice error: $error")
                    cameraDevice = null
                    captureSession = null
                    device.close()
                    stateCallback.onError(device, error)
                }
            }

            cameraManager.openCamera(cameraId, wrappedCallback, handler)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: $cameraId", e)
            releaseOpenLockOnce()
            _sessionState.value = CameraSessionState.Error(cameraId, -1, e.message ?: "Unknown error")
            return false
        }
    }

    fun createCaptureSession(
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler? = null
    ): Boolean {
        val device = cameraDevice ?: run {
            Log.e(TAG, "Cannot create capture session: cameraDevice is null")
            return false
        }
        val targetHandler = handler ?: backgroundHandler ?: run {
            Log.e(TAG, "Cannot create capture session: backgroundHandler is null")
            return false
        }

        val cameraId = device.id
        _sessionState.value = CameraSessionState.Configuring(cameraId, device)

        val wrappedCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                _sessionState.value = CameraSessionState.Active(cameraId, device, session)
                callback.onConfigured(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                captureSession = null
                _sessionState.value = CameraSessionState.Error(cameraId, -2, "CaptureSession configuration failed")
                callback.onConfigureFailed(session)
            }

            override fun onClosed(session: CameraCaptureSession) {
                if (captureSession == session) {
                    captureSession = null
                }
                callback.onClosed(session)
            }
        }

        return try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, wrappedCallback, targetHandler)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating capture session", e)
            _sessionState.value = CameraSessionState.Error(cameraId, -3, e.message ?: "Session creation exception")
            false
        }
    }

    fun closeSession() {
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session", e)
        } finally {
            captureSession = null
        }
    }

    fun closeCamera() {
        var acquired = false
        try {
            acquired = openCloseLock.tryAcquire(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!acquired) {
                Log.w(TAG, "Timeout waiting for openCloseLock in closeCamera; forcing cleanup")
            }
            _sessionState.value = CameraSessionState.Closing
            closeSession()
            cameraDevice?.close()
            cameraDevice = null
            _sessionState.value = CameraSessionState.Closed
        } catch (e: Exception) {
            Log.e(TAG, "Error during closeCamera", e)
        } finally {
            if (acquired) {
                openCloseLock.release()
            }
        }
    }

    fun release() {
        closeCamera()
        stopBackgroundThread()
    }
}

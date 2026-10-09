package top.maary.darkbag.camera.session

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice

/**
 * Camera2 硬件会话生命周期状态。
 */
sealed interface CameraSessionState {
    data object Closed : CameraSessionState
    data class Opening(val cameraId: String) : CameraSessionState
    data class Opened(val cameraId: String, val device: CameraDevice) : CameraSessionState
    data class Configuring(val cameraId: String, val device: CameraDevice) : CameraSessionState
    data class Active(val cameraId: String, val device: CameraDevice, val session: CameraCaptureSession) : CameraSessionState
    data object Closing : CameraSessionState
    data class Error(val cameraId: String?, val errorCode: Int, val message: String) : CameraSessionState
}

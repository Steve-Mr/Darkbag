package top.maary.darkbag.camera.multicam

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.maary.darkbag.utils.DualLensPairPreference
import top.maary.darkbag.utils.LensType
import top.maary.darkbag.utils.LogicalMultiCameraInfo
import top.maary.darkbag.utils.MultiCameraHardwareType
import top.maary.darkbag.utils.PhysicalLensInfo
import java.util.concurrent.Executor

@RequiresApi(Build.VERSION_CODES.P)
internal class MultiCamSessionConnector(
    private val cameraManager: CameraManager,
    private val scope: CoroutineScope,
    private val executor: Executor,
    private val previewCtrl: MultiCameraPreviewController,
    private val dngWriter: MultiCamDngMetadataWriter,
    private val onCloseRequested: suspend () -> Unit
) {
    companion object {
        private const val TAG = "MultiCamSessionConn"
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraDevice(
        cameraId: String,
        onOpenedAssign: (CameraDevice) -> Unit,
        onDisconnected: (CameraDevice) -> Unit,
        onErrorCallback: ((Int) -> Unit)? = null
    ): CameraDevice {
        val openDeferred = CompletableDeferred<CameraDevice>()
        cameraManager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                onOpenedAssign(device)
                openDeferred.complete(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                onDisconnected(device)
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "Camera $cameraId error: $error")
                if (!openDeferred.isCompleted) {
                    openDeferred.completeExceptionally(RuntimeException("Camera open error: $error"))
                }
                onErrorCallback?.invoke(error)
            }
        })
        return openDeferred.await()
    }

    private fun prepareOutputSurfacesAndReaders(
        lens: PhysicalLensInfo?,
        readerKey: String,
        previewSurfaceToInclude: Surface?
    ): List<OutputConfiguration> {
        val jpegSize = if (lens != null) dngWriter.getOptimalOutputSize(lens, ImageFormat.JPEG) else Size(4000, 3000)
        val jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        previewCtrl.jpegImageReaders[readerKey] = jpegReader

        val outputConfigs = mutableListOf<OutputConfiguration>()
        if (previewSurfaceToInclude != null) outputConfigs.add(OutputConfiguration(previewSurfaceToInclude))
        outputConfigs.add(OutputConfiguration(jpegReader.surface))

        if (lens != null && dngWriter.isRawSupportedForLens(lens)) {
            val rawSize = dngWriter.getOptimalOutputSize(lens, ImageFormat.RAW_SENSOR)
            val isPrimary = lens.physicalId == (previewCtrl.currentPrimaryLens?.physicalId ?: previewCtrl.standalonePrimaryPhysicalId)
            val rawCapacity = if (isPrimary) 6 else 2
            val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, rawCapacity)
            previewCtrl.rawImageReaders[readerKey] = rawReader
            outputConfigs.add(OutputConfiguration(rawReader.surface))
        }
        return outputConfigs
    }

    @SuppressLint("MissingPermission")
    suspend fun openNativeLogicalSession(
        logicalInfo: LogicalMultiCameraInfo,
        pairPref: DualLensPairPreference,
        targetPreviewSurface: Surface
    ) {
        try {
            val device = openCameraDevice(
                cameraId = logicalInfo.logicalCameraId,
                onOpenedAssign = { previewCtrl.cameraDevice = it },
                onDisconnected = { dev ->
                    Log.w(TAG, "Logical camera disconnected: ${dev.id}")
                    scope.launch { onCloseRequested() }
                },
                onErrorCallback = { error ->
                    scope.launch { onCloseRequested() }
                    previewCtrl.onSessionFailedListener?.invoke("Camera open error: $error")
                }
            )
            previewCtrl.createNativeLogicalMultiCameraSession(device, logicalInfo, pairPref, targetPreviewSurface)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open native logical multi-camera", e)
            previewCtrl.closeInternal()
            previewCtrl.onSessionFailedListener?.invoke(e.message ?: "Failed to open camera")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun openConcurrentStandaloneSessions(
        lenses: List<PhysicalLensInfo>,
        targetPreviewSurface: Surface
    ) {
        val primaryLens = previewCtrl.currentPrimaryLens?.takeIf { cur -> lenses.any { it.physicalId == cur.physicalId } }
            ?: lenses.find { it.type == LensType.WIDE }
            ?: lenses.first()
        previewCtrl.standalonePrimaryPhysicalId = primaryLens.physicalId
        var openedCount = 0

        for (lens in lenses) {
            val isPrimary = lens.physicalId == primaryLens.physicalId
            try {
                val dev = openCameraDevice(
                    cameraId = lens.physicalId,
                    onOpenedAssign = { previewCtrl.openDevices[lens.physicalId] = it },
                    onDisconnected = { previewCtrl.openDevices.remove(lens.physicalId) }
                )
                val outputConfigs = prepareOutputSurfacesAndReaders(
                    lens = lens,
                    readerKey = lens.physicalId,
                    previewSurfaceToInclude = if (isPrimary) targetPreviewSurface else null
                )
                val sessionDeferred = CompletableDeferred<CameraCaptureSession>()
                dev.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputConfigs,
                        executor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                previewCtrl.openSessions[lens.physicalId] = sess
                                if (isPrimary) previewCtrl.startRepeatingPreview(dev, sess, targetPreviewSurface)
                                sessionDeferred.complete(sess)
                            }

                            override fun onConfigureFailed(sess: CameraCaptureSession) {
                                sessionDeferred.completeExceptionally(RuntimeException("Session failed on ${lens.physicalId}"))
                            }
                        }
                    )
                )
                sessionDeferred.await()
                openedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed concurrent open for ${lens.name}, will fallback to relay mode", e)
                break
            }
        }

        if (openedCount == lenses.size) {
            Log.i(TAG, "Concurrent standalone sessions opened successfully for all $openedCount cameras")
            previewCtrl.onSessionConfiguredListener?.invoke()
        } else {
            Log.w(TAG, "Concurrent open incomplete ($openedCount/${lenses.size}), falling back to Fast Relay Burst")
            previewCtrl.closeInternal()
            val fallbackInfo = previewCtrl.currentLogicalInfo?.copy(hardwareType = MultiCameraHardwareType.FAST_RELAY_BURST)
                ?: LogicalMultiCameraInfo("0", false, 0, lenses, MultiCameraHardwareType.FAST_RELAY_BURST)
            previewCtrl.currentLogicalInfo = fallbackInfo
            openRelayPrimarySession(fallbackInfo, targetPreviewSurface)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun openRelayPrimarySession(
        logicalInfo: LogicalMultiCameraInfo,
        targetPreviewSurface: Surface,
        targetLens: PhysicalLensInfo? = null,
        isInitialConfig: Boolean = false
    ) {
        val primaryLens = targetLens ?: previewCtrl.currentPrimaryLens
            ?: previewCtrl.activeLenses.find { it.type == LensType.WIDE }
            ?: previewCtrl.activeLenses.firstOrNull()
        previewCtrl.currentPrimaryLens = primaryLens
        val primaryId = primaryLens?.physicalId ?: logicalInfo.logicalCameraId

        try {
            previewCtrl.captureSession?.close()
            previewCtrl.captureSession = null
            previewCtrl.cameraDevice?.close()
            previewCtrl.cameraDevice = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous session during relay switch", e)
        }

        try {
            val dev = openCameraDevice(
                cameraId = primaryId,
                onOpenedAssign = { previewCtrl.cameraDevice = it },
                onDisconnected = { scope.launch { onCloseRequested() } },
                onErrorCallback = { error ->
                    if (isInitialConfig) previewCtrl.onSessionFailedListener?.invoke("Camera open error: $error")
                }
            )
            val outputConfigs = prepareOutputSurfacesAndReaders(
                lens = primaryLens,
                readerKey = primaryId,
                previewSurfaceToInclude = targetPreviewSurface
            )
            dev.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.i(TAG, "Relay primary preview session configured on camera $primaryId")
                            previewCtrl.captureSession = session
                            previewCtrl.startRepeatingPreview(dev, session, targetPreviewSurface)
                            previewCtrl.onSessionConfiguredListener?.invoke()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.w(TAG, "Relay preview configuration failed on $primaryId")
                            if (isInitialConfig) previewCtrl.onSessionFailedListener?.invoke("Relay preview configuration failed")
                        }
                    }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open relay primary session", e)
            previewCtrl.closeInternal()
            if (isInitialConfig) previewCtrl.onSessionFailedListener?.invoke(e.message ?: "Failed to open relay session")
        }
    }
}

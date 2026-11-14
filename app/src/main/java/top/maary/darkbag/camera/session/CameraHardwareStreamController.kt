package top.maary.darkbag.camera.session

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.viewmodel.CameraUiTier
import top.maary.darkbag.viewmodel.CameraViewModel
import java.util.Locale

/**
 * Host contract for Camera2 hardware stream lifecycle and capture request configuration.
 */
interface CameraHardwareStreamHost {
    val hostContext: Context
    val lifecycleScope: CoroutineScope
    val camera2Manager: CameraManager
    val sessionController: Camera2SessionController
    val fragmentCameraBinding: FragmentCameraBinding?
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val lutProcessor: LutSurfaceProcessor?
    val cameraViewModel: CameraViewModel
    val currentLens: LensInfo?
    val isOisSupported: Boolean
    val isHdrOisEnabledPref: Boolean
    val isManualExposure: Boolean
    val isManualIso: Boolean
    val isManualShutter: Boolean
    val isManualFocus: Boolean
    val isFlashEnabled: Boolean
    val isHdrPlusEnabled: Boolean
    val currentIso: Int
    var liveIso: Int
    val currentExposureTime: Long
    var liveExposureTime: Long
    val currentFocusDistance: Float
    var liveFocusDistance: Float
    val currentEvIndex: Int
    val focusMeteringRegion: MeteringRectangle?
    val exposureMeteringRegion: MeteringRectangle?
    val captureResults: MutableMap<Long, TotalCaptureResult>
    val captureResultFlow: MutableSharedFlow<TotalCaptureResult>
    val activeManualTab: String?
    var lastClippingRatio: Double
    var isSwitchingLens: Boolean
    var rawImageReader: ImageReader?
    var analysisImageReader: ImageReader?
    var camera2PreviewSurface: Surface?
    var camera2RetryCount: Int
    var lastTelemetryUpdateMs: Long

    fun formatFocusDist(dist: Float): String
    fun formatShutterTime(nanos: Long): String
    fun onSessionConfigured()
}

/**
 * Controller managing low-level Camera2 hardware streams, device opening/closing,
 * Preview/Repeating requests, Optical Image Stabilization (OIS), and manual exposure/AF parameters.
 */
class CameraHardwareStreamController(
    private val host: CameraHardwareStreamHost
) {
    companion object {
        private const val TAG = "CameraStreamCtrl"
        private const val ANALYSIS_HIGHLIGHT_THRESHOLD = 240
        private const val ANALYSIS_SAMPLING_STEP = 4
    }

    private val camera2Lock = Mutex()

    data class CameraCharacteristicsSnapshot(
        val isoRange: android.util.Range<Int>?,
        val exposureTimeRange: android.util.Range<Long>?,
        val minFocusDistance: Float,
        val evRange: android.util.Range<Int>?,
        val isRawSupported: Boolean,
        val isOisSupported: Boolean,
        val isHdrOisEnabledPref: Boolean
    )

    fun readCameraCharacteristics(targetId: String): CameraCharacteristicsSnapshot {
        val chars = CameraRepository.getCharacteristics(host.camera2Manager, targetId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val availableOis = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        return CameraCharacteristicsSnapshot(
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f,
            evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            isRawSupported = map?.getOutputFormats()?.contains(ImageFormat.RAW_SENSOR) == true,
            isOisSupported = availableOis != null && availableOis.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON),
            isHdrOisEnabledPref = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsFragment.KEY_HDR_PLUS_OIS, true)
        )
    }

    fun getTargetOisMode(isHdrBurst: Boolean): Int {
        return if (isHdrBurst && !host.isHdrOisEnabledPref) {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        } else {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        }
    }

    fun applyStabilizationToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean) {
        if (host.isOisSupported) {
            val mode = getTargetOisMode(isHdrBurst)
            request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
            request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            Log.d(TAG, "OIS ${if(mode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) "enabled" else "disabled"} for ${if(isHdrBurst) "HDR+ Burst" else "Standard/Preview"}")
        } else {
            request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
    }

    fun applyManualSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean = false) {
        applyStabilizationToRequest(request, isHdrBurst)

        request.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        request.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)

        if (host.isManualExposure) {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val isoToUse = if (host.isManualIso) host.currentIso else host.liveIso
            val shutterToUse = if (host.isManualShutter) host.currentExposureTime else host.liveExposureTime
            request.set(CaptureRequest.SENSOR_SENSITIVITY, isoToUse)
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterToUse)

            if (host.isFlashEnabled) {
                request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        } else {
            if (host.isFlashEnabled) {
                request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            } else {
                request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, host.currentEvIndex)
        }

        if (host.isManualFocus) {
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, host.currentFocusDistance)
        } else {
            if (host.focusMeteringRegion != null) {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(host.focusMeteringRegion))
            } else {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(0, 0, 0, 0, 0)))
            }
        }

        if (!host.isManualExposure) {
            if (host.exposureMeteringRegion != null) {
                request.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(host.exposureMeteringRegion))
            } else {
                request.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(0, 0, 0, 0, 0)))
            }
        }

        if (host.currentLens?.useCamera2 == true) {
            val deviceId = host.sessionController.cameraDevice?.id ?: host.currentLens?.id
            if (deviceId != null) {
                val chars = CameraRepository.getCharacteristics(host.camera2Manager, deviceId)
                val lscModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                if (lscModes != null && lscModes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
                    request.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                }
                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArray != null) {
                    val targetRatio = if (host.currentLens?.isZoomPreset == true && host.currentLens?.targetZoomRatio != null) {
                        host.currentLens!!.targetZoomRatio!!
                    } else {
                        1.0f
                    }
                    if (targetRatio > 1.01f) {
                        val cropW = (activeArray.width() / targetRatio).toInt()
                        val cropH = (activeArray.height() / targetRatio).toInt()
                        val centerX = activeArray.centerX()
                        val centerY = activeArray.centerY()
                        val cropRegion = Rect(
                            centerX - cropW / 2,
                            centerY - cropH / 2,
                            centerX + cropW / 2,
                            centerY + cropH / 2
                        )
                        request.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                    } else {
                        request.set(CaptureRequest.SCALER_CROP_REGION, activeArray)
                    }
                }
            }
        }
    }

    fun updateCamera2RepeatingRequest(isHdrBurst: Boolean = false) {
        val handler = host.sessionController.backgroundHandler ?: return
        handler.post {
            val session = host.sessionController.captureSession ?: return@post
            val device = host.sessionController.cameraDevice ?: return@post
            val surface = host.camera2PreviewSurface ?: return@post
            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                request.addTarget(surface)
                val isMainLens = host.currentLens == null || (host.currentLens?.multiplier?.let { it in 0.95f..1.05f } ?: false)
                if ((host.isManualExposure || host.isHdrPlusEnabled) && isMainLens) {
                    host.analysisImageReader?.surface?.let { request.addTarget(it) }
                }
                applyManualSettingsToRequest(request, isHdrBurst)

                session.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        handleCaptureResult(result)
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Camera2 repeating request", e)
            }
        }
    }

    fun handleCaptureResult(result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (timestamp != null) {
            host.captureResults[timestamp] = result
        }
        val resIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val resTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val resFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        if (resIso != null) host.liveIso = resIso
        if (resTime != null) host.liveExposureTime = resTime
        if (resFocus != null) host.liveFocusDistance = resFocus
        host.captureResultFlow.tryEmit(result)

        val activeTier = host.cameraViewModel.uiState.value.uiTier
        if (activeTier == CameraUiTier.PRO || activeTier == CameraUiTier.CUSTOM) {
            val now = SystemClock.uptimeMillis()
            if (now - host.lastTelemetryUpdateMs >= 250L) {
                host.lastTelemetryUpdateMs = now
                val isFocusLocked = !host.isManualFocus && host.focusMeteringRegion != null
                val focusLabel = when {
                    host.isManualFocus -> "MF"
                    isFocusLocked -> "AF-L"
                    else -> "AF"
                }
                val isFocusManualOrLocked = host.isManualFocus || isFocusLocked
                val focusVal = if (host.isManualFocus) host.formatFocusDist(host.currentFocusDistance) else host.formatFocusDist(host.liveFocusDistance)
                val isoLabel = "ISO"
                val isoVal = if (host.isManualIso) "${host.currentIso}" else "${host.liveIso}"
                val shutterLabel = "SEC"
                val shutterVal = if (host.isManualShutter) host.formatShutterTime(host.currentExposureTime) else host.formatShutterTime(host.liveExposureTime)
                val evLabel = "EV"
                val evVal = if (host.currentEvIndex == 0) "0.0" else String.format(Locale.US, "%+d", host.currentEvIndex)

                host.lifecycleScope.launch(Dispatchers.Main.immediate) {
                    val binding = host.cameraUiContainerBinding ?: return@launch
                    if (binding.proInfoBarCard?.visibility != View.VISIBLE) return@launch
                    val ctx = host.hostContext
                    binding.pillFocus?.let { pill ->
                        val isTabActive = host.activeManualTab == "Focus"
                        CameraBentoDeckView.styleBentoProPill(pill, isTabActive, isFocusManualOrLocked)
                        pill.text = CameraBentoDeckView.formatProTelemetry(focusLabel, focusVal, isTabActive, isFocusManualOrLocked, ctx)
                    }
                    binding.pillIso?.let { pill ->
                        val isTabActive = host.activeManualTab == "ISO"
                        CameraBentoDeckView.styleBentoProPill(pill, isTabActive, host.isManualIso)
                        pill.text = CameraBentoDeckView.formatProTelemetry(isoLabel, isoVal, isTabActive, host.isManualIso, ctx)
                    }
                    binding.pillShutter?.let { pill ->
                        val isTabActive = host.activeManualTab == "Shutter"
                        CameraBentoDeckView.styleBentoProPill(pill, isTabActive, host.isManualShutter)
                        pill.text = CameraBentoDeckView.formatProTelemetry(shutterLabel, shutterVal, isTabActive, host.isManualShutter, ctx)
                    }
                    binding.pillEv?.let { pill ->
                        val isTabActive = host.activeManualTab == "EV"
                        val isManualEv = host.currentEvIndex != 0
                        CameraBentoDeckView.styleBentoProPill(pill, isTabActive, isManualEv)
                        pill.text = CameraBentoDeckView.formatProTelemetry(evLabel, evVal, isTabActive, isManualEv, ctx)
                    }
                }
            }
        }
    }

    suspend fun openCamera2(cameraId: String) {
        closeCamera2()
        Log.d(TAG, "Opening Camera2: $cameraId (retryCount: ${host.camera2RetryCount})")

        val opened = host.sessionController.openCamera(host.camera2Manager, cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                host.lifecycleScope.launch(Dispatchers.Main) {
                    camera2Lock.withLock {
                        host.camera2RetryCount = 0
                        createCamera2CaptureSession()
                    }
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                host.lifecycleScope.launch(Dispatchers.Main) {
                    host.isSwitchingLens = false
                    host.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
                    closeCamera2()
                }
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "Camera2 open error: $error for camera $cameraId")
                host.lifecycleScope.launch(Dispatchers.Main) {
                    host.isSwitchingLens = false
                    host.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
                    closeCamera2()
                }

                if (error == 2 && host.camera2RetryCount < 1) {
                    host.camera2RetryCount++
                    Log.i(TAG, "Retrying camera open after hardware error (attempt ${host.camera2RetryCount})...")
                    host.sessionController.backgroundHandler?.postDelayed({
                        host.lifecycleScope.launch { openCamera2(cameraId) }
                    }, 500)
                    return
                }

                host.lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(host.hostContext, host.hostContext.getString(R.string.error_camera_hardware, error), Toast.LENGTH_LONG).show()
                }
            }
        })
        if (!opened) {
            host.lifecycleScope.launch(Dispatchers.Main) {
                host.isSwitchingLens = false
                host.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
            }
        }
    }

    fun createCamera2CaptureSession() {
        val device = host.sessionController.cameraDevice ?: return
        val handler = host.sessionController.backgroundHandler ?: return

        Log.d(TAG, "Creating Camera2 Capture Session for device: ${device.id}")

        val chars = CameraRepository.getCharacteristics(host.camera2Manager, device.id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val isRawSupportedLocally = map?.getOutputFormats()?.contains(ImageFormat.RAW_SENSOR) == true
        val targetCaptureSize: Size
        if (isRawSupportedLocally) {
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
            targetCaptureSize = rawSizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
            host.rawImageReader = ImageReader.newInstance(targetCaptureSize.width, targetCaptureSize.height, ImageFormat.RAW_SENSOR, 8)
        } else {
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            targetCaptureSize = jpegSizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
            host.rawImageReader = ImageReader.newInstance(targetCaptureSize.width, targetCaptureSize.height, ImageFormat.JPEG, 8)
        }

        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
        val analysisSize = yuvSizes?.filter { it.width.toFloat()/it.height.toFloat() in 1.3f..1.4f }
            ?.minByOrNull { it.width * it.height } ?: Size(640, 480)
        host.analysisImageReader = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2)

        host.analysisImageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val width = image.width
                val height = image.height
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride

                var highlightCount = 0
                var totalSampled = 0
                for (y in 0 until height step ANALYSIS_SAMPLING_STEP) {
                    val rowStart = y * rowStride
                    for (x in 0 until width step ANALYSIS_SAMPLING_STEP) {
                        val value = buffer.get(rowStart + x * pixelStride).toInt() and 0xFF
                        if (value > ANALYSIS_HIGHLIGHT_THRESHOLD) {
                            highlightCount++
                        }
                        totalSampled++
                    }
                }
                host.lastClippingRatio = if (totalSampled > 0) highlightCount.toDouble() / totalSampled else 0.0
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing image", e)
            } finally {
                image.close()
            }
        }, handler)

        val previewSize = CameraRepository.selectOptimalPreviewSize(map?.getOutputSizes(SurfaceTexture::class.java))

        Log.d(TAG, "Requesting preview surface from LutProcessor: ${previewSize.width}x${previewSize.height}")
        host.lutProcessor?.getInputSurface(previewSize.width, previewSize.height) { surface ->
            host.lifecycleScope.launch(Dispatchers.Main) {
                camera2Lock.withLock {
                    if (host.sessionController.cameraDevice == null || host.sessionController.cameraDevice != device) {
                        Log.w(TAG, "Device changed before preview surface acquired, ignoring surface.")
                        return@launch
                    }
                    host.camera2PreviewSurface = surface

                    val targets = mutableListOf<Surface>()
                    surface?.let { targets.add(it) }
                    host.rawImageReader?.surface?.let { targets.add(it) }
                    host.analysisImageReader?.surface?.let { targets.add(it) }

                    Log.d(TAG, "Configuring session with ${targets.size} surfaces")

                    host.sessionController.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "Camera2 CaptureSession configured successfully.")
                            host.lifecycleScope.launch(Dispatchers.Main) {
                                host.isSwitchingLens = false
                                host.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
                            }
                            handler.post {
                                try {
                                    val previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    surface?.let { previewRequest.addTarget(it) }

                                    val isMainLens = host.currentLens == null || (host.currentLens?.multiplier?.let { it in 0.95f..1.05f } ?: false)
                                    if ((host.isManualExposure || host.isHdrPlusEnabled) && isMainLens) {
                                        host.analysisImageReader?.surface?.let { previewRequest.addTarget(it) }
                                    }

                                    applyManualSettingsToRequest(previewRequest)

                                    session.setRepeatingRequest(previewRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                            handleCaptureResult(result)
                                        }
                                    }, handler)

                                    host.lifecycleScope.launch(Dispatchers.Main) {
                                        host.onSessionConfigured()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start preview repeating request", e)
                                }
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Camera2 CaptureSession configure failed.")
                            host.lifecycleScope.launch(Dispatchers.Main) {
                                host.isSwitchingLens = false
                                host.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
                                Toast.makeText(
                                    host.hostContext,
                                    host.hostContext.getString(R.string.error_configure_camera_session),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    })
                }
            }
        }
    }

    suspend fun closeCamera2() {
        camera2Lock.withLock {
            host.captureResults.clear()
            host.sessionController.closeCamera()
            host.rawImageReader?.close()
            host.rawImageReader = null
            host.analysisImageReader?.close()
            host.analysisImageReader = null
            host.camera2PreviewSurface = null
            host.lutProcessor?.releaseInputSurface()
        }
    }

    suspend fun releaseCamera2Resources() {
        closeCamera2()
        host.sessionController.release()
    }
}

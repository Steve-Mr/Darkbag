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
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.viewmodel.CameraUiTier
import java.util.Locale

/**
 * Controller managing low-level Camera2 hardware streams, device opening/closing,
 * Preview/Repeating requests, Optical Image Stabilization (OIS), and manual exposure/AF parameters.
 */
class CameraHardwareStreamController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost
) {
    companion object {
        private const val TAG = "CameraStreamCtrl"
        private const val ANALYSIS_HIGHLIGHT_THRESHOLD = 240
        private const val ANALYSIS_SAMPLING_STEP = 4
    }

    /** analysis 面附着判定：主摄(1x) 且手动曝光或 HDR+ 开启（规则见 [PreviewRequestPlan.shouldAttachAnalysis]）。 */
    private fun shouldAttachAnalysis(): Boolean {
        val isMainLens = env.runtimeState.currentLens == null ||
            (env.runtimeState.currentLens?.multiplier?.let { it in 0.95f..1.05f } ?: false)
        return PreviewRequestPlan.shouldAttachAnalysis(env.runtimeState.isManualExposure, env.isHdrPlusEnabled, isMainLens)
    }

    private val camera2Lock: Mutex
        get() = env.sessionArbiter.camera2Lock

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
        val chars = CameraRepository.getCharacteristics(env.camera2Manager, targetId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val availableOis = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        return CameraCharacteristicsSnapshot(
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f,
            evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            isRawSupported = map?.getOutputFormats()?.contains(ImageFormat.RAW_SENSOR) == true,
            isOisSupported = availableOis != null && availableOis.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON),
            isHdrOisEnabledPref = SettingsRepository.getInstance(env.hostContext).current().hdrPlusOis
        )
    }

    fun getTargetOisMode(isHdrBurst: Boolean): Int {
        return if (isHdrBurst && !env.runtimeState.isHdrOisEnabledPref) {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        } else {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        }
    }

    fun applyStabilizationToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean) {
        if (env.runtimeState.isOisSupported) {
            val mode = getTargetOisMode(isHdrBurst)
            request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
            request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            Log.d(TAG, "OIS ${if(mode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) "enabled" else "disabled"} for ${if(isHdrBurst) "HDR+ Burst" else "Standard/Preview"}")
        } else {
            request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
    }

    fun applyFocusAndExposureSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean = false) {
        applyStabilizationToRequest(request, isHdrBurst)

        request.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        request.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        request.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)

        if (env.runtimeState.isManualExposure) {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val isoToUse = if (env.runtimeState.isManualIso) env.runtimeState.currentIso else env.runtimeState.liveIso
            val shutterToUse = if (env.runtimeState.isManualShutter) env.runtimeState.currentExposureTime else env.runtimeState.liveExposureTime
            request.set(CaptureRequest.SENSOR_SENSITIVITY, isoToUse)
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterToUse)

            if (env.runtimeState.isFlashEnabled) {
                request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        } else {
            if (env.runtimeState.isFlashEnabled) {
                request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            } else {
                request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, env.runtimeState.currentEvIndex)
        }

        if (env.runtimeState.isManualFocus) {
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, env.runtimeState.currentFocusDistance)
        } else {
            if (env.runtimeState.focusMeteringRegion != null) {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(env.runtimeState.focusMeteringRegion))
            } else {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(0, 0, 0, 0, 0)))
            }
        }

        if (!env.runtimeState.isManualExposure) {
            if (env.runtimeState.exposureMeteringRegion != null) {
                request.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(env.runtimeState.exposureMeteringRegion))
            } else {
                request.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(0, 0, 0, 0, 0)))
            }
        }
    }

    fun applyManualSettingsToRequest(request: CaptureRequest.Builder, isHdrBurst: Boolean = false) {
        applyFocusAndExposureSettingsToRequest(request, isHdrBurst)
        env.sessionArbiter.applySingleCameraCropToRequest(request)
    }

    /** 异步更新预览 repeating（保持既有 post 语义：调用方不关心成败）。 */
    fun updateCamera2RepeatingRequest(isHdrBurst: Boolean = false) {
        val handler = env.sessionArbiter.sessionController.backgroundHandler ?: return
        handler.post {
            applyRepeatingRequest(handler, isHdrBurst)
        }
    }

    /**
     * **同步**构建并提交预览 repeating，返回成败（S1-B2 R1）。
     *
     * 供 ZSL 探测的恢复阶段使用：`endDualStream()` 必须拿到"恢复到底成没成"的可达结果，
     * 异步 `post` 版本永远只能返回"已派发"。**必须在 camera2Handler 线程调用**
     * （由 `ZslProbeSessionAdapter` 通过 `runBlockingOnHandler` 保证）；handler/设备/会话/预览面
     * 任一缺失或提交抛异常 → `false`。
     */
    fun updateCamera2RepeatingRequestNow(): Boolean {
        val handler = env.sessionArbiter.sessionController.backgroundHandler ?: return false
        return applyRepeatingRequest(handler, isHdrBurst = false)
    }

    /** repeating 的唯一构造点（异步/同步两个入口共用），返回是否成功提交。 */
    private fun applyRepeatingRequest(handler: Handler, isHdrBurst: Boolean): Boolean {
        val session = env.sessionArbiter.sessionController.captureSession ?: return false
        val device = env.sessionArbiter.sessionController.cameraDevice ?: return false
        val surface = env.sessionArbiter.camera2PreviewSurface ?: return false
        return try {
            val plan = PreviewRequestPlan.resolve(surface, env.sessionArbiter.analysisImageReader?.surface, shouldAttachAnalysis())
            val request = env.sessionArbiter.previewRequestFactory.build(device, plan) { applyManualSettingsToRequest(it, isHdrBurst) }
            session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    handleCaptureResult(result)
                }
            }, handler)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Camera2 repeating request", e)
            false
        }
    }

    fun handleCaptureResult(result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (timestamp != null) {
            env.runtimeState.captureResults[timestamp] = result
        }
        val resIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val resTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val resFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        if (resIso != null) env.runtimeState.liveIso = resIso
        if (resTime != null) env.runtimeState.liveExposureTime = resTime
        if (resFocus != null) env.runtimeState.liveFocusDistance = resFocus
        env.runtimeState.captureResultFlow.tryEmit(result)

        val activeTier = env.cameraViewModel.uiState.value.uiTier
        if (activeTier == CameraUiTier.PRO || activeTier == CameraUiTier.CUSTOM) {
            val now = SystemClock.uptimeMillis()
            if (now - env.runtimeState.lastTelemetryUpdateMs >= 250L) {
                env.runtimeState.lastTelemetryUpdateMs = now
                val isFocusLocked = !env.runtimeState.isManualFocus && env.runtimeState.focusMeteringRegion != null
                val focusLabel = when {
                    env.runtimeState.isManualFocus -> "MF"
                    isFocusLocked -> "AF-L"
                    else -> "AF"
                }
                val isFocusManualOrLocked = env.runtimeState.isManualFocus || isFocusLocked
                val focusVal = if (env.runtimeState.isManualFocus) ui.formatFocusDist(env.runtimeState.currentFocusDistance) else ui.formatFocusDist(env.runtimeState.liveFocusDistance)
                val isoLabel = "ISO"
                val isoVal = if (env.runtimeState.isManualIso) "${env.runtimeState.currentIso}" else "${env.runtimeState.liveIso}"
                val shutterLabel = "SEC"
                val shutterVal = if (env.runtimeState.isManualShutter) ui.formatShutterTime(env.runtimeState.currentExposureTime) else ui.formatShutterTime(env.runtimeState.liveExposureTime)
                val evLabel = "EV"
                val evVal = if (env.runtimeState.currentEvIndex == 0) "0.0" else String.format(Locale.US, "%+d", env.runtimeState.currentEvIndex)

                env.lifecycleScope.launch(Dispatchers.Main.immediate) {
                    val binding = ui.cameraUiContainerBinding ?: return@launch
                    if (binding.proInfoBarCard?.visibility != View.VISIBLE) return@launch
                    val ctx = env.hostContext
                    binding.pillFocus?.let { pill ->
                        val isTabActive = env.runtimeState.activeManualTab == "Focus"
                        CameraBentoDeckView.styleBentoProPill(pill, isTabActive, isFocusManualOrLocked)
                        pill.text = CameraBentoDeckView.formatProTelemetry(focusLabel, focusVal, isTabActive, isFocusManualOrLocked, ctx)
                    }
                    binding.pillIso?.let { pill ->
                        val isTabActive = env.runtimeState.activeManualTab == "ISO"
                        CameraBentoDeckView.styleBentoProPill(pill, isTabActive, env.runtimeState.isManualIso)
                        pill.text = CameraBentoDeckView.formatProTelemetry(isoLabel, isoVal, isTabActive, env.runtimeState.isManualIso, ctx)
                    }
                    binding.pillShutter?.let { pill ->
                        val isTabActive = env.runtimeState.activeManualTab == "Shutter"
                        CameraBentoDeckView.styleBentoProPill(pill, isTabActive, env.runtimeState.isManualShutter)
                        pill.text = CameraBentoDeckView.formatProTelemetry(shutterLabel, shutterVal, isTabActive, env.runtimeState.isManualShutter, ctx)
                    }
                    binding.pillEv?.let { pill ->
                        val isTabActive = env.runtimeState.activeManualTab == "EV"
                        val isManualEv = env.runtimeState.currentEvIndex != 0
                        CameraBentoDeckView.styleBentoProPill(pill, isTabActive, isManualEv)
                        pill.text = CameraBentoDeckView.formatProTelemetry(evLabel, evVal, isTabActive, isManualEv, ctx)
                    }
                }
            }
        }
    }

    suspend fun openCamera2(cameraId: String) {
        closeCamera2()
        Log.d(TAG, "Opening Camera2: $cameraId (retryCount: ${env.runtimeState.camera2RetryCount})")

        val opened = env.sessionArbiter.sessionController.openCamera(env.camera2Manager, cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                env.lifecycleScope.launch(Dispatchers.Main) {
                    camera2Lock.withLock {
                        env.runtimeState.camera2RetryCount = 0
                        createCamera2CaptureSession()
                    }
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                env.lifecycleScope.launch(Dispatchers.Main) {
                    env.runtimeState.isSwitchingLens = false
                    ui.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
                    closeCamera2()
                }
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "Camera2 open error: $error for camera $cameraId")
                env.lifecycleScope.launch(Dispatchers.Main) {
                    env.runtimeState.isSwitchingLens = false
                    ui.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
                    closeCamera2()
                }

                if (error == 2 && env.runtimeState.camera2RetryCount < 1) {
                    env.runtimeState.camera2RetryCount++
                    Log.i(TAG, "Retrying camera open after hardware error (attempt ${env.runtimeState.camera2RetryCount})...")
                    env.sessionArbiter.sessionController.backgroundHandler?.postDelayed({
                        env.lifecycleScope.launch { openCamera2(cameraId) }
                    }, 500)
                    return
                }

                env.lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(env.hostContext, env.hostContext.getString(R.string.error_camera_hardware, error), Toast.LENGTH_LONG).show()
                }
            }
        })
        if (!opened) {
            env.lifecycleScope.launch(Dispatchers.Main) {
                env.runtimeState.isSwitchingLens = false
                ui.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
            }
        }
    }

    fun createCamera2CaptureSession() {
        val device = env.sessionArbiter.sessionController.cameraDevice ?: return
        val handler = env.sessionArbiter.sessionController.backgroundHandler ?: return
        env.sessionArbiter.rawFrameThread.start()

        Log.d(TAG, "Creating Camera2 Capture Session for device: ${device.id}")

        val chars = CameraRepository.getCharacteristics(env.camera2Manager, device.id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val isRawSupportedLocally = map?.getOutputFormats()?.contains(ImageFormat.RAW_SENSOR) == true
        val targetCaptureSize: Size
        if (isRawSupportedLocally) {
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()
            targetCaptureSize = rawSizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
            env.sessionArbiter.rawImageReader = ImageReader.newInstance(targetCaptureSize.width, targetCaptureSize.height, ImageFormat.RAW_SENSOR, 8)
        } else {
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            targetCaptureSize = jpegSizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
            env.sessionArbiter.rawImageReader = ImageReader.newInstance(targetCaptureSize.width, targetCaptureSize.height, ImageFormat.JPEG, 8)
        }

        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
        val analysisSize = yuvSizes?.filter { it.width.toFloat()/it.height.toFloat() in 1.3f..1.4f }
            ?.minByOrNull { it.width * it.height } ?: Size(640, 480)
        env.sessionArbiter.analysisImageReader = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2)

        env.sessionArbiter.analysisImageReader?.setOnImageAvailableListener({ reader ->
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
                env.runtimeState.lastClippingRatio = if (totalSampled > 0) highlightCount.toDouble() / totalSampled else 0.0
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing image", e)
            } finally {
                image.close()
            }
        }, handler)

        val previewSize = CameraRepository.selectOptimalPreviewSize(map?.getOutputSizes(SurfaceTexture::class.java))

        Log.d(TAG, "Requesting preview surface from LutProcessor: ${previewSize.width}x${previewSize.height}")
        env.sessionArbiter.lutProcessor?.getInputSurface(previewSize.width, previewSize.height) { surface ->
            env.lifecycleScope.launch(Dispatchers.Main) {
                camera2Lock.withLock {
                    if (env.sessionArbiter.sessionController.cameraDevice == null || env.sessionArbiter.sessionController.cameraDevice != device) {
                        Log.w(TAG, "Device changed before preview surface acquired, ignoring surface.")
                        return@launch
                    }
                    env.sessionArbiter.camera2PreviewSurface = surface

                    val targets = mutableListOf<Surface>()
                    surface?.let { targets.add(it) }
                    env.sessionArbiter.rawImageReader?.surface?.let { targets.add(it) }
                    env.sessionArbiter.analysisImageReader?.surface?.let { targets.add(it) }

                    Log.d(TAG, "Configuring session with ${targets.size} surfaces")

                    env.sessionArbiter.sessionController.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "Camera2 CaptureSession configured successfully.")
                            env.lifecycleScope.launch(Dispatchers.Main) {
                                env.runtimeState.isSwitchingLens = false
                                ui.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
                            }
                            handler.post {
                                try {
                                    val plan = PreviewRequestPlan.resolve(surface, env.sessionArbiter.analysisImageReader?.surface, shouldAttachAnalysis())
                                    val previewRequest = env.sessionArbiter.previewRequestFactory.build(device, plan) { applyManualSettingsToRequest(it) }
                                    session.setRepeatingRequest(previewRequest, object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                            handleCaptureResult(result)
                                        }
                                    }, handler)

                                    env.lifecycleScope.launch(Dispatchers.Main) {
                                        ui.onSessionConfigured()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start preview repeating request", e)
                                }
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Camera2 CaptureSession configure failed.")
                            env.lifecycleScope.launch(Dispatchers.Main) {
                                env.runtimeState.isSwitchingLens = false
                                ui.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
                                Toast.makeText(
                                    env.hostContext,
                                    env.hostContext.getString(R.string.error_configure_camera_session),
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
        env.sessionArbiter.closeSingleCameraResources()
    }

    suspend fun releaseCamera2Resources() {
        closeCamera2()
        env.sessionArbiter.sessionController.release()
    }
}

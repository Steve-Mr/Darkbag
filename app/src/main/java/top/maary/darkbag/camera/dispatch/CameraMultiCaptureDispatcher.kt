package top.maary.darkbag.camera.dispatch

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Toast
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.maary.darkbag.R
import top.maary.darkbag.camera.ConcurrentFrontCameraManager
import top.maary.darkbag.camera.MultiCameraCaptureManager
import top.maary.darkbag.camera.MultiCameraCaptureResult
import top.maary.darkbag.camera.MultiCameraManualConfig
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.models.StandardTimingTracker
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.DebugLogManager
import top.maary.darkbag.utils.ImageSaver
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.utils.MultiCameraHelper
import top.maary.darkbag.viewmodel.CameraViewModel
import java.io.File
import java.io.FileOutputStream

/**
 * Host contract for multi-camera capture operations.
 */
interface CameraMultiCaptureHost {
    val hostContext: Context
    val lifecycleScope: CoroutineScope
    val multiCameraManager: MultiCameraCaptureManager?
    var concurrentFrontCameraManager: ConcurrentFrontCameraManager?
    val imageRepository: ImageRepository
    val lutManager: LutManager
    val cameraViewModel: CameraViewModel
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val fragmentCameraBinding: FragmentCameraBinding?
    val processingSemaphore: Semaphore
    val isHdrPlusEnabled: Boolean
    val isRawSupported: Boolean
    val isMultiCameraManualLinked: Boolean
    val isManualFocus: Boolean
    val currentFocusDistance: Float
    val isManualIso: Boolean
    val currentIso: Int
    val isManualShutter: Boolean
    val currentExposureTime: Long
    val currentEvIndex: Int
    var isFrontPipActive: Boolean
    var isBurstActive: Boolean

    fun getCombinedOrientation(): Int
    fun showShutterBlackout()
    fun showProcessingAnimation()
    fun hideProcessingAnimation()
    fun resetBurstUi()
    fun setGalleryThumbnail(filename: String?)
    fun updateProInfoBar()
}

/**
 * Dispatcher managing multi-camera concurrent lens triggering,
 * concurrent front/back preview pipelines, and parallel frame persistence.
 */
class CameraMultiCaptureDispatcher(
    private val host: CameraMultiCaptureHost
) {
    companion object {
        private const val TAG = "CameraMultiCapture"
    }

    fun takeMultiCameraPicture(timing: StandardTimingTracker? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            host.isBurstActive = false
            host.cameraViewModel.setBurstActive(false)
            host.lifecycleScope.launch(Dispatchers.Main) { host.resetBurstUi() }
            host.processingSemaphore.release()
            return
        }

        val manager = host.multiCameraManager ?: run {
            host.isBurstActive = false
            host.cameraViewModel.setBurstActive(false)
            host.lifecycleScope.launch(Dispatchers.Main) { host.resetBurstUi() }
            host.processingSemaphore.release()
            return
        }

        host.showShutterBlackout()
        HdrPlusRequestManager.onTaskStarted()
        host.showProcessingAnimation()

        val orientation = host.getCombinedOrientation()
        val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val saveRaw = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_SAVE_RAW, false)
        val isHdrPlusActive = host.isHdrPlusEnabled && host.isRawSupported

        val manualConfig = MultiCameraManualConfig(
            isLinked = host.isMultiCameraManualLinked,
            isManualFocus = host.isManualFocus,
            focusDistance = host.currentFocusDistance,
            isManualIso = host.isManualIso,
            iso = host.currentIso,
            isManualShutter = host.isManualShutter,
            exposureTimeNanos = host.currentExposureTime,
            evIndex = host.currentEvIndex
        )

        val frontJpegDeferred = CompletableDeferred<ByteArray?>()
        if (host.isFrontPipActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            host.concurrentFrontCameraManager?.captureFrontJpeg(orientation, manualConfig) { data ->
                frontJpegDeferred.complete(data)
            }
        } else {
            frontJpegDeferred.complete(null)
        }

        manager.captureMultiCamera(
            orientationDegrees = orientation,
            isHdrPlusActive = isHdrPlusActive,
            manualConfig = manualConfig,
            onResult = { result ->
                timing?.recordFrameArrival()
                timing?.recordShutterReady()
                host.lifecycleScope.launch(Dispatchers.Main) {
                    host.isBurstActive = false
                    host.cameraViewModel.setBurstActive(false)
                    host.resetBurstUi()
                }
                host.lifecycleScope.launch(Dispatchers.IO) {
                    val frontJpeg = withTimeoutOrNull(2000L) { frontJpegDeferred.await() }
                    processAndSaveMultiCameraResult(result, saveRaw, frontJpeg, timing)
                }
            },
            onError = { errorMsg ->
                Log.e(TAG, "Multi-camera capture error: $errorMsg")
                HdrPlusRequestManager.onTaskFinished(foregroundAlreadyFinished = false)
                host.lifecycleScope.launch(Dispatchers.Main) {
                    host.processingSemaphore.release()
                    host.resetBurstUi()
                    host.hideProcessingAnimation()
                    Toast.makeText(
                        host.hostContext,
                        host.hostContext.getString(R.string.error_multi_camera_capture_failed, errorMsg),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    suspend fun processAndSaveMultiCameraResult(
        result: MultiCameraCaptureResult,
        saveRaw: Boolean,
        frontJpegData: ByteArray? = null,
        timing: StandardTimingTracker? = null
    ) {
        var foregroundFinished = false
        fun notifyForegroundFinished() {
            if (!foregroundFinished) {
                foregroundFinished = true
                HdrPlusRequestManager.onForegroundTaskFinished()
            }
        }

        try {
            val appContext = host.hostContext.applicationContext
            val prefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
            val rawFolderUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)

            var primarySavedUri: Uri? = null

            val currentLog = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None") ?: "None"
            val currentLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, "None") ?: "None"
            val logIndex = SettingsFragment.LOG_CURVES.indexOf(currentLog)
            val lutPath = if (currentLut != "None" && currentLut.isNotBlank()) {
                val f = File(host.lutManager.lutDir, currentLut)
                if (f.exists()) f.absolutePath else null
            } else null

            val colorEngineMode = prefs.getInt(SettingsFragment.KEY_COLOR_ENGINE_MODE, 2)
            val currentEditConfig = top.maary.darkbag.models.EditConfig(
                log = currentLog,
                lut = currentLut,
                colorEngineMode = colorEngineMode
            )

            for (frame in result.frames) {
                val frameBaseName = "${result.baseName}_MULTI_${frame.lens.name}"
                var jpgPathToSave: String? = null

                // 1. If we have a valid DNG, render it through ColorProcessor (LibRaw + LOG + 3D LUT + LensShading)
                if (frame.tempDngPath != null) {
                    val dngFile = File(frame.tempDngPath)
                    if (dngFile.exists() && dngFile.length() > 0) {
                        val dngBytes = dngFile.readBytes()
                        val renderedFile = File(appContext.cacheDir, "rendered_${frameBaseName}.jpg")
                        val ret = ColorProcessor.processRaw(
                            dngData = dngBytes,
                            targetLog = logIndex,
                            lutPath = lutPath,
                            exposure = 0f,
                            contrast = 0f,
                            saturation = 0f,
                            highlights = 0f,
                            shadows = 0f,
                            whites = 0f,
                            blacks = 0f,
                            digitalGain = frame.digitalGain,
                            outputJpgPath = renderedFile.absolutePath,
                            outputTiffPath = null,
                            useGpu = true,
                            orientation = frame.orientation,
                            mirror = false,
                            outputBitmap = null,
                            downsampleFactor = 1,
                            zoomFactor = 1.0f,
                            metadata = frame.captureMetadata,
                            enableMemoryColor = false,
                            colorEngineMode = colorEngineMode,
                            lensShadingMap = frame.lensShadingMap,
                            lensShadingRows = frame.lensShadingRows,
                            lensShadingCols = frame.lensShadingCols
                        )
                        if (ret >= 0 && renderedFile.exists() && renderedFile.length() > 0) {
                            jpgPathToSave = renderedFile.absolutePath
                            Log.i(TAG, "ColorProcessor successfully rendered LOG/LUT JPEG for ${frame.lens.name}")
                        } else {
                            Log.w(TAG, "ColorProcessor failed with code $ret for ${frame.lens.name}, falling back to camera JPEG")
                        }
                    }
                }

                // 2. Fallback to native JPEG if ColorProcessor wasn't used or failed
                if (jpgPathToSave == null && frame.jpegData != null) {
                    val fallbackFile = File(appContext.cacheDir, "temp_${frameBaseName}.jpg")
                    FileOutputStream(fallbackFile).use { it.write(frame.jpegData) }
                    jpgPathToSave = fallbackFile.absolutePath
                }

                // 3. Save the final JPEG to MediaStore / storage folder
                if (jpgPathToSave != null) {
                    val savedUri = ImageSaver.saveProcessedImage(
                        context = appContext,
                        inputBitmap = null,
                        bmpPath = jpgPathToSave,
                        rotationDegrees = 0,
                        zoomFactor = 1.0f,
                        baseName = frameBaseName,
                        linearDngPath = null,
                        saveJpg = true,
                        saveRaw = false,
                        jpgFolderUri = jpgFolderUri,
                        editConfig = currentEditConfig,
                        captureMetadata = frame.captureMetadata
                    )
                    if (primarySavedUri == null && savedUri != null) {
                        primarySavedUri = savedUri
                        if (timing?.firstOutputWritten == 0L) {
                            timing?.firstOutputWritten = System.currentTimeMillis()
                        }
                    }
                }

                // 4. Save RAW (DNG) if enabled by user
                if (saveRaw && frame.tempDngPath != null) {
                    ImageSaver.saveProcessedImage(
                        context = appContext,
                        inputBitmap = null,
                        bmpPath = null,
                        rotationDegrees = 0,
                        zoomFactor = 1.0f,
                        baseName = frameBaseName,
                        linearDngPath = frame.tempDngPath,
                        saveJpg = false,
                        saveRaw = true,
                        rawFolderUri = rawFolderUri,
                        editConfig = currentEditConfig,
                        captureMetadata = frame.captureMetadata
                    )
                } else if (!saveRaw && frame.tempDngPath != null) {
                    try { File(frame.tempDngPath).delete() } catch (e: Exception) {}
                }
            }

            // 5. Save Front PiP JPEG if captured
            if (frontJpegData != null) {
                val frontBaseName = "${result.baseName}_MULTI_Front"
                val frontFile = File(appContext.cacheDir, "temp_${frontBaseName}.jpg")
                FileOutputStream(frontFile).use { it.write(frontJpegData) }
                val frontUri = ImageSaver.saveProcessedImage(
                    context = appContext,
                    inputBitmap = null,
                    bmpPath = frontFile.absolutePath,
                    rotationDegrees = 0,
                    zoomFactor = 1.0f,
                    baseName = frontBaseName,
                    linearDngPath = null,
                    saveJpg = true,
                    saveRaw = false,
                    jpgFolderUri = jpgFolderUri,
                    editConfig = currentEditConfig,
                    captureMetadata = null
                )
                if (primarySavedUri == null && frontUri != null) {
                    primarySavedUri = frontUri
                    if (timing?.firstOutputWritten == 0L) {
                        timing?.firstOutputWritten = System.currentTimeMillis()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (primarySavedUri != null) {
                    host.imageRepository.invalidateCache()
                    prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, primarySavedUri.toString()).apply()
                    host.setGalleryThumbnail(primarySavedUri.toString())
                }
            }
            notifyForegroundFinished()

            if (timing?.firstOutputWritten == 0L) {
                timing?.firstOutputWritten = System.currentTimeMillis()
            }
            timing?.taskCompleted = System.currentTimeMillis()
            timing?.let { t ->
                val report = t.buildSummaryReport()
                Log.i(TAG, report)
                DebugLogManager.addTimingReport(report)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving multi-camera result", e)
        } finally {
            if (!saveRaw) {
                for (frame in result.frames) {
                    frame.tempDngPath?.let { path ->
                        try { File(path).delete() } catch (ignored: Exception) {}
                    }
                }
            }
            HdrPlusRequestManager.onTaskFinished(foregroundAlreadyFinished = foregroundFinished)
            host.processingSemaphore.release()
            withContext(Dispatchers.Main) {
                host.cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                host.resetBurstUi()
                host.hideProcessingAnimation()
            }
        }
    }

    fun toggleFrontPipInMultiCameraMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !MultiCameraHelper.isConcurrentFrontBackSupported(host.hostContext)) {
            Toast.makeText(host.hostContext, R.string.concurrent_front_camera_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        val binding = host.fragmentCameraBinding ?: return
        val pipContainer = binding.pipContainer ?: return
        val pipViewFinder = binding.pipViewFinder ?: return
        val switchBtn = host.cameraUiContainerBinding?.cameraSwitchButtonAlt ?: return

        host.isFrontPipActive = !host.isFrontPipActive
        if (host.isFrontPipActive) {
            pipContainer.visibility = View.VISIBLE
            val onPrimary = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorOnPrimaryContainer)
            val primaryContainer = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorPrimaryContainer)
            switchBtn.iconTint = ColorStateList.valueOf(onPrimary)
            switchBtn.backgroundTintList = ColorStateList.valueOf(primaryContainer)

            if (host.concurrentFrontCameraManager == null) {
                host.concurrentFrontCameraManager = ConcurrentFrontCameraManager(
                    host.hostContext,
                    host.lifecycleScope
                ).apply {
                    onFailedListener = { error ->
                        host.lifecycleScope.launch(Dispatchers.Main) {
                            Log.e(TAG, "Front PiP camera failed: $error")
                            host.isFrontPipActive = false
                            pipContainer.visibility = View.GONE
                            val onSurface = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorOnSurface)
                            val surfaceContainer = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorSurfaceContainerHighest)
                            switchBtn.iconTint = ColorStateList.valueOf(onSurface)
                            switchBtn.backgroundTintList = ColorStateList.valueOf(surfaceContainer)
                            Toast.makeText(host.hostContext, R.string.concurrent_front_camera_unsupported, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            if (pipViewFinder.isAvailable) {
                host.lifecycleScope.launch {
                    host.concurrentFrontCameraManager?.startFrontPreview(pipViewFinder)
                }
            } else {
                pipViewFinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        host.lifecycleScope.launch {
                            host.concurrentFrontCameraManager?.startFrontPreview(pipViewFinder)
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        } else {
            pipContainer.visibility = View.GONE
            val onSurface = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorOnSurface)
            val surfaceContainer = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorSurfaceContainerHighest)
            switchBtn.iconTint = ColorStateList.valueOf(onSurface)
            switchBtn.backgroundTintList = ColorStateList.valueOf(surfaceContainer)

            host.lifecycleScope.launch {
                host.concurrentFrontCameraManager?.stop()
            }
        }
        host.updateProInfoBar()
    }
}

package top.maary.darkbag.camera.video

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.camera.delegate.CameraCoordinatorHost
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.delegate.CameraUiHost
import top.maary.darkbag.rawvideo.RawVideoNative
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.ImageSaver
import top.maary.darkbag.video.Mp4VideoRecorder
import java.io.File

/**
 * Controller managing RAW DNG-sequence video and MP4 video recording,
 * recording state transitions, audio permission dialogues, and disk serialization.
 */
class CameraVideoController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost
) {
    companion object {
        private const val TAG = "CameraVideoController"
    }

    private var mp4VideoRecorder: Mp4VideoRecorder? = null

    val isRecording: Boolean
        get() = env.runtimeState.rawVideoSessionManager.recording || (mp4VideoRecorder?.recording == true)

    fun startRawVideoRecording() {
        val device = env.sessionArbiter.camera2Device ?: return
        val session = env.sessionArbiter.camera2Session ?: return
        val reader = env.sessionArbiter.rawImageReader ?: return

        val chars = CameraRepository.getCharacteristics(env.camera2Manager, device.id)
        val currentPrefs = SettingsRepository.getInstance(env.hostContext).current()

        val targetFpsStr = currentPrefs.rawVideoFps
        val targetFps = targetFpsStr.toFloatOrNull() ?: 24.0f
        val activeLut = currentPrefs.activeLut.takeIf { it != "None" }
        val activeLog = currentPrefs.targetLog
        val rawResPref = currentPrefs.rawVideoResolution
        val downsampleMode = when {
            rawResPref.contains("1080p") -> RawVideoNative.DOWNSAMPLE_BINNING_1080P
            rawResPref.contains("2K Open Gate") || (rawResPref.contains("4:3") && !rawResPref.contains("Max")) -> RawVideoNative.DOWNSAMPLE_BINNING_2K_OPEN_GATE_4_3
            rawResPref.contains("4K") -> RawVideoNative.DOWNSAMPLE_CROP_4K
            else -> RawVideoNative.DOWNSAMPLE_NONE
        }

        val timestamp = System.currentTimeMillis()
        val baseName = DarkbagIdentity.prefixedBaseName("RAWVID_${timestamp}")
        val tempDir = File(env.hostContext.cacheDir, "rawvideo")
        tempDir.mkdirs()
        val tempFile = File(tempDir, "${baseName}.rawvid")

        val lastResult = env.runtimeState.captureResults.values.lastOrNull()
        val combinedOrientation = coordinator.getCombinedOrientation()
        env.runtimeState.rawVideoSessionManager.onLowStorageCallback = {
            Handler(Looper.getMainLooper()).post {
                if (env.runtimeState.rawVideoSessionManager.recording) {
                    Toast.makeText(env.hostContext, env.hostContext.getString(R.string.toast_low_storage_recording_stopped), Toast.LENGTH_LONG).show()
                    stopRawVideoRecording()
                }
            }
        }
        val success = env.runtimeState.rawVideoSessionManager.startRecording(
            outputPath = tempFile.absolutePath,
            characteristics = chars,
            initialResult = lastResult,
            targetFps = targetFps,
            activeLutName = activeLut,
            activeLogName = activeLog,
            orientation = combinedOrientation,
            targetWidth = reader.width,
            targetHeight = reader.height,
            downsampleMode = downsampleMode
        )

        val targetFpsInt = targetFps.toInt()
        val availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val bestFpsRange = availableFpsRanges?.find { it.lower == targetFpsInt && it.upper == targetFpsInt }
            ?: availableFpsRanges?.find { it.upper == targetFpsInt }
            ?: availableFpsRanges?.find { it.lower <= targetFpsInt && it.upper >= targetFpsInt }
            ?: availableFpsRanges?.maxByOrNull { it.upper }

        val frameDurationNs = (1_000_000_000L / targetFps).toLong()

        if (success) {
            ui.cameraUiContainerBinding?.cameraCaptureButton?.setRecordingState(true)
            ui.cameraUiContainerBinding?.cameraCaptureButton?.startRotation()

            reader.setOnImageAvailableListener({ r ->
                val image = try { r.acquireNextImage() } catch (e: Exception) { r.acquireLatestImage() } ?: return@setOnImageAvailableListener
                val res = env.runtimeState.captureResults.values.lastOrNull()
                env.runtimeState.rawVideoSessionManager.onRawImageAvailable(image, res)
            }, env.sessionArbiter.camera2Handler)

            val cameraHandler = env.sessionArbiter.camera2Handler
            val submitRecordRequest: () -> Unit = {
                try {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    env.sessionArbiter.camera2PreviewSurface?.let { request.addTarget(it) }
                    request.addTarget(reader.surface)
                    val isMainLens = env.runtimeState.currentLens == null || (env.runtimeState.currentLens?.multiplier?.let { it in 0.95f..1.05f } ?: false)
                    if (isMainLens) {
                        env.sessionArbiter.analysisImageReader?.surface?.let { request.addTarget(it) }
                    }

                    // 1. Lock Target FPS range for AE to enforce frame rate and AE exposure ceiling
                    if (bestFpsRange != null) {
                        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFpsRange)
                        Log.i(TAG, "RAW video locked AE FPS Range: $bestFpsRange (target: ${targetFps}fps)")
                    }

                    // 2. Lock Frame Duration
                    request.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)

                    // 3. Apply manual exposure settings if active
                    coordinator.applyManualSettingsToRequest(request)

                    // 4. Shutter speed safety clamp: in manual exposure mode, exposure time must NOT exceed frameDurationNs
                    if (env.runtimeState.isManualExposure) {
                        val currentExp = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                        if (currentExp != null && currentExp > frameDurationNs) {
                            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, frameDurationNs)
                            Log.w(TAG, "Clamped manual shutter ($currentExp ns) to frame duration ($frameDurationNs ns)")
                        }
                    }

                    session.setRepeatingRequest(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            coordinator.handleCaptureResult(result)
                        }
                    }, cameraHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update repeating request for RAW video recording", e)
                }
            }
            if (cameraHandler != null) {
                cameraHandler.post(submitRecordRequest)
            } else {
                submitRecordRequest()
            }
        }
    }

    fun stopRawVideoRecording() {
        ui.cameraUiContainerBinding?.cameraCaptureButton?.setRecordingState(false)
        ui.cameraUiContainerBinding?.cameraCaptureButton?.stopRotation()

        env.sessionArbiter.rawImageReader?.setOnImageAvailableListener(null, null)

        val device = env.sessionArbiter.camera2Device
        val session = env.sessionArbiter.camera2Session
        val surface = env.sessionArbiter.camera2PreviewSurface
        val cameraHandler = env.sessionArbiter.camera2Handler
        if (device != null && session != null && surface != null) {
            val restorePreviewRequest: () -> Unit = {
                try {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    request.addTarget(surface)
                    val isMainLens = env.runtimeState.currentLens == null || (env.runtimeState.currentLens?.multiplier?.let { it in 0.95f..1.05f } ?: false)
                    if (isMainLens) {
                        env.sessionArbiter.analysisImageReader?.surface?.let { request.addTarget(it) }
                    }
                    coordinator.applyManualSettingsToRequest(request)
                    session.setRepeatingRequest(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            coordinator.handleCaptureResult(result)
                        }
                    }, cameraHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore preview repeating request", e)
                }
            }
            if (cameraHandler != null) {
                cameraHandler.post(restorePreviewRequest)
            } else {
                restorePreviewRequest()
            }
        }

        env.runtimeState.isVideoSaving = true
        ui.showProcessingAnimation()

        val appContext = env.hostContext.applicationContext
        val settingsRepository = SettingsRepository.getInstance(appContext)
        val currentPrefs = settingsRepository.current()
        val rawFolderUri = currentPrefs.rawStorageUri
        val jpgFolderUri = currentPrefs.jpgStorageUri

        env.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = env.runtimeState.rawVideoSessionManager.stopRecording()
                if (result == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, appContext.getString(R.string.toast_recording_too_short), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val baseName = result.file.nameWithoutExtension

                val (savedUri, thumbnail) = ImageSaver.saveRawVideo(
                    context = appContext,
                    rawVideoFile = result.file,
                    baseName = baseName,
                    targetFps = 24.0f,
                    rawFolderUri = rawFolderUri,
                    jpgFolderUri = jpgFolderUri
                )

                if (savedUri != null) {
                    settingsRepository.setLastCaptureUri(savedUri.toString())
                    env.imageRepository.invalidateCache()
                    withContext(Dispatchers.Main) {
                        ui.updateCurrentThumbnail(savedUri)
                        val photoViewButton = ui.cameraUiContainerBinding?.photoViewButton
                        if (photoViewButton != null && thumbnail != null) {
                            photoViewButton.visibility = View.VISIBLE
                            photoViewButton.alpha = 1f
                            photoViewButton.setPadding(0, 0, 0, 0)
                            Glide.with(photoViewButton)
                                .load(thumbnail)
                                .apply(RequestOptions.centerCropTransform())
                                .into(photoViewButton)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving raw video", e)
            } finally {
                withContext(Dispatchers.Main) {
                    env.runtimeState.isVideoSaving = false
                    ui.hideProcessingAnimation()
                }
            }
        }
    }

    fun startMp4VideoRecording() {
        val timestamp = System.currentTimeMillis()
        val baseName = DarkbagIdentity.prefixedBaseName("VID_${timestamp}")
        val tempDir = File(env.hostContext.cacheDir, "video")
        tempDir.mkdirs()
        val tempFile = File(tempDir, "${baseName}.mp4")

        val recorder = Mp4VideoRecorder(env.hostContext)
        val videoSurface = recorder.prepare(tempFile, 1080, 1920, 30, env.runtimeState.deviceOrientationDegrees) ?: run {
            Log.e(TAG, "Failed to prepare Mp4VideoRecorder")
            return
        }

        env.sessionArbiter.lutProcessor?.setEncoderSurface(videoSurface, 1080, 1920)

        if (!recorder.start()) {
            Log.e(TAG, "Failed to start Mp4VideoRecorder")
            env.sessionArbiter.lutProcessor?.setEncoderSurface(null, 0, 0)
            recorder.release()
            return
        }

        mp4VideoRecorder = recorder
        ui.cameraUiContainerBinding?.cameraCaptureButton?.setRecordingState(true)
        ui.cameraUiContainerBinding?.cameraCaptureButton?.startRotation()
    }

    fun stopMp4VideoRecording() {
        ui.cameraUiContainerBinding?.cameraCaptureButton?.setRecordingState(false)
        ui.cameraUiContainerBinding?.cameraCaptureButton?.stopRotation()

        env.sessionArbiter.lutProcessor?.setEncoderSurface(null, 0, 0)
        coordinator.updateMotionPhotoEncoder()

        env.runtimeState.isVideoSaving = true
        ui.showProcessingAnimation()

        val recorder = mp4VideoRecorder
        mp4VideoRecorder = null

        val appContext = env.hostContext.applicationContext
        val settingsRepository = SettingsRepository.getInstance(appContext)
        val mediaFolderUri = settingsRepository.current().jpgStorageUri

        env.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = recorder?.stop()
                if (result == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, appContext.getString(R.string.toast_recording_too_short), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val baseName = result.file.nameWithoutExtension

                val (savedUri, thumbnail) = ImageSaver.saveMp4Video(
                    context = appContext,
                    mp4File = result.file,
                    baseName = baseName,
                    mediaFolderUri = mediaFolderUri
                )

                if (savedUri != null) {
                    settingsRepository.setLastCaptureUri(savedUri.toString())
                    env.imageRepository.invalidateCache()
                    withContext(Dispatchers.Main) {
                        ui.updateCurrentThumbnail(savedUri)
                        val photoViewButton = ui.cameraUiContainerBinding?.photoViewButton
                        if (photoViewButton != null && thumbnail != null) {
                            photoViewButton.visibility = View.VISIBLE
                            photoViewButton.alpha = 1f
                            photoViewButton.setPadding(0, 0, 0, 0)
                            Glide.with(photoViewButton)
                                .load(thumbnail)
                                .apply(RequestOptions.centerCropTransform())
                                .into(photoViewButton)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving mp4 video", e)
            } finally {
                withContext(Dispatchers.Main) {
                    env.runtimeState.isVideoSaving = false
                    ui.hideProcessingAnimation()
                }
            }
        }
    }

    fun handleShutterLongPress(
        action: String?,
        isAudioGranted: Boolean,
        shouldShowRationale: Boolean,
        onShowRationale: (() -> Unit) -> Unit,
        onLaunchPermissionRequest: () -> Unit
    ) {
        if (action != PreferenceKeys.SHUTTER_LONG_PRESS_RAW_VIDEO && action != PreferenceKeys.SHUTTER_LONG_PRESS_MP4) {
            return
        }
        val startRecording = {
            if (action == PreferenceKeys.SHUTTER_LONG_PRESS_RAW_VIDEO) startRawVideoRecording() else startMp4VideoRecording()
        }
        when {
            isAudioGranted -> startRecording()
            shouldShowRationale -> onShowRationale(startRecording)
            else -> onLaunchPermissionRequest()
        }
    }


    fun handleShutterLongPressReleased() {
        if (env.runtimeState.rawVideoSessionManager.recording) {
            stopRawVideoRecording()
        } else if (mp4VideoRecorder?.recording == true) {
            stopMp4VideoRecording()
        }
    }

    fun showAudioPermissionSettingsDialog() {
        val ctx = env.hostContext
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_mic_permission_title)
            .setMessage(R.string.dialog_mic_permission_denied_message)
            .setPositiveButton(R.string.dialog_mic_permission_settings) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", ctx.packageName, null)
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open application settings", e)
                }
            }
            .setNegativeButton(R.string.dialog_mic_permission_silent, null)
            .show()
    }

    fun showAudioPermissionRationaleDialog(
        onRequestPermission: () -> Unit,
        onContinueSilent: () -> Unit
    ) {
        val ctx = env.hostContext
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_mic_permission_title)
            .setMessage(R.string.dialog_mic_permission_message)
            .setPositiveButton(R.string.dialog_mic_permission_grant) { _, _ ->
                onRequestPermission()
            }
            .setNegativeButton(R.string.dialog_mic_permission_silent) { _, _ ->
                onContinueSilent()
            }
            .show()
    }

    fun onStop() {
        if (env.runtimeState.rawVideoSessionManager.recording) {
            stopRawVideoRecording()
        } else if (mp4VideoRecorder?.recording == true) {
            stopMp4VideoRecording()
        }
    }
}

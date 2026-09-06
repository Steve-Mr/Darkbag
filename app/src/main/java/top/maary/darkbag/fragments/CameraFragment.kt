/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:SuppressLint("RestrictedApi")
package top.maary.darkbag.fragments
import top.maary.darkbag.ui.ExpressiveShutterButton
import top.maary.darkbag.utils.DebugLogManager
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.rawvideo.RawVideoNative
import top.maary.darkbag.motionphoto.MotionPhotoEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import android.content.res.ColorStateList

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.*
import android.content.ContentUris
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import android.util.Log
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.graphics.BitmapFactory
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.hardware.camera2.CameraCharacteristics
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.view.HapticFeedbackConstants
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import com.google.android.material.slider.Slider
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import top.maary.darkbag.MainApplication
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.models.CaptureMetadata

import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.ImageSaver
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.*
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.window.layout.WindowMetricsCalculator
import top.maary.darkbag.KEY_EVENT_ACTION
import top.maary.darkbag.KEY_EVENT_EXTRA
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.utils.ANIMATION_FAST_MILLIS
import top.maary.darkbag.utils.ANIMATION_SLOW_MILLIS
import top.maary.darkbag.utils.MediaStoreUtils
import top.maary.darkbag.utils.DarkbagIdentity
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.utils.HalfFrameSessionStore
import top.maary.darkbag.utils.HalfFrameManager
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.utils.ExposureUtils
import top.maary.darkbag.utils.simulateClick
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private lateinit var mediaStoreUtils: MediaStoreUtils

    private var displayId: Int = -1
    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private lateinit var windowMetricsCalculator: WindowMetricsCalculator

    // Camera2 State
    private var camera2Device: android.hardware.camera2.CameraDevice? = null
    @Volatile private var camera2Session: android.hardware.camera2.CameraCaptureSession? = null
    private var camera2PreviewSurface: android.view.Surface? = null
    private var rawImageReader: android.media.ImageReader? = null
    private var analysisImageReader: android.media.ImageReader? = null
    private val camera2Manager by lazy {
        requireContext().getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    }

    private var camera2Thread: HandlerThread? = null
    private var camera2Handler: Handler? = null
    private val camera2Lock = kotlinx.coroutines.sync.Mutex()

    private var lutProcessor: LutSurfaceProcessor? = null
    private lateinit var lutManager: LutManager
    private lateinit var cameraRepository: CameraRepository
    private lateinit var imageRepository: ImageRepository
    private var availableLenses: List<LensInfo> = emptyList()
    private var currentLens: LensInfo? = null

    private var activeLutJob: kotlinx.coroutines.Job? = null
    private var lutAdapter: LutPreviewAdapter? = null

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    // Manual Control State
    private var isManualFocus = false
    private var isManualIso = false
    private var isManualShutter = false
    private val isManualExposure: Boolean
        get() = isManualIso || isManualShutter
    @Volatile private var lastClippingRatio: Double = 0.0
    private var activeManualTab: String? = null // "Focus", "ISO", "Shutter", "EV"
    private var focusMeteringRegion: MeteringRectangle? = null
    private var exposureMeteringRegion: MeteringRectangle? = null

    // Live AE / AF telemetry readings
    @Volatile private var liveIso: Int = 100
    @Volatile private var liveExposureTime: Long = 10_000_000L
    @Volatile private var liveFocusDistance: Float = 0.0f

    // Flash State
    private var isFlashEnabled = false

    // HDR+ State
    private var isHdrPlusEnabled = false

    private val locationHelper by lazy { top.maary.darkbag.utils.LocationHelper(requireContext()) }

    private val shouldMirror: Boolean
        get() = lensFacing == CameraCharacteristics.LENS_FACING_FRONT &&
                requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(SettingsFragment.KEY_MIRROR_FRONT_CAMERA, true)

    @Volatile private var isBurstActive = false
    private val rawVideoSessionManager = top.maary.darkbag.rawvideo.RawVideoSessionManager()
    private var mp4VideoRecorder: top.maary.darkbag.video.Mp4VideoRecorder? = null
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, R.string.toast_audio_permission_granted, Toast.LENGTH_SHORT).show()
        } else {
            if (!shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
                showAudioPermissionSettingsDialog()
            } else {
                Toast.makeText(context, R.string.error_mic_permission_silent_video, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAudioPermissionSettingsDialog() {
        val ctx = context ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_mic_permission_title)
            .setMessage(R.string.dialog_mic_permission_denied_message)
            .setPositiveButton(R.string.dialog_mic_permission_settings) { _, _ ->
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", ctx.packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open application settings", e)
                }
            }
            .setNegativeButton(R.string.dialog_mic_permission_silent, null)
            .show()
    }

    private fun showAudioPermissionRationaleDialog(onContinueSilent: () -> Unit) {
        val ctx = context ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_mic_permission_title)
            .setMessage(R.string.dialog_mic_permission_message)
            .setPositiveButton(R.string.dialog_mic_permission_grant) { _, _ ->
                requestAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton(R.string.dialog_mic_permission_silent) { _, _ ->
                onContinueSilent()
            }
            .show()
    }
    private var hdrPlusBurstHelper: HdrPlusBurst? = null
    private var lastHdrPlusConfig: ExposureUtils.ExposureConfig? = null // Cache for instant trigger
    private var burstStartTime: Long = 0L // Profiling

    private var minFocusDistance = 0.0f
    private var isoRange: android.util.Range<Int>? = null
    private var exposureTimeRange: android.util.Range<Long>? = null
    private var evRange: android.util.Range<Int>? = null
    private var isRawSupported = false

    private var currentFocusDistance = 0.0f
    private var currentIso = 100
    private var currentExposureTime = 10_000_000L // 10ms
    private var currentEvIndex = 0
    private var isVideoSaving = false

    private val isProcessing: Boolean
        get() = top.maary.darkbag.processor.HdrPlusRequestManager.pendingTasksCount.value > 0 || isVideoSaving

    // Half-frame State
    private var pendingVfSnapshot: android.graphics.Bitmap? = null
    private var isHalfFrameModeEnabled = false
    private var isMultiCameraModeActive = false
    private var isMultiCameraManualLinked = true
    private var multiCameraManager: top.maary.darkbag.camera.MultiCameraCaptureManager? = null
    private var concurrentFrontCameraManager: top.maary.darkbag.camera.ConcurrentFrontCameraManager? = null
    private var isFrontPipActive = false
    private var halfFrameStep = 0
    private var halfFrameTempPath: String? = null
    private lateinit var halfFrameSessionStore: HalfFrameSessionStore
    private var isHalfFrameUiAnimating = false

    enum class CaptureMode(val key: String) {
        NORMAL(SettingsFragment.MODE_NORMAL),
        HALF_FRAME_SBS(SettingsFragment.MODE_HALF_FRAME_SBS),
        HALF_FRAME_TB(SettingsFragment.MODE_HALF_FRAME_TB),
        MULTI_CAMERA(SettingsFragment.MODE_MULTI_CAMERA);

        companion object {
            fun fromKey(key: String?): CaptureMode? = entries.find { it.key == key }
        }
    }

    private fun resolveActiveCaptureMode(prefs: SharedPreferences): CaptureMode {
        val isHalfFramePref = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
        val isMultiCamPref = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_MODE, false)
        val forceEnable = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_FORCE_ENABLE, false)
        val isMultiCamSupported = top.maary.darkbag.utils.MultiCameraHelper.isMultiCameraSupported(requireContext(), forceEnable)
        val canUseMultiCam = (isMultiCamPref || forceEnable) && isMultiCamSupported

        val savedModeKey = prefs.getString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, null)
        val requestedMode = if (savedModeKey != null) {
            CaptureMode.fromKey(savedModeKey) ?: CaptureMode.NORMAL
        } else {
            // Legacy fallback if active_capture_mode key is not yet initialized
            when {
                (isMultiCamPref || forceEnable) && isMultiCamSupported && !isHalfFramePref -> CaptureMode.MULTI_CAMERA
                isHalfFramePref -> {
                    val layout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS)
                    if (layout == SettingsFragment.HALF_FRAME_LAYOUT_TB) CaptureMode.HALF_FRAME_TB else CaptureMode.HALF_FRAME_SBS
                }
                else -> CaptureMode.NORMAL
            }
        }

        val validatedMode = when (requestedMode) {
            CaptureMode.MULTI_CAMERA -> if (canUseMultiCam) CaptureMode.MULTI_CAMERA else CaptureMode.NORMAL
            CaptureMode.HALF_FRAME_SBS, CaptureMode.HALF_FRAME_TB -> if (isHalfFramePref) requestedMode else CaptureMode.NORMAL
            CaptureMode.NORMAL -> CaptureMode.NORMAL
        }

        if (savedModeKey != validatedMode.key) {
            prefs.edit().putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, validatedMode.key).apply()
        }

        return validatedMode
    }

    private var isOisSupported = false
    private var isHdrOisEnabledPref = true
    private var currentThumbnailUri: android.net.Uri? = null
    private var currentThumbnailTimestamp: Long = 0L

    private fun updateCurrentThumbnail(uri: android.net.Uri?, timestamp: Long = System.currentTimeMillis()) {
        if (uri == null) {
            currentThumbnailUri = null
            currentThumbnailTimestamp = timestamp
            return
        }
        if (timestamp >= currentThumbnailTimestamp) {
            currentThumbnailUri = uri
            currentThumbnailTimestamp = timestamp
        }
    }

    private fun scopedHalfFrameStepKey(prefs: SharedPreferences): String =
        halfFrameSessionStore.scopedStepKeyForCurrentProfile()

    private fun readScopedHalfFrameState(prefs: SharedPreferences, requireFileForStep1: Boolean = false) {
        val session = halfFrameSessionStore.readSession(strict = requireFileForStep1)
        halfFrameStep = session.step
        halfFrameTempPath = session.tempPath
    }

    private fun writeScopedHalfFrameStep(prefs: SharedPreferences, step: Int, captureTimeMillis: Long? = null, digitalGain: Float = 1.0f, flareType: Int = -1) {
        halfFrameSessionStore.markStep(step, captureTimeMillis, digitalGain = digitalGain, flareType = flareType)
        halfFrameStep = step
        if (step == 0) {
            halfFrameTempPath = null
        }
    }

    private fun updateProcessingAnimationUi() {
        val processing = isProcessing
        if (processing) {
            cameraUiContainerBinding?.processingProgress?.visibility = View.VISIBLE
            cameraUiContainerBinding?.photoViewContainer?.visibility = View.VISIBLE
            // Hide thumbnail image while processing if in half-frame mode
            if (isHalfFrameModeEnabled) {
                cameraUiContainerBinding?.photoViewButton?.visibility = View.INVISIBLE
            }
        } else {
            cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
            // Restore thumbnail visibility if not in the middle of a half-frame pair
            if (!isHalfFrameModeEnabled || halfFrameStep == 0) {
                cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE
                cameraUiContainerBinding?.photoViewButton?.alpha = 1f
            }
        }
    }

    private fun showProcessingAnimation() {
        lifecycleScope.launch(Dispatchers.Main) {
            updateProcessingAnimationUi()
        }
    }

    private fun hideProcessingAnimation() {
        lifecycleScope.launch(Dispatchers.Main) {
            updateProcessingAnimationUi()
        }
    }

    // Zoom State
    private var zoomJob: kotlinx.coroutines.Job? = null
    private var transientLensLabel: String? = null

    private var deviceOrientationDegrees = 0

    /** Orientation listener to track device rotation independently of UI rotation */
    private val orientationEventListener by lazy {
        object : OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    return
                }

                // Map Orientation to degrees (0, 90, 180, 270 counter-clockwise)
                val newOrientationDegrees = when (orientation) {
                    in 45 until 135 -> 90 // Landscape Left (90 CCW)
                    in 135 until 225 -> 180 // Upside Down
                    in 225 until 315 -> 270 // Landscape Right (270 CCW)
                    else -> 0 // Portrait
                }

                if (newOrientationDegrees != deviceOrientationDegrees) {
                    deviceOrientationDegrees = newOrientationDegrees
                    updateShutterOrientation()
                }
            }
        }
    }

    // Cache for CaptureResults to match with raw frame timestamps
    private val captureResults = java.util.Collections.synchronizedMap(object :
        LinkedHashMap<Long, TotalCaptureResult>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TotalCaptureResult>?): Boolean {
            return size > 300
        }
    })

    // SharedFlow to broadcast CaptureResults for reactive synchronization
    private val captureResultFlow = MutableSharedFlow<TotalCaptureResult>(
        replay = 10,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Rate limiting semaphore to prevent OOM
    private val processingSemaphore = kotlinx.coroutines.sync.Semaphore(6)

    private var camera2RetryCount = 0
    private val processingChannel = kotlinx.coroutines.channels.Channel<RawImageHolder>(2)

    data class StandardTimingTracker(
        val shutterClick: Long,
        var captureCallback: Long = 0,
        var enqueued: Long = 0,
        var processingStart: Long = 0,
        var jniDone: Long = 0,
        var firstOutputWritten: Long = 0
    )

    data class RawImageHolder(
        val data: ByteBuffer,
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val rotationDegrees: Int, // Sensor Orientation
        val combinedOrientation: Int, // Combined with Display
        val zoomRatio: Float,
        val physicalId: String? = null,
        val timing: StandardTimingTracker? = null,
        val halfFrameMetadata: HalfFrameManager.Metadata? = null,
        val digitalGain: Float = 1.0f,
        val motionPhotoMp4Path: String? = null,
        val motionPhotoStillPtsUs: Long = 0L
    )

    private var motionPhotoEncoder: MotionPhotoEncoder? = null
    private var isMotionPhotoEnabled = false
    private var pendingMotionPhotoTask: CompletableDeferred<Pair<String?, Long>>? = null

    private fun updateMotionPhotoEncoder() {
        if (!isAdded) return
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        isMotionPhotoEnabled = prefs.getBoolean(SettingsFragment.KEY_MOTION_PHOTO, false)

        if (isMotionPhotoEnabled && !isHalfFrameModeEnabled && !isMultiCameraModeActive) {
            if (motionPhotoEncoder == null) {
                motionPhotoEncoder = MotionPhotoEncoder(
                    width = 1080,
                    height = 1440,
                    frameRate = 30,
                    bitRate = 10_000_000
                ).apply {
                    start()
                }
            }
            lutProcessor?.setEncoderSurface(motionPhotoEncoder?.surface, 1080, 1440)
        } else {
            lutProcessor?.setEncoderSurface(null, 0, 0)
            motionPhotoEncoder?.stop()
            motionPhotoEncoder = null
        }
        updateMotionPhotoButton()
    }

    private fun updateMotionPhotoButton() {
        val btn = cameraUiContainerBinding?.motionPhotoButton ?: return
        if (isHalfFrameModeEnabled || isMultiCameraModeActive) {
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        if (isMotionPhotoEnabled) {
            btn.setIconResource(R.drawable.ic_motion_photo_on)
            val onPrimary = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOnPrimaryContainer)
            val primaryContainer = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorPrimaryContainer)
            btn.iconTint = ColorStateList.valueOf(onPrimary)
            btn.backgroundTintList = ColorStateList.valueOf(primaryContainer)
        } else {
            btn.setIconResource(R.drawable.ic_motion_photo_off)
            val onSecondary = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOnSecondaryContainer)
            val secondaryContainer = MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSecondaryContainer)
            btn.iconTint = ColorStateList.valueOf(onSecondary)
            btn.backgroundTintList = ColorStateList.valueOf(secondaryContainer)
        }
    }


    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
            }
        } ?: Unit
    }

    override fun onStart() {
        super.onStart()
        orientationEventListener.enable()
        updateHdrPlusConstraints()
    }

    override fun onStop() {
        super.onStop()
        if (rawVideoSessionManager.recording) {
            stopRawVideoRecording()
        }
        if (mp4VideoRecorder?.recording == true) {
            stopMp4VideoRecording()
        }
        locationHelper.stopListening()
        orientationEventListener.disable()
        lutProcessor?.setEncoderSurface(null, 0, 0)
        motionPhotoEncoder?.stop()
        motionPhotoEncoder = null
        // Ensure Camera2 is closed when stopping to release hardware resources
        lifecycleScope.launch(Dispatchers.Main.immediate + NonCancellable) {
            releaseCamera2Resources()
        }
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCameraToPermissions()
            )
            return
        }

        updateHdrPlusConstraints()
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val previousMultiCam = isMultiCameraModeActive
        val previousHalfFrame = isHalfFrameModeEnabled

        val activeMode = resolveActiveCaptureMode(prefs)
        isHalfFrameModeEnabled = (activeMode == CaptureMode.HALF_FRAME_SBS || activeMode == CaptureMode.HALF_FRAME_TB)
        isMultiCameraModeActive = (activeMode == CaptureMode.MULTI_CAMERA)
        if (isHalfFrameModeEnabled) {
            val layout = if (activeMode == CaptureMode.HALF_FRAME_TB) SettingsFragment.HALF_FRAME_LAYOUT_TB else SettingsFragment.HALF_FRAME_LAYOUT_SBS
            prefs.edit().putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, layout).apply()
        }

        val modeChanged = (previousMultiCam != isMultiCameraModeActive) || (previousHalfFrame != isHalfFrameModeEnabled)

        // Re-initialize camera engine if needed.
        if (modeChanged || camera2Device == null || isMultiCameraModeActive) {
            if (_fragmentCameraBinding?.viewFinderContainer?.isLaidOut == true) {
                bindCameraUseCases()
            } else {
                _fragmentCameraBinding?.viewFinderContainer?.post {
                    bindCameraUseCases()
                }
            }
        }
        if (prefs.getBoolean(SettingsFragment.KEY_SAVE_LOCATION, false)) {
            locationHelper.startListening()
        } else {
            locationHelper.stopListening()
        }
        readScopedHalfFrameState(prefs, requireFileForStep1 = true)
        updateCameraUi()
        updateHalfFrameUI()
        _fragmentCameraBinding?.modeSwitchButton?.let { updateModeSwitchIcon(it) }
        applyUIVisibility()
        updateMotionPhotoEncoder()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        lifecycleScope.launch(Dispatchers.Main.immediate + NonCancellable) {
            closeCamera2()
        }

        lutProcessor?.setEncoderSurface(null, 0, 0)
        motionPhotoEncoder?.stop()
        motionPhotoEncoder = null
        lutProcessor?.release()
        lutProcessor = null

        // Unregister the broadcast receivers and listeners
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    private fun setGalleryThumbnail(filename: String?) {
        val binding = cameraUiContainerBinding ?: return
        val photoViewButton = binding.photoViewButton ?: return

        photoViewButton.post {
            if (filename == null) {
                updateCurrentThumbnail(null)
                photoViewButton.setImageDrawable(null)
                // In half-frame mode or during processing, we keep the container visible but hide the button
                if (isHalfFrameModeEnabled || isProcessing) {
                    photoViewButton.visibility = View.INVISIBLE
                } else {
                    photoViewButton.visibility = View.GONE
                }
                return@post
            }

            try {
                updateCurrentThumbnail(android.net.Uri.parse(filename))
            } catch (_: Exception) {}

            // In half-frame mode, control visibility based on idle state, but do not block loading
            if (isHalfFrameModeEnabled && (halfFrameStep != 0 || isProcessing)) {
                photoViewButton.visibility = View.INVISIBLE
            } else {
                photoViewButton.visibility = View.VISIBLE
                photoViewButton.alpha = 1f
            }

            photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())
            lifecycleScope.launch(Dispatchers.Main) {
                val uri = try { android.net.Uri.parse(filename) } catch (_: Exception) { null }
                val isRawVid = filename.endsWith(".rawvid", ignoreCase = true) || uri?.toString()?.endsWith(".rawvid", ignoreCase = true) == true

                if (isRawVid && uri != null) {
                    val bmp = withContext(Dispatchers.IO) {
                        try {
                            context?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
                                val handle = top.maary.darkbag.rawvideo.RawVideoNative.nativeOpenReaderFd(pfd.fd)
                                if (handle != 0L) {
                                    try {
                                        val header = top.maary.darkbag.rawvideo.RawVideoNative.readHeader(handle)
                                        if (header != null && header.width > 0 && header.height > 0) {
                                            val swapDims = (header.orientation == 90 || header.orientation == 270)
                                            val thumbW = if (swapDims) (320 * header.height) / header.width else 320
                                            val thumbH = if (swapDims) 320 else (320 * header.height) / header.width
                                            val thumbBmp = android.graphics.Bitmap.createBitmap(thumbW, thumbH, android.graphics.Bitmap.Config.ARGB_8888)
                                            val buf = java.nio.ByteBuffer.allocateDirect(header.width * header.height * 2)
                                            val meta = LongArray(3)
                                            val read = top.maary.darkbag.rawvideo.RawVideoNative.nativeReadFrame(handle, 0, meta, buf)
                                            if (read > 0) {
                                                val targetLogIndex = if (header.activeLogName.isNotBlank() && header.activeLogName != "None") {
                                                    SettingsFragment.LOG_CURVES.indexOf(header.activeLogName).takeIf { it >= 0 } ?: -1
                                                } else -1
                                                val lutManager = context?.let { top.maary.darkbag.utils.LutManager(it) }
                                                val lutPath = if (header.activeLutName.isNotBlank() && header.activeLutName != "None" && lutManager != null) {
                                                    val f = java.io.File(lutManager.lutDir, header.activeLutName)
                                                    if (f.exists()) f.absolutePath else null
                                                } else null
                                                top.maary.darkbag.rawvideo.RawVideoNative.nativeDebayerFrameToBitmap(
                                                    bayerBuffer = buf,
                                                    width = header.width,
                                                    height = header.height,
                                                    orientation = header.orientation,
                                                    cfaPattern = header.cfaPattern,
                                                    whiteLevel = header.whiteLevel,
                                                    blackLevel = header.blackLevel.firstOrNull() ?: 64f,
                                                    neutralPoint = header.neutralPoint,
                                                    targetLog = targetLogIndex,
                                                    lutPath = lutPath,
                                                    exposure = header.exposure,
                                                    contrast = header.contrast,
                                                    saturation = header.saturation,
                                                    outBitmap = thumbBmp
                                                )
                                                thumbBmp
                                            } else null
                                        } else null
                                    } finally {
                                        top.maary.darkbag.rawvideo.RawVideoNative.nativeCloseReader(handle)
                                    }
                                } else null
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bmp != null) {
                        Glide.with(photoViewButton)
                            .load(bmp)
                            .apply(RequestOptions.circleCropTransform())
                            .into(photoViewButton)
                        return@launch
                    }
                }

                val lastModified = try {
                    context?.let { mediaStoreUtils.getFileLastModified(it, android.net.Uri.parse(filename)) } ?: 0L
                } catch (e: Exception) {
                    val file = java.io.File(filename)
                    if (file.exists()) file.lastModified() else 0L
                }

                val file = java.io.File(filename)
                val loadTarget = if (file.exists()) file else filename
                Glide.with(photoViewButton)
                    .load(loadTarget)
                    .apply(RequestOptions.circleCropTransform())
                    .signature(com.bumptech.glide.signature.ObjectKey(lastModified))
                    .into(photoViewButton)
            }
        }
    }

    private fun setGalleryThumbnailBitmap(bitmap: android.graphics.Bitmap?) {
        val binding = cameraUiContainerBinding ?: return
        val photoViewButton = binding.photoViewButton ?: return

        photoViewButton.post {
            if (bitmap == null) {
                photoViewButton.setImageDrawable(null)
                if (isHalfFrameModeEnabled || isProcessing) {
                    photoViewButton.visibility = View.INVISIBLE
                } else {
                    photoViewButton.visibility = View.GONE
                }
                return@post
            }

            if (isHalfFrameModeEnabled && (halfFrameStep != 0 || isProcessing)) {
                photoViewButton.visibility = View.INVISIBLE
            } else {
                photoViewButton.visibility = View.VISIBLE
                photoViewButton.alpha = 1f
            }

            photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())
            photoViewButton.scaleX = 0.88f
            photoViewButton.scaleY = 0.88f
            Glide.with(photoViewButton)
                .load(bitmap)
                .apply(RequestOptions.circleCropTransform())
                .into(photoViewButton)
            photoViewButton.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(120)
                .start()
        }
    }

    private fun generateInstantThumbnail(
        rawBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        orientation: Int,
        cfaPattern: Int,
        whiteLevel: Int,
        blackLevel: Float,
        wb: FloatArray,
        targetLogIndex: Int,
        lutPath: String?,
        exposure: Float = 0f
    ): android.graphics.Bitmap? {
        return try {
            val thumbDim = 256
            val swapDims = (orientation == 90 || orientation == 270)
            val thumbW = if (swapDims) (thumbDim * height) / width else thumbDim
            val thumbH = if (swapDims) thumbDim else (thumbDim * height) / width
            val thumbBmp = android.graphics.Bitmap.createBitmap(thumbW.coerceAtLeast(1), thumbH.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)

            val np = if (wb.size >= 4 && wb[0] > 0.001f && wb[1] > 0.001f && wb[3] > 0.001f) {
                floatArrayOf(1.0f / wb[0], 1.0f / wb[1], 1.0f / wb[3])
            } else {
                null
            }

            val dup = rawBuffer.duplicate()
            dup.position(0)

            // If input Bayer is high resolution (>= 2000x1500), perform 2x2 binning to eliminate Bayer aliasing and improve SNR
            var debayerSuccess = false
            if (width >= 2000 && height >= 1500) {
                val bW = width / 2
                val bH = height / 2
                val binnedBuf = java.nio.ByteBuffer.allocateDirect(bW * bH * 2)
                val binSuccess = top.maary.darkbag.rawvideo.RawVideoNative.nativeBayerBinning2x2(
                    srcBuffer = dup,
                    srcWidth = width,
                    srcHeight = height,
                    srcRowStrideBytes = width * 2,
                    dstBuffer = binnedBuf,
                    mode = top.maary.darkbag.rawvideo.RawVideoNative.BINNING_MODE_AVERAGE
                )
                if (binSuccess) {
                    binnedBuf.rewind()
                    debayerSuccess = top.maary.darkbag.rawvideo.RawVideoNative.nativeDebayerFrameToBitmap(
                        bayerBuffer = binnedBuf,
                        width = bW,
                        height = bH,
                        orientation = orientation,
                        cfaPattern = cfaPattern,
                        whiteLevel = whiteLevel,
                        blackLevel = blackLevel,
                        neutralPoint = np,
                        targetLog = targetLogIndex,
                        lutPath = lutPath,
                        exposure = exposure,
                        contrast = 0f,
                        saturation = 0f,
                        outBitmap = thumbBmp
                    )
                }
            }

            if (!debayerSuccess) {
                dup.position(0)
                debayerSuccess = top.maary.darkbag.rawvideo.RawVideoNative.nativeDebayerFrameToBitmap(
                    bayerBuffer = dup,
                    width = width,
                    height = height,
                    orientation = orientation,
                    cfaPattern = cfaPattern,
                    whiteLevel = whiteLevel,
                    blackLevel = blackLevel,
                    neutralPoint = np,
                    targetLog = targetLogIndex,
                    lutPath = lutPath,
                    exposure = exposure,
                    contrast = 0f,
                    saturation = 0f,
                    outBitmap = thumbBmp
                )
            }

            if (debayerSuccess) thumbBmp else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate instant echo thumbnail", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _fragmentCameraBinding = FragmentCameraBinding.bind(view)

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        // Add back press callback for manual controls panel
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val uiBinding = cameraUiContainerBinding
                if (uiBinding?.manualControlsRoot?.visibility == View.VISIBLE ||
                    uiBinding?.proInfoBarCard?.visibility == View.VISIBLE ||
                    uiBinding?.lutListContainer?.visibility == View.VISIBLE
                ) {
                    dismissFloatingOverlays()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)

        // Handle volume key events from global flow
        viewLifecycleOwner.lifecycleScope.launch {
            (requireContext().applicationContext as MainApplication).keyEventFlow.collect { event ->
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
                }
            }
        }

        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

        // Initialize WindowMetricsCalculator to retrieve display metrics
        windowMetricsCalculator = WindowMetricsCalculator.getOrCreate()

        // Initialize MediaStoreUtils for fetching this app's images
        mediaStoreUtils = MediaStoreUtils(requireContext())

        lutManager = LutManager(requireContext())
        cameraRepository = CameraRepository(requireContext())
        imageRepository = ImageRepository(requireContext())
        halfFrameSessionStore = HalfFrameSessionStore(requireContext())

        // Initialize Flash State
        isFlashEnabled = prefs.getBoolean(SettingsFragment.KEY_FLASH_MODE, false)

        // Initialize HDR+ State
        isHdrPlusEnabled = prefs.getBoolean(KEY_HDR_PLUS_ENABLED, true)
        updateHdrPlusUi()
        updateHdrPlusConstraints()

        // Initialize Half-frame & Multi-camera State (isolated by mode/layout profile)
        val activeMode = resolveActiveCaptureMode(prefs)
        isHalfFrameModeEnabled = (activeMode == CaptureMode.HALF_FRAME_SBS || activeMode == CaptureMode.HALF_FRAME_TB)
        isMultiCameraModeActive = (activeMode == CaptureMode.MULTI_CAMERA)
        if (isHalfFrameModeEnabled) {
            val layout = if (activeMode == CaptureMode.HALF_FRAME_TB) SettingsFragment.HALF_FRAME_LAYOUT_TB else SettingsFragment.HALF_FRAME_LAYOUT_SBS
            prefs.edit().putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, layout).apply()
        }
        readScopedHalfFrameState(prefs, requireFileForStep1 = true)

        updateHalfFrameUI()
        updateShutterOrientation()
        _fragmentCameraBinding?.viewFinderContainer?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isHalfFrameModeEnabled && !isHalfFrameUiAnimating) {
                updateHalfFrameUI()
            }
        }

        // Initialize HDR+ Burst Helper
        hdrPlusBurstHelper = HdrPlusBurst(
            frameCount = 1,
            onBurstComplete = { burstResult ->
                processHdrPlusBurst(burstResult, 1.0f)
            }
        )

        // Listen for background save completions from JNI or WorkManager
        viewLifecycleOwner.lifecycleScope.launch {
            ColorProcessor.backgroundSaveFlow.collect { event ->
                Log.d(TAG, "Received background save complete event: ${event.baseName}")

                (requireContext().applicationContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
                    try {
                        // For HDR+, work is already finalized by HdrPlusExportWorker.
                        // We only need to update the thumbnail using the provided targetUri.
                        if (event.targetUri != null) {
                            Log.d(TAG, "Update thumbnail for ${event.baseName}: ${event.targetUri}")
                            withContext(Dispatchers.Main) {
                                imageRepository.invalidateCache()
                                prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, event.targetUri).apply()
                                setGalleryThumbnail(event.targetUri)
                            }
                        } else {
                             Log.w(TAG, "Received save event for ${event.baseName} without targetUri.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Background UI update failed for ${event.baseName}", e)
                    }
                }
            }
        }

        // Listen for HDR+/RAW processing queue changes to drive loading animation
        viewLifecycleOwner.lifecycleScope.launch {
            top.maary.darkbag.processor.HdrPlusRequestManager.pendingTasksCount.collect {
                withContext(Dispatchers.Main) {
                    updateProcessingAnimationUi()
                }
            }
        }

        // Start processing consumer
        // Use viewLifecycleOwner.lifecycleScope for the listener loop to avoid leaking fragment.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            for (holder in processingChannel) {
                val appContext = requireContext().applicationContext
                // Launch each task in applicationScope so it continues even if fragment is destroyed
                (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
                    try {
                        processImageAsync(appContext, holder)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image from channel", e)
                    } finally {
                        processingSemaphore.release()
                        withContext(Dispatchers.Main) {
                            cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                            // Wait for final stitched result in backgroundSaveFlow to hide animation for ALL pipelines.
                        }
                    }
                }
            }
        }

        // Wait for the views to be properly laid out
        _fragmentCameraBinding?.viewFinderContainer?.post {

            // Keep track of the display in which this view is attached
            displayId = _fragmentCameraBinding?.viewFinderContainer?.display?.displayId ?: -1

            // Build UI controls
            updateCameraUi()

            applyUIVisibility()

            // Initialize LUT Processor early to be ready for any engine
            if (lutProcessor == null) {
                lutProcessor = LutSurfaceProcessor()
                updateMotionPhotoEncoder()
            }

            // Setup ViewFinder early
            setupViewFinderBinding()

            // Setup Tap to Focus
            setupTapToFocus()

            // Set up the camera and its use cases
            lifecycleScope.launch {
                setUpCamera()
                updateHdrPlusConstraints()
            }
        }
    }

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()
    }

    private fun refreshLenses(force: Boolean = false) {
        val repoFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_BACK
        else
            CameraCharacteristics.LENS_FACING_FRONT

        // Use unified focal length presets (includes digital ones like 28mm, 35mm, 2.0x)
        val newLenses = cameraRepository.getFocalLengthPresets(repoFacing)

        if (!force && newLenses.size == availableLenses.size && newLenses.zip(availableLenses).all { it.first.sensorId == it.second.sensorId && it.first.facing == it.second.facing }) {
            return // No change
        }

        Log.d(TAG, "Available Lenses/Presets identified: ${newLenses.size} for facing $repoFacing")

        // Update currentLens reference if it exists
        var updatedLens: LensInfo? = null
        currentLens?.let { old ->
             var found = newLenses.find { it.sensorId == old.sensorId }
             if (found == null) {
                 val base1x = newLenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
                 if (base1x != null) {
                     found = cameraRepository.get1xPresets(base1x).find { it.sensorId == old.sensorId }
                 }
             }
             updatedLens = found
        }

        if (updatedLens == null) {
            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val savedLensId = prefs.getString(KEY_SELECTED_LENS_ID, null)
            val defaultLensId = prefs.getString(SettingsFragment.KEY_DEFAULT_LENS_ID, null)
            val default1xFocal = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, null)

            if (savedLensId != null) {
                var found = newLenses.find { it.sensorId == savedLensId }
                if (found == null) {
                    val base1x = newLenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
                    if (base1x != null) {
                        found = cameraRepository.get1xPresets(base1x).find { it.sensorId == savedLensId }
                    }
                }
                updatedLens = found
            }

            if (updatedLens == null) {
                val targetId = defaultLensId
                var found = newLenses.find { it.sensorId == targetId }
                if (found == null) {
                    found = newLenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
                        ?: newLenses.firstOrNull()
                }

                if (found != null && found.multiplier in 0.95f..1.05f && !found.isZoomPreset) {
                    val presets1x = cameraRepository.get1xPresets(found)
                    updatedLens = presets1x.find { it.name == default1xFocal } ?: presets1x.firstOrNull() ?: found
                } else {
                    updatedLens = found
                }
            }
        }

        currentLens = updatedLens
        availableLenses = newLenses
    }

    /** Initialize Camera Engine, and prepare to bind the camera use cases  */
    private suspend fun setUpCamera() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        val defaultLensId = prefs.getString(SettingsFragment.KEY_DEFAULT_LENS_ID, null)
        if (defaultLensId != null) {
            val facing = cameraRepository.getFacingOfSensorId(defaultLensId)
            lensFacing = if (facing == CameraCharacteristics.LENS_FACING_FRONT)
                CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        } else {
            lensFacing = prefs.getInt(KEY_LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        }

        // Initialize Lenses
        withContext(Dispatchers.Default) {
            refreshLenses()
        }

        // Select lensFacing depending on the available cameras
        if (availableLenses.isEmpty()) {
             lensFacing = CameraCharacteristics.LENS_FACING_FRONT
        }

        // Enable or disable switching between cameras
        updateCameraSwitchButton()

        // Build and bind the camera use cases
        bindCameraUseCases()
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun setupViewFinderBinding() {
        val proc = lutProcessor ?: return
        // Connect ViewFinder TextureView to LutProcessor
        _fragmentCameraBinding?.viewFinder?.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    proc.setOutputSurface(Surface(st), w, h)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    proc.setOutputSurface(Surface(st), w, h)
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    proc.setOutputSurface(null, 0, 0)
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }

        // If surface is already available, bind it immediately
        val vf = _fragmentCameraBinding?.viewFinder
        if (vf != null && vf.isAvailable) {
            vf.surfaceTexture?.let { st ->
                proc.setOutputSurface(
                    Surface(st),
                    vf.width,
                    vf.height
                )
            }
        }
        updateLiveLut()

        // Ensure LUT is loaded
    }


    private var bindJob: kotlinx.coroutines.Job? = null

    private fun bindCameraUseCases() {
        bindJob?.cancel()
        bindJob = lifecycleScope.launch {
            // Move heavy lens refresh to background
            withContext(Dispatchers.Default) {
                refreshLenses()
            }

            // Ensure Camera2 is closed if we are switching engines or lenses
            closeCamera2()

            bindCameraUseCasesInternal()
        }
    }

    private suspend fun bindCameraUseCasesInternal() {
        // Reset Tap-to-Focus regions on lens switch
        focusMeteringRegion = null
        exposureMeteringRegion = null

        // Fetch Characteristics for Manual Control
        val targetId = currentLens?.id ?: if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) "0" else "1"

        try {
            val chars = camera2Manager.getCameraCharacteristics(targetId)

            isoRange = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposureTimeRange = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            minFocusDistance = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
            evRange = chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

            // Clamp current values to new ranges
            isoRange?.let { currentIso = currentIso.coerceIn(it.lower, it.upper) }
            exposureTimeRange?.let { currentExposureTime = currentExposureTime.coerceIn(it.lower, it.upper) }

            // Check for RAW support early to enable HDR+ UI
            val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            isRawSupported = map?.getOutputFormats()?.contains(android.graphics.ImageFormat.RAW_SENSOR) == true

            // Check OIS support
            val availableOis = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            isOisSupported = availableOis != null && availableOis.contains(android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)

            // Cache HDR+ OIS preference
            isHdrOisEnabledPref = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsFragment.KEY_HDR_PLUS_OIS, true)

            // Update UI if manual panel is visible
            lifecycleScope.launch(Dispatchers.Main) {
                if (activeManualTab != null) {
                    updateDialPanel()
                }
                updateProInfoBar()

                if (isRawSupported) {
                    cameraUiContainerBinding?.hdrPlusSwitch?.visibility = View.VISIBLE
                    updateHdrPlusUi()
                } else {
                    cameraUiContainerBinding?.hdrPlusSwitch?.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch camera characteristics", e)
        }

        // Force 4:3 Aspect Ratio for the container
        val metrics = windowMetricsCalculator.computeCurrentWindowMetrics(requireActivity()).bounds
        val ratio = if (metrics.width() < metrics.height()) "3:4" else "4:3"
        val viewFinder = _fragmentCameraBinding?.viewFinder
        val viewFinderContainer = _fragmentCameraBinding?.viewFinderContainer

        (viewFinderContainer?.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
            if (lp.dimensionRatio != ratio) {
                lp.dimensionRatio = ratio
                viewFinderContainer.layoutParams = lp
            }
        }

        // Also update the AutoFitTextureView's internal ratio
        if (ratio == "3:4") {
            viewFinder?.setAspectRatio(3, 4)
        } else {
            viewFinder?.setAspectRatio(4, 3)
        }

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        if (isMultiCameraModeActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val logicalInfo = top.maary.darkbag.utils.MultiCameraHelper.getLogicalMultiCameraInfo(requireContext())
            if (logicalInfo != null) {
                Log.d(TAG, "Binding Multi-Camera Session with logical camera: ${logicalInfo.logicalCameraId}")
                closeCamera2()

                val countPref = top.maary.darkbag.utils.MultiCameraCountPreference.fromKey(
                    prefs.getString(SettingsFragment.KEY_MULTI_CAMERA_COUNT_PREF, null)
                )
                val pairPref = top.maary.darkbag.utils.DualLensPairPreference.fromKey(
                    prefs.getString(SettingsFragment.KEY_MULTI_CAMERA_DUAL_PAIR, null)
                )
                val saveRaw = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_SAVE_RAW, false)

                lifecycleScope.launch(Dispatchers.Main) {
                    initLensControls()
                }

                lutProcessor?.getInputSurface(1440, 1080) { previewSurface ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (!isAdded) return@launch
                        if (multiCameraManager == null) {
                            multiCameraManager = top.maary.darkbag.camera.MultiCameraCaptureManager(requireContext(), lifecycleScope)
                        }
                        multiCameraManager?.onSessionFailedListener = { error ->
                            Log.e(TAG, "Multi-camera session failed: $error, falling back to standard mode")
                            lifecycleScope.launch(Dispatchers.Main) {
                                isMultiCameraModeActive = false
                                _fragmentCameraBinding?.modeSwitchButton?.let { updateModeSwitchIcon(it) }
                                bindCameraUseCases()
                            }
                        }
                        multiCameraManager?.openAndConfigure(
                            logicalInfo = logicalInfo,
                            countPref = countPref,
                            pairPref = pairPref,
                            targetPreviewSurface = previewSurface,
                            saveRaw = saveRaw,
                            initialLensMultiplier = currentLens?.multiplier
                        )
                    }
                }
                return
            } else {
                Log.w(TAG, "Multi-camera requested but not supported on device, disabling multi-camera mode")
                isMultiCameraModeActive = false
                _fragmentCameraBinding?.modeSwitchButton?.let { updateModeSwitchIcon(it) }
            }
        }

        Log.d(TAG, "Switching to Camera2 Engine for lens: ${currentLens?.name ?: targetId}")

        // Ensure UI is updated before opening Camera2
        initLensControls()

        // Update constraints to set flash/underexposure button visibility correctly
        updateHdrPlusConstraints()

        // Re-apply half-frame transformations and UI if enabled
        updateHalfFrameUI()

        // Pre-initialize JNI memory pool with burst size and sensor resolution
        val burstSizeStr = prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "5") ?: "5"
        val burstSize = burstSizeStr.toIntOrNull() ?: 5
        lifecycleScope.launch(Dispatchers.Default) {
            val targetCharId = currentLens?.id ?: targetId
            val sensorSize = try {
                val c = camera2Manager.getCameraCharacteristics(targetCharId)
                val m = c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val rawSizes = m?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
                rawSizes?.maxByOrNull { it.width * it.height }
                    ?: c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { android.util.Size(it.width(), it.height()) }
            } catch (e: Exception) { null } ?: android.util.Size(4000, 3000)
            ColorProcessor.initMemoryPool(sensorSize.width, sensorSize.height, burstSize)
        }

        // Give system a moment to release hardware
        delay(300)
        openCamera2(currentLens?.id ?: targetId)
    }


    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {
        val root = _fragmentCameraBinding?.root as? androidx.constraintlayout.widget.ConstraintLayout ?: return

        // Remove all views except viewFinderContainer to avoid duplicates when re-inflating <merge>
        val viewsToRemove = mutableListOf<View>()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.id != R.id.viewFinderContainer) {
                viewsToRemove.add(child)
            }
        }
        viewsToRemove.forEach { root.removeView(it) }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            root
        )

        // Update shutter dot on UI update
        updateShutterOrientation()

        // In the background, load latest photo taken (if any) for gallery thumbnail
        lifecycleScope.launch {
            val context = requireContext()
            val thumbnailUri = mediaStoreUtils.getLatestAppImage()
            if (thumbnailUri != null) {
                updateCurrentThumbnail(thumbnailUri)
                setGalleryThumbnail(thumbnailUri.toString())
            } else {
                updateCurrentThumbnail(null)
                setGalleryThumbnail(null)
            }
            // Warm ImageViewer data cache so first entry is faster.
            kotlin.runCatching {
                imageRepository.getGroupedImages()
            }.onFailure {
                android.util.Log.w(TAG, "Failed to warm image repository cache", it)
            }
        }

        // Apply WindowInsets to UI Container to avoid system bar overlap
        cameraUiContainerBinding?.root?.let { rootView ->
            var currentBottomInset = 0
            var currentToolbarHeight = 0

            fun updateContainerPadding() {
                rootView.updatePadding(
                    bottom = kotlin.math.max(currentBottomInset, currentToolbarHeight)
                )
            }

            val mainActivity = activity as? top.maary.darkbag.MainActivity
            mainActivity?.let {
                viewLifecycleOwner.lifecycleScope.launch {
                    viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                        it.toolbarHeightFlow.collect { height ->
                            currentToolbarHeight = height
                            updateContainerPadding()
                        }
                    }
                }
            }

            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.mandatorySystemGestures()
                )

                view.updatePadding(
                    left = insets.left,
                    top = insets.top,
                    right = insets.right
                )
                currentBottomInset = insets.bottom
                updateContainerPadding()

                // Update Viewfinder and Lens Group constraints
                val uiBinding = cameraUiContainerBinding
                val vfBinding = _fragmentCameraBinding
                if (uiBinding != null && vfBinding != null) {
                    val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
                    val root = vfBinding.root as androidx.constraintlayout.widget.ConstraintLayout
                    constraintSet.clone(root)

                    val containerId = vfBinding.viewFinderContainer.id
                    val topId = uiBinding.topRightControls?.id
                    val bottomId = uiBinding.bottomIslandCard?.id
                    if (topId != null && bottomId != null) {
                        val marginMedium = resources.getDimensionPixelSize(R.dimen.margin_medium)
                        constraintSet.connect(containerId, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START, marginMedium)
                        constraintSet.connect(containerId, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END, marginMedium)

                        constraintSet.constrainHeight(containerId, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT)
                        constraintSet.connect(containerId, androidx.constraintlayout.widget.ConstraintSet.TOP, topId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
                        constraintSet.connect(containerId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, bottomId, androidx.constraintlayout.widget.ConstraintSet.TOP)
                        constraintSet.setVerticalBias(containerId, 0.5f)
                    }

                    constraintSet.applyTo(root)

                    // Ensure clipping
                    vfBinding.viewFinderContainer.clipToOutline = true
                    vfBinding.viewFinderStage.clipToOutline = true
                }

                WindowInsetsCompat.CONSUMED
            }
            // Request insets dispatch to handle race conditions during view creation
            rootView.requestApplyInsets()
        }

        // Touch overlay to dismiss menus
        cameraUiContainerBinding?.touchOverlay?.setOnClickListener {
            dismissFloatingOverlays()
        }

        // Listener for settings button
        cameraUiContainerBinding?.settingsButton?.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToSettings())
        }

        // Flash / Underexposure Toggle Button
        cameraUiContainerBinding?.flashButton?.let { btn ->
            if (isHdrPlusEnabled) {
                updateUnderexposureButton()
            } else {
                updateFlashIcon(btn)
            }
            btn.setOnClickListener {
                if (isHdrPlusEnabled) {
                    cycleUnderexposureMode()
                } else {
                    isFlashEnabled = !isFlashEnabled
                    requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(SettingsFragment.KEY_FLASH_MODE, isFlashEnabled).apply()
                    updateFlashIcon(btn)
                    applyCameraControls()
                }
            }
        }

        // Motion Photo Toggle Button
        cameraUiContainerBinding?.motionPhotoButton?.let { btn ->
            updateMotionPhotoButton()
            btn.setOnClickListener {
                if (isHalfFrameModeEnabled) return@setOnClickListener
                isMotionPhotoEnabled = !isMotionPhotoEnabled
                requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(SettingsFragment.KEY_MOTION_PHOTO, isMotionPhotoEnabled).apply()
                updateMotionPhotoEncoder()
            }
        }

        // Listener for button used to capture photo
        val shutter = cameraUiContainerBinding?.cameraCaptureButton
        shutter?.isLongPressHoldEnabled = true
        shutter?.onLongPressHoldStarted = {
            if (isHalfFrameModeEnabled && halfFrameStep == 1) {
                // Cancel/Reset half-frame
                val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                halfFrameSessionStore.clearCurrentSession(deleteTempFile = true)
                writeScopedHalfFrameStep(prefs, 0)
                updateHalfFrameUI()
            } else if (!isBurstActive && !isHalfFrameModeEnabled && !isMultiCameraModeActive) {
                val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val action = prefs.getString(SettingsFragment.KEY_SHUTTER_LONG_PRESS_ACTION, SettingsFragment.SHUTTER_LONG_PRESS_MP4)
                if (action == SettingsFragment.SHUTTER_LONG_PRESS_RAW_VIDEO || action == SettingsFragment.SHUTTER_LONG_PRESS_MP4) {
                    val hasAudio = androidx.core.content.ContextCompat.checkSelfPermission(
                        requireContext(),
                        android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasAudio) {
                        if (shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
                            showAudioPermissionRationaleDialog {
                                if (action == SettingsFragment.SHUTTER_LONG_PRESS_RAW_VIDEO) {
                                    startRawVideoRecording()
                                } else {
                                    startMp4VideoRecording()
                                }
                            }
                        } else {
                            requestAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        if (action == SettingsFragment.SHUTTER_LONG_PRESS_RAW_VIDEO) {
                            startRawVideoRecording()
                        } else {
                            startMp4VideoRecording()
                        }
                    }
                }
            }
        }
        shutter?.onLongPressHoldReleased = {
            if (rawVideoSessionManager.recording) {
                stopRawVideoRecording()
            } else if (mp4VideoRecorder?.recording == true) {
                stopMp4VideoRecording()
            }
        }

        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {
            if (isBurstActive) return@setOnClickListener

            // Capture snapshot immediately for half-frame animation
            if (isHalfFrameModeEnabled) {
                pendingVfSnapshot = _fragmentCameraBinding?.viewFinder?.bitmap
            }

            // Check concurrency limit
            if (!processingSemaphore.tryAcquire()) {
                Toast.makeText(requireContext(),
                    "Processing queue full, please wait...",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val timing = StandardTimingTracker(shutterClick = System.currentTimeMillis())

            // Early Step Update for Half-frame to allow rapid follow-up
            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val isFrame1Trigger = isHalfFrameModeEnabled && halfFrameStep == 0
            val isFrame2Trigger = isHalfFrameModeEnabled && halfFrameStep == 1

            // Trigger Motion Photo snapshot if enabled and not in half-frame or multi-camera mode
            val motionEnabled = prefs.getBoolean(SettingsFragment.KEY_MOTION_PHOTO, false) && !isHalfFrameModeEnabled && !isMultiCameraModeActive
            if (motionEnabled && motionPhotoEncoder?.isEncoding == true) {
                val deferred = CompletableDeferred<Pair<String?, Long>>()
                pendingMotionPhotoTask = deferred
                val tempMp4 = File(requireContext().cacheDir, "motion_${timing.shutterClick}.mp4")
                val shutterNano = System.nanoTime()
                val currentOrientation = deviceOrientationDegrees
                motionPhotoEncoder?.captureSnapshot(
                    captureTimestampNs = shutterNano,
                    preDurationMs = 1500L,
                    postDurationMs = 750L,
                    outputFile = tempMp4,
                    orientationDegrees = currentOrientation
                ) { file, stillPtsUs ->
                    deferred.complete(Pair(file?.absolutePath, stillPtsUs))
                }
            } else {
                pendingMotionPhotoTask = null
            }

            val hfGroupId = if (isFrame2Trigger) {
                halfFrameSessionStore.readSession().baseName
            } else {
                top.maary.darkbag.utils.ImageUtils.getBaseName(SimpleDateFormat(FILENAME, Locale.US).format(timing.shutterClick))
            }

            var resolvedFlare = -1
            if (isHalfFrameModeEnabled) {
                val flarePref = if (prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_LIGHT_LEAK, false)) 0 else -1
                resolvedFlare = if (flarePref == 0) Random().nextInt(2) + 1 else flarePref
            }

            if (isFrame1Trigger) {
                halfFrameSessionStore.clearCurrentSession(deleteTempFile = false)
                halfFrameSessionStore.setBaseName(hfGroupId)

                // For Frame 1 trigger, we might not have a config yet, but writeScopedHalfFrameStep
                // will be updated after capture with the actual digitalGain in takeSinglePicture/triggerHdrPlusBurst
                writeScopedHalfFrameStep(prefs, 1, timing.shutterClick, flareType = resolvedFlare)
                // Animate slightly faster to sync with blackout fade
                fragmentCameraBinding.viewFinder.postDelayed({
                    updateHalfFrameUI(animate = true)
                }, 50)
                showProcessingAnimation()
            }

            var hfMetadataForTrigger: HalfFrameManager.Metadata? = null
            if (isHalfFrameModeEnabled) {
                val session = halfFrameSessionStore.readSession()

                hfMetadataForTrigger = HalfFrameManager.Metadata(
                    profile = session.profile,
                    dateStamp = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DATE_STAMP, false),
                    captureTimeMillis = timing.shutterClick,
                    frame1BaseName = if (isFrame2Trigger) session.baseName else null,
                    frame1TempPath = if (isFrame2Trigger) session.tempPath else null,
                    frame1CaptureTime = if (isFrame2Trigger) session.captureTimeMillis else 0L,
                    frame1DigitalGain = if (isFrame2Trigger) session.digitalGain else 1.0f,
                    flareType = if (isFrame2Trigger) session.flareType else resolvedFlare
                )
            } else {
                hfMetadataForTrigger = null
            }

            if (isFrame2Trigger) {
                writeScopedHalfFrameStep(prefs, 0)
                // Animate slightly faster to sync with blackout fade
                fragmentCameraBinding.viewFinder.postDelayed({
                    updateHalfFrameUI(animate = true)
                }, 50)

                showProcessingAnimation() // Immediate indicator on click for second frame
                cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE // Show thumbnail container for progress indicator
                setGalleryThumbnail(null) // Clear previous thumbnail and show placeholder/indicator
            }

            if (isMultiCameraModeActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                takeMultiCameraPicture(timing)
            } else {
                if (isHdrPlusEnabled && isRawSupported) {
                    triggerHdrPlusBurstCamera2(isFrame1Trigger, hfMetadataForTrigger)
                } else {
                    takeSinglePictureCamera2(timing, isFrame1Trigger, hfMetadataForTrigger)
                }
            }
        }
        _fragmentCameraBinding?.cameraSwitchButtonAlt?.let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                if (isMultiCameraModeActive) {
                    toggleFrontPipInMultiCameraMode()
                    return@setOnClickListener
                }

                lensFacing = if (CameraCharacteristics.LENS_FACING_FRONT == lensFacing) {
                    CameraCharacteristics.LENS_FACING_BACK
                } else {
                    CameraCharacteristics.LENS_FACING_FRONT
                }

                // Persist choice for session
                val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putInt(KEY_LENS_FACING, lensFacing).apply()

                // Re-bind use cases to update selected camera
                lifecycleScope.launch {
                    withContext(Dispatchers.Default) {
                        refreshLenses(force = true)
                    }
                    initLensControls()
                    bindCameraUseCases()
                }
            }
        }

        // Initialize Manual Controls
        initManualControls()

        updateHdrPlusConstraints()

        // HDR+ Switch
        cameraUiContainerBinding?.hdrPlusSwitch?.let { toggle ->
            toggle.setOnCheckedChangeListener { _, isChecked ->
                isHdrPlusEnabled = isChecked
                requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_HDR_PLUS_ENABLED, isHdrPlusEnabled).apply()
                updateHdrPlusUi()
                updateHdrPlusConstraints()
            }
        }

        // Mode Switch Button (Lens Row)
        _fragmentCameraBinding?.modeSwitchButton?.let { btn ->
            updateModeSwitchIcon(btn)
            btn.setOnClickListener {
                cycleCaptureMode()
                updateModeSwitchIcon(btn)
            }
        }

        // LUT Switcher (Strip)
        cameraUiContainerBinding?.lutSwitcherButton?.setOnClickListener {
            val binding = cameraUiContainerBinding
            if (binding?.proInfoBarCard?.visibility == View.VISIBLE ||
                binding?.manualControlsRoot?.visibility == View.VISIBLE) {
                binding.proInfoBarCard?.visibility = View.GONE
                binding.manualControlsRoot?.visibility = View.GONE
                binding.dialPanel?.visibility = View.GONE
                activeManualTab = null
                updateFocusPeakingState()
                updateProInfoBar()
                updateLensControlRowPosition(animate = true)
            }
            showLutMenu()
        }

        cameraUiContainerBinding?.photoViewButton?.setOnLongClickListener {
            val safeActivity = activity ?: return@setOnLongClickListener false
            val navController = Navigation.findNavController(safeActivity, R.id.fragment_container)
            if (navController.currentDestination?.id == R.id.camera_fragment) {
                navController.navigate(R.id.action_camera_to_playground_gallery)
            }
            true
        }

        // Listener for button used to view the most recent photo
        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            // Only navigate when the gallery has photos
            lifecycleScope.launch {
                val uri = currentThumbnailUri ?: mediaStoreUtils.getLatestAppImage()
                if (uri != null) {
                    val safeContext = context ?: return@launch
                    val prefs = safeContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    val useInternalViewer = prefs.getBoolean(SettingsFragment.KEY_USE_INTERNAL_VIEWER, true)
                    var externalViewerStarted = false

                    if (!useInternalViewer) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "image/*")
                                addCategory(Intent.CATEGORY_DEFAULT)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val externalPackage = prefs.getString(SettingsFragment.KEY_EXTERNAL_VIEWER_PACKAGE, "")
                            if (!externalPackage.isNullOrEmpty()) {
                                intent.setPackage(externalPackage)
                            }
                            startActivity(intent)
                            externalViewerStarted = true
                        } catch (e: Exception) {
                            Toast.makeText(safeContext, R.string.error_no_external_viewer, Toast.LENGTH_SHORT).show()
                        }
                    }

                    if (useInternalViewer || !externalViewerStarted) {
                        val safeActivity = activity ?: return@launch
                        Navigation.findNavController(safeActivity, R.id.fragment_container)
                            .navigate(CameraFragmentDirections.actionCameraToImageViewer(uri.toString()))
                    }
                }
            }
        }

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val activeLutName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
        cameraUiContainerBinding?.lutSwitcherButton?.text = activeLutName?.substringBeforeLast(".") ?: getString(R.string.lut_none)
        updateLiveLut()
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val showSwitch = prefs.getBoolean(SettingsFragment.KEY_SHOW_CAMERA_SWITCH_BUTTON, true)
        try {
            _fragmentCameraBinding?.cameraSwitchButtonAlt?.isEnabled =
                hasBackCamera() && hasFrontCamera()
            _fragmentCameraBinding?.cameraSwitchButtonAlt?.visibility = if (showSwitch) View.VISIBLE else View.GONE
        } catch (exception: Exception) {
            _fragmentCameraBinding?.cameraSwitchButtonAlt?.isEnabled = false
        }
    }

    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera(): Boolean {
        return try {
            camera2Manager.cameraIdList.any { id ->
                val chars = camera2Manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) { false }
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean {
        return try {
            camera2Manager.cameraIdList.any { id ->
                val chars = camera2Manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) { false }
    }

    private suspend fun processImageAsync(context: Context, image: RawImageHolder) =
        withContext(Dispatchers.IO) {
            val timing = image.timing
            timing?.processingStart = System.currentTimeMillis()
            try {
                val contentResolver = context.contentResolver
                val dngName = if (image.halfFrameMetadata != null) {
                    val suffix = if (image.halfFrameMetadata.frame1BaseName != null) "_HF2" else "_HF1"
                    val group = image.halfFrameMetadata.frame1BaseName ?: SimpleDateFormat(FILENAME, Locale.US).format(image.halfFrameMetadata.captureTimeMillis)
                    DarkbagIdentity.prefixedBaseName(group + suffix)
                } else {
                    DarkbagIdentity.prefixedBaseName(SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis()))
                }

                Log.d(
                    TAG,
                    "Processing Image (Standard Halide): Timestamp=${image.timestamp}, ZoomRatio=${image.zoomRatio}, Rotation=${image.combinedOrientation}"
                )

                // 1. Wait for Metadata
                val captureResult = findCaptureResult(image.timestamp)

                if (captureResult == null) {
                    Log.e(
                        TAG,
                        "Timed out waiting for android.hardware.camera2.CaptureResult for timestamp ${image.timestamp}"
                    )
                    return@withContext
                }

                val cameraManager =
                    context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

                val activePhysicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    captureResult.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                } else null

                val targetCharId = activePhysicalId ?: image.physicalId ?: currentLens?.id ?: "0"
                Log.d(TAG, "Fetching characteristics for processing using ID: $targetCharId")
                val chars = cameraManager.getCameraCharacteristics(targetCharId)

                // Metadata Extraction
                var whiteLevel = 1023
                var blackLevelPattern = intArrayOf(64, 64, 64, 64)
                var wb = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)
                var ccm = floatArrayOf(2.0f, -1.0f, 0.0f, -0.5f, 2.0f, -0.5f, 0.0f, -1.0f, 2.0f)
                var cfa = 0

                whiteLevel = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { bl ->
                    blackLevelPattern = intArrayOf(bl.getOffsetForIndex(0, 0), bl.getOffsetForIndex(1, 0), bl.getOffsetForIndex(0, 1), bl.getOffsetForIndex(1, 1))
                }
                chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?.let { cfa = it }
                val activeArrayRect = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val activeArray = if (activeArrayRect != null) {
                    intArrayOf(activeArrayRect.top, activeArrayRect.left, activeArrayRect.bottom, activeArrayRect.right)
                } else null

                captureResult.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)?.let { wbVec ->
                    wb = floatArrayOf(wbVec.red, wbVec.greenEven, wbVec.greenOdd, wbVec.blue)
                }
                captureResult.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { ccmMat ->
                    var idx = 0
                    for(row in 0 until 3) for(col in 0 until 3) ccm[idx++] = ccmMat.getElement(col, row).toFloat()
                }

                var lensShadingMapData: FloatArray? = null
                var lensShadingRows = 0
                var lensShadingCols = 0
                captureResult.get(android.hardware.camera2.CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { lsc ->
                    lensShadingRows = lsc.rowCount
                    lensShadingCols = lsc.columnCount
                    val out = FloatArray(4 * lensShadingRows * lensShadingCols)
                    fun idx(ch: Int, row: Int, col: Int): Int = ch * lensShadingRows * lensShadingCols + row * lensShadingCols + col
                    for (row in 0 until lensShadingRows) {
                        for (col in 0 until lensShadingCols) {
                            out[idx(0, row, col)] = lsc.getGainFactor(0, col, row)
                            out[idx(1, row, col)] = lsc.getGainFactor(1, col, row)
                            out[idx(2, row, col)] = lsc.getGainFactor(2, col, row)
                            out[idx(3, row, col)] = lsc.getGainFactor(3, col, row)
                        }
                    }
                    lensShadingMapData = out
                }

                // 2. Prepare Settings
                val prefs =
                    context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
                val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)

                val activeLutName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
                var nativeLutPath: String? = null
                if (activeLutName != null) {
                    val lutFile = File(File(context.filesDir, "luts"), activeLutName)
                    if (lutFile.exists()) nativeLutPath = lutFile.absolutePath
                }

                val saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true)
                val saveRaw = prefs.getBoolean(SettingsFragment.KEY_SAVE_RAW, true)
                val jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
                val rawFolderUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)

                val fullResJpgFile = File(context.cacheDir, "${dngName}_full.jpg")
                val linearDngFile = File(context.cacheDir, "${dngName}_linear.dng")
                val bayerDngFile = File(context.cacheDir, "${dngName}_bayer.dng")
                var dngWritten = false

                val iso = captureResult.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val exposureTime = captureResult.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
                val fNumber = captureResult.get(android.hardware.camera2.CaptureResult.LENS_APERTURE) ?: 1.8f
                val focalLength = captureResult.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH) ?: 0.0f
                val captureTime = System.currentTimeMillis()

                val debugStats = LongArray(15)
                val mirror = shouldMirror

                val captureMetadata = createCaptureMetadata(
                    iso = iso,
                    exposureTime = exposureTime,
                    fNumber = fNumber,
                    focalLength = focalLength,
                    captureTime = captureTime,
                    targetCharId = targetCharId,
                    isHdrPlus = false,
                    captureResult = captureResult
                )

                // 3. Resolution Binning (Half-Frame Economical Mode or User Resolution Setting)
                val photoResolution = prefs.getString(SettingsFragment.KEY_PHOTO_RESOLUTION, SettingsFragment.RESOLUTION_FULL)
                val isHfDownsample = isHalfFrameModeEnabled && prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DOWNSAMPLE, true)
                val binningFactor = when {
                    isHfDownsample || photoResolution == SettingsFragment.RESOLUTION_BINNING_2X2 -> 2
                    photoResolution == SettingsFragment.RESOLUTION_BINNING_4X4 -> 4
                    else -> 1
                }

                var procBuffer = image.data
                var procWidth = image.width
                var procHeight = image.height

                if (binningFactor == 2 && procWidth >= 4 && procHeight >= 4) {
                    val targetW = procWidth / 2
                    val targetH = procHeight / 2
                    val binnedBuf = java.nio.ByteBuffer.allocateDirect(targetW * targetH * 2)
                    val dup = image.data.duplicate()
                    dup.position(0)
                    val ok = top.maary.darkbag.rawvideo.RawVideoNative.nativeBayerBinning2x2(
                        srcBuffer = dup,
                        srcWidth = procWidth,
                        srcHeight = procHeight,
                        srcRowStrideBytes = procWidth * 2,
                        dstBuffer = binnedBuf,
                        mode = top.maary.darkbag.rawvideo.RawVideoNative.BINNING_MODE_AVERAGE
                    )
                    if (ok) {
                        binnedBuf.rewind()
                        procBuffer = binnedBuf
                        procWidth = targetW
                        procHeight = targetH
                        Log.i(TAG, "Applied RAW Bayer 2x2 Binning: ${image.width}x${image.height} -> ${procWidth}x${procHeight}")
                    }
                } else if (binningFactor == 4 && procWidth >= 8 && procHeight >= 8) {
                    val targetW = procWidth / 4
                    val targetH = procHeight / 4
                    val binnedBuf = java.nio.ByteBuffer.allocateDirect(targetW * targetH * 2)
                    val dup = image.data.duplicate()
                    dup.position(0)
                    val ok = top.maary.darkbag.rawvideo.RawVideoNative.nativeBayerBinning4x4(
                        srcBuffer = dup,
                        srcWidth = procWidth,
                        srcHeight = procHeight,
                        srcRowStrideBytes = procWidth * 2,
                        dstBuffer = binnedBuf,
                        mode = top.maary.darkbag.rawvideo.RawVideoNative.BINNING_MODE_AVERAGE
                    )
                    if (ok) {
                        binnedBuf.rewind()
                        procBuffer = binnedBuf
                        procWidth = targetW
                        procHeight = targetH
                        Log.i(TAG, "Applied RAW Bayer 4x4 Binning: ${image.width}x${image.height} -> ${procWidth}x${procHeight}")
                    }
                }

                val enableDualStreamFusion = prefs.getBoolean(SettingsFragment.KEY_DUAL_STREAM_FUSION, true)

                // Instant Echo Thumbnail & Enqueue to Processing Service
                val instantThumb = generateInstantThumbnail(
                    rawBuffer = procBuffer,
                    width = procWidth,
                    height = procHeight,
                    orientation = image.combinedOrientation,
                    cfaPattern = cfa,
                    whiteLevel = whiteLevel,
                    blackLevel = blackLevelPattern.firstOrNull()?.toFloat() ?: 64f,
                    wb = wb,
                    targetLogIndex = targetLogIndex,
                    lutPath = nativeLutPath,
                    exposure = 0f
                )
                
                val fastOutputUri: android.net.Uri? = null
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.toast_processing_queued), Toast.LENGTH_SHORT).show()
                    if (isHalfFrameModeEnabled && prefs.getInt(scopedHalfFrameStepKey(prefs), 0) == 1) {
                        setGalleryThumbnail(null)
                    } else if (instantThumb != null) {
                        setGalleryThumbnailBitmap(instantThumb)
                    }
                    // 保持加载动画，直到服务处理完毕
                }

                if (saveRaw) {
                    try {
                        val dngThumbnailSource: java.io.File? = null

                        val dngCreator = android.hardware.camera2.DngCreator(chars, captureResult)
                        dngCreator.setDescription(DarkbagIdentity.imageDescription(isHdrPlus = false))
                        captureMetadata.location?.let { dngCreator.setLocation(it) }

                        val dngOrientation = when (image.combinedOrientation) {
                            90 -> ExifInterface.ORIENTATION_ROTATE_90
                            180 -> ExifInterface.ORIENTATION_ROTATE_180
                            270 -> ExifInterface.ORIENTATION_ROTATE_270
                            else -> ExifInterface.ORIENTATION_NORMAL
                        }
                        dngCreator.setOrientation(dngOrientation)
                        dngThumbnailSource?.let { createDngThumbnailBitmap(it) }?.let { thumb ->
                            try {
                                dngCreator.setThumbnail(thumb)
                            } finally {
                                thumb.recycle()
                            }
                        }

                        val dngTargetFile = if (image.halfFrameMetadata != null) {
                            File(context.cacheDir, "${dngName}_orig.dng")
                        } else {
                            bayerDngFile
                        }

                        FileOutputStream(dngTargetFile).use { dngCreator.writeByteBuffer(it, Size(procWidth, procHeight), procBuffer, 0L) }
                        dngWritten = true
                        Log.d(TAG, "RAW saved: ${dngTargetFile.absolutePath} (${dngTargetFile.length()} bytes)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save RAW", e)
                    }
                }

                procBuffer.rewind()

                // 4. Submit to HdrPlusProcessingService
                var motionMp4Path = image.motionPhotoMp4Path
                var motionStillPtsUs = image.motionPhotoStillPtsUs
                if (motionMp4Path == null && pendingMotionPhotoTask != null) {
                    val task = pendingMotionPhotoTask
                    pendingMotionPhotoTask = null
                    val result = withTimeoutOrNull(2500L) { task?.await() }
                    motionMp4Path = result?.first
                    motionStillPtsUs = result?.second ?: 0L
                }

                val request = top.maary.darkbag.processor.HdrPlusRequest(
                    requestId = java.util.UUID.randomUUID().toString(),
                    megaBuffer = procBuffer,
                    numFrames = 1,
                    width = procWidth,
                    height = procHeight,
                    orientation = image.combinedOrientation,
                    whiteLevel = whiteLevel,
                    blackLevelPattern = blackLevelPattern ?: intArrayOf(64,64,64,64),
                    lensShadingMap = lensShadingMapData,
                    lensShadingRows = lensShadingRows,
                    lensShadingCols = lensShadingCols,
                    useSensorColorMatrix = false,
                    whiteBalance = wb,
                    ccm = ccm,
                    ccmAlt = ccm,
                    exportMatrixAB = false,
                    cfaPattern = cfa,
                    targetLogIndex = targetLogIndex,
                    lutPath = nativeLutPath,
                    digitalGain = image.digitalGain,
                    zoomFactor = image.zoomRatio,
                    mirror = mirror,
                    metadata = captureMetadata,
                    isSingleFrame = true,
                    saveJpg = saveJpg,
                    saveRaw = saveRaw,
                    baseName = dngName,
                    fullResJpgPath = fullResJpgFile.absolutePath,
                    linearDngPath = linearDngFile.absolutePath,
                    zslTargetUriStr = fastOutputUri?.toString(),
                    jpgFolderUri = jpgFolderUri,
                    rawFolderUri = rawFolderUri,
                    hfMetadata = image.halfFrameMetadata,
                    editConfig = top.maary.darkbag.models.EditConfig(
                        log = targetLogName ?: "None",
                        lut = activeLutName ?: "None",
                        digitalGain = image.digitalGain,
                        adjustments = if (image.halfFrameMetadata?.profile != null && image.halfFrameMetadata.profile != top.maary.darkbag.utils.HalfFrameSessionStore.PROFILE_NORMAL) {
                            listOf(
                                top.maary.darkbag.models.BasicAdjustments(digitalGain = image.halfFrameMetadata.frame1DigitalGain),
                                top.maary.darkbag.models.BasicAdjustments(digitalGain = image.digitalGain)
                            )
                        } else null,
                        hfLayout = if (image.halfFrameMetadata?.profile == top.maary.darkbag.utils.HalfFrameSessionStore.PROFILE_HALF_TOP) "TB" else if (image.halfFrameMetadata?.profile == top.maary.darkbag.utils.HalfFrameSessionStore.PROFILE_HALF_SIDE) "SBS" else null,
                        showTimestamp = image.halfFrameMetadata?.dateStamp ?: false,
                        zoomFactor = image.zoomRatio
                    ),
                    runAblationTest = false,
                    motionPhotoMp4Path = motionMp4Path,
                    motionPhotoStillPtsUs = motionStillPtsUs,
                    enableMemoryColor = false,
                    colorEngineMode = prefs.getInt(SettingsFragment.KEY_COLOR_ENGINE_MODE, 0),
                    enableDualStreamFusion = enableDualStreamFusion
                )
                top.maary.darkbag.processor.HdrPlusRequestManager.enqueue(request)
                val serviceIntent = android.content.Intent(context, top.maary.darkbag.processor.HdrPlusProcessingService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // 6. Timing Report
                timing?.let { t ->
                    val report = """
                        [Standard Mode Report]
                        Total (to First Output): ${t.firstOutputWritten - t.shutterClick}ms
                        Shutter to Callback: ${t.captureCallback - t.shutterClick}ms
                        Callback to Enqueued: ${t.enqueued - t.captureCallback}ms
                        Wait in Queue: ${t.processingStart - t.enqueued}ms
                        JNI (Halide + FastJPG): ${t.jniDone - t.processingStart}ms
                        DNG Write (DngCreator): ${t.firstOutputWritten - t.jniDone}ms
                        Native Halide Detail: ${debugStats[0]}ms
                    """.trimIndent()
                    Log.i(TAG, report)
                    DebugLogManager.addLog(report)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in background processing", e)
            }
        }

    private fun setupTapToFocus() {
        _fragmentCameraBinding?.viewFinderStage?.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val uiBinding = cameraUiContainerBinding
                if (uiBinding?.lutListContainer?.visibility == View.VISIBLE ||
                    uiBinding?.manualControlsRoot?.visibility == View.VISIBLE ||
                    uiBinding?.proInfoBarCard?.visibility == View.VISIBLE
                ) {
                    dismissFloatingOverlays()
                    return@setOnTouchListener true
                }

                triggerTapToFocusCamera2(event.x, event.y)
                showFocusRing(event.x, event.y)
                view.performClick()
            }
            true
        }
    }

    private fun triggerTapToFocusCamera2(x: Float, y: Float) {
        val device = camera2Device ?: return
        val session = camera2Session ?: return
        val surface = camera2PreviewSurface ?: return
        val handler = camera2Handler ?: return

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val characteristics = camera2Manager.getCameraCharacteristics(device.id)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@launch

                val lastResult = captureResultFlow.replayCache.lastOrNull()
                val cropRegion = lastResult?.get(CaptureResult.SCALER_CROP_REGION) ?: activeArray

                val region = getMeteringRectangle(
                    x, y,
                    _fragmentCameraBinding?.viewFinderStage?.width ?: 0,
                    _fragmentCameraBinding?.viewFinderStage?.height ?: 0,
                    sensorOrientation,
                    lensFacing,
                    cropRegion
                )

                focusMeteringRegion = region
                if (!isManualExposure) {
                    exposureMeteringRegion = region
                }

                // 1. Cancel ongoing
                val cancelRequest = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                cancelRequest.addTarget(surface)
                applyManualSettingsToRequest(cancelRequest)
                cancelRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                cancelRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL)
                session.capture(cancelRequest.build(), null, handler)

                // 2. Trigger AF/AE
                val triggerRequest = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                triggerRequest.addTarget(surface)
                applyManualSettingsToRequest(triggerRequest)

                triggerRequest.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                if (!isManualExposure) {
                    triggerRequest.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }

                // If in manual focus, we need to temporarily switch to AUTO to perform the tap-to-focus
                if (isManualFocus) {
                    triggerRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                }

                triggerRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                if (!isManualExposure) {
                    triggerRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }

                session.capture(triggerRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        super.onCaptureCompleted(session, request, result)
                        if (isManualFocus) {
                             syncManualFocusAfterTap()
                        }
                    }
                }, handler)

                // 3. Resume repeating
                withContext(Dispatchers.Main) {
                    // If we were in manual focus, we don't want to switch to AUTO permanently.
                    // But if we were in continuous AF, we switch to AUTO to keep it locked.
                    if (!isManualFocus) {
                        // focusMeteringRegion is already set, applyCameraControls will use AF_MODE_AUTO
                        applyCameraControls()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera2 tap to focus trigger failed", e)
            }
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val focusRing = _fragmentCameraBinding?.focusRing ?: return
        val size = resources.getDimension(R.dimen.focus_ring_size)

        focusRing.animate().cancel()

        focusRing.translationX = x - size / 2
        focusRing.translationY = y - size / 2
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1.0f

        focusRing.animate()
            .setStartDelay(FOCUS_RING_DISPLAY_TIME_MS)
            .alpha(0.0f)
            .setDuration(FOCUS_RING_FADE_OUT_DURATION_MS)
            .withEndAction { focusRing.visibility = View.GONE }
            .start()
    }

    private fun dismissFloatingOverlays() {
        val binding = cameraUiContainerBinding ?: return

        if (binding.lutListContainer?.visibility == View.VISIBLE) {
            binding.lutListContainer?.visibility = View.GONE
        }

        if (binding.proInfoBarCard?.visibility == View.VISIBLE ||
            binding.manualControlsRoot?.visibility == View.VISIBLE ||
            binding.dialPanel?.visibility == View.VISIBLE ||
            activeManualTab != null
        ) {
            binding.proInfoBarCard?.visibility = View.GONE
            binding.manualControlsRoot?.visibility = View.GONE
            binding.dialPanel?.visibility = View.GONE
            activeManualTab = null
            updateFocusPeakingState()
            updateProInfoBar()
            updateLensControlRowPosition(animate = true)
        }

        binding.touchOverlay?.visibility = View.GONE
    }

    private fun initManualControls() {
        val prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, false)
        val binding = cameraUiContainerBinding ?: return
        binding.btnProMode?.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) {
            binding.proInfoBarCard?.visibility = View.GONE
            binding.manualControlsRoot?.visibility = View.GONE
            return
        }

        // Level 1: Pro Trigger Button (Toggles Level 2 Pills Bar)
        binding.btnProMode?.setOnClickListener {
            if (binding.lutListContainer?.visibility == View.VISIBLE) {
                binding.lutListContainer?.visibility = View.GONE
            }
            val isBarOpen = binding.proInfoBarCard?.visibility == View.VISIBLE
            if (isBarOpen) {
                binding.proInfoBarCard?.visibility = View.GONE
                binding.manualControlsRoot?.visibility = View.GONE
                binding.touchOverlay?.visibility = View.GONE
                activeManualTab = null
                updateFocusPeakingState()
                updateLensControlRowPosition(animate = true)
            } else {
                binding.proInfoBarCard?.visibility = View.VISIBLE
                binding.touchOverlay?.visibility = View.VISIBLE
                updateProInfoBar()
                updateLensControlRowPosition(animate = true)
            }
        }

        // Dismiss touch overlay
        binding.touchOverlay?.setOnClickListener {
            dismissFloatingOverlays()
        }

        isMultiCameraManualLinked = prefs.getBoolean("pref_multi_camera_manual_linked", true)

        // Level 2: Pill Click Listeners (Toggles Level 3 Dial Panel)
        binding.pillFocus?.setOnClickListener { toggleManualTab("Focus") }
        binding.pillIso?.setOnClickListener { toggleManualTab("ISO") }
        binding.pillShutter?.setOnClickListener { toggleManualTab("Shutter") }
        binding.pillEv?.setOnClickListener { toggleManualTab("EV") }

        // Multi-Camera Sync Link Button
        binding.btnProLink?.setOnClickListener { v ->
            isMultiCameraManualLinked = !isMultiCameraManualLinked
            val p = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            p.edit().putBoolean("pref_multi_camera_manual_linked", isMultiCameraManualLinked).apply()
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            val msg = if (isMultiCameraManualLinked) {
                getString(R.string.multi_camera_link_synced)
            } else {
                getString(R.string.multi_camera_link_primary_only)
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            updateProInfoBar()
        }

        // Dial Wheel Item Selection
        binding.dialWheelView?.setOnItemSelectedListener { item, _, fromUser ->
            if (fromUser) {
                handleDialSelection(item)
            }
        }

        // Auto Button
        binding.btnDialAuto?.setOnClickListener {
            resetCurrentManualParameter()
        }

        // Focus Extras
        binding.btnFocusNear?.setOnClickListener {
            currentFocusDistance = minFocusDistance
            isManualFocus = true
            applyCameraControls()
            updateDialPanel()
            updateProInfoBar()
        }

        binding.btnFocusFar?.setOnClickListener {
            currentFocusDistance = 0.0f
            isManualFocus = true
            applyCameraControls()
            updateDialPanel()
            updateProInfoBar()
        }

        updateLensControlRowPosition(animate = false)
        updateProInfoBar()
    }

    private fun updateLensControlRowPosition(animate: Boolean = true) {
        val vfBinding = _fragmentCameraBinding ?: return
        val lensRow = vfBinding.lensControlRow ?: return
        lensRow.translationY = 0f
    }

    private fun toggleManualTab(tab: String) {
        val binding = cameraUiContainerBinding ?: return
        if (activeManualTab == tab) {
            // Collapse dial
            activeManualTab = null
            binding.manualControlsRoot?.visibility = View.GONE
            updateFocusPeakingState()
            updateProInfoBar()
        } else {
            // Open dial
            activeManualTab = tab
            binding.manualControlsRoot?.visibility = View.VISIBLE
            binding.touchOverlay?.visibility = View.VISIBLE
            updateDialPanel()
            updateFocusPeakingState()
            updateProInfoBar()
        }
    }

    private fun updateFocusPeakingState() {
        val context = context ?: return
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val peakingPref = prefs.getBoolean(SettingsFragment.KEY_EXP_FOCUS_PEAKING, false)
        val enabled = peakingPref && (activeManualTab == "Focus" || isManualFocus)
        lutProcessor?.setFocusPeakingEnabled(enabled)
    }

    private fun handleDialSelection(item: top.maary.darkbag.ui.ProDialWheelView.DialItem) {
        val binding = cameraUiContainerBinding ?: return
        when (activeManualTab) {
            "Focus" -> {
                currentFocusDistance = item.rawValue.toFloat()
                isManualFocus = true
                binding.tvDialTargetValue?.text = formatFocusDist(currentFocusDistance)
                updateFocusPeakingState()
            }
            "ISO" -> {
                currentIso = item.rawValue.toInt()
                isManualIso = true
                binding.tvDialTargetValue?.text = "$currentIso"
            }
            "Shutter" -> {
                currentExposureTime = item.rawValue.toLong()
                isManualShutter = true
                binding.tvDialTargetValue?.text = formatShutterTime(currentExposureTime)
            }
            "EV" -> {
                currentEvIndex = item.rawValue.toInt()
                binding.tvDialTargetValue?.text = formatEvVal(currentEvIndex)
            }
        }
        applyCameraControls()
        updateProInfoBar()
    }

    private fun resetCurrentManualParameter() {
        when (activeManualTab) {
            "Focus" -> {
                isManualFocus = false
                focusMeteringRegion = null
                updateFocusPeakingState()
            }
            "ISO" -> {
                isManualIso = false
                exposureMeteringRegion = null
            }
            "Shutter" -> {
                isManualShutter = false
                exposureMeteringRegion = null
            }
            "EV" -> {
                currentEvIndex = 0
                exposureMeteringRegion = null
            }
        }
        applyCameraControls()
        updateDialPanel()
        updateProInfoBar()
        updateLensControlRowPosition(animate = true)
    }

    private fun updateDialPanel() {
        val binding = cameraUiContainerBinding ?: return
        val wheel = binding.dialWheelView ?: return
        binding.focusExtras?.visibility = if (activeManualTab == "Focus") View.VISIBLE else View.GONE

        when (activeManualTab) {
            "Focus" -> {
                binding.tvDialTargetLabel?.text = "FOCUS"
                val items = buildFocusDialItems(minFocusDistance)
                val targetVal = if (isManualFocus) currentFocusDistance else 0.0f
                var closestIndex = 0
                var minDiff = Double.MAX_VALUE
                items.forEachIndexed { i, it ->
                    val diff = abs(it.rawValue - targetVal)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIndex = i
                    }
                }
                wheel.setItems(items, closestIndex)
                binding.tvDialTargetValue?.text = if (isManualFocus) formatFocusDist(currentFocusDistance) else "AUTO (${formatFocusDist(liveFocusDistance)})"
            }
            "ISO" -> {
                binding.tvDialTargetLabel?.text = "ISO"
                val items = buildIsoDialItems(isoRange)
                val targetVal = if (isManualIso) currentIso else liveIso
                var closestIndex = 0
                var minDiff = Double.MAX_VALUE
                items.forEachIndexed { i, it ->
                    val diff = abs(it.rawValue - targetVal)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIndex = i
                    }
                }
                wheel.setItems(items, closestIndex)
                binding.tvDialTargetValue?.text = if (isManualIso) "$currentIso" else "AUTO ($liveIso)"
            }
            "Shutter" -> {
                binding.tvDialTargetLabel?.text = "SHUTTER"
                val items = buildShutterDialItems(exposureTimeRange)
                val targetVal = if (isManualShutter) currentExposureTime else liveExposureTime
                var closestIndex = 0
                var minDiff = Double.MAX_VALUE
                items.forEachIndexed { i, it ->
                    val diff = abs(it.rawValue - targetVal)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIndex = i
                    }
                }
                wheel.setItems(items, closestIndex)
                binding.tvDialTargetValue?.text = if (isManualShutter) formatShutterTime(currentExposureTime) else "AUTO (${formatShutterTime(liveExposureTime)})"
            }
            "EV" -> {
                binding.tvDialTargetLabel?.text = "EV"
                val items = buildEvDialItems(evRange)
                var closestIndex = 0
                var minDiff = Double.MAX_VALUE
                items.forEachIndexed { i, it ->
                    val diff = abs(it.rawValue - currentEvIndex)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIndex = i
                    }
                }
                wheel.setItems(items, closestIndex)
                binding.tvDialTargetValue?.text = formatEvVal(currentEvIndex)
            }
        }
    }

    private fun updateProInfoBar() {
        val binding = cameraUiContainerBinding ?: return
        val primaryColor = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, Color.parseColor("#FFD54F"))
        val onPrimaryColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary, Color.BLACK)
        val onSurfaceColor = Color.WHITE
        val outlineColor = Color.parseColor("#4DFFFFFF")
        val frostedSurfaceColor = Color.parseColor("#731C1B1F")
        val activeContainerColor = androidx.core.graphics.ColorUtils.setAlphaComponent(primaryColor, 90)
        val lockedContainerColor = androidx.core.graphics.ColorUtils.setAlphaComponent(primaryColor, 45)
        val density = resources.displayMetrics.density

        fun applyPillState(
            btn: com.google.android.material.button.MaterialButton?,
            isTabActive: Boolean,
            isValueManual: Boolean,
            textValue: String
        ) {
            if (btn == null) return
            btn.text = textValue
            if (isTabActive) {
                btn.strokeWidth = (1.5f * density).roundToInt()
                btn.strokeColor = ColorStateList.valueOf(primaryColor)
                btn.backgroundTintList = ColorStateList.valueOf(activeContainerColor)
                btn.setTextColor(primaryColor)
            } else if (isValueManual) {
                btn.strokeWidth = (1f * density).roundToInt()
                btn.strokeColor = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(primaryColor, 140))
                btn.backgroundTintList = ColorStateList.valueOf(lockedContainerColor)
                btn.setTextColor(primaryColor)
            } else {
                btn.strokeWidth = (0.5f * density).roundToInt()
                btn.strokeColor = ColorStateList.valueOf(outlineColor)
                btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                btn.setTextColor(onSurfaceColor)
            }
        }

        // Focus
        val focusText = if (isManualFocus) "MF " + formatFocusDist(currentFocusDistance) else "AF"
        applyPillState(binding.pillFocus, activeManualTab == "Focus", isManualFocus, focusText)

        // ISO
        val isoText = if (isManualIso) "ISO $currentIso" else "ISO"
        applyPillState(binding.pillIso, activeManualTab == "ISO", isManualIso, isoText)

        // Shutter
        val shutterText = if (isManualShutter) formatShutterTime(currentExposureTime) else "SEC"
        applyPillState(binding.pillShutter, activeManualTab == "Shutter", isManualShutter, shutterText)

        // EV
        val evText = if (currentEvIndex != 0) formatEvVal(currentEvIndex) else "EV"
        applyPillState(binding.pillEv, activeManualTab == "EV", currentEvIndex != 0, evText)

        // Multi-Camera Link Button (Only visible if multi-camera mode or front PiP is active)
        val isMultiCamActive = isMultiCameraModeActive || isFrontPipActive || (availableLenses.size > 1 && multiCameraManager != null)
        binding.btnProLink?.visibility = if (isMultiCamActive) View.VISIBLE else View.GONE
        if (isMultiCameraManualLinked) {
            binding.btnProLink?.setIconResource(R.drawable.ic_link)
            binding.btnProLink?.iconTint = ColorStateList.valueOf(primaryColor)
            binding.btnProLink?.strokeWidth = (1f * density).roundToInt()
            binding.btnProLink?.strokeColor = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(primaryColor, 140))
            binding.btnProLink?.backgroundTintList = ColorStateList.valueOf(lockedContainerColor)
        } else {
            binding.btnProLink?.setIconResource(R.drawable.ic_link_off)
            binding.btnProLink?.iconTint = ColorStateList.valueOf(onSurfaceColor)
            binding.btnProLink?.strokeWidth = (0.5f * density).roundToInt()
            binding.btnProLink?.strokeColor = ColorStateList.valueOf(outlineColor)
            binding.btnProLink?.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }

        // Update Level 1 Trigger Button State Indicator (PRO / M)
        val isAnyManual = isManualFocus || isManualIso || isManualShutter || currentEvIndex != 0
        binding.btnProMode?.let { btn ->
            if (isAnyManual) {
                btn.text = "M"
                btn.setTextColor(onPrimaryColor)
                btn.strokeWidth = 0
                btn.strokeColor = ColorStateList.valueOf(primaryColor)
                btn.backgroundTintList = ColorStateList.valueOf(primaryColor)
            } else {
                btn.text = "PRO"
                btn.setTextColor(onSurfaceColor)
                btn.strokeWidth = (1f * density).roundToInt()
                btn.strokeColor = ColorStateList.valueOf(outlineColor)
                btn.backgroundTintList = ColorStateList.valueOf(frostedSurfaceColor)
            }
        }

        // Update Level 3 Dial Theme Colors
        binding.dialWheelView?.setThemeColors(primaryColor, onSurfaceColor, Color.parseColor("#B0BEC5"))
        binding.tvDialTargetLabel?.setTextColor(primaryColor)
        binding.tvDialTargetValue?.setTextColor(onSurfaceColor)
    }

    private fun formatShutterTime(nanos: Long): String {
        val ms = nanos / 1_000_000.0
        return if (ms < 950.0) {
            val fraction = 1000.0 / ms
            if (fraction >= 10.0) String.format(Locale.US, "1/%.0fs", fraction)
            else String.format(Locale.US, "1/%.1fs", fraction)
        } else {
            String.format(Locale.US, "%.1fs", ms / 1000.0)
        }
    }

    private fun formatFocusDist(dist: Float): String {
        return if (dist <= 0.001f) "∞"
        else if (minFocusDistance > 0 && dist >= minFocusDistance * 0.95f) "Macro"
        else {
            val meters = 1.0f / dist
            if (meters < 1.0f) String.format(Locale.US, "%.0fcm", meters * 100f)
            else String.format(Locale.US, "%.1fm", meters)
        }
    }

    private fun formatEvVal(evIndex: Int): String {
        return if (evIndex == 0) "EV 0.0"
        else String.format(Locale.US, "EV %+d", evIndex)
    }

    private fun buildShutterDialItems(range: android.util.Range<Long>?): List<top.maary.darkbag.ui.ProDialWheelView.DialItem> {
        val standardNanos = listOf(
            125_000L to "1/8000",
            250_000L to "1/4000",
            500_000L to "1/2000",
            1_000_000L to "1/1000",
            2_000_000L to "1/500",
            3_000_000L to "1/320",
            4_000_000L to "1/250",
            6_000_000L to "1/160",
            8_000_000L to "1/125",
            12_500_000L to "1/80",
            16_666_667L to "1/60",
            25_000_000L to "1/40",
            33_333_333L to "1/30",
            50_000_000L to "1/20",
            66_666_667L to "1/15",
            100_000_000L to "1/10",
            125_000_000L to "1/8",
            250_000_000L to "1/4",
            500_000_000L to "1/2",
            1_000_000_000L to "1s",
            2_000_000_000L to "2s",
            4_000_000_000L to "4s",
            8_000_000_000L to "8s",
            15_000_000_000L to "15s",
            30_000_000_000L to "30s"
        )
        val lower = range?.lower ?: 100_000L
        val upper = range?.upper ?: 30_000_000_000L
        return standardNanos.filter { it.first in lower..upper }.map { (nanos, label) ->
            val isMajor = nanos in listOf(
                125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L,
                8_000_000L, 16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L,
                250_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L,
                4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 30_000_000_000L
            )
            top.maary.darkbag.ui.ProDialWheelView.DialItem(
                id = nanos.toString(),
                label = label,
                rawValue = nanos.toDouble(),
                isMajor = isMajor
            )
        }
    }

    private fun buildIsoDialItems(range: android.util.Range<Int>?): List<top.maary.darkbag.ui.ProDialWheelView.DialItem> {
        val standardIsos = listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
            800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800
        )
        val lower = range?.lower ?: 50
        val upper = range?.upper ?: 6400
        return standardIsos.filter { it in lower..upper }.map { iso ->
            val isMajor = iso in listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
            top.maary.darkbag.ui.ProDialWheelView.DialItem(
                id = iso.toString(),
                label = iso.toString(),
                rawValue = iso.toDouble(),
                isMajor = isMajor
            )
        }
    }

    private fun buildFocusDialItems(maxDiopter: Float): List<top.maary.darkbag.ui.ProDialWheelView.DialItem> {
        if (maxDiopter <= 0f) {
            return listOf(
                top.maary.darkbag.ui.ProDialWheelView.DialItem("inf", "∞", 0.0, isMajor = true)
            )
        }
        val items = mutableListOf<top.maary.darkbag.ui.ProDialWheelView.DialItem>()
        items.add(top.maary.darkbag.ui.ProDialWheelView.DialItem("inf", "∞", 0.0, isMajor = true))

        val stops = listOf(
            0.5f to "2.0m",
            1.0f to "1.0m",
            1.5f to "0.7m",
            2.0f to "0.5m",
            3.0f to "0.33m",
            4.0f to "0.25m",
            5.0f to "0.2m",
            7.0f to "0.14m",
            10.0f to "0.1m"
        )
        for ((d, label) in stops) {
            if (d <= maxDiopter * 0.95f) {
                val isMajor = (d in listOf(0.5f, 1.0f, 2.0f, 5.0f, 10.0f))
                items.add(
                    top.maary.darkbag.ui.ProDialWheelView.DialItem(
                        id = d.toString(),
                        label = label,
                        rawValue = d.toDouble(),
                        isMajor = isMajor
                    )
                )
            }
        }
        items.add(
            top.maary.darkbag.ui.ProDialWheelView.DialItem(
                id = "macro",
                label = "Macro",
                rawValue = maxDiopter.toDouble(),
                isMajor = true
            )
        )
        return items
    }

    private fun buildEvDialItems(range: android.util.Range<Int>?): List<top.maary.darkbag.ui.ProDialWheelView.DialItem> {
        val lower = range?.lower ?: -6
        val upper = range?.upper ?: 6
        val items = mutableListOf<top.maary.darkbag.ui.ProDialWheelView.DialItem>()
        for (i in lower..upper) {
            val label = if (i == 0) "0" else if (i > 0) "+$i" else "$i"
            items.add(
                top.maary.darkbag.ui.ProDialWheelView.DialItem(
                    id = i.toString(),
                    label = label,
                    rawValue = i.toDouble(),
                    isMajor = (i % 3 == 0 || i == 0)
                )
            )
        }
        return items
    }

    private fun initLensControls() {
        val binding = _fragmentCameraBinding ?: return
        val container = binding.lensControlsContainer ?: return
        val row = binding.lensControlRow ?: return

        // Row visibility is managed by applyUIVisibility()
        applyUIVisibility()

        // Always clear container to avoid stale buttons from previous facings
        container.removeAllViews()

        if (availableLenses.isEmpty()) {
            refreshLenses()
        }

        val repoFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_BACK
        else
            CameraCharacteristics.LENS_FACING_FRONT

        val filteredLenses = (if (isMultiCameraModeActive) {
            val physical = availableLenses.filter { it.facing == repoFacing && !it.isLogicalAuto && !it.isZoomPreset }
            if (physical.isNotEmpty()) physical else availableLenses.filter { it.facing == repoFacing && !it.isZoomPreset }
        } else {
            availableLenses.filter { it.facing == repoFacing }.filter {
                !it.isZoomPreset || it.sensorId.contains("virtual-2x")
            }.filter {
                !it.sensorId.contains(CameraRepository.VIRTUAL_TELE_2X_SUFFIX)
            }
        }).distinctBy { it.name }

        // Populate lens controls if any are available
        if (filteredLenses.isNotEmpty()) {
            val isBackCamera = lensFacing == CameraCharacteristics.LENS_FACING_BACK
            val largestTele = if (isBackCamera) {
                filteredLenses.filter { it.multiplier > 1.05f && !it.isZoomPreset }.maxByOrNull { it.multiplier }
            } else null

            for (lens in filteredLenses) {
                val btn = com.google.android.material.button.MaterialButton(
                    requireContext()
                ).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        resources.getDimensionPixelSize(R.dimen.lens_button_size),
                        resources.getDimensionPixelSize(R.dimen.lens_button_size)
                    ).apply {
                        marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_small)
                    }
                    text = lens.name
                    tag = lens
                    textSize = 10f
                    setPadding(0, 0, 0, 0)
                    insetTop = 0
                    insetBottom = 0
                    cornerRadius = resources.getDimensionPixelSize(R.dimen.radius_full)

                    setOnClickListener {
                        if (isMultiCameraModeActive) {
                            currentLens = lens
                            updateLensUI()
                            multiCameraManager?.switchPrimaryPreviewLensByMultiplier(lens.multiplier)
                            return@setOnClickListener
                        }

                        val oldLens = currentLens
                        val is1x = lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset
                        val presets1xForCheck = if (is1x) cameraRepository.get1xPresets(lens) else emptyList()
                        val isAlreadyIn1xPresets = oldLens != null && oldLens.id == lens.id &&
                                presets1xForCheck.any { it.name == oldLens.name }

                        val isLargestTele = largestTele != null && lens.sensorId == largestTele.sensorId
                        val isAlreadyInTelePresets = oldLens != null && (oldLens.sensorId == lens.sensorId || oldLens.sensorId == "${lens.sensorId}${CameraRepository.VIRTUAL_TELE_2X_SUFFIX}")

                        if (is1x && isAlreadyIn1xPresets) {
                            val presets1x = presets1xForCheck
                            val currentName = oldLens.name
                            val currentIndex = presets1x.indexOfFirst { it.name == currentName }
                            val nextIndex = if (currentIndex != -1 && currentIndex < presets1x.size - 1) {
                                currentIndex + 1
                            } else {
                                0
                            }
                            currentLens = presets1x[nextIndex]

                            transientLensLabel = currentLens!!.name
                            lifecycleScope.launch(Dispatchers.Main) {
                                delay(800)
                                if (transientLensLabel == presets1x[nextIndex].name) {
                                    transientLensLabel = null
                                    updateLensUI()
                                }
                            }
                        } else if (isLargestTele && isAlreadyInTelePresets) {
                             val telePresets = cameraRepository.getTelePresets(lens)
                             val isCurrentlyNative = oldLens?.sensorId == lens.sensorId
                             currentLens = if (isCurrentlyNative) telePresets[1] else telePresets[0]
                        } else if (is1x) {
                            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                            val default1xFocal = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, null)
                            val presets1x = cameraRepository.get1xPresets(lens)
                            currentLens = presets1x.find { it.name == default1xFocal } ?: presets1x.firstOrNull() ?: lens
                        } else {
                            currentLens = lens
                        }

                        updateLensUI()

                        if (oldLens?.id != currentLens?.id || oldLens?.physicalId != currentLens?.physicalId) {
                            animateSwitch {
                                bindCameraUseCases()
                            }
                        } else {
                            updateZoom(true)
                        }
                    }
                }
                container.addView(btn)
            }
        }
        updateLensUI()
        applyUIVisibility()
    }

    private fun updateLensUI() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val showLensControls = prefs.getBoolean(SettingsFragment.KEY_SHOW_LENS_CONTROLS, true)
        currentLens?.let {
            prefs.edit().putString(KEY_SELECTED_LENS_ID, it.sensorId).apply()
        }

        val binding = _fragmentCameraBinding ?: return

        // Ensure the row containing the switch button is always visible
        binding.lensControlRow?.visibility = View.VISIBLE

        val container = binding.lensControlsContainer ?: return

        val colorPrimary = MaterialColors.getColor(container, android.R.attr.colorPrimary)
        val colorOnSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)

        val isBackCamera = lensFacing == CameraCharacteristics.LENS_FACING_BACK
        val largestTele = if (isBackCamera) {
            availableLenses.filter { it.multiplier > 1.05f && !it.isZoomPreset }.maxByOrNull { it.multiplier }
        } else null

        for (i in 0 until container.childCount) {
            val btn = container.getChildAt(i) as? com.google.android.material.button.MaterialButton
            val lens = btn?.tag as? LensInfo
            if (btn != null && lens != null) {
                val isActive = lens.sensorId == currentLens?.sensorId ||
                              (lens.multiplier in 0.95f..1.05f && currentLens?.id == lens.id && !currentLens!!.sensorId.contains("virtual")) ||
                              (largestTele != null && lens.sensorId == largestTele.sensorId && currentLens?.sensorId == "${largestTele.sensorId}${CameraRepository.VIRTUAL_TELE_2X_SUFFIX}")

                if (isActive) {
                    if (lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset) {
                        btn.text = transientLensLabel ?: String.format("%.1fx", currentLens?.multiplier ?: 1.0f)
                    } else if (largestTele != null && lens.sensorId == largestTele.sensorId) {
                        btn.text = String.format("%.1fx", currentLens?.multiplier ?: lens.multiplier)
                    }

                    val activeColor = if (currentLens?.isZoomPreset == true && (currentLens?.targetZoomRatio ?: 1.0f) > 1.0f) {
                        MaterialColors.getColor(btn, com.google.android.material.R.attr.colorTertiary)
                    } else {
                        colorPrimary
                    }
                    btn.setTextColor(activeColor)
                    btn.strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke_small)
                    btn.strokeColor = android.content.res.ColorStateList.valueOf(activeColor)
                    btn.setBackgroundColor(MaterialColors.layer(
                        MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSurface),
                        activeColor,
                        0.1f
                    ))
                } else {
                    btn.setTextColor(colorOnSurface)
                    btn.strokeWidth = 0

                    if (lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset) {
                        val default1xFocal = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, null)
                        val presets1x = cameraRepository.get1xPresets(lens)
                        val defaultPreset = presets1x.find { it.name == default1xFocal } ?: presets1x.firstOrNull() ?: lens
                        btn.text = String.format("%.1fx", defaultPreset.multiplier)
                    } else {
                        btn.text = lens.name
                    }

                    btn.setBackgroundColor(MaterialColors.layer(
                        MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSurface),
                        colorOnSurface,
                        0.15f
                    ))
                }
            }
        }

        binding.lensControlsCard?.visibility = if (container.childCount > 1 && showLensControls) View.VISIBLE else View.GONE
    }

    private fun updateZoom(animate: Boolean) {
        updateZoomCamera2()
        updateLensUI()
    }

    private fun handleCaptureResult(result: android.hardware.camera2.TotalCaptureResult) {
        val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
        if (timestamp != null) {
            captureResults[timestamp] = result
        }
        val resIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
        val resTime = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
        val resFocus = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
        if (resIso != null) liveIso = resIso
        if (resTime != null) liveExposureTime = resTime
        if (resFocus != null) liveFocusDistance = resFocus
        captureResultFlow.tryEmit(result)
    }

    private fun updateZoomCamera2() {
        val session = camera2Session ?: return
        val device = camera2Device ?: return
        val chars = camera2Manager.getCameraCharacteristics(device.id)
        val activeArray = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val targetRatio = if (currentLens?.isZoomPreset == true && currentLens?.targetZoomRatio != null) {
            currentLens!!.targetZoomRatio!!
        } else {
            1.0f
        }

        val cropW = (activeArray.width() / targetRatio).toInt()
        val cropH = (activeArray.height() / targetRatio).toInt()
        val centerX = activeArray.centerX()
        val centerY = activeArray.centerY()

        val cropRegion = android.graphics.Rect(
            centerX - cropW / 2,
            centerY - cropH / 2,
            centerX + cropW / 2,
            centerY + cropH / 2
        )

        try {
            val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
            request.addTarget(camera2PreviewSurface!!)
            applyManualSettingsToRequest(request)
            request.set(android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION, cropRegion)

            session.setRepeatingRequest(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    handleCaptureResult(result)
                }
            }, camera2Handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Camera2 zoom", e)
        }
    }

    private fun animateSwitch(onMidPoint: () -> Unit) {
        val switchDuration = 200L
        fragmentCameraBinding.viewFinder.animate()
            .alpha(0f)
            .setDuration(switchDuration)
            .withEndAction {
                lifecycleScope.launch(Dispatchers.Main) {
                    onMidPoint()
                    delay(100)
                    fragmentCameraBinding.viewFinder.animate()
                        .alpha(1f)
                        .setDuration(switchDuration)
                        .start()
                }
            }
            .start()
    }

    private fun createCaptureMetadataFromTimestamp(timestamp: Long): CaptureMetadata {
        val result = captureResults[timestamp]
        val captureTime = System.currentTimeMillis()
        return createCaptureMetadata(
            iso = result?.get(CaptureResult.SENSOR_SENSITIVITY),
            exposureTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            fNumber = result?.get(CaptureResult.LENS_APERTURE),
            focalLength = result?.get(CaptureResult.LENS_FOCAL_LENGTH),
            captureTime = captureTime,
            targetCharId = currentLens?.id,
            isHdrPlus = isHdrPlusEnabled,
            captureResult = result
        )
    }

    private fun getCombinedOrientation(): Int {
        val sensorOrientation = try {
            val lens = currentLens
            val targetId = lens?.id ?: if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) "0" else "1"

            camera2Manager.getCameraCharacteristics(targetId)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (e: Exception) { 0 }

        val effectiveDegrees = if (isHalfFrameModeEnabled) {
            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val layout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS)
            if (layout == SettingsFragment.HALF_FRAME_LAYOUT_TB) 270 else 0
        } else {
            deviceOrientationDegrees
        }

        val combined = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - effectiveDegrees + 360) % 360
        } else {
            (sensorOrientation + effectiveDegrees) % 360
        }
        Log.d(TAG, "getCombinedOrientation: sensor=$sensorOrientation, effective=$effectiveDegrees, facing=$lensFacing -> combined=$combined")
        return combined
    }

    private fun applyCameraControls(isHdrBurst: Boolean = false) {
        updateCamera2RepeatingRequest(isHdrBurst)
    }

    private fun updateCamera2RepeatingRequest(isHdrBurst: Boolean = false) {
        val session = camera2Session ?: return
        val device = camera2Device ?: return
        val surface = camera2PreviewSurface ?: return
        val handler = camera2Handler ?: return

        try {
            val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
            request.addTarget(surface)
            applyManualSettingsToRequest(request, isHdrBurst)

            session.setRepeatingRequest(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    handleCaptureResult(result)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Camera2 repeating request", e)
        }
    }

    private fun refreshLutList() {
        val binding = cameraUiContainerBinding ?: return
        val rv = binding.lutList ?: return

        if (rv.layoutManager == null) {
            rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        }

        val luts = lutManager.getLuts()
        lutAdapter = LutPreviewAdapter(luts)
        rv.adapter = lutAdapter
    }

    private inner class LutPreviewAdapter(val luts: List<File>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<LutPreviewAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val text: android.widget.TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            view.setBackgroundColor(Color.TRANSPARENT)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val prefs = requireContext().getSharedPreferences(
                SettingsFragment.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val currentName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)

            val colorOnSurface = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurface)
            val colorPrimary = MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)

            holder.text.setTextColor(colorOnSurface)
            holder.text.textSize = 12f
            holder.text.setPadding(10, 10, 10, 10)

            if (position == 0) {
                holder.text.text = "None"
                if (currentName == null) holder.text.setTextColor(colorPrimary)
                holder.itemView.setOnClickListener {
                    prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                    updateLiveLut()

                    val oldPosition = if (currentName != null) luts.indexOfFirst { it.name == currentName } + 1 else 0
                    notifyItemChanged(oldPosition)
                    notifyItemChanged(0)

                    cameraUiContainerBinding?.lutListContainer?.visibility = View.GONE
                    cameraUiContainerBinding?.touchOverlay?.visibility = View.GONE
                }
            } else {
                val file = luts[position - 1]
                holder.text.text = file.nameWithoutExtension
                if (currentName == file.name) holder.text.setTextColor(colorPrimary)
                holder.itemView.setOnClickListener {
                    prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, file.name).apply()
                    updateLiveLut()

                    val oldPosition = if (currentName != null) luts.indexOfFirst { it.name == currentName } + 1 else 0
                    notifyItemChanged(oldPosition)
                    notifyItemChanged(position)

                    cameraUiContainerBinding?.lutListContainer?.visibility = View.GONE
                    cameraUiContainerBinding?.touchOverlay?.visibility = View.GONE
                }
            }
        }

        override fun getItemCount() = luts.size + 1
    }

    private fun updateFlashIcon(btn: MaterialButton) {
        btn.text = null
        btn.setIconResource(if (isFlashEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    private fun updateUnderexposureButton() {
        val btn = cameraUiContainerBinding?.flashButton ?: return
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"

        btn.text = null
        val iconRes = when {
            mode.contains("Dynamic") -> R.drawable.ic_hdr_dynamic
            mode == "Off" -> R.drawable.ic_exposure_off
            mode == "-1 EV" -> R.drawable.ic_exposure_neg_1
            mode == "-2 EV" -> R.drawable.ic_exposure_neg_2
            else -> R.drawable.ic_hdr_dynamic
        }
        btn.setIconResource(iconRes)
    }

    private fun cycleUnderexposureMode() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val currentMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"
        val modes = SettingsFragment.HDR_UNDEREXPOSURE_MODES
        val currentIndex = modes.indexOf(currentMode)
        val nextIndex = (currentIndex + 1) % modes.size
        val nextMode = modes[nextIndex]

        prefs.edit().putString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, nextMode).apply()
        updateUnderexposureButton()

        // Update lastHdrPlusConfig to reflect new mode immediately for the next shot
        lastHdrPlusConfig = null

    }

    private fun updateLiveLut() {
        val proc = lutProcessor ?: return
        val prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        val activeLutName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
        val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
        val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)
        val isPreviewEnabled = prefs.getBoolean(SettingsFragment.KEY_ENABLE_LUT_PREVIEW, true)

        val displayLut = activeLutName?.substringBeforeLast(".") ?: getString(R.string.lut_none)
        cameraUiContainerBinding?.lutSwitcherButton?.text = displayLut

        activeLutJob?.cancel()
        activeLutJob = lifecycleScope.launch(Dispatchers.IO) {
            var lutData: FloatArray? = null
            var size = 0
            if (isPreviewEnabled && activeLutName != null) {
                val file = File(lutManager.lutDir, activeLutName)
                if (file.exists()) {
                    lutData = ColorProcessor.loadLutData(file.absolutePath)
                    if (lutData != null) {
                        size =
                            Math.round(Math.pow((lutData.size / 3).toDouble(), 1.0 / 3.0)).toInt()
                    }
                }
            }
            if (isActive) {
                val engineMode = prefs.getInt(SettingsFragment.KEY_COLOR_ENGINE_MODE, 0)
                proc.updateColorEngineMode(engineMode)
                proc.updateLut(lutData, size, targetLogIndex)
            }
        }
    }

    private fun showLutMenu() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val currentLog = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None") ?: "None"
        val currentLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, "None")?.substringBeforeLast(".") ?: "None"

        val items = mutableListOf<Pair<String, Boolean>>()
        items.add("${getString(R.string.lut_menu_log)}: $currentLog" to true)
        if (currentLog == "None") {
            items.add(getString(R.string.lut_menu_select_log_first) to false)
        } else {
            items.add("${getString(R.string.lut_menu_lut)}: $currentLut" to true)
        }

        showPillPopup(items, autoDismiss = false) { item, _ ->
            if (item.startsWith(getString(R.string.lut_menu_log))) {
                showLogSelectionMenu()
            } else {
                showLutSelectionMenu()
            }
        }
    }

    private fun showLogSelectionMenu() {
        val items = mutableListOf("← Back" to true)
        SettingsFragment.LOG_CURVES.forEach { log ->
            items.add(log to true)
        }

        showPillPopup(items, autoDismiss = false) { selectedLog, position ->
            if (position == 0) {
                showLutMenu()
            } else {
                val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(SettingsFragment.KEY_TARGET_LOG, selectedLog).apply()
                if (selectedLog == getString(R.string.lut_none)) {
                    prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                }
                updateLiveLut()
                showLutMenu()
            }
        }
    }

    private fun showLutSelectionMenu() {
        val luts = lutManager.getLuts()
        val items = mutableListOf("← Back" to true, getString(R.string.lut_none) to true)
        luts.forEach { file ->
            items.add(file.nameWithoutExtension to true)
        }

        showPillPopup(items, autoDismiss = false) { selectedName, position ->
            if (position == 0) {
                showLutMenu()
            } else {
                val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                if (position == 1) {
                    prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                } else {
                    val filename = luts[position - 2].name
                    prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, filename).apply()
                }
                updateLiveLut()
                showLutMenu()
            }
        }
    }

    private fun showPillPopup(items: List<Pair<String, Boolean>>, autoDismiss: Boolean = true, onSelected: (String, Int) -> Unit) {
        val binding = cameraUiContainerBinding ?: return
        val container = binding.lutListContainer ?: return
        val rv = binding.lutList ?: return

        binding.touchOverlay?.bringToFront()
        binding.touchOverlay?.visibility = View.VISIBLE
        container.bringToFront()

        val colorSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        container.setCardBackgroundColor(colorSurface)
        container.cardElevation = 8f
        container.layoutParams.width = resources.getDimensionPixelSize(R.dimen.round_button_large) * 3
        container.visibility = View.VISIBLE

        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rv.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class PillViewHolder(val btn: com.google.android.material.button.MaterialButton) :
                androidx.recyclerview.widget.RecyclerView.ViewHolder(btn)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val btn = LayoutInflater.from(parent.context).inflate(R.layout.item_popup_pill, parent, false) as com.google.android.material.button.MaterialButton
                return PillViewHolder(btn)
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val pillHolder = holder as PillViewHolder
                val (title, isEnabled) = items[position]
                pillHolder.btn.text = title
                pillHolder.btn.isEnabled = isEnabled
                pillHolder.btn.alpha = if (isEnabled) 1.0f else 0.5f
                pillHolder.btn.setOnClickListener {
                    onSelected(title, position)
                    if (autoDismiss) {
                        dismissFloatingOverlays()
                    }
                }
            }

            override fun getItemCount() = items.size
        }
    }

    companion object {
        private const val TAG = "Darkbag"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val FOCUS_RING_DISPLAY_TIME_MS = 500L
        private const val FOCUS_RING_FADE_OUT_DURATION_MS = 300L
        private const val ANALYSIS_HIGHLIGHT_THRESHOLD = 240
        private const val ANALYSIS_SAMPLING_STEP = 4

        const val KEY_SELECTED_LENS_ID = "selected_lens_sensor_id"
        const val KEY_LENS_FACING = "lens_facing"
        const val KEY_HDR_PLUS_ENABLED = "hdr_plus_enabled"
    }

    private fun saveJpegFallback(
        data: ByteArray,
        rotationDegrees: Int,
        zoomFactor: Float,
        halfFrameMetadata: HalfFrameManager.Metadata? = null,
        captureMetadata: CaptureMetadata? = null
    ) {
        val appContext = requireContext().applicationContext
        val mirror = shouldMirror

        (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
            try {
                val name = SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())
                val bmpFile = File(appContext.cacheDir, "temp_$name.jpg")
                FileOutputStream(bmpFile).use { it.write(data) }

                val jpgFolderUri = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)

                var motionMp4Path: String? = null
                var motionStillPtsUs: Long = 0L
                if (pendingMotionPhotoTask != null) {
                    val task = pendingMotionPhotoTask
                    pendingMotionPhotoTask = null
                    val result = withTimeoutOrNull(2500L) { task?.await() }
                    motionMp4Path = result?.first
                    motionStillPtsUs = result?.second ?: 0L
                }

                val uri = ImageSaver.saveProcessedImage(
                    context = appContext,
                    inputBitmap = null,
                    bmpPath = bmpFile.absolutePath,
                    rotationDegrees = rotationDegrees,
                    zoomFactor = zoomFactor,
                    baseName = name,
                    linearDngPath = null,
                    saveJpg = true,
                    jpgFolderUri = jpgFolderUri,
                    mirror = mirror,
                    halfFrameMetadata = halfFrameMetadata,
                    captureMetadata = captureMetadata,
                    motionPhotoMp4Path = motionMp4Path,
                    motionPhotoStillPtsUs = motionStillPtsUs
                )
                withContext(Dispatchers.Main) {
                    val uiPrefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    if (uri != null) {
                        imageRepository.invalidateCache()
                        uiPrefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, uri.toString()).apply()
                        setGalleryThumbnail(uri.toString())
                    } else if (isHalfFrameModeEnabled && uiPrefs.getInt(scopedHalfFrameStepKey(uiPrefs), 0) == 1) {
                        setGalleryThumbnail(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save JPEG fallback", e)
            } finally {
                processingSemaphore.release()
                withContext(Dispatchers.Main) {
                    cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                    if (!isHalfFrameModeEnabled) {
                        hideProcessingAnimation()
                    }
                }
            }
        }
    }

    private fun triggerAutoBurst(prefs: SharedPreferences) {
        val autoBurst = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_AUTO_BURST, false)
        if (autoBurst) {
            lifecycleScope.launch(Dispatchers.Main) {
                delay(800) // Keep standard interval
                cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
            }
        }
    }


    private suspend fun findCaptureResult(timestamp: Long, tolerance: Long = 5_000_000L): TotalCaptureResult? {
        synchronized(captureResults) {
            captureResults.entries.firstOrNull { abs(it.key - timestamp) < tolerance }?.value?.let { return it }
        }

        return withTimeoutOrNull(3000) {
            captureResultFlow.first { res ->
                val ts = res.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                ts != null && abs(ts - timestamp) < tolerance
            }
        }
    }

    private fun processHdrPlusBurst(
        burstResult: BurstResult,
        digitalGain: Float,
        hfMetadata: HalfFrameManager.Metadata? = null
    ) {
        val currentZoom = if (currentLens?.isZoomPreset == true && currentLens?.targetZoomRatio != null) {
            currentLens!!.targetZoomRatio!!
        } else {
            1.0f
        }
        val combinedOrientation = getCombinedOrientation()
        val startTime = burstStartTime
        val captureEndTime = System.currentTimeMillis()
        val appContext = context?.applicationContext ?: return

        (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
            var fallbackSent = false
            var isHdrPlusSuccess = false
            var megaBufferReleased = false
            try {
                val context = appContext
                val frames = burstResult.frames
                val megaBuffer = burstResult.megaBuffer
                Log.d(TAG, "processHdrPlusBurst started with ${frames.size} frames. DigitalGain=$digitalGain")

                val width = frames[0].width
                val height = frames[0].height
                val rotationDegrees = frames[0].rotationDegrees

                val timestamp = frames[0].timestamp
                val result = findCaptureResult(timestamp)

                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val activePhysicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && result != null) {
                    result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                } else null

                val targetCharId = activePhysicalId ?: frames[0].physicalId ?: currentLens?.id ?: "0"
                Log.d(TAG, "Fetching HDR+ characteristics for processing using ID: $targetCharId")
                val chars = cameraManager.getCameraCharacteristics(targetCharId)

                var whiteLevel = 1023
                var blackLevelPattern = intArrayOf(64, 64, 64, 64)
                var wb = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)
                var ccmMain = floatArrayOf(
                    2.0f, -1.0f, 0.0f,
                    -0.5f, 2.0f, -0.5f,
                    0.0f, -1.0f, 2.0f
                )
                var cfa = 0
                var ccmSensor = ccmMain.copyOf()
                var ccmCapture = ccmMain.copyOf()
                var lensShadingMapData: FloatArray? = null
                var lensShadingRows = 0
                var lensShadingCols = 0
                val useSensorColorMatrix = false

                whiteLevel = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                val bl = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                if (bl != null) {
                    blackLevelPattern = intArrayOf(
                        bl.getOffsetForIndex(0, 0),
                        bl.getOffsetForIndex(1, 0),
                        bl.getOffsetForIndex(0, 1),
                        bl.getOffsetForIndex(1, 1)
                    )
                }

                val cfaEnum = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                if (cfaEnum != null) cfa = cfaEnum

                result?.let { r ->
                    val wbVec = r.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)
                    if (wbVec != null) {
                        wb[0] = wbVec.red
                        wb[1] = wbVec.greenEven
                        wb[2] = wbVec.greenOdd
                        wb[3] = wbVec.blue
                    }

                    val ccmMat = r.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_TRANSFORM)
                    if (ccmMat != null) {
                        var idx = 0
                        for(row in 0 until 3) {
                            for(col in 0 until 3) {
                                val rat = ccmMat.getElement(col, row)
                                ccmCapture[idx++] = rat.toFloat()
                            }
                        }
                    }

                    if (useSensorColorMatrix) {
                        val sensorMat = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
                        if (sensorMat != null) {
                            var idx = 0
                            for (row in 0 until 3) {
                                for (col in 0 until 3) {
                                    val rat = sensorMat.getElement(col, row)
                                    ccmSensor[idx++] = rat.toFloat()
                                }
                            }
                        }
                    }

                    val lsc = r.get(android.hardware.camera2.CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
                    if (lsc != null) {
                        lensShadingRows = lsc.rowCount
                        lensShadingCols = lsc.columnCount
                        val out = FloatArray(4 * lensShadingRows * lensShadingCols)
                        fun idx(ch: Int, row: Int, col: Int): Int = ch * lensShadingRows * lensShadingCols + row * lensShadingCols + col
                        for (row in 0 until lensShadingRows) {
                            for (col in 0 until lensShadingCols) {
                                out[idx(0, row, col)] = lsc.getGainFactor(0, col, row)
                                out[idx(1, row, col)] = lsc.getGainFactor(1, col, row)
                                out[idx(2, row, col)] = lsc.getGainFactor(2, col, row)
                                out[idx(3, row, col)] = lsc.getGainFactor(3, col, row)
                            }
                        }
                        lensShadingMapData = out
                    }
                }

                
                val ccm = if (useSensorColorMatrix) ccmSensor else ccmCapture
                val ccmAlt = if (useSensorColorMatrix) ccmCapture else ccmSensor
                val exportMatrixAB = false
                val activeArrayRect = chars?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val activeArray = if (activeArrayRect != null) {
                    intArrayOf(activeArrayRect.top, activeArrayRect.left, activeArrayRect.bottom, activeArrayRect.right)
                } else null
Log.d(TAG, "Metadata: WL=$whiteLevel, BL=${blackLevelPattern.joinToString()}, WB=${wb.joinToString()}, CFA=$cfa, LSC=${lensShadingRows}x${lensShadingCols}, useSensorCCM=$useSensorColorMatrix")

                val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
                val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)
                val activeLutName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)

                var nativeLutPath: String? = null
                if (activeLutName != null) {
                    val lutFile = File(File(context.filesDir, "luts"), activeLutName)
                    if (lutFile.exists()) nativeLutPath = lutFile.absolutePath
                }

                val iso = result?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val exposureTime = result?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
                val fNumber = result?.get(android.hardware.camera2.CaptureResult.LENS_APERTURE) ?: 1.8f
                val focalLength = result?.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH) ?: 0.0f
                val captureTime = System.currentTimeMillis()

                val captureMetadata = createCaptureMetadata(
                    iso = iso.toInt(),
                    exposureTime = exposureTime,
                    fNumber = fNumber,
                    focalLength = focalLength,
                    captureTime = captureTime,
                    targetCharId = targetCharId,
                    isHdrPlus = true,
                    captureResult = result
                )

                val dngName = if (hfMetadata != null) {
                    val suffix = if (hfMetadata.frame1BaseName != null) "_HF2" else "_HF1"
                    val group = hfMetadata.frame1BaseName ?: SimpleDateFormat(FILENAME, Locale.US).format(hfMetadata.captureTimeMillis)
                    DarkbagIdentity.prefixedBaseName(group + suffix + "_HDRPLUS")
                } else {
                    DarkbagIdentity.prefixedBaseName(SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis()) + "_HDRPLUS")
                }
                val saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true)
                val saveRaw = prefs.getBoolean(SettingsFragment.KEY_SAVE_RAW, true)
                val jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
                val rawFolderUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)

                val fullResJpgFile = File(context.cacheDir, "${dngName}_full.jpg")

                val linearDngFile = File(context.cacheDir, "${dngName}_linear.dng")
                val linearDngPath = linearDngFile.absolutePath

                    Log.d(TAG, "Output Paths: DNG=$linearDngPath")

                val jniStartTime = System.currentTimeMillis()
                megaBuffer.rewind()

                val debugStats = LongArray(15)

                // Initial JNI call produces:
                // 1) intermediate linear RAW buffer (tempRawPath) for the ExportWorker,
                // 2) optional fast downsampled JPEG (tempJpgPath) for immediate gallery update.
                // REFACTORED: REMOVED synchronous front-end JNI.
                val mirror = shouldMirror
                isHdrPlusSuccess = true
                val fastJpegUri: android.net.Uri? = null

                    var motionMp4Path: String? = null
                    var motionStillPtsUs: Long = 0L
                    if (pendingMotionPhotoTask != null) {
                        val task = pendingMotionPhotoTask
                        pendingMotionPhotoTask = null
                        val result = withTimeoutOrNull(2500L) { task?.await() }
                        motionMp4Path = result?.first
                        motionStillPtsUs = result?.second ?: 0L
                    }

                    // Evaluate burst sharpness and select anchor frame, rejecting blurred frames
                    var finalNumFrames = burstResult.frames.size
                    var anchorFrameIndex = 0
                    var acceptedIndices: List<Int> = (0 until burstResult.frames.size).toList()
                    if (burstResult.frames.size > 1) {
                        try {
                            val evalResult = top.maary.darkbag.rawvideo.RawVideoNative.nativeEvaluateBurst(
                                megaBuffer = megaBuffer,
                                numFrames = burstResult.frames.size,
                                width = width,
                                height = height,
                                rowStride = width * 2,
                                cfaPattern = cfa,
                                iso = iso ?: 100,
                                triggerIndex = 0,
                                rejectionThreshold = 0.45f
                            )
                            if (evalResult != null && evalResult.size >= 2) {
                                anchorFrameIndex = evalResult[0]
                                val acceptedCount = evalResult[1].coerceIn(1, burstResult.frames.size)
                                val list = mutableListOf<Int>()
                                for (i in 0 until acceptedCount) {
                                    if (2 + i < evalResult.size) {
                                        list.add(evalResult[2 + i])
                                    }
                                }
                                if (list.isNotEmpty()) {
                                    acceptedIndices = list
                                    finalNumFrames = list.size
                                }
                                Log.i(TAG, "Burst evaluation: anchorFrame=$anchorFrameIndex, accepted=$finalNumFrames/${burstResult.frames.size}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to evaluate burst sharpness, falling back to default order", e)
                        }
                    }

                    val photoResolution = prefs.getString(SettingsFragment.KEY_PHOTO_RESOLUTION, SettingsFragment.RESOLUTION_FULL)
                    val isHfDownsample = isHalfFrameModeEnabled && prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_DOWNSAMPLE, true)

                    val binningFactor = when {
                        isHfDownsample || photoResolution == SettingsFragment.RESOLUTION_BINNING_2X2 -> 2
                        photoResolution == SettingsFragment.RESOLUTION_BINNING_4X4 -> 4
                        else -> 1
                    }

                    var procBuffer = megaBuffer
                    var procWidth = width
                    var procHeight = height

                    if (binningFactor == 2 && width >= 4 && height >= 4) {
                        val targetW = width / 2
                        val targetH = height / 2
                        val binnedFrameSizeBytes = targetW * targetH * 2
                        val binnedMega = ByteBuffer.allocateDirect(binnedFrameSizeBytes * finalNumFrames)
                        val frameSizeBytes = width * height * 2

                        var allOk = true
                        for (i in 0 until finalNumFrames) {
                            val srcIndex = acceptedIndices[i]
                            val srcSlice = megaBuffer.duplicate()
                            srcSlice.position(srcIndex * frameSizeBytes)
                            srcSlice.limit((srcIndex + 1) * frameSizeBytes)

                            val dstSlice = binnedMega.duplicate()
                            dstSlice.position(i * binnedFrameSizeBytes)
                            dstSlice.limit((i + 1) * binnedFrameSizeBytes)

                            val ok = top.maary.darkbag.rawvideo.RawVideoNative.nativeBayerBinning2x2(
                                srcBuffer = srcSlice.slice(),
                                srcWidth = width,
                                srcHeight = height,
                                srcRowStrideBytes = width * 2,
                                dstBuffer = dstSlice.slice(),
                                mode = top.maary.darkbag.rawvideo.RawVideoNative.BINNING_MODE_AVERAGE
                            )
                            if (!ok) {
                                allOk = false
                                break
                            }
                        }

                        if (allOk) {
                            binnedMega.rewind()
                            HdrPlusBurst.releaseBuffer(burstResult.megaBuffer)
                            megaBufferReleased = true
                            procBuffer = binnedMega
                            procWidth = targetW
                            procHeight = targetH
                            Log.i(TAG, "Applied RAW Bayer 2x2 Binning to HDR+ burst: ${width}x${height} -> ${procWidth}x${procHeight}, frames=$finalNumFrames")
                        }
                    } else if (binningFactor == 4 && width >= 8 && height >= 8) {
                        val targetW = width / 4
                        val targetH = height / 4
                        val binnedFrameSizeBytes = targetW * targetH * 2
                        val binnedMega = ByteBuffer.allocateDirect(binnedFrameSizeBytes * finalNumFrames)
                        val frameSizeBytes = width * height * 2

                        var allOk = true
                        for (i in 0 until finalNumFrames) {
                            val srcIndex = acceptedIndices[i]
                            val srcSlice = megaBuffer.duplicate()
                            srcSlice.position(srcIndex * frameSizeBytes)
                            srcSlice.limit((srcIndex + 1) * frameSizeBytes)

                            val dstSlice = binnedMega.duplicate()
                            dstSlice.position(i * binnedFrameSizeBytes)
                            dstSlice.limit((i + 1) * binnedFrameSizeBytes)

                            val ok = top.maary.darkbag.rawvideo.RawVideoNative.nativeBayerBinning4x4(
                                srcBuffer = srcSlice.slice(),
                                srcWidth = width,
                                srcHeight = height,
                                srcRowStrideBytes = width * 2,
                                dstBuffer = dstSlice.slice(),
                                mode = top.maary.darkbag.rawvideo.RawVideoNative.BINNING_MODE_AVERAGE
                            )
                            if (!ok) {
                                allOk = false
                                break
                            }
                        }

                        if (allOk) {
                            binnedMega.rewind()
                            HdrPlusBurst.releaseBuffer(burstResult.megaBuffer)
                            megaBufferReleased = true
                            procBuffer = binnedMega
                            procWidth = targetW
                            procHeight = targetH
                            Log.i(TAG, "Applied RAW Bayer 4x4 Binning to HDR+ burst: ${width}x${height} -> ${procWidth}x${procHeight}, frames=$finalNumFrames")
                        }
                    } else {
                        // Binning not applied (Full resolution).
                        // Ensure accepted anchor frame is placed at index 0
                        if (anchorFrameIndex in 1 until burstResult.frames.size) {
                            val frameSizeBytes = width * 2 * height
                            val tempBuf = java.nio.ByteBuffer.allocateDirect(frameSizeBytes)
                            val dup = megaBuffer.duplicate()
                            dup.position(0)
                            dup.limit(frameSizeBytes)
                            tempBuf.put(dup)
                            tempBuf.rewind()

                            dup.position(anchorFrameIndex * frameSizeBytes)
                            dup.limit((anchorFrameIndex + 1) * frameSizeBytes)
                            val dupDst = megaBuffer.duplicate()
                            dupDst.position(0)
                            dupDst.limit(frameSizeBytes)
                            dupDst.put(dup)

                            dupDst.position(anchorFrameIndex * frameSizeBytes)
                            dupDst.limit((anchorFrameIndex + 1) * frameSizeBytes)
                            dupDst.put(tempBuf)

                            megaBuffer.rewind()
                            Log.i(TAG, "Swapped Anchor Frame $anchorFrameIndex to index 0 for Halide alignment")
                        }
                    }

                    val enableDualStreamFusion = prefs.getBoolean(SettingsFragment.KEY_DUAL_STREAM_FUSION, true)

                    // Instant Echo: generate thumbnail directly from Anchor Frame (index 0) in <20ms
                    val evAdjust = if (digitalGain > 0.01f) (kotlin.math.ln(digitalGain) / kotlin.math.ln(2.0)).toFloat() else 0f
                    val instantThumb = generateInstantThumbnail(
                        rawBuffer = procBuffer,
                        width = procWidth,
                        height = procHeight,
                        orientation = combinedOrientation,
                        cfaPattern = cfa,
                        whiteLevel = whiteLevel,
                        blackLevel = blackLevelPattern.firstOrNull()?.toFloat() ?: 64f,
                        wb = wb,
                        targetLogIndex = targetLogIndex,
                        lutPath = nativeLutPath,
                        exposure = evAdjust
                    )

                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.toast_hdr_queued), Toast.LENGTH_SHORT).show()
                        if (isHalfFrameModeEnabled && prefs.getInt(scopedHalfFrameStepKey(prefs), 0) == 1) {
                            setGalleryThumbnail(null)
                        } else if (instantThumb != null) {
                            setGalleryThumbnailBitmap(instantThumb)
                        }
                    }

                    val request = top.maary.darkbag.processor.HdrPlusRequest(
                        requestId = java.util.UUID.randomUUID().toString(),
                        megaBuffer = procBuffer,
                        numFrames = finalNumFrames,
                        width = procWidth,
                        height = procHeight,
                        orientation = combinedOrientation,
                        whiteLevel = whiteLevel,
                        blackLevelPattern = blackLevelPattern ?: intArrayOf(64,64,64,64),
                        lensShadingMap = lensShadingMapData,
                        lensShadingRows = lensShadingRows,
                        lensShadingCols = lensShadingCols,
                        useSensorColorMatrix = useSensorColorMatrix,
                        whiteBalance = wb,
                        ccm = ccm,
                        ccmAlt = ccmAlt,
                        exportMatrixAB = exportMatrixAB,
                        cfaPattern = cfa,
                        targetLogIndex = targetLogIndex,
                        lutPath = nativeLutPath,
                        digitalGain = digitalGain,
                        zoomFactor = currentZoom,
                        mirror = mirror,
                        metadata = captureMetadata,
                        isSingleFrame = false,
                        saveJpg = saveJpg,
                        saveRaw = saveRaw,
                        baseName = dngName,
                        fullResJpgPath = fullResJpgFile.absolutePath,
                        linearDngPath = linearDngFile.absolutePath,
                        zslTargetUriStr = fastJpegUri?.toString(),
                        jpgFolderUri = jpgFolderUri,
                        rawFolderUri = rawFolderUri,
                        hfMetadata = hfMetadata?.copy(digitalGain = digitalGain),
                        editConfig = top.maary.darkbag.models.EditConfig(
                            log = targetLogName ?: "None",
                            lut = activeLutName ?: "None",
                            digitalGain = digitalGain,
                            adjustments = if (hfMetadata?.profile != null && hfMetadata.profile != top.maary.darkbag.utils.HalfFrameSessionStore.PROFILE_NORMAL) {
                                listOf(
                                    top.maary.darkbag.models.BasicAdjustments(digitalGain = hfMetadata.frame1DigitalGain),
                                    top.maary.darkbag.models.BasicAdjustments(digitalGain = digitalGain)
                                )
                            } else null,
                            hfLayout = if (hfMetadata?.profile == top.maary.darkbag.utils.HalfFrameSessionStore.PROFILE_HALF_TOP) "TB" else if (hfMetadata?.profile == top.maary.darkbag.utils.HalfFrameSessionStore.PROFILE_HALF_SIDE) "SBS" else null,
                            showTimestamp = hfMetadata?.dateStamp ?: false,
                            flareType = hfMetadata?.flareType ?: -1,
                            zoomFactor = currentZoom
                        ),
                        runAblationTest = false,
                        motionPhotoMp4Path = motionMp4Path,
                        motionPhotoStillPtsUs = motionStillPtsUs,
                        enableMemoryColor = false,
                        colorEngineMode = prefs.getInt(SettingsFragment.KEY_COLOR_ENGINE_MODE, 0),
                        enableDualStreamFusion = enableDualStreamFusion
                    )
                    top.maary.darkbag.processor.HdrPlusRequestManager.enqueue(request)
                    val serviceIntent = android.content.Intent(context, top.maary.darkbag.processor.HdrPlusProcessingService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    // Native timing stats are now recorded in HdrPlusProcessingService

            } catch (e: Exception) {
                isHdrPlusSuccess = false
                Log.e(TAG, "HDR+ processing failed, falling back to single shot", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.toast_hdr_failed_fallback), Toast.LENGTH_SHORT).show()
                }

                if (burstResult.frames.isNotEmpty()) {
                    try {
                        val firstFrame = burstResult.frames[0]
                        val frameSize = firstFrame.width * firstFrame.height * 2
                        val data = ByteBuffer.allocateDirect(frameSize)
                        if (!megaBufferReleased) {
                            burstResult.megaBuffer.position(0)
                            burstResult.megaBuffer.limit(frameSize)
                            data.put(burstResult.megaBuffer)
                            data.rewind()
                        }

                        val holder = RawImageHolder(
                            data = data,
                            width = firstFrame.width,
                            height = firstFrame.height,
                            timestamp = firstFrame.timestamp,
                            rotationDegrees = firstFrame.rotationDegrees,
                            combinedOrientation = combinedOrientation,
                            zoomRatio = currentZoom,
                            physicalId = firstFrame.physicalId,
                            halfFrameMetadata = hfMetadata
                        )
                        processingChannel.send(holder)
                        fallbackSent = true
                    } catch (fallbackEx: Exception) {
                        Log.e(TAG, "Fallback failed", fallbackEx)
                    }
                }
            } finally {
                burstResult.frames.forEach { it.close() }
                if (!isHdrPlusSuccess && !megaBufferReleased) {
                    HdrPlusBurst.releaseBuffer(burstResult.megaBuffer)
                }
                
                if (!fallbackSent) {
                    processingSemaphore.release()
                    lifecycleScope.launch(Dispatchers.Main) {
                        resetBurstUi()
                        if (!isHdrPlusSuccess) {
                            hideProcessingAnimation()
                        }
                    }
                }
            }
        }
    }

    private fun updateHdrPlusUi() {
        cameraUiContainerBinding?.hdrPlusSwitch?.let { toggle ->
            toggle.isChecked = isHdrPlusEnabled
        }
        cameraUiContainerBinding?.tvHdrStatus?.text = if (isHdrPlusEnabled) "HDR+" else "OFF"
    }

    private fun updateHdrPlusConstraints() {
        val binding = cameraUiContainerBinding ?: return
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val showFlashButton = prefs.getBoolean(SettingsFragment.KEY_SHOW_HDR_UNDEREXPOSURE_BUTTON, true)

        if (isHdrPlusEnabled) {
            // Hide and disable flash
            if (showFlashButton) {
                binding.flashButton?.visibility = View.VISIBLE
                updateUnderexposureButton()
            } else {
                binding.flashButton?.visibility = View.GONE
            }

            if (isFlashEnabled) {
                isFlashEnabled = false
                binding.flashButton?.let { updateFlashIcon(it) }
                applyCameraControls()
            }

            // Retain manual controls if enabled in settings
            val manualEnabled = prefs.getBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, false)
            binding.btnProMode?.visibility = if (manualEnabled) View.VISIBLE else View.GONE
            if (!manualEnabled) {
                binding.proInfoBarCard?.visibility = View.GONE
                binding.manualControlsRoot?.visibility = View.GONE
            }
            updateProInfoBar()
            updateLensControlRowPosition(animate = true)
        } else {
            // Restore flash visibility if supported
            val targetId = currentLens?.id ?: if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) "0" else "1"
            val hasFlash = try {
                val c2Chars = camera2Manager.getCameraCharacteristics(targetId)
                c2Chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            } catch (e: Exception) { false }

            binding.flashButton?.visibility = if (hasFlash && showFlashButton) View.VISIBLE else View.GONE
            if (hasFlash) {
                binding.flashButton?.let { updateFlashIcon(it) }
            }

            // Restore manual controls if enabled in settings
            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val manualEnabled = prefs.getBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, false)
            binding.btnProMode?.visibility = if (manualEnabled) View.VISIBLE else View.GONE
            if (!manualEnabled) {
                binding.proInfoBarCard?.visibility = View.GONE
                binding.manualControlsRoot?.visibility = View.GONE
            }
            updateProInfoBar()
            updateLensControlRowPosition(animate = true)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera2(cameraId: String) {
        // Ensure previous device and session are closed
        closeCamera2()

        Log.d(TAG, "Opening Camera2: $cameraId (retryCount: $camera2RetryCount)")

        if (camera2Thread == null) {
            camera2Thread = HandlerThread("Camera2Thread").apply { start() }
            camera2Handler = Handler(camera2Thread!!.looper)
        }

        try {
            camera2Manager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(device: android.hardware.camera2.CameraDevice) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        camera2Lock.withLock {
                            camera2RetryCount = 0 // Reset on success
                            camera2Device = device
                            createCamera2CaptureSession()
                        }
                    }
                }

                override fun onDisconnected(device: android.hardware.camera2.CameraDevice) {
                    lifecycleScope.launch { closeCamera2() }
                }

                override fun onError(device: android.hardware.camera2.CameraDevice, error: Int) {
                    Log.e(TAG, "Camera2 open error: $error for camera $cameraId")
                    lifecycleScope.launch { closeCamera2() }

                    if (error == 2 && camera2RetryCount < 1) {
                         camera2RetryCount++
                         Log.i(TAG, "Retrying camera open after hardware error (attempt $camera2RetryCount)...")
                         camera2Handler?.postDelayed({
                             lifecycleScope.launch { openCamera2(cameraId) }
                         }, 500)
                         return
                    }

                    lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.error_camera_hardware, error), Toast.LENGTH_LONG).show()
                    }
                }
            }, camera2Handler)
        } catch (e: android.hardware.camera2.CameraAccessException) {
            Log.e(TAG, "Failed to open Camera2", e)
        }
    }

    private fun createCamera2CaptureSession() {
        val device = camera2Device ?: return
        val handler = camera2Handler ?: return

        Log.d(TAG, "Creating Camera2 Capture Session for device: ${device.id}")

        val chars = camera2Manager.getCameraCharacteristics(device.id)
        val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val rawResPref = prefs.getString(SettingsFragment.KEY_RAW_VIDEO_RESOLUTION, SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION) ?: SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION

        val isRawSupportedLocally = map?.getOutputFormats()?.contains(android.graphics.ImageFormat.RAW_SENSOR) == true
        val targetCaptureSize: android.util.Size
        if (isRawSupportedLocally) {
            val rawSizes = map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR) ?: emptyArray()
            targetCaptureSize = rawSizes.maxByOrNull { it.width * it.height } ?: android.util.Size(4000, 3000)
            rawImageReader = ImageReader.newInstance(targetCaptureSize.width, targetCaptureSize.height, android.graphics.ImageFormat.RAW_SENSOR, 8)
        } else {
            val jpegSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: emptyArray()
            targetCaptureSize = jpegSizes.maxByOrNull { it.width * it.height } ?: android.util.Size(4000, 3000)
            rawImageReader = ImageReader.newInstance(targetCaptureSize.width, targetCaptureSize.height, android.graphics.ImageFormat.JPEG, 8)
        }
        val burstSizeStr = prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "5") ?: "5"
        val burstSize = burstSizeStr.toIntOrNull() ?: 5
        lifecycleScope.launch(Dispatchers.Default) {
            ColorProcessor.initMemoryPool(targetCaptureSize.width, targetCaptureSize.height, burstSize)
        }

        val yuvSizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
        val analysisSize = yuvSizes?.filter { it.width.toFloat()/it.height.toFloat() in 1.3f..1.4f }
            ?.minByOrNull { it.width * it.height } ?: android.util.Size(640, 480)
        analysisImageReader = ImageReader.newInstance(analysisSize.width, analysisSize.height, android.graphics.ImageFormat.YUV_420_888, 2)

        analysisImageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val width = image.width
                val height = image.height
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride

                var highlightCount = 0

                // Sample pixels to save CPU while still getting a good estimate
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
                lastClippingRatio = if (totalSampled > 0) highlightCount.toDouble() / totalSampled else 0.0
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing image", e)
            } finally {
                image.close()
            }
        }, handler)

        val previewSize = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width.toFloat()/it.height.toFloat() in 1.3f..1.4f }
            ?.maxByOrNull { it.width * it.height } ?: android.util.Size(1440, 1080)

        Log.d(TAG, "Requesting preview surface from LutProcessor: ${previewSize.width}x${previewSize.height}")
        lutProcessor?.getInputSurface(previewSize.width, previewSize.height) { surface ->
            lifecycleScope.launch(Dispatchers.Main) {
                camera2Lock.withLock {
                    if (!isAdded || camera2Device !== device) {
                        Log.w(TAG, "Preview surface ready but fragment detached or device changed/closed. Ignoring.")
                        return@withLock
                    }

                    camera2PreviewSurface = surface
                    val surfaces = listOf(surface, rawImageReader!!.surface, analysisImageReader!!.surface)

                    try {
                        device.createCaptureSession(surfaces, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            camera2Lock.withLock {
                                camera2Session = session
                            }
                        }
                        try {
                            val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(surface)
                            analysisImageReader?.surface?.let { request.addTarget(it) }

                            applyManualSettingsToRequest(request)

                            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                            val force60fps = prefs.getBoolean(SettingsFragment.KEY_FORCE_60FPS, false)
                            if (force60fps) {
                                val fpsRanges = chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                val targetRange = fpsRanges?.find { it.upper == 60 }
                                if (targetRange != null) {
                                    Log.d(TAG, "Setting target FPS range to: $targetRange")
                                    request.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange)
                                }
                            }

                            if (!isManualFocus) {
                                request.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }

                            session.setRepeatingRequest(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                                    handleCaptureResult(result)
                                }
                            }, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating request", e)
                        }
                    }

                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                        Log.e(TAG, "Camera2 session config failed")
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create capture session", e)
            }
                }
            }
        }
    }

    private fun toggleFrontPipInMultiCameraMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !top.maary.darkbag.utils.MultiCameraHelper.isConcurrentFrontBackSupported(requireContext())) {
            Toast.makeText(requireContext(), R.string.concurrent_front_camera_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        val binding = _fragmentCameraBinding ?: return
        val pipContainer = binding.pipContainer ?: return
        val pipViewFinder = binding.pipViewFinder ?: return
        val switchBtn = binding.cameraSwitchButtonAlt ?: return

        isFrontPipActive = !isFrontPipActive
        if (isFrontPipActive) {
            pipContainer.visibility = View.VISIBLE
            val onPrimary = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorOnPrimaryContainer)
            val primaryContainer = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorPrimaryContainer)
            switchBtn.iconTint = ColorStateList.valueOf(onPrimary)
            switchBtn.backgroundTintList = ColorStateList.valueOf(primaryContainer)

            if (concurrentFrontCameraManager == null) {
                concurrentFrontCameraManager = top.maary.darkbag.camera.ConcurrentFrontCameraManager(
                    requireContext(),
                    viewLifecycleOwner.lifecycleScope
                ).apply {
                    onFailedListener = { error ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            Log.e("CameraFragment", "Front PiP camera failed: $error")
                            isFrontPipActive = false
                            pipContainer.visibility = View.GONE
                            val onSurface = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorOnSurface)
                            val surfaceContainer = MaterialColors.getColor(switchBtn, com.google.android.material.R.attr.colorSurfaceContainerHighest)
                            switchBtn.iconTint = ColorStateList.valueOf(onSurface)
                            switchBtn.backgroundTintList = ColorStateList.valueOf(surfaceContainer)
                            Toast.makeText(requireContext(), R.string.concurrent_front_camera_unsupported, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            if (pipViewFinder.isAvailable) {
                viewLifecycleOwner.lifecycleScope.launch {
                    concurrentFrontCameraManager?.startFrontPreview(pipViewFinder)
                }
            } else {
                pipViewFinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            concurrentFrontCameraManager?.startFrontPreview(pipViewFinder)
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

            viewLifecycleOwner.lifecycleScope.launch {
                concurrentFrontCameraManager?.stop()
            }
        }
        updateProInfoBar()
    }

    private fun takeMultiCameraPicture(timing: StandardTimingTracker? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            processingSemaphore.release()
            return
        }

        val manager = multiCameraManager ?: run {
            processingSemaphore.release()
            return
        }

        showShutterBlackout()
        showProcessingAnimation()

        val orientation = getCombinedOrientation()
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val saveRaw = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_SAVE_RAW, false)
        val isHdrPlusActive = isHdrPlusEnabled && isRawSupported

        val manualConfig = top.maary.darkbag.camera.MultiCameraManualConfig(
            isLinked = isMultiCameraManualLinked,
            isManualFocus = isManualFocus,
            focusDistance = currentFocusDistance,
            isManualIso = isManualIso,
            iso = currentIso,
            isManualShutter = isManualShutter,
            exposureTimeNanos = currentExposureTime,
            evIndex = currentEvIndex
        )

        val frontJpegDeferred = CompletableDeferred<ByteArray?>()
        if (isFrontPipActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            concurrentFrontCameraManager?.captureFrontJpeg(orientation, manualConfig) { data ->
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
                lifecycleScope.launch(Dispatchers.IO) {
                    val frontJpeg = withTimeoutOrNull(2000L) { frontJpegDeferred.await() }
                    processAndSaveMultiCameraResult(result, saveRaw, frontJpeg)
                }
            },
            onError = { errorMsg ->
                Log.e(TAG, "Multi-camera capture error: $errorMsg")
                lifecycleScope.launch(Dispatchers.Main) {
                    processingSemaphore.release()
                    hideProcessingAnimation()
                    Toast.makeText(requireContext(), getString(R.string.error_multi_camera_capture_failed, errorMsg), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private suspend fun processAndSaveMultiCameraResult(
        result: top.maary.darkbag.camera.MultiCameraCaptureResult,
        saveRaw: Boolean,
        frontJpegData: ByteArray? = null
    ) {
        val appContext = requireContext().applicationContext
        val prefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
        val rawFolderUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)

        var primarySavedUri: Uri? = null

        val currentLog = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None") ?: "None"
        val currentLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, "None") ?: "None"
        val logIndex = SettingsFragment.LOG_CURVES.indexOf(currentLog)
        val lutPath = if (currentLut != "None" && currentLut.isNotBlank()) {
            val f = File(lutManager.lutDir, currentLut)
            if (f.exists()) f.absolutePath else null
        } else null

        val currentEditConfig = top.maary.darkbag.models.EditConfig(
            log = currentLog,
            lut = currentLut
        )

        try {
            for (frame in result.frames) {
                val frameBaseName = "${result.baseName}_MULTI_${frame.lens.name}"
                var jpgPathToSave: String? = null

                // 1. If we have a valid DNG, render it through ColorProcessor (LibRaw + LOG + 3D LUT)
                if (frame.tempDngPath != null) {
                    val dngFile = File(frame.tempDngPath)
                    if (dngFile.exists() && dngFile.length() > 0) {
                        val dngBytes = dngFile.readBytes()
                        val renderedFile = File(appContext.cacheDir, "rendered_${frameBaseName}.jpg")
                        val ret = top.maary.darkbag.processor.ColorProcessor.processRaw(
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
                            digitalGain = 1.0f,
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
                            colorEngineMode = prefs.getInt(SettingsFragment.KEY_COLOR_ENGINE_MODE, 0)
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
                    // Clean up temporary DNG file if user didn't request saving RAW
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
                }
            }

            withContext(Dispatchers.Main) {
                if (primarySavedUri != null) {
                    imageRepository.invalidateCache()
                    prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, primarySavedUri.toString()).apply()
                    setGalleryThumbnail(primarySavedUri.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving multi-camera result", e)
        } finally {
            processingSemaphore.release()
            withContext(Dispatchers.Main) {
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                hideProcessingAnimation()
            }
        }
    }

    private fun takeSinglePictureCamera2(
        timing: StandardTimingTracker? = null,
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null
    ) {
        val device = camera2Device ?: run { processingSemaphore.release(); return }
        val session = camera2Session ?: run { processingSemaphore.release(); return }
        val reader = rawImageReader ?: run { processingSemaphore.release(); return }
        val handler = camera2Handler ?: run { processingSemaphore.release(); return }

        try {
            val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
            request.addTarget(reader.surface)
            request.set(android.hardware.camera2.CaptureRequest.JPEG_ORIENTATION, getCombinedOrientation())

            applyManualSettingsToRequest(request)

            reader.setOnImageAvailableListener({ r ->
                timing?.captureCallback = System.currentTimeMillis()
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val currentZoom = if (currentLens?.isZoomPreset == true && currentLens?.targetZoomRatio != null) {
                        currentLens!!.targetZoomRatio!!
                    } else {
                        1.0f
                    }
                    if (image.format == android.graphics.ImageFormat.RAW_SENSOR) {
                        val digitalGain = getDigitalGainAndUpdateStep(
                            image.timestamp,
                            isFrame1Trigger,
                            timing?.shutterClick
                        )

                        val holder = copyAndroidImageToHolder(image, currentZoom, getCombinedOrientation(), currentLens?.id, hfMetadata?.copy(digitalGain = digitalGain)).copy(timing = timing, digitalGain = digitalGain)
                        image.close()
                        if (!isFrame1Trigger) {
                            showProcessingAnimation()
                        }
                        lifecycleScope.launch {
                            timing?.enqueued = System.currentTimeMillis()
                            processingChannel.send(holder)
                        }
                    } else {
                        // JPEG path
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        image.close()
                       
                        if (!isFrame1Trigger) {
                            showProcessingAnimation()
                        }

                        val captureMetadata = if (hfMetadata == null) createCaptureMetadataFromTimestamp(image.timestamp) else null

                        saveJpegFallback(data, 0, currentZoom, hfMetadata, captureMetadata) // Rotation handled by C2 JPEG_ORIENTATION
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process Camera2 image", e)
                    image.close()
                    processingSemaphore.release()
                }
            }, handler)

            session.capture(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, timestamp: Long, frameNumber: Long) {
                    showShutterBlackout()
                    if (isFrame1Trigger) {
                        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                        triggerAutoBurst(prefs)
                    }
                }

                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp != null) {
                        captureResults[timestamp] = result
                    }
                    captureResultFlow.tryEmit(result)
                }
            }, handler)

        } catch (e: Exception) {
            Log.e(TAG, "Camera2 capture failed", e)
            processingSemaphore.release()
        }
    }

    private fun triggerHdrPlusBurstCamera2(
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null
    ) {
        val device = camera2Device ?: run { processingSemaphore.release(); return }
        val session = camera2Session ?: run { processingSemaphore.release(); return }
        val reader = rawImageReader ?: run { processingSemaphore.release(); return }
        val handler = camera2Handler ?: run { processingSemaphore.release(); return }

        isBurstActive = true
        val captureStartTime = hfMetadata?.captureTimeMillis ?: System.currentTimeMillis()
        burstStartTime = captureStartTime

        try {
            val result = captureResultFlow.replayCache.lastOrNull()
            val curIso = result?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val curTime = result?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
            val validIsoRange = isoRange ?: android.util.Range(100, 3200)
            val validTimeRange = exposureTimeRange ?: android.util.Range(1000L, 1_000_000_000L)
            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val underexposureMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"

            val config = ExposureUtils.calculateHdrPlusExposure(
                curIso, curTime, validIsoRange, validTimeRange, underexposureMode, lastClippingRatio
            )

            val burstIso = if (isManualIso) currentIso else config.iso
            val burstTime = if (isManualShutter) currentExposureTime else config.exposureTime
            val burstGain = if (isManualExposure) {
                if (currentEvIndex != 0) (2.0).pow(currentEvIndex.toDouble() / 3.0).toFloat() else 1.0f
            } else {
                config.digitalGain
            }

            val burstSize = (prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "5") ?: "5").toIntOrNull() ?: 5

            if (isFrame1Trigger) {
                writeScopedHalfFrameStep(requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE), 1, captureStartTime, digitalGain = burstGain, flareType = hfMetadata?.flareType ?: -1)
            }

            hdrPlusBurstHelper = HdrPlusBurst(frameCount = burstSize, onBurstComplete = { burstResult ->
                processHdrPlusBurst(burstResult, burstGain, hfMetadata?.copy(digitalGain = burstGain))
            })

            lifecycleScope.launch(Dispatchers.Main) {
                cameraUiContainerBinding?.cameraCaptureButton?.setProgress(0f)
                cameraUiContainerBinding?.cameraCaptureButton?.startRotation()
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
                showShutterBlackout()
            }

            val burstRequests = mutableListOf<android.hardware.camera2.CaptureRequest>()
            val combinedOrientation = getCombinedOrientation()
            for (i in 0 until burstSize) {
                val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                request.addTarget(reader.surface)
                request.set(android.hardware.camera2.CaptureRequest.JPEG_ORIENTATION, combinedOrientation)

                applyManualSettingsToRequest(request, true)

                request.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF)
                request.set(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, burstIso)
                request.set(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, burstTime)

                burstRequests.add(request.build())
            }

            var framesCaptured = 0

            val watchdog = lifecycleScope.launch(Dispatchers.Main) {
                delay(8000)
                if (isBurstActive && framesCaptured < burstSize) {
                    Log.e(TAG, "Burst capture timed out! Resetting UI.")
                    isBurstActive = false
                    processingSemaphore.release()
                    resetBurstUi()
                }
            }

            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val chars = camera2Manager.getCameraCharacteristics(currentLens?.id ?: "0")
                    val sensorOrientation = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                    hdrPlusBurstHelper?.addManualFrame(
                        plane.buffer,
                        image.width,
                        image.height,
                        plane.rowStride,
                        plane.pixelStride,
                        image.timestamp,
                        sensorOrientation,
                        currentLens?.id
                    )
                    image.close()
                    framesCaptured++
                    lifecycleScope.launch(Dispatchers.Main) {
                        cameraUiContainerBinding?.cameraCaptureButton?.setProgress(framesCaptured.toFloat() / burstSize)
                    }
                    if (framesCaptured >= burstSize) {
                        watchdog.cancel()
                        lifecycleScope.launch(Dispatchers.Main) {
                            resetBurstUi()
                            if (!isFrame1Trigger) {
                                showProcessingAnimation()
                            }

                            if (isFrame1Trigger) {
                                triggerAutoBurst(prefs)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add C2 frame", e)
                    image.close()
                }
            }, handler)

            // Use captureBurst instead of sequential capture for better performance
            session.captureBurst(burstRequests, object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp != null) {
                        captureResults[timestamp] = result
                    }
                    captureResultFlow.tryEmit(result)
                }
            }, handler)

        } catch (e: Exception) {
            Log.e(TAG, "Camera2 burst failed", e)
            isBurstActive = false
            processingSemaphore.release()
            lifecycleScope.launch(Dispatchers.Main) {
                resetBurstUi()
            }
        }
    }

    private fun copyAndroidImageToHolder(
        image: android.media.Image,
        zoomRatio: Float,
        combinedOrientation: Int,
        physicalId: String?,
        halfFrameMetadata: HalfFrameManager.Metadata? = null
    ): RawImageHolder {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = 2 // RAW16
        val width = image.width
        val height = image.height

        val rowLength = width * pixelStride
        val data = ByteBuffer.allocateDirect(rowLength * height)

        buffer.rewind()
        if (rowStride == rowLength) {
            data.put(buffer)
        } else {
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.limit(y * rowStride + rowLength)
                data.put(buffer)
            }
            buffer.limit(buffer.capacity())
        }
        data.rewind()

        val chars = camera2Manager.getCameraCharacteristics(physicalId ?: "0")
        val sensorOrientation = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        return RawImageHolder(
            data = data,
            width = width,
            height = height,
            timestamp = image.timestamp,
            rotationDegrees = sensorOrientation,
            combinedOrientation = combinedOrientation,
            zoomRatio = zoomRatio,
            physicalId = physicalId,
            halfFrameMetadata = halfFrameMetadata
        )
    }


    private fun startRawVideoRecording() {
        val device = camera2Device ?: return
        val session = camera2Session ?: return
        val reader = rawImageReader ?: return

        val chars = camera2Manager.getCameraCharacteristics(device.id)
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        val targetFpsStr = prefs.getString(SettingsFragment.KEY_RAW_VIDEO_FPS, "24") ?: "24"
        val targetFps = targetFpsStr.toFloatOrNull() ?: 24.0f
        val activeLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
        val activeLog = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
        val rawResPref = prefs.getString(SettingsFragment.KEY_RAW_VIDEO_RESOLUTION, SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION) ?: SettingsFragment.DEFAULT_RAW_VIDEO_RESOLUTION
        val downsampleMode = when {
            rawResPref.contains("1080p") -> RawVideoNative.DOWNSAMPLE_BINNING_1080P
            rawResPref.contains("2K Open Gate") || (rawResPref.contains("4:3") && !rawResPref.contains("Max")) -> RawVideoNative.DOWNSAMPLE_BINNING_2K_OPEN_GATE_4_3
            rawResPref.contains("4K") -> RawVideoNative.DOWNSAMPLE_CROP_4K
            else -> RawVideoNative.DOWNSAMPLE_NONE
        }

        val timestamp = System.currentTimeMillis()
        val baseName = DarkbagIdentity.prefixedBaseName("RAWVID_${timestamp}")
        val tempDir = File(requireContext().cacheDir, "rawvideo")
        tempDir.mkdirs()
        val tempFile = File(tempDir, "${baseName}.rawvid")

        val lastResult = captureResults.values.lastOrNull()
        val combinedOrientation = getCombinedOrientation()
        rawVideoSessionManager.onLowStorageCallback = {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (rawVideoSessionManager.recording) {
                    context?.let { ctx ->
                        Toast.makeText(ctx, ctx.getString(R.string.toast_low_storage_recording_stopped), Toast.LENGTH_LONG).show()
                    }
                    stopRawVideoRecording()
                }
            }
        }
        val success = rawVideoSessionManager.startRecording(
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
        val availableFpsRanges = chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val bestFpsRange = availableFpsRanges?.find { it.lower == targetFpsInt && it.upper == targetFpsInt }
            ?: availableFpsRanges?.find { it.upper == targetFpsInt }
            ?: availableFpsRanges?.find { it.lower <= targetFpsInt && it.upper >= targetFpsInt }
            ?: availableFpsRanges?.maxByOrNull { it.upper }

        val frameDurationNs = (1_000_000_000L / targetFps).toLong()

        if (success) {
            cameraUiContainerBinding?.cameraCaptureButton?.setRecordingState(true)
            cameraUiContainerBinding?.cameraCaptureButton?.startRotation()

            reader.setOnImageAvailableListener({ r ->
                val image = try { r.acquireNextImage() } catch (e: Exception) { r.acquireLatestImage() } ?: return@setOnImageAvailableListener
                val res = captureResults.values.lastOrNull()
                rawVideoSessionManager.onRawImageAvailable(image, res)
            }, camera2Handler)

            try {
                val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD)
                camera2PreviewSurface?.let { request.addTarget(it) }
                request.addTarget(reader.surface)
                analysisImageReader?.surface?.let { request.addTarget(it) }

                // 1. Lock Target FPS range for AE to enforce frame rate and AE exposure ceiling
                if (bestFpsRange != null) {
                    request.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFpsRange)
                    Log.i(TAG, "RAW video locked AE FPS Range: $bestFpsRange (target: ${targetFps}fps)")
                }

                // 2. Lock Frame Duration
                request.set(android.hardware.camera2.CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)

                // 3. Apply manual exposure settings if active
                applyManualSettingsToRequest(request)

                // 4. Shutter speed safety clamp: in manual exposure mode, exposure time must NOT exceed frameDurationNs
                if (isManualExposure) {
                    val currentExp = request.get(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME)
                    if (currentExp != null && currentExp > frameDurationNs) {
                        request.set(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, frameDurationNs)
                        Log.w(TAG, "Clamped manual shutter ($currentExp ns) to frame duration ($frameDurationNs ns)")
                    }
                }

                session.setRepeatingRequest(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                        handleCaptureResult(result)
                    }
                }, camera2Handler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update repeating request for RAW video recording", e)
            }
        }
    }

    private fun stopRawVideoRecording() {
        cameraUiContainerBinding?.cameraCaptureButton?.setRecordingState(false)
        cameraUiContainerBinding?.cameraCaptureButton?.stopRotation()

        rawImageReader?.setOnImageAvailableListener(null, null)

        val device = camera2Device
        val session = camera2Session
        val surface = camera2PreviewSurface
        if (device != null && session != null && surface != null) {
            try {
                val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                request.addTarget(surface)
                analysisImageReader?.surface?.let { request.addTarget(it) }
                applyManualSettingsToRequest(request)
                session.setRepeatingRequest(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                        handleCaptureResult(result)
                    }
                }, camera2Handler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore preview repeating request", e)
            }
        }

        isVideoSaving = true
        showProcessingAnimation()

        val appContext = context?.applicationContext
        val prefs = appContext?.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val rawFolderUri = prefs?.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)
        val jpgFolderUri = prefs?.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = rawVideoSessionManager.stopRecording()
                if (result == null) {
                    if (appContext != null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, appContext.getString(R.string.toast_recording_too_short), Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }
                if (appContext == null) {
                    result.file.delete()
                    return@launch
                }
                val baseName = result.file.nameWithoutExtension

                val (savedUri, thumbnail) = top.maary.darkbag.utils.ImageSaver.saveRawVideo(
                    context = appContext,
                    rawVideoFile = result.file,
                    baseName = baseName,
                    targetFps = 24.0f,
                    rawFolderUri = rawFolderUri,
                    jpgFolderUri = jpgFolderUri
                )

                if (savedUri != null) {
                    prefs?.edit()?.putString(SettingsFragment.KEY_LAST_CAPTURE_URI, savedUri.toString())?.apply()
                    imageRepository.invalidateCache()
                    withContext(Dispatchers.Main) {
                        updateCurrentThumbnail(savedUri)
                        val photoViewButton = cameraUiContainerBinding?.photoViewButton
                        if (photoViewButton != null && thumbnail != null) {
                            photoViewButton.visibility = View.VISIBLE
                            photoViewButton.alpha = 1f
                            photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())
                            Glide.with(photoViewButton)
                                .load(thumbnail)
                                .apply(RequestOptions.circleCropTransform())
                                .into(photoViewButton)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving raw video", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isVideoSaving = false
                    hideProcessingAnimation()
                }
            }
        }
    }

    private fun startMp4VideoRecording() {
        val timestamp = System.currentTimeMillis()
        val baseName = DarkbagIdentity.prefixedBaseName("VID_${timestamp}")
        val tempDir = File(requireContext().cacheDir, "video")
        tempDir.mkdirs()
        val tempFile = File(tempDir, "${baseName}.mp4")

        val recorder = top.maary.darkbag.video.Mp4VideoRecorder(requireContext())
        val videoSurface = recorder.prepare(tempFile, 1080, 1920, 30, deviceOrientationDegrees) ?: run {
            Log.e(TAG, "Failed to prepare Mp4VideoRecorder")
            return
        }

        lutProcessor?.setEncoderSurface(videoSurface, 1080, 1920)

        if (!recorder.start()) {
            Log.e(TAG, "Failed to start Mp4VideoRecorder")
            lutProcessor?.setEncoderSurface(null, 0, 0)
            recorder.release()
            return
        }

        mp4VideoRecorder = recorder
        cameraUiContainerBinding?.cameraCaptureButton?.setRecordingState(true)
        cameraUiContainerBinding?.cameraCaptureButton?.startRotation()
    }

    private fun stopMp4VideoRecording() {
        cameraUiContainerBinding?.cameraCaptureButton?.setRecordingState(false)
        cameraUiContainerBinding?.cameraCaptureButton?.stopRotation()

        lutProcessor?.setEncoderSurface(null, 0, 0)
        updateMotionPhotoEncoder()

        isVideoSaving = true
        showProcessingAnimation()

        val recorder = mp4VideoRecorder
        mp4VideoRecorder = null

        val appContext = context?.applicationContext
        val prefs = appContext?.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val mediaFolderUri = prefs?.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = recorder?.stop()
                if (result == null) {
                    if (appContext != null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, appContext.getString(R.string.toast_recording_too_short), Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }
                if (appContext == null) {
                    result.file.delete()
                    return@launch
                }
                val baseName = result.file.nameWithoutExtension

                val (savedUri, thumbnail) = top.maary.darkbag.utils.ImageSaver.saveMp4Video(
                    context = appContext,
                    mp4File = result.file,
                    baseName = baseName,
                    mediaFolderUri = mediaFolderUri
                )

                if (savedUri != null) {
                    prefs?.edit()?.putString(SettingsFragment.KEY_LAST_CAPTURE_URI, savedUri.toString())?.apply()
                    imageRepository.invalidateCache()
                    withContext(Dispatchers.Main) {
                        updateCurrentThumbnail(savedUri)
                        val photoViewButton = cameraUiContainerBinding?.photoViewButton
                        if (photoViewButton != null && thumbnail != null) {
                            photoViewButton.visibility = View.VISIBLE
                            photoViewButton.alpha = 1f
                            photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())
                            Glide.with(photoViewButton)
                                .load(thumbnail)
                                .apply(RequestOptions.circleCropTransform())
                                .into(photoViewButton)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving mp4 video", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isVideoSaving = false
                    hideProcessingAnimation()
                }
            }
        }
    }

    private fun getTargetOisMode(isHdrBurst: Boolean): Int {
        return if (isHdrBurst && !isHdrOisEnabledPref) {
            android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        } else {
            android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        }
    }

    private fun applyStabilizationToRequest(request: android.hardware.camera2.CaptureRequest.Builder, isHdrBurst: Boolean) {
        if (isOisSupported) {
            val mode = getTargetOisMode(isHdrBurst)
            request.set(android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
            // Explicitly disable EIS when OIS is in use to avoid conflicts
            request.set(android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            Log.d(TAG, "OIS ${if(mode == android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) "enabled" else "disabled"} for ${if(isHdrBurst) "HDR+ Burst" else "Standard/Preview"}")
        } else {
            request.set(android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
    }

    private fun applyManualSettingsToRequest(request: android.hardware.camera2.CaptureRequest.Builder, isHdrBurst: Boolean = false) {
        // Apply Stabilization
        applyStabilizationToRequest(request, isHdrBurst)

        // Ensure predictable ISP baseline for preview color processing
        request.set(android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        request.set(android.hardware.camera2.CaptureRequest.TONEMAP_MODE, android.hardware.camera2.CaptureRequest.TONEMAP_MODE_FAST)
        request.set(android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_MODE, android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_MODE_FAST)

        if (isManualExposure) {
            request.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF)
            val isoToUse = if (isManualIso) currentIso else liveIso
            val shutterToUse = if (isManualShutter) currentExposureTime else liveExposureTime
            request.set(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, isoToUse)
            request.set(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, shutterToUse)

            if (isFlashEnabled) {
                request.set(android.hardware.camera2.CaptureRequest.FLASH_MODE, android.hardware.camera2.CaptureRequest.FLASH_MODE_TORCH)
            } else {
                request.set(android.hardware.camera2.CaptureRequest.FLASH_MODE, android.hardware.camera2.CaptureRequest.FLASH_MODE_OFF)
            }
        } else {
            if (isFlashEnabled) {
                request.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            } else {
                request.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON)
            }
            request.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
        }

        if (isManualFocus) {
            request.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF)
            request.set(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
        } else {
            if (focusMeteringRegion != null) {
                request.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO)
                request.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusMeteringRegion))
            } else {
                request.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }

        if (!isManualExposure && exposureMeteringRegion != null) {
            request.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_REGIONS, arrayOf(exposureMeteringRegion))
        }

        if (currentLens?.useCamera2 == true) {
            val deviceId = camera2Device?.id ?: currentLens?.id
            if (deviceId != null) {
                val chars = camera2Manager.getCameraCharacteristics(deviceId)
                val activeArray = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArray != null) {
                    val targetRatio = if (currentLens?.isZoomPreset == true && currentLens?.targetZoomRatio != null) {
                        currentLens!!.targetZoomRatio!!
                    } else {
                        1.0f
                    }
                    if (targetRatio > 1.01f) {
                        val cropW = (activeArray.width() / targetRatio).toInt()
                        val cropH = (activeArray.height() / targetRatio).toInt()
                        val centerX = activeArray.centerX()
                        val centerY = activeArray.centerY()
                        val cropRegion = android.graphics.Rect(
                            centerX - cropW / 2,
                            centerY - cropH / 2,
                            centerX + cropW / 2,
                            centerY + cropH / 2
                        )
                        request.set(android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION, cropRegion)
                    } else {
                        request.set(android.hardware.camera2.CaptureRequest.SCALER_CROP_REGION, activeArray)
                    }
                }
            }
        }
    }

    private suspend fun closeCamera2() {
        camera2Lock.withLock {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                multiCameraManager?.close()
                multiCameraManager = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                concurrentFrontCameraManager?.stop()
                concurrentFrontCameraManager = null
                isFrontPipActive = false
            }
            camera2Session?.close()
            camera2Session = null
            camera2Device?.close()
            camera2Device = null
            rawImageReader?.close()
            rawImageReader = null
            analysisImageReader?.close()
            analysisImageReader = null
            camera2PreviewSurface = null
            lutProcessor?.releaseInputSurface()
            // Do NOT quit the thread here to avoid "dead thread" crash during callbacks
        }
    }

    private suspend fun releaseCamera2Resources() {
        closeCamera2()
        camera2Thread?.quitSafely()
        camera2Thread = null
        camera2Handler = null
    }

    private fun getDigitalGainAndUpdateStep(
        timestamp: Long,
        isFrame1Trigger: Boolean,
        shutterClickTime: Long?
    ): Float {
        val result = captureResults[timestamp]
        val curIso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val curTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val validIsoRange = isoRange ?: android.util.Range(100, 3200)
        val validTimeRange = exposureTimeRange ?: android.util.Range(1000L, 1_000_000_000L)

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val underexposureMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"

        val digitalGain = if (isHdrPlusEnabled) {
            ExposureUtils.calculateHdrPlusExposure(
                curIso, curTime, validIsoRange, validTimeRange, underexposureMode, lastClippingRatio
            ).digitalGain
        } else 1.0f

        if (isFrame1Trigger) {
            val session = halfFrameSessionStore.readSession()
            writeScopedHalfFrameStep(prefs, 1, shutterClickTime, digitalGain = digitalGain, flareType = session.flareType)
        }

        return digitalGain
    }

    private fun syncManualFocusAfterTap() {
        lifecycleScope.launch(Dispatchers.Default) {
            withTimeoutOrNull(3000) {
                captureResultFlow.first { res ->
                    val afState = res.get(CaptureResult.CONTROL_AF_STATE)
                    afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                }
            }?.let { res ->
                val dist = res.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (dist != null) {
                    currentFocusDistance = dist
                    withContext(Dispatchers.Main) {
                        isManualFocus = true
                        applyCameraControls()
                        updateDialPanel()
                        updateProInfoBar()
                    }
                }
            }
        }
    }

    private fun showShutterBlackout() {
        val vfBinding = _fragmentCameraBinding ?: return
        val blackout = vfBinding.viewFinderBlackout ?: return
        val vf = vfBinding.viewFinder
        blackout.post {
            // Sync translation and scaling with the ViewFinder to ensure full coverage in Half-frame mode
            blackout.translationX = vf.translationX
            blackout.translationY = vf.translationY
            blackout.scaleX = vf.scaleX
            blackout.scaleY = vf.scaleY

            blackout.visibility = View.VISIBLE
            blackout.bringToFront()
            blackout.postDelayed({
                _fragmentCameraBinding?.viewFinderBlackout?.visibility = View.INVISIBLE
            }, 100L) // Use 100ms to ensure visibility during processing
        }
    }

    private fun updateHalfFrameUI(animate: Boolean = false) {
        val uiBinding = cameraUiContainerBinding ?: return
        val vfBinding = _fragmentCameraBinding ?: return
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        // Post all updates to the viewfinder to avoid requestLayout() during layout pass
        // and ensure consistent ordering of state changes.
        vfBinding.viewFinder.post {
            val isEnabled = isHalfFrameModeEnabled
            readScopedHalfFrameState(prefs)

            val gapView = vfBinding.halfFrameGapIndicator ?: return@post
            val snapshotView = vfBinding.halfFrameSnapshot ?: return@post
            val edgeTopView = vfBinding.halfFrameFilmEdgeTop ?: return@post
            val edgeBottomView = vfBinding.halfFrameFilmEdgeBottom ?: return@post

            if (!isEnabled) {
                isHalfFrameUiAnimating = false
                if (uiBinding.tvHalfFrameStep?.visibility != View.GONE) {
                    uiBinding.tvHalfFrameStep?.visibility = View.GONE
                }
                if (gapView.visibility != View.GONE) gapView.visibility = View.GONE
                if (snapshotView.visibility != View.GONE) snapshotView.visibility = View.GONE
                if (edgeTopView.visibility != View.GONE) edgeTopView.visibility = View.GONE
                if (edgeBottomView.visibility != View.GONE) edgeBottomView.visibility = View.GONE

                vfBinding.viewFinder.animate().cancel()
                vfBinding.viewFinder.alpha = 1f
                vfBinding.viewFinder.scaleX = 1f
                vfBinding.viewFinder.scaleY = 1f
                vfBinding.viewFinder.translationX = 0f
                vfBinding.viewFinder.translationY = 0f

                if (uiBinding.photoViewButton?.visibility != View.VISIBLE) {
                    uiBinding.photoViewButton?.visibility = View.VISIBLE
                    uiBinding.photoViewButton?.alpha = 1f
                }

                return@post
            }

            // Enabled path
            if (uiBinding.tvHalfFrameStep?.visibility != View.VISIBLE) {
                uiBinding.tvHalfFrameStep?.visibility = View.VISIBLE
            }
            val stepText = if (halfFrameStep == 0) "1/2" else "2/2"
            if (uiBinding.tvHalfFrameStep?.text != stepText) {
                uiBinding.tvHalfFrameStep?.text = stepText
            }

            // Hide thumbnail button during processing of Shot 1 and throughout Shot 2
            if (halfFrameStep == 1) {
                uiBinding.photoViewButton?.visibility = View.INVISIBLE
            } else if (!isProcessing) {
                uiBinding.photoViewButton?.visibility = View.VISIBLE
                uiBinding.photoViewButton?.alpha = 1f
            }

            val stageW = vfBinding.viewFinderStage.width.toFloat()
            val stageH = vfBinding.viewFinderStage.height.toFloat()
            if (stageW <= 0f || stageH <= 0f) return@post

            val gapWidth = (stageW * 0.12f).coerceAtLeast(60f)
            // Keep the viewfinder aspect ratio by applying a uniform scale on both axes.
            // In half-frame mode, we shrink the VF slightly to reveal the gap.
            val scale = stageW / (stageW + gapWidth)
            val shift = (gapWidth * scale) / 2f

            if (edgeTopView.visibility != View.VISIBLE) edgeTopView.visibility = View.VISIBLE
            if (edgeBottomView.visibility != View.VISIBLE) edgeBottomView.visibility = View.VISIBLE

            vfBinding.viewFinder.scaleX = scale
            vfBinding.viewFinder.scaleY = scale

            val layout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS)
            val isTopBottom = layout == SettingsFragment.HALF_FRAME_LAYOUT_TB

            val baseShift = if (halfFrameStep == 0) -shift else shift
            val targetShift = if (isTopBottom) -baseShift else baseShift

            if (animate) {
                performHalfFrameAdvanceAnimation(targetShift, stageW, gapWidth * scale, isTopBottom)
            } else {
                isHalfFrameUiAnimating = false
                vfBinding.viewFinder.animate().cancel()
                vfBinding.viewFinder.translationX = targetShift
                // Ensure viewfinder is visible after lens/engine switch
                vfBinding.viewFinder.alpha = 1f
                gapView.visibility = View.GONE
                snapshotView.visibility = View.GONE
            }

            if (vfBinding.viewFinder.translationY != 0f) {
                vfBinding.viewFinder.translationY = 0f
            }
        }
    }

    private fun performHalfFrameAdvanceAnimation(targetShift: Float, stageW: Float, gapWidth: Float, isTopBottom: Boolean) {
        val vfBinding = _fragmentCameraBinding ?: return
        val vf = vfBinding.viewFinder
        val snapshot = vfBinding.halfFrameSnapshot ?: return
        val gap = vfBinding.halfFrameGapIndicator ?: return

        // Direction logic: SBS moves Left (-X), TB moves Right (+X)
        val moveFactor = if (isTopBottom) 1f else -1f
        val duration = 500L
        val interpolator = android.view.animation.AccelerateDecelerateInterpolator()

        // 1. Snapshot logic
        val bitmap = pendingVfSnapshot
        if (bitmap != null) {
            snapshot.setImageBitmap(bitmap)
            snapshot.visibility = View.VISIBLE
            snapshot.translationX = vf.translationX
            snapshot.scaleX = vf.scaleX
            snapshot.scaleY = vf.scaleY
            if (snapshot.layoutParams.width != vf.width || snapshot.layoutParams.height != vf.height) {
                snapshot.layoutParams.width = vf.width
                snapshot.layoutParams.height = vf.height
                snapshot.requestLayout()
            }
        }

        // 2. Gap logic (only show when moving FROM Shot 1 TO Shot 2)
        if (halfFrameStep == 1) {
            gap.visibility = View.VISIBLE
            val scaledVfWidth = vf.width * vf.scaleX
            gap.translationX = if (isTopBottom) {
                vf.translationX - gapWidth // Gap is to the LEFT of Shot 1 in TB
            } else {
                vf.translationX + scaledVfWidth // Gap is to the RIGHT of Shot 1 in SBS
            }
            val targetWidth = gapWidth.toInt()
            val targetHeight = (vf.height * vf.scaleY).toInt()
            if (gap.layoutParams.width != targetWidth || gap.layoutParams.height != targetHeight) {
                gap.layoutParams.width = targetWidth
                gap.layoutParams.height = targetHeight
                gap.requestLayout()
            }
        } else {
            gap.visibility = View.GONE
        }

        // 3. Prepare Live ViewFinder to slide in from opposite side
        vf.animate().cancel()
        isHalfFrameUiAnimating = true
        // Start position is current target + distance of one stage width in opposite direction of roll
        vf.translationX = targetShift - (moveFactor * stageW)

        // 4. Perform animations
        snapshot.animate()
            .translationX(snapshot.translationX + (moveFactor * stageW))
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction {
                snapshot.visibility = View.GONE
                snapshot.setImageBitmap(null)
                pendingVfSnapshot = null
            }
            .start()

        if (gap.visibility == View.VISIBLE) {
            gap.animate()
                .translationX(gap.translationX + (moveFactor * stageW))
                .setDuration(duration)
                .setInterpolator(interpolator)
                .withEndAction { gap.visibility = View.GONE }
                .start()
        }

        vf.animate()
            .translationX(targetShift)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction { isHalfFrameUiAnimating = false }
            .start()

        animateFilmEdgeRoll(isTopBottom, duration)
    }

    private fun animateFilmEdgeRoll(isTopBottom: Boolean, duration: Long = 500L) {
        val vfBinding = _fragmentCameraBinding ?: return
        val topEdge = vfBinding.halfFrameFilmEdgeTop ?: return
        val bottomEdge = vfBinding.halfFrameFilmEdgeBottom ?: return

        topEdge.animate().cancel()
        bottomEdge.animate().cancel()

        // Sprocket hole period is 30dp.
        val periodPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 30f, resources.displayMetrics
        )
        // Move by exactly 10 periods to ensure a seamless reset and a convincing roll
        val rollDistance = periodPx * 10
        val moveFactor = if (isTopBottom) 1f else -1f // Match VF move direction
        val dist = moveFactor * rollDistance

        val interpolator = android.view.animation.AccelerateDecelerateInterpolator()

        topEdge.animate()
            .translationX(dist)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction { topEdge.translationX = 0f }
            .start()

        bottomEdge.animate()
            .translationX(dist)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction { bottomEdge.translationX = 0f }
            .start()
    }

    private fun cycleCaptureMode() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val isHalfFramePref = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
        val isMultiCamPref = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_MODE, false)
        val forceEnable = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_FORCE_ENABLE, false)
        val isMultiCamSupported = top.maary.darkbag.utils.MultiCameraHelper.isMultiCameraSupported(requireContext(), forceEnable)
        val canUseMultiCam = (isMultiCamPref || forceEnable) && isMultiCamSupported

        val currentActiveMode = resolveActiveCaptureMode(prefs)

        val availableModes = mutableListOf(CaptureMode.NORMAL)
        if (isHalfFramePref) {
            availableModes.add(CaptureMode.HALF_FRAME_SBS)
            availableModes.add(CaptureMode.HALF_FRAME_TB)
        }
        if (canUseMultiCam) {
            availableModes.add(CaptureMode.MULTI_CAMERA)
        }

        val currentIndex = availableModes.indexOf(currentActiveMode)
        val nextIndex = if (currentIndex != -1 && currentIndex < availableModes.size - 1) {
            currentIndex + 1
        } else {
            0
        }
        val nextMode = availableModes[nextIndex]

        val editor = prefs.edit().putString(SettingsFragment.KEY_ACTIVE_CAPTURE_MODE, nextMode.key)
        if (nextMode == CaptureMode.HALF_FRAME_SBS) {
            editor.putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS)
        } else if (nextMode == CaptureMode.HALF_FRAME_TB) {
            editor.putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_TB)
        }
        editor.apply()

        val modeChanged = (currentActiveMode != nextMode)
        isHalfFrameModeEnabled = (nextMode == CaptureMode.HALF_FRAME_SBS || nextMode == CaptureMode.HALF_FRAME_TB)
        isMultiCameraModeActive = (nextMode == CaptureMode.MULTI_CAMERA)

        readScopedHalfFrameState(prefs, requireFileForStep1 = true)
        updateHalfFrameUI()
        updateShutterOrientation()
        updateMotionPhotoEncoder()
        updateProInfoBar()
        _fragmentCameraBinding?.modeSwitchButton?.let { updateModeSwitchIcon(it) }

        if (modeChanged) {
            if (isMultiCameraModeActive && isAdded) {
                Toast.makeText(requireContext(), R.string.multi_camera_mode_enabled_toast, Toast.LENGTH_SHORT).show()
            }
            bindCameraUseCases()
        }
    }

    private fun updateModeSwitchIcon(btn: MaterialButton) {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val activeMode = resolveActiveCaptureMode(prefs)

        val iconRes = when (activeMode) {
            CaptureMode.MULTI_CAMERA -> R.drawable.ic_mode_multi_camera
            CaptureMode.HALF_FRAME_SBS -> R.drawable.ic_mode_half_side
            CaptureMode.HALF_FRAME_TB -> R.drawable.ic_mode_half_top
            CaptureMode.NORMAL -> R.drawable.ic_mode_normal
        }
        btn.setIconResource(iconRes)
    }

    private fun rotateShutter(targetRotation: Float) {
        val binding = cameraUiContainerBinding ?: return
        val shutter = binding.cameraCaptureButton as? ExpressiveShutterButton ?: return

        val current = shutter.getDotRotation()
        val diff = (targetRotation - current) % 360
        val shortestDiff = when {
            diff > 180 -> diff - 360
            diff < -180 -> diff + 360
            else -> diff
        }

        ValueAnimator.ofFloat(current, current + shortestDiff).apply {
            duration = ANIMATION_SLOW_MILLIS.toLong()
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                shutter.setDotRotation(animator.animatedValue as Float)
            }
            start()
        }
    }

    private fun updateShutterOrientation() {
        rotateShutter(getDotTargetRotation())
    }

    private fun applyUIVisibility() {
        val uiBinding = cameraUiContainerBinding ?: return
        val vfBinding = _fragmentCameraBinding ?: return
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        val showHdrPill = prefs.getBoolean(SettingsFragment.KEY_SHOW_HDR_PLUS_SWITCH, true)
        val showSettings = prefs.getBoolean(SettingsFragment.KEY_SHOW_SETTINGS_BUTTON, true)
        val showSwitch = prefs.getBoolean(SettingsFragment.KEY_SHOW_CAMERA_SWITCH_BUTTON, true)
        val showModeSwitch = prefs.getBoolean(SettingsFragment.KEY_SHOW_MODE_SWITCH_BUTTON, true)
        val showLensControls = prefs.getBoolean(SettingsFragment.KEY_SHOW_LENS_CONTROLS, true)
        val showLutSwitcher = prefs.getBoolean(SettingsFragment.KEY_SHOW_LUT_SWITCHER, true)

        uiBinding.hdrPlusPill?.visibility = if (showHdrPill) View.VISIBLE else View.GONE
        uiBinding.settingsButton?.visibility = if (showSettings) View.VISIBLE else View.GONE
        vfBinding.cameraSwitchButtonAlt?.visibility = if (showSwitch) View.VISIBLE else View.GONE
        vfBinding.modeSwitchButton?.visibility = if (showModeSwitch) View.VISIBLE else View.GONE

        val hasMultipleLenses = vfBinding.lensControlsContainer?.childCount ?: 0 > 1
        vfBinding.lensControlsCard?.visibility = if (showLensControls && hasMultipleLenses) View.VISIBLE else View.GONE

        // Hide the whole row if all its components are hidden
        vfBinding.lensControlRow?.visibility = if (showSwitch || showModeSwitch || (showLensControls && hasMultipleLenses)) View.VISIBLE else View.GONE

        uiBinding.lutSwitcherButton?.visibility = if (showLutSwitcher) View.VISIBLE else View.GONE

        // Update Motion Photo Button & Flash/Underexposure button as well
        updateMotionPhotoButton()
        updateHdrPlusConstraints()
    }

    private fun getDotTargetRotation(): Float {
        if (!isHalfFrameModeEnabled) {
            return -deviceOrientationDegrees.toFloat()
        }

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val layout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS)

        // Half-frame forces output orientation. Dot points to the fixed "Up" of the output frame.
        return if (layout == SettingsFragment.HALF_FRAME_LAYOUT_TB) {
            // Top-bottom forces Landscape. Right side is Up.
            90f
        } else {
            // Side-by-side forces Portrait. Up is phone-top (0).
            0f
        }
    }

    private fun resetBurstUi() {
        cameraUiContainerBinding?.cameraCaptureButton?.setProgress(0f)
        cameraUiContainerBinding?.cameraCaptureButton?.stopRotation()
        isBurstActive = false

        if (processingSemaphore.availablePermits > 0) {
            cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
        } else {
            cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
        }
    }

    private fun writeDngToStream(
        dngCreator: android.hardware.camera2.DngCreator,
        image: RawImageHolder,
        outputStream: java.io.OutputStream
    ) {
        val bytes = ByteArray(image.data.remaining())
        val originalPos = image.data.position()
        image.data.get(bytes)
        image.data.position(originalPos)

        val inputStream = java.io.ByteArrayInputStream(bytes)
        dngCreator.writeInputStream(
            outputStream,
            android.util.Size(image.width, image.height),
            inputStream,
            0
        )
    }

    private fun createDngThumbnailBitmap(sourceJpeg: File, maxDimension: Int = 240): android.graphics.Bitmap? {
        if (!sourceJpeg.exists() || sourceJpeg.length() <= 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceJpeg.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        while ((bounds.outWidth / inSampleSize) > maxDimension || (bounds.outHeight / inSampleSize) > maxDimension) {
            inSampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(sourceJpeg.absolutePath, decodeOpts) ?: return null
        if (decoded.width < 256 && decoded.height < 256) return decoded

        val scale = minOf(255f / decoded.width.toFloat(), 255f / decoded.height.toFloat(), 1.0f)
        val scaledWidth = maxOf(1, kotlin.math.floor(decoded.width * scale).toInt())
        val scaledHeight = maxOf(1, kotlin.math.floor(decoded.height * scale).toInt())
        val scaled = android.graphics.Bitmap.createScaledBitmap(decoded, scaledWidth, scaledHeight, true)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    private fun createCaptureMetadata(
        iso: Int?,
        exposureTime: Long?,
        fNumber: Float?,
        focalLength: Float?,
        captureTime: Long,
        targetCharId: String?,
        isHdrPlus: Boolean,
        captureResult: CaptureResult?
    ): CaptureMetadata {
        val offset = SimpleDateFormat("XXX", Locale.US).format(Date(captureTime))

        // Get focal length in 35mm film equivalent
        var focalIn35mm: Int? = null
        var finalFocalLength = focalLength
        try {
            val chars = targetCharId?.let { camera2Manager.getCameraCharacteristics(it) }
            if (chars != null) {
                focalIn35mm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let { focalLengths ->
                    val focal = captureResult?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: focalLengths.firstOrNull() ?: 0f
                    val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 36f
                    (focal * 36f / sensorWidth).toInt()
                }

                if (currentLens?.isZoomPreset == true) {
                    val zoomRatio = currentLens?.targetZoomRatio ?: 1.0f
                    if (zoomRatio > 1.0f) {
                        val baseF35 = focalIn35mm ?: kotlin.math.round(currentLens?.equivalentFocalLength ?: 24f).toInt()
                        val virtualF35 = kotlin.math.round(baseF35 * zoomRatio).toInt()
                        focalIn35mm = virtualF35

                        val baseFocal = finalFocalLength ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                        finalFocalLength = baseFocal * zoomRatio
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to calculate focalLengthIn35mmFilm", e)
        }

        val saveLoc = try {
            val p = context?.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            p?.getBoolean(SettingsFragment.KEY_SAVE_LOCATION, false) == true
        } catch (e: Exception) { false }

        val locationSnapshot = if (saveLoc) locationHelper.getCurrentLocation() else null

        return CaptureMetadata(
            iso = iso,
            exposureTime = exposureTime,
            fNumber = fNumber,
            focalLength = finalFocalLength,
            focalLengthIn35mmFilm = focalIn35mm,
            dateTimeOriginal = captureTime,
            dateTimeDigitized = captureTime,
            offsetTime = offset,
            offsetTimeOriginal = offset,
            offsetTimeDigitized = offset,
            make = DarkbagIdentity.normalizedManufacturer(),
            model = DarkbagIdentity.normalizedModel(),
            uniqueCameraModel = DarkbagIdentity.uniqueCameraModel(targetCharId),
            software = DarkbagIdentity.softwareString(isHdrPlus),
            imageDescription = DarkbagIdentity.imageDescription(isHdrPlus),
            location = locationSnapshot
        )
    }

    private fun getMeteringRectangle(
        x: Float, y: Float,
        viewWidth: Int, viewHeight: Int,
        sensorOrientation: Int,
        lensFacing: Int,
        cropRegion: android.graphics.Rect
    ): MeteringRectangle {
        val normalizedX = x / viewWidth
        val normalizedY = y / viewHeight

        val displayRotation = when (displayManager.getDisplay(displayId)?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val sensorToDisplay = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + displayRotation) % 360
        } else {
            (sensorOrientation - displayRotation + 360) % 360
        }

        val matrix = Matrix()
        matrix.postRotate(-sensorToDisplay.toFloat(), 0.5f, 0.5f)
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f, 0.5f, 0.5f)
        }

        val pts = floatArrayOf(normalizedX, normalizedY)
        matrix.mapPoints(pts)

        val sx = pts[0].coerceIn(0f, 1f)
        val sy = pts[1].coerceIn(0f, 1f)

        val centerX = cropRegion.left + (sx * cropRegion.width()).toInt()
        val centerY = cropRegion.top + (sy * cropRegion.height()).toInt()

        val size = (cropRegion.width() * 0.1f).toInt()
        val rect = android.graphics.Rect(
            (centerX - size / 2).coerceIn(cropRegion.left, cropRegion.right),
            (centerY - size / 2).coerceIn(cropRegion.top, cropRegion.bottom),
            (centerX + size / 2).coerceIn(cropRegion.left, cropRegion.right),
            (centerY + size / 2).coerceIn(cropRegion.top, cropRegion.bottom)
        )
        return MeteringRectangle(rect, 1000)
    }
}

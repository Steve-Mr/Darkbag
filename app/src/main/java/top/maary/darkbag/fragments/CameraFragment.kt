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
import top.maary.darkbag.motionphoto.MotionPhotoEncoder
import kotlinx.coroutines.CompletableDeferred
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
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import top.maary.darkbag.MainApplication
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.models.CaptureMetadata

import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.ImageSaver
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.activity.OnBackPressedCallback
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit

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
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
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
    private var isManualExposure = false
    @Volatile private var lastClippingRatio: Double = 0.0
    private var activeManualTab: String? = null
    private var focusMeteringRegion: MeteringRectangle? = null
    private var exposureMeteringRegion: MeteringRectangle? = null

    // Flash State
    private var isFlashEnabled = false

    // HDR+ State
    private var isHdrPlusEnabled = false

    private val locationHelper by lazy { top.maary.darkbag.utils.LocationHelper(requireContext()) }

    private val shouldMirror: Boolean
        get() = lensFacing == CameraSelector.LENS_FACING_FRONT &&
                requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(SettingsFragment.KEY_MIRROR_FRONT_CAMERA, true)

    @Volatile private var isBurstActive = false
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

    private val isProcessing: Boolean
        get() = top.maary.darkbag.processor.HdrPlusRequestManager.pendingTasksCount.value > 0

    // Half-frame State
    private var pendingVfSnapshot: android.graphics.Bitmap? = null
    private var isHalfFrameModeEnabled = false
    private var isMultiCameraModeActive = false
    private var multiCameraManager: top.maary.darkbag.camera.MultiCameraCaptureManager? = null
    private var halfFrameStep = 0
    private var halfFrameTempPath: String? = null
    private lateinit var halfFrameSessionStore: HalfFrameSessionStore
    private var isHalfFrameUiAnimating = false

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

                if (!isHalfFrameModeEnabled) {
                    val rotation = when (orientation) {
                        in 45 until 135 -> android.view.Surface.ROTATION_270
                        in 135 until 225 -> android.view.Surface.ROTATION_180
                        in 225 until 315 -> android.view.Surface.ROTATION_90
                        else -> android.view.Surface.ROTATION_0
                    }

                    imageCapture?.targetRotation = rotation
                    imageAnalyzer?.targetRotation = rotation
                    preview?.targetRotation = rotation
                }
            }
        }
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    // Cache for CaptureResults to match with ImageProxy timestamps
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

        if (isMotionPhotoEnabled && !isHalfFrameModeEnabled) {
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
        if (isHalfFrameModeEnabled) {
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
                if (!isHalfFrameModeEnabled) {
                    preview?.targetRotation = view.display.rotation
                }
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
        locationHelper.stopListening()
        orientationEventListener.disable()
        lutProcessor?.setEncoderSurface(null, 0, 0)
        motionPhotoEncoder?.stop()
        motionPhotoEncoder = null
        // Ensure Camera2 is closed when stopping to release hardware resources
        lifecycleScope.launch {
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

        // Re-initialize camera engine if needed.
        // For Camera2 engine, we need to re-bind use cases (which triggers openCamera2).
        // For CameraX, they are bound to lifecycle but we ensure consistency.
        // Only bind if the view has already been fully created and the layout passed
        if (cameraProvider != null || currentLens?.useCamera2 == true) {
            if (_fragmentCameraBinding?.viewFinderContainer?.isLaidOut == true) {
                bindCameraUseCases()
            } else {
                _fragmentCameraBinding?.viewFinderContainer?.post {
                    bindCameraUseCases()
                }
            }
        }
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
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

        // Important: Unbind CameraX before releasing executors/processors
        // to prevent RejectedExecutionException during cleanup.
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding CameraX in onDestroyView", e)
        }

        // Shut down our background executor
        cameraExecutor.shutdown()

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

            // Remove thumbnail padding
            photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

            lifecycleScope.launch(Dispatchers.Main) {
                val lastModified = try {
                    context?.let { mediaStoreUtils.getFileLastModified(it, android.net.Uri.parse(filename)) } ?: 0L
                } catch (e: Exception) {
                    0L
                }

                // Load thumbnail into circular button using Glide
                Glide.with(photoViewButton)
                    .load(filename)
                    .apply(RequestOptions.circleCropTransform())
                    .signature(com.bumptech.glide.signature.ObjectKey(lastModified))
                    .into(photoViewButton)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize Preferences
        val prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        val defaultStartup = prefs.getString(SettingsFragment.KEY_DEFAULT_STARTUP, SettingsFragment.STARTUP_CAMERA)
        val enablePlayground = prefs.getBoolean(SettingsFragment.KEY_ENABLE_PLAYGROUND, true)

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (enablePlayground && defaultStartup == SettingsFragment.STARTUP_PLAYGROUND) {
                    if (!androidx.navigation.fragment.NavHostFragment.findNavController(this@CameraFragment).navigateUp()) {
                        requireActivity().finishAfterTransition()
                    }
                } else {
                    requireActivity().finishAfterTransition()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

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

        // Initialize Half-frame State (isolated by mode/layout profile)
        isHalfFrameModeEnabled = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
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
        val cameraXIds = mutableSetOf<String>()
        cameraProvider?.availableCameraInfos?.forEach { info ->
            val id = Camera2CameraInfo.from(info).cameraId
            cameraXIds.add(id)
        }

        val repoFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        else
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT

        // Use unified focal length presets (includes digital ones like 28mm, 35mm, 2.0x)
        val newLenses = cameraRepository.getFocalLengthPresets(cameraXIds, repoFacing)

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
        // Initially don't initialize cameraProvider unless needed.
        // But we need to know lensFacing.
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        val defaultLensId = prefs.getString(SettingsFragment.KEY_DEFAULT_LENS_ID, null)
        if (defaultLensId != null) {
            val facing = cameraRepository.getFacingOfSensorId(defaultLensId)
            lensFacing = if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        } else {
            lensFacing = prefs.getInt(KEY_LENS_FACING, CameraSelector.LENS_FACING_BACK)
        }

        // Initialize Lenses
        withContext(Dispatchers.Default) {
            refreshLenses()
        }

        // Select lensFacing depending on the available cameras
        if (availableLenses.isEmpty()) {
             // Fallback to front if no back lenses found or just use default check
             lensFacing = CameraSelector.LENS_FACING_FRONT
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

    @OptIn(ExperimentalCamera2Interop::class)
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

    @OptIn(ExperimentalCamera2Interop::class)
    private suspend fun bindCameraUseCasesInternal() {
        // Reset Tap-to-Focus regions on lens switch
        focusMeteringRegion = null
        exposureMeteringRegion = null

        // Fetch Characteristics for Manual Control
        val targetId = currentLens?.id ?: if (lensFacing == CameraSelector.LENS_FACING_BACK) "0" else "1"

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
                updateManualPanel()
                updateTabColors()

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

        // Decide Engine: MultiCamera, Camera2 (Hard Switch), or CameraX
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val useCameraxFallback = prefs.getBoolean(SettingsFragment.KEY_USE_CAMERAX, false)

        if (isMultiCameraModeActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val logicalInfo = top.maary.darkbag.utils.MultiCameraHelper.getLogicalMultiCameraInfo(requireContext())
            if (logicalInfo != null) {
                Log.d(TAG, "Binding Multi-Camera Session with logical camera: ${logicalInfo.logicalCameraId}")
                cameraProvider?.unbindAll()
                camera = null
                closeCamera2()

                val countPref = top.maary.darkbag.utils.MultiCameraCountPreference.fromKey(
                    prefs.getString(SettingsFragment.KEY_MULTI_CAMERA_COUNT_PREF, null)
                )
                val pairPref = top.maary.darkbag.utils.DualLensPairPreference.fromKey(
                    prefs.getString(SettingsFragment.KEY_MULTI_CAMERA_DUAL_PAIR, null)
                )
                val saveRaw = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_SAVE_RAW, false)

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
                            saveRaw = saveRaw
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

        if (currentLens?.useCamera2 == true && !useCameraxFallback) {
            Log.d(TAG, "Switching to Camera2 Engine for lens: ${currentLens?.name}")

            // Clean up CameraX if it was active
            cameraProvider?.unbindAll()
            camera = null

            // Ensure UI is updated before opening Camera2
            initLensControls()

            // Update constraints to set flash/underexposure button visibility correctly
            updateHdrPlusConstraints()

            // Re-apply half-frame transformations and UI if enabled
            updateHalfFrameUI()

            // Give system a moment to release hardware
            delay(300)
            openCamera2(currentLens!!.id)
            return
        }

        // Else, ensure Camera2 is closed and use CameraX
        closeCamera2()

        // Initialize CameraX provider lazily if needed
        if (cameraProvider == null) {
            lifecycleScope.launch {
                cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()
                refreshLenses() // Update lenses with CameraX direct info if available
                bindCameraUseCases() // Re-enter to bind
            }
            return
        }

        val cameraProvider = cameraProvider!!

        // Use previously computed screen metrics
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val rotation = fragmentCameraBinding.viewFinder.display.rotation

        // CameraSelector
        var cameraSelector = if (currentLens != null) {
            CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter {
                        val id = Camera2CameraInfo.from(it).cameraId
                        id == currentLens?.id
                    }
                }
                .build()
        } else {
            CameraSelector.Builder().requireLensFacing(lensFacing).build()
        }

        val cameraInfo = try {
            cameraProvider.getCameraInfo(cameraSelector)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera info for ${currentLens?.id}", e)
            if (useCameraxFallback) {
                Log.w(TAG, "Fallback to generic lens facing as requested by setting")
                cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val info = cameraProvider.getCameraInfo(cameraSelector)

                // Sync currentLens with actual bound camera
                val actualId = Camera2CameraInfo.from(info).cameraId
                val fallbackLens = availableLenses.find { it.id == actualId && it.physicalId == null }
                if (fallbackLens != null && fallbackLens.sensorId != currentLens?.sensorId) {
                    Log.d(TAG, "Updating currentLens to fallback: ${fallbackLens.name}")
                    currentLens = fallbackLens
                    updateLensUI()
                }
                info
            } else {
                Log.e(TAG, "CameraX bind failed and fallback is disabled.")
                Toast.makeText(requireContext(), "Failed to bind to selected lens", Toast.LENGTH_SHORT).show()
                return
            }
        }


        // Force 4:3 aspect ratio
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    AspectRatio.RATIO_4_3,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                )
            )
            .build()

        // Preview
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)

        currentLens?.physicalId?.let { pId ->
            Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(pId)
        }
        preview = previewBuilder.build()

        // ImageCapture
        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .setFlashMode(if (isFlashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)

        currentLens?.physicalId?.let { pId ->
            Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(pId)
        }

        if (isRawSupported) {
            imageCaptureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
        }

        // Add Camera2 Interop Callback to capture metadata
        androidx.camera.camera2.interop.Camera2Interop.Extender(imageCaptureBuilder)
            .setSessionCaptureCallback(object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: android.hardware.camera2.CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    val timestamp =
                        result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp != null) {
                        captureResults[timestamp] = result
                    }
                    captureResultFlow.tryEmit(result)

                    // Background Calculation for HDR+ Latency Optimization
                    if (isHdrPlusEnabled && !isBurstActive && !isManualExposure && isAdded) {
                        lifecycleScope.launch(Dispatchers.Default) {
                            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
                            val time = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L

                            val validIsoRange = isoRange ?: android.util.Range(100, 3200)
                            val validTimeRange = exposureTimeRange ?: android.util.Range(1000L, 1_000_000_000L)
                            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                            val underexposureMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"

                            lastHdrPlusConfig = ExposureUtils.calculateHdrPlusExposure(
                                iso, time, validIsoRange, validTimeRange, underexposureMode, lastClippingRatio
                            )
                        }
                    }
                }
            })

        imageCapture = imageCaptureBuilder.build()

        // ImageAnalysis
        val imageAnalyzerBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)

        currentLens?.physicalId?.let { pId ->
            Camera2Interop.Extender(imageAnalyzerBuilder).setPhysicalCameraId(pId)
        }
        imageAnalyzer = imageAnalyzerBuilder.build()
            .also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                })
            }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        if (camera != null) {
            removeCameraStateObservers(camera!!.cameraInfo)
        }

        val lutBinder = object : Preview.SurfaceProvider {
            override fun onSurfaceRequested(request: SurfaceRequest) {
                lutProcessor?.onInputSurface(request)
            }
        }

        // Use main executor for the surface provider to avoid unbinding callbacks
        // hitting a shut down cameraExecutor during lifecycle transitions.
        preview?.setSurfaceProvider(androidx.core.content.ContextCompat.getMainExecutor(requireContext()), lutBinder)

        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview!!)
            .addUseCase(imageCapture!!)
            .addUseCase(imageAnalyzer!!)
            .build()

        try {
            // Refresh Physical Lens Controls UI for the active facing before binding
            initLensControls()

            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, useCaseGroup
            )

            camera?.let { cam ->
                observeCameraState(cam.cameraInfo)
            }

            // Pre-initialize JNI memory pool with current resolution and burst size
            // Move to background thread to avoid blocking main thread
            val burstSizeStr = prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "5") ?: "5"
            val burstSize = burstSizeStr.toIntOrNull() ?: 5
            imageCapture?.resolutionInfo?.resolution?.let { res ->
                lifecycleScope.launch(Dispatchers.Default) {
                    ColorProcessor.initMemoryPool(res.width, res.height, burstSize)
                }
            }

            // Restore Zoom
            updateZoom(false)

            // Apply Settings
            applyCameraControls()

            // Finally, update constraints to set flash/underexposure button visibility correctly
            updateHdrPlusConstraints()

            // Re-apply half-frame transformations and rotations if enabled
            updateHalfFrameUI()

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed, attempting fallback", exc)
            if (currentLens?.isLogicalAuto == false) {
                currentLens = availableLenses.find { it.isLogicalAuto }
                lifecycleScope.launch(Dispatchers.Main) {
                    updateLensUI()
                    bindCameraUseCases()
                }
            }
        }
    }

    private fun removeCameraStateObservers(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.removeObservers(viewLifecycleOwner)
    }

    private fun observeCameraState(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.observe(viewLifecycleOwner) { cameraState ->
            cameraState.error?.let { error ->
                Log.e(TAG, "Camera State Error: ${error.code}")
                if ((error.code == CameraState.ERROR_CAMERA_DISABLED || error.code == CameraState.ERROR_CAMERA_FATAL_ERROR)
                    && currentLens?.isLogicalAuto == false) {

                    Log.w(TAG, "Camera error detected on non-auto lens, falling back")
                    currentLens = availableLenses.find { it.isLogicalAuto }
                    updateLensUI()
                    bindCameraUseCases()
                }
            }
        }
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
                    val manualId = uiBinding.manualControlsRoot?.id

                    if (topId != null && bottomId != null) {
                        // Center Viewfinder Container between top bar and manual controls (or bottom island if manual is GONE)
                        val bottomAnchorId = if (manualId != null) {
                            // Ensure Manual Controls are constrained to the Bottom Island
                            constraintSet.connect(manualId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, bottomId, androidx.constraintlayout.widget.ConstraintSet.TOP)
                            manualId
                        } else {
                            bottomId
                        }

                        val marginMedium = resources.getDimensionPixelSize(R.dimen.margin_medium)
                        constraintSet.connect(containerId, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START, marginMedium)
                        constraintSet.connect(containerId, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END, marginMedium)

                        constraintSet.constrainHeight(containerId, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT)
                        constraintSet.connect(containerId, androidx.constraintlayout.widget.ConstraintSet.TOP, topId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
                        constraintSet.connect(containerId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, bottomAnchorId, androidx.constraintlayout.widget.ConstraintSet.TOP)
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
            // Close LUT list
            if (cameraUiContainerBinding?.lutListContainer?.visibility == View.VISIBLE) {
                cameraUiContainerBinding?.lutListContainer?.visibility = View.GONE
                it.visibility = View.GONE
            }

            if (cameraUiContainerBinding?.manualPanel?.visibility == View.VISIBLE) {
                 cameraUiContainerBinding?.manualPanel?.visibility = View.GONE
                 cameraUiContainerBinding?.manualTabs?.clearChecked()
                 activeManualTab = null
                 it.visibility = View.GONE
            }
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
                    imageCapture?.flashMode = if (isFlashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
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
        cameraUiContainerBinding?.cameraCaptureButton?.setOnLongClickListener {
            if (isHalfFrameModeEnabled && halfFrameStep == 1) {
                // Cancel/Reset half-frame
                val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                halfFrameSessionStore.clearCurrentSession(deleteTempFile = true)
                writeScopedHalfFrameStep(prefs, 0)
                updateHalfFrameUI()
                true
            } else {
                false
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

            // Trigger Motion Photo snapshot if enabled and not in half-frame mode
            val motionEnabled = prefs.getBoolean(SettingsFragment.KEY_MOTION_PHOTO, false) && !isHalfFrameModeEnabled
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
            } else if (currentLens?.useCamera2 == true) {
                if (isHdrPlusEnabled && isRawSupported) {
                    triggerHdrPlusBurstCamera2(isFrame1Trigger, hfMetadataForTrigger)
                } else {
                    takeSinglePictureCamera2(timing, isFrame1Trigger, hfMetadataForTrigger)
                }
            } else {
                // Get a stable reference of the modifiable image capture use case
                imageCapture?.let { imageCapture ->
                    if (isRawSupported) {
                        if (isHdrPlusEnabled) {
                            triggerHdrPlusBurst(imageCapture, isFrame1Trigger, hfMetadataForTrigger)
                        } else {
                            takeSinglePicture(imageCapture, timing, isFrame1Trigger, hfMetadataForTrigger)
                        }
                    } else {
                        takeSinglePicture(imageCapture, timing, isFrame1Trigger, hfMetadataForTrigger)
                    }
                } ?: run {
                     processingSemaphore.release()
                }
            }
        }
        _fragmentCameraBinding?.cameraSwitchButtonAlt?.let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
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
        } catch (exception: CameraInfoUnavailableException) {
            _fragmentCameraBinding?.cameraSwitchButtonAlt?.isEnabled = false
        }
    }

    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera(): Boolean {
        val provider = cameraProvider
        if (provider != null) {
            return provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        }
        // Fallback to CameraManager if CameraX is not initialized
        return try {
            camera2Manager.cameraIdList.any { id ->
                val chars = camera2Manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) { false }
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean {
        val provider = cameraProvider
        if (provider != null) {
            return provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        }
        // Fallback to CameraManager if CameraX is not initialized
        return try {
            camera2Manager.cameraIdList.any { id ->
                val chars = camera2Manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) { false }
    }

    /**
     * Our custom image analysis class.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        override fun analyze(image: ImageProxy) {
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            lastAnalyzedTimestamp = frameTimestamps.first

            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            var sum = 0L
            // Direct ByteBuffer iteration to avoid massive allocations (toByteArray + map + average)
            // This reduces garbage collection pressure significantly during preview.
            for (y in 0 until height) {
                val rowStart = y * rowStride
                for (x in 0 until width) {
                    sum += buffer.get(rowStart + x * pixelStride).toLong() and 0xFF
                }
            }
            val luma = sum.toDouble() / (width * height)

            listeners.forEach { it(luma) }
            image.close()
        }
    }

    private fun copyImageToHolder(
        image: ImageProxy,
        zoomRatio: Float,
        combinedOrientation: Int,
        physicalId: String? = null,
        halfFrameMetadata: HalfFrameManager.Metadata? = null
    ): RawImageHolder {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = 2 // 16-bit raw

        val rowLength = width * pixelStride
        val dataLength = rowLength * height
        val cleanData = ByteBuffer.allocateDirect(dataLength)

        if (rowStride == rowLength) {
            cleanData.put(buffer)
        } else {
            buffer.rewind()
            for (y in 0 until height) {
                val rowStart = y * rowStride
                if (rowStart + rowLength > buffer.capacity()) break
                buffer.position(rowStart)
                buffer.limit(rowStart + rowLength)
                cleanData.put(buffer)
            }
            buffer.limit(buffer.capacity())
        }
        cleanData.rewind()

        return RawImageHolder(
            data = cleanData,
            width = width,
            height = height,
            timestamp = image.imageInfo.timestamp,
            rotationDegrees = image.imageInfo.rotationDegrees,
            combinedOrientation = combinedOrientation,
            zoomRatio = zoomRatio,
            physicalId = physicalId,
            halfFrameMetadata = halfFrameMetadata
        )
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
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

                val cam = camera
                val camera2InfoId = if (cam != null) Camera2CameraInfo.from(cam.cameraInfo).cameraId else "0"

                val targetCharId = activePhysicalId ?: image.physicalId ?: currentLens?.id ?: camera2InfoId
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

                // 3. JNI Halide Processing (REMOVED)
                // 前台 JNI 调用与 fastjpg 生成已移除，直接交由 HdrPlusProcessingService 处理
                val fastOutputUri: android.net.Uri? = null
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Processing Queued", Toast.LENGTH_SHORT).show()
                    if (isHalfFrameModeEnabled && prefs.getInt(scopedHalfFrameStepKey(prefs), 0) == 1) {
                        setGalleryThumbnail(null)
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

                        val dngBuffer = image.data.duplicate()
                        dngBuffer.rewind()
                        FileOutputStream(bayerDngFile).use { out ->
                            dngCreator.writeByteBuffer(out, Size(image.width, image.height), dngBuffer, 0)
                        }
                        
                        ImageSaver.saveProcessedImage(
                            context = context,
                            inputBitmap = null,
                            bmpPath = null,
                            rotationDegrees = 0,
                            zoomFactor = 1.0f,
                            baseName = dngName,
                            linearDngPath = bayerDngFile.absolutePath,
                            saveJpg = false,
                            saveRaw = saveRaw,
                            jpgFolderUri = null,
                            rawFolderUri = rawFolderUri,
                            isFastPath = false,
                            captureMetadata = captureMetadata
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save DNG asynchronously", e)
                    }
                }


                // 5. Enqueue HQ Processing
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
                    megaBuffer = image.data!!,
                    numFrames = 1,
                    width = image.width,
                    height = image.height,
                    orientation = image.combinedOrientation,
                    whiteLevel = whiteLevel,
                    blackLevelPattern = blackLevelPattern ?: intArrayOf(64,64,64,64),
                    lensShadingMap = lensShadingMapData,
                    lensShadingRows = lensShadingRows,
                    lensShadingCols = lensShadingCols,
                    useSensorColorMatrix = false,
                    whiteBalance = wb,
                    ccm = ccm,
                    ccmAlt = null,
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
                    motionPhotoStillPtsUs = motionStillPtsUs
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
                if (currentLens?.useCamera2 == true) {
                     triggerTapToFocusCamera2(event.x, event.y)
                } else {
                    val cameraInfo = camera?.cameraInfo ?: return@setOnTouchListener true
                    val width = _fragmentCameraBinding?.viewFinderStage?.width?.toFloat() ?: 0f
                    val height = _fragmentCameraBinding?.viewFinderStage?.height?.toFloat() ?: 0f

                    val factory = DisplayOrientedMeteringPointFactory(
                        _fragmentCameraBinding?.viewFinderStage?.display!!,
                        cameraInfo,
                        width,
                        height
                    )
                    val point = factory.createPoint(event.x, event.y)

                    val actionBuilder = if (isManualExposure) {
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    } else {
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    }

                    actionBuilder.disableAutoCancel()
                    val action = actionBuilder.build()

                    // If manual focus is ON, we temporarily switch to Auto to sync the slider
                    if (isManualFocus) {
                        syncManualFocusAfterTap()
                    }

                    isManualFocus = false
                    applyCameraControls() // Apply change

                    if (activeManualTab == "Focus") {
                        updateManualPanel()
                    }

                    updateTabColors()

                    camera?.cameraControl?.startFocusAndMetering(action)
                }

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

    private fun initManualControls() {
        val prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, false)
        if (!enabled) return

        val binding = cameraUiContainerBinding ?: return
        binding.manualControlsRoot?.visibility = View.VISIBLE

        // Tab Listeners
        binding.manualTabs?.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_tab_focus -> activeManualTab = "Focus"
                    R.id.btn_tab_iso -> activeManualTab = "ISO"
                    R.id.btn_tab_shutter -> activeManualTab = "Shutter"
                    R.id.btn_tab_ev -> activeManualTab = "EV"
                }
                binding.manualPanel?.visibility = View.VISIBLE
                binding.touchOverlay?.visibility = View.VISIBLE
                updateManualPanel()
            } else {
                if (group.checkedButtonId == View.NO_ID) {
                    activeManualTab = null
                    binding.manualPanel?.visibility = View.GONE
                    binding.touchOverlay?.visibility = View.GONE
                }
            }
        }

        // Manual Panel Listeners
        binding.seekbarManual?.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                handleManualProgress(value)
            }
        }

        binding.btnManualAuto?.setOnClickListener {
            resetCurrentManualParameter()
        }

        // Focus Extras
        binding.btnFocusNear?.setOnClickListener {
            currentFocusDistance = minFocusDistance
            isManualFocus = true
            applyCameraControls()
            updateManualPanel()
            updateTabColors()
        }

        binding.btnFocusFar?.setOnClickListener {
            currentFocusDistance = 0.0f
            isManualFocus = true
            applyCameraControls()
            updateManualPanel()
            updateTabColors()
        }

    }

    private fun handleManualProgress(value: Float) {
        val binding = cameraUiContainerBinding ?: return
        val max = binding.seekbarManual?.valueTo ?: 1000.0f
        val ratio = value / max

        when (activeManualTab) {
            "Focus" -> {
                currentFocusDistance = ratio * minFocusDistance
                isManualFocus = true
                binding.tvManualValue?.text = String.format("%.2f", currentFocusDistance)
            }

            "ISO" -> {
                isoRange?.let { range ->
                    currentIso = (range.lower + (range.upper - range.lower) * ratio).toInt()
                    isManualExposure = true
                    binding.tvManualValue?.text = "$currentIso"
                }
            }

            "Shutter" -> {
                exposureTimeRange?.let { range ->
                    val minVal = range.lower.toDouble()
                    val maxVal = range.upper.toDouble()
                    val res = minVal * Math.pow(maxVal / minVal, ratio.toDouble())
                    currentExposureTime = res.toLong()
                    isManualExposure = true

                    val ms = currentExposureTime / 1_000_000.0
                    if (ms < 1000) {
                        binding.tvManualValue?.text = String.format("1/%.0fs", 1000.0 / ms)
                    } else {
                        binding.tvManualValue?.text = String.format("%.1fs", ms / 1000.0)
                    }
                }
            }

            "EV" -> {
                evRange?.let { range ->
                    currentEvIndex = (range.lower + (range.upper - range.lower) * ratio).toInt()
                    if (isManualExposure) {
                        Toast.makeText(requireContext(),
                            "EV disabled in Manual Exposure",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        binding.tvManualValue?.text = "$currentEvIndex"
                    }
                }
            }
        }
        applyCameraControls()
        updateTabColors()
    }

    private fun resetCurrentManualParameter() {
        when (activeManualTab) {
            "Focus" -> {
                isManualFocus = false
                focusMeteringRegion = null
                camera?.cameraControl?.cancelFocusAndMetering()
            }

            "ISO", "Shutter" -> {
                isManualExposure = false
                exposureMeteringRegion = null
            }

            "EV" -> {
                currentEvIndex = 0
                exposureMeteringRegion = null
            }
        }
        applyCameraControls()
        updateManualPanel()
        updateTabColors()
    }

    private fun updateTabColors() {
        val binding = cameraUiContainerBinding ?: return
        val activeColor = Color.YELLOW
        val inactiveColor = Color.WHITE

        binding.btnTabFocus?.setTextColor(if (isManualFocus) activeColor else inactiveColor)
        binding.btnTabIso?.setTextColor(if (isManualExposure) activeColor else inactiveColor)
        binding.btnTabShutter?.setTextColor(if (isManualExposure) activeColor else inactiveColor)
        binding.btnTabEv?.setTextColor(if (!isManualExposure && currentEvIndex != 0) activeColor else inactiveColor)
    }

    private fun updateManualPanel() {
        val binding = cameraUiContainerBinding ?: return
        binding.focusExtras?.visibility =
            if (activeManualTab == "Focus") View.VISIBLE else View.GONE

        val max = binding.seekbarManual?.valueTo ?: 1000.0f

        when (activeManualTab) {
            "Focus" -> {
                if (isManualFocus) {
                    val ratio =
                        if (minFocusDistance > 0) currentFocusDistance / minFocusDistance else 0f
                    binding.seekbarManual?.value = (ratio * max).coerceIn(0f, max)
                    binding.tvManualValue?.text = String.format("%.2f", currentFocusDistance)
                } else {
                    binding.tvManualValue?.text = "Auto"
                    binding.seekbarManual?.value = 0.0f
                }
            }

            "ISO" -> {
                isoRange?.let { range ->
                    if (isManualExposure) {
                        val ratio =
                            (currentIso - range.lower).toFloat() / (range.upper - range.lower)
                        binding.seekbarManual?.value = (ratio * max).coerceIn(0f, max)
                        binding.tvManualValue?.text = "$currentIso"
                    } else {
                        binding.tvManualValue?.text = "Auto"
                        binding.seekbarManual?.value = 0.0f
                    }
                }
            }

            "Shutter" -> {
                exposureTimeRange?.let { range ->
                    if (isManualExposure) {
                        val minVal = range.lower.toDouble()
                        val maxVal = range.upper.toDouble()
                        val ratio =
                            Math.log(currentExposureTime.toDouble() / minVal) / Math.log(maxVal / minVal)
                        binding.seekbarManual?.value = (ratio * max).toFloat().coerceIn(0f, max)
                        val ms = currentExposureTime / 1_000_000.0
                        if (ms < 1000) {
                            binding.tvManualValue?.text = String.format("1/%.0fs", 1000.0 / ms)
                        } else {
                            binding.tvManualValue?.text = String.format("%.1fs", ms / 1000.0)
                        }
                    } else {
                        binding.tvManualValue?.text = "Auto"
                        binding.seekbarManual?.value = 0.0f
                    }
                }
            }

            "EV" -> {
                evRange?.let { range ->
                    val ratio =
                        (currentEvIndex - range.lower).toFloat() / (range.upper - range.lower)
                    binding.seekbarManual?.value = (ratio * max).coerceIn(0f, max)
                    binding.tvManualValue?.text = "$currentEvIndex"
                }
            }
        }
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

        val repoFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        else
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT

        val filteredLenses = availableLenses.filter { it.facing == repoFacing }.filter {
            !it.isZoomPreset || it.sensorId.contains("virtual-2x")
        }.filter {
            !it.sensorId.contains(CameraRepository.VIRTUAL_TELE_2X_SUFFIX)
        }

        // Populate lens controls if any are available
        if (filteredLenses.isNotEmpty()) {
            val isBackCamera = lensFacing == CameraSelector.LENS_FACING_BACK
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

                        if (oldLens?.id != currentLens?.id || oldLens?.physicalId != currentLens?.physicalId || oldLens?.useCamera2 != currentLens?.useCamera2) {
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

        val isBackCamera = lensFacing == CameraSelector.LENS_FACING_BACK
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
        if (camera2Device != null) {
            updateZoomCamera2()
            updateLensUI()
            return
        }

        val targetRatio = if (currentLens?.isZoomPreset == true && currentLens?.targetZoomRatio != null) {
            currentLens!!.targetZoomRatio!!
        } else {
            1.0f
        }

        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 8.0f
        val ratio = targetRatio.coerceAtMost(maxZoom)

        camera?.cameraControl?.setZoomRatio(ratio)
        updateLensUI()
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
                    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp != null) {
                        captureResults[timestamp] = result
                    }
                    captureResultFlow.tryEmit(result)
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
            val targetId = lens?.id ?: if (lensFacing == CameraSelector.LENS_FACING_BACK) "0" else "1"

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

        val combined = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            (sensorOrientation - effectiveDegrees + 360) % 360
        } else {
            (sensorOrientation + effectiveDegrees) % 360
        }
        Log.d(TAG, "getCombinedOrientation: sensor=$sensorOrientation, effective=$effectiveDegrees, facing=$lensFacing -> combined=$combined")
        return combined
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCameraControls(isHdrBurst: Boolean = false) {
        if (camera2Device != null) {
            updateCamera2RepeatingRequest(isHdrBurst)
            return
        }

        val cameraControl = camera?.cameraControl ?: return
        val camera2Control = Camera2CameraControl.from(cameraControl)
        val builder = CaptureRequestOptions.Builder()

        val prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val antiBandingMode = when (prefs.getString(SettingsFragment.KEY_ANTIBANDING, "Auto")) {
            "50Hz" -> android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            "60Hz" -> android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            "Off" -> android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antiBandingMode)

        if (isManualFocus) {
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE,
                currentFocusDistance
            )
        }

        if (isManualExposure) {
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF
            )
            builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            builder.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                currentExposureTime
            )
        }

        // Apply Stabilization
        applyStabilizationOptions(builder, isHdrBurst)

        camera2Control.setCaptureRequestOptions(builder.build())

        if (!isManualExposure) {
            cameraControl.setExposureCompensationIndex(currentEvIndex)
        }
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
                    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp != null) {
                        captureResults[timestamp] = result
                    }
                    captureResultFlow.tryEmit(result)
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
                        container.visibility = View.GONE
                        binding.touchOverlay?.visibility = View.GONE
                    }
                }
            }

            override fun getItemCount() = items.size
        }
    }

    companion object {
        private const val TAG = "Darkbag"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_TYPE = "image/jpeg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val FOCUS_RING_DISPLAY_TIME_MS = 500L
        private const val FOCUS_RING_FADE_OUT_DURATION_MS = 300L
        private const val AE_SETTLE_DELAY_MS = 50L
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

    private fun takeSinglePicture(
        imageCapture: ImageCapture,
        timing: StandardTimingTracker? = null,
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null
    ) {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    timing?.captureCallback = System.currentTimeMillis()

                    if (isFrame1Trigger) {
                        triggerAutoBurst(prefs)
                    }

                    if (image.format == android.graphics.ImageFormat.RAW_SENSOR) {
                        try {
                            val currentZoom = if (currentLens?.isZoomPreset == true && currentLens?.targetZoomRatio != null) {
                                currentLens!!.targetZoomRatio!!
                            } else {
                                1.0f
                            }

                            val digitalGain = getDigitalGainAndUpdateStep(
                                image.imageInfo.timestamp,
                                isFrame1Trigger,
                                timing?.shutterClick
                            )

                            val holder = copyImageToHolder(
                                image, currentZoom, getCombinedOrientation(), currentLens?.physicalId, hfMetadata?.copy(digitalGain = digitalGain)
                            ).copy(timing = timing, digitalGain = digitalGain)
                            image.close()

                            if (!isFrame1Trigger) {
                                showProcessingAnimation()
                            }
                            lifecycleScope.launch {
                                timing?.enqueued = System.currentTimeMillis()
                                processingChannel.send(holder)
                            }
                        } catch (e: OutOfMemoryError) {
                            Log.e(TAG, "OOM during capture copy", e)
                            image.close()
                            processingSemaphore.release()
                            lifecycleScope.launch(Dispatchers.Main) {
                                Toast.makeText(requireContext(),
                                    "Memory full, photo not saved",
                                    Toast.LENGTH_SHORT
                                ).show()
                                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                                hideProcessingAnimation()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during capture copy", e)
                            image.close()
                            processingSemaphore.release()
                            lifecycleScope.launch(Dispatchers.Main) {
                                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled =
                                    true
                                hideProcessingAnimation()
                            }
                        }
                    } else {
                        // JPEG/YUV fallback path
                        val buffer = image.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)
                        val rotation = image.imageInfo.rotationDegrees
                        image.close()

                        val currentZoom = if (currentLens?.isZoomPreset == true && currentLens?.targetZoomRatio != null) {
                                currentLens!!.targetZoomRatio!!
                            } else {
                                1.0f
                        }
                        if (!isFrame1Trigger) {
                            showProcessingAnimation()
                        }

                        val captureMetadata = if (hfMetadata == null) createCaptureMetadataFromTimestamp(image.imageInfo.timestamp) else null

                        saveJpegFallback(data, rotation, currentZoom, hfMetadata, captureMetadata)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    processingSemaphore.release()
                    lifecycleScope.launch(Dispatchers.Main) {
                        cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                    }
                }
            })

        if (processingSemaphore.availablePermits == 0) {
            cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
        }

        showShutterBlackout()
    }

    private fun triggerHdrPlusBurst(
        imageCapture: ImageCapture,
        isFrame1Trigger: Boolean = false,
        hfMetadata: HalfFrameManager.Metadata? = null
    ) {
        if (isBurstActive) {
            Log.d(TAG, "Burst already active, ignoring trigger")
            processingSemaphore.release()
            return
        }
        isBurstActive = true
        val captureStartTime = hfMetadata?.captureTimeMillis ?: System.currentTimeMillis()
        burstStartTime = captureStartTime

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

                val config = lastHdrPlusConfig ?: run {
                    val result = captureResultFlow.replayCache.lastOrNull() ?: withTimeoutOrNull(2000) {
                        captureResultFlow.first()
                    }

                    if (result == null) {
                        Log.e(TAG, "Timed out waiting for capture result for HDR+ config")
                        throw RuntimeException("Camera metadata timeout")
                    }

                    val currentIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
                    val currentTime = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
                    val validIsoRange = isoRange ?: android.util.Range(100, 3200)
                    val validTimeRange = exposureTimeRange ?: android.util.Range(1000L, 1_000_000_000L)
                    val underexposureMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"
                    ExposureUtils.calculateHdrPlusExposure(
                        currentIso,
                        currentTime,
                        validIsoRange,
                        validTimeRange,
                        underexposureMode,
                        lastClippingRatio
                    )
                }

                Log.d(
                    TAG,
                    "HDR+ Exposure: TargetISO=${config.iso}, TargetTime=${config.exposureTime}, DigitalGain=${config.digitalGain}"
                )

                val cameraControl = camera?.cameraControl
                if (cameraControl != null) {
                    val camera2Control = Camera2CameraControl.from(cameraControl)
                    val builder = CaptureRequestOptions.Builder()
                    builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF
                    )
                    builder.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, config.iso)
                    builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                        config.exposureTime
                    )
                    // Apply OIS for HDR+ Burst
                    applyStabilizationOptions(builder, true)
                    camera2Control.setCaptureRequestOptions(builder.build()).await()
                }

                delay(AE_SETTLE_DELAY_MS)

                val burstSizeStr = prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "5") ?: "5"
                val burstSize = burstSizeStr.toIntOrNull() ?: 5

                if (isFrame1Trigger) {
                    writeScopedHalfFrameStep(prefs, 1, captureStartTime, digitalGain = config.digitalGain, flareType = hfMetadata?.flareType ?: -1)
                }

                hdrPlusBurstHelper = HdrPlusBurst(
                    frameCount = burstSize,
                    onBurstComplete = { burstResult ->
                        processHdrPlusBurst(burstResult, config.digitalGain, hfMetadata?.copy(digitalGain = config.digitalGain))
                    }
                )

                cameraUiContainerBinding?.cameraCaptureButton?.setProgress(0f)
                cameraUiContainerBinding?.cameraCaptureButton?.startRotation()
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false

                showShutterBlackout()

                Toast.makeText(requireContext(),
                    "Capturing HDR+ Burst ($burstSize frames)...",
                    Toast.LENGTH_SHORT
                ).show()

                Log.d(TAG, "Starting HDR+ Burst (Pipelined, $burstSize frames)")

                for (i in 0 until burstSize) {
                    captureBurstFrame(imageCapture, burstSize, i, isFrame1Trigger)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HDR+ burst", e)
                Toast.makeText(requireContext(),
                    "HDR+ setup failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                resetBurstUi()
                processingSemaphore.release()
                applyCameraControls()
            }
        }
    }

    private fun captureBurstFrame(imageCapture: ImageCapture, totalFrames: Int, currentFrame: Int, isFrame1Trigger: Boolean = false) {
        Log.d(TAG, "Triggering burst frame ${currentFrame + 1}/$totalFrames")
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.d(TAG, "Burst frame ${currentFrame + 1} captured successfully.")

                    lifecycleScope.launch(Dispatchers.Main) {
                        val progress = (currentFrame + 1).toFloat() / totalFrames
                        cameraUiContainerBinding?.cameraCaptureButton?.setProgress(progress)

                        if (currentFrame + 1 >= totalFrames) {
                            Log.d(TAG, "HDR+ Burst Capture sequence complete.")
                            applyCameraControls()
                            resetBurstUi()
                            if (!isFrame1Trigger) {
                                showProcessingAnimation()
                            }

                            if (isFrame1Trigger) {
                                val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                                triggerAutoBurst(prefs)
                            }
                        }
                    }

                    val helper = hdrPlusBurstHelper
                    if (helper != null) {
                        try {
                            helper.addFrame(image, currentLens?.physicalId)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to add frame to burst", e)
                            lifecycleScope.launch(Dispatchers.Main) {
                                applyCameraControls()
                                resetBurstUi()
                                processingSemaphore.release()
                            }
                            return
                        }
                    } else {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Burst frame ${currentFrame + 1} failed: ${exception.message}")
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (isBurstActive) {
                            applyCameraControls()
                            resetBurstUi()
                            processingSemaphore.release()
                            hdrPlusBurstHelper?.reset()
                            isBurstActive = false
                        }
                    }
                }
            }
        )
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

                var chars: android.hardware.camera2.CameraCharacteristics? = null
                val cam = camera
                val camInfo = cam?.cameraInfo

                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val camera2InfoId = if (camInfo != null) Camera2CameraInfo.from(camInfo).cameraId else "0"

                val activePhysicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && result != null) {
                    result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                } else null

                val targetCharId = activePhysicalId ?: frames[0].physicalId ?: currentLens?.id ?: camera2InfoId
                Log.d(TAG, "Fetching HDR+ characteristics for processing using ID: $targetCharId")
                chars = cameraManager.getCameraCharacteristics(targetCharId)

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

                if (chars != null) {
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
                }

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

                    if (useSensorColorMatrix && chars != null) {
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
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "HDR+ Queued for processing", Toast.LENGTH_SHORT).show()
                    if (isHalfFrameModeEnabled && prefs.getInt(scopedHalfFrameStepKey(prefs), 0) == 1) {
                        setGalleryThumbnail(null)
                    }
                    // 保持加载动画，不调用 hideProcessingAnimation()
                }

                    var motionMp4Path: String? = null
                    var motionStillPtsUs: Long = 0L
                    if (pendingMotionPhotoTask != null) {
                        val task = pendingMotionPhotoTask
                        pendingMotionPhotoTask = null
                        val result = withTimeoutOrNull(2500L) { task?.await() }
                        motionMp4Path = result?.first
                        motionStillPtsUs = result?.second ?: 0L
                    }

                    val request = top.maary.darkbag.processor.HdrPlusRequest(
                        requestId = java.util.UUID.randomUUID().toString(),
                        megaBuffer = megaBuffer,
                        numFrames = burstResult.frames.size,
                        width = width,
                        height = height,
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
                        motionPhotoStillPtsUs = motionStillPtsUs
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
                Log.e(TAG, "HDR+ processing failed, falling back to single shot", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "HDR+ failed, saving single frame...", Toast.LENGTH_SHORT).show()
                }

                if (burstResult.frames.isNotEmpty()) {
                    try {
                        val firstFrame = burstResult.frames[0]
                        val frameSize = firstFrame.width * firstFrame.height * 2
                        val data = ByteBuffer.allocateDirect(frameSize)
                        burstResult.megaBuffer.position(0)
                        burstResult.megaBuffer.limit(frameSize)
                        data.put(burstResult.megaBuffer)
                        data.rewind()

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
                HdrPlusBurst.releaseBuffer(burstResult.megaBuffer)
                
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
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                // If using Camera2, the repeating request will be updated via applyCameraControls or similar
                applyCameraControls()
            }
            // Hide manual controls
            binding.manualControlsRoot?.visibility = View.GONE
            // Close manual panel if open
            if (binding.manualPanel?.visibility == View.VISIBLE) {
                binding.manualPanel?.visibility = View.GONE
                binding.touchOverlay?.visibility = View.GONE
                binding.manualTabs?.clearChecked()
                activeManualTab = null
            }
        } else {
            // Restore flash visibility if supported
            val hasFlash = try {
                if (currentLens?.useCamera2 == true) {
                    val c2Chars = camera2Manager.getCameraCharacteristics(currentLens!!.id)
                    c2Chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                } else {
                    camera?.cameraInfo?.hasFlashUnit() ?: false
                }
            } catch (e: Exception) { false }

            binding.flashButton?.visibility = if (hasFlash && showFlashButton) View.VISIBLE else View.GONE
            if (hasFlash && binding.flashButton != null) {
                updateFlashIcon(binding.flashButton!!)
            }

            // Restore manual controls if enabled in settings
            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val manualEnabled = prefs.getBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, false)
            binding.manualControlsRoot?.visibility = if (manualEnabled) View.VISIBLE else View.GONE
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
                        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                        val useCameraxFallback = prefs.getBoolean(SettingsFragment.KEY_USE_CAMERAX, false)

                        if (useCameraxFallback) {
                            Log.w(TAG, "Camera2 failed after retries, falling back to CameraX Auto")
                            currentLens = availableLenses.find { it.isLogicalAuto }
                            updateLensUI()
                            bindCameraUseCases()
                        } else {
                            Toast.makeText(requireContext(), "Camera hardware error: $error. Please restart the app.", Toast.LENGTH_LONG).show()
                        }
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

        val isRawSupportedLocally = map?.getOutputFormats()?.contains(android.graphics.ImageFormat.RAW_SENSOR) == true
        if (isRawSupportedLocally) {
            val rawSizes = map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
            val size = rawSizes?.maxByOrNull { it.width * it.height } ?: android.util.Size(4000, 3000)
            rawImageReader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.RAW_SENSOR, 8)
        } else {
            val jpegSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            val size = jpegSizes?.maxByOrNull { it.width * it.height } ?: android.util.Size(4000, 3000)
            rawImageReader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 8)
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
                                    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                                    if (timestamp != null) {
                                        captureResults[timestamp] = result
                                    }
                                    captureResultFlow.tryEmit(result)
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

        manager.captureMultiCamera(
            orientationDegrees = orientation,
            onResult = { result ->
                lifecycleScope.launch(Dispatchers.IO) {
                    processAndSaveMultiCameraResult(result, saveRaw)
                }
            },
            onError = { errorMsg ->
                Log.e(TAG, "Multi-camera capture error: $errorMsg")
                lifecycleScope.launch(Dispatchers.Main) {
                    processingSemaphore.release()
                    hideProcessingAnimation()
                    Toast.makeText(requireContext(), "Multi-camera capture failed: $errorMsg", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private suspend fun processAndSaveMultiCameraResult(
        result: top.maary.darkbag.camera.MultiCameraCaptureResult,
        saveRaw: Boolean
    ) {
        val appContext = requireContext().applicationContext
        val prefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
        val rawFolderUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)

        var primarySavedUri: Uri? = null

        val currentLog = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None") ?: "None"
        val currentLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, "None")?.substringBeforeLast(".") ?: "None"

        val currentEditConfig = top.maary.darkbag.models.EditConfig(
            log = currentLog,
            lut = currentLut
        )

        try {
            for (frame in result.frames) {
                val frameBaseName = "${result.baseName}_MULTI_${frame.lens.name}"

                // 1. Process and save JPEG
                if (frame.jpegData != null) {
                    val bmpFile = File(appContext.cacheDir, "temp_${frameBaseName}.jpg")
                    FileOutputStream(bmpFile).use { it.write(frame.jpegData) }

                    val savedUri = ImageSaver.saveProcessedImage(
                        context = appContext,
                        inputBitmap = null,
                        bmpPath = bmpFile.absolutePath,
                        rotationDegrees = 0, // already oriented by JPEG_ORIENTATION
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

                // 2. Process and save RAW if present and enabled
                if (saveRaw && frame.rawBytes != null) {
                    val rawFile = File(appContext.cacheDir, "temp_${frameBaseName}.dng")
                    FileOutputStream(rawFile).use { it.write(frame.rawBytes) }

                    ImageSaver.saveProcessedImage(
                        context = appContext,
                        inputBitmap = null,
                        bmpPath = null,
                        rotationDegrees = 0,
                        zoomFactor = 1.0f,
                        baseName = frameBaseName,
                        linearDngPath = rawFile.absolutePath,
                        saveJpg = false,
                        saveRaw = true,
                        rawFolderUri = rawFolderUri,
                        editConfig = currentEditConfig,
                        captureMetadata = frame.captureMetadata
                    )
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

            val burstSize = (prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "5") ?: "5").toIntOrNull() ?: 5

            if (isFrame1Trigger) {
                writeScopedHalfFrameStep(requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE), 1, captureStartTime, digitalGain = config.digitalGain, flareType = hfMetadata?.flareType ?: -1)
            }

            hdrPlusBurstHelper = HdrPlusBurst(frameCount = burstSize, onBurstComplete = { burstResult ->
                processHdrPlusBurst(burstResult, config.digitalGain, hfMetadata?.copy(digitalGain = config.digitalGain))
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
                request.set(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, config.iso)
                request.set(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, config.exposureTime)

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


    private fun getTargetOisMode(isHdrBurst: Boolean): Int {
        return if (isHdrBurst && !isHdrOisEnabledPref) {
            android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        } else {
            android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        }
    }

    private fun <B> applyStabilizationLogic(
        builder: B,
        isHdrBurst: Boolean,
        setOption: (B, android.hardware.camera2.CaptureRequest.Key<Int>, Int) -> Unit
    ) {
        if (isOisSupported) {
            val mode = getTargetOisMode(isHdrBurst)
            setOption(builder, android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode)
            // Explicitly disable EIS when OIS is in use to avoid conflicts
            setOption(builder, android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        } else {
            setOption(builder, android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
    }

    private fun applyStabilizationOptions(builder: CaptureRequestOptions.Builder, isHdrBurst: Boolean) {
        applyStabilizationLogic(builder, isHdrBurst) { b, key, value ->
            b.setCaptureRequestOption(key, value)
        }
    }

    private fun applyStabilizationToRequest(request: android.hardware.camera2.CaptureRequest.Builder, isHdrBurst: Boolean) {
        applyStabilizationLogic(request, isHdrBurst) { r, key, value ->
            r.set(key, value)
        }

        if (isOisSupported) {
            val mode = getTargetOisMode(isHdrBurst)
            Log.d(TAG, "OIS ${if(mode == android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) "enabled" else "disabled"} for ${if(isHdrBurst) "HDR+ Burst" else "Standard/Preview"}")
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
            request.set(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            request.set(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)

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
                        updateManualPanel()
                        updateTabColors()
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
            // Re-read enabled state inside the post to ensure we use the latest value
            val isEnabled = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
            isHalfFrameModeEnabled = isEnabled
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

                // Restore dynamic rotation for use cases
                val rotation = when (deviceOrientationDegrees) {
                    90 -> android.view.Surface.ROTATION_270
                    180 -> android.view.Surface.ROTATION_180
                    270 -> android.view.Surface.ROTATION_90
                    else -> android.view.Surface.ROTATION_0
                }
                preview?.targetRotation = rotation
                imageCapture?.targetRotation = rotation
                imageAnalyzer?.targetRotation = rotation

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

            // Force fixed rotation for half-frame mode
            val rotation = if (isTopBottom) {
                android.view.Surface.ROTATION_90 // Corresponds to 270 orientation (Right is Up)
            } else {
                android.view.Surface.ROTATION_0 // Corresponds to 0 orientation (Top is Up)
            }
            preview?.targetRotation = rotation
            imageCapture?.targetRotation = rotation
            imageAnalyzer?.targetRotation = rotation
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
        val currentMode = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
        val currentLayout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS)
        val isMultiCamPrefEnabled = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_MODE, false)
        val forceEnable = prefs.getBoolean(SettingsFragment.KEY_MULTI_CAMERA_FORCE_ENABLE, false)
        val isMultiCamSupported = top.maary.darkbag.utils.MultiCameraHelper.isMultiCameraSupported(requireContext(), forceEnable)
        val canUseMultiCam = (isMultiCamPrefEnabled || forceEnable) && isMultiCamSupported

        val (newMode, newLayout, newMultiCam) = when {
            !currentMode && !isMultiCameraModeActive -> {
                // Normal -> Side-by-side
                Triple(true, SettingsFragment.HALF_FRAME_LAYOUT_SBS, false)
            }
            currentMode && currentLayout == SettingsFragment.HALF_FRAME_LAYOUT_SBS -> {
                // Side-by-side -> Top-bottom
                Triple(true, SettingsFragment.HALF_FRAME_LAYOUT_TB, false)
            }
            currentMode && currentLayout == SettingsFragment.HALF_FRAME_LAYOUT_TB -> {
                if (canUseMultiCam) {
                    // Top-bottom -> Multi-Camera
                    Triple(false, SettingsFragment.HALF_FRAME_LAYOUT_SBS, true)
                } else {
                    // Top-bottom -> Normal
                    Triple(false, SettingsFragment.HALF_FRAME_LAYOUT_SBS, false)
                }
            }
            isMultiCameraModeActive -> {
                // Multi-Camera -> Normal
                Triple(false, SettingsFragment.HALF_FRAME_LAYOUT_SBS, false)
            }
            else -> Triple(false, SettingsFragment.HALF_FRAME_LAYOUT_SBS, false)
        }

        prefs.edit()
            .putBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, newMode)
            .putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, newLayout)
            .apply()

        val modeChanged = (currentMode != newMode) || (isMultiCameraModeActive != newMultiCam)
        isHalfFrameModeEnabled = newMode
        isMultiCameraModeActive = newMultiCam

        readScopedHalfFrameState(prefs, requireFileForStep1 = true)
        updateHalfFrameUI()
        updateShutterOrientation()
        updateMotionPhotoEncoder()
        _fragmentCameraBinding?.modeSwitchButton?.let { updateModeSwitchIcon(it) }

        if (modeChanged) {
            bindCameraUseCases()
        }
    }

    private fun updateModeSwitchIcon(btn: MaterialButton) {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
        val layout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUTS[0])

        val iconRes = when {
            isMultiCameraModeActive -> R.drawable.ic_mode_multi_camera
            !mode -> R.drawable.ic_mode_normal
            layout == SettingsFragment.HALF_FRAME_LAYOUTS[0] -> R.drawable.ic_mode_half_side
            else -> R.drawable.ic_mode_half_top
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

        val sensorToDisplay = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            (sensorOrientation + displayRotation) % 360
        } else {
            (sensorOrientation - displayRotation + 360) % 360
        }

        val matrix = Matrix()
        matrix.postRotate(-sensorToDisplay.toFloat(), 0.5f, 0.5f)
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
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

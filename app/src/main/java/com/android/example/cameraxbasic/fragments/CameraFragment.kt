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
package com.android.example.cameraxbasic.fragments

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
import com.android.example.cameraxbasic.MainApplication
import com.android.example.cameraxbasic.processor.ColorProcessor
import com.android.example.cameraxbasic.processor.HdrPlusExportWorker
import com.android.example.cameraxbasic.utils.ImageSaver
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.layout.WindowMetricsCalculator
import com.android.example.cameraxbasic.KEY_EVENT_ACTION
import com.android.example.cameraxbasic.KEY_EVENT_EXTRA
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.CameraUiContainerBinding
import com.android.example.cameraxbasic.databinding.FragmentCameraBinding
import com.android.example.cameraxbasic.utils.ANIMATION_FAST_MILLIS
import com.android.example.cameraxbasic.utils.ANIMATION_SLOW_MILLIS
import com.android.example.cameraxbasic.utils.MediaStoreUtils
import com.android.example.cameraxbasic.utils.LutManager
import com.android.example.cameraxbasic.utils.HalfFrameSessionStore
import com.android.example.cameraxbasic.utils.HalfFrameManager
import com.android.example.cameraxbasic.processor.LutSurfaceProcessor
import com.android.example.cameraxbasic.utils.ExposureUtils
import com.android.example.cameraxbasic.utils.simulateClick
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

    private lateinit var broadcastManager: LocalBroadcastManager

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
    private lateinit var cameraRepository: com.android.example.cameraxbasic.utils.CameraRepository
    private var availableLenses: List<com.android.example.cameraxbasic.utils.LensInfo> = emptyList()
    private var currentLens: com.android.example.cameraxbasic.utils.LensInfo? = null

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
    private var archProgress: com.android.example.cameraxbasic.utils.ArchProgressDrawable? = null

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

    private var processingCount = 0

    // Half-frame State
    private var isHalfFrameModeEnabled = false
    private var halfFrameStep = 0
    private var halfFrameTempPath: String? = null
    private var halfFrameBaseFinderWidth = 0
    private var halfFrameBaseFinderHeight = 0
    private lateinit var halfFrameSessionStore: HalfFrameSessionStore

    private var isOisSupported = false
    private var isHdrOisEnabledPref = true

    private fun scopedHalfFrameStepKey(prefs: SharedPreferences): String =
        halfFrameSessionStore.scopedStepKeyForCurrentProfile()

    private fun readScopedHalfFrameState(prefs: SharedPreferences, requireFileForStep1: Boolean = false) {
        val session = halfFrameSessionStore.readSession(strict = requireFileForStep1)
        halfFrameStep = session.step
        halfFrameTempPath = session.tempPath
    }

    private fun writeScopedHalfFrameStep(prefs: SharedPreferences, step: Int, captureTimeMillis: Long? = null) {
        halfFrameSessionStore.markStep(step, captureTimeMillis)
        halfFrameStep = step
        if (step == 0) {
            halfFrameTempPath = null
        }
    }

    private fun showProcessingAnimation() {
        lifecycleScope.launch(Dispatchers.Main) {
            processingCount++
            cameraUiContainerBinding?.processingProgress?.visibility = View.VISIBLE
            cameraUiContainerBinding?.photoViewContainer?.visibility = View.VISIBLE
            // Hide thumbnail image while processing if in half-frame mode
            if (isHalfFrameModeEnabled) {
                cameraUiContainerBinding?.photoViewButton?.visibility = View.INVISIBLE
            }
            Log.d(TAG, "showProcessingAnimation: count=$processingCount")
        }
    }

    private fun hideProcessingAnimation() {
        lifecycleScope.launch(Dispatchers.Main) {
            processingCount = (processingCount - 1).coerceAtLeast(0)
            if (processingCount == 0) {
                cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
                // Restore thumbnail visibility if not in the middle of a half-frame pair
                if (!isHalfFrameModeEnabled || halfFrameStep == 0) {
                    cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE
                    cameraUiContainerBinding?.photoViewButton?.alpha = 1f
                }
            }
            Log.d(TAG, "hideProcessingAnimation: count=$processingCount")
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
                    // Smoothly rotate shutter button and progress indicator to point its Arch top towards the new "up"
                    rotateShutter(-deviceOrientationDegrees.toFloat())
                }

                val rotation = when (orientation) {
                    in 45 until 135 -> android.view.Surface.ROTATION_270
                    in 135 until 225 -> android.view.Surface.ROTATION_180
                    in 225 until 315 -> android.view.Surface.ROTATION_90
                    else -> android.view.Surface.ROTATION_0
                }

                imageCapture?.targetRotation = rotation
                imageAnalyzer?.targetRotation = rotation
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
    private val processingSemaphore = kotlinx.coroutines.sync.Semaphore(2)

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
        val halfFrameMetadata: HalfFrameManager.Metadata? = null
    )

    /** Volume down button receiver used to trigger shutter */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
                }
            }
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
                preview?.targetRotation = view.display.rotation
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
        orientationEventListener.disable()
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
        if (cameraProvider != null || currentLens?.useCamera2 == true) {
            bindCameraUseCases()
        }

        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        readScopedHalfFrameState(prefs, requireFileForStep1 = true)
        updateHalfFrameUI()
        cameraUiContainerBinding?.modeSwitchButton?.let { updateModeSwitchIcon(it) }
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

        lutProcessor?.release()
        lutProcessor = null

        // Unregister the broadcast receivers and listeners
        broadcastManager.unregisterReceiver(volumeDownReceiver)
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
                photoViewButton.setImageDrawable(null)
                // In half-frame mode or during processing, we keep the container visible but hide the button
                if (isHalfFrameModeEnabled || processingCount > 0) {
                    photoViewButton.visibility = View.INVISIBLE
                } else {
                    photoViewButton.visibility = View.GONE
                }
                return@post
            }

            // In half-frame mode, only show the thumbnail if we are at step 0 (idle) and not processing
            if (isHalfFrameModeEnabled && (halfFrameStep != 0 || processingCount > 0)) {
                photoViewButton.visibility = View.INVISIBLE
                return@post
            }

            photoViewButton.visibility = View.VISIBLE
            photoViewButton.alpha = 1f
            // Remove thumbnail padding
            photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

            // Load thumbnail into circular button using Glide
            Glide.with(photoViewButton)
                .load(filename)
                .apply(RequestOptions.circleCropTransform())
                .into(photoViewButton)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

        // Initialize WindowMetricsCalculator to retrieve display metrics
        windowMetricsCalculator = WindowMetricsCalculator.getOrCreate()

        // Initialize MediaStoreUtils for fetching this app's images
        mediaStoreUtils = MediaStoreUtils(requireContext())

        lutManager = LutManager(requireContext())
        cameraRepository = com.android.example.cameraxbasic.utils.CameraRepository(requireContext())

        // Initialize Preferences
        val prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
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
        fragmentCameraBinding.viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isHalfFrameModeEnabled) {
                updateHalfFrameUI()
            }
        }

        // Initialize HDR+ Burst Helper
        hdrPlusBurstHelper = HdrPlusBurst(
            frameCount = 3,
            onBurstComplete = { frames ->
                processHdrPlusBurst(frames, 1.0f)
            }
        )

        // Listen for Half-frame events
        viewLifecycleOwner.lifecycleScope.launch {
            ColorProcessor.halfFrameFlow.collect { step ->
                withContext(Dispatchers.Main) {
                    if (step == 1) {
                        hideProcessingAnimation()
                    } else {
                        // Full capture complete (Frame 2 background stitching done)
                        hideProcessingAnimation() // Final cleanup
                    }
                }
            }
        }

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
                                prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, event.targetUri).apply()
                                setGalleryThumbnail(event.targetUri)
                                hideProcessingAnimation() // Hide when final stitched result is ready
                            }
                        } else {
                             Log.w(TAG, "Received save event for ${event.baseName} without targetUri.")
                             withContext(Dispatchers.Main) { hideProcessingAnimation() }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Background UI update failed for ${event.baseName}", e)
                        withContext(Dispatchers.Main) { hideProcessingAnimation() }
                    }
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
                            // If not half-frame, we hide now. If half-frame, wait for final stitched result in backgroundSaveFlow.
                            if (!isHalfFrameModeEnabled) {
                                hideProcessingAnimation()
                            }
                        }
                    }
                }
            }
        }

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {

            // Keep track of the display in which this view is attached
            displayId = fragmentCameraBinding.viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()

            // Initialize LUT Processor early to be ready for any engine
            if (lutProcessor == null) {
                lutProcessor = LutSurfaceProcessor()
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
        var updatedLens: com.android.example.cameraxbasic.utils.LensInfo? = null
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
            val default1xFocal = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, "24mm")

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
                    updatedLens = presets1x.find { it.name == default1xFocal } ?: found
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
        lensFacing = prefs.getInt(KEY_LENS_FACING, CameraSelector.LENS_FACING_BACK)

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
        fragmentCameraBinding.viewFinder.surfaceTextureListener =
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
        if (fragmentCameraBinding.viewFinder.isAvailable) {
            fragmentCameraBinding.viewFinder.surfaceTexture?.let { st ->
                proc.setOutputSurface(
                    Surface(st),
                    fragmentCameraBinding.viewFinder.width,
                    fragmentCameraBinding.viewFinder.height
                )
            }
        }
        updateLiveLut() // Ensure LUT is loaded
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

        // Force 4:3 Aspect Ratio for all engines
        val metrics = windowMetricsCalculator.computeCurrentWindowMetrics(requireActivity()).bounds
        if (metrics.width() < metrics.height()) {
            fragmentCameraBinding.viewFinder.setAspectRatio(3, 4)
        } else {
            fragmentCameraBinding.viewFinder.setAspectRatio(4, 3)
        }

        // Decide Engine: Camera2 (Hard Switch) or CameraX
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val useCameraxFallback = prefs.getBoolean(SettingsFragment.KEY_USE_CAMERAX, false)

        if (currentLens?.useCamera2 == true && !useCameraxFallback) {
            Log.d(TAG, "Switching to Camera2 Engine for lens: ${currentLens?.name}")

            // Clean up CameraX if it was active
            cameraProvider?.unbindAll()
            camera = null

            // Ensure UI is updated before opening Camera2
            initLensControls()

            // Check Flash for Camera2
            try {
                val c2Chars = camera2Manager.getCameraCharacteristics(currentLens!!.id)
                val hasFlash = c2Chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                cameraUiContainerBinding?.flashButton?.visibility = if (hasFlash && !isHdrPlusEnabled) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check flash for Camera2", e)
            }

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
                Toast.makeText(context, "Failed to bind to selected lens", Toast.LENGTH_SHORT).show()
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
                // Check Flash Availability
                if (cam.cameraInfo.hasFlashUnit() && !isHdrPlusEnabled) {
                    cameraUiContainerBinding?.flashButton?.visibility = View.VISIBLE
                } else {
                    cameraUiContainerBinding?.flashButton?.visibility = View.GONE
                }

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

        // Remove all views except view_finder and focus_ring to avoid duplicates when re-inflating <merge>
        val viewsToRemove = mutableListOf<View>()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.id != R.id.view_finder && child.id != R.id.focus_ring) {
                viewsToRemove.add(child)
            }
        }
        viewsToRemove.forEach { root.removeView(it) }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            root
        )

        // Recompute half-frame base size after UI reinflation / configuration changes.
        // We reset the viewfinder to its default constraints to ensure the next layout pass
        // allows updateHalfFrameUI to capture the correct full dimensions.
        halfFrameBaseFinderWidth = 0
        halfFrameBaseFinderHeight = 0
        (fragmentCameraBinding.viewFinder.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
            if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT || lp.height != 0) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.height = 0
                fragmentCameraBinding.viewFinder.layoutParams = lp
            }
        }

        val colorPrimary = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorPrimary, Color.YELLOW)
        archProgress = com.android.example.cameraxbasic.utils.ArchProgressDrawable().apply {
            setColor(colorPrimary)
        }
        cameraUiContainerBinding?.captureProgress?.setImageDrawable(archProgress)

        // Reset rotation of shutter on UI update
        cameraUiContainerBinding?.cameraCaptureButton?.rotation = -deviceOrientationDegrees.toFloat()
        cameraUiContainerBinding?.captureProgress?.rotation = -deviceOrientationDegrees.toFloat()

        // In the background, load latest photo taken (if any) for gallery thumbnail
        lifecycleScope.launch {
            val thumbnailUri = mediaStoreUtils.getLatestAppImage(requireContext())
            thumbnailUri?.let {
                setGalleryThumbnail(it.toString())
            }
        }

        // Apply WindowInsets to UI Container to avoid system bar overlap
        cameraUiContainerBinding?.root?.let { rootView ->
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.mandatorySystemGestures()
                )
                view.updatePadding(
                    left = insets.left,
                    top = insets.top,
                    right = insets.right,
                    bottom = insets.bottom
                )

                // Update Viewfinder and Lens Group constraints
                val uiBinding = cameraUiContainerBinding
                val vfBinding = _fragmentCameraBinding
                if (uiBinding != null && vfBinding != null) {
                    val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
                    val root = vfBinding.root as androidx.constraintlayout.widget.ConstraintLayout
                    constraintSet.clone(root)

                    val vfId = vfBinding.viewFinder.id
                    val topId = uiBinding.topRightControls?.id
                    val bottomId = uiBinding.bottomIslandCard?.id
                    val lensRowId = uiBinding.lensControlRow?.id
                    val manualId = uiBinding.manualControlsRoot?.id

                    if (topId != null && bottomId != null) {
                        // Center Viewfinder between top bar and manual controls (or bottom island if manual is GONE)
                        val bottomAnchorId = if (manualId != null) {
                            // Ensure Manual Controls are constrained to the Bottom Island
                            constraintSet.connect(manualId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, bottomId, androidx.constraintlayout.widget.ConstraintSet.TOP)
                            manualId
                        } else {
                            bottomId
                        }

                        constraintSet.constrainHeight(vfId, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT)
                        constraintSet.constrainDefaultHeight(vfId, androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT_WRAP)
                        constraintSet.connect(vfId, androidx.constraintlayout.widget.ConstraintSet.TOP, topId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
                        constraintSet.connect(vfId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, bottomAnchorId, androidx.constraintlayout.widget.ConstraintSet.TOP)
                        constraintSet.setVerticalBias(vfId, 0.5f)

                        // Constrain Lens Group Row to the bottom of the Viewfinder (above its bottom edge)
                        if (lensRowId != null) {
                            val marginXsmall = resources.getDimensionPixelSize(R.dimen.margin_xsmall)
                            constraintSet.connect(lensRowId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, vfId, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, marginXsmall)
                            constraintSet.connect(lensRowId, androidx.constraintlayout.widget.ConstraintSet.START, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.START)
                            constraintSet.connect(lensRowId, androidx.constraintlayout.widget.ConstraintSet.END, androidx.constraintlayout.widget.ConstraintSet.PARENT_ID, androidx.constraintlayout.widget.ConstraintSet.END)
                        }
                    }

                    constraintSet.applyTo(root)
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

        // Flash Button
        cameraUiContainerBinding?.flashButton?.let { btn ->
            updateFlashIcon(btn)
            btn.setOnClickListener {
                isFlashEnabled = !isFlashEnabled
                requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(SettingsFragment.KEY_FLASH_MODE, isFlashEnabled).apply()
                updateFlashIcon(btn)
                imageCapture?.flashMode = if (isFlashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
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
                Toast.makeText(requireContext(), "Half-frame Reset", Toast.LENGTH_SHORT).show()
                true
            } else {
                false
            }
        }

        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {
            if (isBurstActive) return@setOnClickListener

            // Check concurrency limit
            if (!processingSemaphore.tryAcquire()) {
                Toast.makeText(
                    requireContext(),
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

            if (isFrame1Trigger) {
                halfFrameSessionStore.clearCurrentSession(deleteTempFile = false)
                writeScopedHalfFrameStep(prefs, 1, System.currentTimeMillis())
                // Animate after shutter blackout
                fragmentCameraBinding.viewFinder.postDelayed({
                    updateHalfFrameUI(animate = true)
                }, 100)
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
                    frame1CaptureTime = if (isFrame2Trigger) session.captureTimeMillis else 0L
                )
            }

            if (isFrame2Trigger) {
                writeScopedHalfFrameStep(prefs, 0)
                // Animate after shutter blackout
                fragmentCameraBinding.viewFinder.postDelayed({
                    updateHalfFrameUI(animate = true)
                }, 100)

                showProcessingAnimation() // Immediate indicator on click for second frame
                cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE // Show thumbnail container for progress indicator
                setGalleryThumbnail(null) // Clear previous thumbnail and show placeholder/indicator
            }

            if (currentLens?.useCamera2 == true) {
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
        cameraUiContainerBinding?.cameraSwitchButtonAlt?.let {

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
        cameraUiContainerBinding?.modeSwitchButton?.let { btn ->
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

        // Listener for button used to view the most recent photo
        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            // Only navigate when the gallery has photos
            lifecycleScope.launch {
                val uri = mediaStoreUtils.getLatestAppImage(requireContext())
                if (uri != null) {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(
                            requireContext(),
                            "No gallery app installed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            cameraUiContainerBinding?.cameraSwitchButtonAlt?.isEnabled =
                hasBackCamera() && hasFrontCamera()
            cameraUiContainerBinding?.cameraSwitchButtonAlt?.visibility = View.VISIBLE
        } catch (exception: CameraInfoUnavailableException) {
            cameraUiContainerBinding?.cameraSwitchButtonAlt?.isEnabled = false
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
                val dngName =
                    SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())

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

                val saveTiff = prefs.getBoolean(SettingsFragment.KEY_SAVE_TIFF, false)
                val saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true)
                val saveRaw = prefs.getBoolean(SettingsFragment.KEY_SAVE_RAW, true)
                val jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
                val tiffFolderUri = prefs.getString(SettingsFragment.KEY_TIFF_STORAGE_URI, null)
                val rawFolderUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)

                val tempRawFile = File(context.cacheDir, "$dngName.tmp.raw")
                val tempJpgFile = File(context.cacheDir, "$dngName.tmp.jpg")
                val fullResJpgFile = File(context.cacheDir, "${dngName}_full.jpg")
                val tiffFile = File(context.cacheDir, "$dngName.tiff")
                val linearDngFile = File(context.cacheDir, "${dngName}_linear.dng")
                val bayerDngFile = File(context.cacheDir, "${dngName}_bayer.dng")
                var dngWritten = false
                if (saveRaw) {
                    try {
                        val dngCreator = android.hardware.camera2.DngCreator(chars, captureResult)

                        // Map rotation to DngCreator orientation
                        val dngOrientation = when (image.combinedOrientation) {
                            90 -> ExifInterface.ORIENTATION_ROTATE_90
                            180 -> ExifInterface.ORIENTATION_ROTATE_180
                            270 -> ExifInterface.ORIENTATION_ROTATE_270
                            else -> ExifInterface.ORIENTATION_NORMAL
                        }
                        dngCreator.setOrientation(dngOrientation)

                        FileOutputStream(bayerDngFile).use { out ->
                            dngCreator.writeByteBuffer(out, Size(image.width, image.height), image.data, 0)
                        }
                        dngWritten = true
                        Log.d(TAG, "DNG saved using DngCreator: ${bayerDngFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save DNG using DngCreator", e)
                    }
                }

                val iso = captureResult.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val exposureTime = captureResult.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
                val fNumber = captureResult.get(android.hardware.camera2.CaptureResult.LENS_APERTURE) ?: 1.8f
                val focalLength = captureResult.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH) ?: 0.0f
                val captureTime = System.currentTimeMillis()

                val debugStats = LongArray(15)
                val mirror = shouldMirror

                // 3. JNI Halide Processing
                val result = ColorProcessor.processSingleFrameRaw(
                    bayerBuffer = image.data,
                    width = image.width,
                    height = image.height,
                    orientation = image.combinedOrientation,
                    whiteLevel = whiteLevel,
                    blackLevelPattern = blackLevelPattern,
                    lensShadingMap = lensShadingMapData,
                    lensShadingRows = lensShadingRows,
                    lensShadingCols = lensShadingCols,
                    whiteBalance = wb,
                    ccm = ccm,
                    cfaPattern = cfa,
                    iso = iso,
                    exposureTime = exposureTime,
                    fNumber = fNumber,
                    focalLength = focalLength,
                    captureTimeMillis = captureTime,
                    targetLog = targetLogIndex,
                    lutPath = nativeLutPath,
                    outputTiffPath = null,
                    outputJpgPath = if (saveJpg) tempJpgFile.absolutePath else null, // Fast JPG
                    outputDngPath = null,
                    digitalGain = 1.0f,
                    debugStats = debugStats,
                    outputBitmap = null,
                    tempRawPath = tempRawFile.absolutePath,
                    zoomFactor = image.zoomRatio,
                    mirror = mirror
                )

                timing?.jniDone = System.currentTimeMillis()

                if (result < 0) throw RuntimeException("processSingleFrameRaw failed: $result")

                // 4. Fast Output Feedback (Thumbnail)
                val fastOutputUri = ImageSaver.saveProcessedImage(
                    context = context,
                    inputBitmap = null,
                    bmpPath = if (saveJpg) tempJpgFile.absolutePath else null,
                    rotationDegrees = 0,
                    zoomFactor = 1.0f,
                    baseName = dngName,
                    linearDngPath = if (dngWritten) bayerDngFile.absolutePath else null,
                    tiffPath = null,
                    saveJpg = saveJpg,
                    saveTiff = false,
                    saveRaw = saveRaw,
                    jpgFolderUri = jpgFolderUri,
                    rawFolderUri = rawFolderUri,
                    mirror = false,
                    isFastPath = true,
                    halfFrameMetadata = image.halfFrameMetadata
                )

                timing?.firstOutputWritten = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    if (fastOutputUri != null) {
                        prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, fastOutputUri.toString()).apply()
                        setGalleryThumbnail(fastOutputUri.toString())
                    } else if (isHalfFrameModeEnabled && prefs.getInt(scopedHalfFrameStepKey(prefs), 0) == 1) {
                        setGalleryThumbnail(null)
                    }
                }

                // 5. Enqueue HQ Processing
                val workData = androidx.work.Data.Builder()
                    .putString("tempRawPath", tempRawFile.absolutePath)
                if (activeArray != null) workData.putIntArray("activeArray", activeArray)
                workData.putInt("width", image.width)
                    .putInt("height", image.height)
                    .putInt("orientation", image.combinedOrientation)
                    .putFloat("digitalGain", 1.0f)
                    .putInt("targetLog", targetLogIndex)
                    .putString("lutPath", nativeLutPath)
                    .putString("tiffPath", if (saveTiff) tiffFile.absolutePath else null)
                    .putString("jpgPath", if (saveJpg) fullResJpgFile.absolutePath else null)
                    .putString("targetUri", fastOutputUri?.toString())
                    .putFloat("zoomFactor", image.zoomRatio)
                    .putInt("iso", iso)
                    .putLong("exposureTime", exposureTime)
                    .putFloat("fNumber", fNumber)
                    .putFloat("focalLength", focalLength)
                    .putLong("captureTimeMillis", captureTime)
                    .putFloatArray("ccm", ccm)
                    .putFloatArray("whiteBalance", wb)
                    .putString("baseName", dngName)
                    .putBoolean("saveTiff", saveTiff)
                    .putBoolean("saveJpg", saveJpg)
                    .putBoolean("saveRaw", saveRaw)
                    .putString("jpgFolderUri", jpgFolderUri)
                    .putString("tiffFolderUri", tiffFolderUri)
                    .putString("rawFolderUri", rawFolderUri)
                    .putBoolean("mirror", mirror)

                image.halfFrameMetadata?.let { hf ->
                    workData.putString("hfProfile", hf.profile)
                    workData.putBoolean("hfDateStamp", hf.dateStamp)
                    workData.putLong("hfCaptureTime", hf.captureTimeMillis)
                    hf.frame1BaseName?.let { workData.putString("hfF1Base", it) }
                    hf.frame1TempPath?.let { workData.putString("hfF1Path", it) }
                    workData.putLong("hfF1Time", hf.frame1CaptureTime)
                }

                val workRequest = androidx.work.OneTimeWorkRequestBuilder<HdrPlusExportWorker>()
                    .setInputData(workData.build())
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueue(workRequest)

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
                    com.android.example.cameraxbasic.utils.DebugLogManager.addLog(report)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in background processing", e)
            }
        }

    private fun setupTapToFocus() {
        fragmentCameraBinding.viewFinder.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                if (currentLens?.useCamera2 == true) {
                     triggerTapToFocusCamera2(event.x, event.y)
                } else {
                    val cameraInfo = camera?.cameraInfo ?: return@setOnTouchListener true
                    val width = fragmentCameraBinding.viewFinder.width.toFloat()
                    val height = fragmentCameraBinding.viewFinder.height.toFloat()

                    val factory = DisplayOrientedMeteringPointFactory(
                        fragmentCameraBinding.viewFinder.display,
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
                    fragmentCameraBinding.viewFinder.width,
                    fragmentCameraBinding.viewFinder.height,
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
        val focusRing = fragmentCameraBinding.focusRing
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
                        Toast.makeText(
                            requireContext(),
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
        val binding = cameraUiContainerBinding ?: return
        val container = binding.lensControlsContainer ?: return
        val row = binding.lensControlRow ?: return

        // Ensure the row containing the switch button is always visible
        row.visibility = View.VISIBLE

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
            !it.sensorId.contains(com.android.example.cameraxbasic.utils.CameraRepository.VIRTUAL_TELE_2X_SUFFIX)
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
                        val isAlreadyIn1xPresets = oldLens != null && oldLens.id == lens.id &&
                                (oldLens.name == "24mm" || oldLens.name == "28mm" || oldLens.name == "35mm")

                        val isLargestTele = largestTele != null && lens.sensorId == largestTele.sensorId
                        val isAlreadyInTelePresets = oldLens != null && (oldLens.sensorId == lens.sensorId || oldLens.sensorId == "${lens.sensorId}${com.android.example.cameraxbasic.utils.CameraRepository.VIRTUAL_TELE_2X_SUFFIX}")

                        if (is1x && isAlreadyIn1xPresets) {
                            val presets1x = cameraRepository.get1xPresets(lens)
                            val currentName = oldLens?.name ?: "24mm"
                            val nextIndex = when (currentName) {
                                "24mm" -> presets1x.indexOfFirst { it.name == "28mm" }.takeIf { it != -1 } ?: 0
                                "28mm" -> presets1x.indexOfFirst { it.name == "35mm" }.takeIf { it != -1 } ?: 0
                                "35mm" -> 0
                                else -> 0
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
                            val default1xFocal = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, "24mm")
                            val presets1x = cameraRepository.get1xPresets(lens)
                            currentLens = presets1x.find { it.name == default1xFocal } ?: lens
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
        currentLens?.let {
            prefs.edit().putString(KEY_SELECTED_LENS_ID, it.sensorId).apply()
        }

        val binding = cameraUiContainerBinding ?: return

        // Ensure the row containing the switch button is always visible
        binding.lensControlRow?.visibility = View.VISIBLE

        val container = binding.lensControlsContainer ?: return

        val colorPrimary = MaterialColors.getColor(container, com.google.android.material.R.attr.colorPrimary)
        val colorOnSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)

        val isBackCamera = lensFacing == CameraSelector.LENS_FACING_BACK
        val largestTele = if (isBackCamera) {
            availableLenses.filter { it.multiplier > 1.05f && !it.isZoomPreset }.maxByOrNull { it.multiplier }
        } else null

        for (i in 0 until container.childCount) {
            val btn = container.getChildAt(i) as? com.google.android.material.button.MaterialButton
            val lens = btn?.tag as? com.android.example.cameraxbasic.utils.LensInfo
            if (btn != null && lens != null) {
                val isActive = lens.sensorId == currentLens?.sensorId ||
                              (lens.multiplier in 0.95f..1.05f && currentLens?.id == lens.id && !currentLens!!.sensorId.contains("virtual")) ||
                              (largestTele != null && lens.sensorId == largestTele.sensorId && currentLens?.sensorId == "${largestTele.sensorId}${com.android.example.cameraxbasic.utils.CameraRepository.VIRTUAL_TELE_2X_SUFFIX}")

                if (isActive) {
                    if (lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset) {
                        btn.text = transientLensLabel ?: String.format("%.1fx", currentLens?.multiplier ?: 1.0f)
                    } else if (largestTele != null && lens.sensorId == largestTele.sensorId) {
                        btn.text = String.format("%.1fx", currentLens?.multiplier ?: lens.multiplier)
                    }

                    btn.setTextColor(colorPrimary)
                    btn.strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke_small)
                    btn.strokeColor = android.content.res.ColorStateList.valueOf(colorPrimary)
                    btn.setBackgroundColor(MaterialColors.layer(
                        MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSurface),
                        colorPrimary,
                        0.1f
                    ))
                } else {
                    btn.setTextColor(colorOnSurface)
                    btn.strokeWidth = 0

                    if (lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset) {
                        val default1xFocal = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, "24mm")
                        val presets1x = cameraRepository.get1xPresets(lens)
                        val defaultPreset = presets1x.find { it.name == default1xFocal } ?: lens
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

        binding.lensControlsCard?.visibility = if (container.childCount > 1) View.VISIBLE else View.GONE
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

    private fun getCombinedOrientation(): Int {
        val sensorOrientation = try {
            val lens = currentLens
            val targetId = lens?.id ?: if (lensFacing == CameraSelector.LENS_FACING_BACK) "0" else "1"

            camera2Manager.getCameraCharacteristics(targetId)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (e: Exception) { 0 }

        val combined = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            (sensorOrientation - deviceOrientationDegrees + 360) % 360
        } else {
            (sensorOrientation + deviceOrientationDegrees) % 360
        }
        Log.d(TAG, "getCombinedOrientation: sensor=$sensorOrientation, device=$deviceOrientationDegrees, facing=$lensFacing -> combined=$combined")
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
            val colorPrimary = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorPrimary)

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
        btn.setIconResource(if (isFlashEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
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
        private const val TAG = "CameraXBasic"
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
        halfFrameMetadata: HalfFrameManager.Metadata? = null
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

                val uri = ImageSaver.saveProcessedImage(
                    context = appContext,
                    inputBitmap = null,
                    bmpPath = bmpFile.absolutePath,
                    rotationDegrees = rotationDegrees,
                    zoomFactor = zoomFactor,
                    baseName = name,
                    linearDngPath = null,
                    tiffPath = null,
                    saveJpg = true,
                    saveTiff = false,
                    jpgFolderUri = jpgFolderUri,
                    mirror = mirror,
                    halfFrameMetadata = halfFrameMetadata
                )
                withContext(Dispatchers.Main) {
                    val uiPrefs = appContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    if (uri != null) {
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

                            val holder = copyImageToHolder(
                                image, currentZoom, getCombinedOrientation(), currentLens?.physicalId, hfMetadata
                            ).copy(timing = timing)
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
                                Toast.makeText(
                                    requireContext(),
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
                        saveJpegFallback(data, rotation, currentZoom, hfMetadata)
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

                hdrPlusBurstHelper = HdrPlusBurst(
                    frameCount = burstSize,
                    onBurstComplete = { frames ->
                        processHdrPlusBurst(frames, config.digitalGain, hfMetadata)
                    }
                )

                archProgress?.setProgress(0f)
                cameraUiContainerBinding?.captureProgress?.visibility = View.VISIBLE
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false

                showShutterBlackout()

                Toast.makeText(
                    requireContext(),
                    "Capturing HDR+ Burst ($burstSize frames)...",
                    Toast.LENGTH_SHORT
                ).show()

                Log.d(TAG, "Starting HDR+ Burst (Pipelined, $burstSize frames)")

                for (i in 0 until burstSize) {
                    captureBurstFrame(imageCapture, burstSize, i, isFrame1Trigger)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HDR+ burst", e)
                Toast.makeText(
                    requireContext(),
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
                        archProgress?.setProgress(progress)

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
                                Toast.makeText(requireContext(), "Burst failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(requireContext(), "Burst failed at frame ${currentFrame + 1}", Toast.LENGTH_SHORT).show()
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
        frames: List<HdrFrame>,
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
                Log.d(TAG, "processHdrPlusBurst started with ${frames.size} frames. DigitalGain=$digitalGain")

                val width = frames[0].width
                val height = frames[0].height
                val rotationDegrees = frames[0].rotationDegrees

                val buffers = frames.map { it.buffer!! }.toTypedArray()

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

                val dngName = SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis()) + "_HDRPLUS"
                val saveTiff = prefs.getBoolean(SettingsFragment.KEY_SAVE_TIFF, false)
                val saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true)
                val saveRaw = prefs.getBoolean(SettingsFragment.KEY_SAVE_RAW, true)
                val jpgFolderUri = prefs.getString(SettingsFragment.KEY_JPG_STORAGE_URI, null)
                val tiffFolderUri = prefs.getString(SettingsFragment.KEY_TIFF_STORAGE_URI, null)
                val rawFolderUri = prefs.getString(SettingsFragment.KEY_RAW_STORAGE_URI, null)
                val hqBackgroundExport = prefs.getBoolean(SettingsFragment.KEY_HQ_BACKGROUND_EXPORT, false)

                val tiffFile = File(context.cacheDir, "$dngName.tiff")
                val tiffPath = if(saveTiff) tiffFile.absolutePath else null
                val tempRawFile = File(context.cacheDir, "$dngName.tmp.raw")
                val tempJpgFile = File(context.cacheDir, "$dngName.tmp.jpg")
                val fullResJpgFile = File(context.cacheDir, "${dngName}_full.jpg")

                val linearDngFile = File(context.cacheDir, "${dngName}_linear.dng")
                val linearDngPath = linearDngFile.absolutePath

                Log.d(TAG, "Output Paths: TIFF=$tiffPath, DNG=$linearDngPath")

                val jniStartTime = System.currentTimeMillis()
                buffers.forEach { it.rewind() }

                val debugStats = LongArray(15)

                // Initial JNI call produces:
                // 1) intermediate linear RAW buffer (tempRawPath) for the ExportWorker,
                // 2) optional fast downsampled JPEG (tempJpgPath) for immediate gallery update.
                val mirror = shouldMirror

                val ret = ColorProcessor.processHdrPlus(
                    buffers,
                    width, height,
                    combinedOrientation,
                    whiteLevel, blackLevelPattern,
                    lensShadingMapData, lensShadingRows, lensShadingCols, useSensorColorMatrix,
                    wb, ccm, ccmAlt, exportMatrixAB, cfa,
                    iso, exposureTime, fNumber, focalLength, captureTime,
                    targetLogIndex,
                    nativeLutPath,
                    null, // outputTiffPath
                    if (saveJpg) tempJpgFile.absolutePath else null, // outputJpgPath (fast preview)
                    null, // outputDngPath (finalize in background)
                    digitalGain,
                    debugStats,
                    null, // outputBitmap
                    tempRawFile.absolutePath,
                    currentZoom,
                    mirror
                )

                val jniEndTime = System.currentTimeMillis()
                Log.d(TAG, "JNI processHdrPlus returned $ret in ${jniEndTime - jniStartTime}ms")

                if (ret == 0) {
                    isHdrPlusSuccess = true

                    val saveStartTime = System.currentTimeMillis()

                    val mirror = shouldMirror

                    val fastJpegUri = if (saveJpg) {
                        ImageSaver.saveProcessedImage(
                            context = context,
                            inputBitmap = null,
                            bmpPath = tempJpgFile.absolutePath,
                            rotationDegrees = 0, // Rotation already handled in JNI
                            zoomFactor = 1.0f, // Zoom already handled in JNI
                            baseName = dngName,
                            linearDngPath = null,
                            tiffPath = null,
                            saveJpg = true,
                            saveTiff = false,
                            jpgFolderUri = jpgFolderUri,
                            mirror = false, // Mirroring already handled in JNI
                            isFastPath = true,
                            halfFrameMetadata = hfMetadata
                        )
                    } else {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        if (fastJpegUri != null) {
                            prefs.edit().putString(SettingsFragment.KEY_LAST_CAPTURE_URI, fastJpegUri.toString()).apply()
                            setGalleryThumbnail(fastJpegUri.toString())
                        } else if (isHalfFrameModeEnabled && prefs.getInt(scopedHalfFrameStepKey(prefs), 0) == 1) {
                            setGalleryThumbnail(null)
                        }
                        Toast.makeText(context, "HDR+ Saved!", Toast.LENGTH_SHORT).show()
                        if (!isHalfFrameModeEnabled) {
                            hideProcessingAnimation()
                        }
                    }

                    val workData = androidx.work.Data.Builder()
                        .putString("tempRawPath", tempRawFile.absolutePath)
                    if (activeArray != null) workData.putIntArray("activeArray", activeArray)
                    workData.putInt("width", width)
                        .putInt("height", height)
                        .putInt("orientation", combinedOrientation)
                        .putFloat("digitalGain", digitalGain)
                        .putInt("targetLog", targetLogIndex)
                        .putString("lutPath", nativeLutPath)
                        .putString("tiffPath", tiffPath)
                        .putString("jpgPath", if (saveJpg) fullResJpgFile.absolutePath else null)
                        .putString("targetUri", fastJpegUri?.toString()) // Replace fast JPEG in place
                        .putFloat("zoomFactor", currentZoom)
                        .putString("dngPath", if (saveRaw) linearDngPath else null)
                        .putInt("iso", (iso).toInt())
                        .putLong("exposureTime", exposureTime)
                        .putFloat("fNumber", fNumber)
                        .putFloat("focalLength", focalLength)
                        .putLong("captureTimeMillis", captureTime)
                        .putFloatArray("ccm", ccm)
                        .putFloatArray("whiteBalance", wb)
                        .putString("baseName", dngName)
                        .putBoolean("saveTiff", saveTiff)
                        .putBoolean("saveJpg", saveJpg)
                        .putBoolean("saveRaw", saveRaw)
                        .putString("jpgFolderUri", jpgFolderUri)
                        .putString("tiffFolderUri", tiffFolderUri)
                        .putString("rawFolderUri", rawFolderUri)
                        .putBoolean("mirror", mirror)

                    hfMetadata?.let { hf ->
                        workData.putString("hfProfile", hf.profile)
                        workData.putBoolean("hfDateStamp", hf.dateStamp)
                        workData.putLong("hfCaptureTime", hf.captureTimeMillis)
                        hf.frame1BaseName?.let { workData.putString("hfF1Base", it) }
                        hf.frame1TempPath?.let { workData.putString("hfF1Path", it) }
                        workData.putLong("hfF1Time", hf.frame1CaptureTime)
                    }

                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<HdrPlusExportWorker>()
                        .setInputData(workData.build())
                        .build()
                    androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
                    val saveEndTime = System.currentTimeMillis()

                    // Log Statistics
                    val totalTime = saveEndTime - startTime
                    val captureTime = captureEndTime - startTime
                    val waitTime = jniStartTime - captureEndTime
                    val jniTime = jniEndTime - jniStartTime
                    val halideTime = debugStats[0]
                    val copyTime = debugStats[1]
                    val postTime = debugStats[2]
                    val dngEncodeTime = debugStats[3]
                    val nativeSaveTime = debugStats[4]
                    val dngWaitTime = debugStats[5]
                    val nativeTotalTime = debugStats[6]
                    val saveTime = saveEndTime - saveStartTime

                    val logMsg = """
                        [Total: ${totalTime}ms]
                        Capture: ${captureTime}ms
                        Wait: ${waitTime}ms
                        JNI (Total): ${jniTime}ms
                          - Native Total: ${nativeTotalTime}ms
                          - JNI Prep: ${debugStats[12]}ms
                          - Copy: ${copyTime}ms
                          - Halide: ${halideTime}ms
                            * Align: ${debugStats[7]}ms
                            * Merge: ${debugStats[8]}ms
                            * BlackWhite: ${debugStats[13]}ms
                            * WB: ${debugStats[14]}ms
                            * Demosaic: ${debugStats[9]}ms
                            * Denoise: ${debugStats[10]}ms
                            * sRGB: ${debugStats[11]}ms
                          - Post: ${postTime}ms
                          - DNG Encode: ${dngEncodeTime}ms
                          - Save(Log/TIFF/BMP): ${nativeSaveTime}ms
                          - DNG Wait(get): ${dngWaitTime}ms
                        Save (IO/Compress): ${saveTime}ms
                        HQ Export Mode: ${if (hqBackgroundExport) "Background" else "Inline"}
                    """.trimIndent()

                    Log.i(TAG, logMsg)
                    com.android.example.cameraxbasic.utils.DebugLogManager.addLog(logMsg)

                } else {
                    throw RuntimeException("JNI processing returned error code: $ret")
                }

            } catch (e: Exception) {
                Log.e(TAG, "HDR+ processing failed, falling back to single shot", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "HDR+ failed, saving single frame...", Toast.LENGTH_SHORT).show()
                }

                if (frames.isNotEmpty()) {
                    try {
                        val firstFrame = frames[0]
                        val data = ByteBuffer.allocateDirect(firstFrame.buffer!!.remaining())
                        firstFrame.buffer!!.rewind()
                        data.put(firstFrame.buffer!!)
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
                frames.forEach {
                    HdrPlusBurst.releaseBuffer(it.buffer)
                    it.close()
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
        if (isHdrPlusEnabled) {
            // Hide and disable flash
            binding.flashButton?.visibility = View.GONE
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

            binding.flashButton?.visibility = if (hasFlash) View.VISIBLE else View.GONE

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
                            Toast.makeText(context, "Camera hardware error: $error. Please restart the app.", Toast.LENGTH_LONG).show()
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
                        val holder = copyAndroidImageToHolder(image, currentZoom, getCombinedOrientation(), currentLens?.id, hfMetadata).copy(timing = timing)
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
                        saveJpegFallback(data, 0, currentZoom, hfMetadata) // Rotation handled by C2 JPEG_ORIENTATION
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

            hdrPlusBurstHelper = HdrPlusBurst(frameCount = burstSize, onBurstComplete = { frames ->
                processHdrPlusBurst(frames, config.digitalGain, hfMetadata)
            })

            lifecycleScope.launch(Dispatchers.Main) {
                archProgress?.setProgress(0f)
                cameraUiContainerBinding?.captureProgress?.visibility = View.VISIBLE
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
                    Toast.makeText(requireContext(), "Burst capture timed out", Toast.LENGTH_SHORT).show()
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
                        archProgress?.setProgress(framesCaptured.toFloat() / burstSize)
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

            // Sequential capture instead of captureBurst to comply with requirements
            for (request in burstRequests) {
                session.capture(request, object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                        val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                        if (timestamp != null) {
                            captureResults[timestamp] = result
                        }
                        captureResultFlow.tryEmit(result)
                    }
                }, handler)
            }

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
        val binding = cameraUiContainerBinding ?: return
        val blackout = binding.viewFinderBlackout ?: return
        val vf = fragmentCameraBinding.viewFinder
        blackout.post {
            // Sync translation and scaling with the ViewFinder to ensure full coverage in Half-frame mode
            blackout.translationX = vf.translationX
            blackout.translationY = vf.translationY
            blackout.scaleX = vf.scaleX
            blackout.scaleY = vf.scaleY

            blackout.visibility = View.VISIBLE
            blackout.bringToFront()
            blackout.postDelayed({
                cameraUiContainerBinding?.viewFinderBlackout?.visibility = View.INVISIBLE
            }, 100L) // Use 100ms to ensure visibility during processing
        }
    }

    private fun updateHalfFrameUI(animate: Boolean = false) {
        val uiBinding = cameraUiContainerBinding ?: return
        val vfBinding = fragmentCameraBinding
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        // Post all updates to the viewfinder to avoid requestLayout() during layout pass
        // and ensure consistent ordering of state changes.
        vfBinding.viewFinder.post {
            // Re-read enabled state inside the post to ensure we use the latest value
            val isEnabled = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
            isHalfFrameModeEnabled = isEnabled
            readScopedHalfFrameState(prefs)

            val gapView = uiBinding.halfFrameGapIndicator ?: return@post
            val snapshotView = uiBinding.halfFrameSnapshot ?: return@post
            val edgeTopView = uiBinding.halfFrameFilmEdgeTop ?: return@post
            val edgeBottomView = uiBinding.halfFrameFilmEdgeBottom ?: return@post

            if (!isEnabled) {
                if (uiBinding.tvHalfFrameStep?.visibility != View.GONE) {
                    uiBinding.tvHalfFrameStep?.visibility = View.GONE
                }
                if (gapView.visibility != View.GONE) gapView.visibility = View.GONE
                if (snapshotView.visibility != View.GONE) snapshotView.visibility = View.GONE
                if (edgeTopView.visibility != View.GONE) edgeTopView.visibility = View.GONE
                if (edgeBottomView.visibility != View.GONE) edgeBottomView.visibility = View.GONE

                (vfBinding.viewFinder.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
                    if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT || lp.height != 0) {
                        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                        lp.height = 0
                        vfBinding.viewFinder.layoutParams = lp
                    }
                }

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
            } else if (processingCount == 0) {
                uiBinding.photoViewButton?.visibility = View.VISIBLE
                uiBinding.photoViewButton?.alpha = 1f
            }

            if (halfFrameBaseFinderWidth <= 0 || halfFrameBaseFinderHeight <= 0) {
                halfFrameBaseFinderWidth = vfBinding.viewFinder.width
                halfFrameBaseFinderHeight = vfBinding.viewFinder.height
            }

            val totalW = halfFrameBaseFinderWidth.toFloat()
            val totalH = halfFrameBaseFinderHeight.toFloat()
            if (totalW <= 0f || totalH <= 0f) return@post

            val gapWidthBase = (totalW * 0.12f).coerceAtLeast(60f)
            // Keep the viewfinder aspect ratio by applying a uniform scale on both axes.
            val scale = totalW / (totalW + gapWidthBase)
            val gapWidthScaled = gapWidthBase * scale
            val shift = gapWidthScaled / 2f

            if (edgeTopView.visibility != View.VISIBLE) edgeTopView.visibility = View.VISIBLE
            if (edgeBottomView.visibility != View.VISIBLE) edgeBottomView.visibility = View.VISIBLE
            edgeTopView.bringToFront()
            edgeBottomView.bringToFront()

            val targetW = (totalW * scale).toInt()
            val targetH = (totalH * scale).toInt()
            (vfBinding.viewFinder.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
                if (lp.width != targetW || lp.height != targetH) {
                    lp.width = targetW
                    lp.height = targetH
                    vfBinding.viewFinder.layoutParams = lp

                    gapView.layoutParams.width = gapWidthScaled.toInt()
                    gapView.requestLayout()
                }
            }

            vfBinding.viewFinder.scaleX = 1f
            vfBinding.viewFinder.scaleY = 1f

            val targetShift = if (halfFrameStep == 0) -shift else shift

            if (animate) {
                performHalfFrameAdvanceAnimation(targetShift, totalW, gapWidthScaled)
            } else {
                vfBinding.viewFinder.animate().cancel()
                vfBinding.viewFinder.translationX = targetShift
                gapView.visibility = View.GONE
                snapshotView.visibility = View.GONE
            }

            if (vfBinding.viewFinder.translationY != 0f) {
                vfBinding.viewFinder.translationY = 0f
            }
        }
    }

    private fun performHalfFrameAdvanceAnimation(targetShift: Float, totalW: Float, gapWidth: Float) {
        val uiBinding = cameraUiContainerBinding ?: return
        val vf = fragmentCameraBinding.viewFinder
        val snapshot = uiBinding.halfFrameSnapshot ?: return
        val gap = uiBinding.halfFrameGapIndicator ?: return

        // VF base position (centered) is (totalW - vf.width) / 2
        val vfBaseX = (totalW - vf.width) / 2f
        val startShift = vf.translationX
        val currentVfLeft = vfBaseX + startShift

        // 1. Take Snapshot of current viewfinder
        val bitmap = vf.bitmap
        if (bitmap != null) {
            snapshot.setImageBitmap(bitmap)
            snapshot.visibility = View.VISIBLE
            // Snapshot's layout is parent.start, so its translationX is its screen position
            snapshot.translationX = currentVfLeft
            // Ensure snapshot matches VF visible area perfectly
            snapshot.layoutParams.width = vf.width
            snapshot.layoutParams.height = vf.height
            snapshot.requestLayout()
        }

        // 2. Prepare Gap (also parent.start layout)
        gap.visibility = View.VISIBLE
        // If halfFrameStep is now 1 (took shot 1), gap is to the right of shot 1: currentVfLeft + vf.width
        // If halfFrameStep is now 0 (took shot 2), gap is to the left of shot 2: currentVfLeft - gapWidth
        gap.translationX = if (halfFrameStep == 1) currentVfLeft + vf.width else currentVfLeft - gapWidth
        gap.layoutParams.height = vf.height
        gap.requestLayout()

        // 3. Prepare ViewFinder for "coming in" from right
        // We want it to end at targetShift. Since everything moves by -totalW, it must start at targetShift + totalW
        vf.animate().cancel()
        vf.translationX = targetShift + totalW

        // 4. Animate everything to the left by exactly totalW (full screen width)
        val duration = 450L
        val interpolator = android.view.animation.AccelerateDecelerateInterpolator()

        snapshot.animate()
            .translationX(currentVfLeft - totalW)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction {
                snapshot.visibility = View.GONE
                snapshot.setImageBitmap(null)
            }
            .start()

        gap.animate()
            .translationX(gap.translationX - totalW)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction { gap.visibility = View.GONE }
            .start()

        vf.animate()
            .translationX(targetShift)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()

        animateFilmEdgeRoll(duration)
    }

    private fun animateFilmEdgeRoll(duration: Long = 360L) {
        val uiBinding = cameraUiContainerBinding ?: return
        val topEdge = uiBinding.halfFrameFilmEdgeTop ?: return
        val bottomEdge = uiBinding.halfFrameFilmEdgeBottom ?: return

        topEdge.animate().cancel()
        bottomEdge.animate().cancel()

        // Sprocket hole period is 30/1200 of the view width (based on 1200dp vector with 30dp spacing)
        val periodPx = (30f / 1200f) * topEdge.width
        // Move by multiple periods to cover about 40% of the screen width for a "roll" feel
        val rollDistance = periodPx * ( (resources.displayMetrics.widthPixels * 0.4f) / periodPx ).toInt()

        val interpolator = android.view.animation.AccelerateDecelerateInterpolator()

        topEdge.animate()
            .translationX(-rollDistance)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction { topEdge.translationX = 0f }
            .start()

        bottomEdge.animate()
            .translationX(-rollDistance)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction { bottomEdge.translationX = 0f }
            .start()
    }

    private fun cycleCaptureMode() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val currentMode = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
        val currentLayout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUTS[0])

        val (newMode, newLayout) = when {
            !currentMode -> true to SettingsFragment.HALF_FRAME_LAYOUTS[0] // Normal -> Side-by-side
            currentLayout == SettingsFragment.HALF_FRAME_LAYOUTS[0] -> true to SettingsFragment.HALF_FRAME_LAYOUTS[1] // Side-by-side -> Top-bottom
            else -> false to SettingsFragment.HALF_FRAME_LAYOUTS[0] // Top-bottom -> Normal
        }

        prefs.edit()
            .putBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, newMode)
            .putString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, newLayout)
            .apply()

        isHalfFrameModeEnabled = newMode
        readScopedHalfFrameState(prefs, requireFileForStep1 = true)
        updateHalfFrameUI()

        // Re-bind use cases if needed?
        // Actually Half-frame doesn't change use cases, just UI and post-processing.
    }

    private fun updateModeSwitchIcon(btn: MaterialButton) {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getBoolean(SettingsFragment.KEY_HALF_FRAME_MODE, false)
        val layout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUTS[0])

        val iconRes = when {
            !mode -> R.drawable.ic_mode_normal
            layout == SettingsFragment.HALF_FRAME_LAYOUTS[0] -> R.drawable.ic_mode_half_side
            else -> R.drawable.ic_mode_half_top
        }
        btn.setIconResource(iconRes)
    }

    private fun rotateShutter(targetRotation: Float) {
        val binding = cameraUiContainerBinding ?: return

        fun animateRotation(view: android.view.View, target: Float) {
            val current = view.rotation
            val diff = (target - current) % 360
            val shortestDiff = when {
                diff > 180 -> diff - 360
                diff < -180 -> diff + 360
                else -> diff
            }

            view.animate()
                .rotation(current + shortestDiff)
                .setDuration(ANIMATION_SLOW_MILLIS)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .start()
        }

        animateRotation(binding.cameraCaptureButton, targetRotation)
        animateRotation(binding.captureProgress, targetRotation)
    }

    private fun resetBurstUi() {
        cameraUiContainerBinding?.captureProgress?.visibility = View.GONE
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

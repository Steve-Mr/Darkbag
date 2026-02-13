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
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
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
    private var camera2Session: android.hardware.camera2.CameraCaptureSession? = null
    private var camera2PreviewSurface: android.view.Surface? = null
    private var rawImageReader: android.media.ImageReader? = null
    private var analysisImageReader: android.media.ImageReader? = null
    private val camera2Manager by lazy {
        requireContext().getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    }

    private var camera2Thread: HandlerThread? = null
    private var camera2Handler: Handler? = null

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
    private var activeManualTab: String? = null

    // Flash State
    private var isFlashEnabled = false

    // HDR+ State
    private var isHdrPlusEnabled = false
    @Volatile private var isBurstActive = false
    private var hdrPlusBurstHelper: HdrPlusBurst? = null
    private var lastHdrPlusConfig: ExposureUtils.ExposureConfig? = null // Cache for instant trigger
    private var burstStartTime: Long = 0L // Profiling

    private var minFocusDistance = 0.0f
    private var isoRange: android.util.Range<Int>? = null
    private var exposureTimeRange: android.util.Range<Long>? = null
    private var evRange: android.util.Range<Int>? = null

    private var currentFocusDistance = 0.0f
    private var currentIso = 100
    private var currentExposureTime = 10_000_000L // 10ms
    private var currentEvIndex = 0

    // Zoom State
    private var defaultFocalLength = 24
    private var currentFocalLength = -1
    private var is2xMode = false
    private var zoomJob: kotlinx.coroutines.Job? = null

    private var deviceOrientationDegrees = 0

    /** Orientation listener to track device rotation independently of UI rotation */
    private val orientationEventListener by lazy {
        object : OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    return
                }

                // Map Orientation to degrees (0, 90, 180, 270 clockwise)
                deviceOrientationDegrees = when (orientation) {
                    in 45 until 135 -> 270 // Landscape Left (90 CCW -> 270 CW)
                    in 135 until 225 -> 180 // Upside Down
                    in 225 until 315 -> 90 // Landscape Right (270 CCW -> 90 CW)
                    else -> 0 // Portrait
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
    private val processingChannel = kotlinx.coroutines.channels.Channel<RawImageHolder>(2)

    data class RawImageHolder(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val rotationDegrees: Int, // Sensor Orientation
        val combinedOrientation: Int, // Combined with Display
        val zoomRatio: Float,
        val physicalId: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RawImageHolder

            if (!data.contentEquals(other.data)) return false
            if (width != other.width) return false
            if (height != other.height) return false
            if (timestamp != other.timestamp) return false
            if (rotationDegrees != other.rotationDegrees) return false
            if (zoomRatio != other.zoomRatio) return false
            if (physicalId != other.physicalId) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + rotationDegrees
            result = 31 * result + zoomRatio.hashCode()
            result = 31 * result + (physicalId?.hashCode() ?: 0)
            return result
        }
    }

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
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener.disable()
        // Ensure Camera2 is closed when stopping to release hardware resources
        releaseCamera2Resources()
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

        // Re-initialize camera engine if needed.
        // For Camera2 engine, we need to re-bind use cases (which triggers openCamera2).
        // For CameraX, they are bound to lifecycle but we ensure consistency.
        if (cameraProvider != null || currentLens?.useCamera2 == true) {
            bindCameraUseCases()
        }
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

    private fun setGalleryThumbnail(filename: String) {
        // Run the operations in the view's thread
        cameraUiContainerBinding?.photoViewButton?.let { photoViewButton ->
            photoViewButton.post {
                // Remove thumbnail padding
                photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

                // Load thumbnail into circular button using Glide
                Glide.with(photoViewButton)
                    .load(filename)
                    .apply(RequestOptions.circleCropTransform())
                    .into(photoViewButton)
            }
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

        // Initialize Zoom Default
        val prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val defaultStr = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_LENGTH, "24") ?: "24"
        defaultFocalLength = defaultStr.toIntOrNull() ?: 24

        // Restore Zoom State
        if (currentFocalLength == -1) {
            currentFocalLength = prefs.getInt(KEY_CURRENT_FOCAL_LENGTH, defaultFocalLength)
        }
        is2xMode = prefs.getBoolean(KEY_IS_2X_MODE, false)

        // Initialize Flash State
        isFlashEnabled = prefs.getBoolean(SettingsFragment.KEY_FLASH_MODE, false)

        // Initialize HDR+ State
        isHdrPlusEnabled = prefs.getBoolean(KEY_HDR_PLUS_ENABLED, true)
        updateHdrPlusUi()

        // Initialize HDR+ Burst Helper
        hdrPlusBurstHelper = HdrPlusBurst(
            frameCount = 3,
            onBurstComplete = { frames ->
                processHdrPlusBurst(frames, 1.0f)
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
                                Toast.makeText(context, "HDR+ Saved!", Toast.LENGTH_SHORT).show()
                                setGalleryThumbnail(event.targetUri)
                                cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
                            }
                        } else {
                             Log.w(TAG, "Received save event for ${event.baseName} without targetUri.")
                             withContext(Dispatchers.Main) {
                                 cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
                             }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Background UI update failed for ${event.baseName}", e)
                        withContext(Dispatchers.Main) {
                            cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
                        }
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
                        withContext(Dispatchers.Main) {
                            cameraUiContainerBinding?.processingProgress?.visibility = View.VISIBLE
                        }
                        processImageAsync(appContext, holder)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image from channel", e)
                    } finally {
                        processingSemaphore.release()
                        withContext(Dispatchers.Main) {
                            cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                            cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
                            cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
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

    private fun refreshLenses() {
        val cameraXIds = mutableSetOf<String>()
        cameraProvider?.availableCameraInfos?.forEach { info ->
            val id = Camera2CameraInfo.from(info).cameraId
            cameraXIds.add(id)
            Log.d(TAG, "CameraX availableCameraInfo ID: $id")
        }

        val repoFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        else
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT

        val newLenses = cameraRepository.enumerateCameras(cameraXIds, repoFacing)
        Log.d(TAG, "Available Lenses identified: ${newLenses.size} for facing $repoFacing")

        // Update currentLens reference if it exists, to pick up any engine changes
        currentLens?.let { old ->
             currentLens = newLenses.find { it.sensorId == old.sensorId }
                 ?: newLenses.find { it.id == old.id && it.physicalId == old.physicalId }
                 // Don't keep old lens if it's from a different facing
        }

        availableLenses = newLenses

        if (currentLens == null || availableLenses.none { it.sensorId == currentLens?.sensorId }) {
            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val savedLensId = prefs.getString(KEY_SELECTED_LENS_ID, null)

            currentLens = availableLenses.find { it.sensorId == savedLensId }
                ?: availableLenses.find { it.multiplier in 0.95f..1.05f && !it.isLogicalAuto }
                ?: availableLenses.find { it.isLogicalAuto }
                ?: availableLenses.firstOrNull()
        }
    }

    /** Initialize Camera Engine, and prepare to bind the camera use cases  */
    private suspend fun setUpCamera() {
        // Initially don't initialize cameraProvider unless needed.
        // But we need to know lensFacing.
        lensFacing = CameraSelector.LENS_FACING_BACK

        // Initialize Lenses
        refreshLenses()

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

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases() {
        refreshLenses()

        // Fetch Characteristics for Manual Control
        val targetId = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
             currentLens?.physicalId ?: currentLens?.id ?: "0"
        } else {
             "1" // Front
        }

        var isRawSupported = false
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

            // Update UI if manual panel is visible
            lifecycleScope.launch(Dispatchers.Main) {
                updateManualPanel()
                updateTabColors()

                if (isRawSupported) {
                    cameraUiContainerBinding?.hdrPlusToggle?.visibility = View.VISIBLE
                    updateHdrPlusUi()
                } else {
                    cameraUiContainerBinding?.hdrPlusToggle?.visibility = View.GONE
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

        if (lensFacing == CameraSelector.LENS_FACING_BACK && currentLens?.useCamera2 == true && !useCameraxFallback) {
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
                cameraUiContainerBinding?.flashButton?.visibility = if (hasFlash) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check flash for Camera2", e)
            }

            // Give CameraX a moment to release hardware
            lifecycleScope.launch(Dispatchers.Main) {
                delay(150)
                openCamera2(currentLens!!.id)
            }
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
        var cameraSelector = if (lensFacing == CameraSelector.LENS_FACING_BACK && currentLens != null) {
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

        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            currentLens?.physicalId?.let { pId ->
                Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(pId)
            }
        }
        preview = previewBuilder.build()

        // ImageCapture
        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .setFlashMode(if (isFlashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)

        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            currentLens?.physicalId?.let { pId ->
                Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(pId)
            }
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
                                iso, time, validIsoRange, validTimeRange, underexposureMode
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

        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            currentLens?.physicalId?.let { pId ->
                Camera2Interop.Extender(imageAnalyzerBuilder).setPhysicalCameraId(pId)
            }
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
                if (cam.cameraInfo.hasFlashUnit()) {
                    cameraUiContainerBinding?.flashButton?.visibility = View.VISIBLE
                } else {
                    cameraUiContainerBinding?.flashButton?.visibility = View.GONE
                }

                observeCameraState(cam.cameraInfo)
            }

            // Pre-initialize JNI memory pool with current resolution and burst size
            // Move to background thread to avoid blocking main thread
            val burstSizeStr = prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "8") ?: "8"
            val burstSize = burstSizeStr.toIntOrNull() ?: 8
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

        // Remove previous UI if any
        cameraUiContainerBinding?.root?.let {
            fragmentCameraBinding.root.removeView(it)
        }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            fragmentCameraBinding.root,
            true
        )

        // In the background, load latest photo taken (if any) for gallery thumbnail
        lifecycleScope.launch {
            val thumbnailUri = mediaStoreUtils.getLatestImageFilename()
            thumbnailUri?.let {
                setGalleryThumbnail(it)
            }
        }

        // Apply WindowInsets to UI Container to avoid system bar overlap
        cameraUiContainerBinding?.root?.let { rootView ->
            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(
                    left = insets.left,
                    top = insets.top,
                    right = insets.right,
                    bottom = insets.bottom
                )
                WindowInsetsCompat.CONSUMED
            }
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
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {
            // Check concurrency limit
            if (!processingSemaphore.tryAcquire()) {
                Toast.makeText(
                    requireContext(),
                    "Processing queue full, please wait...",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (currentLens?.useCamera2 == true) {
                if (isHdrPlusEnabled) {
                    triggerHdrPlusBurstCamera2()
                } else {
                    takeSinglePictureCamera2()
                }
            } else {
                // Get a stable reference of the modifiable image capture use case
                imageCapture?.let { imageCapture ->
                    if (imageCapture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW) {
                        if (isHdrPlusEnabled) {
                            triggerHdrPlusBurst(imageCapture)
                        } else {
                            takeSinglePicture(imageCapture)
                        }
                    } else {
                        takeSinglePicture(imageCapture)
                    }
                } ?: run {
                     processingSemaphore.release()
                }
            }
        }
        cameraUiContainerBinding?.cameraSwitchButton?.let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }

        // Initialize Manual Controls
        initManualControls()

        // HDR+ Toggle
        cameraUiContainerBinding?.hdrPlusToggle?.let { toggle ->
            toggle.setOnClickListener {
                isHdrPlusEnabled = !isHdrPlusEnabled
                requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_HDR_PLUS_ENABLED, isHdrPlusEnabled).apply()
                updateHdrPlusUi()
            }
        }

        // LUT Selector (Always visible now)
        cameraUiContainerBinding?.lutButton?.visibility = View.VISIBLE
        cameraUiContainerBinding?.lutButton?.setOnClickListener {
             // Toggle LUT List Visibility
             val lutListContainer = cameraUiContainerBinding?.lutListContainer
             if (lutListContainer?.visibility == View.VISIBLE) {
                 lutListContainer.visibility = View.GONE
                 cameraUiContainerBinding?.touchOverlay?.visibility = View.GONE
             } else {
                 lutListContainer?.visibility = View.VISIBLE
                 cameraUiContainerBinding?.touchOverlay?.visibility = View.VISIBLE
                 refreshLutList()
             }
        }

        // Initialize Zoom Controls
        initZoomControls()

        // Listener for button used to view the most recent photo
        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            // Only navigate when the gallery has photos
            lifecycleScope.launch {
                val images = mediaStoreUtils.getImages()
                if (images.isNotEmpty()) {
                    val uri = images.first().uri
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
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled =
                hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = false
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

        /**
         * Helper extension function used to extract a byte array from an image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

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

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listeners.forEach { it(luma) }
            image.close()
        }
    }

    private fun copyImageToHolder(image: ImageProxy, zoomRatio: Float, combinedOrientation: Int, physicalId: String? = null): RawImageHolder {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = 2 // 16-bit raw

        val rowLength = width * pixelStride
        val dataLength = rowLength * height
        val cleanData = ByteArray(dataLength)

        if (rowStride == rowLength) {
            if (buffer.remaining() == dataLength) {
                buffer.get(cleanData)
            } else {
                buffer.get(cleanData, 0, dataLength)
            }
        } else {
            val rowData = ByteArray(rowLength)
            buffer.rewind()
            for (y in 0 until height) {
                val rowStart = y * rowStride
                if (rowStart + rowLength > buffer.capacity()) break
                buffer.position(rowStart)
                buffer.get(rowData)
                System.arraycopy(rowData, 0, cleanData, y * rowLength, rowLength)
            }
        }

        return RawImageHolder(
            data = cleanData,
            width = width,
            height = height,
            timestamp = image.imageInfo.timestamp,
            rotationDegrees = image.imageInfo.rotationDegrees,
            combinedOrientation = combinedOrientation,
            zoomRatio = zoomRatio,
            physicalId = physicalId
        )
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private suspend fun processImageAsync(context: Context, image: RawImageHolder) =
        withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val dngName =
                    SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())

                Log.d(
                    TAG,
                    "Processing Image: Timestamp=${image.timestamp}, ZoomRatio=${image.zoomRatio}, Rotation=${image.rotationDegrees}"
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

                // 2. Prepare Settings
                val prefs =
                    context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
                val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)

                val activeLutName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
                var nativeLutPath: String? = null

                if (activeLutName != null) {
                    val lutFile = File(File(context.filesDir, "luts"), activeLutName)
                    if (lutFile.exists()) {
                        nativeLutPath = lutFile.absolutePath
                    }
                }

                if (nativeLutPath == null) {
                    val lutPath = prefs.getString(SettingsFragment.KEY_LUT_URI, null)
                    if (lutPath != null) {
                        if (lutPath.startsWith("content://")) {
                            val lutFile = File(context.cacheDir, "temp_lut.cube")
                            try {
                                contentResolver.openInputStream(Uri.parse(lutPath))?.use { input ->
                                    FileOutputStream(lutFile).use { output -> input.copyTo(output) }
                                }
                                nativeLutPath = lutFile.absolutePath
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load LUT", e)
                            }
                        } else {
                            nativeLutPath = lutPath
                        }
                    }
                }

                val saveTiff = prefs.getBoolean(SettingsFragment.KEY_SAVE_TIFF, true)
                val saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true)
                val useGpu = prefs.getBoolean(SettingsFragment.KEY_USE_GPU, false)

                val tiffFile = if (saveTiff) File(context.cacheDir, "$dngName.tiff") else null
                val tiffPath = tiffFile?.absolutePath
                val bmpPath = File(context.cacheDir, "temp_${dngName}.bmp").absolutePath

                // 3. Generate DNG in Memory (for LibRaw and Saving)
                android.hardware.camera2.DngCreator(chars, captureResult).use { dngCreatorReal ->

                    val dngOutputStream = java.io.ByteArrayOutputStream()
                    var dngBytes: ByteArray? = null

                    val orientation = when (image.combinedOrientation) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }
                    dngCreatorReal.setOrientation(orientation)

                    val inputStream = java.io.ByteArrayInputStream(image.data)
                    dngCreatorReal.writeInputStream(
                        dngOutputStream,
                        android.util.Size(image.width, image.height),
                        inputStream,
                        0
                    )
                    dngBytes = dngOutputStream.toByteArray()

                    // 4. Process with LibRaw
                    val result = ColorProcessor.processRaw(
                        dngBytes, targetLogIndex, nativeLutPath, tiffPath, bmpPath, useGpu
                    )

                    if (result == 1) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "GPU processing failed. Fallback to CPU used.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else if (result < 0) {
                        throw RuntimeException("ColorProcessor returned error code $result")
                    }

                    val cropRegion =
                        captureResult.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
                    val activeArray =
                        chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                    var zoomFactor = 1.0f
                    if (image.zoomRatio > 1.05f) {
                        zoomFactor = image.zoomRatio
                    } else if (cropRegion != null && activeArray != null) {
                        val metaZoom =
                            activeArray.width().toFloat() / cropRegion.width().toFloat()
                        if (metaZoom > 1.05f) {
                            zoomFactor = metaZoom
                        }
                    }

                    // 5. Shared Save Logic
                    val finalJpgUri = ImageSaver.saveProcessedImage(
                        context,
                        null,
                        bmpPath,
                        image.combinedOrientation,
                        zoomFactor,
                        dngName,
                        null,
                        tiffPath,
                        saveJpg,
                        saveTiff
                    ) { bitmap ->
                        try {
                            // Generate Thumbnail for DNG
                            val maxThumbSize = 256
                            val scale = min(
                                maxThumbSize.toDouble() / bitmap.width,
                                maxThumbSize.toDouble() / bitmap.height
                            )
                            val thumbWidth = (bitmap.width * scale).toInt()
                            val thumbHeight = (bitmap.height * scale).toInt()

                            val thumbBitmap = if (scale < 1.0) {
                                android.graphics.Bitmap.createScaledBitmap(
                                    bitmap,
                                    thumbWidth,
                                    thumbHeight,
                                    true
                                )
                            } else {
                                bitmap
                            }
                            dngCreatorReal.setThumbnail(thumbBitmap)
                            Log.d(TAG, "DNG Thumbnail set: ${thumbWidth}x${thumbHeight}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to set DNG thumbnail", e)
                        }
                    }

                    // 6. Save Standard RAW DNG
                    val dngValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$dngName.dng")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val dngUri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        dngValues
                    )
                    if (dngUri != null) {
                        try {
                            contentResolver.openOutputStream(dngUri)?.use { out ->
                                val inputStream2 = java.io.ByteArrayInputStream(image.data)
                                dngCreatorReal.writeInputStream(
                                    out,
                                    android.util.Size(image.width, image.height),
                                    inputStream2,
                                    0
                                )
                            }
                            // Inject crop metadata if zoomed
                            if (zoomFactor > 1.05f) {
                                try {
                                    contentResolver.openFileDescriptor(dngUri, "rw")?.use { pfd ->
                                        val exif =
                                            androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                                        val fullWidth = image.width
                                        val fullHeight = image.height
                                        val cropWidth = (fullWidth / zoomFactor).toInt()
                                        val cropHeight = (fullHeight / zoomFactor).toInt()
                                        val x = ((fullWidth - cropWidth) / 2) / 2 * 2
                                        val y = ((fullHeight - cropHeight) / 2) / 2 * 2

                                        exif.setAttribute(
                                            "DefaultCropSize",
                                            "$cropWidth $cropHeight"
                                        )
                                        exif.setAttribute("DefaultCropOrigin", "$x $y")
                                        exif.saveAttributes()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to inject DNG crop metadata", e)
                                }
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                dngValues.clear()
                                dngValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                contentResolver.update(dngUri, dngValues, null, null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing DNG to MediaStore", e)
                            contentResolver.delete(dngUri, null, null)
                        }
                    }

                    // 7. Update UI
                    withContext(Dispatchers.Main) {
                        if (finalJpgUri != null) {
                            setGalleryThumbnail(finalJpgUri.toString())
                        } else {
                            mediaStoreUtils.getLatestImageFilename()
                                ?.let { setGalleryThumbnail(it) }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in background processing", e)
            }
        }

    private fun setupTapToFocus() {
        val width = fragmentCameraBinding.viewFinder.width.toFloat()
        val height = fragmentCameraBinding.viewFinder.height.toFloat()
        val cameraInfo = camera?.cameraInfo ?: return

        val factory = DisplayOrientedMeteringPointFactory(
            fragmentCameraBinding.viewFinder.display,
            cameraInfo,
            width,
            height
        )

        fragmentCameraBinding.viewFinder.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                if (currentLens?.useCamera2 == true) {
                     isManualFocus = false
                     applyCameraControls()
                } else {
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point).build()

                    isManualFocus = false
                    applyCameraControls() // Apply change

                    if (activeManualTab == "Focus") {
                        updateManualPanel()
                    }

                    updateTabColors()

                    camera?.cameraControl?.startFocusAndMetering(action)
                }

                val screenX = view.x + event.x
                val screenY = view.y + event.y
                showFocusRing(screenX, screenY)
                view.performClick()
            }
            true
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val focusRing = fragmentCameraBinding.focusRing
        val width = focusRing.width.toFloat()
        val height = focusRing.height.toFloat()

        focusRing.animate().cancel()

        focusRing.translationX = x - width / 2
        focusRing.translationY = y - height / 2
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
                camera?.cameraControl?.cancelFocusAndMetering()
            }

            "ISO", "Shutter" -> {
                isManualExposure = false
            }

            "EV" -> {
                currentEvIndex = 0
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
        val scroll = binding.controlsHubScroll ?: return

        if (lensFacing != CameraSelector.LENS_FACING_BACK) {
            scroll.visibility = View.GONE
            return
        }

        refreshLenses()

        if (availableLenses.isNotEmpty()) {
            scroll.visibility = View.VISIBLE
            container.removeAllViews()

            val uiLenses = availableLenses.filter { !it.isLogicalAuto }.toMutableList()

            val hasPhysical2x = uiLenses.any { it.multiplier in 1.9f..2.1f }
            if (!hasPhysical2x) {
                val mainLens = uiLenses.find { it.multiplier in 0.95f..1.05f } ?: uiLenses.firstOrNull()
                mainLens?.let {
                    uiLenses.add(it.copy(name = "2.0x", targetZoomRatio = 2.0f, sensorId = "virtual-2x", isZoomPreset = true))
                }
            }

            uiLenses.sortBy { it.multiplier }

            for (lens in uiLenses) {
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
                        val is1xLens = lens.multiplier in 0.95f..1.05f

                        if (lens.sensorId == "virtual-2x") {
                             is2xMode = true
                             currentFocalLength = 24
                             val targetMain = uiLenses.find { it.multiplier in 0.95f..1.05f && it.sensorId != "virtual-2x" }
                             if (targetMain != null) {
                                 currentLens = targetMain
                                 if (oldLens?.id != currentLens?.id || oldLens?.useCamera2 != currentLens?.useCamera2) {
                                     animateSwitch { bindCameraUseCases() }
                                 } else {
                                     updateZoom(true)
                                 }
                             }
                             return@setOnClickListener
                        }

                        if (oldLens?.sensorId == lens.sensorId) {
                            if (is1xLens) {
                                if (is2xMode) {
                                    is2xMode = false
                                    currentFocalLength = 24
                                } else {
                                    when (currentFocalLength) {
                                        24 -> currentFocalLength = 28
                                        28 -> currentFocalLength = 35
                                        35 -> currentFocalLength = 24
                                        else -> currentFocalLength = 24
                                    }
                                }
                                updateZoom(true)
                            }
                        } else {
                            currentLens = lens
                            is2xMode = false
                            currentFocalLength = 24

                            updateLensUI()

                            if (oldLens?.id != lens.id || oldLens?.physicalId != lens.physicalId || oldLens?.useCamera2 != lens.useCamera2) {
                                animateSwitch {
                                    bindCameraUseCases()
                                }
                            } else {
                                updateZoom(true)
                            }
                        }
                    }
                }
                container.addView(btn)
            }

            if (currentLens == null) {
                val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val savedLensId = prefs.getString(KEY_SELECTED_LENS_ID, null)

                currentLens = availableLenses.find { it.sensorId == savedLensId }
                    ?: availableLenses.find { it.multiplier in 0.95f..1.05f && !it.isLogicalAuto }
                    ?: availableLenses.find { it.isLogicalAuto }
                    ?: availableLenses.firstOrNull()
            }
            updateLensUI()
        } else {
            scroll.visibility = View.GONE
        }
    }

    private fun updateLensUI() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        currentLens?.let {
            prefs.edit().putString(KEY_SELECTED_LENS_ID, it.sensorId).apply()
        }

        val binding = cameraUiContainerBinding ?: return
        val container = binding.lensControlsContainer ?: return
        val scroll = binding.controlsHubScroll ?: return

        val colorPrimary = MaterialColors.getColor(container, com.google.android.material.R.attr.colorPrimary)
        val colorOnSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)

        val uiLenses = availableLenses.filter { !it.isLogicalAuto }.toMutableList()
        val hasPhysical2x = uiLenses.any { it.multiplier in 1.9f..2.1f }
        if (!hasPhysical2x) {
            val mainLens = uiLenses.find { it.multiplier in 0.95f..1.05f } ?: uiLenses.firstOrNull()
            mainLens?.let {
                uiLenses.add(it.copy(name = "2.0x", targetZoomRatio = 2.0f, sensorId = "virtual-2x", isZoomPreset = true))
            }
        }
        uiLenses.sortBy { it.multiplier }

        for (i in 0 until container.childCount) {
            val btn = container.getChildAt(i) as? com.google.android.material.button.MaterialButton
            val lens = btn?.tag as? com.android.example.cameraxbasic.utils.LensInfo
            if (btn != null && lens != null) {
                val isActive = if (lens.sensorId == "virtual-2x") {
                    is2xMode
                } else {
                    lens.sensorId == currentLens?.sensorId && !is2xMode
                }

                if (isActive) {
                    btn.setTextColor(colorPrimary)
                    btn.strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke_small)
                    btn.strokeColor = android.content.res.ColorStateList.valueOf(colorPrimary)
                    btn.setBackgroundColor(MaterialColors.layer(
                        MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSurface),
                        colorPrimary,
                        0.1f
                    ))

                    if (lens.multiplier in 0.95f..1.05f && lens.sensorId != "virtual-2x") {
                        btn.text = when {
                            currentFocalLength == 28 -> "28mm"
                            currentFocalLength == 35 -> "35mm"
                            else -> lens.name
                        }
                    } else {
                        btn.text = lens.name
                    }
                } else {
                    btn.setTextColor(colorOnSurface)
                    btn.strokeWidth = 0
                    btn.text = lens.name
                    btn.setBackgroundColor(MaterialColors.layer(
                        MaterialColors.getColor(btn, com.google.android.material.R.attr.colorSurface),
                        colorOnSurface,
                        0.15f
                    ))
                }
            }
        }

        binding.zoomControlsContainer?.visibility = View.GONE
    }

    private fun initZoomControls() {
        val binding = cameraUiContainerBinding ?: return

        binding.btnZoomToggle?.setOnClickListener {
            if (is2xMode) {
                is2xMode = false
                currentFocalLength = defaultFocalLength
            } else {
                currentFocalLength = when (currentFocalLength) {
                    24 -> 28
                    28 -> 35
                    35 -> 24
                    else -> 24
                }
            }
            updateZoom(true)
        }

        binding.btnZoom2x?.setOnClickListener {
            if (!is2xMode) {
                is2xMode = true
                updateZoom(true)
            }
        }

        updateZoomUI(false)
    }

    private fun updateZoom(animate: Boolean) {
        if (camera2Device != null) {
            updateZoomCamera2()
            updateZoomUI(animate)
            return
        }

        if (currentLens?.isZoomPreset == true && currentLens?.targetZoomRatio != null) {
            camera?.cameraControl?.setZoomRatio(currentLens!!.targetZoomRatio!!)
            updateZoomUI(animate)
            return
        }

        val targetRatio = if (is2xMode) {
            2.0f
        } else {
            if (currentLens?.isLogicalAuto == true || (currentLens?.multiplier ?: 0f) in 0.95f..1.05f) {
                currentFocalLength / 24.0f
            } else {
                1.0f
            }
        }

        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio
            ?: 8.0f
        val ratio = targetRatio.coerceAtMost(maxZoom)

        camera?.cameraControl?.setZoomRatio(ratio)
        updateZoomUI(animate)
    }

    private fun updateZoomCamera2() {
        val session = camera2Session ?: return
        val device = camera2Device ?: return
        val chars = camera2Manager.getCameraCharacteristics(device.id)
        val activeArray = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val targetRatio = if (is2xMode) 2.0f else (currentFocalLength / 24.0f)

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
            val targetId = if (lens?.useCamera2 == true) {
                lens.id
            } else if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                lens?.physicalId ?: lens?.id ?: "0"
            } else {
                "1"
            }
            camera2Manager.getCameraCharacteristics(targetId)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (e: Exception) { 0 }

        return if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            (sensorOrientation - deviceOrientationDegrees + 360) % 360
        } else {
            (sensorOrientation + deviceOrientationDegrees) % 360
        }
    }

    private fun updateZoomUI(animate: Boolean) {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_2X_MODE, is2xMode)
            .putInt(KEY_CURRENT_FOCAL_LENGTH, currentFocalLength)
            .apply()

        val binding = cameraUiContainerBinding ?: return

        updateLensUI()

        val activeColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val inactiveColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)

        if (is2xMode) {
            binding.btnZoom2x?.setTextColor(activeColor)
            binding.btnZoomToggle?.setTextColor(inactiveColor)

            binding.btnZoomToggle?.text = "1x"
            zoomJob?.cancel()
        } else {
            binding.btnZoom2x?.setTextColor(inactiveColor)
            binding.btnZoomToggle?.setTextColor(activeColor)

            zoomJob?.cancel()
            val labelX = when (currentFocalLength) {
                24 -> "1x"
                28 -> "1.2x"
                35 -> "1.5x"
                else -> "1x"
            }

            if (animate) {
                zoomJob = lifecycleScope.launch(Dispatchers.Main) {
                    val labelMm = "${currentFocalLength}mm"

                    binding.btnZoomToggle?.text = labelMm
                    delay(500)
                    binding.btnZoomToggle?.text = labelX
                }
            } else {
                binding.btnZoomToggle?.text = labelX
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCameraControls() {
        if (camera2Device != null) {
            updateCamera2RepeatingRequest()
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

        camera2Control.setCaptureRequestOptions(builder.build())

        if (!isManualExposure) {
            cameraControl.setExposureCompensationIndex(currentEvIndex)
        }
    }

    private fun updateCamera2RepeatingRequest() {
        val session = camera2Session ?: return
        val device = camera2Device ?: return
        val surface = camera2PreviewSurface ?: return
        val handler = camera2Handler ?: return

        try {
            val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
            request.addTarget(surface)
            applyManualSettingsToRequest(request)

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

        activeLutJob?.cancel()
        activeLutJob = lifecycleScope.launch(Dispatchers.IO) {
            var lutData: FloatArray? = null
            var size = 0
            if (activeLutName != null) {
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

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_TYPE = "image/jpeg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val FOCUS_RING_DISPLAY_TIME_MS = 500L
        private const val FOCUS_RING_FADE_OUT_DURATION_MS = 300L
        private const val AE_SETTLE_DELAY_MS = 50L

        const val KEY_SELECTED_LENS_ID = "selected_lens_sensor_id"
        const val KEY_IS_2X_MODE = "is_2x_mode"
        const val KEY_CURRENT_FOCAL_LENGTH = "current_focal_length"
        const val KEY_HDR_PLUS_ENABLED = "hdr_plus_enabled"
    }

    private fun takeSinglePicture(imageCapture: ImageCapture) {
        if (imageCapture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW) {
            imageCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val currentZoom =
                                if (is2xMode) 2.0f else (currentFocalLength / 24.0f)

                            val holder = copyImageToHolder(image, currentZoom, getCombinedOrientation(), currentLens?.physicalId)
                            image.close()

                            lifecycleScope.launch {
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
                                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during capture copy", e)
                            image.close()
                            processingSemaphore.release()
                            lifecycleScope.launch(Dispatchers.Main) {
                                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled =
                                    true
                                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                        processingSemaphore.release()
                        lifecycleScope.launch(Dispatchers.Main) {
                            cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                            cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
                        }
                    }
                })

            if (processingSemaphore.availablePermits == 0) {
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 0.5f
            }

        } else {
            processingSemaphore.release()
            Toast.makeText(
                requireContext(),
                "RAW capture is not supported on this device.",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
            fragmentCameraBinding.root.postDelayed({
                fragmentCameraBinding.root.foreground = null
            }, ANIMATION_FAST_MILLIS)
        }
    }

    private fun triggerHdrPlusBurst(imageCapture: ImageCapture) {
        if (isBurstActive) {
            Log.d(TAG, "Burst already active, ignoring trigger")
            return
        }
        isBurstActive = true
        burstStartTime = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.Main) {
            try {
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
                    val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    val underexposureMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"
                    ExposureUtils.calculateHdrPlusExposure(
                        currentIso,
                        currentTime,
                        validIsoRange,
                        validTimeRange,
                        underexposureMode
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
                    camera2Control.setCaptureRequestOptions(builder.build()).await()
                }

                delay(AE_SETTLE_DELAY_MS)

                val prefs = requireContext().getSharedPreferences(
                    SettingsFragment.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                val burstSizeStr = prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "3") ?: "3"
                val burstSize = burstSizeStr.toIntOrNull() ?: 3

                hdrPlusBurstHelper = HdrPlusBurst(
                    frameCount = burstSize,
                    onBurstComplete = { frames ->
                        processHdrPlusBurst(frames, config.digitalGain)
                    }
                )

                cameraUiContainerBinding?.captureProgress?.max = burstSize
                cameraUiContainerBinding?.captureProgress?.progress = 0
                cameraUiContainerBinding?.captureProgress?.visibility = View.VISIBLE
                cameraUiContainerBinding?.processingProgress?.visibility = View.VISIBLE
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 0.5f

                Toast.makeText(
                    requireContext(),
                    "Capturing HDR+ Burst ($burstSize frames)...",
                    Toast.LENGTH_SHORT
                ).show()

                Log.d(TAG, "Starting HDR+ Burst (Pipelined, $burstSize frames)")

                for (i in 0 until burstSize) {
                    captureBurstFrame(imageCapture, burstSize, i)
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
                cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
                applyCameraControls()
            }
        }
    }

    private fun captureBurstFrame(imageCapture: ImageCapture, totalFrames: Int, currentFrame: Int) {
        Log.d(TAG, "Triggering burst frame ${currentFrame + 1}/$totalFrames")
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.d(TAG, "Burst frame ${currentFrame + 1} captured successfully.")

                    lifecycleScope.launch(Dispatchers.Main) {
                        cameraUiContainerBinding?.captureProgress?.progress =
                            (cameraUiContainerBinding?.captureProgress?.progress ?: 0) + 1

                        if ((cameraUiContainerBinding?.captureProgress?.progress ?: 0) >= totalFrames) {
                            Log.d(TAG, "HDR+ Burst Capture sequence complete.")
                            applyCameraControls()
                            resetBurstUi()
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
        captureResults.entries.find { abs(it.key - timestamp) < tolerance }?.value?.let { return it }

        return withTimeoutOrNull(3000) {
            captureResultFlow.first { res ->
                val ts = res.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                ts != null && abs(ts - timestamp) < tolerance
            }
        }
    }

    private fun processHdrPlusBurst(frames: List<HdrFrame>, digitalGain: Float) {
        val currentZoom = if (is2xMode) 2.0f else (currentFocalLength / 24.0f)
        val combinedOrientation = getCombinedOrientation()
        val startTime = burstStartTime
        val captureEndTime = System.currentTimeMillis()
        val appContext = context?.applicationContext ?: return

        (appContext as MainApplication).applicationScope.launch(Dispatchers.IO) {
            var fallbackSent = false
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
                var blackLevel = 64
                var wb = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)
                var ccm = floatArrayOf(
                    2.0f, -1.0f, 0.0f,
                    -0.5f, 2.0f, -0.5f,
                    0.0f, -1.0f, 2.0f
                )
                var cfa = 0

                if (chars != null) {
                    whiteLevel = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                    val bl = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                    if (bl != null) blackLevel = bl.getOffsetForIndex(0, 0)

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
                                ccm[idx++] = rat.toFloat()
                            }
                        }
                    }
                }

                Log.d(TAG, "Metadata: WL=$whiteLevel, BL=$blackLevel, WB=${wb.joinToString()}, CFA=$cfa")

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
                val saveTiff = prefs.getBoolean(SettingsFragment.KEY_SAVE_TIFF, true)
                val saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true)

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

                // Initial JNI call only produces the linear raw buffer (finalImage)
                // which is saved to tempRawPath for the ExportWorker to pick up.
                // We pass null for all other output paths and set isAsync=false to
                // avoid redundant saving and callbacks from JNI.
                val ret = ColorProcessor.processHdrPlus(
                    buffers,
                    width, height,
                    combinedOrientation,
                    whiteLevel, blackLevel,
                    wb, ccm, cfa,
                    iso, exposureTime, fNumber, focalLength, captureTime,
                    targetLogIndex,
                    nativeLutPath,
                    null, // outputTiffPath
                    null, // outputJpgPath
                    null, // outputDngPath
                    digitalGain,
                    debugStats,
                    null, // outputBitmap
                    false, // isAsync (deprecated in favor of WorkManager)
                    tempRawFile.absolutePath,
                    currentZoom
                )

                val jniEndTime = System.currentTimeMillis()
                Log.d(TAG, "JNI processHdrPlus returned $ret in ${jniEndTime - jniStartTime}ms")

                if (ret == 0) {
                    val workData = androidx.work.Data.Builder()
                        .putString("tempRawPath", tempRawFile.absolutePath)
                        .putInt("width", width)
                        .putInt("height", height)
                        .putInt("orientation", combinedOrientation)
                        .putFloat("digitalGain", digitalGain)
                        .putInt("targetLog", targetLogIndex)
                        .putString("lutPath", nativeLutPath)
                        .putString("tiffPath", tiffPath)
                        .putString("jpgPath", if (saveJpg) fullResJpgFile.absolutePath else null)
                        .putString("targetUri", null) // Initial save
                        .putFloat("zoomFactor", currentZoom)
                        .putString("dngPath", linearDngPath)
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
                        .build()

                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<HdrPlusExportWorker>()
                        .setInputData(workData)
                        .build()
                    androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
                    val saveEndTime = System.currentTimeMillis()

                    val logMsg = """
                        [Total: ${saveEndTime - startTime}ms]
                        JNI Prep: ${debugStats[12]}ms, Align: ${debugStats[7]}ms, Merge: ${debugStats[8]}ms
                        Demosaic: ${debugStats[9]}ms, Denoise: ${debugStats[10]}ms, Color/Tone: ${debugStats[11]}ms
                        Halide Total: ${debugStats[0]}ms
                        Offload to Worker: ${saveEndTime - jniEndTime}ms
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
                    cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
                }

                if (frames.isNotEmpty()) {
                    try {
                        val firstFrame = frames[0]
                        val data = ByteArray(firstFrame.buffer!!.remaining())
                        firstFrame.buffer!!.rewind()
                        firstFrame.buffer!!.get(data)

                        val holder = RawImageHolder(
                            data = data,
                            width = firstFrame.width,
                            height = firstFrame.height,
                            timestamp = firstFrame.timestamp,
                            rotationDegrees = firstFrame.rotationDegrees,
                            combinedOrientation = combinedOrientation,
                            zoomRatio = currentZoom,
                            physicalId = firstFrame.physicalId
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
                    }
                }
            }
        }
    }

    private fun updateHdrPlusUi() {
        cameraUiContainerBinding?.hdrPlusToggle?.let { toggle ->
            val color = if (isHdrPlusEnabled)
                MaterialColors.getColor(toggle, com.google.android.material.R.attr.colorPrimary)
            else
                MaterialColors.getColor(toggle, com.google.android.material.R.attr.colorOnSurface)

            toggle.setTextColor(color)
            toggle.isChecked = isHdrPlusEnabled
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera2(cameraId: String) {
        // Ensure previous session is closed, but keep the thread if possible
        camera2Session?.close()
        camera2Session = null

        if (camera2Thread == null) {
            camera2Thread = HandlerThread("Camera2Thread").apply { start() }
            camera2Handler = Handler(camera2Thread!!.looper)
        }

        try {
            camera2Manager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(device: android.hardware.camera2.CameraDevice) {
                    camera2Device = device
                    createCamera2CaptureSession()
                }

                override fun onDisconnected(device: android.hardware.camera2.CameraDevice) {
                    closeCamera2()
                }

                override fun onError(device: android.hardware.camera2.CameraDevice, error: Int) {
                    Log.e(TAG, "Camera2 open error: $error")
                    closeCamera2()
                    lifecycleScope.launch(Dispatchers.Main) {
                        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                        val useCameraxFallback = prefs.getBoolean(SettingsFragment.KEY_USE_CAMERAX, false)

                        if (useCameraxFallback) {
                            Log.w(TAG, "Camera2 failed, falling back to CameraX Auto")
                            currentLens = availableLenses.find { it.isLogicalAuto }
                            updateLensUI()
                            bindCameraUseCases()
                        } else {
                            Toast.makeText(context, "Camera hardware error: $error", Toast.LENGTH_LONG).show()
                            cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
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

        val chars = camera2Manager.getCameraCharacteristics(device.id)
        val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
        val size = rawSizes?.maxByOrNull { it.width * it.height } ?: android.util.Size(4000, 3000)

        rawImageReader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.RAW_SENSOR, 8)

        val yuvSizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
        val analysisSize = yuvSizes?.filter { it.width.toFloat()/it.height.toFloat() in 1.3f..1.4f }
            ?.minByOrNull { it.width * it.height } ?: android.util.Size(640, 480)
        analysisImageReader = ImageReader.newInstance(analysisSize.width, analysisSize.height, android.graphics.ImageFormat.YUV_420_888, 2)

        analysisImageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.close()
        }, handler)

        val previewSize = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.filter { it.width.toFloat()/it.height.toFloat() in 1.3f..1.4f }
            ?.maxByOrNull { it.width * it.height } ?: android.util.Size(1440, 1080)

        Log.d(TAG, "Requesting preview surface from LutProcessor: ${previewSize.width}x${previewSize.height}")
        lutProcessor?.getInputSurface(previewSize.width, previewSize.height) { surface ->
            if (!isAdded || camera2Device?.id != device.id) {
                Log.w(TAG, "Preview surface ready but fragment detached or device changed. Ignoring.")
                return@getInputSurface
            }

            camera2PreviewSurface = surface
            val surfaces = listOf(surface, rawImageReader!!.surface, analysisImageReader!!.surface)

            try {
                device.createCaptureSession(surfaces, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        camera2Session = session
                        try {
                            val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(surface)
                            analysisImageReader?.surface?.let { request.addTarget(it) }

                            applyManualSettingsToRequest(request)

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

    private fun takeSinglePictureCamera2() {
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
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val currentZoom = if (is2xMode) 2.0f else (currentFocalLength / 24.0f)
                    val holder = copyAndroidImageToHolder(image, currentZoom, getCombinedOrientation(), currentLens?.id)
                    image.close()
                    lifecycleScope.launch {
                        processingChannel.send(holder)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process Camera2 image", e)
                    image.close()
                    processingSemaphore.release()
                }
            }, handler)

            session.capture(request.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, timestamp: Long, frameNumber: Long) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                             fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                             fragmentCameraBinding.root.postDelayed({ fragmentCameraBinding.root.foreground = null }, 50)
                        }
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

    private fun triggerHdrPlusBurstCamera2() {
        val device = camera2Device ?: run { processingSemaphore.release(); return }
        val session = camera2Session ?: run { processingSemaphore.release(); return }
        val reader = rawImageReader ?: run { processingSemaphore.release(); return }
        val handler = camera2Handler ?: run { processingSemaphore.release(); return }

        isBurstActive = true
        burstStartTime = System.currentTimeMillis()

        try {
            val result = captureResultFlow.replayCache.lastOrNull()
            val curIso = result?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val curTime = result?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
            val validIsoRange = isoRange ?: android.util.Range(100, 3200)
            val validTimeRange = exposureTimeRange ?: android.util.Range(1000L, 1_000_000_000L)
            val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val underexposureMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"

            val config = ExposureUtils.calculateHdrPlusExposure(
                curIso, curTime, validIsoRange, validTimeRange, underexposureMode
            )

            val burstSize = (prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "3") ?: "3").toIntOrNull() ?: 3

            hdrPlusBurstHelper = HdrPlusBurst(frameCount = burstSize, onBurstComplete = { frames ->
                processHdrPlusBurst(frames, config.digitalGain)
            })

            lifecycleScope.launch(Dispatchers.Main) {
                cameraUiContainerBinding?.captureProgress?.max = burstSize
                cameraUiContainerBinding?.captureProgress?.progress = 0
                cameraUiContainerBinding?.captureProgress?.visibility = View.VISIBLE
                cameraUiContainerBinding?.processingProgress?.visibility = View.VISIBLE
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 0.5f
            }

            val burstRequests = mutableListOf<android.hardware.camera2.CaptureRequest>()
            val combinedOrientation = getCombinedOrientation()
            for (i in 0 until burstSize) {
                val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                request.addTarget(reader.surface)
                request.set(android.hardware.camera2.CaptureRequest.JPEG_ORIENTATION, combinedOrientation)

                applyManualSettingsToRequest(request)

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
                        cameraUiContainerBinding?.captureProgress?.progress = framesCaptured
                    }
                    if (framesCaptured >= burstSize) {
                        watchdog.cancel()
                        lifecycleScope.launch(Dispatchers.Main) { resetBurstUi() }
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
                cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
                resetBurstUi()
            }
        }
    }

    private fun copyAndroidImageToHolder(image: android.media.Image, zoomRatio: Float, combinedOrientation: Int, physicalId: String?): RawImageHolder {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = 2 // RAW16
        val width = image.width
        val height = image.height

        val rowLength = width * pixelStride
        val data = ByteArray(rowLength * height)

        buffer.rewind()
        if (rowStride == rowLength) {
            buffer.get(data)
        } else {
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(data, y * rowLength, rowLength)
            }
        }

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
            physicalId = physicalId
        )
    }


    private fun applyManualSettingsToRequest(request: android.hardware.camera2.CaptureRequest.Builder) {
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
        }

        if (currentLens?.useCamera2 == true) {
            val deviceId = camera2Device?.id ?: currentLens?.id
            if (deviceId != null) {
                val chars = camera2Manager.getCameraCharacteristics(deviceId)
                val activeArray = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeArray != null) {
                    val targetRatio = if (is2xMode) 2.0f else (currentFocalLength / 24.0f)
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

    private fun closeCamera2() {
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

    private fun releaseCamera2Resources() {
        closeCamera2()
        camera2Thread?.quitSafely()
        camera2Thread = null
        camera2Handler = null
    }

    private fun resetBurstUi() {
        cameraUiContainerBinding?.captureProgress?.visibility = View.GONE
        cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
        isBurstActive = false

        if (processingSemaphore.availablePermits > 0) {
            cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
            cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
        } else {
            cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
            cameraUiContainerBinding?.cameraCaptureButton?.alpha = 0.5f
        }
    }
}

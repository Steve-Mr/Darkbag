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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
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
import androidx.camera.core.UseCaseGroup
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import androidx.concurrent.futures.await
import com.android.example.cameraxbasic.processor.ColorProcessor
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

    private var lutProcessor: LutSurfaceProcessor? = null
    private lateinit var lutManager: LutManager
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
    private var isBurstActive = false
    private var hdrPlusBurstHelper: HdrPlusBurst? = null
    private var lastHdrPlusConfig: ExposureUtils.ExposureConfig? = null // Cache for instant trigger

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

    /** Orientation listener to track device rotation independently of UI rotation */
    private val orientationEventListener by lazy {
        object : OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    return
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
        val rotationDegrees: Int,
        val zoomRatio: Float
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

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + rotationDegrees
            result = 31 * result + zoomRatio.hashCode()
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
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCameraToPermissions()
            )
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

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

        // Initialize Zoom Default
        val prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val defaultStr = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_LENGTH, "24") ?: "24"
        defaultFocalLength = defaultStr.toIntOrNull() ?: 24
        if (currentFocalLength == -1) {
            currentFocalLength = defaultFocalLength
        }

        // Initialize Flash State
        isFlashEnabled = prefs.getBoolean(SettingsFragment.KEY_FLASH_MODE, false)

        // Initialize HDR+ Burst Helper
        // Burst count is now dynamic, but we initialize with default.
        // It will be updated/reset in triggerHdrPlusBurst
        hdrPlusBurstHelper = HdrPlusBurst(
            frameCount = 3,
            onBurstComplete = { frames ->
                processHdrPlusBurst(frames, 1.0f)
            }
        )

        // Start processing consumer
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            for (holder in processingChannel) {
                try {
                    processImageAsync(requireContext(), holder)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image from channel", e)
                } finally {
                    processingSemaphore.release()
                    withContext(Dispatchers.Main) {
                        cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                        cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
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
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private suspend fun setUpCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        // Select lensFacing depending on the available cameras
        lensFacing = when {
            hasBackCamera() -> CameraSelector.LENS_FACING_BACK
            hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
            else -> throw IllegalStateException("Back and front camera are unavailable")
        }

        // Enable or disable switching between cameras
        updateCameraSwitchButton()

        // Build and bind the camera use cases
        bindCameraUseCases()
    }

    /** Declare and bind preview, capture and analysis use cases */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = windowMetricsCalculator.computeCurrentWindowMetrics(requireActivity()).bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val rotation = fragmentCameraBinding.viewFinder.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val cameraInfo = cameraProvider.getCameraInfo(cameraSelector)

        // Fetch Characteristics for Manual Control
        try {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            isoRange =
                camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposureTimeRange =
                camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            minFocusDistance =
                camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    ?: 0.0f
            evRange = cameraInfo.exposureState.exposureCompensationRange

            // Initialize defaults if ranges are valid
            isoRange?.let { currentIso = it.lower }
            exposureTimeRange?.let { currentExposureTime = it.lower }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch camera characteristics", e)
        }
        val capabilities = ImageCapture.getImageCaptureCapabilities(cameraInfo)
        val isRawSupported =
            capabilities.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)

        // Enable HDR+ UI if RAW is supported
        if (isRawSupported) {
            cameraUiContainerBinding?.hdrPlusToggle?.visibility = View.VISIBLE
            isHdrPlusEnabled = true
            updateHdrPlusUi()
        }

        // Force 4:3 aspect ratio to match typical sensor output and avoid cropping in preview
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    AspectRatio.RATIO_4_3,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                )
            )
            .build()

        // Configure AutoFitTextureView
        if (metrics.width() < metrics.height()) {
            fragmentCameraBinding.viewFinder.setAspectRatio(3, 4)
        } else {
            fragmentCameraBinding.viewFinder.setAspectRatio(4, 3)
        }

        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setResolutionSelector(resolutionSelector)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build()

        // ImageCapture
        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .setFlashMode(if (isFlashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .apply {
                if (isRawSupported) {
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                }
            }

        // Add Camera2 Interop Callback to capture metadata
        androidx.camera.camera2.interop.Camera2Interop.Extender(imageCaptureBuilder)
            .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val timestamp =
                        result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp != null) {
                        captureResults[timestamp] = result
                    }
                    captureResultFlow.tryEmit(result)

                    // Background Calculation for HDR+ Latency Optimization
                    if (isHdrPlusEnabled && !isBurstActive && !isManualExposure) {
                        lifecycleScope.launch(Dispatchers.Default) {
                            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                            val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L

                            // Safe check for ranges, default if null
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
        imageAnalyzer = ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setResolutionSelector(resolutionSelector)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()
            // The analyzer can then be assigned to the instance
            .also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                    // Values returned from our analyzer are passed to the attached listener
                    // We log image analysis results here - you should do something useful
                    // instead!
                    // Values returned from our analyzer are passed to the attached listener
                    // We log image analysis results here - you should do something useful
                    // instead!
                })
            }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        if (camera != null) {
            // Must remove observers from the previous camera instance
            removeCameraStateObservers(camera!!.cameraInfo)
        }

        // Manual pipeline for preview
        if (lutProcessor == null) {
            lutProcessor = LutSurfaceProcessor()
        }

        val lutBinder = object : Preview.SurfaceProvider {
            override fun onSurfaceRequested(request: SurfaceRequest) {
                // Connect Camera to LutProcessor
                lutProcessor?.onInputSurface(request)
            }
        }

        // Connect View to LutProcessor
        fragmentCameraBinding.viewFinder.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    lutProcessor?.setOutputSurface(Surface(surface), width, height)
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    lutProcessor?.setOutputSurface(Surface(surface), width, height)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    lutProcessor?.setOutputSurface(null, 0, 0)
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }

        // Check if surface is already available
        if (fragmentCameraBinding.viewFinder.isAvailable) {
            val st = fragmentCameraBinding.viewFinder.surfaceTexture
            if (st != null) {
                lutProcessor?.setOutputSurface(
                    Surface(st),
                    fragmentCameraBinding.viewFinder.width,
                    fragmentCameraBinding.viewFinder.height
                )
            }
        }

        preview?.setSurfaceProvider(cameraExecutor, lutBinder)
        updateLiveLut() // Ensure LUT is loaded

        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview!!)
            .addUseCase(imageCapture!!)
            .addUseCase(imageAnalyzer!!)
            .build()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, useCaseGroup
            )

            // Check Flash Availability
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                cameraUiContainerBinding?.flashButton?.visibility = View.VISIBLE
            } else {
                cameraUiContainerBinding?.flashButton?.visibility = View.GONE
            }

            observeCameraState(camera?.cameraInfo!!)

            // Restore Zoom
            updateZoom(false)

            // Apply Settings
            applyCameraControls()

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun removeCameraStateObservers(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.removeObservers(viewLifecycleOwner)
    }

    private fun observeCameraState(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.observe(viewLifecycleOwner) { cameraState ->
            // Camera state toasts removed as per requirement
            cameraState.error?.let { error ->
                Log.e(TAG, "Camera State Error: ${error.code}")
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

            // Close Manual Controls if open (and reset tab selection if desired, or just hide panel)
            // Ideally we just hide the panel and uncheck tabs if that's the desired UX.
            // Or just hide the panel and keep state?
            // "Clicking on tab again collapses" was requested. "Clicking outside closes" also.
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
                // Save pref
                requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(SettingsFragment.KEY_FLASH_MODE, isFlashEnabled).apply()
                updateFlashIcon(btn)
                // Update UseCase dynamically
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

            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                if (imageCapture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW) {
                    if (isHdrPlusEnabled) {
                        // Trigger Burst
                        triggerHdrPlusBurst(imageCapture)
                    } else {
                        // Standard Single RAW Capture with Processing
                        takeSinglePicture(imageCapture)
                    }
                } else {
                    // JPEG Capture
                    takeSinglePicture(imageCapture)
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
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    /**
     * Our custom image analysis class.
     *
     * <p>All we need to do is override the function `analyze` with our desired operations. Here,
     * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
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

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: Analyzer method implementation must
         * call image.close() on received images when finished using them. Otherwise, new images
         * may not be received or the camera may stall, depending on back pressure setting.
         *
         */
        override fun analyze(image: ImageProxy) {
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Keep track of frames analyzed
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image
            val luma = pixels.average()

            // Call all listeners with new value
            listeners.forEach { it(luma) }

            image.close()
        }
    }

    private fun copyImageToHolder(image: ImageProxy, zoomRatio: Float): RawImageHolder {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = 2 // 16-bit raw

        // Strictly, we want tight packing for DngCreator input stream
        // width * pixelStride is the tight packing size for a row
        val rowLength = width * pixelStride
        val dataLength = rowLength * height
        val cleanData = ByteArray(dataLength)

        if (rowStride == rowLength) {
            // Fast path: Data is already tightly packed
            if (buffer.remaining() == dataLength) {
                buffer.get(cleanData)
            } else {
                // Buffer might be larger (e.g. alignment), only get what we need
                buffer.get(cleanData, 0, dataLength)
            }
        } else {
            // Slow path: Remove padding bytes from each row
            val rowData = ByteArray(rowLength)
            // Save original position
            buffer.rewind()
            for (y in 0 until height) {
                // Calculate position of the row start
                val rowStart = y * rowStride
                if (rowStart + rowLength > buffer.capacity()) break // Safety
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
            zoomRatio = zoomRatio
        )
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private suspend fun processImageAsync(context: Context, image: RawImageHolder) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val dngName =
                    SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())

                Log.d(
                    TAG,
                    "Processing Image: Timestamp=${image.timestamp}, ZoomRatio=${image.zoomRatio}, Rotation=${image.rotationDegrees}"
                )

                val cam = camera ?: return@withContext
                val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
                val cameraManager =
                    context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val chars = cameraManager.getCameraCharacteristics(camera2Info.cameraId)

                // 1. Wait for Metadata
                val captureResult = findCaptureResult(image.timestamp)

                if (captureResult == null) {
                    Log.e(
                        TAG,
                        "Timed out waiting for CaptureResult for timestamp ${image.timestamp}"
                    )
                    return@withContext
                }

                // 2. Prepare Settings
                val prefs =
                    context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
                val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)

                // Use Active LUT filename if present, else fallback to legacy
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

                    // Store DNG bytes in memory
                    val dngOutputStream = java.io.ByteArrayOutputStream()
                    var dngBytes: ByteArray? = null

                    val orientation = when (image.rotationDegrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        270 -> ExifInterface.ORIENTATION_ROTATE_270
                        else -> ExifInterface.ORIENTATION_NORMAL
                    }
                    dngCreatorReal.setOrientation(orientation)

                    // Write DNG to memory (no thumbnail yet)
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

                    // Determine Digital Zoom for Standard Pipeline
                    // LibRaw saves the FULL image, so we might need to crop the Bitmap post-processing
                    // if the user had zoomed in (using Crop Region).
                    // Or if zoomRatio > 1.0 (Digital Zoom).

                    // Wait, `processRaw` processes the WHOLE DNG.
                    // The Bitmap output is full resolution (possibly subsampled by LibRaw if half_size used, but here full).
                    // We need to apply the crop.
                    val cropRegion =
                        captureResult.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
                    val activeArray =
                        chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

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
                    // For standard pipeline, the image should be saved as-is (unrotated pixels) because
                    // JPEG EXIF orientation handles the display, or LibRaw output is already oriented.
                    // Passing rotationDegrees caused double rotation or unwanted rotation.
                    // We pass 0 for rotation here to match original behavior.

                    // Note: `saveProcessedImage` is suspending.
                    val finalJpgUri = saveProcessedImage(
                        context,
                        bmpPath,
                        0, // Rotation disabled for standard pipeline
                        zoomFactor,
                        dngName,
                        null, // No Linear DNG here
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

                    // 6. Save Standard RAW DNG (specific to this pipeline)
                    // Insert DNG into MediaStore
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
                } // End of dngCreatorReal.use

            } catch (e: Exception) {
                Log.e(TAG, "Error in background processing", e)
            }
        }

    /**
     * Shared helper to handle Bitmap post-processing (Rotate, Crop, Compress) and Saving (JPG, TIFF, LinearDNG).
     * Deletes input temp files after saving.
     */
    private suspend fun saveProcessedImage(
        context: Context,
        bmpPath: String,
        rotationDegrees: Int,
        zoomFactor: Float,
        baseName: String,
        linearDngPath: String?,
        tiffPath: String?,
        saveJpg: Boolean,
        saveTiff: Boolean,
        onBitmapReady: ((android.graphics.Bitmap) -> Unit)? = null
    ): Uri? {
        val contentResolver = context.contentResolver
        var finalJpgUri: Uri? = null
        val bmpFile = File(bmpPath)

        // 1. Process BMP -> JPG
        if (bmpFile.exists()) {
            var processedBitmap: android.graphics.Bitmap? = null
            try {
                processedBitmap = BitmapFactory.decodeFile(bmpPath)

                // Rotate if needed
                if (processedBitmap != null && rotationDegrees != 0) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotationDegrees.toFloat())
                    val rotated = android.graphics.Bitmap.createBitmap(
                        processedBitmap, 0, 0, processedBitmap.width, processedBitmap.height, matrix, true
                    )
                    if (rotated != processedBitmap) {
                        processedBitmap.recycle()
                        processedBitmap = rotated
                    }
                }

                // Crop if needed (Digital Zoom)
                if (processedBitmap != null && zoomFactor > 1.05f) {
                    val newWidth = (processedBitmap.width / zoomFactor).toInt()
                    val newHeight = (processedBitmap.height / zoomFactor).toInt()
                    val x = (processedBitmap.width - newWidth) / 2
                    val y = (processedBitmap.height - newHeight) / 2
                    val safeX = max(0, x)
                    val safeY = max(0, y)
                    val safeWidth = min(newWidth, processedBitmap.width - safeX)
                    val safeHeight = min(newHeight, processedBitmap.height - safeY)

                    val croppedBitmap = android.graphics.Bitmap.createBitmap(
                        processedBitmap, safeX, safeY, safeWidth, safeHeight
                    )
                    if (croppedBitmap != processedBitmap) {
                        processedBitmap.recycle()
                        processedBitmap = croppedBitmap
                    }
                }

                // Invoke callback for thumbnail generation or other usage before compression/recycling
                if (processedBitmap != null) {
                    onBitmapReady?.invoke(processedBitmap)
                }

                // Save JPG
                if (saveJpg && processedBitmap != null) {
                    val jpgValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.jpg")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val jpgUri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        jpgValues
                    )
                    if (jpgUri != null) {
                        finalJpgUri = jpgUri
                        try {
                            contentResolver.openOutputStream(jpgUri)?.use { out ->
                                processedBitmap.compress(
                                    android.graphics.Bitmap.CompressFormat.JPEG,
                                    95,
                                    out
                                )
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                jpgValues.clear()
                                jpgValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                contentResolver.update(jpgUri, jpgValues, null, null)
                            }
                            Log.i(TAG, "Saved JPEG to $jpgUri")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save JPEG stream", e)
                            contentResolver.delete(jpgUri, null, null)
                            finalJpgUri = null
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error processing bitmap", t)
            } finally {
                processedBitmap?.recycle()
            }
            // Cleanup BMP
            bmpFile.delete()
        }

        // 2. Save TIFF
        if (saveTiff && tiffPath != null) {
            val tiffFile = File(tiffPath)
            if (tiffFile.exists()) {
                val tiffValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.tiff")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/tiff")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val tiffUri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    tiffValues
                )
                if (tiffUri != null) {
                    try {
                        contentResolver.openOutputStream(tiffUri)?.use { out ->
                            java.io.FileInputStream(tiffFile).copyTo(out)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            tiffValues.clear()
                            tiffValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            contentResolver.update(tiffUri, tiffValues, null, null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save TIFF", e)
                        contentResolver.delete(tiffUri, null, null)
                    }
                }
                tiffFile.delete()
            }
        }

        // 3. Save Linear DNG (HDR+ only usually)
        if (linearDngPath != null) {
            val dngFile = File(linearDngPath)
            if (dngFile.exists()) {
                val dngValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${baseName}_linear.dng")
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
                            java.io.FileInputStream(dngFile).copyTo(out)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            dngValues.clear()
                            dngValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            contentResolver.update(dngUri, dngValues, null, null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save Linear DNG", e)
                        contentResolver.delete(dngUri, null, null)
                    }
                }
                dngFile.delete()
            }
        }

        return finalJpgUri
    }

    private fun setupTapToFocus() {
        // Use DisplayOrientedMeteringPointFactory with explicit inputs since we removed PreviewView
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
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()

                // If in manual focus mode, tapping switch to AF
                isManualFocus = false
                applyCameraControls() // Apply change (clear manual focus override)

                // Also reset Focus UI if active
                if (activeManualTab == "Focus") {
                    updateManualPanel()
                }

                // Update text color for Focus Tab (Reset to auto color)
                updateTabColors()

                camera?.cameraControl?.startFocusAndMetering(action)

                // Calculate screen coordinates for Focus Ring (which is in root layout)
                // view.x/y is relative to root. event.x/y is relative to view.
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

        // Cancel any ongoing animation to prevent conflicts from rapid taps
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
                // If checking the same tab as active, we might want to toggle off?
                // MaterialButtonToggleGroup single selection mode makes it hard to deselect by clicking same item
                // unless selectionRequired=false. We set selectionRequired=false in XML.

                // However, the listener fires when checked state changes.
                // If I click "Focus" while it's checked, it might uncheck it.
                // Let's rely on isChecked.

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
                // If unchecking, and no other button is checked
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
            updateManualPanel() // Update slider position
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
                // 0 is Far (0.0), Max is Near (minFocusDistance)
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
                    // Logarithmic scale
                    // v = min * (max/min)^ratio
                    val minVal = range.lower.toDouble()
                    val maxVal = range.upper.toDouble()
                    val res = minVal * Math.pow(maxVal / minVal, ratio.toDouble())
                    currentExposureTime = res.toLong()
                    isManualExposure = true

                    // Format text
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
                    // EV doesn't set isManualExposure flag as it works in Auto.
                    // But if isManualExposure is TRUE, EV does nothing.
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

        // Set initial UI state
        updateZoomUI(false)
    }

    private fun updateZoom(animate: Boolean) {
        val targetRatio = if (is2xMode) {
            2.0f
        } else {
            currentFocalLength / 24.0f
        }

        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio
            ?: 8.0f // Default high enough if null
        val ratio = targetRatio.coerceAtMost(maxZoom)

        camera?.cameraControl?.setZoomRatio(ratio)
        updateZoomUI(animate)
    }

    private fun updateZoomUI(animate: Boolean) {
        val binding = cameraUiContainerBinding ?: return
        val activeColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val inactiveColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)

        if (is2xMode) {
            binding.btnZoom2x?.setTextColor(activeColor)
            binding.btnZoomToggle?.setTextColor(inactiveColor)

            // If we just switched to 2x, we might want to ensure 1x label is generic or last state?
            // Requirement: "user at 2x clicks 1x returns to default".
            // Label can just remain "1x" or whatever it was?
            // Let's reset it to "1x" for clarity as "Standard".
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
        val cameraControl = camera?.cameraControl ?: return
        val camera2Control = Camera2CameraControl.from(cameraControl)
        val builder = CaptureRequestOptions.Builder()

        // Global Settings: Anti-Banding
        val prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val antiBandingMode = when (prefs.getString(SettingsFragment.KEY_ANTIBANDING, "Auto")) {
            "50Hz" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            "60Hz" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            "Off" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antiBandingMode)

        // Focus
        if (isManualFocus) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                currentFocusDistance
            )
        }

        // Exposure
        if (isManualExposure) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            builder.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                currentExposureTime
            )
        }

        camera2Control.setCaptureRequestOptions(builder.build())

        // EV
        if (!isManualExposure) {
            cameraControl.setExposureCompensationIndex(currentEvIndex)
        }
    }

    private fun refreshLutList() {
        val binding = cameraUiContainerBinding ?: return
        val rv = binding.lutList ?: return

        // Ensure LayoutManager
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
                    // Update Prefs
                    prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                    updateLiveLut()

                    // Optimized Notify
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
                    // Update Prefs
                    prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, file.name).apply()
                    updateLiveLut()

                    // Optimized Notify
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
                        // size = cuberoot(len/3)
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
    }
    private fun takeSinglePicture(imageCapture: ImageCapture) {
        if (imageCapture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW) {
            // RAW Capture with Processing
            imageCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            // 1. Immediate Copy (Free the pipeline)
                            val currentZoom =
                                if (is2xMode) 2.0f else (currentFocalLength / 24.0f)

                            val holder = copyImageToHolder(image, currentZoom)
                            image.close() // Close ASAP

                            // 2. Queue for Processing
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
                                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled =
                                    true
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

            // Optimistic UI update
            if (processingSemaphore.availablePermits == 0) {
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 0.5f
            }

        } else {
            processingSemaphore.release() // Not raw, release immediately (logic for JPG path)
            Toast.makeText(
                requireContext(),
                "RAW capture is not supported on this device.",
                Toast.LENGTH_SHORT
            ).show()
        }

        // We can only change the foreground Drawable using API level 23+ API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Display flash animation to indicate that photo was captured
            fragmentCameraBinding.root.postDelayed({
                fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                fragmentCameraBinding.root.postDelayed(
                    { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS
                )
            }, ANIMATION_SLOW_MILLIS)
        }
    }

    private fun triggerHdrPlusBurst(imageCapture: ImageCapture) {
        if (isBurstActive) {
            Log.d(TAG, "Burst already active, ignoring trigger")
            return
        }
        isBurstActive = true

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                // 1. Get Calculated Exposure (Instant)
                // Use cached config if available to skip calculation delay
                val config = lastHdrPlusConfig ?: run {
                    // Fallback if cache empty
                    val result = captureResultFlow.replayCache.lastOrNull() ?: captureResultFlow.first()
                    val currentIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                    val currentTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
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

                // 2. Apply Manual Exposure for Burst
                val cameraControl = camera?.cameraControl
                if (cameraControl != null) {
                    val camera2Control = Camera2CameraControl.from(cameraControl)
                    val builder = CaptureRequestOptions.Builder()
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF
                    )
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, config.iso)
                    builder.setCaptureRequestOption(
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        config.exposureTime
                    )
                    camera2Control.setCaptureRequestOptions(builder.build()).await()
                }

                // Slight delay to ensure AE settles
                delay(AE_SETTLE_DELAY_MS)

                // 3. Get Burst Size Preference
                val prefs = requireContext().getSharedPreferences(
                    SettingsFragment.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                val burstSizeStr = prefs.getString(SettingsFragment.KEY_HDR_BURST_COUNT, "3") ?: "3"
                val burstSize = burstSizeStr.toIntOrNull() ?: 3

                // 4. Re-initialize helper with correct count & gain
                hdrPlusBurstHelper = HdrPlusBurst(
                    frameCount = burstSize,
                    onBurstComplete = { frames ->
                        processHdrPlusBurst(frames, config.digitalGain)
                    }
                )

                // Initialize UI for Burst
                cameraUiContainerBinding?.captureProgress?.max = burstSize
                cameraUiContainerBinding?.captureProgress?.progress = 0
                cameraUiContainerBinding?.captureProgress?.visibility = View.VISIBLE
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 0.5f

                Toast.makeText(
                    requireContext(),
                    "Capturing HDR+ Burst ($burstSize frames)...",
                    Toast.LENGTH_SHORT
                ).show()

                Log.d(TAG, "Starting HDR+ Burst (Sequential, $burstSize frames)")

                // Sequential burst to avoid overloading CameraX request queue
                recursiveBurstCapture(imageCapture, burstSize, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HDR+ burst", e)
                Toast.makeText(
                    requireContext(),
                    "HDR+ setup failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                // Ensure state is cleaned up on failure
                cameraUiContainerBinding?.captureProgress?.visibility = View.GONE
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
                isBurstActive = false
                processingSemaphore.release()
                // Attempt to restore camera controls
                applyCameraControls()
            }
        }
    }

    private fun recursiveBurstCapture(imageCapture: ImageCapture, totalFrames: Int, currentFrame: Int) {
        if (currentFrame >= totalFrames) {
            Log.d(TAG, "HDR+ Burst Capture sequence complete.")
            // Restore Auto Exposure (or previous state)
            lifecycleScope.launch(Dispatchers.Main) {
                applyCameraControls()
                // Reset Burst Active state immediately to allow background processing
                isBurstActive = false
                cameraUiContainerBinding?.captureProgress?.visibility = View.GONE
                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
            }
            return
        }

        Log.d(TAG, "Capturing burst frame ${currentFrame + 1}/$totalFrames")
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.d(TAG, "Burst frame ${currentFrame + 1} captured successfully.")

                    lifecycleScope.launch(Dispatchers.Main) {
                        cameraUiContainerBinding?.captureProgress?.progress = currentFrame + 1
                    }

                    val helper = hdrPlusBurstHelper
                    if (helper != null) {
                        try {
                            helper.addFrame(image)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to add frame to burst", e)
                            lifecycleScope.launch(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Burst failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                applyCameraControls()
                                cameraUiContainerBinding?.captureProgress?.visibility = View.GONE
                                cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                                cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
                            }
                            isBurstActive = false
                            processingSemaphore.release()
                            return
                        }
                    } else {
                        Log.e(TAG, "HdrPlusBurst helper is null, closing image manually.")
                        image.close()
                        lifecycleScope.launch(Dispatchers.Main) {
                            cameraUiContainerBinding?.captureProgress?.visibility = View.GONE
                            cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                            cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
                        }
                        isBurstActive = false
                        processingSemaphore.release()
                        return
                    }
                    // Trigger next frame immediately
                    recursiveBurstCapture(imageCapture, totalFrames, currentFrame + 1)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Burst frame ${currentFrame + 1} failed: ${exception.message}")
                    // Abort burst on error? Or try next?
                    // If we abort, the helper never finishes.
                    // For now, let's try next, but the helper won't reach count.
                    // Better to reset/abort.
                    lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Burst failed at frame ${currentFrame + 1}", Toast.LENGTH_SHORT).show()
                        // Restore AE on failure too
                        applyCameraControls()
                        cameraUiContainerBinding?.captureProgress?.visibility = View.GONE
                        cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                        cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
                    }
                    hdrPlusBurstHelper?.reset()
                    isBurstActive = false // Reset active flag
                    processingSemaphore.release() // Release lock since we are aborting
                }
            }
        )
    }

    private suspend fun findCaptureResult(timestamp: Long, tolerance: Long = 5_000_000L): TotalCaptureResult? {
        // 1. Check cache first for an immediate match.
        captureResults.entries.find { abs(it.key - timestamp) < tolerance }?.value?.let { return it }

        // 2. If not in cache, wait on the flow with a timeout.
        return withTimeoutOrNull(3000) {
            captureResultFlow.first { res ->
                val ts = res.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                ts != null && abs(ts - timestamp) < tolerance
            }
        }
    }

    private fun processHdrPlusBurst(frames: List<HdrFrame>, digitalGain: Float) {
        val currentZoom = if (is2xMode) 2.0f else (currentFocalLength / 24.0f)

        lifecycleScope.launch(Dispatchers.IO) {
            var fallbackSent = false
            try {
                val context = context ?: run {
                    Log.w(TAG, "processHdrPlusBurst aborted: Fragment context is null.")
                    return@launch
                }

                Log.d(TAG, "processHdrPlusBurst started with ${frames.size} frames. DigitalGain=$digitalGain")

                // 1. Prepare buffers
                val width = frames[0].width
                val height = frames[0].height
                val rotationDegrees = frames[0].rotationDegrees

                val buffers = frames.map { it.buffer!! }.toTypedArray()

                // Need characteristics for static info
                var chars: CameraCharacteristics? = null
                val cam = camera
                val camInfo = cam?.cameraInfo
                if (camInfo is Camera2CameraInfo) {
                    chars = Camera2CameraInfo.extractCameraCharacteristics(camInfo)
                }

                // 2. Metadata (WB, CCM, BlackLevel)
                val timestamp = frames[0].timestamp
                val result = findCaptureResult(timestamp)

                // Default values
                var whiteLevel = 1023
                var blackLevel = 64
                var wb = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)
                var ccm = floatArrayOf(
                    2.0f, -1.0f, 0.0f,
                    -0.5f, 2.0f, -0.5f,
                    0.0f, -1.0f, 2.0f
                )
                var cfa = 0 // Default RGGB

                if (chars != null) {
                    whiteLevel = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                    val bl = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                    if (bl != null) blackLevel = bl.getOffsetForIndex(0, 0)

                    val cfaEnum = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                    if (cfaEnum != null) cfa = cfaEnum // Pass Raw Enum (0..3)
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

                // 4. Settings (Log/LUT)
                val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
                val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)
                val activeLutName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
                var nativeLutPath: String? = null
                if (activeLutName != null) {
                    val lutFile = File(File(context.filesDir, "luts"), activeLutName)
                    if (lutFile.exists()) nativeLutPath = lutFile.absolutePath
                }

                Log.d(TAG, "Settings: Log=$targetLogName ($targetLogIndex), LUT=$nativeLutPath")

                // Extract Real Metadata
                val iso = result?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val exposureTime = result?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
                val fNumber = result?.get(android.hardware.camera2.CaptureResult.LENS_APERTURE) ?: 1.8f
                val focalLength = result?.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH) ?: 0.0f
                val captureTime = System.currentTimeMillis()

                // 5. Output Path
                val dngName = SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis()) + "_HDRPLUS"
                val saveTiff = prefs.getBoolean(SettingsFragment.KEY_SAVE_TIFF, true)
                val saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true)

                val tiffFile = File(context.cacheDir, "$dngName.tiff")
                val tiffPath = if(saveTiff) tiffFile.absolutePath else null

                val bmpFile = File(context.cacheDir, "$dngName.bmp")
                val bmpPath = bmpFile.absolutePath // JNI writes BMP here

                // Save Linear DNG (as requested)
                val linearDngFile = File(context.cacheDir, "${dngName}_linear.dng")
                val linearDngPath = linearDngFile.absolutePath

                Log.d(TAG, "Output Paths: BMP=$bmpPath, TIFF=$tiffPath, DNG=$linearDngPath")

                // 6. JNI Call
                val startTime = System.currentTimeMillis()
                // Ensure buffers are rewound just in case
                buffers.forEach { it.rewind() }

                val ret = ColorProcessor.processHdrPlus(
                    buffers,
                    width, height,
                    rotationDegrees,
                    whiteLevel, blackLevel,
                    wb, ccm, cfa,
                    iso, exposureTime, fNumber, focalLength, captureTime,
                    targetLogIndex,
                    nativeLutPath,
                    tiffPath,
                    bmpPath,
                    linearDngPath,
                    digitalGain
                )

                Log.d(TAG, "JNI processHdrPlus returned $ret in ${System.currentTimeMillis() - startTime}ms")

                if (ret == 0) {
                    val finalJpgUri = saveProcessedImage(
                        context,
                        bmpPath,
                        rotationDegrees,
                        currentZoom,
                        dngName,
                        linearDngPath,
                        tiffPath,
                        saveJpg,
                        saveTiff
                    )

                    // Update UI
                    withContext(Dispatchers.Main) {
                        if (finalJpgUri != null) {
                            Toast.makeText(context, "HDR+ Saved!", Toast.LENGTH_SHORT).show()
                            setGalleryThumbnail(finalJpgUri.toString())
                        } else {
                            // If user didn't request JPG, we might still have succeeded with others.
                            // But usually JPG is default.
                            if (saveJpg) {
                                Toast.makeText(context, "HDR+ Save Failed", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "HDR+ Saved!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                } else {
                    throw RuntimeException("JNI processing returned error code: $ret")
                }

            } catch (e: Exception) {
                Log.e(TAG, "HDR+ processing failed, falling back to single shot", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "HDR+ failed, saving single frame...", Toast.LENGTH_SHORT).show()
                }

                // Fallback: Use the first frame as a single shot
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
                            zoomRatio = currentZoom
                        )
                        processingChannel.send(holder)
                        fallbackSent = true
                    } catch (fallbackEx: Exception) {
                        Log.e(TAG, "Fallback failed", fallbackEx)
                    }
                }
            } finally {
                frames.forEach { it.close() }
                // Release semaphore ONLY if we didn't hand off the work to the channel
                if (!fallbackSent) {
                    processingSemaphore.release()
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
}

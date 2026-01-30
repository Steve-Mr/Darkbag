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
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.media.ExifInterface
import android.util.Log
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.widget.SeekBar
import android.widget.ToggleButton
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import androidx.concurrent.futures.await
import com.android.example.cameraxbasic.processor.ColorProcessor
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
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
import com.android.example.cameraxbasic.utils.simulateClick
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.camera.core.CameraEffect
import androidx.camera.core.UseCaseGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.example.cameraxbasic.utils.LutManager
import com.android.example.cameraxbasic.processor.LutSurfaceProcessor
import androidx.activity.result.contract.ActivityResultContracts

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

    private lateinit var lutManager: LutManager
    private lateinit var lutSurfaceProcessor: LutSurfaceProcessor
    private lateinit var lutAdapter: LutAdapter

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowMetricsCalculator: WindowMetricsCalculator

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (lutManager.importLut(uri)) {
                    withContext(Dispatchers.Main) {
                        lutAdapter.updateList()
                        Toast.makeText(requireContext(), "LUT imported", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Failed to import LUT", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Manual Control State
    private var isManualFocus = false
    private var isManualExposure = false
    private var activeManualTab: String? = null

    private var minFocusDistance = 0.0f
    private var isoRange: android.util.Range<Int>? = null
    private var exposureTimeRange: android.util.Range<Long>? = null
    private var evRange: android.util.Range<Int>? = null

    private var currentFocusDistance = 0.0f
    private var currentIso = 100
    private var currentExposureTime = 10_000_000L // 10ms
    private var currentEvIndex = 0

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
    private val captureResults = java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<Long, TotalCaptureResult>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TotalCaptureResult>?): Boolean {
            return size > 300
        }
    })

    // Rate limiting semaphore to prevent OOM
    private val processingSemaphore = kotlinx.coroutines.sync.Semaphore(2)
    private val processingChannel = kotlinx.coroutines.channels.Channel<RawImageHolder>(2)

    data class RawImageHolder(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val rotationDegrees: Int
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

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + rotationDegrees
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

        lutSurfaceProcessor.release()

        // Shut down our background executor
        cameraExecutor.shutdown()

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

        lutManager = LutManager(requireContext())
        lutSurfaceProcessor = LutSurfaceProcessor()

        // Load initial LUT
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val initialLut = prefs.getString(SettingsFragment.KEY_LUT_URI, null)
        lutSurfaceProcessor.updateLut(initialLut)

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
            isoRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposureTimeRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            minFocusDistance = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
            evRange = cameraInfo.exposureState.exposureCompensationRange

            // Initialize defaults if ranges are valid
            isoRange?.let { currentIso = it.lower }
            exposureTimeRange?.let { currentExposureTime = it.lower }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch camera characteristics", e)
        }
        val capabilities = ImageCapture.getImageCaptureCapabilities(cameraInfo)
        val isRawSupported = capabilities.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)

        // Force 4:3 aspect ratio to match typical sensor output and avoid cropping in preview
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .build()

        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setResolutionSelector(resolutionSelector)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build()

        // Configure LUT Processor and Effect
        val effect = CameraEffect(
            CameraEffect.PREVIEW,
            cameraExecutor,
            lutSurfaceProcessor
        ) { error ->
            Log.e(TAG, "CameraEffect error: ${error.cause}", error)
        }

        // Sync Log setting
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
        val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)
        lutSurfaceProcessor.setTargetLog(targetLogIndex)

        // ImageCapture
        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
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
                    val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp != null) {
                        captureResults[timestamp] = result
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
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        if (camera != null) {
            // Must remove observers from the previous camera instance
            removeCameraStateObservers(camera!!.cameraInfo)
        }

        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview!!)
            .addUseCase(imageCapture!!)
            .addUseCase(imageAnalyzer!!)
            .addEffect(effect)
            .build()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, useCaseGroup)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
            observeCameraState(camera?.cameraInfo!!)
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

        // Listener for settings button
        cameraUiContainerBinding?.settingsButton?.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToSettings())
        }

        // Setup LUT List
        lutAdapter = LutAdapter(lutManager) { file ->
            if (file == null) {
                // "Import" clicked
                lutPicker.launch(arrayOf("*/*"))
            } else {
                // LUT selected
                Toast.makeText(requireContext(), "Selected: ${lutManager.getDisplayName(file)}", Toast.LENGTH_SHORT).show()
                lutSurfaceProcessor.updateLut(file.absolutePath)
                // Persist selection for capture usage
                requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(SettingsFragment.KEY_LUT_URI, file.absolutePath)
                    .apply()
            }
        }

        cameraUiContainerBinding?.lutList?.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = lutAdapter
        }

        // LUT Button
        cameraUiContainerBinding?.lutButton?.setOnClickListener {
             val list = cameraUiContainerBinding?.lutList ?: return@setOnClickListener
             if (list.visibility == View.VISIBLE) {
                 list.visibility = View.GONE
             } else {
                 list.visibility = View.VISIBLE
                 lutAdapter.updateList()
             }
        }

        // Listener for button used to capture photo
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {
            // Check concurrency limit
            if (!processingSemaphore.tryAcquire()) {
                Toast.makeText(requireContext(), "Processing queue full, please wait...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                if (imageCapture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW) {
                    // RAW Capture with Processing
                    imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                // 1. Immediate Copy (Free the pipeline)
                                val holder = copyImageToHolder(image)
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
                                    Toast.makeText(requireContext(), "Memory full, photo not saved", Toast.LENGTH_SHORT).show()
                                    cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
                                    cameraUiContainerBinding?.cameraCaptureButton?.alpha = 1.0f
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error during capture copy", e)
                                image.close()
                                processingSemaphore.release()
                                lifecycleScope.launch(Dispatchers.Main) {
                                    cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = true
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

                    // Optimistic UI update: disable button if we think we might be full,
                    // though the semaphore check handles the hard limit.
                    // We can also dim the button to indicate "busy" if usage is high.
                    if (processingSemaphore.availablePermits == 0) {
                        cameraUiContainerBinding?.cameraCaptureButton?.isEnabled = false
                        cameraUiContainerBinding?.cameraCaptureButton?.alpha = 0.5f
                    }

                } else {
                    processingSemaphore.release() // Not raw, release immediately (logic for JPG path)
                    Toast.makeText(requireContext(), "RAW capture is not supported on this device.", Toast.LENGTH_SHORT).show()
                }

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Display flash animation to indicate that photo was captured
                    fragmentCameraBinding.root.postDelayed({
                        fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                        fragmentCameraBinding.root.postDelayed(
                                { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
            }
        }

        // Setup for button used to switch cameras
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
                        Toast.makeText(requireContext(), "No gallery app installed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = hasBackCamera() && hasFrontCamera()
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

    private fun copyImageToHolder(image: ImageProxy): RawImageHolder {
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
            rotationDegrees = image.imageInfo.rotationDegrees
        )
    }

    // ... processImageAsync ... (omitted since it wasn't modified in logic, but included in full file write)
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private suspend fun processImageAsync(context: Context, image: RawImageHolder) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val dngName = SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())

        val cam = camera ?: return@withContext
        val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val chars = cameraManager.getCameraCharacteristics(camera2Info.cameraId)

        // 1. Wait for Metadata
        var attempts = 0
        var captureResult: TotalCaptureResult? = null
        val tolerance = 5_000_000L // 5ms in nanoseconds

        while (attempts < 25) { // Wait up to 5 seconds
            // Try exact match
            captureResult = captureResults[image.timestamp]

            // Try fuzzy match
            if (captureResult == null) {
                 synchronized(captureResults) {
                    captureResult = captureResults.entries.find {
                        kotlin.math.abs(it.key - image.timestamp) < tolerance
                    }?.value
                 }
            }

            if (captureResult != null) break

            kotlinx.coroutines.delay(200)
            attempts++
        }

        if (captureResult == null) {
            Log.e(TAG, "Timed out waiting for CaptureResult for timestamp ${image.timestamp}")
            return@withContext
        }

        // 2. Prepare Settings
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None")
        val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)
        val lutPath = prefs.getString(SettingsFragment.KEY_LUT_URI, null)

        var nativeLutPath: String? = null
        if (lutPath != null) {
            if (lutPath.startsWith("content://")) {
                val lutFile = File(context.cacheDir, "temp_lut.cube")
                try {
                    contentResolver.openInputStream(Uri.parse(lutPath))?.use { input ->
                        FileOutputStream(lutFile).use { output -> input.copyTo(output) }
                    }
                    nativeLutPath = lutFile.absolutePath
                } catch (e: Exception) { Log.e(TAG, "Failed to load LUT", e) }
            } else {
                nativeLutPath = lutPath
            }
        }

        val saveTiff = prefs.getBoolean(SettingsFragment.KEY_SAVE_TIFF, true)
        val saveJpg = prefs.getBoolean(SettingsFragment.KEY_SAVE_JPG, true)
        val useGpu = prefs.getBoolean(SettingsFragment.KEY_USE_GPU, false)

        val tiffFile = if (saveTiff) File(context.cacheDir, "$dngName.tiff") else null
        val tiffPath = tiffFile?.absolutePath
        val bmpPath = File(context.cacheDir, "temp_${dngName}.bmp").absolutePath // Unique temp name

        // 3. Process Color (Generate BMP for JPG and Thumbnail)
        // We need a DirectByteBuffer for JNI
        val directBuffer = ByteBuffer.allocateDirect(image.data.size)
        directBuffer.put(image.data)
        directBuffer.rewind()

        // Stride is now exactly width * 2 because we packed it
        val packedStride = image.width * 2

        val whiteLevel = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val blackLevelPattern = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevel = blackLevelPattern?.getOffsetForIndex(0,0) ?: 0
        val cfa = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

        val colorTransform = chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            ?: chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_FORWARD_MATRIX1)

        val ccm = FloatArray(9)
        if (colorTransform != null) {
            val rawMat = IntArray(18)
            colorTransform.copyElements(rawMat, 0)
            for (i in 0 until 9) {
                val num = rawMat[i * 2]
                val den = rawMat[i * 2 + 1]
                ccm[i] = if (den != 0) num.toFloat() / den.toFloat() else 0f
            }
        } else {
            ccm[0]=1f; ccm[4]=1f; ccm[8]=1f;
        }

        val neutral = captureResult.get(android.hardware.camera2.CaptureResult.SENSOR_NEUTRAL_COLOR_POINT) as? RggbChannelVector
        val wb = if (neutral != null) {
            val rVal = neutral.red
            val gEvenVal = neutral.greenEven
            val bVal = neutral.blue
            val r = if (rVal > 0) 1.0f / rVal else 1.0f
            val g = if (gEvenVal > 0) 1.0f / gEvenVal else 1.0f
            val b = if (bVal > 0) 1.0f / bVal else 1.0f
            floatArrayOf(r, g, g, b)
        } else {
            floatArrayOf(2.0f, 1.0f, 1.0f, 2.0f)
        }

        try {
            // Process to generate BMP/TIFF
            val result = ColorProcessor.processRaw(
                directBuffer, image.width, image.height, packedStride, whiteLevel, blackLevel, cfa,
                wb, ccm, targetLogIndex, nativeLutPath, tiffPath, bmpPath, useGpu
            )

            if (result == 1) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "GPU processing failed. Fallback to CPU used.", Toast.LENGTH_LONG).show()
                }
            } else if (result < 0) {
                throw RuntimeException("ColorProcessor returned error code $result")
            }

            // Load the processed image (BMP) for thumbnail and JPG saving
            var processedBitmap: android.graphics.Bitmap? = null
            if (File(bmpPath).exists()) {
                 processedBitmap = BitmapFactory.decodeFile(bmpPath)
            }

            // 4. Save DNG (with Thumbnail)
            val dngCreatorReal = android.hardware.camera2.DngCreator(chars, captureResult)
            try {
                val orientation = when (image.rotationDegrees) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                dngCreatorReal.setOrientation(orientation)

                // Set Thumbnail if available (Resize to max 256x256)
                if (processedBitmap != null) {
                    val maxThumbSize = 256
                    val scale = min(maxThumbSize.toDouble() / processedBitmap.width, maxThumbSize.toDouble() / processedBitmap.height)
                    val thumbWidth = (processedBitmap.width * scale).toInt()
                    val thumbHeight = (processedBitmap.height * scale).toInt()

                    val thumbBitmap = if (scale < 1.0) {
                        android.graphics.Bitmap.createScaledBitmap(processedBitmap, thumbWidth, thumbHeight, true)
                    } else {
                        processedBitmap
                    }

                    dngCreatorReal.setThumbnail(thumbBitmap)
                }

                // Insert DNG into MediaStore
                val dngValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$dngName.dng")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val dngUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, dngValues)
                if (dngUri != null) {
                    try {
                        val dngOut = contentResolver.openOutputStream(dngUri)
                        if (dngOut != null) {
                            dngOut.use { out ->
                                // Use ByteArrayInputStream for the raw data
                                val inputStream = java.io.ByteArrayInputStream(image.data)
                                dngCreatorReal.writeInputStream(out, android.util.Size(image.width, image.height), inputStream, 0)
                            }
                            Log.d(TAG, "Saved DNG to $dngUri")

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                dngValues.clear()
                                dngValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                contentResolver.update(dngUri, dngValues, null, null)
                            }
                        } else {
                            Log.e(TAG, "Failed to open OutputStream for $dngUri")
                            contentResolver.delete(dngUri, null, null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing DNG to MediaStore", e)
                        contentResolver.delete(dngUri, null, null)
                    }
                } else {
                    Log.e(TAG, "Failed to create MediaStore entry for DNG")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save DNG", e)
            } finally {
                dngCreatorReal.close()
            }

            // 5. Save TIFF
            if (tiffFile != null && tiffFile.exists()) {
                val tiffValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$dngName.tiff")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/tiff")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val tiffUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, tiffValues)
                if (tiffUri != null) {
                    contentResolver.openOutputStream(tiffUri)?.use { out ->
                        java.io.FileInputStream(tiffFile).copyTo(out)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tiffValues.clear()
                        tiffValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(tiffUri, tiffValues, null, null)
                    }
                }
                tiffFile.delete()
            }

            // 6. Save JPG
            var finalJpgUri: Uri? = null
            if (saveJpg && processedBitmap != null) {
                 val jpgValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$dngName.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Darkbag")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val jpgUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, jpgValues)
                if (jpgUri != null) {
                    finalJpgUri = jpgUri
                    contentResolver.openOutputStream(jpgUri)?.use { out ->
                        // Apply rotation
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(image.rotationDegrees.toFloat())
                        val rotatedBitmap = android.graphics.Bitmap.createBitmap(processedBitmap, 0, 0, processedBitmap.width, processedBitmap.height, matrix, true)
                        rotatedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        jpgValues.clear()
                        jpgValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(jpgUri, jpgValues, null, null)
                    }
                }
            }

            // Clean up BMP
            if (File(bmpPath).exists()) File(bmpPath).delete()

            // 7. Update UI (Thumbnail)
            // Use finalJpgUri if available, otherwise DNG or TIFF uri (requires finding them again or logic change)
            // But we can just use the path or the URI we just created.
            // Simplified: Refresh thumbnail on main thread using the filename we just generated
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (saveJpg && finalJpgUri != null) {
                    setGalleryThumbnail(finalJpgUri.toString())
                } else {
                    // Fallback to DNG if JPG not saved? Or just the latest file.
                     mediaStoreUtils.getLatestImageFilename()?.let { setGalleryThumbnail(it) }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in background processing", e)
        }
    }

    private fun initManualControls() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(SettingsFragment.KEY_MANUAL_CONTROLS, false)
        if (!enabled) return

        val binding = cameraUiContainerBinding ?: return
        binding.manualControlsRoot?.visibility = View.VISIBLE

        // Tab Listeners
        val tabs = mutableMapOf<ToggleButton, String>()
        binding.btnTabFocus?.let { tabs[it] = "Focus" }
        binding.btnTabIso?.let { tabs[it] = "ISO" }
        binding.btnTabShutter?.let { tabs[it] = "Shutter" }
        binding.btnTabEv?.let { tabs[it] = "EV" }

        tabs.forEach { (btn, name) ->
            btn.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    // Uncheck others
                    tabs.keys.filter { it != btn }.forEach { it.isChecked = false }
                    activeManualTab = name
                    binding.manualPanel?.visibility = View.VISIBLE
                    updateManualPanel()
                } else {
                    if (activeManualTab == name) {
                        activeManualTab = null
                        binding.manualPanel?.visibility = View.GONE
                    }
                }
            }
        }

        // Manual Panel Listeners
        binding.seekbarManual?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                handleManualProgress(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnManualAuto?.setOnClickListener {
            resetCurrentManualParameter()
        }

        // Focus Extras
        binding.btnFocusNear?.setOnClickListener {
            currentFocusDistance = minFocusDistance
            isManualFocus = true
            applyManualControls()
            updateManualPanel() // Update slider position
            updateTabColors()
        }

        binding.btnFocusFar?.setOnClickListener {
            currentFocusDistance = 0.0f
            isManualFocus = true
            applyManualControls()
            updateManualPanel()
            updateTabColors()
        }

        // Tap to Focus on ViewFinder
        fragmentCameraBinding.viewFinder.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val factory = fragmentCameraBinding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()

                // If in manual focus mode, tapping switch to AF
                isManualFocus = false
                applyManualControls() // Apply change (clear manual focus override)

                // Also reset Focus UI if active
                if (activeManualTab == "Focus") {
                     updateManualPanel()
                }

                // Update text color for Focus Tab (Reset to auto color)
                updateTabColors()

                camera?.cameraControl?.startFocusAndMetering(action)
                view.performClick()
            }
            true
        }
    }

    private fun handleManualProgress(progress: Int) {
        val binding = cameraUiContainerBinding ?: return
        val max = binding.seekbarManual?.max ?: 100
        val ratio = progress.toFloat() / max

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
                         binding.tvManualValue?.text = String.format("1/%.0fs", 1000.0/ms)
                     } else {
                         binding.tvManualValue?.text = String.format("%.1fs", ms/1000.0)
                     }
                }
            }
            "EV" -> {
                 evRange?.let { range ->
                     currentEvIndex = (range.lower + (range.upper - range.lower) * ratio).toInt()
                     // EV doesn't set isManualExposure flag as it works in Auto.
                     // But if isManualExposure is TRUE, EV does nothing.
                     if (isManualExposure) {
                         Toast.makeText(requireContext(), "EV disabled in Manual Exposure", Toast.LENGTH_SHORT).show()
                     } else {
                         binding.tvManualValue?.text = "$currentEvIndex"
                     }
                 }
            }
        }
        applyManualControls()
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
        applyManualControls()
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
        binding.focusExtras?.visibility = if (activeManualTab == "Focus") View.VISIBLE else View.GONE

        val max = binding.seekbarManual?.max ?: 100

        when (activeManualTab) {
            "Focus" -> {
                if (isManualFocus) {
                    val ratio = if (minFocusDistance > 0) currentFocusDistance / minFocusDistance else 0f
                    binding.seekbarManual?.progress = (ratio * max).toInt()
                    binding.tvManualValue?.text = String.format("%.2f", currentFocusDistance)
                } else {
                    binding.tvManualValue?.text = "Auto"
                    binding.seekbarManual?.progress = 0
                }
            }
            "ISO" -> {
                isoRange?.let { range ->
                    if (isManualExposure) {
                        val ratio = (currentIso - range.lower).toFloat() / (range.upper - range.lower)
                        binding.seekbarManual?.progress = (ratio * max).toInt()
                        binding.tvManualValue?.text = "$currentIso"
                    } else {
                         binding.tvManualValue?.text = "Auto"
                         binding.seekbarManual?.progress = 0
                    }
                }
            }
            "Shutter" -> {
                exposureTimeRange?.let { range ->
                    if (isManualExposure) {
                        val minVal = range.lower.toDouble()
                        val maxVal = range.upper.toDouble()
                        val ratio = Math.log(currentExposureTime.toDouble() / minVal) / Math.log(maxVal / minVal)
                        binding.seekbarManual?.progress = (ratio * max).toInt()
                         val ms = currentExposureTime / 1_000_000.0
                         if (ms < 1000) {
                             binding.tvManualValue?.text = String.format("1/%.0fs", 1000.0/ms)
                         } else {
                             binding.tvManualValue?.text = String.format("%.1fs", ms/1000.0)
                         }
                    } else {
                        binding.tvManualValue?.text = "Auto"
                        binding.seekbarManual?.progress = 0
                    }
                }
            }
            "EV" -> {
                evRange?.let { range ->
                    val ratio = (currentEvIndex - range.lower).toFloat() / (range.upper - range.lower)
                    binding.seekbarManual?.progress = (ratio * max).toInt()
                    binding.tvManualValue?.text = "$currentEvIndex"
                }
            }
        }
    }

    private fun applyManualControls() {
        val cameraControl = camera?.cameraControl ?: return
        val camera2Control = Camera2CameraControl.from(cameraControl)
        val builder = CaptureRequestOptions.Builder()

        // Focus
        if (isManualFocus) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
        }

        // Exposure
        if (isManualExposure) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
        }

        camera2Control.setCaptureRequestOptions(builder.build())

        // EV
        if (!isManualExposure) {
             cameraControl.setExposureCompensationIndex(currentEvIndex)
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_TYPE = "image/jpeg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }

    class LutAdapter(
        private val lutManager: LutManager,
        private val onClick: (File?) -> Unit
    ) : RecyclerView.Adapter<LutAdapter.ViewHolder>() {

        private var items = listOf<File>()

        init {
            updateList()
        }

        fun updateList() {
            items = lutManager.getLutList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lut, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            if (position < items.size) {
                val file = items[position]
                holder.textView.text = lutManager.getDisplayName(file)
                holder.itemView.setOnClickListener { onClick(file) }
            } else {
                holder.textView.text = "+ Import"
                holder.itemView.setOnClickListener { onClick(null) }
            }
        }

        override fun getItemCount(): Int = items.size + 1

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.tv_lut_name)
        }
    }
}

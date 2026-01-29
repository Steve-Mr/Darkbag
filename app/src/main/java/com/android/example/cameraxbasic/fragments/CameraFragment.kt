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
import android.graphics.ImageFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.view.setPadding
import androidx.exifinterface.media.ExifInterface
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
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    private var currentCameraId: String? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowMetricsCalculator: WindowMetricsCalculator

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private val cameraManager by lazy {
        requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // Camera2 variables
    private var camera2Device: CameraDevice? = null
    private var camera2Session: CameraCaptureSession? = null
    private var camera2ImageReader: ImageReader? = null
    private var camera2Thread: HandlerThread? = null
    private var camera2Handler: Handler? = null

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

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
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onStart() {
        super.onStart()
        // Restart Camera2 if we are in Camera2 mode (SurfaceView is visible) and we have a valid ID
        if (currentCameraId != null && fragmentCameraBinding.root.findViewById<View>(R.id.view_finder_camera2).visibility == View.VISIBLE) {
            startCamera2(currentCameraId!!)
        }
    }

    override fun onStop() {
        super.onStop()
        stopCamera2()
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
        stopCamera2()
        super.onDestroyView()

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

        // Restore state
        savedInstanceState?.getString("currentCameraId")?.let {
            currentCameraId = it
        }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentCameraId?.let { outState.putString("currentCameraId", it) }
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

        // Populate lens buttons after camera is set up
        populateLensButtons()
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = windowMetricsCalculator.computeCurrentWindowMetrics(requireActivity()).bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = fragmentCameraBinding.viewFinder.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = if (currentCameraId != null) {
            CameraSelector.Builder().addCameraFilter { cameraInfos ->
                cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == currentCameraId }
            }.build()
        } else {
            CameraSelector.Builder().requireLensFacing(lensFacing).build()
        }

        // Safety Check: Ensure the selected camera is supported by CameraX
        if (currentCameraId != null) {
            try {
                if (!cameraProvider.hasCamera(cameraSelector)) {
                    // Fallback to Camera2
                    Toast.makeText(requireContext(), "Switching to Camera ID $currentCameraId (Camera2 mode)...", Toast.LENGTH_SHORT).show()

                    // Unbind CameraX
                    cameraProvider.unbindAll()

                    // Hide PreviewView, Show SurfaceView
                    fragmentCameraBinding.viewFinder.visibility = View.GONE
                    fragmentCameraBinding.root.findViewById<View>(R.id.view_finder_camera2).visibility = View.VISIBLE

                    startCamera2(currentCameraId!!)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking camera availability", e)
                return
            }
        }

        // Check for RAW support
        var isRawSupported = false
        try {
            // Check if we can get camera info for the selector to verify capabilities
            if (cameraProvider.hasCamera(cameraSelector)) {
               val cameraInfos = cameraSelector.filter(cameraProvider.availableCameraInfos)
               if (cameraInfos.isNotEmpty()) {
                   val cameraInfo = cameraInfos[0]
                   val capabilities = ImageCapture.getImageCaptureCapabilities(cameraInfo)
                   isRawSupported = capabilities.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)
               }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking RAW support", e)
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(screenAspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .build()

        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setResolutionSelector(resolutionSelector)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // We request aspect ratio but no resolution to match preview config, but letting
            // CameraX optimize for whatever specific resolution best fits our use cases
            .setResolutionSelector(resolutionSelector)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .apply {
                if (isRawSupported) {
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                }
            }
            .build()

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

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer)

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
            run {
                when (cameraState.type) {
                    CameraState.Type.PENDING_OPEN -> {
                        // Ask the user to close other camera apps
                        Toast.makeText(context,
                                "CameraState: Pending Open",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.OPENING -> {
                        // Show the Camera UI
                        Toast.makeText(context,
                                "CameraState: Opening",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.OPEN -> {
                        // Setup Camera resources and begin processing
                        Toast.makeText(context,
                                "CameraState: Open",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.CLOSING -> {
                        // Close camera UI
                        Toast.makeText(context,
                                "CameraState: Closing",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.Type.CLOSED -> {
                        // Free camera resources
                        Toast.makeText(context,
                                "CameraState: Closed",
                                Toast.LENGTH_SHORT).show()
                    }
                }
            }

            cameraState.error?.let { error ->
                when (error.code) {
                    // Open errors
                    CameraState.ERROR_STREAM_CONFIG -> {
                        // Make sure to setup the use cases properly
                        Toast.makeText(context,
                                "Stream config error",
                                Toast.LENGTH_SHORT).show()
                    }
                    // Opening errors
                    CameraState.ERROR_CAMERA_IN_USE -> {
                        // Close the camera or ask user to close another camera app that's using the
                        // camera
                        Toast.makeText(context,
                                "Camera in use",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                        // Close another open camera in the app, or ask the user to close another
                        // camera app that's using the camera
                        Toast.makeText(context,
                                "Max cameras in use",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                        Toast.makeText(context,
                                "Other recoverable error",
                                Toast.LENGTH_SHORT).show()
                    }
                    // Closing errors
                    CameraState.ERROR_CAMERA_DISABLED -> {
                        // Ask the user to enable the device's cameras
                        Toast.makeText(context,
                                "Camera disabled",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                        // Ask the user to reboot the device to restore camera function
                        Toast.makeText(context,
                                "Fatal error",
                                Toast.LENGTH_SHORT).show()
                    }
                    // Closed errors
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                        // Ask the user to disable the "Do Not Disturb" mode, then reopen the camera
                        Toast.makeText(context,
                                "Do not disturb mode enabled",
                                Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     *  [androidx.camera.core.ImageAnalysis.Builder] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun populateLensButtons() {
        val lensListContainer = cameraUiContainerBinding?.cameraLensList
        lensListContainer?.removeAllViews()

        Toast.makeText(requireContext(), "Scanning for cameras...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            // Scan for available cameras, including aggressive scan
            val availableCameras = mutableListOf<Pair<String, String>>() // ID, Name

            try {
                val systemIds = cameraManager.cameraIdList.toMutableList()
                // Aggressive scan
                for (i in 0..5) {
                    if (!systemIds.contains(i.toString())) {
                        try {
                            // Check if we can get characteristics (meaning it likely exists)
                            cameraManager.getCameraCharacteristics(i.toString())
                            systemIds.add(i.toString())
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Found hidden camera ID $i", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }

                // Find main back camera for zoom calc (widest lens that is BACK facing)
                var mainBackId: String? = null
                var mainBackFocalLength: Float = Float.MAX_VALUE

                for (id in systemIds) {
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            if (focalLengths != null && focalLengths.isNotEmpty()) {
                                val focalLength = focalLengths[0]
                                if (focalLength < mainBackFocalLength) {
                                    mainBackFocalLength = focalLength
                                    mainBackId = id
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking camera ID $id", e)
                    }
                }

                for (id in systemIds) {
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)

                        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            var zoomText = id
                            if (mainBackId != null && focalLengths != null && focalLengths.isNotEmpty()) {
                                val zoomRatio = focalLengths[0] / mainBackFocalLength
                                zoomText = String.format(Locale.US, "%.1fx", zoomRatio)
                            }
                            availableCameras.add(Pair(id, zoomText))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing camera ID $id", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enumerating cameras", e)
            }

            withContext(Dispatchers.Main) {
                if (availableCameras.size > 1) {
                    cameraUiContainerBinding?.cameraLensListScroll?.visibility = View.VISIBLE
                    for ((id, name) in availableCameras) {
                        val button = Button(requireContext()).apply {
                            text = name
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                marginEnd = 16
                            }
                            setOnClickListener {
                                // Switch to this specific camera ID
                                currentCameraId = id
                                lensFacing = CameraSelector.LENS_FACING_BACK // Keep this for context
                                Toast.makeText(context, "Switching to Camera ID $id", Toast.LENGTH_SHORT).show()

                                // Stop Camera2 if running
                                stopCamera2()
                                fragmentCameraBinding.viewFinder.visibility = View.VISIBLE
                                fragmentCameraBinding.root.findViewById<View>(R.id.view_finder_camera2).visibility = View.GONE

                                bindCameraUseCases()
                            }
                        }
                        lensListContainer?.addView(button)
                    }
                } else {
                    cameraUiContainerBinding?.cameraLensListScroll?.visibility = View.GONE
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

        populateLensButtons()

        // In the background, load latest photo taken (if any) for gallery thumbnail
        lifecycleScope.launch {
            val thumbnailUri = mediaStoreUtils.getLatestImageFilename()
            thumbnailUri?.let {
                setGalleryThumbnail(it)
            }
        }

        // Listener for button used to capture photo
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {

            if (camera2Device != null) {
                takePictureCamera2()
                return@setOnClickListener
            }

            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                // Create time stamped name and MediaStore entry.
                val name = SimpleDateFormat(FILENAME, Locale.US)
                    .format(System.currentTimeMillis())

                val appContext = requireContext().applicationContext
                val appNameStr = resources.getString(R.string.app_name)

                // Callback for image saved
                val imageSavedCallback = object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri
                        Log.d(TAG, "Photo capture succeeded: $savedUri")

                        // Add EXIF metadata
                        try {
                            if (savedUri != null) {
                                val pfd = appContext.contentResolver.openFileDescriptor(savedUri, "rw")
                                if (pfd != null) {
                                    val exif = ExifInterface(pfd.fileDescriptor)
                                    exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                                    exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                                    exif.setAttribute(ExifInterface.TAG_SOFTWARE, appNameStr)
                                    exif.saveAttributes()
                                    pfd.close()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing EXIF", e)
                        }

                        // We can only change the foreground Drawable using API level 23+ API
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            // Update the gallery thumbnail with latest picture taken
                            savedUri?.let { setGalleryThumbnail(it.toString()) }
                        }

                        // Implicit broadcasts will be ignored for devices running API level >= 24
                        // so if you only target API level 24+ you can remove this statement
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            // Suppress deprecated Camera usage needed for API level 23 and below
                            @Suppress("DEPRECATION")
                            appContext.sendBroadcast(
                                Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                            )
                        }
                    }
                }

                if (imageCapture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW) {
                    // RAW Only Capture (DNG)
                    val dngContentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                            val appName = requireContext().resources.getString(R.string.app_name)
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${appName}")
                        }
                    }
                    val dngOutputOptions = ImageCapture.OutputFileOptions
                        .Builder(requireContext().contentResolver,
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            dngContentValues)
                        .build()

                    imageCapture.takePicture(
                        dngOutputOptions,
                        cameraExecutor,
                        imageSavedCallback
                    )
                } else {
                    // Fallback or Standard JPEG Capture
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_TYPE)
                        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                            val appName = requireContext().resources.getString(R.string.app_name)
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${appName}")
                        }
                    }

                    val outputOptions = ImageCapture.OutputFileOptions
                        .Builder(requireContext().contentResolver,
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            contentValues)
                        .build()

                    imageCapture.takePicture(
                        outputOptions, cameraExecutor, imageSavedCallback
                    )
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
                    currentCameraId = null // Reset specific ID when switching groups
                    CameraSelector.LENS_FACING_BACK
                } else {
                    currentCameraId = null
                    CameraSelector.LENS_FACING_FRONT
                }
                // Stop Camera2 if running
                stopCamera2()
                fragmentCameraBinding.viewFinder.visibility = View.VISIBLE
                fragmentCameraBinding.root.findViewById<View>(R.id.view_finder_camera2).visibility = View.GONE

                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }

        // Listener for button used to view the most recent photo
        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            // Only navigate when the gallery has photos
            lifecycleScope.launch {
                if (mediaStoreUtils.getImages().isNotEmpty()) {
                    Navigation.findNavController(requireActivity(), R.id.fragment_container)
                        .navigate(CameraFragmentDirections.actionCameraToGallery(
                            mediaStoreUtils.mediaStoreCollection.toString()
                        )
                    )
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

    @SuppressLint("MissingPermission")
    private fun startCamera2(cameraId: String) {
        stopCamera2()

        camera2Thread = HandlerThread("Camera2Thread").apply { start() }
        camera2Handler = Handler(camera2Thread!!.looper)

        val surfaceView = fragmentCameraBinding.root.findViewById<android.view.SurfaceView>(R.id.view_finder_camera2)

        // Wait for surface
        if (surfaceView.holder.surface.isValid) {
            openCamera2Device(cameraId)
        } else {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    openCamera2Device(cameraId)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera2Device(cameraId: String) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Setup ImageReader for RAW
            val rawSizes = streamConfigMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
            if (rawSizes != null && rawSizes.isNotEmpty()) {
                val largestRaw = Collections.max(Arrays.asList(*rawSizes)) { o1, o2 ->
                    (o1.width.toLong() * o1.height).compareTo(o2.width.toLong() * o2.height)
                }
                camera2ImageReader = ImageReader.newInstance(largestRaw.width, largestRaw.height, ImageFormat.RAW_SENSOR, 2)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    camera2Device = camera
                    createCamera2Session()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    camera2Device = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    camera2Device = null
                    Toast.makeText(context, "Camera2 Open Error: $error", Toast.LENGTH_SHORT).show()
                }
            }, camera2Handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Camera2", e)
        }
    }

    private fun createCamera2Session() {
        try {
            val surfaceView = fragmentCameraBinding.root.findViewById<android.view.SurfaceView>(R.id.view_finder_camera2)
            val previewSurface = surfaceView.holder.surface
            val targets = mutableListOf(previewSurface)

            camera2ImageReader?.surface?.let { targets.add(it) }

            camera2Device?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (camera2Device == null) return
                    camera2Session = session

                    try {
                        val builder = camera2Device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(previewSurface)
                        session.setRepeatingRequest(builder.build(), null, camera2Handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera2 preview failed", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                     Toast.makeText(context, "Camera2 Session Failed", Toast.LENGTH_SHORT).show()
                }
            }, camera2Handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Camera2 session", e)
        }
    }

    private fun stopCamera2() {
        try {
            camera2Session?.close()
            camera2Session = null
            camera2Device?.close()
            camera2Device = null
            camera2ImageReader?.close()
            camera2ImageReader = null
            camera2Thread?.quitSafely()
            // Removed join to prevent UI blocking
            camera2Thread = null
            camera2Handler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Camera2", e)
        }
    }

    private fun takePictureCamera2() {
        if (camera2Device == null || camera2Session == null || camera2ImageReader == null) return

        try {
            val characteristics = cameraManager.getCameraCharacteristics(camera2Device!!.id)
            val builder = camera2Device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(camera2ImageReader!!.surface)
            // Orientation
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val rotation = requireActivity().windowManager.defaultDisplay.rotation
            builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation) // Rough handling

            // Variables to hold Image and Result
            var capturedImage: android.media.Image? = null
            var capturedResult: TotalCaptureResult? = null

            // Function to process DNG when both are ready
            fun processDngIfReady() {
                if (capturedImage != null && capturedResult != null) {
                    val dngCreator = android.hardware.camera2.DngCreator(characteristics, capturedResult!!)
                    val image = capturedImage!!

                    try {
                        val name = SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())
                        val dngContentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                                val appName = requireContext().resources.getString(R.string.app_name)
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${appName}")
                            }
                        }
                        val dngUri = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, dngContentValues)

                        if (dngUri != null) {
                            val outputStream = requireContext().contentResolver.openOutputStream(dngUri)
                            if (outputStream != null) {
                                dngCreator.writeImage(outputStream, image)
                                outputStream.close()

                                // Add Extra EXIF
                                 val pfd = requireContext().contentResolver.openFileDescriptor(dngUri, "rw")
                                 if (pfd != null) {
                                     val exif = ExifInterface(pfd.fileDescriptor)
                                     exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                                     exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                                     exif.setAttribute(ExifInterface.TAG_SOFTWARE, requireContext().resources.getString(R.string.app_name))
                                     exif.saveAttributes()
                                     pfd.close()
                                 }

                                 // Display flash logic similar to CameraX
                                 fragmentCameraBinding.root.post {
                                     fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                                     fragmentCameraBinding.root.postDelayed(
                                             { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS)
                                 }
                                 // Toast on Main Thread
                                 fragmentCameraBinding.root.post {
                                     Toast.makeText(context, "DNG Saved (Camera2)", Toast.LENGTH_SHORT).show()
                                 }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving Camera2 DNG", e)
                    } finally {
                        image.close()
                        dngCreator.close()
                    }
                }
            }

            camera2ImageReader?.setOnImageAvailableListener({ reader ->
                capturedImage = reader.acquireNextImage()
                processDngIfReady()
            }, camera2Handler)

            camera2Session?.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                 override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                     capturedResult = result
                     processDngIfReady()
                 }
            }, camera2Handler)

        } catch (e: Exception) {
            Log.e(TAG, "Camera2 Capture Failed", e)
        }
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

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_TYPE = "image/jpeg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }
}

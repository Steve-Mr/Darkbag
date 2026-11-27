package top.maary.darkbag.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope as ownerLifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.maary.darkbag.MainActivity
import top.maary.darkbag.MainApplication
import top.maary.darkbag.R
import top.maary.darkbag.camera.CameraControllerHub
import top.maary.darkbag.camera.delegate.CameraEnvironmentHost
import top.maary.darkbag.camera.session.CameraSessionArbiter
import top.maary.darkbag.camera.state.CameraRuntimeStateHolder
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.HdrPlusRequestManager
import top.maary.darkbag.processor.StreamingBufferPool
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewmodel.CameraEffect
import top.maary.darkbag.viewmodel.CameraUiTier
import top.maary.darkbag.viewmodel.CameraViewModel

/**
 * Passive View-layer Fragment hosting the camera viewfinder, ViewBinding,
 * WindowInsets, navigation actions, and MVI StateFlow observation.
 * Hardware sessions, mode strategies, and UI controls live in [CameraSessionArbiter]
 * and [CameraControllerHub].
 */
class CameraFragment : Fragment(), CameraEnvironmentHost {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!
    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private var displayId: Int = -1
    private lateinit var windowMetricsCalculator: WindowMetricsCalculator
    private val settingsRepository by lazy { SettingsRepository.getInstance(requireContext()) }
    private val displayManager by lazy { requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    override val hostContext: Context get() = requireContext()
    override val hostActivity: FragmentActivity? get() = activity
    override val isHostAdded: Boolean get() = isAdded
    override val lifecycleScope: CoroutineScope get() = ownerLifecycleScope
    override val cameraViewModel: CameraViewModel by viewModels()
    override val camera2Manager: CameraManager by lazy {
        requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    override val cameraRepository by lazy { CameraRepository(requireContext()) }
    override val imageRepository by lazy { ImageRepository(requireContext()) }
    override val lutManager by lazy { LutManager(requireContext()) }
    override val locationHelper by lazy { LocationHelper(requireContext()) }
    override val displayRotation: Int get() = view?.display?.rotation ?: Surface.ROTATION_0
    override val shouldMirror: Boolean
        get() = runtimeState.lensFacing == CameraCharacteristics.LENS_FACING_FRONT &&
            settingsRepository.current().mirrorFrontCamera

    override val runtimeState: CameraRuntimeStateHolder by lazy {
        CameraRuntimeStateHolder().apply {
            context?.applicationContext?.let { appContext ->
                lensFacing = SettingsRepository.getInstance(appContext).current().lensFacing
                    ?: CameraCharacteristics.LENS_FACING_BACK
            }
        }
    }

    override val sessionArbiter: CameraSessionArbiter by lazy {
        CameraSessionArbiter(camera2Manager, runtimeState)
    }

    private val hub: CameraControllerHub by lazy {
        CameraControllerHub(
            env = this,
            fragmentBindingProvider = { _fragmentCameraBinding },
            uiBindingProvider = { cameraUiContainerBinding },
            isPortraitBoundsProvider = {
                val bounds = windowMetricsCalculator.computeCurrentWindowMetrics(requireActivity()).bounds
                bounds.width() < bounds.height()
            }
        )
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "RECORD_AUDIO permission granted via prompt")
        } else {
            Log.d(TAG, "RECORD_AUDIO permission denied; user can continue silently on next hold")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val display = view?.display ?: return
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${display.rotation}")
            }
        }
    }

    override fun shouldShowAudioPermissionRationale(): Boolean =
        shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

    override fun requestAudioPermission() {
        requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        top.maary.darkbag.utils.PredictiveBackTransitions.apply(this)
    }

    override fun onStart() {
        super.onStart()
        hub.onStart()
    }

    override fun onStop() {
        super.onStop()
        hub.onStop()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setFloatingToolbarForcedHidden(true)
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCameraToPermissions()
            )
            return
        }

        val vfContainer = _fragmentCameraBinding?.viewFinderContainer
        hub.onResume(
            isViewFinderLaidOut = vfContainer?.isLaidOut == true,
            onRebuildUi = { refreshCameraUiIfInflated() },
            onPostBind = { action ->
                vfContainer?.post {
                    if (isAdded && _fragmentCameraBinding != null) action()
                }
            }
        )
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.setFloatingToolbarForcedHidden(false)
        _fragmentCameraBinding?.viewFinderBlackout?.animate()?.cancel()
        _fragmentCameraBinding = null
        cameraUiContainerBinding = null
        super.onDestroyView()
        hub.onDestroyView()
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        StreamingBufferPool.clear()
        ColorProcessor.clearSabreTexturePoolAsync()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ColorProcessor.warmupGpuAsync(requireContext())

        fun floatingOverlayTargets(): List<top.maary.darkbag.utils.PredictiveBackTransitions.ScrubTarget> {
            val binding = cameraUiContainerBinding ?: return emptyList()
            return listOf(
                top.maary.darkbag.utils.PredictiveBackTransitions.ScrubTarget(
                    view = binding.manualControlsRoot,
                    maxTranslationYDp = 28f,
                    minScale = 0.97f
                ),
                top.maary.darkbag.utils.PredictiveBackTransitions.ScrubTarget(
                    view = binding.lutListContainer,
                    maxTranslationYDp = 24f,
                    minScale = 0.96f
                )
            )
        }

        val floatingOverlayBackCallback = object : OnBackPressedCallback(hub.isFloatingOverlayOpen) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                cameraUiContainerBinding?.touchOverlay?.alpha = 1f
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                top.maary.darkbag.utils.PredictiveBackTransitions.applyScrubProgress(
                    targets = floatingOverlayTargets(),
                    backEvent = backEvent,
                    backdropView = cameraUiContainerBinding?.touchOverlay
                )
            }

            override fun handleOnBackCancelled() {
                top.maary.darkbag.utils.PredictiveBackTransitions.cancelScrub(
                    targets = floatingOverlayTargets(),
                    backdropView = cameraUiContainerBinding?.touchOverlay
                )
            }

            override fun handleOnBackPressed() {
                top.maary.darkbag.utils.PredictiveBackTransitions.resetScrubImmediate(
                    targets = floatingOverlayTargets(),
                    backdropView = cameraUiContainerBinding?.touchOverlay
                )
                hub.dismissFloatingOverlays()
            }
        }
        hub.onFloatingOverlayStateChanged = { isOpen ->
            floatingOverlayBackCallback.isEnabled = isOpen
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, floatingOverlayBackCallback)

        val mainApp = requireContext().applicationContext as MainApplication
        viewLifecycleOwner.ownerLifecycleScope.launch {
            mainApp.keyEventFlow.collect { event ->
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
                }
            }
        }

        displayManager.registerDisplayListener(displayListener, null)
        windowMetricsCalculator = WindowMetricsCalculator.getOrCreate()
        displayId = fragmentCameraBinding.viewFinder.display?.displayId ?: 0

        hub.onViewCreated(mainApp.applicationScope)
        cameraViewModel.setUiTier(CameraUiTier.fromKey(settingsRepository.current().uiTier))
        updateCameraUi()

        _fragmentCameraBinding?.viewFinderContainer?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            hub.onViewFinderLayoutChanged()
        }

        viewLifecycleOwner.ownerLifecycleScope.launch {
            ColorProcessor.backgroundSaveFlow.collect { event ->
                hub.onBackgroundSaveCompleted(event.targetUri, event.captureTimestamp)
            }
        }

        if (!settingsRepository.isHydrated) {
            viewLifecycleOwner.ownerLifecycleScope.launch {
                settingsRepository.awaitHydrated()
                if (!isAdded || _fragmentCameraBinding == null) return@launch
                hub.onSettingsHydrated(settingsRepository) { updateCameraUi() }
            }
        }

        viewLifecycleOwner.ownerLifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    HdrPlusRequestManager.pendingForegroundTasksCount.collect { count ->
                        hub.onForegroundTaskCountChanged(count)
                    }
                }
                launch {
                    cameraViewModel.uiState.collect { uiState ->
                        cameraUiContainerBinding?.cameraCaptureButton?.setDotRotation(uiState.shutterDotRotation)
                    }
                }
                launch {
                    cameraViewModel.uiState
                        .map { Pair(it.activeLogName, it.activeLutName) }
                        .distinctUntilChanged()
                        .collect { (log, lut) ->
                            val cleanLut = lut.takeIf { !it.equals("None", ignoreCase = true) }
                            cameraUiContainerBinding?.let {
                                CameraBentoDeckView.bindRecipeRow(it, log, cleanLut)
                            }
                            hub.lutController.updateLiveLut(log, cleanLut)
                        }
                }
                launch {
                    cameraViewModel.effects.collect { effect ->
                        when (effect) {
                            is CameraEffect.ShutterBlackout -> hub.overlayController.showShutterBlackout()
                            is CameraEffect.ResetShutterUi -> hub.shutterController.resetBurstUi()
                            is CameraEffect.SetShutterProgress -> cameraUiContainerBinding?.cameraCaptureButton?.setProgress(effect.progress)
                            is CameraEffect.AnimateHalfFrame -> hub.overlayController.updateHalfFrameUI(animate = true)
                            is CameraEffect.ClearThumbnailPlaceholder -> {
                                cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE
                                hub.thumbnailController.setGalleryThumbnail(null)
                            }
                            is CameraEffect.PerformHapticFeedback -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            is CameraEffect.PlayShutterClick -> Unit
                        }
                    }
                }
                launch {
                    ColorProcessor.halfFrameFlow.collect {
                        hub.onHalfFrameStoreChanged()
                    }
                }
                launch {
                    settingsRepository.preferences
                        .map { prefs ->
                            listOf(
                                prefs.motionPhotoEnabled,
                                prefs.motionPhotoAudioEnabled,
                                prefs.expRawMotionPhoto,
                                prefs.expRawMotionPhotoQuality
                            )
                        }
                        .distinctUntilChanged()
                        .drop(1)
                        .collect {
                            hub.updateMotionPhotoEncoder()
                        }
                }
            }
        }

        fragmentCameraBinding.viewFinder.post {
            if (!isAdded || _fragmentCameraBinding == null) return@post
            displayId = _fragmentCameraBinding?.viewFinder?.display?.displayId ?: -1
            hub.setupViewFinderSurface()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCameraUi()
        hub.onConfigurationChanged()
    }

    private fun refreshCameraUiIfInflated() {
        val uiBinding = cameraUiContainerBinding
        if (uiBinding == null) {
            updateCameraUi()
            return
        }
        CameraBentoDeckView.applyDynamicRowHeights(uiBinding)
        hub.overlayController.updateViewFinderConstraints()
        hub.shutterController.updateShutterOrientation()
        hub.bindCameraDeckUi()
        hub.thumbnailController.loadLatestThumbnail()
    }

    private fun updateCameraUi() {
        val root = _fragmentCameraBinding?.root ?: return

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

        cameraUiContainerBinding?.let { CameraBentoDeckView.applyDynamicRowHeights(it) }
        hub.overlayController.updateViewFinderConstraints()
        hub.shutterController.updateShutterOrientation()

        cameraUiContainerBinding?.root?.let { rootView ->
            var currentBottomInset = 0
            var currentToolbarHeight = 0

            fun updateContainerPadding() {
                rootView.updatePadding(bottom = kotlin.math.max(currentBottomInset, currentToolbarHeight))
            }

            (activity as? MainActivity)?.let { mainActivity ->
                viewLifecycleOwner.ownerLifecycleScope.launch {
                    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        mainActivity.toolbarHeightFlow.collect { height ->
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
                view.updatePadding(left = insets.left, top = insets.top, right = insets.right)
                currentBottomInset = insets.bottom
                updateContainerPadding()
                hub.overlayController.updateViewFinderConstraints()
                WindowInsetsCompat.CONSUMED
            }
            rootView.requestApplyInsets()
        }

        cameraUiContainerBinding?.settingsButton?.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToSettings())
        }

        cameraUiContainerBinding?.btnBentoPlayground?.setOnClickListener {
            val safeActivity = activity ?: return@setOnClickListener
            val navController = Navigation.findNavController(safeActivity, R.id.fragment_container)
            if (navController.currentDestination?.id == R.id.camera_fragment) {
                navController.navigate(R.id.action_camera_to_playground_gallery)
            }
        }

        cameraUiContainerBinding?.touchOverlay?.setOnClickListener {
            hub.dismissFloatingOverlays()
        }

        hub.bindCameraDeckUi()
        hub.thumbnailController.loadLatestThumbnail()
    }

    companion object {
        private const val TAG = "CameraFragment"
    }
}

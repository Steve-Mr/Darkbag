package top.maary.darkbag.camera.delegate

import android.animation.ValueAnimator
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.maary.darkbag.camera.session.PreviewRequestPlan
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LensInfo
import kotlin.math.roundToInt

/**
 * Controller managing lens discovery, Bento lens button row generation,
 * smooth switching transitions, focal length preset toggles, and digital zoom.
 */
class CameraLensController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost
) {
    companion object {
        private const val TAG = "CameraLensController"
    }

    private val settingsRepository by lazy { SettingsRepository.getInstance(env.hostContext) }
    private var lensSwitchWatchdog: Job? = null

    fun updateLensControlRowPosition(animate: Boolean = true) {
        val uiBinding = ui.cameraUiContainerBinding ?: return
        val lensRow = uiBinding.lensControlRow ?: return
        lensRow.translationY = 0f
    }

    fun initLensControls() {
        val binding = ui.cameraUiContainerBinding ?: return
        val container = binding.lensControlsContainer ?: return
        val state = env.runtimeState

        // Always clear container to avoid stale buttons from previous facings
        container.removeAllViews()

        if (state.availableLenses.isEmpty()) {
            refreshLenses()
        }

        val repoFacing = if (state.lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_BACK
        else
            CameraCharacteristics.LENS_FACING_FRONT

        val filteredLenses = (coordinator.activeCoordinator?.filterAvailableLenses(state.availableLenses, repoFacing)
            ?: state.availableLenses.filter { it.facing == repoFacing }).distinctBy { it.name }

        // Populate lens controls if any are available
        if (filteredLenses.isNotEmpty()) {
            val isBackCamera = state.lensFacing == CameraCharacteristics.LENS_FACING_BACK
            val largestTele = if (isBackCamera) {
                filteredLenses.filter { it.multiplier > 1.05f && !it.isZoomPreset }.maxByOrNull { it.multiplier }
            } else null

            val density = env.hostContext.resources.displayMetrics.density
            val marginPx = (3f * density).roundToInt()

            for (lens in filteredLenses) {
                val btn = MaterialButton(env.hostContext).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1.0f
                    ).apply {
                        marginStart = marginPx
                        marginEnd = marginPx
                    }
                    text = lens.name
                    tag = lens
                    textSize = 11f
                    isSingleLine = true
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    includeFontPadding = false
                    setPadding(0, 0, 0, 0)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    iconPadding = 0

                    setOnClickListener {
                        if (state.isBurstActive || state.isSwitchingLens) return@setOnClickListener
                        if (coordinator.activeCoordinator?.onLensSelected(lens) == true) {
                            state.currentLens = lens
                            updateLensUI()
                            return@setOnClickListener
                        }

                        val oldLens = state.currentLens
                        val is1x = lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset
                        val presets1xForCheck = if (is1x) env.cameraRepository.get1xPresets(lens) else emptyList()
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
                            state.currentLens = presets1x[nextIndex]

                            state.transientLensLabel = state.currentLens!!.name
                            env.lifecycleScope.launch(Dispatchers.Main) {
                                delay(800)
                                if (state.transientLensLabel == presets1x[nextIndex].name) {
                                    state.transientLensLabel = null
                                    updateLensUI()
                                }
                            }
                        } else if (isLargestTele && isAlreadyInTelePresets) {
                            val telePresets = env.cameraRepository.getTelePresets(lens)
                            val isCurrentlyNative = oldLens?.sensorId == lens.sensorId
                            state.currentLens = if (isCurrentlyNative) telePresets[1] else telePresets[0]
                        } else if (is1x) {
                            val default1xFocal = settingsRepository.current().defaultFocal1xName
                            val presets1x = env.cameraRepository.get1xPresets(lens)
                            state.currentLens = presets1x.find { it.name == default1xFocal } ?: presets1x.firstOrNull() ?: lens
                        } else {
                            state.currentLens = lens
                        }

                        updateLensUI()

                        if (oldLens?.id != state.currentLens?.id || oldLens?.physicalId != state.currentLens?.physicalId) {
                            animateSwitch {
                                coordinator.bindCameraUseCases()
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
        ui.applyUIVisibility()
    }

    fun updateLensUI() {
        val currentPrefs = settingsRepository.current()
        val spec = CameraBentoDeckView.resolveVisibilitySpec(currentPrefs, env.cameraViewModel.uiState.value.uiTier)
        val showLensControls = spec.showLensControls
        val state = env.runtimeState
        state.currentLens?.let {
            settingsRepository.setSelectedLensId(it.sensorId)
        }

        val binding = ui.cameraUiContainerBinding ?: return
        val container = binding.lensControlsContainer ?: return

        val isBackCamera = state.lensFacing == CameraCharacteristics.LENS_FACING_BACK
        val largestTele = if (isBackCamera) {
            state.availableLenses.filter { it.multiplier > 1.05f && !it.isZoomPreset }.maxByOrNull { it.multiplier }
        } else null

        for (i in 0 until container.childCount) {
            val btn = container.getChildAt(i) as? MaterialButton
            val lens = btn?.tag as? LensInfo
            if (btn != null && lens != null) {
                val activeLens = state.currentLens
                val isActive = lens.sensorId == activeLens?.sensorId ||
                        (lens.multiplier in 0.95f..1.05f && activeLens?.id == lens.id && !activeLens.sensorId.contains("virtual")) ||
                        (largestTele != null && lens.sensorId == largestTele.sensorId && activeLens?.sensorId == "${largestTele.sensorId}${CameraRepository.VIRTUAL_TELE_2X_SUFFIX}")

                if (isActive) {
                    if (lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset) {
                        btn.text = state.transientLensLabel ?: String.format("%.1fx", activeLens?.multiplier ?: 1.0f)
                    } else if (largestTele != null && lens.sensorId == largestTele.sensorId) {
                        btn.text = String.format("%.1fx", activeLens?.multiplier ?: lens.multiplier)
                    } else {
                        btn.text = lens.name
                    }

                    val isDigitalCrop = (activeLens?.isZoomPreset == true && (activeLens.targetZoomRatio ?: 1.0f) > 1.0f) || lens.isZoomPreset
                    CameraBentoDeckView.styleBentoLensButton(btn, isActive = true, isDigitalCrop = isDigitalCrop)
                } else {
                    if (lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset) {
                        val default1xFocal = currentPrefs.defaultFocal1xName
                        val presets1x = env.cameraRepository.get1xPresets(lens)
                        val defaultPreset = presets1x.find { it.name == default1xFocal } ?: presets1x.firstOrNull() ?: lens
                        btn.text = String.format("%.1fx", defaultPreset.multiplier)
                    } else {
                        btn.text = lens.name
                    }

                    CameraBentoDeckView.styleBentoLensButton(btn, isActive = false, isDigitalCrop = lens.isZoomPreset)
                }
            }
        }

        val hasMultipleLenses = container.childCount > 1
        val showLensStrip = hasMultipleLenses && showLensControls
        binding.lensControlsCard?.visibility = if (showLensStrip) View.VISIBLE else View.GONE
        binding.lensControlRow?.visibility = if (spec.showModeSwitch || spec.showCameraSwitch || showLensStrip) View.VISIBLE else View.GONE
    }

    /** Enables/disables the camera-switch button depending on available facings and tier visibility. */
    fun updateCameraSwitchButton() {
        val spec = CameraBentoDeckView.resolveVisibilitySpec(settingsRepository.current(), env.cameraViewModel.uiState.value.uiTier)
        val button = ui.cameraUiContainerBinding?.cameraSwitchButtonAlt ?: return
        try {
            button.isEnabled = hasCameraFacing(CameraCharacteristics.LENS_FACING_BACK) &&
                hasCameraFacing(CameraCharacteristics.LENS_FACING_FRONT)
            button.visibility = if (spec.showCameraSwitch) View.VISIBLE else View.GONE
        } catch (exception: Exception) {
            button.isEnabled = false
        }
    }

    private fun hasCameraFacing(facing: Int): Boolean = try {
        env.camera2Manager.cameraIdList.any { id ->
            CameraRepository.getCharacteristics(env.camera2Manager, id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    } catch (e: Exception) {
        false
    }

    fun updateZoom(animate: Boolean) {
        updateZoomCamera2()
        updateLensUI()
    }

    fun updateZoomCamera2() {
        val arbiter = env.sessionArbiter
        val state = env.runtimeState
        val session = arbiter.camera2Session ?: return
        val device = arbiter.camera2Device ?: return
        val chars = CameraRepository.getCharacteristics(env.camera2Manager, device.id)
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val cropRegion: Rect = arbiter.computeCropRegion(activeArray, state.currentLens)

        val handler = arbiter.camera2Handler ?: return
        handler.post {
            val activeSession = arbiter.camera2Session ?: return@post
            val activeDevice = arbiter.camera2Device ?: return@post
            val surface = arbiter.camera2PreviewSurface ?: return@post
            try {
                val plan = PreviewRequestPlan.resolve(surface, null, attachAnalysis = false)
                val request = arbiter.previewRequestFactory.build(activeDevice, plan) {
                    coordinator.applyManualSettingsToRequest(it)
                    it.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                }
                activeSession.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        coordinator.handleCaptureResult(result)
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Camera2 zoom", e)
            }
        }
    }

    fun animateSwitch(onMidPoint: () -> Unit) {
        val state = env.runtimeState
        if (state.isSwitchingLens) return
        state.isSwitchingLens = true

        lensSwitchWatchdog?.cancel()
        lensSwitchWatchdog = env.lifecycleScope.launch(Dispatchers.Main) {
            delay(3000L)
            if (state.isSwitchingLens) {
                Log.w(TAG, "Lens switch watchdog triggered! Resetting isSwitchingLens.")
                state.isSwitchingLens = false
                ui.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
            }
        }

        val vf = ui.fragmentCameraBinding?.viewFinder
        if (vf == null) {
            onMidPoint()
            state.isSwitchingLens = false
            lensSwitchWatchdog?.cancel()
            lensSwitchWatchdog = null
            return
        }

        vf.animate().cancel()
        val fadeOut = ValueAnimator.ofFloat(1.0f, 0.3f).apply {
            duration = 100L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                vf.alpha = animator.animatedValue as Float
            }
        }

        val fadeIn = ValueAnimator.ofFloat(0.3f, 1.0f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                vf.alpha = animator.animatedValue as Float
            }
        }

        fadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onMidPoint()
                fadeIn.start()
            }
        })

        fadeIn.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                state.isSwitchingLens = false
                lensSwitchWatchdog?.cancel()
                lensSwitchWatchdog = null
            }
        })

        fadeOut.start()
    }

    fun refreshLenses(force: Boolean = false) {
        val state = env.runtimeState
        val repoFacing = if (state.lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_BACK
        else
            CameraCharacteristics.LENS_FACING_FRONT

        // Use unified focal length presets (includes digital ones like 28mm, 35mm, 2.0x)
        val newLenses = env.cameraRepository.getFocalLengthPresets(repoFacing)

        if (!force && newLenses.size == state.availableLenses.size && newLenses.zip(state.availableLenses).all { it.first.sensorId == it.second.sensorId && it.first.facing == it.second.facing }) {
            return // No change
        }

        Log.d(TAG, "Available Lenses/Presets identified: ${newLenses.size} for facing $repoFacing")

        // Update currentLens reference if it exists
        var updatedLens: LensInfo? = null
        state.currentLens?.let { old ->
            var found = newLenses.find { it.sensorId == old.sensorId }
            if (found == null) {
                val base1x = newLenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
                if (base1x != null) {
                    found = env.cameraRepository.get1xPresets(base1x).find { it.sensorId == old.sensorId }
                }
            }
            updatedLens = found
        }

        if (updatedLens == null) {
            val currentPrefs = settingsRepository.current()
            val savedLensId = currentPrefs.selectedLensId
            val defaultLensId = currentPrefs.defaultLensId
            val default1xFocal = currentPrefs.defaultFocal1xName

            if (savedLensId != null) {
                var found = newLenses.find { it.sensorId == savedLensId }
                if (found == null) {
                    val base1x = newLenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
                    if (base1x != null) {
                        found = env.cameraRepository.get1xPresets(base1x).find { it.sensorId == savedLensId }
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
                    val presets1x = env.cameraRepository.get1xPresets(found)
                    updatedLens = presets1x.find { it.name == default1xFocal } ?: presets1x.firstOrNull() ?: found
                } else {
                    updatedLens = found
                }
            }
        }

        state.currentLens = updatedLens
        state.availableLenses = newLenses
    }

    fun bindCameraSwitchButton() {
        val btn = ui.cameraUiContainerBinding?.cameraSwitchButtonAlt ?: return
        btn.isEnabled = false
        val state = env.runtimeState
        btn.setOnClickListener {
            if (state.isBurstActive) return@setOnClickListener
            if (coordinator.activeCoordinator?.onSwitchCameraClicked() == true) return@setOnClickListener

            state.lensFacing = if (CameraCharacteristics.LENS_FACING_FRONT == state.lensFacing) {
                CameraCharacteristics.LENS_FACING_BACK
            } else {
                CameraCharacteristics.LENS_FACING_FRONT
            }
            settingsRepository.setLensFacing(state.lensFacing)

            env.lifecycleScope.launch {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    refreshLenses(force = true)
                }
                initLensControls()
                coordinator.bindCameraUseCases()
            }
        }
    }

    fun onDestroy() {
        lensSwitchWatchdog?.cancel()
        lensSwitchWatchdog = null
    }
}

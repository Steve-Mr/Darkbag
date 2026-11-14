package top.maary.darkbag.camera.delegate

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.utils.CameraRepository
import top.maary.darkbag.utils.LensInfo
import top.maary.darkbag.viewmodel.CameraViewModel
import kotlin.math.roundToInt

/**
 * Host contract for lens and zoom controls.
 */
interface CameraLensHost {
    val hostContext: Context
    val lifecycleScope: CoroutineScope
    val cameraRepository: CameraRepository
    val cameraViewModel: CameraViewModel
    val camera2Manager: CameraManager
    val camera2Device: CameraDevice?
    val camera2Session: CameraCaptureSession?
    val camera2PreviewSurface: android.view.Surface?
    val camera2Handler: Handler?
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val fragmentCameraBinding: FragmentCameraBinding?
    val activeCoordinator: CaptureModeCoordinator?
    var lensFacing: Int
    var availableLenses: List<LensInfo>
    var currentLens: LensInfo?
    var isBurstActive: Boolean
    var isSwitchingLens: Boolean
    var transientLensLabel: String?

    fun applyUIVisibility()
    fun bindCameraUseCases()
    fun applyManualSettingsToRequest(request: CaptureRequest.Builder)
    fun handleCaptureResult(result: TotalCaptureResult)
}

/**
 * Controller managing lens discovery, Bento lens button row generation,
 * smooth switching transitions, focal length preset toggles, and digital zoom.
 */
class CameraLensController(
    private val host: CameraLensHost
) {
    companion object {
        private const val TAG = "CameraLensController"
        const val KEY_SELECTED_LENS_ID = "selected_lens_id"
    }

    private var lensSwitchWatchdog: Job? = null

    fun updateLensControlRowPosition(animate: Boolean = true) {
        val uiBinding = host.cameraUiContainerBinding ?: return
        val lensRow = uiBinding.lensControlRow ?: return
        lensRow.translationY = 0f
    }

    fun initLensControls() {
        val binding = host.cameraUiContainerBinding ?: return
        val container = binding.lensControlsContainer ?: return

        // Always clear container to avoid stale buttons from previous facings
        container.removeAllViews()

        if (host.availableLenses.isEmpty()) {
            refreshLenses()
        }

        val repoFacing = if (host.lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_BACK
        else
            CameraCharacteristics.LENS_FACING_FRONT

        val filteredLenses = (host.activeCoordinator?.filterAvailableLenses(host.availableLenses, repoFacing)
            ?: host.availableLenses.filter { it.facing == repoFacing }).distinctBy { it.name }

        // Populate lens controls if any are available
        if (filteredLenses.isNotEmpty()) {
            val isBackCamera = host.lensFacing == CameraCharacteristics.LENS_FACING_BACK
            val largestTele = if (isBackCamera) {
                filteredLenses.filter { it.multiplier > 1.05f && !it.isZoomPreset }.maxByOrNull { it.multiplier }
            } else null

            val density = host.hostContext.resources.displayMetrics.density
            val marginPx = (3f * density).roundToInt()

            for (lens in filteredLenses) {
                val btn = MaterialButton(host.hostContext).apply {
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
                        if (host.isBurstActive || host.isSwitchingLens) return@setOnClickListener
                        if (host.activeCoordinator?.onLensSelected(lens) == true) {
                            host.currentLens = lens
                            updateLensUI()
                            return@setOnClickListener
                        }

                        val oldLens = host.currentLens
                        val is1x = lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset
                        val presets1xForCheck = if (is1x) host.cameraRepository.get1xPresets(lens) else emptyList()
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
                            host.currentLens = presets1x[nextIndex]

                            host.transientLensLabel = host.currentLens!!.name
                            host.lifecycleScope.launch(Dispatchers.Main) {
                                delay(800)
                                if (host.transientLensLabel == presets1x[nextIndex].name) {
                                    host.transientLensLabel = null
                                    updateLensUI()
                                }
                            }
                        } else if (isLargestTele && isAlreadyInTelePresets) {
                            val telePresets = host.cameraRepository.getTelePresets(lens)
                            val isCurrentlyNative = oldLens?.sensorId == lens.sensorId
                            host.currentLens = if (isCurrentlyNative) telePresets[1] else telePresets[0]
                        } else if (is1x) {
                            val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                            val default1xFocal = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, null)
                            val presets1x = host.cameraRepository.get1xPresets(lens)
                            host.currentLens = presets1x.find { it.name == default1xFocal } ?: presets1x.firstOrNull() ?: lens
                        } else {
                            host.currentLens = lens
                        }

                        updateLensUI()

                        if (oldLens?.id != host.currentLens?.id || oldLens?.physicalId != host.currentLens?.physicalId) {
                            animateSwitch {
                                host.bindCameraUseCases()
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
        host.applyUIVisibility()
    }

    fun updateLensUI() {
        val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val spec = CameraBentoDeckView.resolveVisibilitySpec(prefs, host.cameraViewModel.uiState.value.uiTier)
        val showLensControls = spec.showLensControls
        host.currentLens?.let {
            prefs.edit().putString(KEY_SELECTED_LENS_ID, it.sensorId).apply()
        }

        val binding = host.cameraUiContainerBinding ?: return
        val container = binding.lensControlsContainer ?: return

        val isBackCamera = host.lensFacing == CameraCharacteristics.LENS_FACING_BACK
        val largestTele = if (isBackCamera) {
            host.availableLenses.filter { it.multiplier > 1.05f && !it.isZoomPreset }.maxByOrNull { it.multiplier }
        } else null

        for (i in 0 until container.childCount) {
            val btn = container.getChildAt(i) as? MaterialButton
            val lens = btn?.tag as? LensInfo
            if (btn != null && lens != null) {
                val isActive = lens.sensorId == host.currentLens?.sensorId ||
                        (lens.multiplier in 0.95f..1.05f && host.currentLens?.id == lens.id && !host.currentLens!!.sensorId.contains("virtual")) ||
                        (largestTele != null && lens.sensorId == largestTele.sensorId && host.currentLens?.sensorId == "${largestTele.sensorId}${CameraRepository.VIRTUAL_TELE_2X_SUFFIX}")

                if (isActive) {
                    if (lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset) {
                        btn.text = host.transientLensLabel ?: String.format("%.1fx", host.currentLens?.multiplier ?: 1.0f)
                    } else if (largestTele != null && lens.sensorId == largestTele.sensorId) {
                        btn.text = String.format("%.1fx", host.currentLens?.multiplier ?: lens.multiplier)
                    } else {
                        btn.text = lens.name
                    }

                    val isDigitalCrop = (host.currentLens?.isZoomPreset == true && (host.currentLens?.targetZoomRatio ?: 1.0f) > 1.0f) || lens.isZoomPreset
                    CameraBentoDeckView.styleBentoLensButton(btn, isActive = true, isDigitalCrop = isDigitalCrop)
                } else {
                    if (lens.multiplier in 0.95f..1.05f && !lens.isZoomPreset) {
                        val default1xFocal = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, null)
                        val presets1x = host.cameraRepository.get1xPresets(lens)
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
        val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val spec = CameraBentoDeckView.resolveVisibilitySpec(prefs, host.cameraViewModel.uiState.value.uiTier)
        val button = host.cameraUiContainerBinding?.cameraSwitchButtonAlt ?: return
        try {
            button.isEnabled = hasCameraFacing(CameraCharacteristics.LENS_FACING_BACK) &&
                hasCameraFacing(CameraCharacteristics.LENS_FACING_FRONT)
            button.visibility = if (spec.showCameraSwitch) View.VISIBLE else View.GONE
        } catch (exception: Exception) {
            button.isEnabled = false
        }
    }

    private fun hasCameraFacing(facing: Int): Boolean = try {
        host.camera2Manager.cameraIdList.any { id ->
            CameraRepository.getCharacteristics(host.camera2Manager, id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    } catch (e: Exception) { false }

    fun updateZoom(animate: Boolean) {
        updateZoomCamera2()
        updateLensUI()
    }

    fun updateZoomCamera2() {
        val session = host.camera2Session ?: return
        val device = host.camera2Device ?: return
        val chars = CameraRepository.getCharacteristics(host.camera2Manager, device.id)
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val targetRatio = if (host.currentLens?.isZoomPreset == true && host.currentLens?.targetZoomRatio != null) {
            host.currentLens!!.targetZoomRatio!!
        } else {
            1.0f
        }

        val cropW = (activeArray.width() / targetRatio).toInt()
        val cropH = (activeArray.height() / targetRatio).toInt()
        val centerX = activeArray.centerX()
        val centerY = activeArray.centerY()

        val cropRegion = Rect(
            centerX - cropW / 2,
            centerY - cropH / 2,
            centerX + cropW / 2,
            centerY + cropH / 2
        )

        val handler = host.camera2Handler ?: return
        handler.post {
            val activeSession = host.camera2Session ?: return@post
            val activeDevice = host.camera2Device ?: return@post
            val surface = host.camera2PreviewSurface ?: return@post
            try {
                val request = activeDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                request.addTarget(surface)
                host.applyManualSettingsToRequest(request)
                request.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)

                activeSession.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        host.handleCaptureResult(result)
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Camera2 zoom", e)
            }
        }
    }

    fun animateSwitch(onMidPoint: () -> Unit) {
        if (host.isSwitchingLens) return
        host.isSwitchingLens = true

        lensSwitchWatchdog?.cancel()
        lensSwitchWatchdog = host.lifecycleScope.launch(Dispatchers.Main) {
            delay(3000L)
            if (host.isSwitchingLens) {
                Log.w(TAG, "Lens switch watchdog triggered! Resetting isSwitchingLens.")
                host.isSwitchingLens = false
                host.fragmentCameraBinding?.viewFinder?.animate()?.alpha(1f)?.setDuration(200L)?.start()
            }
        }

        val vf = host.fragmentCameraBinding?.viewFinder
        if (vf == null) {
            onMidPoint()
            host.isSwitchingLens = false
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
                host.isSwitchingLens = false
                lensSwitchWatchdog?.cancel()
                lensSwitchWatchdog = null
            }
        })

        fadeOut.start()
    }

    fun refreshLenses(force: Boolean = false) {
        val repoFacing = if (host.lensFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_BACK
        else
            CameraCharacteristics.LENS_FACING_FRONT

        // Use unified focal length presets (includes digital ones like 28mm, 35mm, 2.0x)
        val newLenses = host.cameraRepository.getFocalLengthPresets(repoFacing)

        if (!force && newLenses.size == host.availableLenses.size && newLenses.zip(host.availableLenses).all { it.first.sensorId == it.second.sensorId && it.first.facing == it.second.facing }) {
            return // No change
        }

        Log.d(TAG, "Available Lenses/Presets identified: ${newLenses.size} for facing $repoFacing")

        // Update currentLens reference if it exists
        var updatedLens: LensInfo? = null
        host.currentLens?.let { old ->
            var found = newLenses.find { it.sensorId == old.sensorId }
            if (found == null) {
                val base1x = newLenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
                if (base1x != null) {
                    found = host.cameraRepository.get1xPresets(base1x).find { it.sensorId == old.sensorId }
                }
            }
            updatedLens = found
        }

        if (updatedLens == null) {
            val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val savedLensId = prefs.getString(KEY_SELECTED_LENS_ID, null)
            val defaultLensId = prefs.getString(SettingsFragment.KEY_DEFAULT_LENS_ID, null)
            val default1xFocal = prefs.getString(SettingsFragment.KEY_DEFAULT_FOCAL_1X, null)

            if (savedLensId != null) {
                var found = newLenses.find { it.sensorId == savedLensId }
                if (found == null) {
                    val base1x = newLenses.find { it.multiplier in 0.95f..1.05f && !it.isZoomPreset }
                    if (base1x != null) {
                        found = host.cameraRepository.get1xPresets(base1x).find { it.sensorId == savedLensId }
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
                    val presets1x = host.cameraRepository.get1xPresets(found)
                    updatedLens = presets1x.find { it.name == default1xFocal } ?: presets1x.firstOrNull() ?: found
                } else {
                    updatedLens = found
                }
            }
        }

        host.currentLens = updatedLens
        host.availableLenses = newLenses
    }

    fun bindCameraSwitchButton() {
        val btn = host.cameraUiContainerBinding?.cameraSwitchButtonAlt ?: return
        btn.isEnabled = false
        btn.setOnClickListener {
            if (host.isBurstActive) return@setOnClickListener
            if (host.activeCoordinator?.onSwitchCameraClicked() == true) return@setOnClickListener

            host.lensFacing = if (CameraCharacteristics.LENS_FACING_FRONT == host.lensFacing) {
                CameraCharacteristics.LENS_FACING_BACK
            } else {
                CameraCharacteristics.LENS_FACING_FRONT
            }
            val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(top.maary.darkbag.fragments.CameraFragment.KEY_LENS_FACING, host.lensFacing).apply()

            host.lifecycleScope.launch {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    refreshLenses(force = true)
                }
                initLensControls()
                host.bindCameraUseCases()
            }
        }
    }

    fun onDestroy() {
        lensSwitchWatchdog?.cancel()
        lensSwitchWatchdog = null
    }
}

package top.maary.darkbag.camera.delegate

import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.ProDialWheelView
import top.maary.darkbag.viewmodel.CameraUiTier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Manages Bento camera tier switching (P&S / FILM / PRO),
 * manual parameter pills, Level 3 dial wheel interactions, and PRO telemetry.
 */
class CameraTierController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost
) {
    private val settingsRepository by lazy { SettingsRepository.getInstance(env.hostContext) }

    val activeManualTab: String?
        get() = env.runtimeState.activeManualTab

    fun initManualControls() {
        val binding = ui.cameraUiContainerBinding ?: return
        val state = env.runtimeState
        binding.btnProMode?.visibility = View.GONE

        // Dismiss touch overlay
        binding.touchOverlay?.setOnClickListener {
            dismissFloatingOverlays()
        }

        state.isMultiCameraManualLinked = settingsRepository.current().multiCameraManualLinked

        // Level 2: Pill Click Listeners (Toggles Level 3 Dial Panel)
        binding.pillFocus?.setOnClickListener { toggleManualTab("Focus") }
        binding.pillIso?.setOnClickListener { toggleManualTab("ISO") }
        binding.pillShutter?.setOnClickListener { toggleManualTab("Shutter") }
        binding.pillEv?.setOnClickListener { toggleManualTab("EV") }

        // Multi-Camera Sync Link Button
        binding.btnProLink?.setOnClickListener { v ->
            state.isMultiCameraManualLinked = !state.isMultiCameraManualLinked
            settingsRepository.setMultiCameraManualLinked(state.isMultiCameraManualLinked)
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            val msg = if (state.isMultiCameraManualLinked) {
                env.hostContext.getString(R.string.multi_camera_link_synced)
            } else {
                env.hostContext.getString(R.string.multi_camera_link_primary_only)
            }
            Toast.makeText(env.hostContext, msg, Toast.LENGTH_SHORT).show()
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
            state.currentFocusDistance = state.minFocusDistance
            state.isManualFocus = true
            coordinator.applyCameraControls()
            updateDialPanel()
            updateProInfoBar()
        }

        binding.btnFocusFar?.setOnClickListener {
            state.currentFocusDistance = 0.0f
            state.isManualFocus = true
            coordinator.applyCameraControls()
            updateDialPanel()
            updateProInfoBar()
        }

        updateProInfoBar()
        updateTierUi()
    }

    fun updateTierUi(tier: CameraUiTier = env.cameraViewModel.uiState.value.uiTier) {
        val binding = ui.cameraUiContainerBinding ?: return
        val spec = CameraBentoDeckView.resolveVisibilitySpec(settingsRepository.current(), tier)
        CameraBentoDeckView.bindTierState(
            binding = binding,
            tier = tier,
            isAnyManual = env.runtimeState.hasAnyManualOverride,
            hasActiveManualTab = activeManualTab != null,
            visibilitySpec = spec
        )
        CameraBentoDeckView.applyDynamicRowHeights(binding)
        updateFocusPeakingState()
    }

    fun toggleManualTab(tab: String) {
        val binding = ui.cameraUiContainerBinding ?: return
        val root = ui.rootViewGroup
        if (root != null) {
            val transition = android.transition.AutoTransition().apply {
                duration = 200
                ordering = android.transition.TransitionSet.ORDERING_TOGETHER
            }
            android.transition.TransitionManager.beginDelayedTransition(root, transition)
        }
        if (activeManualTab == tab) {
            env.runtimeState.activeManualTab = null
            env.cameraViewModel.setActiveManualTab(null)
            binding.manualControlsRoot?.visibility = View.GONE
            binding.touchOverlay?.visibility = View.GONE
            updateBentoFolding(isDialOpen = false)
            updateFocusPeakingState()
            updateProInfoBar()
        } else {
            env.runtimeState.activeManualTab = tab
            env.cameraViewModel.setActiveManualTab(tab)
            binding.manualControlsRoot?.visibility = View.VISIBLE
            binding.dialPanel?.visibility = View.VISIBLE
            binding.touchOverlay?.visibility = View.VISIBLE
            updateBentoFolding(isDialOpen = true)
            updateDialPanel()
            updateFocusPeakingState()
            updateProInfoBar()
        }
    }

    fun dismissFloatingOverlays() {
        val binding = ui.cameraUiContainerBinding ?: return
        if (activeManualTab != null) {
            val root = ui.rootViewGroup
            if (root != null) {
                val transition = android.transition.AutoTransition().apply {
                    duration = 200
                    ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                }
                android.transition.TransitionManager.beginDelayedTransition(root, transition)
            }
            env.runtimeState.activeManualTab = null
            env.cameraViewModel.setActiveManualTab(null)
            binding.manualControlsRoot?.visibility = View.GONE
            binding.touchOverlay?.visibility = View.GONE
            updateBentoFolding(isDialOpen = false)
            updateFocusPeakingState()
            updateProInfoBar()
        }
        if (binding.lutListContainer?.visibility == View.VISIBLE) {
            binding.lutListContainer?.visibility = View.GONE
            binding.touchOverlay?.visibility = View.GONE
        }
    }

    fun updateFocusPeakingState() {
        val snapshot = settingsRepository.current()
        val currentTier = env.cameraViewModel.uiState.value.uiTier
        val spec = CameraBentoDeckView.resolveVisibilitySpec(snapshot, currentTier)
        val isBadgeVisible = spec.enableFocusPeaking
        val badge = ui.cameraUiContainerBinding?.btnFocusPeakingBadge

        badge?.visibility = if (isBadgeVisible) View.VISIBLE else View.GONE
        if (isBadgeVisible) {
            val isActive = snapshot.focusPeakingActive
            if (badge != null) {
                CameraBentoDeckView.styleFocusPeakingBadge(badge, isActive)
            }
            env.sessionArbiter.lutProcessor?.setFocusPeakingEnabled(isActive)
        } else {
            env.sessionArbiter.lutProcessor?.setFocusPeakingEnabled(false)
        }
    }

    fun handleDialSelection(item: ProDialWheelView.DialItem) {
        val binding = ui.cameraUiContainerBinding
        val state = env.runtimeState
        when (activeManualTab) {
            "Focus" -> {
                state.currentFocusDistance = item.rawValue.toFloat()
                state.isManualFocus = true
                binding?.tvDialTargetValue?.text = formatFocusDist(state.currentFocusDistance)
                updateFocusPeakingState()
            }
            "ISO" -> {
                state.currentIso = item.rawValue.toInt()
                state.isManualIso = true
                binding?.tvDialTargetValue?.text = "${state.currentIso}"
            }
            "Shutter" -> {
                state.currentExposureTime = item.rawValue.toLong()
                state.isManualShutter = true
                binding?.tvDialTargetValue?.text = formatShutterTime(state.currentExposureTime)
            }
            "EV" -> {
                state.currentEvIndex = item.rawValue.toInt()
                binding?.tvDialTargetValue?.text = formatEvVal(state.currentEvIndex)
            }
        }
        coordinator.applyCameraControls()
        updateProInfoBar()
    }

    fun resetCurrentManualParameter() {
        val state = env.runtimeState
        when (activeManualTab) {
            "Focus" -> {
                state.isManualFocus = false
                state.focusMeteringRegion = null
                updateFocusPeakingState()
            }
            "ISO" -> {
                state.isManualIso = false
                state.exposureMeteringRegion = null
            }
            "Shutter" -> {
                state.isManualShutter = false
                state.exposureMeteringRegion = null
            }
            "EV" -> {
                state.currentEvIndex = 0
                state.exposureMeteringRegion = null
            }
        }
        coordinator.applyCameraControls()
        updateDialPanel()
        updateProInfoBar()
    }

    fun updateDialPanel() {
        val binding = ui.cameraUiContainerBinding ?: return
        val wheel = binding.dialWheelView ?: return
        val state = env.runtimeState
        binding.focusExtras?.visibility = if (activeManualTab == "Focus") View.VISIBLE else View.GONE

        when (activeManualTab) {
            "Focus" -> {
                binding.tvDialTargetLabel?.text = "FOCUS"
                val items = buildFocusDialItems(state.minFocusDistance)
                val targetVal = if (state.isManualFocus) state.currentFocusDistance else 0.0f
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
                binding.tvDialTargetValue?.text = if (state.isManualFocus) formatFocusDist(state.currentFocusDistance) else "AUTO (${formatFocusDist(state.liveFocusDistance)})"
            }
            "ISO" -> {
                binding.tvDialTargetLabel?.text = "ISO"
                val items = buildIsoDialItems(state.isoRange)
                val targetVal = if (state.isManualIso) state.currentIso else state.liveIso
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
                binding.tvDialTargetValue?.text = if (state.isManualIso) "${state.currentIso}" else "AUTO (${state.liveIso})"
            }
            "Shutter" -> {
                binding.tvDialTargetLabel?.text = "SHUTTER"
                val items = buildShutterDialItems(state.exposureTimeRange)
                val targetVal = if (state.isManualShutter) state.currentExposureTime else state.liveExposureTime
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
                binding.tvDialTargetValue?.text = if (state.isManualShutter) formatShutterTime(state.currentExposureTime) else "AUTO (${formatShutterTime(state.liveExposureTime)})"
            }
            "EV" -> {
                binding.tvDialTargetLabel?.text = "EV"
                val items = buildEvDialItems(state.evRange)
                var closestIndex = 0
                var minDiff = Double.MAX_VALUE
                items.forEachIndexed { i, it ->
                    val diff = abs(it.rawValue - state.currentEvIndex)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIndex = i
                    }
                }
                wheel.setItems(items, closestIndex)
                binding.tvDialTargetValue?.text = formatEvVal(state.currentEvIndex)
            }
        }
    }

    private var isUpdatingProInfoBar = false

    fun updateProInfoBar() {
        if (isUpdatingProInfoBar) return
        isUpdatingProInfoBar = true
        try {
            val binding = ui.cameraUiContainerBinding ?: return
            val state = env.runtimeState
            val primaryColor = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, Color.parseColor("#FFD54F"))
            val onSurfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
            val onSurfaceVariantColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)
            val density = env.hostContext.resources.displayMetrics.density

            // Focus
            val isFocusLocked = !state.isManualFocus && state.hasFocusMeteringLock
            val focusLabel = when {
                state.isManualFocus -> "MF"
                isFocusLocked -> "AF-L"
                else -> "AF"
            }
            val isFocusManualOrLocked = state.isManualFocus || isFocusLocked
            val focusVal = if (state.isManualFocus) formatFocusDist(state.currentFocusDistance) else formatFocusDist(state.liveFocusDistance)
            binding.pillFocus?.let { pill ->
                val isTabActive = activeManualTab == "Focus"
                CameraBentoDeckView.styleBentoProPill(pill, isTabActive, isFocusManualOrLocked)
                pill.text = CameraBentoDeckView.formatProTelemetry(focusLabel, focusVal, isTabActive, isFocusManualOrLocked, pill.context)
            }

            // ISO
            val isoLabel = "ISO"
            val isoVal = if (state.isManualIso) "${state.currentIso}" else "${state.liveIso}"
            binding.pillIso?.let { pill ->
                val isTabActive = activeManualTab == "ISO"
                CameraBentoDeckView.styleBentoProPill(pill, isTabActive, state.isManualIso)
                pill.text = CameraBentoDeckView.formatProTelemetry(isoLabel, isoVal, isTabActive, state.isManualIso, pill.context)
            }

            // Shutter
            val shutterLabel = "SEC"
            val shutterVal = if (state.isManualShutter) formatShutterTime(state.currentExposureTime) else formatShutterTime(state.liveExposureTime)
            binding.pillShutter?.let { pill ->
                val isTabActive = activeManualTab == "Shutter"
                CameraBentoDeckView.styleBentoProPill(pill, isTabActive, state.isManualShutter)
                pill.text = CameraBentoDeckView.formatProTelemetry(shutterLabel, shutterVal, isTabActive, state.isManualShutter, pill.context)
            }

            // EV
            val evLabel = "EV"
            val evVal = if (state.currentEvIndex == 0) "0.0" else String.format(Locale.US, "%+d", state.currentEvIndex)
            binding.pillEv?.let { pill ->
                val isTabActive = activeManualTab == "EV"
                val isManualEv = state.currentEvIndex != 0
                CameraBentoDeckView.styleBentoProPill(pill, isTabActive, isManualEv)
                pill.text = CameraBentoDeckView.formatProTelemetry(evLabel, evVal, isTabActive, isManualEv, pill.context)
            }

            // Multi-Camera Link Button (Only visible if coordinator supports link or front PiP is active)
            val isMultiCamActive = (coordinator.activeCoordinator?.supportsProLink() == true) ||
                state.isFrontPipActive ||
                (state.availableLenses.size > 1 && env.sessionArbiter.multiCameraManager != null)
            binding.btnProLink?.visibility = if (isMultiCamActive) View.VISIBLE else View.GONE
            (binding.pillEv?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val targetMarginEnd = if (isMultiCamActive) (5f * density).roundToInt() else 0
                if (lp.marginEnd != targetMarginEnd) {
                    lp.marginEnd = targetMarginEnd
                    binding.pillEv?.layoutParams = lp
                }
            }
            binding.btnProLink?.let { linkBtn ->
                CameraBentoDeckView.styleBentoProLink(linkBtn, state.isMultiCameraManualLinked)
            }

            // Sync manual flags to ViewModel & update tier button styling
            env.cameraViewModel.updateManualFlags(
                isManualFocus = state.isManualFocus,
                isManualIso = state.isManualIso,
                isManualShutter = state.isManualShutter,
                isProLinked = state.isMultiCameraManualLinked
            )
            updateTierUi()

            // Update Level 3 Dial Theme Colors
            binding.dialWheelView?.setThemeColors(primaryColor, onSurfaceColor, onSurfaceVariantColor)
            binding.tvDialTargetLabel?.setTextColor(primaryColor)
            binding.tvDialTargetValue?.setTextColor(onSurfaceColor)
        } finally {
            isUpdatingProInfoBar = false
        }
    }

    fun updateBentoFolding(isDialOpen: Boolean) {
        val binding = ui.cameraUiContainerBinding ?: return
        val density = env.hostContext.resources.displayMetrics.density
        val tier = CameraBentoDeckView.resolveScreenTier(env.hostContext.resources)
        val isCompact = tier == CameraBentoDeckView.BentoScreenTier.COMPACT

        if (isDialOpen) {
            binding.bentoFilmRow?.visibility = View.GONE
            val lensHeightDp = if (isCompact) 34 else 42
            binding.lensControlRow?.let { v ->
                val lp = v.layoutParams
                val h = (lensHeightDp * density).roundToInt()
                if (lp != null && lp.height != h) {
                    lp.height = h
                    v.layoutParams = lp
                }
            }
            val shutterHeightDp = if (isCompact) 64 else 72
            binding.bentoShutterRow?.let { v ->
                val lp = v.layoutParams
                val h = (shutterHeightDp * density).roundToInt()
                if (lp != null && lp.height != h) {
                    lp.height = h
                    v.layoutParams = lp
                }
            }
        } else {
            val uiTier = env.cameraViewModel.uiState.value.uiTier
            val spec = CameraBentoDeckView.resolveVisibilitySpec(settingsRepository.current(), uiTier)
            binding.bentoFilmRow?.visibility = if (spec.showFilmRow) View.VISIBLE else View.GONE
            CameraBentoDeckView.applyDynamicRowHeights(binding)
        }
    }

    fun formatShutterTime(nanos: Long): String {
        val ms = nanos / 1_000_000.0
        return if (ms < 950.0) {
            val fraction = 1000.0 / ms
            if (fraction >= 10.0) String.format(Locale.US, "1/%.0fs", fraction)
            else String.format(Locale.US, "1/%.1fs", fraction)
        } else {
            String.format(Locale.US, "%.1fs", ms / 1000.0)
        }
    }

    fun formatFocusDist(dist: Float): String {
        val minDist = env.runtimeState.minFocusDistance
        return if (dist <= 0.001f) "∞"
        else if (minDist > 0 && dist >= minDist * 0.95f) "Macro"
        else {
            val meters = 1.0f / dist
            if (meters < 1.0f) String.format(Locale.US, "%.0fcm", meters * 100f)
            else String.format(Locale.US, "%.1fm", meters)
        }
    }

    fun formatEvVal(evIndex: Int): String {
        return if (evIndex == 0) "EV 0.0"
        else String.format(Locale.US, "EV %+d", evIndex)
    }

    fun buildShutterDialItems(range: android.util.Range<Long>?): List<ProDialWheelView.DialItem> {
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
            ProDialWheelView.DialItem(
                id = nanos.toString(),
                label = label,
                rawValue = nanos.toDouble(),
                isMajor = isMajor
            )
        }
    }

    fun buildIsoDialItems(range: android.util.Range<Int>?): List<ProDialWheelView.DialItem> {
        val standardIsos = listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
            800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800
        )
        val lower = range?.lower ?: 50
        val upper = range?.upper ?: 6400
        return standardIsos.filter { it in lower..upper }.map { iso ->
            val isMajor = iso in listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
            ProDialWheelView.DialItem(
                id = iso.toString(),
                label = iso.toString(),
                rawValue = iso.toDouble(),
                isMajor = isMajor
            )
        }
    }

    fun buildFocusDialItems(maxDiopter: Float): List<ProDialWheelView.DialItem> {
        if (maxDiopter <= 0f) {
            return listOf(
                ProDialWheelView.DialItem("inf", "∞", 0.0, isMajor = true)
            )
        }
        val items = mutableListOf<ProDialWheelView.DialItem>()
        items.add(ProDialWheelView.DialItem("inf", "∞", 0.0, isMajor = true))

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
                    ProDialWheelView.DialItem(
                        id = d.toString(),
                        label = label,
                        rawValue = d.toDouble(),
                        isMajor = isMajor
                    )
                )
            }
        }
        items.add(
            ProDialWheelView.DialItem(
                id = "macro",
                label = "Macro",
                rawValue = maxDiopter.toDouble(),
                isMajor = true
            )
        )
        return items
    }

    fun buildEvDialItems(range: android.util.Range<Int>?): List<ProDialWheelView.DialItem> {
        val lower = range?.lower ?: -6
        val upper = range?.upper ?: 6
        val items = mutableListOf<ProDialWheelView.DialItem>()
        for (i in lower..upper) {
            val label = if (i == 0) "0" else if (i > 0) "+$i" else "$i"
            items.add(
                ProDialWheelView.DialItem(
                    id = i.toString(),
                    label = label,
                    rawValue = i.toDouble(),
                    isMajor = (i % 3 == 0 || i == 0)
                )
            )
        }
        return items
    }

    fun bindTierControls(
        onVisibilityRefresh: () -> Unit,
        onLensRefresh: () -> Unit
    ) {
        val uiBinding = ui.cameraUiContainerBinding ?: return
        uiBinding.btnFocusPeakingBadge?.setOnClickListener { btn ->
            btn.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val currentState = settingsRepository.current().focusPeakingActive
            settingsRepository.setFocusPeakingActive(!currentState)
            updateFocusPeakingState()
        }

        val savedTier = CameraUiTier.fromKey(settingsRepository.current().uiTier)
        env.cameraViewModel.setUiTier(savedTier)
        updateTierUi(savedTier)

        uiBinding.btnBentoTier?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val r = ui.rootViewGroup
            if (r != null) {
                val transition = android.transition.AutoTransition().apply {
                    duration = 200
                    ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                }
                android.transition.TransitionManager.beginDelayedTransition(r, transition)
            }
            val nextTier = env.cameraViewModel.uiState.value.uiTier.next()
            settingsRepository.setUiTier(nextTier.key)
            env.cameraViewModel.setUiTier(nextTier)
            updateTierUi(nextTier)
            val nextSpec = CameraBentoDeckView.resolveVisibilitySpec(settingsRepository.current(), nextTier)
            if (!nextSpec.showProModule && activeManualTab != null) {
                env.cameraViewModel.setActiveManualTab(null)
                updateBentoFolding(isDialOpen = false)
                updateFocusPeakingState()
                updateProInfoBar()
            } else if (nextSpec.showProModule) {
                updateProInfoBar()
            }
            onVisibilityRefresh()
            onLensRefresh()
            updateFocusPeakingState()
        }
    }

    fun applyDeckVisibility(
        supportsMotionPhoto: Boolean,
        isHdrPlusEnabled: Boolean,
        beforeApply: () -> Unit
    ) {
        val uiBinding = ui.cameraUiContainerBinding ?: return
        val currentTier = env.cameraViewModel.uiState.value.uiTier
        val spec = CameraBentoDeckView.resolveVisibilitySpec(settingsRepository.current(), currentTier)

        val hasMultipleLenses = (uiBinding.lensControlsContainer?.childCount ?: 0) > 1
        val isAnyManual = env.runtimeState.hasAnyManualOverride

        beforeApply()

        val isFlashOrUnderexposureAvailable = isHdrPlusEnabled || (uiBinding.flashButton?.visibility == View.VISIBLE)
        CameraBentoDeckView.applyVisibilitySpec(
            binding = uiBinding,
            spec = spec,
            hasMultipleLenses = hasMultipleLenses,
            isAnyManual = isAnyManual,
            hasActiveManualTab = activeManualTab != null,
            supportsMotionPhoto = supportsMotionPhoto,
            isFlashOrUnderexposureAvailable = isFlashOrUnderexposureAvailable
        )
        CameraBentoDeckView.applyDynamicRowHeights(uiBinding)
        uiBinding.lensControlsContainer?.let { container ->
            for (i in 0 until container.childCount) {
                container.getChildAt(i)?.requestLayout()
            }
        }
        updateFocusPeakingState()
    }
}

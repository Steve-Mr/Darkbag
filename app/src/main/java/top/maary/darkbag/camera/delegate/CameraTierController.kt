package top.maary.darkbag.camera.delegate

import android.content.Context
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.processor.lut.LutProcessor
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.ProDialWheelView
import top.maary.darkbag.viewmodel.CameraUiTier
import top.maary.darkbag.viewmodel.CameraViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

interface CameraTierHost {
    val context: Context
    val binding: CameraUiContainerBinding?
    val rootViewGroup: ViewGroup?
    val activeCoordinator: CaptureModeCoordinator?
    val cameraViewModel: CameraViewModel
    val lutProcessor: LutProcessor?
    val isFrontPipActive: Boolean
    val hasMultipleAvailableLenses: Boolean
    val hasMultiCameraManager: Boolean
    val minFocusDistance: Float
    val isoRange: android.util.Range<Int>?
    val exposureTimeRange: android.util.Range<Long>?
    val evRange: android.util.Range<Int>?

    var currentFocusDistance: Float
    var liveFocusDistance: Float
    var isManualFocus: Boolean

    var currentIso: Int
    var liveIso: Int
    var isManualIso: Boolean

    var currentExposureTime: Long
    var liveExposureTime: Long
    var isManualShutter: Boolean

    var currentEvIndex: Int
    var isMultiCameraManualLinked: Boolean
    var hasFocusMeteringLock: Boolean

    fun applyCameraControls()
    fun clearFocusMetering()
    fun clearExposureMetering()
}

/**
 * Manages Bento camera tier switching (P&S / FILM / PRO),
 * manual parameter pills, Level 3 dial wheel interactions, and PRO telemetry.
 */
class CameraTierController(
    private val host: CameraTierHost
) {
    var activeManualTab: String? = null
        private set

    fun initManualControls() {
        val prefs = host.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val binding = host.binding ?: return
        binding.btnProMode?.visibility = View.GONE

        // Dismiss touch overlay
        binding.touchOverlay?.setOnClickListener {
            dismissFloatingOverlays()
        }

        host.isMultiCameraManualLinked = prefs.getBoolean("pref_multi_camera_manual_linked", true)

        // Level 2: Pill Click Listeners (Toggles Level 3 Dial Panel)
        binding.pillFocus?.setOnClickListener { toggleManualTab("Focus") }
        binding.pillIso?.setOnClickListener { toggleManualTab("ISO") }
        binding.pillShutter?.setOnClickListener { toggleManualTab("Shutter") }
        binding.pillEv?.setOnClickListener { toggleManualTab("EV") }

        // Multi-Camera Sync Link Button
        binding.btnProLink?.setOnClickListener { v ->
            host.isMultiCameraManualLinked = !host.isMultiCameraManualLinked
            val p = host.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            p.edit().putBoolean("pref_multi_camera_manual_linked", host.isMultiCameraManualLinked).apply()
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            val msg = if (host.isMultiCameraManualLinked) {
                host.context.getString(R.string.multi_camera_link_synced)
            } else {
                host.context.getString(R.string.multi_camera_link_primary_only)
            }
            Toast.makeText(host.context, msg, Toast.LENGTH_SHORT).show()
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
            host.currentFocusDistance = host.minFocusDistance
            host.isManualFocus = true
            host.applyCameraControls()
            updateDialPanel()
            updateProInfoBar()
        }

        binding.btnFocusFar?.setOnClickListener {
            host.currentFocusDistance = 0.0f
            host.isManualFocus = true
            host.applyCameraControls()
            updateDialPanel()
            updateProInfoBar()
        }

        updateProInfoBar()
        updateTierUi()
    }

    fun bindTierSwitcher() {
        val initialPrefs = host.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val savedTier = CameraUiTier.fromKey(initialPrefs.getString(CameraBentoDeckView.PREF_KEY_UI_TIER, CameraUiTier.FILM.key))
        host.cameraViewModel.setUiTier(savedTier)
        updateTierUi(savedTier)

        host.binding?.btnBentoTier?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val root = host.rootViewGroup
            if (root != null) {
                val transition = android.transition.AutoTransition().apply {
                    duration = 200
                    ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                }
                android.transition.TransitionManager.beginDelayedTransition(root, transition)
            }
            val tierPrefs = host.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val nextTier = host.cameraViewModel.uiState.value.uiTier.next()
            tierPrefs.edit().putString(CameraBentoDeckView.PREF_KEY_UI_TIER, nextTier.key).apply()
            host.cameraViewModel.setUiTier(nextTier)
            updateTierUi(nextTier)
        }
    }

    fun updateTierUi(tier: CameraUiTier = host.cameraViewModel.uiState.value.uiTier) {
        val binding = host.binding ?: return
        val prefs = host.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        CameraBentoDeckView.bindTierState(
            binding = binding,
            tier = tier,
            prefs = prefs,
            isManualFocus = host.isManualFocus,
            isManualIso = host.isManualIso,
            isManualShutter = host.isManualShutter,
            isProLinked = host.isMultiCameraManualLinked
        )
        updateFocusPeakingState()
        updateProInfoBar()
    }

    fun toggleManualTab(tab: String) {
        val binding = host.binding ?: return
        val root = host.rootViewGroup
        if (root != null) {
            val transition = android.transition.AutoTransition().apply {
                duration = 200
                ordering = android.transition.TransitionSet.ORDERING_TOGETHER
            }
            android.transition.TransitionManager.beginDelayedTransition(root, transition)
        }
        if (activeManualTab == tab) {
            // Collapse dial
            activeManualTab = null
            host.cameraViewModel.setActiveManualTab(null)
            binding.manualControlsRoot?.visibility = View.GONE
            binding.touchOverlay?.visibility = View.GONE
            updateBentoFolding(isDialOpen = false)
            updateFocusPeakingState()
            updateProInfoBar()
        } else {
            // Open dial
            activeManualTab = tab
            host.cameraViewModel.setActiveManualTab(tab)
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
        val binding = host.binding ?: return
        if (activeManualTab != null) {
            val root = host.rootViewGroup
            if (root != null) {
                val transition = android.transition.AutoTransition().apply {
                    duration = 200
                    ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                }
                android.transition.TransitionManager.beginDelayedTransition(root, transition)
            }
            activeManualTab = null
            host.cameraViewModel.setActiveManualTab(null)
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
        val prefs = host.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val currentTier = host.cameraViewModel.uiState.value.uiTier
        val spec = CameraBentoDeckView.resolveVisibilitySpec(prefs, currentTier)
        val isBadgeVisible = spec.enableFocusPeaking
        val badge = host.binding?.btnFocusPeakingBadge

        badge?.visibility = if (isBadgeVisible) View.VISIBLE else View.GONE
        if (isBadgeVisible) {
            val isActive = prefs.getBoolean(SettingsFragment.KEY_FOCUS_PEAKING_ACTIVE, true)
            if (badge != null) {
                CameraBentoDeckView.styleFocusPeakingBadge(badge, isActive)
            }
            host.lutProcessor?.setFocusPeakingEnabled(isActive)
        } else {
            host.lutProcessor?.setFocusPeakingEnabled(false)
        }
    }

    fun handleDialSelection(item: ProDialWheelView.DialItem) {
        val binding = host.binding ?: return
        when (activeManualTab) {
            "Focus" -> {
                host.currentFocusDistance = item.rawValue.toFloat()
                host.isManualFocus = true
                binding.tvDialTargetValue?.text = formatFocusDist(host.currentFocusDistance)
                updateFocusPeakingState()
            }
            "ISO" -> {
                host.currentIso = item.rawValue.toInt()
                host.isManualIso = true
                binding.tvDialTargetValue?.text = "${host.currentIso}"
            }
            "Shutter" -> {
                host.currentExposureTime = item.rawValue.toLong()
                host.isManualShutter = true
                binding.tvDialTargetValue?.text = formatShutterTime(host.currentExposureTime)
            }
            "EV" -> {
                host.currentEvIndex = item.rawValue.toInt()
                binding.tvDialTargetValue?.text = formatEvVal(host.currentEvIndex)
            }
        }
        host.applyCameraControls()
        updateProInfoBar()
    }

    fun resetCurrentManualParameter() {
        when (activeManualTab) {
            "Focus" -> {
                host.isManualFocus = false
                host.clearFocusMetering()
                updateFocusPeakingState()
            }
            "ISO" -> {
                host.isManualIso = false
                host.clearExposureMetering()
            }
            "Shutter" -> {
                host.isManualShutter = false
                host.clearExposureMetering()
            }
            "EV" -> {
                host.currentEvIndex = 0
                host.clearExposureMetering()
            }
        }
        host.applyCameraControls()
        updateDialPanel()
        updateProInfoBar()
    }

    fun updateDialPanel() {
        val binding = host.binding ?: return
        val wheel = binding.dialWheelView ?: return
        binding.focusExtras?.visibility = if (activeManualTab == "Focus") View.VISIBLE else View.GONE

        when (activeManualTab) {
            "Focus" -> {
                binding.tvDialTargetLabel?.text = "FOCUS"
                val items = buildFocusDialItems(host.minFocusDistance)
                val targetVal = if (host.isManualFocus) host.currentFocusDistance else 0.0f
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
                binding.tvDialTargetValue?.text = if (host.isManualFocus) formatFocusDist(host.currentFocusDistance) else "AUTO (${formatFocusDist(host.liveFocusDistance)})"
            }
            "ISO" -> {
                binding.tvDialTargetLabel?.text = "ISO"
                val items = buildIsoDialItems(host.isoRange)
                val targetVal = if (host.isManualIso) host.currentIso else host.liveIso
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
                binding.tvDialTargetValue?.text = if (host.isManualIso) "${host.currentIso}" else "AUTO (${host.liveIso})"
            }
            "Shutter" -> {
                binding.tvDialTargetLabel?.text = "SHUTTER"
                val items = buildShutterDialItems(host.exposureTimeRange)
                val targetVal = if (host.isManualShutter) host.currentExposureTime else host.liveExposureTime
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
                binding.tvDialTargetValue?.text = if (host.isManualShutter) formatShutterTime(host.currentExposureTime) else "AUTO (${formatShutterTime(host.liveExposureTime)})"
            }
            "EV" -> {
                binding.tvDialTargetLabel?.text = "EV"
                val items = buildEvDialItems(host.evRange)
                var closestIndex = 0
                var minDiff = Double.MAX_VALUE
                items.forEachIndexed { i, it ->
                    val diff = abs(it.rawValue - host.currentEvIndex)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIndex = i
                    }
                }
                wheel.setItems(items, closestIndex)
                binding.tvDialTargetValue?.text = formatEvVal(host.currentEvIndex)
            }
        }
    }

    fun updateProInfoBar() {
        val binding = host.binding ?: return
        val primaryColor = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, Color.parseColor("#FFD54F"))
        val onSurfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val onSurfaceVariantColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)
        val density = host.context.resources.displayMetrics.density

        // Focus
        val isFocusLocked = !host.isManualFocus && host.hasFocusMeteringLock
        val focusLabel = when {
            host.isManualFocus -> "MF"
            isFocusLocked -> "AF-L"
            else -> "AF"
        }
        val isFocusManualOrLocked = host.isManualFocus || isFocusLocked
        val focusVal = if (host.isManualFocus) formatFocusDist(host.currentFocusDistance) else formatFocusDist(host.liveFocusDistance)
        binding.pillFocus?.let { pill ->
            val isTabActive = activeManualTab == "Focus"
            CameraBentoDeckView.styleBentoProPill(pill, isTabActive, isFocusManualOrLocked)
            pill.text = CameraBentoDeckView.formatProTelemetry(focusLabel, focusVal, isTabActive, isFocusManualOrLocked, pill.context)
        }

        // ISO
        val isoLabel = "ISO"
        val isoVal = if (host.isManualIso) "${host.currentIso}" else "${host.liveIso}"
        binding.pillIso?.let { pill ->
            val isTabActive = activeManualTab == "ISO"
            CameraBentoDeckView.styleBentoProPill(pill, isTabActive, host.isManualIso)
            pill.text = CameraBentoDeckView.formatProTelemetry(isoLabel, isoVal, isTabActive, host.isManualIso, pill.context)
        }

        // Shutter
        val shutterLabel = "SEC"
        val shutterVal = if (host.isManualShutter) formatShutterTime(host.currentExposureTime) else formatShutterTime(host.liveExposureTime)
        binding.pillShutter?.let { pill ->
            val isTabActive = activeManualTab == "Shutter"
            CameraBentoDeckView.styleBentoProPill(pill, isTabActive, host.isManualShutter)
            pill.text = CameraBentoDeckView.formatProTelemetry(shutterLabel, shutterVal, isTabActive, host.isManualShutter, pill.context)
        }

        // EV
        val evLabel = "EV"
        val evVal = if (host.currentEvIndex == 0) "0.0" else String.format(Locale.US, "%+d", host.currentEvIndex)
        binding.pillEv?.let { pill ->
            val isTabActive = activeManualTab == "EV"
            val isManualEv = host.currentEvIndex != 0
            CameraBentoDeckView.styleBentoProPill(pill, isTabActive, isManualEv)
            pill.text = CameraBentoDeckView.formatProTelemetry(evLabel, evVal, isTabActive, isManualEv, pill.context)
        }

        // Multi-Camera Link Button (Only visible if coordinator supports link or front PiP is active)
        val isMultiCamActive = (host.activeCoordinator?.supportsProLink() == true) || host.isFrontPipActive || (host.hasMultipleAvailableLenses && host.hasMultiCameraManager)
        binding.btnProLink?.visibility = if (isMultiCamActive) View.VISIBLE else View.GONE
        (binding.pillEv?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val targetMarginEnd = if (isMultiCamActive) (5f * density).roundToInt() else 0
            if (lp.marginEnd != targetMarginEnd) {
                lp.marginEnd = targetMarginEnd
                binding.pillEv?.layoutParams = lp
            }
        }
        binding.btnProLink?.let { linkBtn ->
            CameraBentoDeckView.styleBentoProLink(linkBtn, host.isMultiCameraManualLinked)
        }

        // Sync manual flags to ViewModel & update tier button styling
        host.cameraViewModel.updateManualFlags(
            isManualFocus = host.isManualFocus,
            isManualIso = host.isManualIso,
            isManualShutter = host.isManualShutter,
            isProLinked = host.isMultiCameraManualLinked
        )
        updateTierUi()

        // Update Level 3 Dial Theme Colors
        binding.dialWheelView?.setThemeColors(primaryColor, onSurfaceColor, onSurfaceVariantColor)
        binding.tvDialTargetLabel?.setTextColor(primaryColor)
        binding.tvDialTargetValue?.setTextColor(onSurfaceColor)
    }

    fun updateBentoFolding(isDialOpen: Boolean) {
        val binding = host.binding ?: return
        val density = host.context.resources.displayMetrics.density
        val tier = CameraBentoDeckView.resolveScreenTier(host.context.resources)
        val isCompact = tier == CameraBentoDeckView.BentoScreenTier.COMPACT

        if (isDialOpen) {
            // Collapse bento_film_row to View.GONE (reclaims vertical space!)
            binding.bentoFilmRow?.visibility = View.GONE
            // Compact lens_control_row height: 34dp on compact, 42dp on standard/tall
            val lensHeightDp = if (isCompact) 34 else 42
            binding.lensControlRow?.let { v ->
                val lp = v.layoutParams
                val h = (lensHeightDp * density).roundToInt()
                if (lp != null && lp.height != h) {
                    lp.height = h
                    v.layoutParams = lp
                }
            }
            // Compact bento_shutter_row height: 64dp on compact, 72dp on standard/tall
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
            // Restore bento_film_row to View.VISIBLE if enabled in current tier spec
            val prefs = host.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val uiTier = host.cameraViewModel.uiState.value.uiTier
            val spec = CameraBentoDeckView.resolveVisibilitySpec(prefs, uiTier)
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
        return if (dist <= 0.001f) "∞"
        else if (host.minFocusDistance > 0 && dist >= host.minFocusDistance * 0.95f) "Macro"
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
}

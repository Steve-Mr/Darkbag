package top.maary.darkbag.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.viewmodel.CameraUiTier
import kotlin.math.roundToInt

typealias DeckVisibilitySpec = CameraBentoDeckView.DeckVisibilitySpec
typealias VisibilitySpec = CameraBentoDeckView.DeckVisibilitySpec

/**
 * Visibility resolution, tier binding, recipe-row binding, and WYSIWYG customizer preview
 * binder for [CameraBentoDeckView].
 */
object BentoDeckPreviewBinder {

    @JvmStatic
    fun resolveVisibilitySpec(prefs: DarkbagPreferences, tier: CameraUiTier): DeckVisibilitySpec {
        return when (tier) {
            CameraUiTier.POINT_AND_SHOOT -> presetSpec(tier)
            CameraUiTier.FILM -> presetSpec(tier)
            CameraUiTier.PRO -> presetSpec(tier)
            CameraUiTier.CUSTOM -> DeckVisibilitySpec(
                tier = tier,
                showProModule = prefs.enableManualControls,
                showLogAndLut = prefs.showLutSwitcher,
                showHdrPlusPill = prefs.showHdrPlusSwitch,
                showUnderexposure = prefs.showHdrUnderexposureButton,
                showMotionPhoto = true,
                showModeSwitch = prefs.showModeSwitchButton,
                showLensControls = prefs.showLensControls,
                showCameraSwitch = prefs.showCameraSwitchButton,
                showSettingsButton = prefs.showSettingsButton,
                enableFocusPeaking = prefs.expFocusPeaking
            )
        }
    }

    private fun presetSpec(tier: CameraUiTier): DeckVisibilitySpec = when (tier) {
        CameraUiTier.POINT_AND_SHOOT -> DeckVisibilitySpec(
            tier = tier, showProModule = false, showLogAndLut = false,
            showHdrPlusPill = false, showUnderexposure = false, showMotionPhoto = false,
            showModeSwitch = true, showLensControls = true, showCameraSwitch = true,
            showSettingsButton = true, enableFocusPeaking = false
        )
        CameraUiTier.FILM -> DeckVisibilitySpec(
            tier = tier, showProModule = false, showLogAndLut = true,
            showHdrPlusPill = true, showUnderexposure = true, showMotionPhoto = true,
            showModeSwitch = true, showLensControls = true, showCameraSwitch = true,
            showSettingsButton = true, enableFocusPeaking = false
        )
        CameraUiTier.PRO -> DeckVisibilitySpec(
            tier = tier, showProModule = true, showLogAndLut = true,
            showHdrPlusPill = true, showUnderexposure = true, showMotionPhoto = true,
            showModeSwitch = true, showLensControls = true, showCameraSwitch = true,
            showSettingsButton = true, enableFocusPeaking = true
        )
        CameraUiTier.CUSTOM -> DeckVisibilitySpec(tier = tier)
    }

    @JvmStatic
    @JvmOverloads
    fun bindTierState(
        binding: CameraUiContainerBinding,
        tier: CameraUiTier,
        isAnyManual: Boolean = false,
        hasActiveManualTab: Boolean = false,
        showLutSwitcherPref: Boolean = true,
        showManualControlsPref: Boolean = false,
        visibilitySpec: DeckVisibilitySpec? = null
    ) {
        val root = binding.bentoDeckView ?: binding.bottomIslandCard ?: return
        val density = root.resources.displayMetrics.density

        val spec = visibilitySpec ?: if (tier == CameraUiTier.CUSTOM) {
            DeckVisibilitySpec(
                tier = tier,
                showProModule = showManualControlsPref,
                showLogAndLut = showLutSwitcherPref,
                showHdrPlusPill = true,
                showUnderexposure = true,
                showMotionPhoto = true,
                showModeSwitch = true,
                showLensControls = true,
                showCameraSwitch = true,
                showSettingsButton = true,
                enableFocusPeaking = false
            )
        } else {
            presetSpec(tier)
        }

        val colorPrimary = MaterialColors.getColor(root, android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(root, MaterialR.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorOnPrimaryContainer = MaterialColors.getColor(root, MaterialR.attr.colorOnPrimaryContainer, Color.WHITE)
        val colorSecondaryContainer = MaterialColors.getColor(root, MaterialR.attr.colorSecondaryContainer, Color.DKGRAY)
        val colorOnSecondaryContainer = MaterialColors.getColor(root, MaterialR.attr.colorOnSecondaryContainer, Color.WHITE)
        val colorSurfaceContainerHighest = MaterialColors.getColor(root, MaterialR.attr.colorSurfaceContainerHighest, Color.GRAY)
        val colorOnSurface = MaterialColors.getColor(root, MaterialR.attr.colorOnSurface, Color.WHITE)
        val colorInverseSurface = MaterialColors.getColor(root, MaterialR.attr.colorSurfaceInverse, Color.DKGRAY)
        val colorInverseOnSurface = MaterialColors.getColor(root, MaterialR.attr.colorOnSurfaceInverse, Color.WHITE)

        binding.btnBentoTier?.let { btn ->
            btn.text = tier.displayLabel
            when (tier) {
                CameraUiTier.POINT_AND_SHOOT -> {
                    btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHighest)
                    btn.setTextColor(colorOnSurface)
                    btn.cornerRadius = (14f * density).roundToInt()
                }
                CameraUiTier.FILM -> {
                    btn.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                    btn.setTextColor(colorOnSecondaryContainer)
                    btn.cornerRadius = (12f * density).roundToInt()
                }
                CameraUiTier.PRO -> {
                    btn.backgroundTintList = ColorStateList.valueOf(colorInverseSurface)
                    btn.setTextColor(colorInverseOnSurface)
                    btn.cornerRadius = (10f * density).roundToInt()
                }
                CameraUiTier.CUSTOM -> {
                    btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                    btn.setTextColor(colorOnPrimaryContainer)
                    btn.cornerRadius = (10f * density).roundToInt()
                }
            }
            if (isAnyManual) {
                btn.strokeWidth = (1.5f * density).roundToInt()
                btn.strokeColor = ColorStateList.valueOf(colorPrimary)
            } else {
                btn.strokeWidth = 0
            }
        }

        val showFilmRow = spec.showFilmRow && !hasActiveManualTab
        binding.bentoFilmRow?.visibility = if (showFilmRow) View.VISIBLE else View.GONE
        binding.bentoLogButton?.visibility = if (showFilmRow && spec.showLogAndLut) View.VISIBLE else View.GONE
        binding.lutSwitcherButton?.visibility = if (showFilmRow && spec.showLogAndLut) View.VISIBLE else View.GONE
        binding.hdrPlusPill?.visibility = if (showFilmRow && spec.showHdrPlusPill) View.VISIBLE else View.GONE

        val showPro = spec.showProModule || isAnyManual
        binding.bentoProModule?.visibility = if (showPro) View.VISIBLE else View.GONE
        binding.proInfoBarCard?.visibility = if (showPro) View.VISIBLE else View.GONE
        binding.manualControlsRoot?.visibility = if (showPro && hasActiveManualTab) View.VISIBLE else View.GONE
    }

    @JvmStatic
    @JvmOverloads
    fun applyVisibilitySpec(
        binding: CameraUiContainerBinding,
        spec: DeckVisibilitySpec,
        hasMultipleLenses: Boolean = true,
        isAnyManual: Boolean = false,
        hasActiveManualTab: Boolean = false,
        supportsMotionPhoto: Boolean = true,
        isFlashOrUnderexposureAvailable: Boolean = true
    ) {
        val root = binding.bentoDeckView ?: binding.bottomIslandCard ?: binding.root
        val density = root.resources.displayMetrics.density

        bindTierState(
            binding = binding,
            tier = spec.tier,
            isAnyManual = isAnyManual,
            hasActiveManualTab = hasActiveManualTab,
            showLutSwitcherPref = spec.showLogAndLut,
            showManualControlsPref = spec.showProModule,
            visibilitySpec = spec
        )

        // Row B: Film Recipe & Quick Switches + Dynamic Weight Balancing
        val showUnderexposureTile = spec.showUnderexposure && isFlashOrUnderexposureAvailable
        val showFilmRow = (spec.showLogAndLut || spec.showHdrPlusPill || showUnderexposureTile) && !hasActiveManualTab
        binding.bentoFilmRow?.visibility = if (showFilmRow) View.VISIBLE else View.GONE
        binding.bentoLogButton?.visibility = if (showFilmRow && spec.showLogAndLut) View.VISIBLE else View.GONE
        binding.lutSwitcherButton?.visibility = if (showFilmRow && spec.showLogAndLut) View.VISIBLE else View.GONE
        binding.hdrPlusPill?.visibility = if (showFilmRow && spec.showHdrPlusPill) View.VISIBLE else View.GONE
        binding.flashButton?.visibility = if (showFilmRow && showUnderexposureTile) View.VISIBLE else View.GONE
        val showMotionTile = showFilmRow && spec.showMotionPhoto && supportsMotionPhoto
        binding.motionPhotoButton?.visibility = if (showMotionTile) View.VISIBLE else View.GONE

        val expandFilmPills = !spec.showLogAndLut
        val hdrWidthPx = (50f * density).roundToInt()
        val iconWidthPx = (44f * density).roundToInt()
        val gap5dp = (5f * density).roundToInt()

        BentoDeckDimensions.updateRowBWeight(binding.hdrPlusPill, hdrWidthPx, expandFilmPills)
        BentoDeckDimensions.updateRowBWeight(binding.flashButton, iconWidthPx, expandFilmPills)
        BentoDeckDimensions.updateRowBWeight(binding.motionPhotoButton, iconWidthPx, expandFilmPills)

        val rowBVisible = listOfNotNull(
            binding.bentoLogButton,
            binding.lutSwitcherButton,
            binding.hdrPlusPill,
            binding.flashButton,
            binding.motionPhotoButton
        ).filter { it.visibility == View.VISIBLE }
        rowBVisible.forEachIndexed { idx, v ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEachIndexed
            val targetMarginEnd = if (idx < rowBVisible.lastIndex) gap5dp else 0
            if (lp.marginEnd != targetMarginEnd) {
                lp.marginEnd = targetMarginEnd
                v.layoutParams = lp
            }
        }

        // Row C: Lens Controls & Mode/Camera Switches + Dynamic Weight Balancing
        val showLensStrip = spec.showLensControls && hasMultipleLenses
        val showLensRow = spec.showModeSwitch || showLensStrip || spec.showCameraSwitch
        binding.lensControlRow?.visibility = if (showLensRow) View.VISIBLE else View.GONE
        val isModeSwitchVisible = showLensRow && spec.showModeSwitch
        val isLensControlsVisible = showLensRow && showLensStrip
        val isCameraSwitchVisible = showLensRow && spec.showCameraSwitch

        binding.modeSwitchButton?.visibility = if (isModeSwitchVisible) View.VISIBLE else View.GONE
        binding.lensControlsCard?.visibility = if (isLensControlsVisible) View.VISIBLE else View.GONE
        binding.cameraSwitchButtonAlt?.visibility = if (isCameraSwitchVisible) View.VISIBLE else View.GONE

        val expandLensSideButtons = !showLensStrip
        val sideBtnWidthPx = (52f * density).roundToInt()
        val gap6dp = (6f * density).roundToInt()
        val sideSlotPx = sideBtnWidthPx + gap6dp

        BentoDeckDimensions.updateRowCWeight(binding.modeSwitchButton, sideBtnWidthPx, expandLensSideButtons)
        BentoDeckDimensions.updateRowCWeight(binding.cameraSwitchButtonAlt, sideBtnWidthPx, expandLensSideButtons)

        binding.lensControlsCard?.let { card ->
            val lp = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return@let
            val targetStart = if (!isModeSwitchVisible && isCameraSwitchVisible) sideSlotPx else 0
            val targetEnd = if (isModeSwitchVisible && !isCameraSwitchVisible) sideSlotPx else (if (isCameraSwitchVisible) gap6dp else 0)
            if (lp.marginStart != targetStart || lp.marginEnd != targetEnd) {
                lp.marginStart = targetStart
                lp.marginEnd = targetEnd
                card.layoutParams = lp
            }
        }
        binding.modeSwitchButton?.let { btn ->
            val lp = btn.layoutParams as? ViewGroup.MarginLayoutParams ?: return@let
            val targetEnd = if (isLensControlsVisible || isCameraSwitchVisible) gap6dp else 0
            if (lp.marginEnd != targetEnd) {
                lp.marginEnd = targetEnd
                btn.layoutParams = lp
            }
        }

        // Row D: Shutter & Right Dual-Layer Stack
        binding.settingsButton?.visibility = if (spec.showSettingsButton) View.VISIBLE else View.GONE
        (binding.btnBentoPlayground?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val targetEnd = if (spec.showSettingsButton) (4f * density).roundToInt() else 0
            if (lp.marginEnd != targetEnd) {
                lp.marginEnd = targetEnd
                binding.btnBentoPlayground.layoutParams = lp
            }
        }

        binding.btnFocusPeakingBadge?.visibility = if (spec.enableFocusPeaking) View.VISIBLE else View.GONE
    }

    @JvmStatic
    fun bindAuthenticPreview(
        binding: CameraUiContainerBinding,
        context: Context,
        prefs: DarkbagPreferences,
        spec: DeckVisibilitySpec
    ) {
        val density = context.resources.displayMetrics.density

        binding.touchOverlay?.visibility = View.GONE
        binding.topRightControls?.visibility = View.GONE
        binding.btnProMode?.visibility = View.GONE
        binding.lutListContainer?.visibility = View.GONE
        binding.manualControlsRoot?.visibility = View.GONE

        (binding.bottomIslandCard?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.setMargins(0, 0, 0, 0)
            binding.bottomIslandCard.layoutParams = lp
        }

        val rowMarginPx = (4f * density).roundToInt()
        setPreviewRowMetrics(binding.proInfoBarCard, (40f * density).roundToInt(), rowMarginPx)
        setPreviewRowMetrics(binding.bentoFilmRow, (40f * density).roundToInt(), rowMarginPx)
        setPreviewRowMetrics(binding.lensControlRow, (42f * density).roundToInt(), rowMarginPx)
        binding.bentoShutterRow?.layoutParams = binding.bentoShutterRow?.layoutParams?.apply { height = (68f * density).roundToInt() }
        binding.photoViewCard?.layoutParams = binding.photoViewCard?.layoutParams?.apply { width = (64f * density).roundToInt() }
        binding.bentoRightStack?.layoutParams = binding.bentoRightStack?.layoutParams?.apply { width = (92f * density).roundToInt() }
        val padPx = (7f * density).roundToInt()
        (binding.bottomIslandCard as? MaterialCardView)?.setContentPadding(padPx, padPx, padPx, padPx)

        val activeMode = CaptureMode.fromKey(prefs.activeCaptureMode) ?: CaptureMode.NORMAL
        binding.bottomIslandCard?.let { BentoDeckStylingEngine.styleDeckCard(it, activeMode) }

        bindPreviewProPill(binding.pillFocus, "AF", "AUTO", context)
        bindPreviewProPill(binding.pillIso, "ISO", "100", context)
        bindPreviewProPill(binding.pillShutter, "SEC", "1/125s", context)
        bindPreviewProPill(binding.pillEv, "EV", "0.0", context)
        binding.btnProLink?.visibility = if (activeMode == CaptureMode.MULTI_CAMERA) View.VISIBLE else View.GONE
        binding.btnProLink?.let { BentoDeckStylingEngine.styleBentoProLink(it, isLinked = true) }

        val targetLogName = prefs.targetLog.takeIf { !it.equals("None", ignoreCase = true) } ?: "F-Log2"
        val activeLutName = prefs.activeLut.takeIf { !it.equals("None", ignoreCase = true) } ?: "Classic Neg"
        bindRecipeRow(binding, targetLogName, activeLutName)
        BentoDeckStylingEngine.styleHdrPlusTile(binding, prefs.hdrPlusEnabled)

        val underexposureMode = prefs.hdrUnderexposureMode
        binding.flashButton?.let { btn ->
            btn.text = null
            val iconRes = when {
                underexposureMode.contains("Dynamic") -> R.drawable.ic_hdr_dynamic
                underexposureMode == "Off" -> R.drawable.ic_exposure_off
                underexposureMode == "-1 EV" -> R.drawable.ic_exposure_neg_1
                underexposureMode == "-2 EV" -> R.drawable.ic_exposure_neg_2
                else -> R.drawable.ic_hdr_dynamic
            }
            btn.setIconResource(iconRes)
            BentoDeckStylingEngine.styleBentoToggleTile(btn, isActive = underexposureMode != "Off", isTertiaryAccent = false)
        }

        val isMotionPhoto = prefs.motionPhotoEnabled
        binding.motionPhotoButton?.let { btn ->
            btn.setIconResource(if (isMotionPhoto) R.drawable.ic_motion_photo_on else R.drawable.ic_motion_photo_off)
            BentoDeckStylingEngine.styleBentoToggleTile(btn, isActive = isMotionPhoto, isTertiaryAccent = false)
        }

        binding.modeSwitchButton?.let { btn ->
            val iconRes = when (activeMode) {
                CaptureMode.MULTI_CAMERA -> R.drawable.ic_mode_multi_camera
                CaptureMode.HALF_FRAME_SBS -> R.drawable.ic_mode_half_side
                CaptureMode.HALF_FRAME_TB -> R.drawable.ic_mode_half_top
                CaptureMode.NORMAL -> R.drawable.ic_mode_normal
            }
            btn.setIconResource(iconRes)
            BentoDeckStylingEngine.styleModeSwitchButton(btn, activeMode)
        }

        populatePreviewLenses(binding.lensControlsContainer, context, density)

        binding.photoViewButton?.let { iv ->
            iv.visibility = View.VISIBLE
            iv.setImageResource(R.drawable.ic_photo)
            val pad = (16f * density).roundToInt()
            iv.setPadding(pad, pad, pad, pad)
            iv.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(iv, MaterialR.attr.colorOnSurfaceVariant, Color.GRAY)
            )
        }
        binding.cameraCaptureButton?.setHalfFrameState(activeMode.isHalfFrame, 0)

        applyVisibilitySpec(
            binding = binding,
            spec = spec,
            hasMultipleLenses = true,
            isAnyManual = false,
            hasActiveManualTab = false,
            supportsMotionPhoto = activeMode.supportsMotionPhoto,
            isFlashOrUnderexposureAvailable = true
        )

        binding.btnFocusPeakingBadge?.visibility = View.GONE
        disableInteractivityForPreview(binding.root)
    }

    @JvmStatic
    fun bindRecipeRow(binding: CameraUiContainerBinding, targetLogName: String?, activeLutName: String?) {
        val isLogActive = !targetLogName.isNullOrBlank() && !targetLogName.equals("None", ignoreCase = true)
        val isLutActive = !activeLutName.isNullOrBlank() && !activeLutName.equals("None", ignoreCase = true)

        binding.bentoLogButton?.let { btn ->
            btn.text = if (isLogActive) targetLogName else btn.context.getString(R.string.camera_recipe_log_off)
            BentoDeckStylingEngine.styleBentoToggleTile(btn, isActive = isLogActive, isTertiaryAccent = true)
        }
        binding.lutSwitcherButton?.let { btn ->
            val lutOff = btn.context.getString(R.string.camera_recipe_lut_off)
            btn.text = if (isLutActive) BentoDeckStylingEngine.formatRecipeLabel(activeLutName, lutOff) else lutOff
            BentoDeckStylingEngine.styleBentoToggleTile(btn, isActive = isLutActive, isTertiaryAccent = true)
        }
    }

    @JvmStatic
    fun disableInteractivityForPreview(view: View?) {
        if (view == null) return
        view.isClickable = false
        view.isLongClickable = false
        view.isFocusable = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableInteractivityForPreview(view.getChildAt(i))
            }
        }
    }

    private fun populatePreviewLenses(container: LinearLayout?, context: Context, density: Float) {
        if (container == null || container.childCount > 0) return
        val previewLenses = listOf(
            Triple("0.6x", false, false),
            Triple("1.0x", true, false),
            Triple("28mm", false, true),
            Triple("35mm", false, true),
            Triple("3.0x", false, false)
        )
        val marginPx = (2.5f * density).roundToInt()
        for ((label, isActive, isDigitalCrop) in previewLenses) {
            val btn = MaterialButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f).apply {
                    marginStart = marginPx
                    marginEnd = marginPx
                }
                text = label
                textSize = 10.5f
                isSingleLine = true
                maxLines = 1
                gravity = Gravity.CENTER
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
            }
            BentoDeckStylingEngine.styleBentoLensButton(btn, isActive = isActive, isDigitalCrop = isDigitalCrop)
            container.addView(btn)
        }
    }

    private fun setPreviewRowMetrics(view: View?, heightPx: Int, bottomMarginPx: Int) {
        (view?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.height = heightPx
            lp.bottomMargin = bottomMarginPx
            view.layoutParams = lp
        }
    }

    private fun bindPreviewProPill(pill: MaterialButton?, label: String, value: String, context: Context) {
        pill ?: return
        BentoDeckStylingEngine.styleBentoProPill(pill, isTabActive = false, isValueManual = false)
        pill.text = BentoDeckStylingEngine.formatProTelemetry(label, value, false, false, context)
    }
}

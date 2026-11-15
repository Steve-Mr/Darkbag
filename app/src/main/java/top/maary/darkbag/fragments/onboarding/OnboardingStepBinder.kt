package top.maary.darkbag.fragments.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.CompoundButton
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentPermissionsBinding
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.bento.BentoSwitchTile
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.viewmodel.CameraUiTier

/**
 * Coordinates the 4-step Bento Matrix onboarding UI inside [PermissionsFragment]:
 * - Step 1: Startup module cards & tiered permission tiles
 * - Step 2: Output formats (JPG/DNG) & optical defaults (focal length, motion photo, location)
 * - Step 3: Base Log & 3D LUT recipe pairing
 * - Step 4: Viewfinder UI tier & shutter hold action
 */
class OnboardingStepBinder(
    private val binding: FragmentPermissionsBinding,
    private val settingsRepository: SettingsRepository,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun requestCameraPermission()
        fun requestNotificationPermission()
        fun requestAudioPermission()
        fun requestLocationPermissions(permissions: Array<String>)
        fun requestBatchStartupPermissions(permissions: Array<String>)
        fun launchLutPicker(selectedLog: String)
        fun onFinishOnboarding()
    }

    var currentStep: Int = 1
        private set
    var selectedPairingLog: String = "F-Log2"
        private set
    var selectedUiTier: String = "FILM"
        private set

    private val context: Context
        get() = binding.root.context

    fun setupAllSteps() {
        setupHeaderControls()
        setupStep1StartupAndPermissions()
        setupStep2FormatsAndOptics()
        setupStep3RecipePairing()
        setupStep4ViewfinderTier()
        setupBottomActionDock()
    }

    fun refreshStepsAfterHydration() {
        setupStep1StartupAndPermissions()
        setupStep2FormatsAndOptics()
        setupStep3RecipePairing()
        setupStep4ViewfinderTier()
    }

    fun setSaveLocationChecked(checked: Boolean) {
        binding.switchSaveLocation.bindSilently(checked) { onSaveLocationToggled(it) }
    }

    fun showImportedRecipeResult(importedFileName: String, pairingLog: String) {
        binding.layoutPairingResult.visibility = View.VISIBLE
        binding.tvPairingDesc.text = context.getString(R.string.onboarding_recipe_imported_count, importedFileName, pairingLog)
    }

    private fun setupHeaderControls() {
        binding.btnHeaderBack.setOnClickListener {
            if (currentStep > 1) goToStep(currentStep - 1)
        }
        binding.btnHeaderSkip.setOnClickListener {
            val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!cameraGranted && settingsRepository.current().defaultStartupPage == PreferenceKeys.STARTUP_CAMERA) {
                callbacks.requestCameraPermission()
            } else {
                callbacks.onFinishOnboarding()
            }
        }
    }

    // =========================================================================
    // Step 1: Startup Module & Permissions
    // =========================================================================
    private fun setupStep1StartupAndPermissions() {
        updateStartupModuleCards(settingsRepository.current().defaultStartupPage)

        binding.cardStartupCamera.setOnClickListener {
            settingsRepository.setDefaultStartupPage(PreferenceKeys.STARTUP_CAMERA)
            updateStartupModuleCards(PreferenceKeys.STARTUP_CAMERA)
        }
        binding.cardStartupPlayground.setOnClickListener {
            settingsRepository.setDefaultStartupPage(PreferenceKeys.STARTUP_PLAYGROUND)
            updateStartupModuleCards(PreferenceKeys.STARTUP_PLAYGROUND)
        }

        binding.tilePermCamera.setOnClickListener { callbacks.requestCameraPermission() }
        binding.btnGrantCamera.setOnClickListener { callbacks.requestCameraPermission() }
        val onNotifClick = View.OnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) callbacks.requestNotificationPermission()
        }
        binding.tilePermNotifications.setOnClickListener(onNotifClick)
        binding.btnGrantNotifications.setOnClickListener(onNotifClick)
        binding.tilePermAudio.setOnClickListener { callbacks.requestAudioPermission() }
        binding.btnGrantAudio.setOnClickListener { callbacks.requestAudioPermission() }

        updatePermissionTilesState()
    }

    private fun updateStartupModuleCards(selectedMode: String) {
        val colorOnSurface = m3Color(MaterialR.attr.colorOnSurface, Color.WHITE)
        val colorOnPrimary = m3Color(MaterialR.attr.colorOnPrimaryContainer, Color.WHITE)
        val colorOnTertiary = m3Color(MaterialR.attr.colorOnTertiaryContainer, Color.WHITE)

        val isCamera = selectedMode == PreferenceKeys.STARTUP_CAMERA
        updatePrimarySquircle(binding.cardStartupCamera, isCamera)
        binding.txtStartupCameraTitle.setTextColor(if (isCamera) colorOnPrimary else colorOnSurface)
        binding.txtStartupCameraDesc.setTextColor(if (isCamera) colorOnPrimary else colorOnSurface)

        updateCardRoleSquircle(binding.cardStartupPlayground, !isCamera, MaterialR.attr.colorTertiaryContainer, MaterialR.attr.colorTertiary)
        binding.txtStartupPlaygroundTitle.setTextColor(if (!isCamera) colorOnTertiary else colorOnSurface)
        binding.txtStartupPlaygroundDesc.setTextColor(if (!isCamera) colorOnTertiary else colorOnSurface)
    }

    fun updatePermissionTilesState() {
        val camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        updatePermTile(binding.tilePermCamera, binding.btnGrantCamera, camGranted)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.tilePermNotifications.visibility = View.VISIBLE
            updatePermTile(binding.tilePermNotifications, binding.btnGrantNotifications, notifGranted)
        } else {
            binding.tilePermNotifications.visibility = View.GONE
        }
        updatePermTile(binding.tilePermAudio, binding.btnGrantAudio, audioGranted)
    }

    private fun updatePermTile(tile: MaterialCardView, btn: MaterialButton, granted: Boolean) {
        updatePrimarySquircle(tile, granted)
        btn.isEnabled = !granted
        btn.text = context.getString(if (granted) R.string.onboarding_perm_granted else R.string.onboarding_perm_grant)
    }

    // =========================================================================
    // Step 2: Formats & Optics
    // =========================================================================
    private fun setupStep2FormatsAndOptics() {
        val current = settingsRepository.current()
        val initialJpg = current.saveJpg
        val initialRaw = if (settingsRepository.hasExplicitKey(PreferenceKeys.SAVE_RAW)) current.saveRaw else true

        binding.cbSaveJpg.setOnCheckedChangeListener(null)
        binding.cbSaveRaw.setOnCheckedChangeListener(null)
        binding.cbSaveJpg.isChecked = initialJpg
        binding.cbSaveRaw.isChecked = initialRaw
        renderFormatCards(initialJpg, initialRaw)

        fun onUserFormatChanged() {
            var isJpg = binding.cbSaveJpg.isChecked
            val isRaw = binding.cbSaveRaw.isChecked
            if (!isJpg && !isRaw) {
                isJpg = true
                binding.cbSaveJpg.setOnCheckedChangeListener(null)
                binding.cbSaveJpg.isChecked = true
                binding.cbSaveJpg.setOnCheckedChangeListener { _, _ -> onUserFormatChanged() }
            }
            settingsRepository.edit {
                this[PreferenceKeys.SAVE_JPG] = isJpg
                this[PreferenceKeys.SAVE_RAW] = isRaw
            }
            renderFormatCards(isJpg, isRaw)
        }

        binding.tileFormatJpg.setOnClickListener {
            if (binding.cbSaveJpg.isChecked && !binding.cbSaveRaw.isChecked) return@setOnClickListener
            binding.cbSaveJpg.toggle()
        }
        binding.cbSaveJpg.setOnCheckedChangeListener { _, _ -> onUserFormatChanged() }

        binding.tileFormatRaw.setOnClickListener {
            if (binding.cbSaveRaw.isChecked && !binding.cbSaveJpg.isChecked) return@setOnClickListener
            binding.cbSaveRaw.toggle()
        }
        binding.cbSaveRaw.setOnCheckedChangeListener { _, _ -> onUserFormatChanged() }

        updateFocalTiles(current.defaultFocal1xName ?: "24mm")
        listOf("24mm" to binding.tileFocal24, "28mm" to binding.tileFocal28, "35mm" to binding.tileFocal35).forEach { (focal, tile) ->
            tile.setOnClickListener {
                settingsRepository.setDefaultFocal1x(focal)
                updateFocalTiles(focal)
            }
        }

        binding.switchMotionPhoto.bindSilently(current.motionPhotoEnabled) {
            settingsRepository.setMotionPhotoEnabled(it)
        }
        binding.switchSaveLocation.bindSilently(
            LocationHelper.hasPermission(context) && current.saveLocationEnabled
        ) { onSaveLocationToggled(it) }
    }

    private fun onSaveLocationToggled(isChecked: Boolean) {
        if (!isChecked) {
            settingsRepository.setSaveLocationEnabled(false)
        } else if (LocationHelper.hasPermission(context)) {
            settingsRepository.setSaveLocationEnabled(true)
        } else {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            callbacks.requestLocationPermissions(perms.toTypedArray())
        }
    }

    private fun renderFormatCards(isJpg: Boolean, isRaw: Boolean) {
        updateCardRoleSquircle(binding.tileFormatJpg, isJpg, MaterialR.attr.colorSecondaryContainer, MaterialR.attr.colorSecondary)
        updatePrimarySquircle(binding.tileFormatRaw, isRaw)
        binding.tvFormatExplain.text = context.getString(
            when {
                isJpg && isRaw -> R.string.onboarding_format_dual_desc
                isJpg -> R.string.onboarding_format_jpg_desc
                else -> R.string.onboarding_format_raw_desc
            }
        )
    }

    private fun updateFocalTiles(selectedFocal: String) {
        updatePrimarySquircle(binding.tileFocal24, selectedFocal == "24mm")
        updatePrimarySquircle(binding.tileFocal28, selectedFocal == "28mm")
        updatePrimarySquircle(binding.tileFocal35, selectedFocal == "35mm")
    }

    // =========================================================================
    // Step 3: Recipe Pairing & 3D LUT
    // =========================================================================
    private fun setupStep3RecipePairing() {
        val logButtons = mapOf(
            "F-Log2" to binding.btnLogFlog2,
            "Apple Log" to binding.btnLogApple,
            "S-Log3" to binding.btnLogSlog3,
            "Arri LogC3" to binding.btnLogArri,
            "Canon Log 2" to binding.btnLogClog,
            "Rec.709" to binding.btnLogRec709
        )

        fun updateLogButtonsState() {
            val colorPrimary = m3Color(android.R.attr.colorPrimary, Color.WHITE)
            val colorPrimaryContainer = m3Color(MaterialR.attr.colorPrimaryContainer, Color.DKGRAY)
            val colorOnPrimaryContainer = m3Color(MaterialR.attr.colorOnPrimaryContainer, Color.WHITE)
            val colorOnSurface = m3Color(MaterialR.attr.colorOnSurface, Color.BLACK)
            val colorSurface = m3Color(MaterialR.attr.colorSurface, Color.WHITE)
            val colorOutlineVariant = m3Color(MaterialR.attr.colorOutlineVariant, Color.GRAY)
            val density = context.resources.displayMetrics.density

            logButtons.forEach { (log, btn) ->
                val isSelected = log == selectedPairingLog
                btn.cornerRadius = ((if (isSelected) 10f else 14f) * density).toInt()
                btn.strokeWidth = ((if (isSelected) 1.5f else 1f) * density).toInt()
                btn.strokeColor = ColorStateList.valueOf(if (isSelected) colorPrimary else colorOutlineVariant)
                btn.backgroundTintList = ColorStateList.valueOf(if (isSelected) colorPrimaryContainer else colorSurface)
                btn.setTextColor(if (isSelected) colorOnPrimaryContainer else colorOnSurface)
            }
            binding.btnImportLut.text = context.getString(R.string.onboarding_recipe_import_action, selectedPairingLog)
        }

        logButtons.forEach { (log, btn) ->
            btn.setOnClickListener {
                selectedPairingLog = log
                updateLogButtonsState()
            }
        }
        binding.btnImportLut.setOnClickListener { callbacks.launchLutPicker(selectedPairingLog) }
        binding.switchLivePreview.bindSilently(settingsRepository.current().enableLutPreview) {
            settingsRepository.setLivePreview(it)
        }
        updateLogButtonsState()
    }

    // =========================================================================
    // Step 4: Viewfinder UI Tier
    // =========================================================================
    private fun setupStep4ViewfinderTier() {
        val current = settingsRepository.current()
        val initialTier = resolveInitialUiTier(current)
        renderUiTierSelection(initialTier)

        binding.tileTierPs.setOnClickListener { selectAndPersistUiTier("PS") }
        binding.tileTierFilm.setOnClickListener { selectAndPersistUiTier("FILM") }
        binding.tileTierPro.setOnClickListener { selectAndPersistUiTier("PRO") }

        fun updateShutterHoldText() {
            binding.tvShutterHoldValue.text = context.getString(
                when (settingsRepository.current().shutterLongPressAction) {
                    PreferenceKeys.SHUTTER_LONG_PRESS_RAW_VIDEO -> R.string.pref_shutter_action_raw_video
                    PreferenceKeys.SHUTTER_LONG_PRESS_DISABLED -> R.string.pref_option_disabled
                    else -> R.string.pref_shutter_action_mp4
                }
            )
        }

        binding.tileShutterHoldAction.setOnClickListener {
            val nextAction = when (settingsRepository.current().shutterLongPressAction) {
                PreferenceKeys.SHUTTER_LONG_PRESS_MP4 -> PreferenceKeys.SHUTTER_LONG_PRESS_RAW_VIDEO
                PreferenceKeys.SHUTTER_LONG_PRESS_RAW_VIDEO -> PreferenceKeys.SHUTTER_LONG_PRESS_DISABLED
                else -> PreferenceKeys.SHUTTER_LONG_PRESS_MP4
            }
            settingsRepository.setShutterLongPressAction(nextAction)
            updateShutterHoldText()
        }

        updateShutterHoldText()
    }

    private fun resolveInitialUiTier(current: DarkbagPreferences): String {
        if (settingsRepository.hasExplicitKey(PreferenceKeys.UI_TIER)) {
            return when (CameraUiTier.fromKey(current.uiTier)) {
                CameraUiTier.POINT_AND_SHOOT -> "PS"
                CameraUiTier.PRO -> "PRO"
                else -> "FILM"
            }
        }
        return when {
            current.enableManualControls -> "PRO"
            !current.showLutSwitcher && !current.showHdrPlusSwitch -> "PS"
            else -> "FILM"
        }
    }

    private fun selectAndPersistUiTier(tier: String) {
        renderUiTierSelection(tier)
        val isPro = tier == "PRO"
        val showFilm = tier != "PS"
        settingsRepository.edit {
            this[PreferenceKeys.UI_TIER] = tier
            this[PreferenceKeys.MANUAL_CONTROLS] = isPro
            this[PreferenceKeys.EXP_FOCUS_PEAKING] = isPro
            this[PreferenceKeys.SHOW_LUT_SWITCHER] = showFilm
            this[PreferenceKeys.SHOW_HDR_PLUS_SWITCH] = showFilm
            this[PreferenceKeys.SHOW_MODE_SWITCH_BUTTON] = showFilm
        }
    }

    private fun renderUiTierSelection(tier: String) {
        selectedUiTier = tier
        updatePrimarySquircle(binding.tileTierPs, tier == "PS")
        updatePrimarySquircle(binding.tileTierFilm, tier == "FILM")
        updatePrimarySquircle(binding.tileTierPro, tier == "PRO")

        binding.tvTierDesc.text = context.getString(
            when (tier) {
                "PS" -> R.string.onboarding_tier_ps_desc
                "PRO" -> R.string.onboarding_tier_pro_desc
                else -> R.string.onboarding_tier_film_desc
            }
        )
    }

    // =========================================================================
    // Navigation & Primary Action Dock
    // =========================================================================
    private fun setupBottomActionDock() {
        binding.btnPrimaryCta.setOnClickListener {
            when (currentStep) {
                1 -> {
                    val camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (!camGranted) {
                        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        callbacks.requestBatchStartupPermissions(perms.toTypedArray())
                    } else {
                        goToStep(2)
                    }
                }
                2 -> goToStep(3)
                3 -> goToStep(4)
                4 -> callbacks.onFinishOnboarding()
            }
        }
    }

    fun goToStep(step: Int) {
        currentStep = step
        listOf(binding.containerStep1, binding.containerStep2, binding.containerStep3, binding.containerStep4)
            .forEachIndexed { idx, container -> container.visibility = if (step == idx + 1) View.VISIBLE else View.GONE }

        binding.btnHeaderBack.visibility = if (step > 1) View.VISIBLE else View.INVISIBLE
        binding.tvHeaderStep.text = context.getString(R.string.onboarding_step_format, step, 4)

        val colorPrimary = m3Color(android.R.attr.colorPrimary, Color.WHITE)
        val colorSurfaceContainerHigh = m3Color(MaterialR.attr.colorSurfaceContainerHigh, Color.DKGRAY)
        listOf(binding.progStep1, binding.progStep2, binding.progStep3, binding.progStep4)
            .forEachIndexed { idx, bar ->
                bar.backgroundTintList = ColorStateList.valueOf(if (step >= idx + 1) colorPrimary else colorSurfaceContainerHigh)
            }

        updatePrimaryCtaButton()
        binding.nestedScrollView.smoothScrollTo(0, 0)
    }

    fun updatePrimaryCtaButton() {
        val camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        binding.btnPrimaryCta.text = context.getString(
            when (currentStep) {
                1 -> if (camGranted) R.string.onboarding_btn_next else R.string.onboarding_btn_grant_perms
                2, 3 -> R.string.onboarding_btn_next
                else -> R.string.onboarding_btn_start
            }
        )
    }

    private fun m3Color(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(binding.root, attr, fallback)

    private fun updatePrimarySquircle(card: MaterialCardView, isSelected: Boolean) {
        updateCardRoleSquircle(card, isSelected, MaterialR.attr.colorPrimaryContainer, android.R.attr.colorPrimary)
    }

    private fun updateCardRoleSquircle(
        card: MaterialCardView,
        isSelected: Boolean,
        activeBgAttr: Int,
        activeStrokeAttr: Int
    ) {
        updateCardSquircle(
            card = card,
            isSelected = isSelected,
            activeBg = m3Color(activeBgAttr, Color.DKGRAY),
            activeStroke = m3Color(activeStrokeAttr, Color.WHITE),
            inactiveBg = m3Color(MaterialR.attr.colorSurface, Color.WHITE),
            inactiveStroke = m3Color(MaterialR.attr.colorOutlineVariant, Color.GRAY)
        )
    }

    private fun BentoSwitchTile.bindSilently(checked: Boolean, onChanged: (Boolean) -> Unit) {
        setOnCheckedChangeListener(null as CompoundButton.OnCheckedChangeListener?)
        isChecked = checked
        setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, isNowChecked -> onChanged(isNowChecked) })
    }

    private fun updateCardSquircle(
        card: MaterialCardView,
        isSelected: Boolean,
        activeBg: Int,
        activeStroke: Int,
        inactiveBg: Int,
        inactiveStroke: Int
    ) {
        val density = context.resources.displayMetrics.density
        card.radius = (if (isSelected) 10f else 14f) * density
        card.strokeWidth = ((if (isSelected) 2f else 1f) * density).toInt()
        card.setCardBackgroundColor(ColorStateList.valueOf(if (isSelected) activeBg else inactiveBg))
        card.strokeColor = if (isSelected) activeStroke else inactiveStroke
    }
}

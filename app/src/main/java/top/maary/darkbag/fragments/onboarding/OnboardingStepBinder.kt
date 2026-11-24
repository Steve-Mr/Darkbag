package top.maary.darkbag.fragments.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentPermissionsBinding
import top.maary.darkbag.repository.DarkbagPreferences
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.bento.BentoSwitchTile
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.viewmodel.CameraUiTier

/**
 * Coordinates the dynamic Bento Matrix onboarding UI inside [PermissionsFragment]:
 * - Step 1: Startup module cards, Camera/Playground enable switches & tiered permission tiles
 * - Step 2: Output formats (JPG/DNG), custom storage folders & optical defaults (skipped if Camera disabled)
 * - Step 3: Base Log ("None" supported) & 3D LUT recipe pairing + viewfinder preview discrepancy hint
 * - Step 4: Viewfinder UI tier (P&S / FILM / PRO / CUSTOM with 1:1 live preview) & shutter hold action (skipped if Camera disabled)
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
        fun launchJpgStoragePicker()
        fun launchRawStoragePicker()
        fun openDeckCustomizer(onUpdated: () -> Unit)
        fun onFinishOnboarding()
    }

    var currentStep: Int = 1
        private set
    var selectedPairingLog: String = "None"
        private set
    var selectedUiTier: String = "FILM"
        private set

    private var previewDeckBinding: CameraUiContainerBinding? = null

    private val context: Context
        get() = binding.root.context

    fun setupAllSteps() {
        setupHeaderControls()
        setupStep1StartupAndPermissions()
        setupStep2FormatsAndOptics()
        setupStep3RecipePairing()
        setupStep4ViewfinderTier()
        setupBottomActionDock()
        syncStepChromeForCurrentStep()
    }

    fun refreshStepsAfterHydration() {
        setupStep1StartupAndPermissions()
        setupStep2FormatsAndOptics()
        setupStep3RecipePairing()
        setupStep4ViewfinderTier()
        syncStepChromeForCurrentStep()
    }

    fun setSaveLocationChecked(checked: Boolean) {
        binding.switchSaveLocation.bindSilently(checked) { onSaveLocationToggled(it) }
    }

    fun showImportedRecipeResult(importedFileName: String, pairingLog: String) {
        binding.layoutPairingResult.visibility = View.VISIBLE
        binding.tvPairingDesc.text = context.getString(R.string.onboarding_recipe_imported_count, importedFileName, pairingLog)
    }

    fun refreshStoragePaths() {
        val current = settingsRepository.current()
        val defaultPath = context.getString(R.string.pref_storage_default_path)
        binding.tvJpgPath.text = current.jpgStorageUriName ?: defaultPath
        binding.tvRawPath.text = current.rawStorageUriName ?: defaultPath
    }

    fun getActiveSteps(): List<Int> {
        return if (settingsRepository.current().enableCamera) {
            listOf(1, 2, 3, 4)
        } else {
            listOf(1, 3)
        }
    }

    private fun setupHeaderControls() {
        binding.btnHeaderBack.setOnClickListener {
            goToPreviousStep()
        }
        binding.btnHeaderSkip.setOnClickListener {
            val current = settingsRepository.current()
            val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (current.enableCamera && !cameraGranted && current.defaultStartupPage == PreferenceKeys.STARTUP_CAMERA) {
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
        val current = settingsRepository.current()
        renderStartupState(current.defaultStartupPage, current.enableCamera, current.enablePlayground)

        binding.cardStartupCamera.setOnClickListener {
            val prefs = settingsRepository.current()
            if (!prefs.enableCamera) return@setOnClickListener
            settingsRepository.setDefaultStartupPage(PreferenceKeys.STARTUP_CAMERA)
            renderStartupState(PreferenceKeys.STARTUP_CAMERA, prefs.enableCamera, prefs.enablePlayground)
        }
        binding.cardStartupPlayground.setOnClickListener {
            val prefs = settingsRepository.current()
            if (!prefs.enablePlayground) return@setOnClickListener
            settingsRepository.setDefaultStartupPage(PreferenceKeys.STARTUP_PLAYGROUND)
            renderStartupState(PreferenceKeys.STARTUP_PLAYGROUND, prefs.enableCamera, prefs.enablePlayground)
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

    private fun onEnableCameraToggled(isChecked: Boolean) {
        val prefs = settingsRepository.current()
        if (!isChecked && !prefs.enablePlayground) {
            renderStartupState(prefs.defaultStartupPage, enableCamera = true, enablePlayground = false)
            return
        }
        val targetStartup = if (!isChecked && prefs.defaultStartupPage == PreferenceKeys.STARTUP_CAMERA) {
            PreferenceKeys.STARTUP_PLAYGROUND
        } else {
            prefs.defaultStartupPage
        }
        settingsRepository.edit {
            this[PreferenceKeys.ENABLE_CAMERA] = isChecked
            this[PreferenceKeys.DEFAULT_STARTUP] = targetStartup
        }
        renderStartupState(targetStartup, isChecked, prefs.enablePlayground)
        updatePermissionTilesState()
        updateModuleDependentSections(isChecked)
        syncStepChromeForCurrentStep()
    }

    private fun onEnablePlaygroundToggled(isChecked: Boolean) {
        val prefs = settingsRepository.current()
        if (!isChecked && !prefs.enableCamera) {
            renderStartupState(prefs.defaultStartupPage, enableCamera = false, enablePlayground = true)
            return
        }
        val targetStartup = if (!isChecked && prefs.defaultStartupPage == PreferenceKeys.STARTUP_PLAYGROUND) {
            PreferenceKeys.STARTUP_CAMERA
        } else {
            prefs.defaultStartupPage
        }
        settingsRepository.edit {
            this[PreferenceKeys.ENABLE_PLAYGROUND] = isChecked
            this[PreferenceKeys.DEFAULT_STARTUP] = targetStartup
        }
        renderStartupState(targetStartup, prefs.enableCamera, isChecked)
    }

    private fun renderStartupState(selectedMode: String, enableCamera: Boolean, enablePlayground: Boolean) {
        updateStartupModuleCards(selectedMode, enableCamera, enablePlayground)

        binding.switchEnableCamera.bindSilently(enableCamera) { onEnableCameraToggled(it) }
        binding.switchEnablePlayground.bindSilently(enablePlayground) { onEnablePlaygroundToggled(it) }

        binding.switchEnableCamera.isEnabled = !(enableCamera && !enablePlayground)
        binding.switchEnablePlayground.isEnabled = !(!enableCamera && enablePlayground)
    }

    private fun updateStartupModuleCards(selectedMode: String, enableCamera: Boolean, enablePlayground: Boolean) {
        val colorOnSurface = m3Color(MaterialR.attr.colorOnSurface, Color.WHITE)
        val colorOnPrimary = m3Color(MaterialR.attr.colorOnPrimaryContainer, Color.WHITE)
        val colorOnTertiary = m3Color(MaterialR.attr.colorOnTertiaryContainer, Color.WHITE)

        val isCamera = selectedMode == PreferenceKeys.STARTUP_CAMERA && enableCamera
        updatePrimarySquircle(binding.cardStartupCamera, isCamera)
        binding.txtStartupCameraTitle.setTextColor(if (isCamera) colorOnPrimary else colorOnSurface)
        binding.txtStartupCameraDesc.setTextColor(if (isCamera) colorOnPrimary else colorOnSurface)
        binding.cardStartupCamera.isEnabled = enableCamera
        binding.cardStartupCamera.alpha = if (enableCamera) 1.0f else 0.45f

        val isPlayground = !isCamera && enablePlayground
        updateCardRoleSquircle(binding.cardStartupPlayground, isPlayground, MaterialR.attr.colorTertiaryContainer, MaterialR.attr.colorTertiary)
        binding.txtStartupPlaygroundTitle.setTextColor(if (isPlayground) colorOnTertiary else colorOnSurface)
        binding.txtStartupPlaygroundDesc.setTextColor(if (isPlayground) colorOnTertiary else colorOnSurface)
        binding.cardStartupPlayground.isEnabled = enablePlayground
        binding.cardStartupPlayground.alpha = if (enablePlayground) 1.0f else 0.45f
    }

    private fun updateModuleDependentSections(enableCamera: Boolean) {
        binding.cardTrayLivePreview.visibility = if (enableCamera) View.VISIBLE else View.GONE
    }

    fun updatePermissionTilesState() {
        val enableCamera = settingsRepository.current().enableCamera
        val camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (enableCamera) {
            binding.tilePermCamera.visibility = View.VISIBLE
            binding.tilePermAudio.visibility = View.VISIBLE
            binding.cardPermPlaygroundOnlyHint.visibility = View.GONE
            updatePermTile(binding.tilePermCamera, binding.btnGrantCamera, camGranted)
            updatePermTile(binding.tilePermAudio, binding.btnGrantAudio, audioGranted)
        } else {
            binding.tilePermCamera.visibility = View.GONE
            binding.tilePermAudio.visibility = View.GONE
            binding.cardPermPlaygroundOnlyHint.visibility = View.VISIBLE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.tilePermNotifications.visibility = View.VISIBLE
            updatePermTile(binding.tilePermNotifications, binding.btnGrantNotifications, notifGranted)
        } else {
            binding.tilePermNotifications.visibility = View.GONE
        }
    }

    private fun updatePermTile(tile: MaterialCardView, btn: MaterialButton, granted: Boolean) {
        updatePrimarySquircle(tile, granted)
        btn.isEnabled = !granted
        btn.text = context.getString(if (granted) R.string.onboarding_perm_granted else R.string.onboarding_perm_grant)
    }

    // =========================================================================
    // Step 2: Formats, Storage & Optics
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
        refreshStoragePaths()

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

        binding.layoutJpgStorage.setOnClickListener { callbacks.launchJpgStoragePicker() }
        binding.layoutJpgStorage.setOnLongClickListener {
            if (settingsRepository.current().jpgStorageUri != null) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dialog_reset_storage_title)
                    .setMessage(R.string.dialog_reset_storage_jpg_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.btn_reset) { _, _ ->
                        settingsRepository.setStorageUri(isJpg = true, uri = null, name = null)
                        refreshStoragePaths()
                        Toast.makeText(context, R.string.toast_storage_reset_default, Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } else {
                Toast.makeText(context, R.string.toast_storage_already_default, Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.layoutRawStorage.setOnClickListener { callbacks.launchRawStoragePicker() }
        binding.layoutRawStorage.setOnLongClickListener {
            if (settingsRepository.current().rawStorageUri != null) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dialog_reset_storage_title)
                    .setMessage(R.string.dialog_reset_storage_raw_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.btn_reset) { _, _ ->
                        settingsRepository.setStorageUri(isJpg = false, uri = null, name = null)
                        refreshStoragePaths()
                        Toast.makeText(context, R.string.toast_storage_reset_default, Toast.LENGTH_SHORT).show()
                    }
                    .show()
            } else {
                Toast.makeText(context, R.string.toast_storage_already_default, Toast.LENGTH_SHORT).show()
            }
            true
        }

        updateFocalTiles(current.defaultFocal1xName ?: "24mm")
        listOf("24mm" to binding.tileFocal24, "28mm" to binding.tileFocal28, "35mm" to binding.tileFocal35).forEach { (focal, tile) ->
            tile.setOnClickListener {
                settingsRepository.setDefaultFocal1x(focal)
                updateFocalTiles(focal)
            }
        }

        fun updateMotionPhotoAudioVisibility(motionEnabled: Boolean) {
            binding.switchMotionPhotoAudio.visibility = if (motionEnabled) View.VISIBLE else View.GONE
            binding.cardMotionPhotoAudioHint.visibility = if (motionEnabled) View.VISIBLE else View.GONE
        }

        binding.switchMotionPhoto.bindSilently(current.motionPhotoEnabled) { isChecked ->
            settingsRepository.setMotionPhotoEnabled(isChecked)
            updateMotionPhotoAudioVisibility(isChecked)
        }

        binding.switchMotionPhotoAudio.bindSilently(current.motionPhotoAudioEnabled) { isChecked ->
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    callbacks.requestAudioPermission()
                }
            }
            settingsRepository.setMotionPhotoAudioEnabled(isChecked)
        }
        updateMotionPhotoAudioVisibility(current.motionPhotoEnabled)

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
        binding.layoutJpgStorage.visibility = if (isJpg) View.VISIBLE else View.GONE
        binding.layoutRawStorage.visibility = if (isRaw) View.VISIBLE else View.GONE
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
        val current = settingsRepository.current()
        val logButtons = mapOf(
            "None" to binding.btnLogNone,
            "F-Log2" to binding.btnLogFlog2,
            "Apple Log" to binding.btnLogApple,
            "S-Log3" to binding.btnLogSlog3,
            "Arri LogC3" to binding.btnLogArri,
            "Canon Log 2" to binding.btnLogClog,
            "Rec.709" to binding.btnLogRec709
        )

        selectedPairingLog = when {
            current.targetLog == "None" && current.activeLut == "None" -> "None"
            logButtons.containsKey(current.targetLog) -> current.targetLog
            else -> "F-Log2"
        }

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

            if (selectedPairingLog == "None") {
                binding.btnImportLut.visibility = View.GONE
                binding.layoutPairingResult.visibility = View.GONE
                binding.tvRecipeSkipHint.setText(R.string.onboarding_recipe_none_hint)
            } else {
                binding.btnImportLut.visibility = View.VISIBLE
                binding.btnImportLut.text = context.getString(R.string.onboarding_recipe_import_action, selectedPairingLog)
                binding.tvRecipeSkipHint.setText(R.string.onboarding_recipe_skip_hint)
            }
        }

        logButtons.forEach { (log, btn) ->
            btn.setOnClickListener {
                selectedPairingLog = log
                if (log == "None") {
                    settingsRepository.edit {
                        this[PreferenceKeys.TARGET_LOG] = "None"
                        this[PreferenceKeys.ACTIVE_LUT] = "None"
                        remove(PreferenceKeys.LUT_URI)
                    }
                }
                updateLogButtonsState()
            }
        }

        binding.btnImportLut.setOnClickListener {
            if (selectedPairingLog != "None") {
                callbacks.launchLutPicker(selectedPairingLog)
            }
        }
        binding.switchLivePreview.bindSilently(current.enableLutPreview) {
            settingsRepository.setLivePreview(it)
        }
        updateModuleDependentSections(current.enableCamera)
        updateLogButtonsState()

        if (selectedPairingLog != "None" && current.activeLut != "None") {
            showImportedRecipeResult(current.activeLut, selectedPairingLog)
        }
    }

    // =========================================================================
    // Step 4: Viewfinder UI Tier & Authentic Live Preview
    // =========================================================================
    private fun setupStep4ViewfinderTier() {
        val current = settingsRepository.current()
        if (previewDeckBinding == null) {
            previewDeckBinding = CameraUiContainerBinding.inflate(
                LayoutInflater.from(context),
                binding.previewDeckHost
            )
        }

        val initialTier = resolveInitialUiTier(current)
        renderUiTierSelection(initialTier)
        renderDeckPreview(animate = false)

        binding.tileTierPs.setOnClickListener { selectAndPersistPresetUiTier(CameraUiTier.POINT_AND_SHOOT.key) }
        binding.tileTierFilm.setOnClickListener { selectAndPersistPresetUiTier(CameraUiTier.FILM.key) }
        binding.tileTierPro.setOnClickListener { selectAndPersistPresetUiTier(CameraUiTier.PRO.key) }
        binding.tileTierCustom.setOnClickListener { selectAndPersistCustomTier() }

        binding.btnCustomizeDeck.setOnClickListener {
            callbacks.openDeckCustomizer {
                renderDeckPreview(animate = true)
            }
        }

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

        fun updateViewerDescription(useInternal: Boolean) {
            binding.tvViewerDesc.text = context.getString(
                if (useInternal) {
                    R.string.onboarding_viewer_internal_desc
                } else {
                    R.string.onboarding_viewer_external_desc
                }
            )
        }

        val useInternalViewer = current.useInternalViewer
        binding.switchUseInternalViewer.bindSilently(useInternalViewer) { isChecked ->
            settingsRepository.setUseInternalViewer(isChecked)
            updateViewerDescription(isChecked)
        }
        updateViewerDescription(useInternalViewer)
        updateShutterHoldText()
    }

    private fun resolveInitialUiTier(current: DarkbagPreferences): String {
        if (settingsRepository.hasExplicitKey(PreferenceKeys.UI_TIER)) {
            return when (CameraUiTier.fromKey(current.uiTier)) {
                CameraUiTier.POINT_AND_SHOOT -> CameraUiTier.POINT_AND_SHOOT.key
                CameraUiTier.PRO -> CameraUiTier.PRO.key
                CameraUiTier.CUSTOM -> CameraUiTier.CUSTOM.key
                CameraUiTier.FILM -> CameraUiTier.FILM.key
            }
        }
        return when {
            current.enableManualControls -> CameraUiTier.PRO.key
            !current.showLutSwitcher && !current.showHdrPlusSwitch -> CameraUiTier.POINT_AND_SHOOT.key
            else -> CameraUiTier.FILM.key
        }
    }

    private fun selectAndPersistPresetUiTier(tier: String) {
        val isPro = tier == CameraUiTier.PRO.key
        val showFilm = tier != CameraUiTier.POINT_AND_SHOOT.key
        settingsRepository.edit {
            this[PreferenceKeys.UI_TIER] = tier
            this[PreferenceKeys.MANUAL_CONTROLS] = isPro
            this[PreferenceKeys.EXP_FOCUS_PEAKING] = isPro
            this[PreferenceKeys.SHOW_LUT_SWITCHER] = showFilm
            this[PreferenceKeys.SHOW_HDR_PLUS_SWITCH] = showFilm
            this[PreferenceKeys.SHOW_MODE_SWITCH_BUTTON] = showFilm
        }
        renderUiTierSelection(tier)
        renderDeckPreview(animate = true)
    }

    private fun selectAndPersistCustomTier() {
        settingsRepository.edit {
            this[PreferenceKeys.UI_TIER] = CameraUiTier.CUSTOM.key
        }
        renderUiTierSelection(CameraUiTier.CUSTOM.key)
        renderDeckPreview(animate = true)
    }

    private fun renderUiTierSelection(tier: String) {
        selectedUiTier = tier
        updatePrimarySquircle(binding.tileTierPs, tier == CameraUiTier.POINT_AND_SHOOT.key)
        updatePrimarySquircle(binding.tileTierFilm, tier == CameraUiTier.FILM.key)
        updatePrimarySquircle(binding.tileTierPro, tier == CameraUiTier.PRO.key)
        updatePrimarySquircle(binding.tileTierCustom, tier == CameraUiTier.CUSTOM.key)

        binding.btnCustomizeDeck.visibility = if (tier == CameraUiTier.CUSTOM.key) View.VISIBLE else View.GONE
        binding.tvTierDesc.text = context.getString(
            when (tier) {
                CameraUiTier.POINT_AND_SHOOT.key -> R.string.onboarding_tier_ps_desc
                CameraUiTier.PRO.key -> R.string.onboarding_tier_pro_desc
                CameraUiTier.CUSTOM.key -> R.string.onboarding_tier_custom_desc
                else -> R.string.onboarding_tier_film_desc
            }
        )
    }

    private fun renderDeckPreview(animate: Boolean) {
        val deckBinding = previewDeckBinding ?: return
        if (animate && binding.containerPreviewContent.isLaidOut) {
            val transition = TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(ChangeBounds())
                addTransition(Fade())
                duration = 180
            }
            TransitionManager.beginDelayedTransition(binding.containerPreviewContent, transition)
        }

        val prefs = settingsRepository.current()
        val tier = CameraUiTier.fromKey(selectedUiTier)
        val spec = CameraBentoDeckView.resolveVisibilitySpec(prefs, tier)
        CameraBentoDeckView.bindAuthenticPreview(
            binding = deckBinding,
            context = context,
            prefs = prefs,
            spec = spec
        )
        binding.previewFocusPeakingBadge.visibility = if (spec.enableFocusPeaking) View.VISIBLE else View.GONE
    }

    // =========================================================================
    // Navigation & Primary Action Dock
    // =========================================================================
    private fun setupBottomActionDock() {
        binding.btnPrimaryCta.setOnClickListener {
            val activeSteps = getActiveSteps()
            val isLastStep = currentStep == activeSteps.last()
            when {
                currentStep == 1 -> {
                    val enableCamera = settingsRepository.current().enableCamera
                    val camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (enableCamera && !camGranted) {
                        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        callbacks.requestBatchStartupPermissions(perms.toTypedArray())
                    } else {
                        goToNextStep()
                    }
                }
                isLastStep -> callbacks.onFinishOnboarding()
                else -> goToNextStep()
            }
        }
    }

    fun goToNextStep() {
        val activeSteps = getActiveSteps()
        val currentIdx = activeSteps.indexOf(currentStep)
        if (currentIdx in 0 until activeSteps.lastIndex) {
            goToStep(activeSteps[currentIdx + 1])
        } else if (currentIdx == activeSteps.lastIndex) {
            callbacks.onFinishOnboarding()
        }
    }

    fun goToPreviousStep() {
        val activeSteps = getActiveSteps()
        val currentIdx = activeSteps.indexOf(currentStep)
        if (currentIdx > 0) {
            goToStep(activeSteps[currentIdx - 1])
        }
    }

    fun goToStep(step: Int) {
        val activeSteps = getActiveSteps()
        currentStep = if (activeSteps.contains(step)) step else activeSteps.first()
        listOf(binding.containerStep1, binding.containerStep2, binding.containerStep3, binding.containerStep4)
            .forEachIndexed { idx, container -> container.visibility = if (currentStep == idx + 1) View.VISIBLE else View.GONE }

        syncStepChromeForCurrentStep()
        binding.nestedScrollView.smoothScrollTo(0, 0)
    }

    private fun syncStepChromeForCurrentStep() {
        val activeSteps = getActiveSteps()
        val activeIndex = activeSteps.indexOf(currentStep).coerceAtLeast(0)
        val totalSteps = activeSteps.size

        binding.btnHeaderBack.visibility = if (activeIndex > 0) View.VISIBLE else View.INVISIBLE
        binding.tvHeaderStep.text = context.getString(R.string.onboarding_step_format, activeIndex + 1, totalSteps)

        val colorPrimary = m3Color(android.R.attr.colorPrimary, Color.WHITE)
        val colorSurfaceContainerHigh = m3Color(MaterialR.attr.colorSurfaceContainerHigh, Color.DKGRAY)
        listOf(binding.progStep1, binding.progStep2, binding.progStep3, binding.progStep4)
            .forEachIndexed { idx, bar ->
                bar.visibility = if (idx < totalSteps) View.VISIBLE else View.GONE
                bar.backgroundTintList = ColorStateList.valueOf(if (idx <= activeIndex) colorPrimary else colorSurfaceContainerHigh)
            }

        updatePrimaryCtaButton()
    }

    fun updatePrimaryCtaButton() {
        val enableCamera = settingsRepository.current().enableCamera
        val camGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val activeSteps = getActiveSteps()
        val isLastStep = currentStep == activeSteps.last()

        binding.btnPrimaryCta.text = context.getString(
            when {
                currentStep == 1 && enableCamera && !camGranted -> R.string.onboarding_btn_grant_perms
                isLastStep -> R.string.onboarding_btn_start
                else -> R.string.onboarding_btn_next
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

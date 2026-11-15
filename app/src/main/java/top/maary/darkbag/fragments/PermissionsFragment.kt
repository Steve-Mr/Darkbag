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

package top.maary.darkbag.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import top.maary.darkbag.MainActivity
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentPermissionsBinding
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.LocationHelper
import top.maary.darkbag.utils.LutManager

/**
 * First-install Onboarding and Permissions controller for Darkbag.
 * Follows Bento Matrix & Material 3 Expressive guidelines (Invariant 5).
 *
 * For returning users, executes 0ms startup routing without UI overhead.
 * For new users or explicit reconfiguration, guides through startup module,
 * tiered permissions, capture formats, Log-LUT pairing, and viewfinder UI tier.
 */
class PermissionsFragment : Fragment() {

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var lutManager: LutManager

    private var currentStep = 1
    private var selectedPairingLog = "F-Log2"
    private var selectedUiTier = "FILM"
    private var forceOnboarding = false

    // Permission Launchers
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            updatePermissionTilesState()
            updatePrimaryCtaButton()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required for taking photos", Toast.LENGTH_LONG).show()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionTilesState()
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updatePermissionTilesState()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            settingsRepository.setSaveLocationEnabled(true)
            binding.switchSaveLocation.isChecked = true
        } else {
            settingsRepository.setSaveLocationEnabled(false)
            binding.switchSaveLocation.isChecked = false
            Toast.makeText(requireContext(), R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val batchPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: (
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
        updatePermissionTilesState()
        updatePrimaryCtaButton()

        if (cameraGranted) {
            goToStep(2)
        } else {
            Toast.makeText(requireContext(), "Camera permission is required to continue", Toast.LENGTH_SHORT).show()
        }
    }

    private val lutFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val importedFileName = lutManager.importLut(uri, selectedPairingLog)
        if (importedFileName != null) {
            settingsRepository.edit {
                this[PreferenceKeys.TARGET_LOG] = selectedPairingLog
                this[PreferenceKeys.ACTIVE_LUT] = importedFileName
                this[PreferenceKeys.SAVE_JPG] = true
            }

            binding.layoutPairingResult.visibility = View.VISIBLE
            binding.tvPairingDesc.text = getString(R.string.onboarding_recipe_imported_count, importedFileName, selectedPairingLog)
            Toast.makeText(requireContext(), "Imported $importedFileName paired with $selectedPairingLog", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Failed to import LUT (.cube)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository.getInstance(requireContext())
        lutManager = LutManager(requireContext())
        forceOnboarding = arguments?.getBoolean("force_onboarding", false) ?: false

        // Fast zero-delay routing for returning users when DataStore is already hydrated
        if (!forceOnboarding && settingsRepository.isHydrated) {
            val current = settingsRepository.current()
            val isPlaygroundStartup = current.defaultStartupPage == PreferenceKeys.STARTUP_PLAYGROUND
            if (current.onboardingCompleted && (isPlaygroundStartup || hasPermissions(requireContext()))) {
                routeStartup()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyEdgeToEdgeInsets()
        setupHeaderControls()
        setupStep1StartupAndPermissions()
        setupStep2FormatsAndOptics()
        setupStep3RecipePairing()
        setupStep4ViewfinderTier()
        setupBottomActionDock()

        goToStep(1)

        if (!settingsRepository.isHydrated) {
            viewLifecycleOwner.lifecycleScope.launch {
                settingsRepository.awaitHydrated()
                if (!isAdded) return@launch
                val current = settingsRepository.current()
                val isPlaygroundStartup = current.defaultStartupPage == PreferenceKeys.STARTUP_PLAYGROUND
                if (!forceOnboarding && current.onboardingCompleted && (isPlaygroundStartup || hasPermissions(requireContext()))) {
                    routeStartup()
                    return@launch
                }
                if (_binding != null) {
                    setupStep1StartupAndPermissions()
                    setupStep2FormatsAndOptics()
                    setupStep3RecipePairing()
                    setupStep4ViewfinderTier()
                }
            }
        }
    }

    private fun applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.cardOnboardingHeader) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            val defaultMarginTop = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            lp?.topMargin = defaultMarginTop + systemBars.top
            v.layoutParams = lp
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutBottomDock) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom)
            insets
        }
    }

    private fun setupHeaderControls() {
        binding.btnHeaderBack.setOnClickListener {
            if (currentStep > 1) {
                goToStep(currentStep - 1)
            }
        }

        binding.btnHeaderSkip.setOnClickListener {
            val cameraGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!cameraGranted && settingsRepository.current().defaultStartupPage == PreferenceKeys.STARTUP_CAMERA) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                finishOnboarding()
            }
        }
    }

    // =========================================================================
    // Step 1: Startup Module & Permissions
    // =========================================================================
    private fun setupStep1StartupAndPermissions() {
        val defaultStartup = settingsRepository.current().defaultStartupPage
        updateStartupModuleCards(defaultStartup)

        binding.cardStartupCamera.setOnClickListener {
            settingsRepository.setDefaultStartupPage(PreferenceKeys.STARTUP_CAMERA)
            updateStartupModuleCards(PreferenceKeys.STARTUP_CAMERA)
        }

        binding.cardStartupPlayground.setOnClickListener {
            settingsRepository.setDefaultStartupPage(PreferenceKeys.STARTUP_PLAYGROUND)
            updateStartupModuleCards(PreferenceKeys.STARTUP_PLAYGROUND)
        }

        binding.tilePermCamera.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        binding.btnGrantCamera.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.tilePermNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.btnGrantNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.tilePermAudio.setOnClickListener {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.btnGrantAudio.setOnClickListener {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        updatePermissionTilesState()
    }

    private fun updateStartupModuleCards(selectedMode: String) {
        val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
        val colorTertiary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiary, colorPrimary)
        val colorTertiaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorTertiaryContainer, colorPrimaryContainer)
        val colorSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
        val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)
        val colorOnSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE)
        val colorOnPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer, colorPrimary)
        val colorOnTertiaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnTertiaryContainer, colorTertiary)

        val isCamera = selectedMode == PreferenceKeys.STARTUP_CAMERA
        updateCardSquircle(
            card = binding.cardStartupCamera,
            isSelected = isCamera,
            activeBg = colorPrimaryContainer,
            activeStroke = colorPrimary,
            inactiveBg = colorSurface,
            inactiveStroke = colorOutlineVariant
        )
        binding.txtStartupCameraTitle.setTextColor(if (isCamera) colorOnPrimaryContainer else colorOnSurface)
        binding.txtStartupCameraDesc.setTextColor(if (isCamera) colorOnPrimaryContainer else colorOnSurface)

        updateCardSquircle(
            card = binding.cardStartupPlayground,
            isSelected = !isCamera,
            activeBg = colorTertiaryContainer,
            activeStroke = colorTertiary,
            inactiveBg = colorSurface,
            inactiveStroke = colorOutlineVariant
        )
        binding.txtStartupPlaygroundTitle.setTextColor(if (!isCamera) colorOnTertiaryContainer else colorOnSurface)
        binding.txtStartupPlaygroundDesc.setTextColor(if (!isCamera) colorOnTertiaryContainer else colorOnSurface)
    }

    private fun updatePermissionTilesState() {
        val ctx = context ?: return
        val camGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val audioGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
        val colorSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
        val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)

        // Camera Tile
        updateCardSquircle(binding.tilePermCamera, camGranted, colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)
        binding.btnGrantCamera.isEnabled = !camGranted
        binding.btnGrantCamera.text = if (camGranted) getString(R.string.onboarding_perm_granted) else getString(R.string.onboarding_perm_grant)

        // Notification Tile
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.tilePermNotifications.visibility = View.VISIBLE
            updateCardSquircle(binding.tilePermNotifications, notifGranted, colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)
            binding.btnGrantNotifications.isEnabled = !notifGranted
            binding.btnGrantNotifications.text = if (notifGranted) getString(R.string.onboarding_perm_granted) else getString(R.string.onboarding_perm_grant)
        } else {
            binding.tilePermNotifications.visibility = View.GONE
        }

        // Audio Tile
        updateCardSquircle(binding.tilePermAudio, audioGranted, colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)
        binding.btnGrantAudio.isEnabled = !audioGranted
        binding.btnGrantAudio.text = if (audioGranted) getString(R.string.onboarding_perm_granted) else getString(R.string.onboarding_perm_grant)
    }

    // =========================================================================
    // Step 2: Formats & Optics
    // =========================================================================
    private fun setupStep2FormatsAndOptics() {
        val current = settingsRepository.current()
        binding.cbSaveJpg.isChecked = current.saveJpg
        binding.cbSaveRaw.isChecked = if (settingsRepository.hasExplicitKey(PreferenceKeys.SAVE_RAW)) current.saveRaw else true

        fun updateFormatCards() {
            var isJpg = binding.cbSaveJpg.isChecked
            var isRaw = binding.cbSaveRaw.isChecked
            // Prevent both unchecked
            if (!isJpg && !isRaw) {
                isJpg = true
                binding.cbSaveJpg.isChecked = true
            }

            settingsRepository.edit {
                this[PreferenceKeys.SAVE_JPG] = isJpg
                this[PreferenceKeys.SAVE_RAW] = isRaw
            }

            val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
            val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
            val colorSecondary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondary, colorPrimary)
            val colorSecondaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondaryContainer, colorPrimaryContainer)
            val colorSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
            val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)

            updateCardSquircle(binding.tileFormatJpg, isJpg, colorSecondaryContainer, colorSecondary, colorSurface, colorOutlineVariant)
            updateCardSquircle(binding.tileFormatRaw, isRaw, colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)

            binding.tvFormatExplain.text = when {
                isJpg && isRaw -> getString(R.string.onboarding_format_dual_desc)
                isJpg && !isRaw -> getString(R.string.onboarding_format_jpg_desc)
                else -> getString(R.string.onboarding_format_raw_desc)
            }
        }

        binding.tileFormatJpg.setOnClickListener {
            if (binding.cbSaveJpg.isChecked && !binding.cbSaveRaw.isChecked) return@setOnClickListener
            binding.cbSaveJpg.toggle()
            updateFormatCards()
        }
        binding.cbSaveJpg.setOnCheckedChangeListener { _, _ -> updateFormatCards() }

        binding.tileFormatRaw.setOnClickListener {
            if (binding.cbSaveRaw.isChecked && !binding.cbSaveJpg.isChecked) return@setOnClickListener
            binding.cbSaveRaw.toggle()
            updateFormatCards()
        }
        binding.cbSaveRaw.setOnCheckedChangeListener { _, _ -> updateFormatCards() }

        updateFormatCards()

        // 1.0x Focal length base (24mm, 28mm, 35mm)
        val savedFocal = current.defaultFocal1xName ?: "24mm"
        updateFocalTiles(savedFocal)

        binding.tileFocal24.setOnClickListener {
            settingsRepository.setDefaultFocal1x("24mm")
            updateFocalTiles("24mm")
        }
        binding.tileFocal28.setOnClickListener {
            settingsRepository.setDefaultFocal1x("28mm")
            updateFocalTiles("28mm")
        }
        binding.tileFocal35.setOnClickListener {
            settingsRepository.setDefaultFocal1x("35mm")
            updateFocalTiles("35mm")
        }

        // Switches
        binding.switchMotionPhoto.isChecked = current.motionPhotoEnabled
        binding.switchMotionPhoto.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.setMotionPhotoEnabled(isChecked)
        }

        val hasLoc = LocationHelper.hasPermission(requireContext()) && current.saveLocationEnabled
        binding.switchSaveLocation.isChecked = hasLoc
        binding.switchSaveLocation.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (LocationHelper.hasPermission(requireContext())) {
                    settingsRepository.setSaveLocationEnabled(true)
                } else {
                    val perms = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                    locationPermissionLauncher.launch(perms.toTypedArray())
                }
            } else {
                settingsRepository.setSaveLocationEnabled(false)
            }
        }
    }

    private fun updateFocalTiles(selectedFocal: String) {
        val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
        val colorSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
        val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)

        updateCardSquircle(binding.tileFocal24, selectedFocal == "24mm", colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)
        updateCardSquircle(binding.tileFocal28, selectedFocal == "28mm", colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)
        updateCardSquircle(binding.tileFocal35, selectedFocal == "35mm", colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)
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
            val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
            val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
            val colorOnPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer, android.graphics.Color.WHITE)
            val colorOnSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK)
            val colorSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
            val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)
            val density = resources.displayMetrics.density

            logButtons.forEach { (log, btn) ->
                val isSelected = log == selectedPairingLog
                btn.cornerRadius = ((if (isSelected) 10f else 14f) * density).toInt()
                btn.strokeWidth = ((if (isSelected) 1.5f else 1f) * density).toInt()
                btn.strokeColor = ColorStateList.valueOf(if (isSelected) colorPrimary else colorOutlineVariant)
                btn.backgroundTintList = ColorStateList.valueOf(if (isSelected) colorPrimaryContainer else colorSurface)
                btn.setTextColor(if (isSelected) colorOnPrimaryContainer else colorOnSurface)
            }

            binding.btnImportLut.text = getString(R.string.onboarding_recipe_import_action, selectedPairingLog)
        }

        logButtons.forEach { (log, btn) ->
            btn.setOnClickListener {
                selectedPairingLog = log
                updateLogButtonsState()
            }
        }

        binding.btnImportLut.setOnClickListener {
            lutFilePicker.launch("*/*")
        }

        // Live preview switch
        binding.switchLivePreview.isChecked = settingsRepository.current().enableLutPreview
        binding.switchLivePreview.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.setLivePreview(isChecked)
        }

        updateLogButtonsState()
    }

    // =========================================================================
    // Step 4: Viewfinder UI Tier
    // =========================================================================
    private fun setupStep4ViewfinderTier() {
        fun updateUiTierSelection(tier: String) {
            selectedUiTier = tier
            val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
            val colorPrimaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer, android.graphics.Color.DKGRAY)
            val colorSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
            val colorOutlineVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.GRAY)

            updateCardSquircle(binding.tileTierPs, tier == "PS", colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)
            updateCardSquircle(binding.tileTierFilm, tier == "FILM", colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)
            updateCardSquircle(binding.tileTierPro, tier == "PRO", colorPrimaryContainer, colorPrimary, colorSurface, colorOutlineVariant)

            settingsRepository.edit {
                when (tier) {
                    "PS" -> {
                        this[PreferenceKeys.MANUAL_CONTROLS] = false
                        this[PreferenceKeys.EXP_FOCUS_PEAKING] = false
                        this[PreferenceKeys.SHOW_LUT_SWITCHER] = false
                        this[PreferenceKeys.SHOW_HDR_PLUS_SWITCH] = false
                        this[PreferenceKeys.SHOW_MODE_SWITCH_BUTTON] = false
                        binding.tvTierDesc.text = getString(R.string.onboarding_tier_ps_desc)
                    }
                    "FILM" -> {
                        this[PreferenceKeys.MANUAL_CONTROLS] = false
                        this[PreferenceKeys.EXP_FOCUS_PEAKING] = false
                        this[PreferenceKeys.SHOW_LUT_SWITCHER] = true
                        this[PreferenceKeys.SHOW_HDR_PLUS_SWITCH] = true
                        this[PreferenceKeys.SHOW_MODE_SWITCH_BUTTON] = true
                        binding.tvTierDesc.text = getString(R.string.onboarding_tier_film_desc)
                    }
                    "PRO" -> {
                        this[PreferenceKeys.MANUAL_CONTROLS] = true
                        this[PreferenceKeys.EXP_FOCUS_PEAKING] = true
                        this[PreferenceKeys.SHOW_LUT_SWITCHER] = true
                        this[PreferenceKeys.SHOW_HDR_PLUS_SWITCH] = true
                        this[PreferenceKeys.SHOW_MODE_SWITCH_BUTTON] = true
                        binding.tvTierDesc.text = getString(R.string.onboarding_tier_pro_desc)
                    }
                }
            }
        }

        binding.tileTierPs.setOnClickListener { updateUiTierSelection("PS") }
        binding.tileTierFilm.setOnClickListener { updateUiTierSelection("FILM") }
        binding.tileTierPro.setOnClickListener { updateUiTierSelection("PRO") }

        // Shutter Hold Action Toggle
        fun updateShutterHoldText() {
            val currentAction = settingsRepository.current().shutterLongPressAction
            binding.tvShutterHoldValue.text = when (currentAction) {
                PreferenceKeys.SHUTTER_LONG_PRESS_RAW_VIDEO -> "RAW Video"
                PreferenceKeys.SHUTTER_LONG_PRESS_DISABLED -> "Disabled"
                else -> "Standard MP4"
            }
        }

        binding.tileShutterHoldAction.setOnClickListener {
            val currentAction = settingsRepository.current().shutterLongPressAction
            val nextAction = when (currentAction) {
                PreferenceKeys.SHUTTER_LONG_PRESS_MP4 -> PreferenceKeys.SHUTTER_LONG_PRESS_RAW_VIDEO
                PreferenceKeys.SHUTTER_LONG_PRESS_RAW_VIDEO -> PreferenceKeys.SHUTTER_LONG_PRESS_DISABLED
                else -> PreferenceKeys.SHUTTER_LONG_PRESS_MP4
            }
            settingsRepository.setShutterLongPressAction(nextAction)
            updateShutterHoldText()
        }

        updateUiTierSelection("FILM")
        updateShutterHoldText()
    }

    // =========================================================================
    // Navigation & Primary Action Dock
    // =========================================================================
    private fun setupBottomActionDock() {
        binding.btnPrimaryCta.setOnClickListener {
            when (currentStep) {
                1 -> {
                    val camGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (!camGranted) {
                        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        batchPermissionLauncher.launch(perms.toTypedArray())
                    } else {
                        goToStep(2)
                    }
                }
                2 -> goToStep(3)
                3 -> goToStep(4)
                4 -> finishOnboarding()
            }
        }
    }

    private fun goToStep(step: Int) {
        currentStep = step
        binding.containerStep1.visibility = if (step == 1) View.VISIBLE else View.GONE
        binding.containerStep2.visibility = if (step == 2) View.VISIBLE else View.GONE
        binding.containerStep3.visibility = if (step == 3) View.VISIBLE else View.GONE
        binding.containerStep4.visibility = if (step == 4) View.VISIBLE else View.GONE

        binding.btnHeaderBack.visibility = if (step > 1) View.VISIBLE else View.INVISIBLE
        binding.tvHeaderStep.text = getString(R.string.onboarding_step_format, step, 4)

        val colorPrimary = MaterialColors.getColor(binding.root, android.R.attr.colorPrimary, android.graphics.Color.WHITE)
        val colorSurfaceContainerHigh = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh, android.graphics.Color.DKGRAY)

        binding.progStep1.backgroundTintList = ColorStateList.valueOf(if (step >= 1) colorPrimary else colorSurfaceContainerHigh)
        binding.progStep2.backgroundTintList = ColorStateList.valueOf(if (step >= 2) colorPrimary else colorSurfaceContainerHigh)
        binding.progStep3.backgroundTintList = ColorStateList.valueOf(if (step >= 3) colorPrimary else colorSurfaceContainerHigh)
        binding.progStep4.backgroundTintList = ColorStateList.valueOf(if (step >= 4) colorPrimary else colorSurfaceContainerHigh)

        updatePrimaryCtaButton()
        binding.nestedScrollView.smoothScrollTo(0, 0)
    }

    private fun updatePrimaryCtaButton() {
        val camGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        binding.btnPrimaryCta.text = when (currentStep) {
            1 -> if (camGranted) getString(R.string.onboarding_btn_next) else getString(R.string.onboarding_btn_grant_perms)
            2, 3 -> getString(R.string.onboarding_btn_next)
            else -> getString(R.string.onboarding_btn_start)
        }
    }

    private fun finishOnboarding() {
        settingsRepository.setOnboardingCompleted(true)
        routeStartup()
    }

    private fun updateCardSquircle(card: MaterialCardView, isSelected: Boolean, activeBg: Int, activeStroke: Int, inactiveBg: Int, inactiveStroke: Int) {
        val density = resources.displayMetrics.density
        card.radius = (if (isSelected) 10f else 14f) * density
        card.strokeWidth = ((if (isSelected) 2f else 1f) * density).toInt()
        card.setCardBackgroundColor(ColorStateList.valueOf(if (isSelected) activeBg else inactiveBg))
        card.strokeColor = if (isSelected) activeStroke else inactiveStroke
    }

    private fun routeStartup() {
        lifecycleScope.launch {
            val intent = requireActivity().intent
            val navController = findNavController()

            // If intent action is a Share Intent, do not perform default startup navigation;
            // MainActivity's handleIntent will perform the appropriate navigation once processing finishes.
            if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
                return@launch
            }

            // Clear the intent action so we don't process it again on recreation
            intent.action = null
            when (intent.getStringExtra(MainActivity.SHORTCUT_EXTRA_KEY)) {
                MainActivity.SHORTCUT_VALUE_SETTINGS -> {
                    navController.navigate(PermissionsFragmentDirections.actionPermissionsToSettings())
                    return@launch
                }
                MainActivity.SHORTCUT_VALUE_PLAYGROUND -> {
                    navController.navigate(PermissionsFragmentDirections.actionPermissionsToPlaygroundGallery())
                    return@launch
                }
                MainActivity.SHORTCUT_VALUE_CAMERA -> {
                    navController.navigate(PermissionsFragmentDirections.actionPermissionsToCamera())
                    return@launch
                }
            }

            // If no specific intent, check default startup page preference
            val defaultStartup = settingsRepository.current().defaultStartupPage
            if (defaultStartup == PreferenceKeys.STARTUP_PLAYGROUND) {
                navController.navigate(PermissionsFragmentDirections.actionPermissionsToPlaygroundGallery())
            } else {
                navController.navigate(PermissionsFragmentDirections.actionPermissionsToCamera())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Convenience method used to check if critical permissions required by this app are granted */
        fun hasPermissions(context: Context): Boolean {
            val criticalPermissions = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                criticalPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return criticalPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}

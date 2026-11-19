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
import kotlinx.coroutines.launch
import top.maary.darkbag.MainActivity
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentPermissionsBinding
import top.maary.darkbag.fragments.onboarding.OnboardingStepBinder
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.utils.LutManager

/**
 * First-install Onboarding and Permissions controller for Darkbag.
 * Follows Bento Matrix & Material 3 Expressive guidelines (Invariant 5).
 *
 * Retains Fragment lifecycle, ActivityResult permission/picker launchers, and DataStore
 * startup routing; delegates step UI rendering and Bento card interactions to
 * [OnboardingStepBinder].
 */
class PermissionsFragment : Fragment(), OnboardingStepBinder.Callbacks {

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var lutManager: LutManager
    private var stepBinder: OnboardingStepBinder? = null

    private var forceOnboarding = false

    // Permission Launchers
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            stepBinder?.updatePermissionTilesState()
            stepBinder?.updatePrimaryCtaButton()
        } else {
            Toast.makeText(requireContext(), R.string.toast_camera_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        stepBinder?.updatePermissionTilesState()
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        stepBinder?.updatePermissionTilesState()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            settingsRepository.setSaveLocationEnabled(true)
            stepBinder?.setSaveLocationChecked(true)
        } else {
            settingsRepository.setSaveLocationEnabled(false)
            stepBinder?.setSaveLocationChecked(false)
            Toast.makeText(requireContext(), R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val batchPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: (
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
        stepBinder?.updatePermissionTilesState()
        stepBinder?.updatePrimaryCtaButton()

        if (cameraGranted) {
            stepBinder?.goToStep(2)
        } else {
            Toast.makeText(requireContext(), R.string.toast_camera_permission_continue, Toast.LENGTH_SHORT).show()
        }
    }

    private val lutFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val pairingLog = stepBinder?.selectedPairingLog ?: "F-Log2"
        val importedFileName = lutManager.importLut(uri, pairingLog)
        if (importedFileName != null) {
            settingsRepository.edit {
                this[PreferenceKeys.TARGET_LOG] = pairingLog
                this[PreferenceKeys.ACTIVE_LUT] = importedFileName
                this[PreferenceKeys.SAVE_JPG] = true
            }

            stepBinder?.showImportedRecipeResult(importedFileName, pairingLog)
            Toast.makeText(
                requireContext(),
                getString(R.string.toast_lut_imported_paired, importedFileName, pairingLog),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(requireContext(), R.string.toast_lut_import_failed, Toast.LENGTH_SHORT).show()
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
        val binder = OnboardingStepBinder(binding, settingsRepository, this)
        stepBinder = binder
        binder.goToStep(1)

        if (settingsRepository.isHydrated) {
            binder.setupAllSteps()
        } else {
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
                    stepBinder?.setupAllSteps()
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

    // =========================================================================
    // OnboardingStepBinder.Callbacks
    // =========================================================================
    override fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun requestAudioPermission() {
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun requestLocationPermissions(permissions: Array<String>) {
        locationPermissionLauncher.launch(permissions)
    }

    override fun requestBatchStartupPermissions(permissions: Array<String>) {
        batchPermissionLauncher.launch(permissions)
    }

    override fun launchLutPicker(selectedLog: String) {
        lutFilePicker.launch("*/*")
    }

    override fun onFinishOnboarding() {
        settingsRepository.setOnboardingCompleted(true)
        routeStartup()
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
        stepBinder = null
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

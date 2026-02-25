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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import top.maary.darkbag.R
import top.maary.darkbag.MainActivity
import kotlinx.coroutines.launch

private var PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)

/**
 * The sole purpose of this fragment is to request permissions and, once granted, display the
 * camera fragment to the user.
 */
class PermissionsFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // add the storage access permission request for Android 9 and below.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permissionList = PERMISSIONS_REQUIRED.toMutableList()
            permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            PERMISSIONS_REQUIRED = permissionList.toTypedArray()
        }

        if (!hasPermissions(requireContext())) {
            // Request camera-related permissions
            activityResultLauncher.launch(PERMISSIONS_REQUIRED)
        } else {
            navigateToNext()
        }
    }

    private fun navigateToNext() {
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val startGallery = prefs.getBoolean(SettingsFragment.KEY_START_GALLERY, false)

        val shortcut = requireActivity().intent.getStringExtra(MainActivity.SHORTCUT_EXTRA_KEY)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val navController = Navigation.findNavController(requireActivity(), R.id.fragment_container)

                when (shortcut) {
                    MainActivity.SHORTCUT_VALUE_GALLERY -> {
                        navController.navigate(PermissionsFragmentDirections.actionPermissionsToGallery())
                    }
                    MainActivity.SHORTCUT_VALUE_CAMERA -> {
                        navController.navigate(PermissionsFragmentDirections.actionPermissionsToCamera())
                    }
                    MainActivity.SHORTCUT_VALUE_SETTINGS -> {
                        navController.navigate(PermissionsFragmentDirections.actionPermissionsToGallery())
                        navController.navigate(R.id.settings_fragment)
                    }
                    else -> {
                        if (startGallery) {
                            navController.navigate(PermissionsFragmentDirections.actionPermissionsToGallery())
                        } else {
                            navController.navigate(PermissionsFragmentDirections.actionPermissionsToCamera())
                        }
                    }
                }
            }
        }
    }

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in PERMISSIONS_REQUIRED && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(context, "Permission request denied", Toast.LENGTH_LONG).show()
            } else {
                navigateToNext()
            }
        }

    companion object {
        /** Convenience method used to check if all permissions required by this app are granted */
        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

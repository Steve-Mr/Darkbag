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

package top.maary.darkbag

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import top.maary.darkbag.databinding.ActivityMainBinding
import com.google.android.material.color.DynamicColors
import top.maary.darkbag.fragments.SettingsFragment

const val KEY_EVENT_ACTION = "key_event_action"
const val KEY_EVENT_EXTRA = "key_event_extra"

/**
 * Main entry point into our app. This app follows the single-activity pattern, and all
 * functionality is implemented in the form of fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var activityMainBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        setupNavigation()
        handleIntent(intent)
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController
        activityMainBinding.navView.setupWithNavController(navController)

        val prefs = getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val enableCamera = prefs.getBoolean(SettingsFragment.KEY_ENABLE_CAMERA, true)
            val enableStudio = prefs.getBoolean(SettingsFragment.KEY_ENABLE_STUDIO, true)

            if (destination.id == R.id.camera_fragment || destination.id == R.id.studio_fragment) {
                if (enableCamera && enableStudio) {
                    activityMainBinding.navView.visibility = View.VISIBLE
                } else {
                    activityMainBinding.navView.visibility = View.GONE
                }
            } else {
                activityMainBinding.navView.visibility = View.GONE
            }
        }

        // Handle startup mode
        if (navController.currentDestination?.id == R.id.permissions_fragment) {
            // PermissionsFragment will navigate based on permissions.
            // We need to ensure it knows where to go.
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        val navController = navHostFragment?.navController ?: return

        if (intent.getStringExtra(SHORTCUT_EXTRA_KEY) == SHORTCUT_VALUE_SETTINGS) {
            if (navController.currentDestination?.id != R.id.settings_fragment) {
                navController.navigate(R.id.settings_fragment)
            }
        } else if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            imageUri?.let { navigateToViewer(navController, it) }
        } else if (intent.action == Intent.ACTION_VIEW && intent.type?.startsWith("image/") == true) {
            intent.data?.let { navigateToViewer(navController, it) }
        }
    }

    private fun navigateToViewer(navController: NavController, uri: Uri) {
        val args = Bundle().apply {
            putString("initial_uri", uri.toString())
        }
        navController.navigate(R.id.image_viewer_fragment, args)
    }

    override fun onResume() {
        super.onResume()
    }

    /** When key down event is triggered, relay it via local flow so fragments can handle it */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                (application as MainApplication).keyEventFlow.tryEmit(event)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            // Workaround for Android Q memory leak issue in IRequestFinishCallback$Stub.
            // (https://issuetracker.google.com/issues/139738913)
            finishAfterTransition()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        const val SHORTCUT_EXTRA_KEY = "shortcut"
        const val SHORTCUT_VALUE_SETTINGS = "settings"
    }

}

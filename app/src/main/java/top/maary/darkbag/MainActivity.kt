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
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import top.maary.darkbag.databinding.ActivityMainBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.utils.ShareUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.google.android.material.color.DynamicColors

const val KEY_EVENT_ACTION = "key_event_action"
const val KEY_EVENT_EXTRA = "key_event_extra"

/**
 * Main entry point into our app. This app follows the single-activity pattern, and all
 * functionality is implemented in the form of fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var activityMainBinding: ActivityMainBinding
    private var toolbarHeightWithInsets = 0
    private var currentDestId = -1
    private var isFloatingToolbarForcedHidden = false

    // State flow to broadcast the toolbar's effective height (including bottom margin) to fragments that need it for padding (like PlaygroundGalleryFragment)
    private val _toolbarHeightFlow = MutableStateFlow(0)
    val toolbarHeightFlow: StateFlow<Int> = _toolbarHeightFlow

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        setupFloatingToolbar()
        handleIntent(intent)
    }

    private fun setupFloatingToolbar() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        val navController = navHostFragment?.navController ?: return

        val toolbarLayout = activityMainBinding.floatingToolbar
        val btnCamera = activityMainBinding.floatingToolbarButtonCamera
        val btnPlayground = activityMainBinding.floatingToolbarButtonPlayground

        // Handle window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(toolbarLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<MarginLayoutParams> {
                val baseMargin = (16 * view.context.resources.displayMetrics.density).toInt()
                bottomMargin = baseMargin + systemBars.bottom
            }

            // Wait for next layout pass to measure actual height
            view.post {
                if (view.visibility == View.VISIBLE) {
                    val lp = view.layoutParams as MarginLayoutParams
                    toolbarHeightWithInsets = view.height + lp.topMargin + lp.bottomMargin
                } else {
                    toolbarHeightWithInsets = 0
                }
                _toolbarHeightFlow.value = toolbarHeightWithInsets
            }
            insets
        }

        btnCamera.setOnClickListener {
            if (currentDestId == R.id.camera_fragment) {
                btnCamera.isChecked = true
            } else {
                navController.navigate(R.id.camera_fragment, null, NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build())
            }
        }

        btnPlayground.setOnClickListener {
            if (currentDestId == R.id.playground_gallery_fragment) {
                btnPlayground.isChecked = true
            } else {
                navController.navigate(R.id.playground_gallery_fragment, null, NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build())
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentDestId = destination.id
            updateFloatingToolbarVisibility()
        }
    }

    fun setFloatingToolbarForcedHidden(hidden: Boolean) {
        if (isFloatingToolbarForcedHidden != hidden) {
            isFloatingToolbarForcedHidden = hidden
            updateFloatingToolbarVisibility()
        }
    }

    private fun updateFloatingToolbarVisibility() {
        val prefs = getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val showToolbar = prefs.getBoolean(SettingsFragment.KEY_SHOW_FLOATING_TOOLBAR, true)
        val enableCamera = prefs.getBoolean(SettingsFragment.KEY_ENABLE_CAMERA, true)
        val enablePlayground = prefs.getBoolean(SettingsFragment.KEY_ENABLE_PLAYGROUND, true)

        val toolbarLayout = activityMainBinding.floatingToolbar
        val btnCamera = activityMainBinding.floatingToolbarButtonCamera
        val btnPlayground = activityMainBinding.floatingToolbarButtonPlayground

        btnCamera.visibility = if (enableCamera) View.VISIBLE else View.GONE
        btnPlayground.visibility = if (enablePlayground) View.VISIBLE else View.GONE

        // Only show toolbar on Camera and Playground Gallery fragments
        val isAllowedDestination = currentDestId == R.id.camera_fragment ||
                                   currentDestId == R.id.playground_gallery_fragment

        if (!showToolbar || !enableCamera || !enablePlayground || !isAllowedDestination || isFloatingToolbarForcedHidden) {
            toolbarLayout.visibility = View.GONE
            toolbarHeightWithInsets = 0
        } else {
            toolbarLayout.visibility = View.VISIBLE
            // Recalculate if it just became visible
            toolbarLayout.post {
                if (toolbarLayout.visibility == View.VISIBLE) {
                     val lp = toolbarLayout.layoutParams as MarginLayoutParams
                     toolbarHeightWithInsets = toolbarLayout.height + lp.topMargin + lp.bottomMargin
                }
                _toolbarHeightFlow.value = toolbarHeightWithInsets
            }
        }

        _toolbarHeightFlow.value = toolbarHeightWithInsets

        btnCamera.isChecked = currentDestId == R.id.camera_fragment
        btnPlayground.isChecked = currentDestId == R.id.playground_gallery_fragment
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
        val navController = navHostFragment?.navController ?: return

        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            lifecycleScope.launch {
                val paths = top.maary.darkbag.utils.ShareUtils.processShareIntent(this@MainActivity, intent)
                if (paths.isNotEmpty()) {
                    if (paths.size == 1) {
                        val bundle = android.os.Bundle().apply {
                            putStringArray("playground_dng_paths", paths.toTypedArray())
                        }
                        navController.navigate(R.id.playground_viewer_fragment, bundle)
                    } else {
                        if (navController.currentDestination?.id != R.id.playground_gallery_fragment) {
                            navController.navigate(R.id.playground_gallery_fragment)
                        }
                    }
                }
                intent.action = null // Prevent re-triggering
            }
            return
        }

        when (intent.getStringExtra(SHORTCUT_EXTRA_KEY)) {
            SHORTCUT_VALUE_SETTINGS -> {
                if (navController.currentDestination?.id != R.id.settings_fragment) {
                    navController.navigate(R.id.settings_fragment)
                }
                return
            }
            SHORTCUT_VALUE_PLAYGROUND -> {
                if (navController.currentDestination?.id != R.id.playground_gallery_fragment) {
                    navController.navigate(R.id.playground_gallery_fragment)
                }
                return
            }
            SHORTCUT_VALUE_CAMERA -> {
                if (navController.currentDestination?.id != R.id.camera_fragment) {
                    navController.navigate(R.id.camera_fragment)
                }
                return
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateFloatingToolbarVisibility()
    }

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
            finishAfterTransition()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        const val SHORTCUT_EXTRA_KEY = "shortcut"
        const val SHORTCUT_VALUE_SETTINGS = "settings"
        const val SHORTCUT_VALUE_PLAYGROUND = "playground"
        const val SHORTCUT_VALUE_CAMERA = "camera"
    }

}

package top.maary.darkbag.utils

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import android.view.ViewGroup.MarginLayoutParams
import com.google.android.material.button.MaterialButton
import androidx.navigation.NavController
import top.maary.darkbag.R
import top.maary.darkbag.fragments.SettingsFragment

object FloatingToolbarManager {

    fun setup(
        context: Context,
        toolbarLayout: View?,
        btnCamera: MaterialButton?,
        btnPlayground: MaterialButton?,
        navController: NavController,
        currentDestinationId: Int
    ) {
        if (toolbarLayout == null || btnCamera == null || btnPlayground == null) return

        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val showToolbar = prefs.getBoolean(SettingsFragment.KEY_SHOW_FLOATING_TOOLBAR, true)

        if (!showToolbar) {
            toolbarLayout.visibility = View.GONE
            return
        }
        toolbarLayout.visibility = View.VISIBLE

        // Handle window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(toolbarLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<MarginLayoutParams> {
                val baseMargin = (48 * view.context.resources.displayMetrics.density).toInt()
                bottomMargin = baseMargin + systemBars.bottom
            }
            insets
        }


        val enableCamera = prefs.getBoolean(SettingsFragment.KEY_ENABLE_CAMERA, true)
        val enablePlayground = prefs.getBoolean(SettingsFragment.KEY_ENABLE_PLAYGROUND, true)





        btnCamera.visibility = if (enableCamera) View.VISIBLE else View.GONE
        btnPlayground.visibility = if (enablePlayground) View.VISIBLE else View.GONE

        // Hide toolbar entirely if less than 2 items are visible, it serves no navigation purpose
        if (!enableCamera || !enablePlayground) {
             toolbarLayout.visibility = View.GONE
             return
        }

        // Setup selections and clicks
        btnCamera.isChecked = currentDestinationId == R.id.camera_fragment
        btnPlayground.isChecked = currentDestinationId == R.id.playground_gallery_fragment

        btnCamera.setOnClickListener {
            if (currentDestinationId == R.id.camera_fragment) btnCamera.isChecked = true
            if (currentDestinationId != R.id.camera_fragment) {
                navController.navigate(R.id.camera_fragment, null, androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build())
            }
        }

        btnPlayground.setOnClickListener {
            if (currentDestinationId == R.id.playground_gallery_fragment) btnPlayground.isChecked = true
            if (currentDestinationId != R.id.playground_gallery_fragment) {
                navController.navigate(R.id.playground_gallery_fragment, null, androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build())
            }
        }
    }
}

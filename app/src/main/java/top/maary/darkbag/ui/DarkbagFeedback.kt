package top.maary.darkbag.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import top.maary.darkbag.R

/**
 * Unified Material 3 Expressive Snackbar feedback helper for Darkbag.
 * Replaces legacy Toast popups with anchored, theme-aware Snackbars that respect
 * floating toolbars and the camera Bento deck.
 */
object DarkbagFeedback {

    private const val TAG = "DarkbagFeedback"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showSnackbar(
        view: View?,
        message: CharSequence,
        duration: Int = Snackbar.LENGTH_SHORT,
        isError: Boolean = false,
        anchorView: View? = null
    ) {
        if (view == null || message.isBlank()) {
            Log.w(TAG, "Skipped snackbar (view=$view): $message")
            return
        }
        val showBlock: () -> Unit = {
            runCatching {
                if (!view.isAttachedToWindow) {
                    Log.w(TAG, "Skipped snackbar on detached view: $message")
                    return@runCatching
                }
                val snackbar = Snackbar.make(view, message, duration)
                val resolvedAnchor = anchorView ?: resolveDefaultAnchor(view)
                if (resolvedAnchor != null && resolvedAnchor.isShown && resolvedAnchor !== view) {
                    snackbar.anchorView = resolvedAnchor
                }
                applyExpressiveStyling(snackbar, view, isError)
                snackbar.show()
            }.onFailure { err ->
                Log.w(TAG, "Unable to show snackbar: $message", err)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            showBlock()
        } else {
            mainHandler.post(showBlock)
        }
    }

    fun showSnackbar(
        view: View?,
        @StringRes resId: Int,
        duration: Int = Snackbar.LENGTH_SHORT,
        isError: Boolean = false,
        anchorView: View? = null
    ) {
        if (view == null) return
        val message = runCatching { view.context.getString(resId) }.getOrNull() ?: return
        showSnackbar(view, message, duration, isError, anchorView)
    }

    fun showSnackbar(
        context: Context?,
        message: CharSequence,
        duration: Int = Snackbar.LENGTH_SHORT,
        isError: Boolean = false
    ) {
        val activity = unwrapActivity(context)
        val contentView = activity?.findViewById<View>(android.R.id.content)
        if (contentView == null) {
            Log.w(TAG, "No activity content view available for snackbar: $message")
            return
        }
        showSnackbar(contentView, message, duration, isError)
    }

    fun showSnackbar(
        context: Context?,
        @StringRes resId: Int,
        duration: Int = Snackbar.LENGTH_SHORT,
        isError: Boolean = false
    ) {
        if (context == null) return
        val message = runCatching { context.getString(resId) }.getOrNull() ?: return
        showSnackbar(context, message, duration, isError)
    }

    private fun resolveDefaultAnchor(view: View): View? {
        val root = view.rootView ?: return null
        val floatingToolbar = root.findViewById<View>(R.id.floating_toolbar)
        if (floatingToolbar != null && floatingToolbar.isShown) {
            return floatingToolbar
        }
        val bottomIsland = root.findViewById<View>(R.id.bottom_island_card)
        if (bottomIsland != null && bottomIsland.isShown &&
            view.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        ) {
            return bottomIsland
        }
        return null
    }

    private fun applyExpressiveStyling(snackbar: Snackbar, anchorContextView: View, isError: Boolean) {
        val density = anchorContextView.resources.displayMetrics.density
        val cornerRadiusPx = 16f * density
        val snackView = snackbar.view
        val bgColor = if (isError) {
            MaterialColors.getColor(
                snackView,
                com.google.android.material.R.attr.colorErrorContainer,
                MaterialColors.getColor(snackView, android.R.attr.colorBackground, 0xFF323232.toInt())
            )
        } else {
            MaterialColors.getColor(
                snackView,
                com.google.android.material.R.attr.colorSurfaceInverse,
                0xFF323232.toInt()
            )
        }
        val textColor = if (isError) {
            MaterialColors.getColor(
                snackView,
                com.google.android.material.R.attr.colorOnErrorContainer,
                0xFFFFFFFF.toInt()
            )
        } else {
            MaterialColors.getColor(
                snackView,
                com.google.android.material.R.attr.colorOnSurfaceInverse,
                0xFFFFFFFF.toInt()
            )
        }
        val shape = ShapeAppearanceModel.builder()
            .setAllCornerSizes(cornerRadiusPx)
            .build()
        snackView.background = MaterialShapeDrawable(shape).apply {
            setTint(bgColor)
        }
        snackbar.setTextColor(textColor)
    }

    private fun unwrapActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}

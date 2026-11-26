package top.maary.darkbag.utils

import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.BackEventCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.transition.MaterialSharedAxis

/**
 * Configures Material Motion shared-axis transitions that support predictive back
 * progress seeking between fragments, and provides a reusable gesture scrubber for
 * in-page floating overlays, editors, and selection docks.
 */
object PredictiveBackTransitions {

    private const val CANCEL_RESTORE_DURATION_MS = 150L
    private val cancelInterpolator = DecelerateInterpolator()

    data class ScrubTarget(
        val view: View,
        val maxTranslationYDp: Float = 28f,
        val maxTranslationXDp: Float = 12f,
        val minScale: Float = 0.96f,
        val minAlpha: Float = 0.65f,
        val settleAlpha: Float = 1f
    )

    data class ScrubTransform(
        val translationX: Float,
        val translationY: Float,
        val scale: Float,
        val alpha: Float
    )

    fun apply(fragment: Fragment, axis: Int = MaterialSharedAxis.X) {
        fragment.enterTransition = MaterialSharedAxis(axis, true)
        fragment.exitTransition = MaterialSharedAxis(axis, true)
        fragment.returnTransition = MaterialSharedAxis(axis, false)
        fragment.reenterTransition = MaterialSharedAxis(axis, false)
    }

    internal fun computeScrubTransform(
        progress: Float,
        swipeEdge: Int,
        density: Float,
        maxTranslationYDp: Float,
        maxTranslationXDp: Float,
        minScale: Float,
        minAlpha: Float,
        settleAlpha: Float = 1f
    ): ScrubTransform {
        val p = progress.coerceIn(0f, 1f)
        val xDirection = when (swipeEdge) {
            BackEventCompat.EDGE_LEFT -> 1f
            BackEventCompat.EDGE_RIGHT -> -1f
            else -> 0f
        }
        return ScrubTransform(
            translationX = xDirection * p * maxTranslationXDp * density,
            translationY = p * maxTranslationYDp * density,
            scale = 1f - (1f - minScale) * p,
            alpha = (settleAlpha - (settleAlpha - minAlpha) * p).coerceIn(0f, 1f)
        )
    }

    fun applyScrubProgress(
        targets: List<ScrubTarget>,
        backEvent: BackEventCompat,
        backdropView: View? = null,
        initialBackdropAlpha: Float = 1f
    ) {
        val progress = backEvent.progress.coerceIn(0f, 1f)
        for (target in targets) {
            val view = target.view
            if (!view.isVisible) continue
            view.animate().cancel()
            val transform = computeScrubTransform(
                progress = progress,
                swipeEdge = backEvent.swipeEdge,
                density = view.resources.displayMetrics.density,
                maxTranslationYDp = target.maxTranslationYDp,
                maxTranslationXDp = target.maxTranslationXDp,
                minScale = target.minScale,
                minAlpha = target.minAlpha,
                settleAlpha = target.settleAlpha
            )
            view.translationX = transform.translationX
            view.translationY = transform.translationY
            view.scaleX = transform.scale
            view.scaleY = transform.scale
            view.alpha = transform.alpha
        }
        if (backdropView != null && backdropView.isVisible) {
            backdropView.animate().cancel()
            backdropView.alpha = (initialBackdropAlpha * (1f - 0.5f * progress)).coerceIn(0f, 1f)
        }
    }

    fun cancelScrub(
        targets: List<ScrubTarget>,
        backdropView: View? = null,
        initialBackdropAlpha: Float = 1f
    ) {
        for (target in targets) {
            val view = target.view
            if (!view.isVisible) continue
            view.animate().cancel()
            view.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(target.settleAlpha)
                .setDuration(CANCEL_RESTORE_DURATION_MS)
                .setInterpolator(cancelInterpolator)
                .start()
        }
        if (backdropView != null && backdropView.isVisible) {
            backdropView.animate().cancel()
            backdropView.animate()
                .alpha(initialBackdropAlpha)
                .setDuration(CANCEL_RESTORE_DURATION_MS)
                .setInterpolator(cancelInterpolator)
                .start()
        }
    }

    fun resetScrubImmediate(
        targets: List<ScrubTarget>,
        backdropView: View? = null,
        initialBackdropAlpha: Float = 1f
    ) {
        for (target in targets) {
            val view = target.view
            view.animate().cancel()
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = target.settleAlpha
        }
        if (backdropView != null) {
            backdropView.animate().cancel()
            backdropView.alpha = initialBackdropAlpha
        }
    }
}

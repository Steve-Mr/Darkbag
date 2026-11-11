package top.maary.darkbag.camera.delegate

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentCameraBinding

/**
 * Host contract for camera touch gesture events.
 */
interface CameraGestureHost {
    val isFloatingOverlayOpen: Boolean
    fun dismissFloatingOverlays()
    fun triggerTapToFocus(x: Float, y: Float)
    fun unlockFocusAndMetering()
}

/**
 * Manages viewfinder touch gestures (tap-to-focus, double-tap unlock) and
 * focus ring animations.
 */
class CameraGestureDelegate(
    private val context: Context,
    private val host: CameraGestureHost
) {
    fun setupTapToFocus(stageView: View) {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (host.isFloatingOverlayOpen) {
                    host.dismissFloatingOverlays()
                    return true
                }
                host.triggerTapToFocus(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (host.isFloatingOverlayOpen) {
                    host.dismissFloatingOverlays()
                    return true
                }
                host.unlockFocusAndMetering()
                return true
            }
        })

        stageView.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                view.performClick()
            }
            true
        }
    }

    fun showFocusRingScanning(binding: FragmentCameraBinding, x: Float, y: Float, ringSize: Float) {
        val focusRing = binding.focusRing
        focusRing.animate().cancel()
        binding.focusRingCircle.animate().cancel()
        binding.focusLockBadge.animate().cancel()

        focusRing.translationX = x - ringSize / 2
        focusRing.translationY = y - ringSize / 2
        focusRing.scaleX = 1.25f
        focusRing.scaleY = 1.25f
        focusRing.alpha = 1.0f
        focusRing.visibility = View.VISIBLE

        binding.focusLockBadge.visibility = View.GONE
        setFocusRingStroke(binding, Color.WHITE)

        focusRing.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        binding.focusRingCircle.alpha = 0.4f
        binding.focusRingCircle.animate()
            .alpha(1.0f)
            .setDuration(300L)
            .start()
    }

    fun showFocusRingLocked(binding: FragmentCameraBinding, hasFocusLock: Boolean) {
        if (!hasFocusLock) return

        val colorTertiary = com.google.android.material.color.MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorTertiary,
            Color.parseColor("#00E676")
        )

        setFocusRingStroke(binding, colorTertiary)

        // Pop in the AF-L badge with overshoot spring
        binding.focusLockBadge.visibility = View.VISIBLE
        binding.focusLockBadge.scaleX = 0f
        binding.focusLockBadge.scaleY = 0f
        binding.focusLockBadge.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(200L)
            .setInterpolator(OvershootInterpolator())
            .start()

        binding.focusRing.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(150L)
            .withEndAction {
                binding.focusRing.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(150L)
                    .start()
            }
            .start()
    }

    fun showFocusRingFailed(binding: FragmentCameraBinding) {
        val colorError = com.google.android.material.color.MaterialColors.getColor(
            binding.root,
            androidx.appcompat.R.attr.colorError,
            Color.parseColor("#FF5252")
        )

        setFocusRingStroke(binding, colorError)
        binding.focusLockBadge.visibility = View.GONE

        binding.focusRing.animate()
            .alpha(0.0f)
            .setDuration(400L)
            .withEndAction {
                binding.focusRing.visibility = View.GONE
                binding.focusRing.alpha = 1.0f
                setFocusRingStroke(binding, Color.WHITE)
            }
            .start()
    }

    fun hideFocusRingUnlock(binding: FragmentCameraBinding) {
        binding.focusRing.animate().cancel()
        binding.focusLockBadge.animate().cancel()

        binding.focusRing.animate()
            .alpha(0.0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(200L)
            .withEndAction {
                binding.focusRing.visibility = View.GONE
                binding.focusRing.alpha = 1.0f
                binding.focusRing.scaleX = 1.0f
                binding.focusRing.scaleY = 1.0f
                binding.focusLockBadge.visibility = View.GONE
                setFocusRingStroke(binding, Color.WHITE)
            }
            .start()
    }

    fun setFocusRingStroke(binding: FragmentCameraBinding, color: Int) {
        val ringBg = binding.focusRingCircle.background?.mutate() as? GradientDrawable
        ringBg?.setStroke(2, color)
    }
}

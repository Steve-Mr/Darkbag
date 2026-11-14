package top.maary.darkbag.camera.delegate

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.content.res.Configuration
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import kotlin.math.roundToInt
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.modes.CaptureMode
import top.maary.darkbag.modes.CaptureModeCoordinator
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.viewmodel.CameraViewModel

interface CameraOverlayHost {
    val context: Context
    val fragmentCameraBinding: FragmentCameraBinding?
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val activeCoordinator: CaptureModeCoordinator?
    val cameraViewModel: CameraViewModel
    val isProcessing: Boolean
    val halfFrameStep: Int
    var isHalfFrameUiAnimating: Boolean
    var pendingVfSnapshot: Bitmap?

    fun readScopedHalfFrameState(prefs: SharedPreferences, requireFileForStep1: Boolean = false)
}

/**
 * Manages viewfinder preview overlays:
 * - Shutter blackout blink effect
 * - Processing indicator animations
 * - Half-frame viewfinder split guide and advance animations
 */
class CameraOverlayController(
    private val host: CameraOverlayHost
) {
    fun showShutterBlackout() {
        val vfBinding = host.fragmentCameraBinding ?: return
        val blackout = vfBinding.viewFinderBlackout ?: return
        val vf = vfBinding.viewFinder

        val applyBlackout = Runnable {
            // Sync translation and scaling with the ViewFinder to ensure full coverage in Half-frame mode
            blackout.translationX = vf.translationX
            blackout.translationY = vf.translationY
            blackout.scaleX = vf.scaleX
            blackout.scaleY = vf.scaleY
            blackout.elevation = vf.elevation + 4f

            blackout.animate().cancel()
            blackout.alpha = 0.85f
            blackout.visibility = View.VISIBLE
            blackout.animate()
                .alpha(0f)
                .setDuration(60L)
                .withEndAction {
                    blackout.visibility = View.INVISIBLE
                }
                .start()
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            applyBlackout.run()
        } else {
            vf.post(applyBlackout)
        }
    }

    fun updateProcessingAnimationUi() {
        val processing = host.isProcessing
        val shouldHide = (host.activeCoordinator?.shouldHideGalleryThumbnail() == true)
        if (processing) {
            host.cameraUiContainerBinding?.processingProgress?.visibility = View.VISIBLE
            host.cameraUiContainerBinding?.photoViewContainer?.visibility = View.VISIBLE
            if (shouldHide) {
                host.cameraUiContainerBinding?.photoViewButton?.visibility = View.INVISIBLE
            }
        } else {
            host.cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
            if (!shouldHide) {
                host.cameraUiContainerBinding?.photoViewButton?.visibility = View.VISIBLE
                host.cameraUiContainerBinding?.photoViewButton?.alpha = 1f
            }
        }
    }

    fun showProcessingAnimation() {
        host.cameraUiContainerBinding?.processingProgress?.visibility = View.VISIBLE
        host.cameraUiContainerBinding?.photoViewContainer?.visibility = View.VISIBLE
    }

    fun hideProcessingAnimation() {
        host.cameraUiContainerBinding?.processingProgress?.visibility = View.GONE
    }

    /** Pins the viewfinder container between the top controls and the bottom island (portrait) or the docked island (landscape). */
    fun updateViewFinderConstraints() {
        val uiBinding = host.cameraUiContainerBinding ?: return
        val vfBinding = host.fragmentCameraBinding ?: return
        val root = vfBinding.root as? ConstraintLayout ?: return
        val bottomIsland = uiBinding.bottomIslandCard ?: return
        val topControls = uiBinding.topRightControls ?: return

        val set = ConstraintSet().apply { clone(root) }
        val containerId = vfBinding.viewFinderContainer.id
        val bottomIslandId = bottomIsland.id
        val res = host.context.resources
        val marginH = (10 * res.displayMetrics.density).roundToInt()
        val marginV = (6 * res.displayMetrics.density).roundToInt()
        val isPortrait = res.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val parent = ConstraintSet.PARENT_ID

        set.connect(containerId, ConstraintSet.START, parent, ConstraintSet.START, marginH)
        set.constrainWidth(containerId, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(containerId, ConstraintSet.MATCH_CONSTRAINT)
        set.setDimensionRatio(containerId, if (isPortrait) "3:4" else "4:3")
        set.constrainedWidth(containerId, true)
        set.constrainedHeight(containerId, true)
        if (isPortrait) {
            set.connect(containerId, ConstraintSet.END, parent, ConstraintSet.END, marginH)
            set.connect(containerId, ConstraintSet.TOP, topControls.id, ConstraintSet.BOTTOM, marginV)
            set.connect(containerId, ConstraintSet.BOTTOM, bottomIslandId, ConstraintSet.TOP, marginV)
            set.setVerticalBias(containerId, 0.5f)
        } else {
            // Landscape: bottom_island_card is docked to the right edge
            set.connect(containerId, ConstraintSet.END, bottomIslandId, ConstraintSet.START, marginH)
            set.connect(containerId, ConstraintSet.TOP, parent, ConstraintSet.TOP, marginV)
            set.connect(containerId, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM, marginV)
            set.setHorizontalBias(containerId, 0.5f)
        }
        set.applyTo(root)
        vfBinding.viewFinderContainer.clipToOutline = true
        vfBinding.viewFinderStage.clipToOutline = true
    }

    fun syncViewFinderAspectRatio(isPortraitBounds: Boolean) {
        val ratio = if (isPortraitBounds) "3:4" else "4:3"
        val viewFinder = host.fragmentCameraBinding?.viewFinder
        val viewFinderContainer = host.fragmentCameraBinding?.viewFinderContainer
        (viewFinderContainer?.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            if (lp.dimensionRatio != ratio) {
                lp.dimensionRatio = ratio
                viewFinderContainer.layoutParams = lp
            }
        }
        if (isPortraitBounds) {
            viewFinder?.setAspectRatio(3, 4)
        } else {
            viewFinder?.setAspectRatio(4, 3)
        }
    }

    fun updateHalfFrameUI(animate: Boolean = false) {
        val uiBinding = host.cameraUiContainerBinding ?: return
        val vfBinding = host.fragmentCameraBinding ?: return
        val prefs = host.context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        // Post all updates to the viewfinder to avoid requestLayout() during layout pass
        vfBinding.viewFinder.post {
            val isEnabled = host.activeCoordinator?.mode?.isHalfFrame == true
            host.readScopedHalfFrameState(prefs)
            host.cameraViewModel.setHalfFrameStep(host.halfFrameStep)
            uiBinding.cameraCaptureButton?.setHalfFrameState(isEnabled, host.halfFrameStep)
            uiBinding.bottomIslandCard?.let { card ->
                CameraBentoDeckView.styleDeckCard(card, if (isEnabled) CaptureMode.HALF_FRAME_SBS else CaptureMode.NORMAL)
            }

            val gapView = vfBinding.halfFrameGapIndicator ?: return@post
            val snapshotView = vfBinding.halfFrameSnapshot ?: return@post
            val edgeTopView = vfBinding.halfFrameFilmEdgeTop ?: return@post
            val edgeBottomView = vfBinding.halfFrameFilmEdgeBottom ?: return@post

            if (!isEnabled) {
                host.isHalfFrameUiAnimating = false
                if (uiBinding.tvHalfFrameStep?.visibility != View.GONE) {
                    uiBinding.tvHalfFrameStep?.visibility = View.GONE
                }
                if (gapView.visibility != View.GONE) gapView.visibility = View.GONE
                if (snapshotView.visibility != View.GONE) snapshotView.visibility = View.GONE
                if (edgeTopView.visibility != View.GONE) edgeTopView.visibility = View.GONE
                if (edgeBottomView.visibility != View.GONE) edgeBottomView.visibility = View.GONE

                vfBinding.viewFinder.animate().cancel()
                vfBinding.viewFinder.alpha = 1f
                vfBinding.viewFinder.scaleX = 1f
                vfBinding.viewFinder.scaleY = 1f
                vfBinding.viewFinder.translationX = 0f
                vfBinding.viewFinder.translationY = 0f

                if (uiBinding.photoViewButton?.visibility != View.VISIBLE) {
                    uiBinding.photoViewButton?.visibility = View.VISIBLE
                    uiBinding.photoViewButton?.alpha = 1f
                }

                return@post
            }

            // Enabled path: Half-Frame step is visually represented by the Left/Right split inside ExpressiveShutterButton
            uiBinding.tvHalfFrameStep?.visibility = View.GONE
            val stepText = if (host.halfFrameStep == 0) "1/2" else "2/2"
            if (uiBinding.tvHalfFrameStep?.text != stepText) {
                uiBinding.tvHalfFrameStep?.text = stepText
            }

            // Hide thumbnail button during processing of Shot 1 and throughout Shot 2
            if (host.halfFrameStep == 1) {
                uiBinding.photoViewButton?.visibility = View.INVISIBLE
            } else if (!host.isProcessing) {
                uiBinding.photoViewButton?.visibility = View.VISIBLE
                uiBinding.photoViewButton?.alpha = 1f
            }

            val stageW = vfBinding.viewFinderStage.width.toFloat()
            val stageH = vfBinding.viewFinderStage.height.toFloat()
            if (stageW <= 0f || stageH <= 0f) return@post

            val gapWidth = (stageW * 0.12f).coerceAtLeast(60f)
            val scale = stageW / (stageW + gapWidth)
            val shift = (gapWidth * scale) / 2f

            if (edgeTopView.visibility != View.VISIBLE) edgeTopView.visibility = View.VISIBLE
            if (edgeBottomView.visibility != View.VISIBLE) edgeBottomView.visibility = View.VISIBLE

            vfBinding.viewFinder.scaleX = scale
            vfBinding.viewFinder.scaleY = scale

            val layout = prefs.getString(SettingsFragment.KEY_HALF_FRAME_LAYOUT, SettingsFragment.HALF_FRAME_LAYOUT_SBS)
            val isTopBottom = layout == SettingsFragment.HALF_FRAME_LAYOUT_TB

            val baseShift = if (host.halfFrameStep == 0) -shift else shift
            val targetShift = if (isTopBottom) -baseShift else baseShift

            if (animate) {
                performHalfFrameAdvanceAnimation(targetShift, stageW, gapWidth * scale, isTopBottom)
            } else {
                host.isHalfFrameUiAnimating = false
                vfBinding.viewFinder.animate().cancel()
                vfBinding.viewFinder.translationX = targetShift
                vfBinding.viewFinder.alpha = 1f
                gapView.visibility = View.GONE
                snapshotView.visibility = View.GONE
            }

            if (vfBinding.viewFinder.translationY != 0f) {
                vfBinding.viewFinder.translationY = 0f
            }
        }
    }

    private fun performHalfFrameAdvanceAnimation(targetShift: Float, stageW: Float, gapWidth: Float, isTopBottom: Boolean) {
        val vfBinding = host.fragmentCameraBinding ?: return
        val vf = vfBinding.viewFinder
        val snapshot = vfBinding.halfFrameSnapshot ?: return
        val gap = vfBinding.halfFrameGapIndicator ?: return

        // Direction logic: SBS moves Left (-X), TB moves Right (+X)
        val moveFactor = if (isTopBottom) 1f else -1f
        val duration = 500L
        val interpolator = android.view.animation.AccelerateDecelerateInterpolator()

        // 1. Snapshot logic
        val bitmap = host.pendingVfSnapshot
        if (bitmap != null) {
            snapshot.setImageBitmap(bitmap)
            snapshot.visibility = View.VISIBLE
            snapshot.translationX = vf.translationX
            snapshot.scaleX = vf.scaleX
            snapshot.scaleY = vf.scaleY
            if (snapshot.layoutParams.width != vf.width || snapshot.layoutParams.height != vf.height) {
                snapshot.layoutParams.width = vf.width
                snapshot.layoutParams.height = vf.height
                snapshot.requestLayout()
            }
        }

        // 2. Gap logic (only show when moving FROM Shot 1 TO Shot 2)
        if (host.halfFrameStep == 1) {
            gap.visibility = View.VISIBLE
            val scaledVfWidth = vf.width * vf.scaleX
            gap.translationX = if (isTopBottom) {
                vf.translationX - gapWidth
            } else {
                vf.translationX + scaledVfWidth
            }
            val targetWidth = gapWidth.toInt()
            val targetHeight = (vf.height * vf.scaleY).toInt()
            if (gap.layoutParams.width != targetWidth || gap.layoutParams.height != targetHeight) {
                gap.layoutParams.width = targetWidth
                gap.layoutParams.height = targetHeight
                gap.requestLayout()
            }
        } else {
            gap.visibility = View.GONE
        }

        // 3. Prepare Live ViewFinder to slide in from opposite side
        vf.animate().cancel()
        host.isHalfFrameUiAnimating = true
        vf.translationX = targetShift - (moveFactor * stageW)

        // 4. Perform animations
        snapshot.animate()
            .translationX(snapshot.translationX + (moveFactor * stageW))
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction {
                snapshot.visibility = View.GONE
                snapshot.setImageBitmap(null)
                host.pendingVfSnapshot = null
            }
            .start()

        if (gap.visibility == View.VISIBLE) {
            gap.animate()
                .translationX(gap.translationX + (moveFactor * stageW))
                .setDuration(duration)
                .setInterpolator(interpolator)
                .withEndAction { gap.visibility = View.GONE }
                .start()
        }

        vf.animate()
            .translationX(targetShift)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction { host.isHalfFrameUiAnimating = false }
            .start()

        animateFilmEdgeRoll(isTopBottom, duration)
    }

    private fun animateFilmEdgeRoll(isTopBottom: Boolean, duration: Long = 500L) {
        val vfBinding = host.fragmentCameraBinding ?: return
        val topEdge = vfBinding.halfFrameFilmEdgeTop ?: return
        val bottomEdge = vfBinding.halfFrameFilmEdgeBottom ?: return

        topEdge.animate().cancel()
        bottomEdge.animate().cancel()

        val periodPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 30f, host.context.resources.displayMetrics
        )
        val rollDistance = periodPx * 10
        val moveFactor = if (isTopBottom) 1f else -1f
        val dist = moveFactor * rollDistance

        val interpolator = android.view.animation.AccelerateDecelerateInterpolator()

        topEdge.animate()
            .translationX(dist)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction { topEdge.translationX = 0f }
            .start()

        bottomEdge.animate()
            .translationX(dist)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction { bottomEdge.translationX = 0f }
            .start()
    }
}

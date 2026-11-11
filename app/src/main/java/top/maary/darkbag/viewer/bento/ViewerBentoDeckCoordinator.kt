package top.maary.darkbag.viewer.bento

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentImageViewerBinding
import top.maary.darkbag.fragments.ImageViewerAdapter
import top.maary.darkbag.fragments.ImageViewerFragment
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.bento.BentoContainerRole
import top.maary.darkbag.ui.bento.BentoTrayCard
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 查看器 Bento 底栏协调器 (ViewerBentoDeckCoordinator).
 * 封装格式切换 (RAW/JPG)、多模态视频底栏、胶卷条进度、多摄变焦胶囊栏以及 Look 衍生版本快速轮换的遥测与视觉渲染。
 * 严格遵循 Invariant 5 (Bento Matrix & M3 Expressive 同心圆角陶瓷胶囊规范)。
 */
class ViewerBentoDeckCoordinator(
    private val binding: FragmentImageViewerBinding,
    private val getAdapter: () -> ImageViewerAdapter?,
    private val getCurrentItemIndex: () -> Int,
    private val onControlsVisibilityRequested: () -> Unit,
    private val onMultiCamCollageRequested: (ImageGroup) -> Unit,
    private val onOpenExportHubRequested: () -> Unit,
    private val onFormatToggleRequested: () -> Unit,
    private val onDerivativeCycleRequested: () -> Unit,
    private val onMotionLiveToggleRequested: () -> Unit
) {

    companion object {
        val LOOK_DOT_COLORS = intArrayOf(
            Color.parseColor("#FFD0BC"),
            Color.parseColor("#80D8FF"),
            Color.parseColor("#FFE57F"),
            Color.parseColor("#B9F6CA"),
            Color.parseColor("#EA80FC"),
            Color.parseColor("#FF8A80")
        )

        fun formatVideoTime(millis: Long): String {
            val totalSec = (millis / 1000).coerceAtLeast(0)
            val minutes = totalSec / 60
            val seconds = totalSec % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun setupListeners() {
        binding.btnFormatSwitch?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onFormatToggleRequested()
        }
        binding.btnFormatSwitchCompact?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onFormatToggleRequested()
        }
        binding.btnDerivativeCycle?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onDerivativeCycleRequested()
        }
        binding.btnDerivativeCycleCompact?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onDerivativeCycleRequested()
        }
        binding.btnMotionLive?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onMotionLiveToggleRequested()
        }
        binding.btnVideoExportHub?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onOpenExportHubRequested()
        }
    }

    fun updateFormatBentoRow(
        isAdjusted: Boolean,
        isMotionPhotoAutoPlay: Boolean,
        currentCinemaDngFrame: Pair<Int, Uri?>?
    ) {
        val adapter = getAdapter() ?: return
        if (adapter.itemCount == 0) return
        val currentIndex = getCurrentItemIndex()
        if (currentIndex !in 0 until adapter.itemCount) return
        val currentGroup = adapter.getGroup(currentIndex)
        val ctx = binding.root.context
        val density = ctx.resources.displayMetrics.density

        (binding.viewerBentoDeck as? BentoTrayCard)?.let { deckCard ->
            val role = when {
                currentGroup.isHalfFrame() -> BentoContainerRole.TERTIARY_CONTAINER
                currentGroup.isMultiCamera -> BentoContainerRole.SECONDARY_CONTAINER
                else -> BentoContainerRole.SURFACE_CONTAINER_LOW
            }
            deckCard.setContainerRole(role, animated = true)
        }

        val currentFormat = adapter.getSelectedFormat(currentIndex)
        val isRawSelected = currentFormat == ImageViewerAdapter.FORMAT_DNG

        val lenses = if (currentGroup.isMultiCamera) adapter.getMultiCameraLenses(currentGroup) else emptyList()
        val activeLensIndex = if (currentGroup.isMultiCamera && lenses.isNotEmpty()) {
            adapter.getSelectedLensIndex(currentIndex).coerceIn(0, lenses.size - 1)
        } else 0
        val activeLens = if (currentGroup.isMultiCamera && lenses.isNotEmpty()) lenses[activeLensIndex] else null
        val activeLensTag = activeLens?.lensTag?.ifEmpty {
            String.format(Locale.US, "%.1fx", activeLens.multiplier)
        } ?: "1.0x"

        val hasDng = if (currentGroup.isMultiCamera) activeLens?.dngUri != null else currentGroup.hasMasterRaw
        val derivatives = adapter.getDerivativeUris(currentGroup)
        val hasDerivatives = if (currentGroup.isMultiCamera) activeLens?.jpgUri != null else derivatives.isNotEmpty()

        val isRawVideo = currentGroup.isRawVideo || currentGroup.rawVideoUri != null
        val isCinemaDng = currentGroup.isCinemaDng || currentGroup.cinemaDngFolderUri != null || currentGroup.cinemaDngFirstFrameUri != null || currentGroup.cinemaDngFrameUris.isNotEmpty()
        val activeDerivIdx = adapter.getSelectedDerivativeIndex(currentGroup.baseName).coerceIn(0, (derivatives.size - 1).coerceAtLeast(0))
        val activeDerivativeUri = derivatives.getOrNull(activeDerivIdx) ?: currentGroup.jpgUri ?: currentGroup.mp4VideoUri
        val isMp4Active = !isRawSelected && activeDerivativeUri != null && (activeDerivativeUri.toString().endsWith(".mp4", ignoreCase = true) || currentGroup.isMp4Video)

        val colorPrimary = MaterialColors.getColor(ctx, android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorOnPrimaryContainer = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE)
        val colorSecondaryContainer = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondaryContainer, Color.DKGRAY)
        val colorOnSecondaryContainer = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.WHITE)
        val colorSurfaceContainerHigh = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.GRAY)
        val colorOnSurfaceVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)
        val colorOutlineVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)

        val showDerivCycle = !currentGroup.isMultiCamera && !isRawSelected && derivatives.size >= 2
        val showMotionLive = !currentGroup.isMultiCamera && currentGroup.isMotionPhoto && !isRawSelected && !isAdjusted
        val hasContextualAddons = showMotionLive

        if (hasContextualAddons) {
            binding.viewerFormatRow?.visibility = View.VISIBLE
            binding.btnFormatSwitch?.visibility = View.VISIBLE
            binding.btnFormatSwitchCompact?.visibility = View.GONE
            binding.btnDerivativeCycleCompact?.visibility = View.GONE
        } else {
            binding.viewerFormatRow?.visibility = View.GONE
            binding.btnFormatSwitchCompact?.visibility = View.VISIBLE
            binding.btnDerivativeCycleCompact?.visibility = if (showDerivCycle) View.VISIBLE else View.GONE
        }

        binding.btnFormatSwitch?.let { btn ->
            when {
                hasDng && hasDerivatives -> {
                    btn.isEnabled = !isAdjusted
                    btn.isClickable = !isAdjusted
                    if (currentGroup.isMultiCamera) {
                        btn.text = CameraBentoDeckView.formatProTelemetry(
                            label = "$activeLensTag · ${if (isRawSelected) "DNG" else "JPG"}",
                            value = "JPG ⇄ DNG",
                            isTabActive = isRawSelected,
                            isValueManual = false,
                            context = ctx
                        )
                    } else if (isRawSelected) {
                        val line1 = when {
                            isRawVideo -> "MASTER · RAWVID"
                            isCinemaDng -> "MASTER · CDNG"
                            else -> "MASTER · DNG"
                        }
                        val line2 = if (isRawVideo || currentGroup.mp4VideoUri != null) "⇄ SWITCH MP4" else "⇄ SWITCH JPG"
                        btn.text = CameraBentoDeckView.formatProTelemetry(
                            label = line1,
                            value = line2,
                            isTabActive = true,
                            isValueManual = false,
                            context = ctx
                        )
                    } else {
                        val line1 = if (isMp4Active) "VIEW · MP4" else "VIEW · JPG"
                        val line2 = when {
                            isRawVideo -> "⇄ SWITCH RAWVID"
                            isCinemaDng -> "⇄ SWITCH CDNG"
                            else -> "⇄ SWITCH DNG"
                        }
                        btn.text = CameraBentoDeckView.formatProTelemetry(
                            label = line1,
                            value = line2,
                            isTabActive = false,
                            isValueManual = false,
                            context = ctx
                        )
                    }

                    if (isRawSelected) {
                        btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                        btn.setTextColor(colorOnPrimaryContainer)
                        btn.strokeColor = ColorStateList.valueOf(colorPrimary)
                        btn.strokeWidth = (1.5f * density).toInt()
                        btn.cornerRadius = (10f * density).toInt()
                    } else {
                        btn.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                        btn.setTextColor(colorOnSecondaryContainer)
                        btn.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                        btn.strokeWidth = (1f * density).toInt()
                        btn.cornerRadius = (14f * density).toInt()
                    }
                }
                hasDng && !hasDerivatives -> {
                    btn.isEnabled = false
                    btn.isClickable = false
                    val line1 = if (currentGroup.isMultiCamera) "$activeLensTag · DNG" else "FORMAT"
                    val line2 = when {
                        isRawVideo -> "RAWVID ONLY"
                        isCinemaDng -> "CDNG ONLY"
                        else -> "DNG ONLY"
                    }
                    btn.text = CameraBentoDeckView.formatProTelemetry(
                        label = line1,
                        value = line2,
                        isTabActive = false,
                        isValueManual = false,
                        context = ctx
                    )
                    btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                    btn.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                    btn.strokeWidth = (1f * density).toInt()
                    btn.cornerRadius = (14f * density).toInt()
                }
                else -> {
                    btn.isEnabled = false
                    btn.isClickable = false
                    val line1 = if (currentGroup.isMultiCamera) "$activeLensTag · JPG" else "FORMAT"
                    val line2 = if (isMp4Active) "MP4 ONLY" else "JPG ONLY"
                    btn.text = CameraBentoDeckView.formatProTelemetry(
                        label = line1,
                        value = line2,
                        isTabActive = false,
                        isValueManual = false,
                        context = ctx
                    )
                    btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                    btn.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                    btn.strokeWidth = (1f * density).toInt()
                    btn.cornerRadius = (14f * density).toInt()
                }
            }
        }

        binding.btnFormatSwitchCompact?.let { btn ->
            val isRawAvailable = hasDng
            val rawLabel = when {
                isRawVideo -> "RAWVID"
                isCinemaDng -> "CDNG"
                else -> "RAW"
            }
            val nonRawLabel = if (isMp4Active) "MP4" else "JPG"

            if (isRawSelected) {
                btn.isEnabled = !isAdjusted
                btn.isClickable = !isAdjusted
                btn.text = rawLabel
                btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                btn.setTextColor(colorOnPrimaryContainer)
                btn.strokeColor = ColorStateList.valueOf(colorPrimary)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            } else if (isRawAvailable) {
                btn.isEnabled = !isAdjusted
                btn.isClickable = !isAdjusted
                btn.text = rawLabel
                btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.setTextColor(colorOnSurfaceVariant)
                btn.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                btn.strokeWidth = (1f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            } else {
                btn.isEnabled = false
                btn.isClickable = false
                btn.text = nonRawLabel
                btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.setTextColor(ColorUtils.setAlphaComponent(colorOnSurfaceVariant, 120))
                btn.strokeWidth = 0
                btn.cornerRadius = (14f * density).toInt()
            }
        }

        binding.btnDerivativeCycleCompact?.let { btn ->
            val showCompactLook = !hasContextualAddons && showDerivCycle
            btn.visibility = if (showCompactLook) View.VISIBLE else View.GONE
            if (showCompactLook) {
                val dotColor = LOOK_DOT_COLORS[activeDerivIdx % LOOK_DOT_COLORS.size]
                val dotColorAlpha = ColorUtils.setAlphaComponent(dotColor, 200)

                val text = "V${activeDerivIdx + 1} ●"
                val spannable = SpannableStringBuilder(text)
                val dotIdx = text.indexOf("●")
                if (dotIdx >= 0) {
                    spannable.setSpan(ForegroundColorSpan(dotColor), dotIdx, dotIdx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                btn.text = spannable
                btn.icon = null
                btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.setTextColor(colorOnSurfaceVariant)
                btn.strokeColor = ColorStateList.valueOf(dotColorAlpha)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            }
        }

        binding.btnDerivativeCycle?.let { btn ->
            val showExpandedLook = hasContextualAddons && showDerivCycle
            btn.visibility = if (showExpandedLook) View.VISIBLE else View.GONE
            if (showExpandedLook) {
                val dotColor = LOOK_DOT_COLORS[activeDerivIdx % LOOK_DOT_COLORS.size]
                val dotColorAlpha = ColorUtils.setAlphaComponent(dotColor, 200)

                val formatted = CameraBentoDeckView.formatProTelemetry(
                    label = "LOOK",
                    value = "V${activeDerivIdx + 1} / V${derivatives.size} ●",
                    isTabActive = false,
                    isValueManual = true,
                    context = ctx
                )
                val spannable = formatted as? SpannableStringBuilder ?: SpannableStringBuilder(formatted)
                val dotIdx = spannable.indexOf("●")
                if (dotIdx >= 0) {
                    spannable.setSpan(ForegroundColorSpan(dotColor), dotIdx, dotIdx + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                btn.text = spannable
                btn.icon = null
                btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                btn.strokeColor = ColorStateList.valueOf(dotColorAlpha)
                btn.strokeWidth = (1.5f * density).toInt()
                btn.cornerRadius = (14f * density).toInt()
            }
        }

        binding.btnMotionLive?.let { btn ->
            btn.visibility = if (showMotionLive) View.VISIBLE else View.GONE
            if (showMotionLive) {
                btn.text = CameraBentoDeckView.formatProTelemetry(
                    label = "LIVE PHOTO",
                    value = if (isMotionPhotoAutoPlay) "AUTO ON" else "PAUSED",
                    isTabActive = isMotionPhotoAutoPlay,
                    isValueManual = isMotionPhotoAutoPlay,
                    context = ctx
                )
                if (isMotionPhotoAutoPlay) {
                    btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                    btn.setTextColor(colorOnPrimaryContainer)
                    btn.strokeColor = ColorStateList.valueOf(colorPrimary)
                    btn.strokeWidth = (1.5f * density).toInt()
                    btn.cornerRadius = (12f * density).toInt()
                } else {
                    btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                    btn.setTextColor(colorOnSurfaceVariant)
                    btn.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
                    btn.strokeWidth = (1f * density).toInt()
                    btn.cornerRadius = (12f * density).toInt()
                }
            }
        }

        if (currentGroup.isMultiCamera && lenses.isNotEmpty()) {
            binding.viewerMulticamRow?.visibility = View.VISIBLE
            binding.multicamLensContainer?.let { container ->
                container.removeAllViews()
                for ((idx, lens) in lenses.withIndex()) {
                    val isSelected = (idx == activeLensIndex)
                    val baseTag = if (lens.lensTag.isNotBlank()) lens.lensTag else String.format(Locale.US, "%.1fx", lens.multiplier)
                    val lensLabel = when {
                        baseTag.contains("(") -> baseTag
                        lens.multiplier < 0.9f -> "$baseTag (UW)"
                        lens.multiplier in 0.9f..1.5f -> "$baseTag (W)"
                        lens.multiplier > 1.5f -> "$baseTag (T)"
                        else -> baseTag
                    }

                    val lensBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f).apply {
                            if (idx > 0) marginStart = (2f * density).roundToInt()
                            if (idx < lenses.size - 1) marginEnd = (2f * density).roundToInt()
                        }
                        text = lensLabel
                        textSize = 10.5f
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        insetTop = 0
                        insetBottom = 0
                        setPadding(0, 0, 0, 0)
                        minWidth = 0
                        minimumWidth = 0

                        if (isSelected) {
                            cornerRadius = (10f * density).roundToInt()
                            backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                            setTextColor(colorOnPrimaryContainer)
                            strokeColor = ColorStateList.valueOf(colorPrimary)
                            strokeWidth = (1.5f * density).roundToInt()
                        } else {
                            cornerRadius = (14f * density).roundToInt()
                            backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                            setTextColor(colorOnSurfaceVariant)
                            strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                            strokeWidth = 0
                        }

                        setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val curPos = getCurrentItemIndex()
                            if (adapter.getSelectedLensIndex(curPos) != idx) {
                                adapter.setSelectedLensIndex(curPos, idx)
                                onControlsVisibilityRequested()
                            }
                        }
                    }
                    container.addView(lensBtn)
                }
            }

            binding.btnMulticamCollage?.apply {
                text = CameraBentoDeckView.formatProTelemetry(
                    label = "⊞ COLLAGE",
                    value = "多摄拼图",
                    isTabActive = true,
                    isValueManual = false,
                    context = ctx
                )
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val curPos = getCurrentItemIndex()
                    val grp = adapter.getGroup(curPos)
                    if (grp.isMultiCamera) {
                        onMultiCamCollageRequested(grp)
                    }
                }
            }
        } else {
            binding.viewerMulticamRow?.visibility = View.GONE
            binding.multicamLensContainer?.removeAllViews()
        }

        val isVideoActive = isRawVideo || isCinemaDng || currentGroup.isMp4Video || currentGroup.mp4VideoUri != null || isMp4Active

        if (isVideoActive) {
            binding.viewerVideoDeck?.visibility = View.VISIBLE
            if (isCinemaDng) {
                binding.filmstripBentoContainer?.visibility = View.VISIBLE
                val frameUris = if (currentGroup.cinemaDngFrameUris.isNotEmpty()) {
                    currentGroup.cinemaDngFrameUris
                } else if (currentGroup.cinemaDngFirstFrameUri != null) {
                    listOf(currentGroup.cinemaDngFirstFrameUri)
                } else {
                    emptyList()
                }
                val activeIndex = currentCinemaDngFrame?.first ?: adapter.getActiveCinemaDngFrame(currentIndex)?.first ?: 0
                binding.filmstripBentoSeekbar?.setFrames(frameUris, activeIndex)
            } else {
                binding.filmstripBentoContainer?.visibility = View.GONE
            }

            val exportSub = if (isCinemaDng) "CDNG / MP4 / FRAME" else "MP4 / FRAME / DNG"
            binding.btnVideoExportHub?.text = CameraBentoDeckView.formatProTelemetry(
                label = "↗ EXPORT HUB",
                value = exportSub,
                isTabActive = false,
                isValueManual = false,
                context = ctx
            )

            val isPlaying = adapter.isVideoPlaying(currentIndex)
            val curMs = adapter.getVideoCurrentPosition(currentIndex)
            val totalMs = adapter.getVideoDuration(currentIndex)
            updateVideoPlaybackButton(isPlaying, curMs, totalMs)
        } else {
            binding.viewerVideoDeck?.visibility = View.GONE
        }
    }

    fun updateVideoPlaybackButton(isPlaying: Boolean, currentMs: Long, totalMs: Long) {
        val btn = binding.btnVideoPlayback ?: return
        val ctx = binding.root.context
        val density = ctx.resources.displayMetrics.density

        val colorPrimary = MaterialColors.getColor(ctx, android.R.attr.colorPrimary, Color.WHITE)
        val colorPrimaryContainer = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
        val colorOnPrimaryContainer = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE)
        val colorOutlineVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)

        val line1 = if (isPlaying) "❚❚ PAUSE" else "▶ PLAY"
        val line2 = if (totalMs > 0) {
            "${formatVideoTime(currentMs)} / ${formatVideoTime(totalMs)}"
        } else {
            val adapter = getAdapter()
            val currentIndex = getCurrentItemIndex()
            if (adapter != null && currentIndex in adapter.getGroups().indices) {
                val group = adapter.getGroup(currentIndex)
                if (group.isCinemaDng || group.cinemaDngFrameUris.isNotEmpty()) {
                    val count = group.cinemaDngFrameUris.size.takeIf { it > 0 } ?: group.cinemaDngFrameCount
                    val sec = (count / 24.0f).roundToInt()
                    "24 FPS · ${sec}s"
                } else if (group.isRawVideo) {
                    val fps = group.rawVideoFps.takeIf { it > 0 } ?: 24.0f
                    val sec = (group.rawVideoFrameCount / fps).roundToInt()
                    "${fps.toInt()} FPS · ${sec}s"
                } else {
                    "00:00 / 00:00"
                }
            } else {
                "00:00 / 00:00"
            }
        }

        btn.text = CameraBentoDeckView.formatProTelemetry(
            label = line1,
            value = line2,
            isTabActive = isPlaying,
            isValueManual = false,
            context = ctx
        )

        btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
        btn.setTextColor(colorOnPrimaryContainer)
        if (isPlaying) {
            btn.strokeColor = ColorStateList.valueOf(colorPrimary)
            btn.strokeWidth = (1.5f * density).toInt()
            btn.cornerRadius = (14f * density).toInt()
        } else {
            btn.strokeColor = ColorStateList.valueOf(colorOutlineVariant)
            btn.strokeWidth = (1f * density).toInt()
            btn.cornerRadius = (18f * density).toInt()
        }
    }
}

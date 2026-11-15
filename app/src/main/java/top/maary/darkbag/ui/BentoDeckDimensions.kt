package top.maary.darkbag.ui

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import kotlin.math.roundToInt

typealias BentoScreenTier = CameraBentoDeckView.BentoScreenTier
typealias BentoDimensions = CameraBentoDeckView.BentoDimensions

/**
 * Adaptive 3-tier Bento row heights, spacing, and dynamic weight-balancing helpers.
 *
 * 1. COMPACT tier (aspectRatio < 1.88f or maxDp < 720dp): tighter rows (38dp..68dp).
 * 2. STANDARD tier (aspectRatio in 1.88f..2.05f and maxDp in 720..840dp): balanced rows (44dp..78dp).
 * 3. TALL tier (aspectRatio > 2.05f and maxDp >= 840dp): spacious flagship rows (46dp..88dp).
 */
object BentoDeckDimensions {

    @JvmStatic
    fun resolveScreenTier(resources: Resources): BentoScreenTier {
        val metrics = resources.displayMetrics
        val maxDimension = maxOf(metrics.heightPixels, metrics.widthPixels)
        val minDimension = minOf(metrics.heightPixels, metrics.widthPixels)
        val aspectRatio = if (minDimension > 0) maxDimension.toFloat() / minDimension.toFloat() else 1.77f
        val maxDp = maxOf(resources.configuration.screenHeightDp, resources.configuration.screenWidthDp)
        return BentoScreenTier.resolve(aspectRatio, maxDp)
    }

    @JvmStatic
    fun getBentoDimensions(resources: Resources): BentoDimensions {
        return when (resolveScreenTier(resources)) {
            BentoScreenTier.COMPACT -> BentoDimensions(
                shutterRowDp = 68,
                lensRowDp = 38,
                recipeRowDp = 38,
                proBarDp = 38,
                dialWheelDp = 38,
                cardPaddingDp = 5,
                rowMarginDp = 3
            )
            BentoScreenTier.STANDARD -> BentoDimensions(
                shutterRowDp = 78,
                lensRowDp = 48,
                recipeRowDp = 48,
                proBarDp = 44,
                dialWheelDp = 44,
                cardPaddingDp = 8,
                rowMarginDp = 5
            )
            BentoScreenTier.TALL -> BentoDimensions(
                shutterRowDp = 88,
                lensRowDp = 54,
                recipeRowDp = 54,
                proBarDp = 48,
                dialWheelDp = 46,
                cardPaddingDp = 10,
                rowMarginDp = 6
            )
        }
    }

    @JvmStatic
    fun applyDynamicRowHeights(view: View) {
        val resources = view.resources
        val density = resources.displayMetrics.density
        val dims = getBentoDimensions(resources)
        val tier = resolveScreenTier(resources)

        val proBarPx = (dims.proBarDp * density).roundToInt()
        val recipeRowPx = (dims.recipeRowDp * density).roundToInt()
        val lensRowPx = (dims.lensRowDp * density).roundToInt()
        val shutterRowPx = (dims.shutterRowDp * density).roundToInt()
        val dialWheelPx = (dims.dialWheelDp * density).roundToInt()
        val cardPaddingPx = (dims.cardPaddingDp * density).roundToInt()
        val rowMarginPx = (dims.rowMarginDp * density).roundToInt()

        // 1. Pro parameter bar
        applyHeightAndBottomMargin(view.findViewById(R.id.pro_info_bar_card), proBarPx, rowMarginPx)

        // 2. Dial wheel panel & view
        view.findViewById<View>(R.id.manual_controls_root)?.let { v ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && lp.bottomMargin != rowMarginPx) {
                lp.bottomMargin = rowMarginPx
                v.layoutParams = lp
            }
        }
        view.findViewById<View>(R.id.dial_wheel_view)?.let { v ->
            val lp = v.layoutParams
            if (lp != null && lp.height != dialWheelPx) {
                lp.height = dialWheelPx
                v.layoutParams = lp
            }
        }

        // 3. Film recipe row
        applyHeightAndBottomMargin(view.findViewById(R.id.bento_film_row), recipeRowPx, rowMarginPx)

        // 4. Lens control row
        applyHeightAndBottomMargin(view.findViewById(R.id.lens_control_row), lensRowPx, rowMarginPx)

        // 5. Shutter row
        view.findViewById<View>(R.id.bento_shutter_row)?.let { v ->
            val lp = v.layoutParams
            if (lp != null && lp.height != shutterRowPx) {
                lp.height = shutterRowPx
                v.layoutParams = lp
            }
        }

        // 6. Photo thumbnail card width adjustment
        view.findViewById<View>(R.id.photo_view_card)?.let { v ->
            val lp = v.layoutParams
            val photoWidthDp = when (tier) {
                BentoScreenTier.COMPACT -> 66
                BentoScreenTier.TALL -> 76
                BentoScreenTier.STANDARD -> 72
            }
            val photoWidthPx = (photoWidthDp * density).roundToInt()
            if (lp != null && lp.width != photoWidthPx) {
                lp.width = photoWidthPx
                v.layoutParams = lp
            }
        }

        // 7. Right Bento stack width adjustment
        view.findViewById<View>(R.id.bento_right_stack)?.let { v ->
            val lp = v.layoutParams
            val stackWidthDp = when (tier) {
                BentoScreenTier.COMPACT -> 92
                BentoScreenTier.STANDARD -> 98
                BentoScreenTier.TALL -> 102
            }
            val stackWidthPx = (stackWidthDp * density).roundToInt()
            if (lp != null && lp.width != stackWidthPx) {
                lp.width = stackWidthPx
                v.layoutParams = lp
            }
        }

        // 8. Capture button & weighted container layout (Bento Tofu Block)
        view.findViewById<View>(R.id.camera_capture_button)?.let { v ->
            val isBento = view.id == R.id.bento_shutter_row ||
                view.findViewById<View>(R.id.bento_shutter_row) != null
            if (isBento) {
                val lp = v.layoutParams
                if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    v.layoutParams = lp
                }
                (v.parent as? ViewGroup)?.let { parent ->
                    val parentLp = parent.layoutParams as? LinearLayout.LayoutParams
                    if (parentLp != null) {
                        var parentChanged = false
                        if (parentLp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                            parentLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                            parentChanged = true
                        }
                        if (parentLp.weight != 1f) {
                            parentLp.weight = 1f
                            parentChanged = true
                        }
                        if (parentChanged) {
                            parent.layoutParams = parentLp
                        }
                    }
                }
            }
        }

        // 9. Outer Card Padding
        val cardView = (view as? MaterialCardView)
            ?: (view.parent as? MaterialCardView)
            ?: (view.findViewById<View>(R.id.bottom_island_card) as? MaterialCardView)
        cardView?.setContentPadding(cardPaddingPx, cardPaddingPx, cardPaddingPx, cardPaddingPx)
    }

    @JvmStatic
    fun applyDynamicRowHeights(binding: CameraUiContainerBinding) {
        val root = binding.bottomIslandCard ?: binding.bentoDeckView ?: binding.root
        applyDynamicRowHeights(root)
    }

    @JvmStatic
    fun updateRowBWeight(view: View?, fixedWidthPx: Int, expandFilmPills: Boolean) {
        val lp = view?.layoutParams as? LinearLayout.LayoutParams ?: return
        val targetWidth = if (expandFilmPills) 0 else fixedWidthPx
        val targetWeight = if (expandFilmPills) 1f else 0f
        if (lp.width != targetWidth || lp.weight != targetWeight) {
            lp.width = targetWidth
            lp.weight = targetWeight
            view.layoutParams = lp
        }
    }

    @JvmStatic
    fun updateRowCWeight(view: View?, sideBtnWidthPx: Int, expandLensSideButtons: Boolean) {
        val lp = view?.layoutParams as? LinearLayout.LayoutParams ?: return
        val targetWidth = if (expandLensSideButtons) 0 else sideBtnWidthPx
        val targetWeight = if (expandLensSideButtons) 1f else 0f
        if (lp.width != targetWidth || lp.weight != targetWeight) {
            lp.width = targetWidth
            lp.weight = targetWeight
            view.layoutParams = lp
        }
    }

    private fun applyHeightAndBottomMargin(view: View?, heightPx: Int, bottomMarginPx: Int) {
        val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var changed = false
        if (lp.height != heightPx) {
            lp.height = heightPx
            changed = true
        }
        if (lp.bottomMargin != bottomMarginPx) {
            lp.bottomMargin = bottomMarginPx
            changed = true
        }
        if (changed) {
            view.layoutParams = lp
        }
    }
}

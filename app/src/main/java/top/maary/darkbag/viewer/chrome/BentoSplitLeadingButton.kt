package top.maary.darkbag.viewer.chrome

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Leading half of the custom Bento split action button:
 * keeps asymmetric [18dp, 5dp, 5dp, 18dp] corners and subtly morphs outer corners on press.
 */
class BentoSplitLeadingButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonTonalStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    private val outerRestPx = 18f * density
    private val outerPressedPx = 13f * density
    private val innerPx = 5f * density

    init {
        strokeWidth = 0
        applyLeadingShape(outerRestPx)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        applyLeadingShape(if (isPressed) outerPressedPx else outerRestPx)
    }

    private fun applyLeadingShape(outerRadiusPx: Float) {
        shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(outerRadiusPx)
            .setBottomLeftCornerSize(outerRadiusPx)
            .setTopRightCornerSize(innerPx)
            .setBottomRightCornerSize(innerPx)
            .build()
    }
}

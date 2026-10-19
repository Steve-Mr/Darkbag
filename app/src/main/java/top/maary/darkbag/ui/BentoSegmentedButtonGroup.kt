package top.maary.darkbag.ui

import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * BentoSegmentedButtonGroup overrides MaterialButtonToggleGroup's aggressive
 * updateChildShapes() so that all child buttons maintain concentric rounded corners
 * (e.g. 14dp unselected, 10dp active squircle) at all times, without right-angle (0dp) edges.
 */
class BentoSegmentedButtonGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonToggleGroupStyle
) : MaterialButtonToggleGroup(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    var activeCornerRadiusPx: Float = 10f * density
    var inactiveCornerRadiusPx: Float = 14f * density

    init {
        addOnButtonCheckedListener { _, _, _ ->
            enforceConcentricShapes()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        enforceConcentricShapes()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        enforceConcentricShapes()
    }

    override fun check(id: Int) {
        super.check(id)
        enforceConcentricShapes()
    }

    /**
     * Enforces individual concentric corner radii on each child MaterialButton,
     * overriding any 0dp right-angle resets from MaterialButtonToggleGroup.
     */
    fun enforceConcentricShapes() {
        val testBounds = RectF(0f, 0f, 100f, 100f)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is MaterialButton) {
                val isActive = (child.id == checkedButtonId) || child.isChecked
                val targetRadius = if (isActive) activeCornerRadiusPx else inactiveCornerRadiusPx
                val currentRadius = child.shapeAppearanceModel.topLeftCornerSize.getCornerSize(testBounds)
                if (currentRadius != targetRadius) {
                    child.shapeAppearanceModel = ShapeAppearanceModel.builder()
                        .setAllCornerSizes(targetRadius)
                        .build()
                }
            }
        }
    }
}

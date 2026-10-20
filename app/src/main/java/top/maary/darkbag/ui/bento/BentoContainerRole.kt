package top.maary.darkbag.ui.bento

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.annotation.AttrRes
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.modes.CaptureMode

/**
 * Semantic container roles defined by the Material 3 Expressive color system.
 * Used by Bento trays and floating decks across Darkbag.
 */
enum class BentoContainerRole(@AttrRes val attrResId: Int) {
    SURFACE_CONTAINER(com.google.android.material.R.attr.colorSurfaceContainer),
    SURFACE_CONTAINER_HIGH(com.google.android.material.R.attr.colorSurfaceContainerHigh),
    SURFACE_CONTAINER_LOW(com.google.android.material.R.attr.colorSurfaceContainerLow),
    PRIMARY_CONTAINER(com.google.android.material.R.attr.colorPrimaryContainer),
    SECONDARY_CONTAINER(com.google.android.material.R.attr.colorSecondaryContainer),
    TERTIARY_CONTAINER(com.google.android.material.R.attr.colorTertiaryContainer),
    ERROR_CONTAINER(com.google.android.material.R.attr.colorErrorContainer);

    fun resolveColor(context: Context, defaultColor: Int = Color.DKGRAY): Int {
        return MaterialColors.getColor(context, attrResId, defaultColor)
    }

    fun resolveColor(view: View, defaultColor: Int = Color.DKGRAY): Int {
        return MaterialColors.getColor(view, attrResId, defaultColor)
    }

    companion object {
        @JvmStatic
        fun fromCaptureMode(mode: CaptureMode): BentoContainerRole {
            return when {
                mode.isHalfFrame -> TERTIARY_CONTAINER
                mode == CaptureMode.MULTI_CAMERA -> SECONDARY_CONTAINER
                else -> SURFACE_CONTAINER
            }
        }

        @JvmStatic
        fun fromAttributeIndex(index: Int): BentoContainerRole {
            return when (index) {
                0 -> SURFACE_CONTAINER
                1 -> SURFACE_CONTAINER_HIGH
                2 -> SURFACE_CONTAINER_LOW
                3 -> PRIMARY_CONTAINER
                4 -> SECONDARY_CONTAINER
                5 -> TERTIARY_CONTAINER
                6 -> ERROR_CONTAINER
                else -> SURFACE_CONTAINER
            }
        }
    }
}

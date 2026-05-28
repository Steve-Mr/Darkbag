package top.maary.darkbag.utils

object LayoutUtils {
    fun isTopBottom(layout: String?): Boolean {
        if (layout == null) return false
        return layout == "TB" ||
               layout.contains("top", ignoreCase = true) ||
               layout == "Top-bottom" ||
               layout == "上下排列"
    }

    fun isSideBySide(layout: String?): Boolean {
        if (layout == null) return true // Default to SBS
        return layout == "SBS" ||
               layout.contains("side", ignoreCase = true) ||
               layout == "Side-by-side" ||
               layout == "左右排列" ||
               !isTopBottom(layout)
    }
}

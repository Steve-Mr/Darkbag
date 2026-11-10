package top.maary.darkbag.models

import java.io.File

/**
 * 工坊画廊项目模型，代表独立导入的 DNG 底片或半格成片组。
 */
sealed class PlaygroundItem {
    abstract val mainFile: File
    abstract val aspectRatio: String?

    data class Single(
        val file: File,
        val derivativeFiles: List<File> = emptyList(),
        override val aspectRatio: String? = null
    ) : PlaygroundItem() {
        override val mainFile: File = file
    }

    data class Group(
        val jpgFile: File,
        val dng1: File,
        val dng2: File,
        val derivativeFiles: List<File> = emptyList(),
        var isExpanded: Boolean = false,
        override val aspectRatio: String? = null
    ) : PlaygroundItem() {
        override val mainFile: File = jpgFile
    }
}

package top.maary.darkbag.viewer.editor

import top.maary.darkbag.models.ImageGroup

/**
 * 图像编辑器单向数据流意图 (DarkroomEditorUserIntent).
 */
sealed interface DarkroomEditorUserIntent {
    /** 进入编辑模式 */
    data class EnterEditMode(
        val group: ImageGroup,
        val dngBytes1: ByteArray? = null,
        val dngBytes2: ByteArray? = null
    ) : DarkroomEditorUserIntent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EnterEditMode
            if (group != other.group) return false
            return true
        }

        override fun hashCode(): Int = group.hashCode()
    }

    /** 退出编辑模式 (apply = true 保留修改，apply = false 恢复进入前配置) */
    data class ExitEditMode(val apply: Boolean) : DarkroomEditorUserIntent

    /** 切换调整维度 Tab (0: Light, 1: Range, 2: Tone, 3: Color) */
    data class SelectTab(val tabIndex: Int) : DarkroomEditorUserIntent

    /** 半格模式下切换选中的单帧 (0: Frame 1, 1: Frame 2) */
    data class SelectHalfFrameIndex(val index: Int) : DarkroomEditorUserIntent

    /** 更新主滑块数值 */
    data class UpdateSlider1(val value: Float) : DarkroomEditorUserIntent

    /** 更新副滑块数值 */
    data class UpdateSlider2(val value: Float) : DarkroomEditorUserIntent

    /** 设置 3D LUT 文件名 */
    data class SetLut(val lutFileName: String?) : DarkroomEditorUserIntent

    /** 设置 Log 曲线名称 */
    data class SetLog(val logName: String?) : DarkroomEditorUserIntent

    /** 设置漏光/眩光类型 */
    data class SetFlareType(val flareType: Int) : DarkroomEditorUserIntent

    /** 切换时间戳水印开关 */
    object ToggleTimestamp : DarkroomEditorUserIntent

    /** 切换半格两帧左右/上下互换 */
    object ToggleSwap : DarkroomEditorUserIntent

    /** 重置所有调色参数至基准状态 */
    object ResetAdjustments : DarkroomEditorUserIntent

    /** 缓存 DNG 字节数组 */
    data class SetDngBytes(
        val bytes1: ByteArray?,
        val bytes2: ByteArray?
    ) : DarkroomEditorUserIntent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            return true
        }

        override fun hashCode(): Int = 0
    }

    /** 更新当前激活的项目 (如滑动 ViewPager 切换当前图片) */
    data class SetActiveGroup(val group: ImageGroup) : DarkroomEditorUserIntent
}

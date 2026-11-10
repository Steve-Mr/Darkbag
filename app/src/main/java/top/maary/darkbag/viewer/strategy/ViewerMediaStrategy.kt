package top.maary.darkbag.viewer.strategy

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import top.maary.darkbag.models.EditConfig
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository

/**
 * 图像查看器数据源多态策略契约 (ViewerMediaStrategy).
 * 遵循 Architectural Invariant 3 (策略模式隔离业务) 与 Invariant 4 (UDF / 单一职责).
 * 彻底消灭 PlaygroundViewerFragment 脆弱子类继承，使单一的 ImageViewerFragment
 * 能够无缝适配 MediaStore 系统相册与 App 沙盒文件目录。
 */
interface ViewerMediaStrategy {
    /** 是否支持画廊多选缩略图网格视图 (暗房支持，工坊沙盒不支持) */
    val isGallerySupported: Boolean

    /** 是否为工坊应用私有沙盒模式 */
    val isSandbox: Boolean

    /**
     * 策略专属的初次加载逻辑。
     * - MediaStore 返回 null，由 DarkroomGalleryViewModel 响应式加载；
     * - 沙盒模式解析 Bundle 参数并从私有目录直接构建并返回 ImageGroup 列表。
     */
    suspend fun loadInitialGroups(
        context: Context,
        args: Bundle?,
        repository: ImageRepository
    ): List<ImageGroup>?

    /**
     * 保存调色与后处理产物。
     * - MediaStore 模式通过 MediaStoreExportSink 落地并刷新相册索引；
     * - 沙盒模式保存至 filesDir/playground_dngs/ 并维护半格伴侣 DNG 文件名关联。
     */
    suspend fun saveEdit(
        context: Context,
        group: ImageGroup,
        config: EditConfig,
        isReplacement: Boolean,
        repository: ImageRepository,
        cachedBytes1: ByteArray?,
        cachedBytes2: ByteArray?
    ): Boolean

    /**
     * 删除媒体项目。
     * - MediaStore 模式通过 ContentResolver / MediaStore API 安全删除；
     * - 沙盒模式直接删除私有文件。
     */
    suspend fun deleteImage(
        context: Context,
        group: ImageGroup,
        targetUris: List<Uri>?,
        deleteGroup: Boolean,
        repository: ImageRepository
    ): Boolean

    /**
     * 显示与该数据源匹配的操作/保存菜单 (PopupMenu)。
     */
    fun showSaveMenu(
        context: Context,
        anchor: View,
        currentGroup: ImageGroup,
        onAction: (ViewerSaveAction) -> Unit
    )

    /**
     * 响应放弃未保存修改。若返回 true 表示策略已自行处理退出逻辑 (如沙盒双图未保存直接回退)。
     */
    fun handleDiscard(
        currentGroup: ImageGroup,
        groups: List<ImageGroup>,
        args: Bundle?
    ): Boolean
}

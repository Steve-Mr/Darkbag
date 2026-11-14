package top.maary.darkbag.ui.viewer

import androidx.recyclerview.widget.DiffUtil
import top.maary.darkbag.models.ImageGroup

/**
 * 图像查看器差异计算回调 (ImageViewerDiffCallback).
 * 精准检测 baseName、RAW/JPG URI、编辑配置及视频序列变动，触发精细化 Payload 刷新。
 */
class ImageViewerDiffCallback : DiffUtil.ItemCallback<ImageGroup>() {

    override fun areItemsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean =
        oldItem.baseName == newItem.baseName

    override fun areContentsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean =
        oldItem == newItem &&
        oldItem.metadataLoaded == newItem.metadataLoaded &&
        oldItem.isMotionPhoto == newItem.isMotionPhoto &&
        oldItem.isCinemaDng == newItem.isCinemaDng &&
        oldItem.cinemaDngFrameUris == newItem.cinemaDngFrameUris &&
        oldItem.mp4VideoUri == newItem.mp4VideoUri &&
        oldItem.derivativeMp4Uris == newItem.derivativeMp4Uris &&
        oldItem.derivativeJpgUris == newItem.derivativeJpgUris &&
        oldItem.cinemaDngFolderUri == newItem.cinemaDngFolderUri

    override fun getChangePayload(oldItem: ImageGroup, newItem: ImageGroup): Any? {
        val payloads = mutableSetOf<String>()
        if (oldItem.jpgUri != newItem.jpgUri) payloads.add("JPG_URI_CHANGED")
        if (oldItem.dngUri != newItem.dngUri || oldItem.dngUri1 != newItem.dngUri1 || oldItem.dngUri2 != newItem.dngUri2) {
            payloads.add("DNG_AVAILABILITY_CHANGED")
        }
        if (oldItem.metadataLoaded != newItem.metadataLoaded) payloads.add("METADATA_LOADED")
        if (oldItem.lastModified != newItem.lastModified) payloads.add("LAST_MODIFIED_CHANGED")
        if (oldItem.editConfig != newItem.editConfig) payloads.add("EDIT_CONFIG_CHANGED")
        if (oldItem.isMotionPhoto != newItem.isMotionPhoto) payloads.add("MOTION_PHOTO_CHANGED")
        if (oldItem.cinemaDngFolderUri != newItem.cinemaDngFolderUri || oldItem.isCinemaDng != newItem.isCinemaDng || oldItem.cinemaDngFrameUris != newItem.cinemaDngFrameUris) {
            payloads.add("CINEMADNG_CHANGED")
        }
        if (oldItem.mp4VideoUri != newItem.mp4VideoUri || oldItem.derivativeMp4Uris != newItem.derivativeMp4Uris) {
            payloads.add("MP4_URI_CHANGED")
        }
        if (oldItem.allDerivativeUris != newItem.allDerivativeUris) payloads.add("DERIVATIVES_CHANGED")
        return payloads.ifEmpty { null }
    }

    /**
     * 合并前序已加载的元数据至新列表中，避免重复 IO 解析。
     */
    fun mergeMetadata(currentList: List<ImageGroup>, previousList: List<ImageGroup>): List<ImageGroup> {
        val metadataMap = previousList.filter { it.metadataLoaded }.associateBy { it.baseName }
        return currentList.map { item ->
            val cached = metadataMap[item.baseName]
            if (cached != null && !item.metadataLoaded) {
                item.copy(
                    metadataLoaded = true,
                    width = if (item.width > 0) item.width else cached.width,
                    height = if (item.height > 0) item.height else cached.height,
                    orientation = if (item.orientation != 0) item.orientation else cached.orientation,
                    editConfig = item.editConfig ?: cached.editConfig,
                    isMotionPhoto = item.isMotionPhoto || cached.isMotionPhoto,
                    motionPhotoPtsUs = if (item.motionPhotoPtsUs > 0) item.motionPhotoPtsUs else cached.motionPhotoPtsUs,
                    motionPhotoVideoLength = if (item.motionPhotoVideoLength > 0) item.motionPhotoVideoLength else cached.motionPhotoVideoLength,
                    rawVideoFps = if (item.rawVideoFps > 0) item.rawVideoFps else cached.rawVideoFps,
                    rawVideoFrameCount = if (item.rawVideoFrameCount > 0) item.rawVideoFrameCount else cached.rawVideoFrameCount,
                    rawVideoDurationMs = if (item.rawVideoDurationMs > 0) item.rawVideoDurationMs else cached.rawVideoDurationMs,
                    isCinemaDng = item.isCinemaDng || cached.isCinemaDng,
                    cinemaDngFrameCount = if (item.cinemaDngFrameCount > 0) item.cinemaDngFrameCount else cached.cinemaDngFrameCount
                )
            } else item
        }
    }
}

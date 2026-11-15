package top.maary.darkbag.fragments.gallery

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.fragments.DarkbagGalleryGridAdapter
import top.maary.darkbag.models.CaptureMetadata
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.GalleryAspectRatioHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

/**
 * 画廊网格 ViewHolder 视图绑定器 (GalleryGridItemBinder).
 * 负责单图与多摄物理镜头槽位的样式绑定、Bento 选中态圆角/描边反馈、格式徽章及 EXIF 遥测文本格式化。
 */
internal class GalleryGridItemBinder(
    private val context: Context,
    private val getRepository: () -> ImageRepository?,
    private val getAdapterScope: () -> CoroutineScope,
    private val getItemAt: (Int) -> ImageGroup,
    private val isSelectionMode: () -> Boolean,
    private val isKeySelected: (String) -> Boolean,
    private val getColumnCount: () -> Int,
    private val isWaterfallMode: () -> Boolean,
    private val onToggleSingleSelection: (ImageGroup) -> Unit,
    private val onToggleLensSelection: (ImageGroup, MultiCameraLensItem) -> Unit,
    private val onEnterSelectionAndToggleSingle: (ImageGroup) -> Unit,
    private val onEnterSelectionAndToggleLens: (ImageGroup, MultiCameraLensItem) -> Unit,
    private val onItemClick: (ImageGroup, Int, String?) -> Unit,
    private val onItemLongClick: (ImageGroup, Int, String?) -> Unit
) {
    private val metadataCache = LruCache<String, CaptureMetadata>(100)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun bindSingleMetadataAndListeners(
        holder: DarkbagGalleryGridAdapter.SingleViewHolder,
        group: ImageGroup,
        targetUri: Uri?
    ) {
        setupSingleBadges(holder, group)
        bindSingleColumnMode(holder, group, targetUri)
        bindSingleSelectionState(holder, group)

        holder.binding.cardRoot.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                val item = getItemAt(adapterPos)
                if (isSelectionMode()) {
                    onToggleSingleSelection(item)
                } else {
                    onItemClick(item, adapterPos, null)
                }
            }
        }

        holder.binding.cardRoot.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                val item = getItemAt(adapterPos)
                if (!isSelectionMode()) {
                    onEnterSelectionAndToggleSingle(item)
                } else {
                    onToggleSingleSelection(item)
                }
                onItemLongClick(item, adapterPos, null)
            }
            true
        }
    }

    fun bindSingleColumnMode(
        holder: DarkbagGalleryGridAdapter.SingleViewHolder,
        group: ImageGroup,
        targetUri: Uri?
    ) {
        val waterfall = isWaterfallMode()
        val columnCount = getColumnCount()
        if (waterfall) {
            GalleryAspectRatioHelper.applyAspectRatio(holder.binding.thumbnailView, group.aspectRatio, "4:3")
            holder.binding.thumbnailView.scaleType = ImageView.ScaleType.FIT_CENTER
            holder.binding.thumbnailView.adjustViewBounds = true

            holder.binding.layoutSingleColInfo.visibility = View.GONE
            holder.binding.badgesContainer.visibility = View.VISIBLE
        } else if (columnCount == 1) {
            GalleryAspectRatioHelper.applyAspectRatio(holder.binding.thumbnailView, "4:3")
            holder.binding.thumbnailView.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.binding.thumbnailView.adjustViewBounds = false

            holder.binding.layoutSingleColInfo.visibility = View.VISIBLE
            holder.binding.badgesContainer.visibility = View.GONE

            holder.binding.tvSingleCaptureTime.text =
                if (group.captureTime > 0L) dateFormatter.format(Date(group.captureTime)) else "--"
            holder.binding.tvSingleFilename.text = group.baseName

            val badgeText = resolveFormatBadgeText(group)
            if (badgeText.isNotBlank()) {
                holder.binding.tvSingleFormatPill.visibility = View.VISIBLE
                holder.binding.tvSingleFormatPill.text = badgeText
            } else {
                holder.binding.tvSingleFormatPill.visibility = View.GONE
            }

            bindSingleExifParams(holder, group, targetUri)
        } else {
            GalleryAspectRatioHelper.applyAspectRatio(holder.binding.thumbnailView, "1:1")
            holder.binding.thumbnailView.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.binding.thumbnailView.adjustViewBounds = false

            holder.binding.layoutSingleColInfo.visibility = View.GONE
            holder.binding.badgesContainer.visibility = View.VISIBLE
        }
    }

    private fun bindSingleExifParams(
        holder: DarkbagGalleryGridAdapter.SingleViewHolder,
        group: ImageGroup,
        targetUri: Uri?
    ) {
        if (targetUri == null) {
            holder.binding.tvSingleExifParams.text = "--"
            return
        }
        val cached = metadataCache.get(group.baseName)
        if (cached != null) {
            holder.binding.tvSingleExifParams.text = formatExifParams(cached)
            return
        }

        holder.binding.tvSingleExifParams.text = "--"
        val repo = getRepository() ?: return
        val groupBase = group.baseName
        getAdapterScope().launch {
            try {
                val meta = repo.getCaptureMetadata(targetUri)
                if (meta != null) {
                    metadataCache.put(groupBase, meta)
                    withContext(Dispatchers.Main) {
                        if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION &&
                            getItemAt(holder.bindingAdapterPosition).baseName == groupBase
                        ) {
                            holder.binding.tvSingleExifParams.text = formatExifParams(meta)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("DarkbagGalleryGridAdapter", "Failed to load metadata for $targetUri", e)
            }
        }
    }

    fun formatExifParams(meta: CaptureMetadata): String {
        val parts = mutableListOf<String>()
        val focal = meta.focalLengthIn35mmFilm ?: meta.focalLength?.toInt()
        if (focal != null && focal > 0) parts.add("${focal}mm")
        if (meta.fNumber != null && meta.fNumber > 0f) parts.add("ƒ/${String.format(Locale.US, "%.1f", meta.fNumber)}")
        if (meta.exposureTime != null && meta.exposureTime > 0L) parts.add(formatShutterSpeed(meta.exposureTime))
        if (meta.iso != null && meta.iso > 0) parts.add("ISO ${meta.iso}")
        return if (parts.isNotEmpty()) parts.joinToString(" · ") else "--"
    }

    private fun formatShutterSpeed(ns: Long): String {
        val sec = ns / 1_000_000_000.0
        return if (sec >= 1.0) {
            String.format(Locale.US, "%.1fs", sec)
        } else if (sec > 0.0) {
            val denom = round((1.0 / sec)).toInt()
            "1/${denom}s"
        } else "--"
    }

    fun bindSingleSelectionState(holder: DarkbagGalleryGridAdapter.SingleViewHolder, group: ImageGroup) {
        val selected = isKeySelected(group.baseName)
        val density = holder.itemView.resources.displayMetrics.density
        val primaryColor = MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)

        if (isSelectionMode()) {
            holder.binding.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            holder.binding.iconSelected.visibility = if (selected) View.VISIBLE else View.GONE
            holder.binding.iconUnselected.visibility = if (selected) View.GONE else View.VISIBLE
            if (selected) {
                holder.binding.cardRoot.radius = 10f * density
                holder.binding.cardRoot.strokeWidth = (1.5f * density).toInt()
                holder.binding.cardRoot.strokeColor = primaryColor
            } else {
                holder.binding.cardRoot.radius = 14f * density
                holder.binding.cardRoot.strokeWidth = 0
                holder.binding.cardRoot.strokeColor = Color.TRANSPARENT
            }
        } else {
            holder.binding.selectionOverlay.visibility = View.GONE
            holder.binding.iconSelected.visibility = View.GONE
            holder.binding.iconUnselected.visibility = View.GONE
            holder.binding.cardRoot.radius = 14f * density
            holder.binding.cardRoot.strokeWidth = 0
            holder.binding.cardRoot.strokeColor = Color.TRANSPARENT
        }
    }

    fun resolveFormatBadgeText(group: ImageGroup): String {
        val isCinemaDng = group.isCinemaDng ||
            group.cinemaDngFolderUri != null ||
            group.cinemaDngFirstFrameUri != null ||
            group.cinemaDngFrameUris.isNotEmpty()
        val isRawVideo = group.isRawVideo || group.rawVideoUri != null
        val hasMp4 = group.isMp4Video || group.mp4VideoUri != null || group.derivativeMp4Uris.isNotEmpty()
        val hasJpg = group.jpgUri != null || group.derivativeJpgUris.isNotEmpty()
        val hasDng = group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null
        val derivCount = group.allDerivativeUris.size

        return when {
            isCinemaDng -> if (derivCount >= 2) "CDNG+${derivCount}V" else if (hasMp4) "CDNG+MP4" else if (hasJpg) "CDNG+JPG" else "CDNG"
            isRawVideo -> if (derivCount >= 2) "RAW+${derivCount}V" else if (hasMp4) "RAW+MP4" else "RAW VID"
            group.isMp4Video && !hasDng && !hasJpg -> if (derivCount >= 2) "GRADED+${derivCount}V" else if (group.derivativeMp4Uris.isNotEmpty()) "GRADED" else "MP4"
            group.isHalfFrame() -> context.getString(R.string.format_half_frame)
            hasDng && derivCount >= 2 -> "RAW+${derivCount}V"
            hasJpg && hasDng -> context.getString(R.string.format_raw_jpg)
            hasDng -> context.getString(R.string.format_raw)
            hasJpg -> if (derivCount >= 2) "${derivCount}V" else context.getString(R.string.format_jpg)
            else -> ""
        }
    }

    private fun setupSingleBadges(holder: DarkbagGalleryGridAdapter.SingleViewHolder, group: ImageGroup) {
        val isCinemaDng = group.isCinemaDng ||
            group.cinemaDngFolderUri != null ||
            group.cinemaDngFirstFrameUri != null ||
            group.cinemaDngFrameUris.isNotEmpty()
        val isRawVideo = group.isRawVideo || group.rawVideoUri != null
        val hasMp4 = group.isMp4Video || group.mp4VideoUri != null || group.derivativeMp4Uris.isNotEmpty()

        val badgeText = resolveFormatBadgeText(group)
        if (badgeText.isNotEmpty()) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = badgeText
        } else {
            holder.binding.tvFormatBadge.visibility = View.GONE
        }

        holder.binding.iconMotionBadge.visibility =
            if (isCinemaDng || group.isMotionPhoto || isRawVideo || hasMp4) View.VISIBLE else View.GONE
    }

    fun bindMultiCamViewHolder(holder: DarkbagGalleryGridAdapter.MultiCamViewHolder, group: ImageGroup) {
        val lenses = group.multiCameraLenses
        holder.binding.tvLensCountBadge.text = "${lenses.size} Lenses"

        bindLensSlot(holder, group, lenses.getOrNull(0), holder.binding.slotLens1, holder.binding.thumbnail1, holder.binding.tvLensTag1, holder.binding.tvFormatBadge1)
        bindLensSlot(holder, group, lenses.getOrNull(1), holder.binding.slotLens2, holder.binding.thumbnail2, holder.binding.tvLensTag2, holder.binding.tvFormatBadge2)
        bindLensSlot(holder, group, lenses.getOrNull(2), holder.binding.slotLens3, holder.binding.thumbnail3, holder.binding.tvLensTag3, holder.binding.tvFormatBadge3)

        bindMultiCamSelectionState(holder, group)
    }

    private fun bindLensSlot(
        holder: DarkbagGalleryGridAdapter.MultiCamViewHolder,
        group: ImageGroup,
        lens: MultiCameraLensItem?,
        slotCard: MaterialCardView,
        thumbnail: ImageView,
        tagView: TextView,
        formatBadgeView: TextView
    ) {
        if (lens == null) {
            slotCard.visibility = View.GONE
            return
        }
        slotCard.visibility = View.VISIBLE
        val uri = lens.jpgUri ?: lens.dngUri
        Glide.with(holder.itemView.context)
            .load(uri)
            .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(thumbnail)

        tagView.text = lens.lensTag
        bindLensFormatBadge(formatBadgeView, lens)
        setupLensSlotListeners(slotCard, group, lens, holder)
    }

    private fun bindLensFormatBadge(badgeView: TextView, lens: MultiCameraLensItem) {
        val hasJpg = lens.jpgUri != null
        val hasDng = lens.dngUri != null
        when {
            hasJpg && hasDng -> {
                badgeView.visibility = View.VISIBLE
                badgeView.text = context.getString(R.string.format_raw_jpg)
            }
            hasDng -> {
                badgeView.visibility = View.VISIBLE
                badgeView.text = context.getString(R.string.format_raw)
            }
            hasJpg -> {
                badgeView.visibility = View.VISIBLE
                badgeView.text = context.getString(R.string.format_jpg)
            }
            else -> badgeView.visibility = View.GONE
        }
    }

    private fun setupLensSlotListeners(
        slotView: View,
        group: ImageGroup,
        lens: MultiCameraLensItem,
        holder: DarkbagGalleryGridAdapter.MultiCamViewHolder
    ) {
        slotView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                if (isSelectionMode()) {
                    onToggleLensSelection(group, lens)
                } else {
                    onItemClick(group, adapterPos, lens.lensTag)
                }
            }
        }

        slotView.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                if (!isSelectionMode()) {
                    onEnterSelectionAndToggleLens(group, lens)
                } else {
                    onToggleLensSelection(group, lens)
                }
                onItemLongClick(group, adapterPos, lens.lensTag)
            }
            true
        }
    }

    fun bindMultiCamSelectionState(holder: DarkbagGalleryGridAdapter.MultiCamViewHolder, group: ImageGroup) {
        val primaryColor = MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)
        val strokePx = (2 * holder.itemView.context.resources.displayMetrics.density).toInt()
        val lenses = group.multiCameraLenses

        bindLensSelectionSlot(
            group, lenses.getOrNull(0), primaryColor, strokePx,
            holder.binding.slotLens1, holder.binding.selectionOverlay1,
            holder.binding.iconSelected1, holder.binding.iconUnselected1
        )
        bindLensSelectionSlot(
            group, lenses.getOrNull(1), primaryColor, strokePx,
            holder.binding.slotLens2, holder.binding.selectionOverlay2,
            holder.binding.iconSelected2, holder.binding.iconUnselected2
        )
        bindLensSelectionSlot(
            group, lenses.getOrNull(2), primaryColor, strokePx,
            holder.binding.slotLens3, holder.binding.selectionOverlay3,
            holder.binding.iconSelected3, holder.binding.iconUnselected3
        )
    }

    private fun bindLensSelectionSlot(
        group: ImageGroup,
        lens: MultiCameraLensItem?,
        primaryColor: Int,
        strokePx: Int,
        slotCard: MaterialCardView,
        overlay: View,
        iconSelected: View,
        iconUnselected: View
    ) {
        if (lens == null) return
        val selected = isKeySelected("${group.baseName}#${lens.lensTag}")
        if (isSelectionMode()) {
            overlay.visibility = if (selected) View.VISIBLE else View.GONE
            iconSelected.visibility = if (selected) View.VISIBLE else View.GONE
            iconUnselected.visibility = if (selected) View.GONE else View.VISIBLE
            slotCard.strokeWidth = if (selected) strokePx else 0
            slotCard.strokeColor = if (selected) primaryColor else Color.TRANSPARENT
        } else {
            overlay.visibility = View.GONE
            iconSelected.visibility = View.GONE
            iconUnselected.visibility = View.GONE
            slotCard.strokeWidth = 0
            slotCard.strokeColor = Color.TRANSPARENT
        }
    }
}

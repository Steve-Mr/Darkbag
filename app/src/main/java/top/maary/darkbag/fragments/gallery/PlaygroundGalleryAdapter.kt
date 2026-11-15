package top.maary.darkbag.fragments.gallery

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.databinding.ItemPlaygroundImageBinding
import top.maary.darkbag.models.PlaygroundItem
import top.maary.darkbag.utils.GalleryAspectRatioHelper
import top.maary.darkbag.utils.GalleryThumbnailLoader
import java.io.File

/**
 * Playground 外部暗房画廊网格适配器 (PlaygroundAdapter).
 * 负责 DNG/JPG 单图及合集分组项的异步缩略图解码、多选状态覆盖与局部 payload 刷新。
 */
class PlaygroundAdapter(
    private val coroutineScope: CoroutineScope,
    private val selectedFiles: () -> Set<File>,
    private val isSelectionMode: () -> Boolean,
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit,
    private val onExpandClick: (PlaygroundItem.Group, Int) -> Unit
) : ListAdapter<PlaygroundItem, PlaygroundAdapter.ViewHolder>(PlaygroundItemDiffCallback()) {

    class PlaygroundItemDiffCallback : DiffUtil.ItemCallback<PlaygroundItem>() {
        override fun areItemsTheSame(oldItem: PlaygroundItem, newItem: PlaygroundItem): Boolean {
            return oldItem.mainFile.absolutePath == newItem.mainFile.absolutePath
        }

        override fun areContentsTheSame(oldItem: PlaygroundItem, newItem: PlaygroundItem): Boolean {
            if (oldItem.javaClass != newItem.javaClass) return false
            if (oldItem is PlaygroundItem.Group && newItem is PlaygroundItem.Group) {
                if (oldItem.isExpanded != newItem.isExpanded) return false
                if (oldItem.dng1.lastModified() != newItem.dng1.lastModified() ||
                    oldItem.dng2.lastModified() != newItem.dng2.lastModified()
                ) return false
            }
            return oldItem.mainFile.lastModified() == newItem.mainFile.lastModified()
        }

        override fun getChangePayload(oldItem: PlaygroundItem, newItem: PlaygroundItem): Any? {
            val payloads = mutableListOf<String>()

            if (oldItem is PlaygroundItem.Group && newItem is PlaygroundItem.Group) {
                if (oldItem.isExpanded != newItem.isExpanded) {
                    payloads.add("EXPANSION_CHANGED")
                }
            }

            val isModified = oldItem.mainFile.lastModified() != newItem.mainFile.lastModified() ||
                (oldItem is PlaygroundItem.Group && newItem is PlaygroundItem.Group &&
                    (oldItem.dng1.lastModified() != newItem.dng1.lastModified() ||
                        oldItem.dng2.lastModified() != newItem.dng2.lastModified()))

            if (isModified) {
                payloads.add("MODIFIED")
            }

            return if (payloads.isNotEmpty()) payloads else super.getChangePayload(oldItem, newItem)
        }
    }

    class ViewHolder(val binding: ItemPlaygroundImageBinding) : RecyclerView.ViewHolder(binding.root) {
        var jobMain: Job? = null
        var bitmapMain: Bitmap? = null
        var jobSub1: Job? = null
        var bitmapSub1: Bitmap? = null
        var jobSub2: Job? = null
        var bitmapSub2: Bitmap? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaygroundImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        val flattenedPayloads = payloads.flatMap { if (it is List<*>) it else listOf(it) }
        val item = getItem(position)
        var handled = false

        if (flattenedPayloads.contains("SELECTION_CHANGED")) {
            bindSelectionOverlays(holder, item)
            handled = true
        }

        if (flattenedPayloads.contains("EXPANSION_CHANGED")) {
            if (item is PlaygroundItem.Group) {
                holder.binding.subImagesContainer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            }
            handled = true
        }

        if (flattenedPayloads.contains("MODIFIED")) {
            val context = holder.itemView.context
            when (item) {
                is PlaygroundItem.Single -> {
                    holder.jobMain?.cancel()
                    holder.jobMain = null
                    holder.binding.imageViewThumbnail.setImageDrawable(null)
                    holder.bitmapMain?.recycle()
                    holder.bitmapMain = null

                    val file = item.file
                    val jpgFile = File(file.parent, file.nameWithoutExtension + ".jpg")
                    val targetFile = if (jpgFile.exists()) jpgFile else file
                    loadThumbnail(context, targetFile, holder.binding.imageViewThumbnail, holder, true)
                }
                is PlaygroundItem.Group -> {
                    clearJobsAndBitmaps(holder)
                    loadThumbnail(context, item.jpgFile, holder.binding.imageViewThumbnail, holder, true)
                    loadThumbnail(context, item.dng1, holder.binding.subImageView1, holder, isMain = false, isSub1 = true)
                    loadThumbnail(context, item.dng2, holder.binding.subImageView2, holder, isMain = false, isSub2 = true)
                }
            }
            handled = true
        }

        if (!handled) {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        GalleryAspectRatioHelper.applyAspectRatio(holder.binding.imageViewThumbnail, item.aspectRatio, fallbackRatio = "3:4")
        bindSelectionOverlays(holder, item)

        val context = holder.itemView.context
        clearImageViews(context, holder)
        clearJobsAndBitmaps(holder)

        when (item) {
            is PlaygroundItem.Single -> {
                holder.binding.badgeFormat.text = if (item.file.extension.lowercase() == "dng") "EXT DNG" else "EXT JPG"
                holder.binding.iconLayer.visibility = View.GONE
                holder.binding.subImagesContainer.visibility = View.GONE

                val file = item.file
                val jpgFile = File(file.parent, file.nameWithoutExtension + ".jpg")
                val targetFile = if (jpgFile.exists()) jpgFile else file
                loadThumbnail(context, targetFile, holder.binding.imageViewThumbnail, holder, true)
            }
            is PlaygroundItem.Group -> {
                holder.binding.badgeFormat.text = "HF MERGED"
                holder.binding.iconLayer.visibility = View.VISIBLE
                holder.binding.subImagesContainer.visibility = if (item.isExpanded) View.VISIBLE else View.GONE

                loadThumbnail(context, item.jpgFile, holder.binding.imageViewThumbnail, holder, true)
                holder.binding.subImageView1.setImageDrawable(null)
                holder.binding.subImageView2.setImageDrawable(null)
                loadThumbnail(context, item.dng1, holder.binding.subImageView1, holder, isMain = false, isSub1 = true)
                loadThumbnail(context, item.dng2, holder.binding.subImageView2, holder, isMain = false, isSub2 = true)

                holder.binding.iconLayer.setOnClickListener {
                    onExpandClick(item, holder.bindingAdapterPosition)
                }
                holder.binding.subImageView1.setOnClickListener { onItemClick(item.dng1) }
                holder.binding.subImageView1.setOnLongClickListener {
                    onItemLongClick(item.dng1)
                    true
                }
                holder.binding.subImageView2.setOnClickListener { onItemClick(item.dng2) }
                holder.binding.subImageView2.setOnLongClickListener {
                    onItemLongClick(item.dng2)
                    true
                }
            }
        }

        holder.binding.imageViewThumbnail.setOnClickListener { onItemClick(item.mainFile) }
        holder.binding.imageViewThumbnail.setOnLongClickListener {
            onItemLongClick(item.mainFile)
            true
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        clearImageViews(holder.itemView.context, holder)
        clearJobsAndBitmaps(holder)
    }

    private fun bindSelectionOverlays(holder: ViewHolder, item: PlaygroundItem) {
        val currentSelected = selectedFiles()
        val selectionMode = isSelectionMode()
        val isMainSelected = currentSelected.contains(item.mainFile)

        holder.binding.selectionOverlay.visibility = if (isMainSelected) View.VISIBLE else View.GONE
        holder.binding.iconSelected.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.binding.iconSelected.setImageResource(
            if (isMainSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked
        )

        if (item is PlaygroundItem.Group) {
            val isDng1Selected = currentSelected.contains(item.dng1)
            val isDng2Selected = currentSelected.contains(item.dng2)
            holder.binding.subSelectionOverlay1.visibility = if (isDng1Selected) View.VISIBLE else View.GONE
            holder.binding.subIconSelected1.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.binding.subIconSelected1.setImageResource(
                if (isDng1Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked
            )

            holder.binding.subSelectionOverlay2.visibility = if (isDng2Selected) View.VISIBLE else View.GONE
            holder.binding.subIconSelected2.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.binding.subIconSelected2.setImageResource(
                if (isDng2Selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked
            )
        }
    }

    private fun clearImageViews(context: Context, holder: ViewHolder) {
        Glide.with(context).clear(holder.binding.imageViewThumbnail)
        Glide.with(context).clear(holder.binding.subImageView1)
        Glide.with(context).clear(holder.binding.subImageView2)
        holder.binding.imageViewThumbnail.setImageDrawable(null)
        holder.binding.subImageView1.setImageDrawable(null)
        holder.binding.subImageView2.setImageDrawable(null)
    }

    private fun clearJobsAndBitmaps(holder: ViewHolder) {
        holder.jobMain?.cancel()
        holder.jobSub1?.cancel()
        holder.jobSub2?.cancel()
        holder.jobMain = null
        holder.jobSub1 = null
        holder.jobSub2 = null
        holder.bitmapMain?.recycle()
        holder.bitmapSub1?.recycle()
        holder.bitmapSub2?.recycle()
        holder.bitmapMain = null
        holder.bitmapSub1 = null
        holder.bitmapSub2 = null
    }

    private fun loadThumbnail(
        context: Context,
        file: File,
        imageView: ImageView,
        holder: ViewHolder,
        isMain: Boolean,
        isSub1: Boolean = false,
        isSub2: Boolean = false
    ) {
        val currentTag = file.absolutePath + "_" + file.lastModified()
        imageView.tag = currentTag

        if (file.extension.lowercase() == "jpg") {
            Glide.with(context).load(file).signature(ObjectKey(file.lastModified())).into(imageView)
            return
        }

        val job = coroutineScope.launch(Dispatchers.IO) {
            var decodedBitmap: Bitmap? = null
            var bitmapAssigned = false
            try {
                decodedBitmap = GalleryThumbnailLoader.loadDngThumbnail(
                    context = context,
                    uri = Uri.fromFile(file),
                    reqWidth = 400,
                    reqHeight = 400
                )

                ensureActive()

                withContext(Dispatchers.Main) {
                    if (imageView.tag == currentTag) {
                        if (decodedBitmap != null) {
                            if (isMain) holder.bitmapMain = decodedBitmap
                            else if (isSub1) holder.bitmapSub1 = decodedBitmap
                            else if (isSub2) holder.bitmapSub2 = decodedBitmap

                            bitmapAssigned = true
                            imageView.setImageBitmap(decodedBitmap)
                        } else {
                            Glide.with(context).load(file).signature(ObjectKey(file.lastModified())).into(imageView)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Playground", "Failed to load thumbnail for ${file.name}", e)
            } finally {
                if (decodedBitmap != null && !bitmapAssigned) {
                    decodedBitmap.recycle()
                }
            }
        }

        if (isMain) holder.jobMain = job
        else if (isSub1) holder.jobSub1 = job
        else if (isSub2) holder.jobSub2 = job
    }
}

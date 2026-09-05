package top.maary.darkbag.fragments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import top.maary.darkbag.R
import top.maary.darkbag.databinding.ItemDarkbagGalleryGridBinding
import top.maary.darkbag.databinding.ItemDarkbagGalleryMultiCamGroupBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

data class GallerySelectedItem(
    val group: ImageGroup,
    val specificLens: MultiCameraLensItem? = null
)

class DarkbagGalleryGridAdapter(
    private val context: Context
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_SINGLE = 0
        const val VIEW_TYPE_MULTI_CAMERA = 1
    }

    var onItemClick: ((ImageGroup, Int, String?) -> Unit)? = null
    var onItemLongClick: ((ImageGroup, Int, String?) -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    // Format for keys: "baseName" for single image; "baseName#lensTag" for multi-camera lens
    private val selectedItemKeys = mutableSetOf<String>()
    var isSelectionMode: Boolean = false
        private set

    private val diffCallback = object : DiffUtil.ItemCallback<ImageGroup>() {
        override fun areItemsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem.baseName == newItem.baseName
        }

        override fun areContentsTheSame(oldItem: ImageGroup, newItem: ImageGroup): Boolean {
            return oldItem == newItem &&
                    oldItem.metadataLoaded == newItem.metadataLoaded &&
                    oldItem.isMotionPhoto == newItem.isMotionPhoto &&
                    oldItem.isCinemaDng == newItem.isCinemaDng &&
                    oldItem.lastModified == newItem.lastModified &&
                    oldItem.multiCameraLenses == newItem.multiCameraLenses
        }
    }

    private val differ = AsyncListDiffer(this, diffCallback)

    val currentList: List<ImageGroup>
        get() = differ.currentList

    fun submitList(list: List<ImageGroup>, commitCallback: (() -> Unit)? = null) {
        differ.submitList(list, commitCallback)
    }

    fun getItem(position: Int): ImageGroup = differ.currentList[position]

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode == enabled) return
        isSelectionMode = enabled
        if (!enabled) {
            selectedItemKeys.clear()
            onSelectionChanged?.invoke(0)
        }
        notifyDataSetChanged()
    }

    fun toggleSingleSelection(group: ImageGroup) {
        val key = group.baseName
        if (selectedItemKeys.contains(key)) {
            selectedItemKeys.remove(key)
        } else {
            selectedItemKeys.add(key)
        }
        val pos = differ.currentList.indexOfFirst { it.baseName == group.baseName }
        if (pos != -1) {
            notifyItemChanged(pos, "SELECTION_CHANGED")
        }
        onSelectionChanged?.invoke(selectedItemKeys.size)
    }

    fun toggleLensSelection(group: ImageGroup, lens: MultiCameraLensItem) {
        val key = "${group.baseName}#${lens.lensTag}"
        if (selectedItemKeys.contains(key)) {
            selectedItemKeys.remove(key)
        } else {
            selectedItemKeys.add(key)
        }
        val pos = differ.currentList.indexOfFirst { it.baseName == group.baseName }
        if (pos != -1) {
            notifyItemChanged(pos, "SELECTION_CHANGED")
        }
        onSelectionChanged?.invoke(selectedItemKeys.size)
    }

    fun selectAll() {
        selectedItemKeys.clear()
        for (group in differ.currentList) {
            if (group.isMultiCamera && group.multiCameraLenses.isNotEmpty()) {
                for (lens in group.multiCameraLenses) {
                    selectedItemKeys.add("${group.baseName}#${lens.lensTag}")
                }
            } else {
                selectedItemKeys.add(group.baseName)
            }
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItemKeys.size)
    }

    fun deselectAll() {
        selectedItemKeys.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun getSelectedItems(): List<GallerySelectedItem> {
        val result = mutableListOf<GallerySelectedItem>()
        for (group in differ.currentList) {
            if (group.isMultiCamera && group.multiCameraLenses.isNotEmpty()) {
                for (lens in group.multiCameraLenses) {
                    val key = "${group.baseName}#${lens.lensTag}"
                    if (selectedItemKeys.contains(key)) {
                        result.add(GallerySelectedItem(group, lens))
                    }
                }
            } else {
                if (selectedItemKeys.contains(group.baseName)) {
                    result.add(GallerySelectedItem(group, null))
                }
            }
        }
        return result
    }

    fun getSelectedCount(): Int = selectedItemKeys.size

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return if (item.isMultiCamera && item.multiCameraLenses.isNotEmpty()) {
            VIEW_TYPE_MULTI_CAMERA
        } else {
            VIEW_TYPE_SINGLE
        }
    }

    override fun getItemCount(): Int = differ.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_MULTI_CAMERA) {
            val binding = ItemDarkbagGalleryMultiCamGroupBinding.inflate(inflater, parent, false)
            MultiCamViewHolder(binding)
        } else {
            val binding = ItemDarkbagGalleryGridBinding.inflate(inflater, parent, false)
            SingleViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("SELECTION_CHANGED")) {
            val group = getItem(position)
            if (holder is SingleViewHolder) {
                bindSingleSelectionState(holder, group)
            } else if (holder is MultiCamViewHolder) {
                bindMultiCamSelectionState(holder, group)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val group = getItem(position)
        if (holder is SingleViewHolder) {
            bindSingleViewHolder(holder, group, position)
        } else if (holder is MultiCamViewHolder) {
            bindMultiCamViewHolder(holder, group, position)
        }
    }

    private val rawThumbCache = android.util.LruCache<String, android.graphics.Bitmap>(30)

    private fun bindSingleViewHolder(holder: SingleViewHolder, group: ImageGroup, position: Int) {
        val targetUri = group.jpgUri ?: group.derivativeJpgUris.firstOrNull() ?: group.mp4VideoUri ?: group.derivativeMp4Uris.firstOrNull() ?: group.cinemaDngFirstFrameUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2

        if (targetUri != null) {
            val isDng = targetUri.toString().endsWith(".dng", ignoreCase = true) || group.dngUri == targetUri || group.cinemaDngFirstFrameUri == targetUri
            val isMp4 = targetUri.toString().endsWith(".mp4", ignoreCase = true) || (group.isMp4Video && group.rawVideoUri == null)

            if (isDng && group.jpgUri == null && group.derivativeJpgUris.isEmpty() && group.mp4VideoUri == null && group.derivativeMp4Uris.isEmpty()) {
                loadDngThumbnail(holder, group, targetUri)
            } else if (isMp4) {
                loadMp4Thumbnail(holder, group, targetUri)
            } else {
                Glide.with(holder.itemView.context)
                    .load(targetUri)
                    .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(holder.binding.thumbnailView)
            }
        } else if (group.rawVideoUri != null) {
            loadRawVideoThumbnail(holder, group)
        } else {
            Glide.with(holder.itemView.context).clear(holder.binding.thumbnailView)
            holder.binding.thumbnailView.setImageDrawable(null)
        }

        // Setup format badges
        setupSingleBadges(holder, group)

        // Setup selection state
        bindSingleSelectionState(holder, group)

        // Click listeners
        holder.binding.cardRoot.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                val item = getItem(adapterPos)
                if (isSelectionMode) {
                    toggleSingleSelection(item)
                } else {
                    onItemClick?.invoke(item, adapterPos, null)
                }
            }
        }

        holder.binding.cardRoot.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                val item = getItem(adapterPos)
                if (!isSelectionMode) {
                    setSelectionMode(true)
                    toggleSingleSelection(item)
                } else {
                    toggleSingleSelection(item)
                }
                onItemLongClick?.invoke(item, adapterPos, null)
            }
            true
        }
    }

    private fun bindSingleSelectionState(holder: SingleViewHolder, group: ImageGroup) {
        val selected = selectedItemKeys.contains(group.baseName)
        if (isSelectionMode) {
            holder.binding.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            holder.binding.iconSelected.visibility = if (selected) View.VISIBLE else View.GONE
            holder.binding.iconUnselected.visibility = if (selected) View.GONE else View.VISIBLE
            holder.binding.cardRoot.strokeColor = if (selected) {
                com.google.android.material.color.MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)
            } else {
                Color.TRANSPARENT
            }
        } else {
            holder.binding.selectionOverlay.visibility = View.GONE
            holder.binding.iconSelected.visibility = View.GONE
            holder.binding.iconUnselected.visibility = View.GONE
            holder.binding.cardRoot.strokeColor = Color.TRANSPARENT
        }
    }

    private fun setupSingleBadges(holder: SingleViewHolder, group: ImageGroup) {
        val isCinemaDng = group.isCinemaDng || group.cinemaDngFolderUri != null || group.cinemaDngFirstFrameUri != null || group.cinemaDngFrameUris.isNotEmpty()
        val isRawVideo = group.isRawVideo || group.rawVideoUri != null
        val hasMp4 = group.isMp4Video || group.mp4VideoUri != null || group.derivativeMp4Uris.isNotEmpty()
        val hasJpg = group.jpgUri != null || group.derivativeJpgUris.isNotEmpty()
        val hasDng = group.dngUri != null || group.dngUri1 != null || group.dngUri2 != null
        val derivCount = group.allDerivativeUris.size

        if (isCinemaDng) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = when {
                derivCount >= 2 -> "CDNG+${derivCount}V"
                hasMp4 -> "CDNG+MP4"
                hasJpg -> "CDNG+JPG"
                else -> "CDNG"
            }
        } else if (isRawVideo) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = when {
                derivCount >= 2 -> "RAW+${derivCount}V"
                hasMp4 -> "RAW+MP4"
                else -> "RAW VID"
            }
        } else if (group.isMp4Video && !hasDng && !hasJpg) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = if (derivCount >= 2) "GRADED+${derivCount}V" else if (group.derivativeMp4Uris.isNotEmpty()) "GRADED" else "MP4"
        } else if (group.isHalfFrame()) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = context.getString(R.string.format_half_frame)
        } else if (hasDng && derivCount >= 2) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = "RAW+${derivCount}V"
        } else if (hasJpg && hasDng) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = context.getString(R.string.format_raw_jpg)
        } else if (hasDng) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = context.getString(R.string.format_raw)
        } else if (hasJpg) {
            holder.binding.tvFormatBadge.visibility = View.VISIBLE
            holder.binding.tvFormatBadge.text = if (derivCount >= 2) "${derivCount}V" else context.getString(R.string.format_jpg)
        } else {
            holder.binding.tvFormatBadge.visibility = View.GONE
        }

        if (isCinemaDng || group.isMotionPhoto || isRawVideo || hasMp4) {
            holder.binding.iconMotionBadge.visibility = View.VISIBLE
        } else {
            holder.binding.iconMotionBadge.visibility = View.GONE
        }
    }

    private fun bindMultiCamViewHolder(holder: MultiCamViewHolder, group: ImageGroup, position: Int) {
        val lenses = group.multiCameraLenses
        holder.binding.tvLensCountBadge.text = "${lenses.size} Lenses"

        val primaryColor = com.google.android.material.color.MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)

        // Slot 1
        val lens1 = lenses.getOrNull(0)
        if (lens1 != null) {
            holder.binding.slotLens1.visibility = View.VISIBLE
            val uri1 = lens1.jpgUri ?: lens1.dngUri
            Glide.with(holder.itemView.context)
                .load(uri1)
                .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.binding.thumbnail1)

            holder.binding.tvLensTag1.text = lens1.lensTag
            bindLensFormatBadge(holder.binding.tvFormatBadge1, lens1)
            setupLensSlotListeners(holder.binding.slotLens1, group, lens1, holder)
        } else {
            holder.binding.slotLens1.visibility = View.GONE
        }

        // Slot 2
        val lens2 = lenses.getOrNull(1)
        if (lens2 != null) {
            holder.binding.slotLens2.visibility = View.VISIBLE
            val uri2 = lens2.jpgUri ?: lens2.dngUri
            Glide.with(holder.itemView.context)
                .load(uri2)
                .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.binding.thumbnail2)

            holder.binding.tvLensTag2.text = lens2.lensTag
            bindLensFormatBadge(holder.binding.tvFormatBadge2, lens2)
            setupLensSlotListeners(holder.binding.slotLens2, group, lens2, holder)
        } else {
            holder.binding.slotLens2.visibility = View.GONE
        }

        // Slot 3
        val lens3 = lenses.getOrNull(2)
        if (lens3 != null) {
            holder.binding.slotLens3.visibility = View.VISIBLE
            val uri3 = lens3.jpgUri ?: lens3.dngUri
            Glide.with(holder.itemView.context)
                .load(uri3)
                .apply(RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.binding.thumbnail3)

            holder.binding.tvLensTag3.text = lens3.lensTag
            bindLensFormatBadge(holder.binding.tvFormatBadge3, lens3)
            setupLensSlotListeners(holder.binding.slotLens3, group, lens3, holder)
        } else {
            holder.binding.slotLens3.visibility = View.GONE
        }

        bindMultiCamSelectionState(holder, group)
    }

    private fun bindLensFormatBadge(badgeView: android.widget.TextView, lens: MultiCameraLensItem) {
        val hasJpg = lens.jpgUri != null
        val hasDng = lens.dngUri != null
        if (hasJpg && hasDng) {
            badgeView.visibility = View.VISIBLE
            badgeView.text = context.getString(R.string.format_raw_jpg)
        } else if (hasDng) {
            badgeView.visibility = View.VISIBLE
            badgeView.text = context.getString(R.string.format_raw)
        } else if (hasJpg) {
            badgeView.visibility = View.VISIBLE
            badgeView.text = context.getString(R.string.format_jpg)
        } else {
            badgeView.visibility = View.GONE
        }
    }

    private fun setupLensSlotListeners(
        slotView: View,
        group: ImageGroup,
        lens: MultiCameraLensItem,
        holder: MultiCamViewHolder
    ) {
        slotView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                if (isSelectionMode) {
                    toggleLensSelection(group, lens)
                } else {
                    onItemClick?.invoke(group, adapterPos, lens.lensTag)
                }
            }
        }

        slotView.setOnLongClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                if (!isSelectionMode) {
                    setSelectionMode(true)
                    toggleLensSelection(group, lens)
                } else {
                    toggleLensSelection(group, lens)
                }
                onItemLongClick?.invoke(group, adapterPos, lens.lensTag)
            }
            true
        }
    }

    private fun bindMultiCamSelectionState(holder: MultiCamViewHolder, group: ImageGroup) {
        val primaryColor = com.google.android.material.color.MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)
        val lenses = group.multiCameraLenses

        // Lens 1 selection
        val lens1 = lenses.getOrNull(0)
        if (lens1 != null) {
            val sel1 = selectedItemKeys.contains("${group.baseName}#${lens1.lensTag}")
            if (isSelectionMode) {
                holder.binding.selectionOverlay1.visibility = if (sel1) View.VISIBLE else View.GONE
                holder.binding.iconSelected1.visibility = if (sel1) View.VISIBLE else View.GONE
                holder.binding.iconUnselected1.visibility = if (sel1) View.GONE else View.VISIBLE
                holder.binding.slotLens1.strokeWidth = if (sel1) (2 * holder.itemView.context.resources.displayMetrics.density).toInt() else 0
                holder.binding.slotLens1.strokeColor = if (sel1) primaryColor else Color.TRANSPARENT
            } else {
                holder.binding.selectionOverlay1.visibility = View.GONE
                holder.binding.iconSelected1.visibility = View.GONE
                holder.binding.iconUnselected1.visibility = View.GONE
                holder.binding.slotLens1.strokeWidth = 0
                holder.binding.slotLens1.strokeColor = Color.TRANSPARENT
            }
        }

        // Lens 2 selection
        val lens2 = lenses.getOrNull(1)
        if (lens2 != null) {
            val sel2 = selectedItemKeys.contains("${group.baseName}#${lens2.lensTag}")
            if (isSelectionMode) {
                holder.binding.selectionOverlay2.visibility = if (sel2) View.VISIBLE else View.GONE
                holder.binding.iconSelected2.visibility = if (sel2) View.VISIBLE else View.GONE
                holder.binding.iconUnselected2.visibility = if (sel2) View.GONE else View.VISIBLE
                holder.binding.slotLens2.strokeWidth = if (sel2) (2 * holder.itemView.context.resources.displayMetrics.density).toInt() else 0
                holder.binding.slotLens2.strokeColor = if (sel2) primaryColor else Color.TRANSPARENT
            } else {
                holder.binding.selectionOverlay2.visibility = View.GONE
                holder.binding.iconSelected2.visibility = View.GONE
                holder.binding.iconUnselected2.visibility = View.GONE
                holder.binding.slotLens2.strokeWidth = 0
                holder.binding.slotLens2.strokeColor = Color.TRANSPARENT
            }
        }

        // Lens 3 selection
        val lens3 = lenses.getOrNull(2)
        if (lens3 != null) {
            val sel3 = selectedItemKeys.contains("${group.baseName}#${lens3.lensTag}")
            if (isSelectionMode) {
                holder.binding.selectionOverlay3.visibility = if (sel3) View.VISIBLE else View.GONE
                holder.binding.iconSelected3.visibility = if (sel3) View.VISIBLE else View.GONE
                holder.binding.iconUnselected3.visibility = if (sel3) View.GONE else View.VISIBLE
                holder.binding.slotLens3.strokeWidth = if (sel3) (2 * holder.itemView.context.resources.displayMetrics.density).toInt() else 0
                holder.binding.slotLens3.strokeColor = if (sel3) primaryColor else Color.TRANSPARENT
            } else {
                holder.binding.selectionOverlay3.visibility = View.GONE
                holder.binding.iconSelected3.visibility = View.GONE
                holder.binding.iconUnselected3.visibility = View.GONE
                holder.binding.slotLens3.strokeWidth = 0
                holder.binding.slotLens3.strokeColor = Color.TRANSPARENT
            }
        }
    }

    private fun loadRawVideoThumbnail(holder: SingleViewHolder, group: ImageGroup) {
        val rawUri = group.rawVideoUri ?: return
        val cached = rawThumbCache.get(group.baseName)
        if (cached != null && !cached.isRecycled) {
            holder.binding.thumbnailView.setImageBitmap(cached)
            return
        }

        Glide.with(holder.itemView.context).clear(holder.binding.thumbnailView)
        holder.binding.thumbnailView.setImageDrawable(null)

        val context = holder.itemView.context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                context.contentResolver.openFileDescriptor(rawUri, "r")?.use { pfd ->
                    val handle = top.maary.darkbag.rawvideo.RawVideoNative.nativeOpenReaderFd(pfd.fd)
                    if (handle != 0L) {
                        try {
                            val header = top.maary.darkbag.rawvideo.RawVideoNative.readHeader(handle)
                            if (header != null && header.width > 0 && header.height > 0) {
                                val swapDims = (header.orientation == 90 || header.orientation == 270)
                                val thumbW = if (swapDims) (320 * header.height) / header.width else 320
                                val thumbH = if (swapDims) 320 else (320 * header.height) / header.width
                                val bmp = android.graphics.Bitmap.createBitmap(thumbW, thumbH, android.graphics.Bitmap.Config.ARGB_8888)
                                val bufSize = header.width * header.height * 2
                                val directBuf = java.nio.ByteBuffer.allocateDirect(bufSize)
                                val meta = LongArray(3)
                                val read = top.maary.darkbag.rawvideo.RawVideoNative.nativeReadFrame(handle, 0, meta, directBuf)
                                if (read > 0) {
                                    val targetLogIndex = if (header.activeLogName.isNotBlank() && header.activeLogName != "None") {
                                        top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(header.activeLogName).takeIf { it >= 0 } ?: -1
                                    } else -1
                                    val lutManager = top.maary.darkbag.utils.LutManager(context)
                                    val lutPath = if (header.activeLutName.isNotBlank() && header.activeLutName != "None") {
                                        val f = java.io.File(lutManager.lutDir, header.activeLutName)
                                        if (f.exists()) f.absolutePath else null
                                    } else null

                                    val debayered = top.maary.darkbag.rawvideo.RawVideoNative.nativeDebayerFrameToBitmap(
                                        bayerBuffer = directBuf,
                                        width = header.width,
                                        height = header.height,
                                        orientation = header.orientation,
                                        cfaPattern = header.cfaPattern,
                                        whiteLevel = header.whiteLevel,
                                        blackLevel = header.blackLevel.firstOrNull() ?: 64f,
                                        neutralPoint = header.neutralPoint,
                                        targetLog = targetLogIndex,
                                        lutPath = lutPath,
                                        exposure = header.exposure,
                                        contrast = header.contrast,
                                        saturation = header.saturation,
                                        outBitmap = bmp
                                    )
                                    if (debayered) {
                                        rawThumbCache.put(group.baseName, bmp)
                                        withContext(Dispatchers.Main) {
                                            if (holder.bindingAdapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                                                holder.binding.thumbnailView.setImageBitmap(bmp)
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            top.maary.darkbag.rawvideo.RawVideoNative.nativeCloseReader(handle)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("DarkbagGalleryGridAdapter", "Failed to load raw video thumb: $rawUri", e)
            }
        }
    }

    private fun loadMp4Thumbnail(holder: SingleViewHolder, group: ImageGroup, uri: Uri) {
        val cached = rawThumbCache.get("MP4_${group.baseName}")
        if (cached != null && !cached.isRecycled) {
            holder.binding.thumbnailView.setImageBitmap(cached)
            return
        }
        val context = holder.itemView.context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            var bmp: Bitmap? = null
            try {
                val retriever = android.media.MediaMetadataRetriever()
                if (uri.scheme == "content") {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        bmp = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                } else {
                    retriever.setDataSource(uri.path)
                    bmp = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                retriever.release()
            } catch (e: Exception) {
                Log.w("DarkbagGalleryGridAdapter", "MediaMetadataRetriever failed for $uri", e)
            }

            if (bmp != null) {
                rawThumbCache.put("MP4_${group.baseName}", bmp)
                withContext(Dispatchers.Main) {
                    if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        holder.binding.thumbnailView.setImageBitmap(bmp)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Glide.with(holder.itemView.context)
                        .load(uri)
                        .apply(RequestOptions().frame(0).centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                        .into(holder.binding.thumbnailView)
                }
            }
        }
    }

    private fun loadDngThumbnail(holder: SingleViewHolder, group: ImageGroup, uri: Uri) {
        val cached = rawThumbCache.get("DNG_${group.baseName}")
        if (cached != null && !cached.isRecycled) {
            holder.binding.thumbnailView.setImageBitmap(cached)
            return
        }
        val context = holder.itemView.context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val bmp = top.maary.darkbag.utils.ImageUtils.renderDngBitmap(context, uri, reqWidth = 512, reqHeight = 512)
            if (bmp != null) {
                rawThumbCache.put("DNG_${group.baseName}", bmp)
                withContext(Dispatchers.Main) {
                    if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        holder.binding.thumbnailView.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    class SingleViewHolder(val binding: ItemDarkbagGalleryGridBinding) : RecyclerView.ViewHolder(binding.root)
    class MultiCamViewHolder(val binding: ItemDarkbagGalleryMultiCamGroupBinding) : RecyclerView.ViewHolder(binding.root)
}

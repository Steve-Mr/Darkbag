package top.maary.darkbag.fragments

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.maary.darkbag.databinding.ItemDarkbagGalleryGridBinding
import top.maary.darkbag.databinding.ItemDarkbagGalleryMultiCamGroupBinding
import top.maary.darkbag.fragments.gallery.GalleryGridItemBinder
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.models.MultiCameraLensItem

data class GallerySelectedItem(
    val group: ImageGroup,
    val specificLens: MultiCameraLensItem? = null
)

class DarkbagGalleryGridAdapter(
    private val context: Context,
    var repository: top.maary.darkbag.repository.ImageRepository? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_SINGLE = 0
        const val VIEW_TYPE_MULTI_CAMERA = 1
    }

    var onItemClick: ((ImageGroup, Int, String?) -> Unit)? = null
    var onItemLongClick: ((ImageGroup, Int, String?) -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    var columnCount: Int = 3
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, "COLUMN_COUNT_CHANGED")
            }
        }

    var isWaterfallMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, "COLUMN_COUNT_CHANGED")
            }
        }

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

    private var adapterJob = SupervisorJob()
    private var adapterScope = CoroutineScope(Dispatchers.IO + adapterJob)
    private val thumbnailSemaphore = Semaphore(4)
    private val rawThumbCache = android.util.LruCache<String, Bitmap>(30)

    private val itemBinder = GalleryGridItemBinder(
        context = context,
        getRepository = { repository },
        getAdapterScope = { adapterScope },
        getItemAt = { pos -> getItem(pos) },
        isSelectionMode = { isSelectionMode },
        isKeySelected = { key -> selectedItemKeys.contains(key) },
        getColumnCount = { columnCount },
        isWaterfallMode = { isWaterfallMode },
        onItemClick = { group, pos, lensTag -> onItemClick?.invoke(group, pos, lensTag) },
        onItemLongClick = { group, pos, lensTag -> onItemLongClick?.invoke(group, pos, lensTag) }
    )

    val currentList: List<ImageGroup>
        get() = differ.currentList

    fun submitList(list: List<ImageGroup>, commitCallback: (() -> Unit)? = null) {
        differ.submitList(list, commitCallback)
    }

    fun getItem(position: Int): ImageGroup = differ.currentList[position]

    fun resolveFormatBadgeText(group: ImageGroup): String = itemBinder.resolveFormatBadgeText(group)

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode == enabled) return
        isSelectionMode = enabled
        if (!enabled) {
            selectedItemKeys.clear()
            onSelectionChanged?.invoke(0)
        }
        notifyDataSetChanged()
    }

    fun syncSelection(keys: Set<String>, selectionMode: Boolean) {
        val oldKeys = selectedItemKeys.toSet()
        val oldSelectionMode = isSelectionMode
        if (oldKeys == keys && oldSelectionMode == selectionMode) return

        selectedItemKeys.clear()
        selectedItemKeys.addAll(keys)
        isSelectionMode = selectionMode

        if (oldSelectionMode != selectionMode) {
            notifyItemRangeChanged(0, itemCount, "SELECTION_CHANGED")
        } else {
            val changedKeys = (oldKeys - keys) + (keys - oldKeys)
            for (key in changedKeys) {
                val base = if (key.contains('#')) key.substringBefore('#') else key
                val pos = differ.currentList.indexOfFirst { it.baseName == base }
                if (pos != -1) {
                    notifyItemChanged(pos, "SELECTION_CHANGED")
                }
            }
        }
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
        var handled = false
        if (payloads.contains("SELECTION_CHANGED")) {
            val group = getItem(position)
            if (holder is SingleViewHolder) {
                itemBinder.bindSingleSelectionState(holder, group)
            } else if (holder is MultiCamViewHolder) {
                itemBinder.bindMultiCamSelectionState(holder, group)
            }
            handled = true
        }
        if (payloads.contains("COLUMN_COUNT_CHANGED")) {
            val group = getItem(position)
            if (holder is SingleViewHolder) {
                val targetUri = resolveSingleTargetUri(group)
                itemBinder.bindSingleColumnMode(holder, group, targetUri)
                if (targetUri != null && (group.jpgUri != null || group.derivativeJpgUris.isNotEmpty())) {
                    val requestOptions = if (isWaterfallMode) {
                        RequestOptions().fitCenter().diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    } else {
                        RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    }
                    Glide.with(holder.itemView.context)
                        .load(targetUri)
                        .apply(requestOptions)
                        .into(holder.binding.thumbnailView)
                }
            }
            handled = true
        }
        if (handled) return
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val group = getItem(position)
        if (holder is SingleViewHolder) {
            bindSingleViewHolder(holder, group)
        } else if (holder is MultiCamViewHolder) {
            itemBinder.bindMultiCamViewHolder(holder, group)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (!adapterJob.isActive) {
            adapterJob = SupervisorJob()
            adapterScope = CoroutineScope(Dispatchers.IO + adapterJob)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterJob.cancel()
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        val lp = holder.itemView.layoutParams
        if (lp is androidx.recyclerview.widget.StaggeredGridLayoutManager.LayoutParams) {
            lp.isFullSpan = holder is MultiCamViewHolder
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        val ctx = holder.itemView.context
        val isSafe = ctx !is android.app.Activity || (!ctx.isDestroyed && !ctx.isFinishing)
        if (holder is SingleViewHolder) {
            holder.loadJob?.cancel()
            holder.loadJob = null
            if (isSafe) {
                Glide.with(holder.itemView).clear(holder.binding.thumbnailView)
            }
            holder.binding.thumbnailView.setImageDrawable(null)
        } else if (holder is MultiCamViewHolder) {
            if (isSafe) {
                Glide.with(holder.itemView).clear(holder.binding.thumbnail1)
                Glide.with(holder.itemView).clear(holder.binding.thumbnail2)
                Glide.with(holder.itemView).clear(holder.binding.thumbnail3)
            }
            holder.binding.thumbnail1.setImageDrawable(null)
            holder.binding.thumbnail2.setImageDrawable(null)
            holder.binding.thumbnail3.setImageDrawable(null)
        }
    }

    private fun resolveSingleTargetUri(group: ImageGroup): Uri? {
        return group.jpgUri ?: group.derivativeJpgUris.firstOrNull()
            ?: group.mp4VideoUri ?: group.derivativeMp4Uris.firstOrNull()
            ?: group.cinemaDngFirstFrameUri ?: group.dngUri ?: group.dngUri1 ?: group.dngUri2
    }

    private fun bindSingleViewHolder(holder: SingleViewHolder, group: ImageGroup) {
        holder.loadJob?.cancel()
        holder.loadJob = null

        val targetUri = resolveSingleTargetUri(group)

        if (targetUri != null) {
            val isDng = targetUri.toString().endsWith(".dng", ignoreCase = true) || group.dngUri == targetUri || group.cinemaDngFirstFrameUri == targetUri
            val isMp4 = targetUri.toString().endsWith(".mp4", ignoreCase = true) || (group.isMp4Video && group.rawVideoUri == null)

            if (isDng && group.jpgUri == null && group.derivativeJpgUris.isEmpty() && group.mp4VideoUri == null && group.derivativeMp4Uris.isEmpty()) {
                Glide.with(holder.itemView.context).clear(holder.binding.thumbnailView)
                holder.binding.thumbnailView.setImageDrawable(null)
                loadDngThumbnail(holder, group, targetUri)
            } else if (isMp4) {
                Glide.with(holder.itemView.context).clear(holder.binding.thumbnailView)
                holder.binding.thumbnailView.setImageDrawable(null)
                loadMp4Thumbnail(holder, group, targetUri)
            } else {
                val requestOptions = if (isWaterfallMode) {
                    RequestOptions().fitCenter().diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                } else {
                    RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                }
                Glide.with(holder.itemView.context)
                    .load(targetUri)
                    .apply(requestOptions)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(holder.binding.thumbnailView)
            }
        } else if (group.rawVideoUri != null) {
            Glide.with(holder.itemView.context).clear(holder.binding.thumbnailView)
            holder.binding.thumbnailView.setImageDrawable(null)
            loadRawVideoThumbnail(holder, group)
        } else {
            Glide.with(holder.itemView.context).clear(holder.binding.thumbnailView)
            holder.binding.thumbnailView.setImageDrawable(null)
        }

        itemBinder.bindSingleMetadataAndListeners(holder, group, targetUri)
    }

    private suspend fun postThumbToHolder(
        holder: SingleViewHolder,
        targetBaseName: String,
        cacheKey: String,
        bmp: Bitmap
    ) {
        rawThumbCache.put(cacheKey, bmp)
        withContext(Dispatchers.Main) {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                val currentGroup = differ.currentList.getOrNull(holder.bindingAdapterPosition)
                if (currentGroup?.baseName == targetBaseName) {
                    holder.binding.thumbnailView.setImageBitmap(bmp)
                }
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

        val appContext = holder.itemView.context.applicationContext
        val targetBaseName = group.baseName

        holder.loadJob = adapterScope.launch {
            val bmp = thumbnailSemaphore.withPermit {
                if (!isActive) return@withPermit null
                decodeRawVideoThumbBitmap(appContext, rawUri)
            }
            if (bmp != null) {
                postThumbToHolder(holder, targetBaseName, targetBaseName, bmp)
            }
        }
    }

    private fun decodeRawVideoThumbBitmap(context: Context, rawUri: Uri): Bitmap? {
        try {
            context.contentResolver.openFileDescriptor(rawUri, "r")?.use { pfd ->
                val handle = top.maary.darkbag.rawvideo.RawVideoNative.nativeOpenReaderFd(pfd.fd)
                if (handle == 0L) return null
                try {
                    val header = top.maary.darkbag.rawvideo.RawVideoNative.readHeader(handle)
                    if (header == null || header.width <= 0 || header.height <= 0) return null
                    val swapDims = header.orientation == 90 || header.orientation == 270
                    val thumbW = if (swapDims) (320 * header.height) / header.width else 320
                    val thumbH = if (swapDims) 320 else (320 * header.height) / header.width
                    val localBmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                    val directBuf = java.nio.ByteBuffer.allocateDirect(header.width * header.height * 2)
                    val read = top.maary.darkbag.rawvideo.RawVideoNative.nativeReadFrame(handle, 0, LongArray(3), directBuf)
                    if (read <= 0) return null

                    val targetLogIndex = if (header.activeLogName.isNotBlank() && header.activeLogName != "None") {
                        top.maary.darkbag.repository.PreferenceKeys.LOG_CURVES.indexOf(header.activeLogName).takeIf { it >= 0 } ?: -1
                    } else -1
                    val lutManager = top.maary.darkbag.utils.LutManager(context)
                    val lutPath = if (header.activeLutName.isNotBlank() && header.activeLutName != "None") {
                        java.io.File(lutManager.lutDir, header.activeLutName).takeIf { it.exists() }?.absolutePath
                    } else null

                    val debayered = top.maary.darkbag.rawvideo.RawVideoNative.nativeDebayerFrameToBitmap(
                        bayerBuffer = directBuf, width = header.width, height = header.height,
                        orientation = header.orientation, cfaPattern = header.cfaPattern,
                        whiteLevel = header.whiteLevel, blackLevel = header.blackLevel.firstOrNull() ?: 64f,
                        neutralPoint = header.neutralPoint, targetLog = targetLogIndex, lutPath = lutPath,
                        exposure = header.exposure, contrast = header.contrast, saturation = header.saturation,
                        outBitmap = localBmp
                    )
                    if (debayered) return localBmp
                } finally {
                    top.maary.darkbag.rawvideo.RawVideoNative.nativeCloseReader(handle)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DarkbagGalleryGridAdapter", "Failed to load raw video thumb: $rawUri", e)
        }
        return null
    }

    private fun loadMp4Thumbnail(holder: SingleViewHolder, group: ImageGroup, uri: Uri) {
        val cacheKey = "MP4_${group.baseName}"
        val cached = rawThumbCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            holder.binding.thumbnailView.setImageBitmap(cached)
            return
        }
        val appContext = holder.itemView.context.applicationContext
        val targetBaseName = group.baseName

        holder.loadJob = adapterScope.launch {
            var bmp: Bitmap? = null
            thumbnailSemaphore.withPermit {
                if (!isActive) return@withPermit
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    if (uri.scheme == "content") {
                        appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                            bmp = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } else {
                        retriever.setDataSource(uri.path)
                        bmp = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    retriever.release()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("DarkbagGalleryGridAdapter", "MediaMetadataRetriever failed for $uri", e)
                }
            }
            val decoded = bmp
            if (decoded != null) {
                postThumbToHolder(holder, targetBaseName, cacheKey, decoded)
            } else {
                withContext(Dispatchers.Main) {
                    if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION &&
                        differ.currentList.getOrNull(holder.bindingAdapterPosition)?.baseName == targetBaseName
                    ) {
                        val ctx = holder.itemView.context
                        val isSafe = ctx !is android.app.Activity || (!ctx.isDestroyed && !ctx.isFinishing)
                        if (isSafe) {
                            Glide.with(ctx)
                                .load(uri)
                                .apply(RequestOptions().frame(0).centerCrop().diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                                .into(holder.binding.thumbnailView)
                        }
                    }
                }
            }
        }
    }

    private fun loadDngThumbnail(holder: SingleViewHolder, group: ImageGroup, uri: Uri) {
        val cacheKey = "DNG_${group.baseName}"
        val cached = rawThumbCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            holder.binding.thumbnailView.setImageBitmap(cached)
            return
        }
        val appContext = holder.itemView.context.applicationContext
        val targetBaseName = group.baseName

        holder.loadJob = adapterScope.launch {
            val bmp = thumbnailSemaphore.withPermit {
                if (!isActive) return@withPermit null
                top.maary.darkbag.utils.GalleryThumbnailLoader.loadDngThumbnail(appContext, uri, reqWidth = 512, reqHeight = 512)
            }
            if (bmp != null) {
                postThumbToHolder(holder, targetBaseName, cacheKey, bmp)
            }
        }
    }

    class SingleViewHolder(val binding: ItemDarkbagGalleryGridBinding) : RecyclerView.ViewHolder(binding.root) {
        var loadJob: kotlinx.coroutines.Job? = null
    }
    class MultiCamViewHolder(val binding: ItemDarkbagGalleryMultiCamGroupBinding) : RecyclerView.ViewHolder(binding.root)
}
